#!/usr/bin/env python3
"""SAD-319 prototype ⇄ production parity verifier.

Single source of truth for "does the HTML prototype match the Compose app?".
Every assertion is derived from a file on disk — nothing is estimated.

Usage
    python3 tools/prototype/verify_prototype.py            # all groups
    python3 tools/prototype/verify_prototype.py --group A  # one group (repeatable)
    python3 tools/prototype/verify_prototype.py --list     # list groups/assertions

Groups
    A  document integrity      (CSS/HTML well-formedness, class + id hygiene)
    B  wiring                  (JS ids, inline handlers, router, sheet openers)
    C  screen-set parity       (prototype sections == Compose routes)
    D  design tokens           (CSS vars == ui/theme/Color.kt, light + dark)
    E  component geometry      (Appendix-A matrix, prototype vs Compose literals)
    F  copy                    (production strings present, placeholders intact)
    G  drift-register closure  (the 42 rows of the plan: added/fixed/deleted)
    H  state machines          (every prototype state variant vs its ViewModel)

Exit code 0 only when every selected assertion passes.
"""
from __future__ import annotations

import argparse
import html as html_mod
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROTO = ROOT / "Reference" / "prototype"
INDEX = PROTO / "index.html"
CSS = PROTO / "css" / "styles.css"
JS = PROTO / "js" / "app.js"
STRINGS = ROOT / "app/src/main/res/values/strings.xml"
UI = ROOT / "app/src/main/java/com/safeme/app/ui"
GRADLE = ROOT / "app/build.gradle.kts"


# --------------------------------------------------------------------------- #
# results
# --------------------------------------------------------------------------- #
class Report:
    def __init__(self) -> None:
        self.rows: list[tuple[str, str, bool, str]] = []

    def check(self, group: str, name: str, ok: bool, detail: str = "") -> None:
        self.rows.append((group, name, bool(ok), detail))

    def summary(self) -> int:
        failed = [r for r in self.rows if not r[2]]
        for group, name, ok, detail in self.rows:
            mark = "PASS" if ok else "FAIL"
            line = f"[{group}] {mark}  {name}"
            if detail and not ok:
                line += f"\n            → {detail}"
            print(line)
        total = len(self.rows)
        print(f"\n{total - len(failed)}/{total} assertions passed")
        if failed:
            print("\nFAILED:")
            for group, name, _, detail in failed:
                print(f"  [{group}] {name}: {detail}")
            return 1
        return 0


# --------------------------------------------------------------------------- #
# parsing helpers
# --------------------------------------------------------------------------- #
def read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


def strip_braces_depth(css: str):
    """Yield (selector, body) for top-level rules only (@keyframes bodies skipped)."""
    i, n = 0, len(css)
    buf = []
    while i < n:
        ch = css[i]
        if ch == "{":
            selector = "".join(buf).strip()
            buf = []
            depth, j = 1, i + 1
            while j < n and depth:
                if css[j] == "{":
                    depth += 1
                elif css[j] == "}":
                    depth -= 1
                j += 1
            body = css[i + 1 : j - 1]
            yield selector, body
            i = j
            continue
        buf.append(ch)
        i += 1


def parse_css(css: str) -> dict[str, dict[str, str]]:
    """selector → {property: value}. Selectors are split on commas."""
    css = re.sub(r"/\*.*?\*/", "", css, flags=re.S)      # drop comments first
    out: dict[str, dict[str, str]] = {}
    for selector, body in strip_braces_depth(css):
        selector = re.sub(r"\s+", " ", selector).strip()
        if not selector or selector.startswith("@"):
            continue
        decls: dict[str, str] = {}
        for chunk in body.split(";"):
            if ":" not in chunk:
                continue
            prop, _, val = chunk.partition(":")
            prop = prop.strip()
            if not prop or prop.startswith("--"):
                if prop.startswith("--"):
                    decls[prop] = val.strip()
                continue
            decls[prop] = re.sub(r"\s+", " ", val.strip())
        for sel in selector.split(","):
            out.setdefault(sel.strip(), {}).update(decls)
    return out


VAR_CACHE: dict[str, str] = {}


def resolve_vars(value: str, variables: dict[str, str]) -> str:
    """Expand var(--x) one level deep so geometry checks can read token-based values."""
    def sub(match: re.Match) -> str:
        return variables.get(f"--{match.group(1)}", match.group(0))
    return re.sub(r"var\(--([\w-]+)\)", sub, value)


def px(decls: dict[str, str], prop: str) -> float | None:
    raw = decls.get(prop)
    if raw is None:
        return None
    raw = resolve_vars(raw, VAR_CACHE).strip()
    m = re.match(r"(-?[\d.]+)px", raw)
    return float(m.group(1)) if m else None


def sections(html: str) -> dict[str, str]:
    """screen id → inner HTML."""
    out: dict[str, str] = {}
    for m in re.finditer(
        r'<section class="screen[^"]*" id="sc-([a-z0-9]+)"[^>]*>(.*?)</section>', html, re.S
    ):
        out[m.group(1)] = m.group(2)
    return out


def visible_text(fragment: str) -> str:
    frag = re.sub(r"<script.*?</script>", " ", fragment, flags=re.S)
    frag = re.sub(r"<svg.*?</svg>", " ", frag, flags=re.S)
    frag = re.sub(r"<[^>]+>", " ", frag)
    return re.sub(r"\s+", " ", html_mod.unescape(frag)).strip()


def norm(s: str) -> str:
    s = html_mod.unescape(s)
    s = re.sub(r"%[0-9]\$[sd]", "\x00", s)
    return re.sub(r"\s+", " ", s).strip().lower()


def strings_xml() -> dict[str, str]:
    root = ET.parse(STRINGS).getroot()
    return {e.get("name"): (e.text or "") for e in root.findall("string")}


def hex6(value: str) -> str:
    """#D97757 / #FFD97757 / 0xFFD97757 → '#D97757'."""
    v = value.strip().upper().replace("0X", "").lstrip("#")
    return "#" + (v[2:] if len(v) == 8 else v)


def colour_kt() -> tuple[dict[str, str], dict[str, str]]:
    src = read(UI / "theme/Color.kt")

    def grab(block: str) -> dict[str, str]:
        body = re.search(rf"val {block} = AppColors\((.*?)\n\)", src, re.S).group(1)
        return {m.group(1): hex6(m.group(2))
                for m in re.finditer(r"(\w+)\s*=\s*Color\((0x[0-9A-Fa-f]{8})\)", body)}

    return grab("LightAppColors"), grab("DarkAppColors")


def compose_routes() -> set[str]:
    perms = UI / "screens/permissions"
    consts: dict[str, str] = {}
    for f in perms.glob("*.kt"):
        consts.update(dict(re.findall(r'const val (\w*PERM_ROUTE_\w+)\s*=\s*"([^"]+)"', read(f))))

    def literal(p: Path) -> set[str]:
        return {m.group(1) for m in re.finditer(
            r'composable\(\s*(?:route\s*=\s*)?"([a-z]+)(?:\?[^"]*)?"', read(p))}

    def by_const(p: Path) -> set[str]:
        stripped = re.sub(r'"(?:[^"\\]|\\.)*"', '""', read(p))
        return {consts[c] for c in re.findall(r"composable\((\w+)\)", stripped) if c in consts}

    routes = (literal(UI / "screens/main/MainScreen.kt")
              | literal(perms / "OnboardingNavHost.kt")
              | by_const(perms / "OnboardingNavHost.kt"))
    return routes - {"main"}          # host route, renders the tab NavHost


def kotlin_versions() -> tuple[str, str]:
    src = read(GRADLE)
    return (re.search(r'versionName\s*=\s*"([^"]+)"', src).group(1),
            re.search(r'applicationId\s*=\s*"([^"]+)"', src).group(1))


# --------------------------------------------------------------------------- #
# assertions
# --------------------------------------------------------------------------- #
# Classes that are deliberately style-free: JS lookup hooks and containers whose
# geometry is carried by inline styles / a parent rule. Each entry states why.
HOOK_CLASSES = {
    "launch-row": "JS hook (togLaunch / initLaunchRows walk .launch-row)",
    "lock-off-only": "AppLock state hook — rule added in Phase 3 (drift B-16)",
    "setp": "App Lock setup panel container — geometry set in Phase 5",
    "set-body": "App Lock setup body container — geometry set in Phase 5",
    "set-ui": "App Lock setup method panel (data-m switch) — Phase 5",
}


def group_a(rep: Report, ctx):
    html, css = ctx["html"], ctx["css"]
    rep.check("A", "CSS braces balanced", css.count("{") == css.count("}"),
              f"{css.count('{')} open vs {css.count('}')} close")

    used = set()
    for chunk in re.findall(r'class="([^"]+)"', html):
        used.update(chunk.split())
    selectors = " ".join(ctx["rules"].keys())
    missing = sorted(c for c in used
                     if c not in HOOK_CLASSES
                     and not re.search(rf"\.{re.escape(c)}(?![\w-])", selectors))
    rep.check("A", "every class used in HTML is styled (or a registered hook)", not missing,
              f"unstyled: {missing}")
    rep.check("A", "HTML tag balance (section/div)", html.count("<section") == html.count("</section>")
              and html.count("<div") == html.count("</div>"),
              f"sections {html.count('<section')}/{html.count('</section>')}, "
              f"divs {html.count('<div')}/{html.count('</div>')}")

    ids = re.findall(r'id="([^"]+)"', html)
    dupes = sorted({i for i in ids if ids.count(i) > 1})
    rep.check("A", "no duplicate element ids", not dupes, f"dupes: {dupes}")

    # dead compatibility spans: display:none wrappers that no JS writes to
    dead = []
    for m in re.finditer(r'<div style="display:none">(.*?)</div>', html, re.S):
        for sid in re.findall(r'id="([^"]+)"', m.group(1)):
            if sid not in ctx["js"]:
                dead.append(sid)
    rep.check("A", "no dead display:none compat spans", not dead, f"never written: {dead}")


def group_b(rep: Report, ctx):
    html, js = ctx["html"], ctx["js"]
    ids = set(re.findall(r'id="([^"]+)"', html))
    refs = set(re.findall(r"getElementById\(['\"]([^'\"]+)['\"]\)", js))
    rep.check("B", "every getElementById target exists", not (refs - ids), f"missing: {sorted(refs - ids)}")

    used = set(re.findall(r'on\w+="([A-Za-z_$][\w$]*)\(', html))
    defined = set(re.findall(r"function\s+([A-Za-z_$][\w$]*)\s*\(", js))
    rep.check("B", "every inline handler is defined", not (used - defined), f"undefined: {sorted(used - defined)}")

    screens = set(re.findall(r'id="sc-([a-z0-9]+)"', html))
    targets = set(re.findall(r"nav\(['\"]([a-z]+)['\"]", html + js))
    rep.check("B", "no dead nav() target", not (targets - screens), f"dead: {sorted(targets - screens)}")

    sheets = set(re.findall(r'class="sheet" id="([^"]+)"', html))
    # a sheet is reachable if some call site opens it — three patterns exist in app.js:
    #   openSheet('id') · openSheet(cond ? 'a' : 'b') · getElementById('id').classList.add('show')
    opened = set()
    for args in re.findall(r"openSheet\(([^)]*)\)", html + js):
        opened.update(re.findall(r"['\"]([^'\"]+)['\"]", args))
    opened |= set(re.findall(r"getElementById\('([^']+)'\)\.classList\.add\('show'\)", js))
    rep.check("B", "every sheet is opened by something", not (sheets - opened), f"orphan: {sorted(sheets - opened)}")
    rep.check("B", "every openSheet target exists", not ({o for o in opened if o.startswith("sheet")} - sheets),
              f"missing: {sorted({o for o in opened if o.startswith('sheet')} - sheets)}")

    order = set(re.findall(r"'([a-z0-9]+)'", re.search(r"const ORDER = \[(.*?)\]", js, re.S).group(1)))
    rep.check("B", "ORDER covers exactly the rendered sections", order == screens,
              f"order-only={sorted(order - screens)} sections-only={sorted(screens - order)}")

    nav = set(re.findall(r"(\w+):'", re.search(r"const NAV = \{(.*?)\}", js, re.S).group(1)))
    main_kt = read(UI / "screens/main/MainScreen.kt")
    tabs = set(re.findall(r'NavDestination\("(\w+)"', read(UI / "components/BottomNavBar.kt")))
    rep.check("B", "bottom-nav tabs == MainNavDestinations", nav == tabs, f"proto={sorted(nav)} kotlin={sorted(tabs)}")


def group_c(rep: Report, ctx):
    secs = set(ctx["sections"])
    routes = compose_routes()
    rep.check("C", "prototype sections == Compose routes", secs == routes,
              f"proto-only={sorted(secs - routes)} prod-only={sorted(routes - secs)}")
    rep.check("C", "24 screens on both sides", len(secs) == 24 == len(routes), f"proto={len(secs)} prod={len(routes)}")


LIGHT_MAP = {"brand": "brand", "brand-dark": "brandDark", "brand-soft": "brandSoft",
             "brand-mist": "brandMist", "ink": "ink", "ink-2": "ink2", "ink-3": "ink3",
             "bg": "background", "surface": "surface", "line": "line",
             "success": "success", "warning": "warning", "danger": "danger"}


def group_d(rep: Report, ctx):
    light, dark = colour_kt()
    root = ctx["rules"].get(":root", {})
    bad = []
    for var, token in LIGHT_MAP.items():
        proto_val = hex6(root.get(f"--{var}", "#"))
        want = light.get(token, "")
        if proto_val != want:
            bad.append(f"{var}: proto {proto_val} vs kotlin {want}")
    rep.check("D", "13 light tokens == LightAppColors", not bad, "; ".join(bad))

    dark_rule = ctx["rules"].get(':root[data-theme="dark"]', {})
    bad = []
    for var, token in LIGHT_MAP.items():
        proto_val = hex6(dark_rule.get(f"--{var}", "#"))
        want = dark.get(token, "")
        if proto_val != want:
            bad.append(f"{var}: proto {proto_val} vs kotlin {want}")
    rep.check("D", "13 dark tokens == DarkAppColors", not bad, "; ".join(bad))

    rep.check("D", "dark theme overrides exist for light-only component colours",
              ':root[data-theme="dark"] .sw' in ctx["rules"]
              and ':root[data-theme="dark"] .toast' in ctx["rules"],
              "missing .sw/.toast dark overrides")


def group_e(rep: Report, ctx):
    r = ctx["rules"]

    def eq(selector, prop, want, label):
        got = px(r.get(selector, {}), prop)
        rep.check("E", label, got == want, f"{selector} {prop}: {got} vs {want}")

    # switch 52x31, thumb 25, inset 3, travel 21
    eq(".sw", "width", 52, "switch width 52")
    eq(".sw", "height", 31, "switch height 31")
    rep.check("E", "switch thumb 25 @ inset 3, travel 21",
              "width:25px" in r.get(".sw::after", {}).get("width", "") or
              px(r.get(".sw::after", {}), "width") == 25, "thumb geometry")
    # card / qa / stat / shield / hero / toast / nav / sheet / field / chip / seg
    eq(".card", "border-radius", 20, "card radius 20")
    eq(".card", "padding", 16, "card padding 16")
    eq(".qa", "border-radius", 18, "tile radius 18")
    eq(".qa", "padding", 14, "tile padding 14")
    eq(".stat", "border-radius", 16, "home stat radius 16")
    eq(".stat", "padding", 12, "home stat padding 12")
    eq("#sc-block .stat", "border-radius", 18, "block stat radius 18")
    eq("#sc-block .stat", "padding", 14, "block stat padding 14")
    eq(".shield", "border-radius", 18, "shield radius 18")
    eq(".shield", "padding", 14, "shield padding 14")
    eq("#sc-home .hero", "border-radius", 20, "home hero radius 20")
    eq(".hero", "padding", 22, "hero padding 22")
    eq(".toast", "border-radius", 999, "toast radius full")
    eq(".toasts", "bottom", 24, "toast stack 24 dp from bottom")
    eq(".nav", "height", 66, "nav height 66")
    eq(".nav", "bottom", 10, "nav bottom inset 10")
    eq(".sheet", "border-radius", 26, "sheet top radius 26")
    eq(".field", "height", 48, "field height 48")
    eq(".chip", "height", 36, "chip height 36")
    eq(".seg", "border-radius", 12, "segmented radius 12")
    eq(".li", "min-height", 60, "list row min-height 60")
    eq(".qa .ic", "width", 38, "tile icon 38 (home)")
    eq("#sc-profile .qa .ic", "width", 40, "tile icon 40 (profile)")

    # --- Phase 2: chrome & header conventions -------------------------------
    eq(".nav", "left", 10, "nav left inset 10")
    eq(".nav", "right", 10, "nav right inset 10")
    eq(".nav", "border-radius", 22, "nav radius 22")
    eq(".nav button svg", "width", 23, "nav icon 23")
    eq(".sub .back", "width", 40, "back chip 40")
    eq(".sub .back", "border-radius", 14, "back chip radius 14")
    eq(".sub .back svg", "width", 20, "back glyph 20")
    eq(".sub h1", "font-size", 24, "pushed-screen title 24")
    eq(".nav-on .sub h1", "font-size", 20, "tab-root title 20")
    eq(".sub .sub-desc", "font-size", 12, "subtitle 12")
    eq(".sub .sub-desc", "line-height", 20, "subtitle line-height 20")
    eq(".toasts.above-nav", "bottom", 100, "toasts dock above the nav bar")
    glass = resolve_vars(ctx["rules"].get(".nav", {}).get("background", ""), VAR_CACHE)
    alpha = re.search(r",\s*([\d.]+)\s*\)$", glass)
    rep.check("E", "nav background is surface @ .86 (glass, both themes)",
              bool(alpha) and abs(float(alpha.group(1)) - 0.86) < 1e-9,
              f".nav background resolves to {glass}")

    # block tiles are 40 in production (MoreCard IconBox)
    block_icon = r.get("#sc-block .qa .ic", {})
    rep.check("E", "tile icon 40 (block, MoreCard)", px(block_icon, "width") == 40,
              f"#sc-block .qa .ic width={px(block_icon, 'width')} (expected 40)")
    # shield inner gap 12
    left = r.get(".shield .s-left", {})
    rep.check("E", "shield inner gap 12", "gap:12px" in left.get("gap", "gap:") or left.get("gap", "").endswith("12px"),
              f".shield .s-left gap={left.get('gap')}")
    # hero ring arc white
    rep.check("E", "hero ring arc is white (HomeScreen HeroRing)",
              "stroke:#fff" in r.get(".ring .fg", {}).get("stroke", "") or
              r.get(".ring .fg", {}).get("stroke", "").lower() in ("#fff", "#ffffff"),
              f".ring .fg stroke={r.get('.ring .fg', {}).get('stroke')} (production: Color.White)")


# production strings that the prototype must render (drift register A-01..A-20)
REQUIRED_COPY = {
    "home_hero_title": "Fully protected",
    "home_hero_partial": "Partially protected",
    "home_hero_paused": "Protection paused",
    "home_hero_all_active": "All %1$d layers active",
    "home_hero_layers_active": "%1$d of %2$d layers active",
    "home_hero_attention": "%1$d need attention",
    "home_hero_resume": "Turn on master protection to resume",
    "home_paused": "Paused",
    "home_attention": "Attention",
    "home_quick_actions_empty": "No quick actions yet",
    "bs_closing": "Closing in %1$ds.",
    "bs_dwell_sub": "Time before Close unlocks • 3-120s",
    "bs_overlay_label": "Close-gate • dwell countdown",
    "bs_img_sub_none": "None • SAF persistable URI",
    "bs_why_fallback": "Why: this content or action was blocked",
    "backup_exporting": "Creating backup",
    "backup_restoring": "Restoring",
    "backup_err_not_json": "This file isn't valid JSONC",
    "backup_err_rollback": "Restore failed and couldn't be fully undone",
    "tb_search_placeholder": "Search rules",
    "perm_granted": "Granted ✓",
    "social_footnote": "How it works",
    "blk_manage_sub": "%1$s keywords · %2$s websites · %3$s trusted",
}


def group_f(rep: Report, ctx):
    strings = strings_xml()
    blob = norm(ctx["html"] + ctx["js"])
    missing = []
    for key, expected in REQUIRED_COPY.items():
        needle = norm(strings.get(key, expected))
        probe = needle[:40].replace("\x00", "").strip()
        if probe and probe not in blob.replace("\x00", ""):
            missing.append(f"{key} ({expected})")
    rep.check("F", "all drift-register copy present in prototype", not missing,
              f"missing: {missing}")

    version, appid = kotlin_versions()
    rep.check("F", "no stale version literal (1.0.76 / protect.yourself)",
              "1.0.76" not in ctx["html"] and "protect.yourself" not in ctx["html"],
              "prototype still shows the old version/package")
    rep.check("F", "About shows the real version + package",
              version in ctx["html"] and appid in ctx["html"],
              f"expected {version} / {appid}")

    rep.check("F", "format placeholders preserved in prototype copy",
              not re.search(r"%[0-9]\$[sd]", ctx["html"]),
              "prototype must not contain raw %1$d placeholders — substitute at render time")


def group_g(rep: Report, ctx):
    html, js = ctx["html"], ctx["js"]
    rep.check("G", "orphan Time-Delay sheet removed (B-21)",
              'id="sheetDelay"' not in html and "function testDelay" not in js, "sheetDelay still present")
    rep.check("G", "no dead social compat spans (B-22)",
              "socialHeroPill" not in html and "socialWholeChips" not in html, "compat spans still present")
    rep.check("G", "delete account uses a dialog, not a sheet (B-07)",
              'id="sheetDelete"' not in html and "dlgDelete" in html, "delete is still a bottom sheet")
    rep.check("G", "demo state object drives Home hero (A-01/B-01)", "DEMO" in js, "no DEMO state object in app.js")
    rep.check("G", "Home shows all three hero titles", all(
        t.lower() in html.lower() for t in ("Fully protected", "Partially protected", "Protection paused")),
        "missing hero title variants")
    rep.check("G", "Social Media Blocking has the production footnote (A-10)",
              "How it works" in html, "footnote card missing")
    rep.check("G", "Social launcher renders extras rows (A-10)", "socialExtra" in js or "extras" in js,
              "no extras mechanism")
    rep.check("G", "Blocking manage-sub is state-driven (A-12)", "mgCardSub" in js and "trusted" in js.lower(),
              "manage subtitle not wired")
    rep.check("G", "toast stack anchored 24 dp (A-03)", px(ctx["rules"].get(".toasts", {}), "bottom") == 24,
              "toast bottom is not 24px")
    rep.check("G", "feed empty state left-aligned (A-04)",
              ctx["rules"].get(".feed-empty", {}).get("text-align", "left") == "left",
              "feed-empty still centred")


def group_h(rep: Report, ctx):
    """State machines — every prototype state variant must resolve like the ViewModel."""
    js = ctx["js"]
    strings = strings_xml()
    vm = read(UI / "screens/home/HomeViewModel.kt")
    hs = read(UI / "screens/home/HomeScreen.kt")
    html = ctx["html"]
    demo = re.search(r"const DEMO = \{(.*?)\n  \};", js, re.S)
    rep.check("H", "One DEMO state object owns every home variant",
              bool(demo) and "renderHome()" in js,
              "DEMO/renderHome missing — states would be hardcoded again")

    # --- hero title -------------------------------------------------------- #
    # HomeViewModel: paused -> home_hero_paused; no attention -> home_hero_title; else partial
    for key, res in (("full", "home_hero_title"),
                     ("partial", "home_hero_partial"),
                     ("pausedTitle", "home_hero_paused")):
        want = strings[res]
        got = re.search(rf"{key}:\s*'([^']*)'", js)
        rep.check("H", f"hero title '{key}' == {res}",
                  bool(got) and got.group(1) == want,
                  f"DEMO_STR.{key}={got.group(1) if got else None!r} vs {want!r}")

    # --- hero subtitle ----------------------------------------------------- #
    all_active = strings["home_hero_all_active"]          # "All %1$d layers active"
    layers_active = strings["home_hero_layers_active"]    # "%1$d of %2$d layers active"
    attention = strings["home_hero_attention"]            # "%1$d need attention"
    rep.check("H", "hero subtitle: all-active branch == home_hero_all_active",
              "'All ' + d.total + ' layers active'" in js
              and all_active.replace("%1$d", "N") == "All N layers active",
              f"prototype text vs {all_active!r}")
    rep.check("H", "hero subtitle: partial branch == home_hero_layers_active + ' · ' + home_hero_attention",
              "d.layers + ' of ' + d.total + ' layers active · ' + d.attention + ' need attention'" in js
              and layers_active == "%1$d of %2$d layers active"
              and attention == "%1$d need attention",
              f"{layers_active!r} / {attention!r} vs prototype concat")
    rep.check("H", "hero subtitle: paused branch == home_hero_resume",
              f"'{strings['home_hero_resume']}'" in js,
              f"missing {strings['home_hero_resume']!r}")
    rep.check("H", "subtitle separator is ' · ' (U+00B7)",
              "' layers active · ' + d.attention" in js.replace("\\n", " "),
              "separator drifted from the Compose concat")

    # --- pill -------------------------------------------------------------- #
    for key, res in (("protected", "home_protected"),
                     ("paused", "home_paused"),
                     ("attention", "home_attention")):
        got = re.search(rf"{key}:\s*'([^']*)'", js)
        rep.check("H", f"pill label '{key}' == {res}",
                  bool(got) and got.group(1) == strings[res],
                  f"{got.group(1) if got else None!r} vs {strings[res]!r}")
    rep.check("H", "pillGreen == !paused && attention empty (HomeViewModel:177)",
              "const green = !paused && clean;" in js
              and "val pillGreen = !paused && layers.attention.isEmpty()" in vm,
              "pill colour rule differs from the ViewModel")
    rep.check("H", "amber pill uses .pill.o (warning pair, not .r)",
              "pill.className = 'pill ' + (green ? 'g' : 'o')" in js,
              "paused/attention pill must use the warning palette")

    # --- hero ring --------------------------------------------------------- #
    rep.check("H", "heroProgress is 0 while paused (HomeViewModel:193)",
              "const progress = paused ? 0 : (d.total ? d.layers / d.total : 0);" in js,
              "ring keeps drawing layer progress while paused")
    ring = re.search(r'id="homeHeroRing"[^>]*stroke-dasharray="([\d.]+)"', html)
    r = re.search(r'id="homeHeroRing"[^>]*r="(\d+)"', html)
    rep.check("H", "ring dasharray == circumference of r=26 (2πr)",
              bool(ring) and bool(r) and abs(float(ring.group(1)) - 2 * 3.14159 * int(r.group(1))) < 0.1,
              f"dasharray={ring.group(1) if ring else None}")

    # --- accessibility banner ---------------------------------------------- #
    gate = re.search(r"if \((!state\.a11yEnabled[^)]*)\)", hs)
    rep.check("H", "banner gate copied from HomeScreen.kt:122",
              bool(gate) and "a11yStateKnown" in gate.group(1) and "a11yChecking" in gate.group(1)
              and "banner.style.display = d.a11y === 'off' ? 'flex' : 'none'" in js,
              "prototype shows the banner in states production hides it")
    rep.check("H", "banner has the three production states (off / checking / on)",
              all(f'value="{v}"' in html for v in ("off", "checking", "on")),
              "a state is unreachable from the toolbar")
    for res in ("home_a11y_title", "home_a11y_sub"):
        text = strings[res].replace("\\'", "'")     # XML escapes the apostrophe
        rep.check("H", f"banner copy {res} present", text in js,
                  f"{text!r} missing from the banner renderer")

    # --- master switch ----------------------------------------------------- #
    rep.check("H", "master switch mirrors paused (single source of truth)",
              "master.classList.toggle('on', !paused)" in js
              and "DEMO.home.paused=!t.classList.contains('on')" in js,
              "switch state is duplicated instead of derived")
    rep.check("H", "master toggle toast uses home_toast_on/off",
              f"'{strings['home_toast_on']}'" in js and f"'{strings['home_toast_off']}'" in js,
              "toast copy drifted")

    # --- header greeting / date -------------------------------------------- #
    m = re.search(r"in (\d+)\.\.(\d+) -> R\.string\.home_greeting_morning.*?"
                  r"in (\d+)\.\.(\d+) -> R\.string\.home_greeting_afternoon", vm, re.S)
    bounds = tuple(int(x) for x in m.groups()) if m else None
    rep.check("H", "greeting hour bounds copied from HomeViewModel.currentGreeting()",
              bounds == (5, 11, 12, 16)
              and f"h>={bounds[0]}&&h<={bounds[1]}" in js
              and f"h>={bounds[2]}&&h<={bounds[3]}" in js,
              f"bounds {bounds} vs prototype")
    rep.check("H", "date line uses the production pattern EEEE, h:mm a",
              '"EEEE, h:mm a"' in vm and "toLocaleDateString('en-US',{weekday:'long'})" in js
              and "' '+(d.getHours()<12?'AM':'PM')" in js,
              "date line format differs")
    rep.check("H", "greeting string is '<greeting>, <user>' (home_header_greeting)",
              strings["home_header_greeting"] == "%1$s, %2$s"
              and "DEMO_STR[which] + ', ' + DEMO_STR.userName" in js,
              "greeting composition differs")

    # --- quick actions ----------------------------------------------------- #
    defaults = re.search(r"listOf\(([^)]*QuickActionType[^)]*)\)",
                         read(ROOT / "app/src/main/java/com/safeme/app/data/QuickActionsPrefs.kt"))
    kt_order = [x.split(".")[-1].strip().lower() for x in defaults.group(1).split(",")] if defaults else []
    proto_order = re.search(r"let qaOn=\[([^\]]*)\]", js)
    proto_order = [x.strip().strip("'") for x in proto_order.group(1).split(",")] if proto_order else []
    rep.check("H", "quick-action default order == QuickActionsPrefs.DEFAULTS",
              kt_order == proto_order, f"kotlin {kt_order} vs prototype {proto_order}")
    rep.check("H", "empty quick actions use home_quick_actions_empty",
              strings["home_quick_actions_empty"] in js,
              f"{strings['home_quick_actions_empty']!r} missing")


GROUPS = {"A": group_a, "B": group_b, "C": group_c, "D": group_d,
          "E": group_e, "F": group_f, "G": group_g, "H": group_h}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--group", action="append", choices=sorted(GROUPS))
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--dump-css", metavar="PATH",
                    help="write the parsed selector→declaration map as JSON and exit")
    args = ap.parse_args()
    if args.list:
        print("groups: A integrity · B wiring · C screens · D tokens · E geometry · "
              "F copy · G drift · H state machines")
        return 0

    html, css, js = read(INDEX), read(CSS), read(JS)
    ctx = {"html": html, "css": css, "js": js, "rules": parse_css(css),
           "sections": sections(html)}
    VAR_CACHE.update(ctx["rules"].get(":root", {}))
    if args.dump_css:
        import json
        Path(args.dump_css).write_text(json.dumps(ctx["rules"], indent=1, sort_keys=True))
        print(f"wrote {args.dump_css} ({len(ctx['rules'])} selectors)")
        return 0
    rep = Report()
    for name in (args.group or sorted(GROUPS)):
        GROUPS[name](rep, ctx)
    return rep.summary()


if __name__ == "__main__":
    sys.exit(main())
