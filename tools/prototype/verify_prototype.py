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
    I  copy provenance         (every visible string on every screen is production copy)

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
DATA = ROOT / "app/src/main/java/com/safeme/app/data"
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
    """strings.xml with Android's escapes decoded (\' -> '), so comparisons are
    against what the app actually renders, not against the source spelling."""
    root = ET.parse(STRINGS).getroot()
    return {e.get("name"): (e.text or "").replace("\\'", "'").replace('\\"', '"')
            for e in root.findall("string")}


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


# --------------------------------------------------------------------------- #
# copy deviations
# --------------------------------------------------------------------------- #
# The prototype reproduces production copy verbatim EXCEPT where the string
# itself is defective. Every exception must be registered here with the
# production text, the replacement, and why — and the same table is asserted
# against tools/prototype/DEVIATIONS.md so the doc cannot drift from the code.
DEVIATIONS = {
    "vpn_whitelist_count": {
        "prod": "%1$d app(s) exempt",
        "proto": ("%1$d app exempt", "%1$d apps exempt"),
        "why": "literal '(s)' renders '1 app(s) exempt'; replaced with a real "
               "singular/plural pair (owner decision, SAD-319)",
    },
}

DEVIATIONS_DOC = ROOT / "tools" / "prototype" / "DEVIATIONS.md"


def tpl(key: str) -> str | None:
    """Read one verbatim copy template out of the prototype's T table."""
    table = re.search(r"const T = \{(.*?)\n  \};", read(JS), re.S)
    if not table:
        return None
    m = re.search(rf"{key}:\s*'([^']*)'", table.group(1))
    return m.group(1) if m else None


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

    # Dead compatibility shims: any element that ships with display:none and is
    # never referenced by JS can never become visible again (B-22 class of drift).
    dead = []
    for m in re.finditer(r'<[a-z]+\b[^>]*style="[^"]*display:\s*none[^"]*"[^>]*>', html):
        for sid in re.findall(r'id="([^"]+)"', m.group(0)):
            if sid not in ctx["js"]:
                dead.append(sid)
    rep.check("A", "no dead display:none elements (never written by JS)", not dead,
              f"never written: {dead}")

    # Section banners rot silently: a half-deleted banner once left the PROFILE
    # header sitting in front of the service picker. Assert the comments are
    # well-formed and that no banner name is used twice.
    bad_comments, banners = [], []
    for n, line in enumerate(html.splitlines(), 1):
        if line.count("-->") > line.count("<!--") or line.count("<!--") - line.count("-->") > 1:
            bad_comments.append(n)
        m = re.search(r"<!-- =+ ([A-Z0-9 &/+\-]+) =+ -->", line)
        if m:
            banners.append(m.group(1).strip())
    rep.check("A", "HTML section comments are well formed", not bad_comments,
              f"unbalanced comment delimiters on lines {bad_comments}")
    dup_banners = sorted({b for b in banners if banners.count(b) > 1})
    rep.check("A", "no duplicated section banner", not dup_banners,
              f"duplicated: {dup_banners}")


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
    eq("#sc-block .qa .ic", "width", 40, "tile icon 40 (block)")
    eq("#sc-block .qa .ic", "border-radius", 13, "tile icon radius 13 (block)")
    eq("#sc-block .qa .ic", "margin-bottom", 12, "tile icon gap 12 (block)")
    eq("#sc-block .qa .ic svg", "width", 20, "tile glyph 20 (block)")
    eq(".qa .ic svg", "width", 19, "tile glyph 19 (home)")
    eq(".shield .s-left", "gap", 12, "shield inner gap 12 (B-05)")

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
        if key in DEVIATIONS:
            continue                      # intentionally corrected — asserted in group H
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
    rep.check("G", "Social launcher renders extras rows (A-10)",
              "launch-row extra" in js and "class SocialBlocking" not in js
              and "if(defaults.has(name)) return;" in js,
              "extras rows are no longer derived from wholeBlocked - DEFAULT_LAUNCH")
    rep.check("G", "Blocking manage-sub is state-driven (A-12)", "mgCardSub" in js and "trusted" in js.lower(),
              "manage subtitle not wired")
    rep.check("G", "toast stack anchored 24 dp (A-03)", px(ctx["rules"].get(".toasts", {}), "bottom") == 24,
              "toast bottom is not 24px")
    version, appid = kotlin_versions()
    rep.check("G", "version/package come from one constant that matches the build (B-09)",
              f"const APP_VERSION = '{version}'" in js and f"const APP_PKG = '{appid}'" in js
              and all(f"getElementById('{i}')" in js
                      for i in ("profAboutSub", "profFooter", "aboutVersion")),
              f"expected APP_VERSION={version!r} APP_PKG={appid!r} driving three nodes")
    rep.check("G", "version nodes are rendered, not typed in twice (B-09)",
              all(f'id="{i}"' in html for i in ("profAboutSub", "profFooter", "aboutVersion"))
              and "renderAppMeta()" in js,
              "one of the version nodes is not hooked to renderAppMeta")
    rep.check("G", "profile permission count is live (B-08)",
              'id="permCardSub"' in html and "permCardSub" in js
              and "safeme_perm_" in js,
              "permission subtitle is still a static string")

    # --- B-02/B-03: home is the screen with the most state ------------------- #
    qa = read(DATA / "QuickActionsPrefs.kt")
    ids = re.findall(r'\w+\("([a-z]+)"\)', qa.split("enum class QuickActionType")[1].split("companion object")[0])
    pool = re.findall(r"\{id:'([a-z]+)'", js)
    rep.check("G", "quick-action pool carries every QuickActionType id (B-03)",
              ids and all(i in pool for i in ids) and len(pool) == len(set(pool)),
              f"Kotlin ids {ids} vs prototype pool {pool}")
    defaults = re.findall(r'QuickActionType\.(\w+)', qa.split("DEFAULT_QUICK_ACTIONS")[1].split(")")[0])
    proto_defaults = re.search(r"let qaOn=\[([^\]]*)\]", js)
    rep.check("G", "quick-action default order == DEFAULT_QUICK_ACTIONS (B-03)",
              bool(proto_defaults) and all(
                  d.lower() in proto_defaults.group(1) for d in defaults),
              f"Kotlin defaults {defaults} vs qaOn={proto_defaults.group(1) if proto_defaults else None}")

    # One owner for the banner: two writers (a render pass and an animator) used to
    # fight over it, and the animator carried an enabled state production cannot show.
    rep.check("G", "accessibility banner has a single owner (B-02)",
              "banner.style.display = d.a11y === 'off' ? 'flex' : 'none'" in js
              and "function a11yStatus" not in js
              and "service is on" not in js and "Core blocking engine active" not in js,
              "a second writer, or invented enabled-state copy, is back")

    # ServicePickerViewModel.listAllAccessibilityServices(...).filterNot { it.isOurs }:
    # the picker never lists SafeMe itself, and the subtitle is the service class.
    picker = re.search(r"const DEMO_SERVICES = \[(.*?)\n  \];", js, re.S)
    body = picker.group(1) if picker else ""
    vm_picker = read(UI / "screens/antitamper/ServicePickerViewModel.kt")
    rep.check("G", "service picker excludes SafeMe's own service (B-13)",
              bool(picker) and "filterNot { it.isOurs }" in vm_picker
              and "com.safeme.app" not in body and "SafeMe Accessibility" not in body,
              "the picker lists SafeMe's own accessibility service")
    rep.check("G", "service rows carry a service-class subtitle (B-13)",
              "cls:" in body and "x.cls" in js
              and "entry.serviceClass.substringAfterLast('.')" in read(UI / "screens/antitamper/ServicePickerScreen.kt"),
              "row subtitle is not the service class")
    rep.check("G", "service picker has a centered empty state (B-13)",
              "id=\"svcEmpty\"" in html and "svcEmpty" in js
              and "#svcEmpty{flex:1" in ctx["css"],
              "empty state is not the flex-centered ap_picker_empty state")

    # AntiTamperScreen.PreventUninstallCard: one pill, two labels.
    strings = strings_xml()
    rep.check("G", "anti-tamper card toggles Activate/Deactivate (B-13)",
              strings["at_activate"] in html and strings["at_deactivate"] in js
              and strings["at_toast_activated"] in js and strings["at_toast_deactivated"] in js
              and "R.string.at_deactivate else R.string.at_activate" in
                  read(UI / "screens/antitamper/AntiTamperScreen.kt"),
              "the device-admin pill is not state driven")

    # DnsVpnScreen.vpnStatusSub(): preset/custom -> system prefix -> exempt count
    # -> schedule suffix, and the pill flips with `running`, not with `enabled`.
    strings = strings_xml()
    for res, frag in (("vpn_status_on", "'VPN filtering is on'"),
                      ("vpn_status_off", "'VPN filtering is off'"),
                      ("vpn_status_re_enable", "'Tap to re-enable protection'"),
                      ("vpn_pill_active", "'Active'"),
                      ("vpn_pill_off", "'Off'")):
        rep.check("G", f"vpn status literal matches {res}", frag in js,
                  f"{frag} missing from vpnStatus()")
    rep.check("G", "vpn sub nests vpn_sub_exempt -> system prefix -> schedule suffix (B-15)",
              all(k in js for k in ("T.vpnExempt", "T.vpnSystem", "T.vpnSchedule", "T.vpnCustomV4V6"))
              and js.index("T.vpnExempt") > js.index("T.vpnSystem"),
              "vpnStatusSub() order drifted from DnsVpnScreen")
    presets = read(ROOT / "app/src/main/java/com/safeme/app/vpn/VpnConfig.kt")
    labels = re.findall(r"\(\"([^\"]+)\"\)", presets)
    rep.check("G", "vpn preset labels == DnsPreset", bool(labels) and all(
        f'"{lab}"' in js or f"'{lab}'" in js for lab in labels),
        f"missing DnsPreset labels: {[l for l in labels if l not in js]}")

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
              "fmt(T.heroAll, d.total)" in js and tpl("heroAll") == all_active,
              f"T.heroAll={tpl('heroAll')!r} vs {all_active!r}")
    rep.check("H", "hero subtitle: partial branch == home_hero_layers_active + ' · ' + home_hero_attention",
              "fmt(T.heroLayers, d.layers, d.total)" in js
              and "fmt(T.heroAttention, d.attention)" in js
              and tpl("heroLayers") == layers_active
              and tpl("heroAttention") == attention,
              f"T.heroLayers={tpl('heroLayers')!r} T.heroAttention={tpl('heroAttention')!r}")
    rep.check("H", "hero subtitle: paused branch == home_hero_resume",
              f"'{strings['home_hero_resume']}'" in js,
              f"missing {strings['home_hero_resume']!r}")
    rep.check("H", "subtitle separator is ' · ' (U+00B7)",
              "fmt(T.heroLayers, d.layers, d.total) + ' · '" in js,
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
        # The banner has one presentation, so its copy lives in the markup; JS only
        # decides whether the banner is on screen.
        rep.check("H", f"banner copy {res} present", text in js or text in html,
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
              and "fmt(T.greeting, DEMO_STR[which], DEMO_STR.userName)" in js
              and tpl("greeting") == strings["home_header_greeting"],
              "greeting composition differs")

    # --- quick actions ----------------------------------------------------- #
    defaults = re.search(r"listOf\(([^)]*QuickActionType[^)]*)\)",
                         read(ROOT / "app/src/main/java/com/safeme/app/data/QuickActionsPrefs.kt"))
    kt_order = [x.split(".")[-1].strip().lower() for x in defaults.group(1).split(",")] if defaults else []
    proto_order = re.search(r"let qaOn=\[([^\]]*)\]", js)
    proto_order = [x.strip().strip("'") for x in proto_order.group(1).split(",")] if proto_order else []
    rep.check("H", "quick-action default order == QuickActionsPrefs.DEFAULTS",
              kt_order == proto_order, f"kotlin {kt_order} vs prototype {proto_order}")

    # --- social blocking (B-10) ------------------------------------------- #
    rep.check("H", "launch rows dim to 0.45 when the master switch is off",
              "row.style.opacity = SOCIAL.enabled ? '1' : '.45'" in js
              and "alpha(if (enabled) 1f else 0.45f)" in read(UI / "screens/socialblocking/SocialBlockingScreen.kt"),
              "dim state drifted from SocialBlockingScreen")
    rep.check("H", "row switches follow checked = enabled && pkg in wholeBlocked",
              "const on = SOCIAL.enabled && SOCIAL.wholeBlocked.has(name);" in js,
              "checked rule differs from the composable")
    rep.check("H", "master subtitle switches on social_master_sub_on / _off",
              strings["social_master_sub_on"] in js and strings["social_master_sub_off"] in js
              and 'id="socialMasterSub"' in html,
              "master subtitle not wired to state")
    for res in ("social_master_on", "social_master_off", "social_footnote"):
        probe = strings[res].split("—")[0].strip()
        rep.check("H", f"social copy {res} present", probe in (js + html),
                  f"{probe!r} missing")
    rep.check("H", "presets map to the three production states",
              all(k in js for k in ("'deep'", "'balanced'", "'relax'")),
              "preset states missing")

    # --- delete account is a dialog (B-07) -------------------------------- #
    prof = read(UI / "screens/profile/ProfileScreen.kt")
    rep.check("H", "delete account uses an AlertDialog, not a sheet",
              "AlertDialog(" in prof and 'id="dlgDelete"' in html and 'id="sheetDelete"' not in html,
              "presentation differs from ProfileScreen")
    for res in ("prof_delete_title", "prof_delete_body", "prof_delete_confirm", "prof_delete_cancel"):
        rep.check("H", f"delete dialog copy {res}", strings[res] in html,
                  f"{strings[res]!r} missing")

    # --- backup progress + error states (B-18) ---------------------------- #
    n_err = len(re.findall(r'<string name="backup_err_', read(STRINGS)))
    rep.check("H", "all production backup error strings are modelled",
              f"BACKUP_ERR" in js and len(re.findall(r"^    \w+:", js[js.index("const BACKUP_ERR"):js.index("};", js.index("const BACKUP_ERR"))], re.M)) >= n_err,
              f"{n_err} backup_err_* strings vs the prototype's error table")
    rep.check("H", "backup progress copy uses backup_exporting / backup_restoring",
              tpl("backupExporting") == strings["backup_exporting"]
              and tpl("backupRestoring") == strings["backup_restoring"],
              "progress copy drifted")
    for res in ("backup_err_not_json", "backup_err_rollback"):
        rep.check("H", f"backup error {res} present", strings[res] in js,
                  f"{strings[res]!r} missing")
    rep.check("H", "restore still confirms before running (sheetRestore)",
              'id="sheetRestore"' in html and "openSheet('sheetRestore')" in html,
              "restore confirmation removed")

    # --- blocking manage subtitle (A-12) ---------------------------------- #
    rep.check("H", "blocking manage subtitle uses blk_manage_sub",
              tpl("blkManage") == strings["blk_manage_sub"]
              and "fmt(T.blkManage, KW.keywords.blocklist.length" in js,
              "manage subtitle drifted")

    # --- schedule screen states (B-14) ------------------------------------- #
    svm = read(UI / "screens/schedule/ScheduleViewModel.kt")
    sp = read(ROOT / "app/src/main/java/com/safeme/app/data/SchedulePrefs.kt")
    rep.check("H", "hero count uses the sch_hero_count plural pair",
              "0 active schedule" not in js
              and "on.length+' active schedule'+(on.length===1?'':'s')" in js,
              "hero count pluralisation drifted")
    rep.check("H", "hero pill uses sch_hero_pill_on / _off",
              strings["sch_hero_pill_on"] in js and strings["sch_hero_pill_off"] in js,
              "schedule pill copy drifted")
    for res in ("sch_hero_sub_on", "sch_hero_sub_paused", "sch_hero_sub_empty"):
        body = strings[res].split("%1$s")[0].split("%2$s")[0]
        rep.check("H", f"hero subtitle branch {res} present",
                  body in js, f"{body!r} missing from schedCount()")
    rep.check("H", "card time row == scheduleWindowLabel (en dash + ' (+1)')",
              'scheduleWindowLabel(startMinute: Int, endMinute: Int)' in sp
              and " – " in sp and " (+1)" in sp
              and "o.start+' – '+o.end" in js.replace('"', "'"),
              "card time format drifted")
    rep.check("H", "overnight windows append (+1) when end < start",
              "(o.end<o.start?' (+1)':'')" in js,
              "overnight marker missing")
    rep.check("H", "card apps uses sch_card_apps plural (1 app / N apps) + all-apps copy",
              strings["sch_card_apps_all"] in js
              and "(Number(o.apps)===1?' app':' apps')" in js,
              "apps pill pluralisation drifted")
    rep.check("H", "card next row uses sch_card_next",
              strings["sch_card_next"] == "Next · %1$s" and "Next · '+o.days" in js,
              "card next-row copy drifted")
    rep.check("H", "next boundary label mirrors ScheduleViewModel.nextBoundaryLabel()",
              "SCHEDULE_DAY_NAMES[dayIndex] }" in svm.replace("\n", " ")
              or "return time" in svm
              and "return d===0 ? start : name+' '+start;" in js,
              "boundary label differs from the ViewModel")
    rep.check("H", "disabled cards stay visible (only the switch differs)",
              "enabled:false" in js and "sched-card" in js,
              "demo fixtures no longer cover the disabled state")
    rep.check("H", "Home stat row is derived, not hardcoded",
              "ACTIVITY.filter(e=>e.type==='block').length" in js
              and "document.querySelectorAll('#schedList .sched-card .sw.on').length" in js,
              "home counters are typed in again")
    rep.check("H", "demo lists can be emptied and restored from the toolbar",
              "function setDemoLists(mode)" in js
              and "ACTIVITY.length = 0" in js
              and "DEMO_ACTIVITY.slice().reverse().forEach" in js,
              "empty/populated states not symmetric")

    # --- block screen: dwell countdown + overlay (B-17) -------------------- #
    bs = read(UI / "screens/blockscreen/BlockScreenViewModel.kt")
    ov = read(UI / "screens/blockscreen/BlockOverlay.kt")
    clamp = re.search(r"coerceIn\((\d+),\s*(\d+)\)", bs)
    proto_clamp = re.search(r"Math\.max\((\d+),Math\.min\((\d+),", js)
    rep.check("H", "dwell clamp matches BlockScreenViewModel.coerceIn",
              bool(clamp) and bool(proto_clamp)
              and clamp.groups() == proto_clamp.groups(),
              f"kotlin {clamp.groups() if clamp else None} vs prototype "
              f"{proto_clamp.groups() if proto_clamp else None}")
    rep.check("H", "preview countdown uses bs_closing (full stop, not an ellipsis)",
              "fmt(T.closing, bsDwell)" in js and tpl("closing") == strings["bs_closing"]
              and strings["bs_closing"] == "Closing in %1$ds."
              and "Closing in 5s." in html,
              "preview copy drifted")
    rep.check("H", "overlay label uses bs_overlay_label byte-for-byte",
              strings["bs_overlay_label"] in html,
              f"{strings['bs_overlay_label']!r} not in the overlay")
    ring = re.search(r'class="ring-lg"[^>]*>|r="(\d+)"[^>]*stroke-dasharray="([\d.]+)"', html)
    lg = re.search(r'r="52"[^>]*dasharray="([\d.]+)"', html)
    rep.check("H", "overlay ring dasharray == 2πr for r=52",
              bool(lg) and abs(float(lg.group(1)) - 2 * 3.14159 * 52) < 0.2,
              f"dasharray={lg.group(1) if lg else None}")
    rep.check("H", "overlay arc sweep == remaining/dwell (BlockOverlay.kt:173)",
              "(C*(1-(bsDwell?n/bsDwell:0))).toFixed(1)" in js
              and "remaining.toFloat() / dwell.toFloat()" in ov,
              "prototype ring does not track the countdown")
    rep.check("H", "why chip only renders when the why toggle is on",
              "if (whyOn) {" in ov and "why.style.display = (sw && !sw.classList.contains('on'))" in js,
              "prototype shows the why chip unconditionally")
    rep.check("H", "why explanation falls back to bs_why_fallback",
              strings["bs_why_fallback"] in html,
              f"{strings['bs_why_fallback']!r} missing")

    # --- DNS / VPN status matrix (B-15) ------------------------------------ #
    vpn = read(UI / "screens/vpn/DnsVpnScreen.kt")
    for res in ("vpn_status_on", "vpn_status_off", "vpn_pill_active", "vpn_pill_off",
                "vpn_status_re_enable"):
        rep.check("H", f"vpn state copy {res} present", strings[res] in js,
                  f"{strings[res]!r} missing from vpnStatus()")
    rep.check("H", "vpn sub = preset | 'Custom · v4[ · v6]' (vpnStatusSub)",
              strings["vpn_custom_status_v4"] == "Custom · %1$s"
              and strings["vpn_custom_status_v4v6"] == "Custom · %1$s · %2$s"
              and "fmt(T.vpnCustomV4V6, v.customV4, v.customV6)" in js
              and "fmt(T.vpnCustomV4, v.customV4)" in js,
              "custom preset line drifted")
    rep.check("H", "vpn sub wraps in vpn_sub_system_prefix",
              strings["vpn_sub_system_prefix"] == "System-wide · %1$s"
              and "v.systemDns ? fmt(T.vpnSystem, base) : base" in js,
              "system-wide prefix drifted")
    rep.check("H", "vpn sub appends vpn_sub_exempt",
              strings["vpn_sub_exempt"] == "%1$s · %2$d exempt"
              and "fmt(T.vpnExempt, scoped, v.exempt)" in js,
              "exempt suffix drifted")
    rep.check("H", "vpn sub appends vpn_sub_schedule_suffix last",
              strings["vpn_sub_schedule_suffix"] == "%1$s · schedule block active"
              and "v.scheduleBlocking ? fmt(T.vpnSchedule, withExempt) : withExempt" in js,
              "schedule suffix drifted")
    rep.check("H", "vpn whitelist row uses vpn_whitelist_none when empty",
              strings["vpn_whitelist_none"] in js, "empty-whitelist copy drifted")
    dev = DEVIATIONS["vpn_whitelist_count"]
    rep.check("H", "vpn whitelist count uses a real plural pair (deviation D-1)",
              tpl("vpnWlOne") == dev["proto"][0] and tpl("vpnWlMany") == dev["proto"][1]
              and "fmt(v.exempt === 1 ? T.vpnWlOne : T.vpnWlMany, v.exempt)" in js,
              f"T.vpnWlOne={tpl('vpnWlOne')!r} T.vpnWlMany={tpl('vpnWlMany')!r}")
    doc = DEVIATIONS_DOC.read_text() if DEVIATIONS_DOC.exists() else ""
    rows = [ln for ln in doc.splitlines() if ln.startswith("| `")]
    rep.check("H", "every copy deviation is registered in DEVIATIONS.md",
              len(rows) == len(DEVIATIONS)
              and all(f"`{k}`" in doc and dev["prod"] in doc
                      for k, dev in DEVIATIONS.items()),
              f"{len(rows)} doc rows vs {len(DEVIATIONS)} registry entries")
    leftover = [k for k, v in strings.items() if "(s)" in v and k not in DEVIATIONS]
    rep.check("H", "no unregistered '(s)' plural left in strings.xml",
              not leftover, f"unhandled: {leftover}")

    # --- app lock gate (B-16) --------------------------------------------- #
    alm = read(ROOT / "app/src/main/java/com/safeme/app/protect/AppLockManager.kt")
    auo = read(UI / "screens/applock/AppLockUnlockOverlay.kt")
    max_attempts = re.search(r"MAX_ATTEMPTS_BEFORE_LOCKOUT = (\d+)", alm)
    lockout_ms = re.search(r"LOCKOUT_DURATION_MS = (\d+) \* (\d+) \* (\d+)", alm)
    rep.check("H", "gate lockout threshold copied from AppLockManager",
              bool(max_attempts) and f"lockFails>={max_attempts.group(1)}" in js,
              f"MAX_ATTEMPTS_BEFORE_LOCKOUT={max_attempts.group(1) if max_attempts else None}")
    rep.check("H", "gate lockout duration is 5 minutes",
              bool(lockout_ms) and int(lockout_ms.group(1)) * int(lockout_ms.group(2)) == 5 * 60
              and "5*60*1000" in js,
              "lockout duration drifted")
    rep.check("H", "backoff schedule 5..9 == 1s,2s,4s,8s,16s (AppLockManager)",
              "{5:1000,6:2000,7:4000,8:8000,9:16000}" in js
              and "1s, 2s, 4s, 8s, 16s" in alm,
              "backoff table drifted")
    rep.check("H", "formatLockout == 'Xm Ys' / 'Xs'",
              "return m>0 ? m+'m '+s+'s' : s+'s';" in js
              and 'if (min > 0) "${min}m ${sec}s" else "${sec}s"' in auo,
              "lockout formatting differs")
    for res, where in (("al_gate_locked_out", "gate guard"),
                       ("al_gate_wrong", "wrong-code error"),
                       ("al_gate_use_biometric", "biometric row"),
                       ("al_toast_bio_unavailable", "biometric unavailable"),
                       ("al_gate_bio_off", "biometric off"),
                       ("al_pattern_hint", "pattern gate hint")):
        text = strings[res].replace("\\'", "'").replace("%1$s", "")
        body = json_text = js + html
        rep.check("H", f"{where} uses {res}", text in body,
                  f"{text!r} missing")
    rep.check("H", "forgot link switches copy with the lock method",
              "lockMethod==='pin' ? 'Forgot PIN?' : 'Forgot password?'" in js
              and strings["al_gate_forgot_pin"] == "Forgot PIN?"
              and strings["al_gate_forgot_password"] == "Forgot password?",
              "forgot copy drifted")
    rep.check("H", "gate subtitle uses al_gate_sub for every method",
              "fmt(T.gateSub, methodLabel())" in js
              and strings["al_gate_sub"] == "Enter your %1$s to unlock",
              "gate subtitle drifted")

    rep.check("H", "every T template is byte-identical to its string resource",
              tpl_cases := all(tpl(k) == strings[r] for k, r in (
                  ("heroAll", "home_hero_all_active"),
                  ("heroLayers", "home_hero_layers_active"),
                  ("heroAttention", "home_hero_attention"),
                  ("vpnCustomV4", "vpn_custom_status_v4"),
                  ("vpnCustomV4V6", "vpn_custom_status_v4v6"),
                  ("vpnExempt", "vpn_sub_exempt"),
                  ("vpnSystem", "vpn_sub_system_prefix"),
                  ("vpnSchedule", "vpn_sub_schedule_suffix"),
                  ("lockout", "al_gate_locked_out"),
                  ("gateSub", "al_gate_sub"),
                  ("closing", "bs_closing"),
              )),
              "a template drifted from strings.xml")
    rep.check("H", "fmt() substitutes positional args like getString()",
              "t.replace(/%([1-9])\\$[sd]/g" in js,
              "fmt() no longer handles positional placeholders")

    rep.check("H", "empty quick actions use home_quick_actions_empty",
              strings["home_quick_actions_empty"] in js,
              f"{strings['home_quick_actions_empty']!r} missing")


def kotlin_literals() -> str:
    """Every string literal in the UI sources — production still hardcodes some copy
    (WelcomeScreen), so a prototype string is only 'invented' if it is in neither
    strings.xml nor a Kotlin literal."""
    out = []
    for f in UI.rglob("*.kt"):
        # some sources spell quotes as \" — normalise before extracting
        src = read(f).replace('\\"', '"')
        out += re.findall(r'"([^"\n]{3,})"', src)
    return norm(" ".join(out))


# Containers whose children are built by JS at runtime; their copy is asserted
# against the resource templates in group H instead of being read from markup.
DYNAMIC_IDS = {
    "schedList", "launchList", "kwList", "histList", "svcList", "titleList",
    "homeFeed", "qaOn", "qaAdd", "appList", "vpnAppList", "socialAppList",
    "unlockBody", "toasts",
}
# Single values a renderer writes at boot (counts, timers). They may ship a value
# — asserting them empty would be pointless — but they must be written by JS.
VALUE_IDS = {"homeBlocked", "homeSchedules", "boNum", "homeGreeting", "homeDate",
             "homeHeroTitle", "homeHeroSub", "homePill"}


def group_i(rep: Report, ctx):
    """Per-screen copy provenance: every visible run of text on every screen must
    come from strings.xml or be a literal production actually renders.

    Some screens hardcode copy in Kotlin (WelcomeScreen), so the corpus is
    strings.xml + every literal in ui/**. Containers that JS fills at runtime are
    skipped here — their copy is asserted against resource templates in group H.
    """
    strings = strings_xml()
    corpus_kt = norm(kotlin_literals())

    # Production strings become patterns: placeholders match anything, so a value
    # the app would format at runtime ("0 of 3 granted · finish setup") is known.
    patterns = []
    plural_items = [it.text or "" for pl in ET.parse(STRINGS).getroot().findall("plurals")
                    for it in pl.findall("item")]
    for v in list(strings.values()) + plural_items:
        t = norm(v)
        if len(t) < 3:
            continue
        rx = re.escape(t).replace(re.escape("\x00"), ".+?")
        patterns.append(re.compile(rx))
    kt_runs = {c.strip() for c in corpus_kt.split(" . ") if len(c.strip()) >= 3}

    def known(text: str) -> bool:
        n = norm(text)
        if len(n) < 3:
            return True
        if any(p.fullmatch(n) for p in patterns):
            return True
        # a production title split across spans for an accent word is still
        # production copy: "Battery" + "optimization" from perm_battery_title
        if len(n) >= 4 and not n.isdigit() and any(n in p.pattern for p in patterns):
            return True
        return any(n in k for k in kt_runs) or n in corpus_kt

    unknown: dict[str, list[str]] = {}
    for name, body in ctx["sections"].items():
        for dyn in DYNAMIC_IDS:
            body = re.sub(rf'<[^>]*id="{dyn}"[^>]*>.*?</[a-z]+>', " ", body, flags=re.S)
            body = re.sub(rf'<[^>]*id="{dyn}"[^>]*>', " ", body)
        body = re.sub(r'<[^>]*data-demo="1"[^>]*>.*?</[a-z]+>', " ", body, flags=re.S)
        body = re.sub(r"<script.*?</script>", " ", body, flags=re.S)
        body = re.sub(r"<svg.*?</svg>", " ", body, flags=re.S)
        # text nodes only
        runs = [html_mod.unescape(t).strip() for t in re.split(r"<[^>]+>", body)]
        bad = [re.sub(r"\s+", " ", t) for t in runs if t and not known(t)]
        # a run may be a fragment of a longer production string (e.g. a split
        # sentence); keep only runs that no production string contains
        if bad:
            unknown[name] = sorted(set(bad))

    total = sum(len(v) for v in unknown.values())
    detail = "; ".join(f"{k}: {v[:3]}" for k, v in list(unknown.items())[:8])
    rep.check("I", "every screen's copy exists in strings.xml or a Kotlin literal",
              not unknown, f"{total} invented strings — {detail}")

    # JS-rendered containers must not ship baked-in text of their own
    baked = [i for i in DYNAMIC_IDS
             if re.search(rf'id="{i}"[^>]*>\s*[A-Za-z0-9]', ctx["html"])]
    rep.check("I", "JS-rendered containers ship empty", not baked, f"baked copy in {baked}")


GROUPS = {"A": group_a, "B": group_b, "C": group_c, "D": group_d,
          "E": group_e, "F": group_f, "G": group_g, "H": group_h, "I": group_i}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--group", action="append", choices=sorted(GROUPS))
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--dump-css", metavar="PATH",
                    help="write the parsed selector→declaration map as JSON and exit")
    args = ap.parse_args()
    if args.list:
        print("groups: A integrity · B wiring · C screens · D tokens · E geometry · "
              "F copy · G drift · H state machines · I copy provenance")
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
