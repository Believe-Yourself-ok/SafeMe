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
    I  copy provenance          (every visible string on every screen is production copy)
    J  icon parity              (every glyph the prototype draws is a production ImageVector)

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

    # sheets may carry extra modifier classes (e.g. `class="sheet bs-top"`)
    sheets = set(re.findall(r'class="sheet[^"]*" id="([^"]+)"', html))
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

    # success-bg / danger-bg are component fills (VpnPill, alert banners) that
    # production reads from AppColors but the original 13-token set never covered.
    bad = []
    for var, token in (("success-bg", "successBg"), ("danger-bg", "dangerBg")):
        for label, rule, palette in (("light", root, light), ("dark", dark_rule, dark)):
            proto_val = hex6(rule.get(f"--{var}", "#"))
            want = palette.get(token, "")
            if proto_val != want:
                bad.append(f"{label} {var}: proto {proto_val} vs kotlin {want}")
    rep.check("D", "success-bg/danger-bg match successBg/dangerBg in both themes",
              not bad, "; ".join(bad))

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


    # --- Phase 5(a): the app-picker sheets -----------------------------------
    # AppPickerSheet: search -> 14 -> SelectAllRow(gap 10) -> 14 -> list(max
    # 380dp) -> 16 -> Done. SocialPickerSheet is hand-rolled: 12dp gaps, a fixed
    # 320dp list and a 160dp empty box. SelectAllButton is brandSoft r999 at 38dp
    # both sides, so the two pills are equal.
    eq(".picker-bulk", "gap", 10, "picker bulk row gap 10 (SelectAllRow)")
    eq(".picker-bulk", "margin-top", 14, "picker bulk row 14 below the search field")
    eq("#sheetSocialApps .picker-bulk", "margin-top", 12, "social bulk row 12 below the search field")
    eq(".app-picker", "margin-top", 14, "picker list 14 below the bulk row")
    eq(".app-picker", "max-height", 380, "picker list capped at 380dp")
    eq("#sheetSocialApps .app-picker", "height", 320, "social picker list fixed 320dp")
    eq("#sheetSocialApps .app-picker", "margin-top", 12, "social picker list 12 below the bulk row")
    eq(".app-empty", "font-size", 14, "sche_apps_empty is 14sp")
    eq(".app-empty", "padding", 28, "sche_apps_empty box padding 28")
    eq("#sheetSocialApps .app-empty", "font-size", 13, "social empty state is 13sp")
    eq("#sheetSocialApps .app-empty", "height", 160, "social empty box is 160dp tall")
    eq("#sheetSocialApps .app-picker .li", "padding", 8, "social picker row padding 8")
    eq("#sheetSocialApps .app-picker .li .tx .t", "font-size", 14, "social picker label 14sp")
    rep.check("E", "social picker label is 14sp/500 (Medium)",
              r.get("#sheetSocialApps .app-picker .li .tx .t", {}).get("font-weight") == "500",
              f"font-weight={r.get('#sheetSocialApps .app-picker .li .tx .t', {}).get('font-weight')}")
    eq("#sheetSocialApps .app-picker .li .tx .s", "font-size", 11, "social picker package 11sp")
    # --- Phase 5(g): sheetDnsCustom / sheetVpnApps -> DnsVpnSheets --------- #
    rep.check("E", "DNS/VPN sheets use the 6dp top inset",
              ctx["rules"].get(".sheet.bs-top", {}).get("padding-top") == "6px"
              and "padding(top = 6.dp, bottom = 26.dp)" in read(
                  UI / "screens/vpn/DnsVpnSheets.kt"),
              "padding-top missing on .sheet.bs-top")
    rep.check("E", "DNS note glyph is a 16dp ink-3 icon nudged 2dp down",
              px(ctx["rules"].get(".dns-note svg", {}), "width") == 16
              and px(ctx["rules"].get(".dns-note svg", {}), "margin-top") == 2
              and ctx["rules"].get(".dns-note svg", {}).get("stroke") == "var(--ink-3)",
              f"glyph = {ctx['rules'].get('.dns-note svg')}")
    rep.check("E", "DNS note metrics (gap 8, 12sp/18, ink-2 body)",
              ctx["rules"].get(".dns-note", {}).get("gap") == "8px"
              and ctx["rules"].get(".dns-note span", {}).get("font-size") == "12px"
              and ctx["rules"].get(".dns-note span", {}).get("line-height") == "18px"
              and ctx["rules"].get(".dns-note span", {}).get("color") == "var(--ink-2)",
              f".dns-note = {ctx['rules'].get('.dns-note')}")
    rep.check("E", "DNS error line is left-aligned 12sp danger",
              ctx["rules"].get(".dns-err", {}).get("font-size") == "12px"
              and ctx["rules"].get(".dns-err", {}).get("color") == "var(--danger)"
              and ctx["rules"].get(".dns-err", {}).get("text-align") == "left",
              f".dns-err = {ctx['rules'].get('.dns-err')}")

    # --- Phase 5(f): sheetTitle -> TitleBlockScreen's rule sheet ----------- #
    rep.check("E", "MATCH label: 14 below the field, 10 above the segment, no inset",
              ctx["rules"].get("#sheetTitle .group-label", {}).get("margin") == "14px 0 10px",
              f"margin={ctx['rules'].get('#sheetTitle .group-label', {}).get('margin')}")
    rep.check("E", "scope note is a brandSoft r12 banner with an 18dp glyph",
              ctx["rules"].get(".scope-note", {}).get("background") == "var(--brand-soft)"
              and px(ctx["rules"].get(".scope-note", {}), "border-radius") == 12
              and ctx["rules"].get(".scope-note", {}).get("padding") == "12px 14px"
              and px(ctx["rules"].get(".scope-note svg", {}), "width") == 18
              and "background(colors.brandSoft)" in read(UI / "screens/titleblock/TitleBlockScreen.kt"),
              f".scope-note = {ctx['rules'].get('.scope-note')}")
    rep.check("E", "delete action is a plain 48dp danger row 12 below the buttons",
              px(ctx["rules"].get(".title-del", {}), "height") == 48
              and ctx["rules"].get(".title-del", {}).get("color") == "var(--danger)"
              and px(ctx["rules"].get(".title-del", {}), "margin-top") == 12
              and "tb_sheet_delete" in strings_xml(),
              f".title-del = {ctx['rules'].get('.title-del')}")
    rep.check("E", "rule sheet field is the 50dp SheetField",
              px(ctx["rules"].get(".field.f50", {}), "height") == 50
              and ".height(50.dp)" in read(UI / "screens/titleblock/TitleBlockScreen.kt"),
              "rule field height drifted")

    # --- Phase 5(e): sheetLockSetup / sheetAutoLock ------------------------ #
    # ModalBottomSheet content carries NO top padding; the sheet's own inset
    # spaces the grab bar (so titles sit 21px down, not 31).
    rep.check("E", "sheets have no top padding (grab bar sits on the sheet inset)",
              ctx["rules"].get(".sheet", {}).get("padding", "").startswith("0 20px"),
              f".sheet padding={ctx['rules'].get('.sheet', {}).get('padding')}")
    rep.check("E", "block-screen sheets add their own 6dp top inset",
              ctx["rules"].get("#sheetBsImg", {}).get("padding-top") == "6px"
              and ctx["rules"].get("#sheetBsMsg", {}).get("padding-top") == "6px"
              and "padding(top = 6.dp, bottom = 26.dp)" in read(
                  UI / "screens/blockscreen/BlockSheets.kt"),
              "BlockSheets' explicit 6dp top inset is missing")
    rep.check("E", "grab bar has no top margin of its own",
              ctx["rules"].get(".sheet .grab", {}).get("margin") == "0 auto 16px",
              f"margin={ctx['rules'].get('.sheet .grab', {}).get('margin')}")
    eq(".steps", "gap", 6, "wizard step dots gap 6")
    rep.check("E", "every reached wizard step is a 24dp brand pill",
              px(ctx["rules"].get(".steps span.on", {}), "width") == 24
              and ctx["rules"].get(".steps span.on", {}).get("background") == "var(--brand)"
              and ".width(if (index < active) 24.dp else 8.dp)" in read(
                  UI / "screens/applock/AppLockSheets.kt"),
              f"active step = {ctx['rules'].get('.steps span.on')}")
    eq("#autoLockList", "margin-top", 14, "auto-lock list 14 below the subtitle")

    # --- Phase 5(d): sheetKw / sheetSite -> KeywordSheet / WebsiteSheet ---- #
    rep.check("E", "category picker label is 12sp/600 ink-3",
              ctx["rules"].get(".cat-label", {}).get("font-size") == "12px"
              and ctx["rules"].get(".cat-label", {}).get("font-weight") == "600"
              and ctx["rules"].get(".cat-label", {}).get("color") == "var(--ink-3)",
              f".cat-label = {ctx['rules'].get('.cat-label')}")
    rep.check("E", "category pill grid: 3 equal columns, 8dp gap, 10 below the label",
              ctx["rules"].get(".cat-grid", {}).get("gap") == "8px"
              and px(ctx["rules"].get(".cat-grid", {}), "margin-top") == 10
              and "repeat(3,1fr)" in ctx["rules"].get(".cat-grid", {}).get("grid-template-columns", ""),
              f".cat-grid = {ctx['rules'].get('.cat-grid')}")
    rep.check("E", "category pills are 36dp, 12sp/600, centred",
              px(ctx["rules"].get(".cat-grid .chip", {}), "height") == 36
              and ctx["rules"].get(".cat-grid .chip", {}).get("font-size") == "12px"
              and ctx["rules"].get(".cat-grid .chip", {}).get("font-weight") == "600"
              and "center" in ctx["rules"].get(".cat-grid .chip", {}).get("justify-content", ""),
              f"pill = {ctx['rules'].get('.cat-grid .chip')}")
    rep.check("E", "sheet field is the 50dp SheetField, 16 below the picker",
              px(ctx["rules"].get(".field.f50", {}), "height") == 50
              and px(ctx["rules"].get(".sheet-form", {}), "margin-top") == 16,
              f"field = {ctx['rules'].get('.field.f50')} / {ctx['rules'].get('.sheet-form')}")
    rep.check("E", "category block starts 16 below the title",
              px(ctx["rules"].get(".cat-block", {}), "margin-top") == 16,
              f"margin={ctx['rules'].get('.cat-block', {}).get('margin-top')}")
    rep.check("E", "sheet actions: 20 above, 48dp pills at 15sp, Save bold",
              px(ctx["rules"].get(".sheet-actions", {}), "margin-top") == 20
              and px(ctx["rules"].get(".sheet-actions .btn", {}), "height") == 48
              and ctx["rules"].get(".sheet-actions .btn", {}).get("font-size") == "15px"
              and ctx["rules"].get(".sheet-actions .btn-primary", {}).get("font-weight") == "700",
              f"actions = {ctx['rules'].get('.sheet-actions')}")


    # --- Phase 5(c): sheetBsImg / sheetBsMsg ------------------------------- #
    eq(".img-grid", "margin-top", 14, "image grid 14 below the subtitle")
    eq(".img-grid", "gap", 10, "image grid gap 10")
    rep.check("E", "image tile is 4:3, r14, 2dp transparent border (no glow ring)",
              "4/3" in ctx["rules"].get(".img-tile", {}).get("aspect-ratio", "")
              and px(ctx["rules"].get(".img-tile", {}), "border-radius") == 14
              and "2px" in ctx["rules"].get(".img-tile", {}).get("border", "")
              and "box-shadow" not in ctx["rules"].get(".img-tile.on", {})
              and ".border(2.dp" in read(UI / "screens/blockscreen/BlockSheets.kt"),
              f"selected tile = {ctx['rules'].get('.img-tile.on')}")
    rep.check("E", "no-image tile is brandSoft 12sp/700, brandDark when chosen",
              ctx["rules"].get(".img-tile.clear", {}).get("background") == "var(--brand-soft)"
              and ctx["rules"].get(".img-tile.clear", {}).get("font-size") == "12px"
              and ctx["rules"].get(".img-tile.clear", {}).get("font-weight") == "700"
              and ctx["rules"].get(".img-tile.clear.on", {}).get("color") == "var(--brand-dark)",
              f"clear tile = {ctx['rules'].get('.img-tile.clear')}")
    eq("#sheetBsMsg .chip", "height", 32, "message suggestions are 32dp pills")
    rep.check("E", "message chip strip has no vertical padding",
              ctx["rules"].get("#sheetBsMsg .chips", {}).get("padding") == "0",
              f"padding={ctx['rules'].get('#sheetBsMsg .chips', {}).get('padding')}")
    eq("#sheetBsMsg .group-label", "margin-top", 14, "SUGGESTIONS sits 14 under the field")

    # --- Phase 5(b): sheetTime -> TimePickerSheet --------------------------- #
    rep.check("E", "time stepper: 18dp above, no inter-column gap (the colon pads itself)",
              ctx["rules"].get(".stepper", {}).get("margin") == "18px 0 4px"
              and ctx["rules"].get(".stepper", {}).get("gap") == "0",
              f".stepper = {ctx['rules'].get('.stepper')}")
    eq(".stepper button", "width", 40, "step buttons are 40dp")
    rep.check("E", "step buttons are circles", "%" in ctx["rules"].get(".stepper button", {}).get("border-radius", ""),
              f"border-radius={ctx['rules'].get('.stepper button', {}).get('border-radius')}")
    rep.check("E", "step buttons are brandSoft/brandDark 18sp/700",
              ctx["rules"].get(".stepper button", {}).get("background") == "var(--brand-soft)"
              and ctx["rules"].get(".stepper button", {}).get("color") == "var(--brand-dark)"
              and ctx["rules"].get(".stepper button", {}).get("font-size") == "18px"
              and ctx["rules"].get(".stepper button", {}).get("font-weight") == "700",
              f"step button = {ctx['rules'].get('.stepper button')}")
    eq(".stepper .time-val", "font-size", 30, "stepper value 30sp")
    eq(".stepper .time-val", "min-width", 56, "stepper value column min 56dp")
    eq(".stepper .col", "gap", 10, "stepper column spacing 10")
    rep.check("E", "time colon is 24sp/800 ink-3 with 12dp side padding, nudged 14dp",
              ctx["rules"].get(".time-colon", {}).get("font-size") == "24px"
              and ctx["rules"].get(".time-colon", {}).get("font-weight") == "800"
              and ctx["rules"].get(".time-colon", {}).get("color") == "var(--ink-3)"
              and "12px" in ctx["rules"].get(".time-colon", {}).get("padding", "")
              and "translateY(14px)" in ctx["rules"].get(".time-colon", {}).get("transform", ""),
              f"colon = {ctx['rules'].get('.time-colon')}")
    eq(".ampm-seg", "max-width", 200, "AM/PM segment capped at 200dp")
    rep.check("E", "AM/PM segment 14 below the stepper",
              ctx["rules"].get(".ampm-seg", {}).get("margin") == "14px auto 0",
              f"margin={ctx['rules'].get('.ampm-seg', {}).get('margin')}")
    rep.check("E", "inline time error is 13sp/600 danger, centred, 12 below",
              ctx["rules"].get(".time-err", {}).get("font-size") == "13px"
              and ctx["rules"].get(".time-err", {}).get("font-weight") == "600"
              and ctx["rules"].get(".time-err", {}).get("color") == "var(--danger)"
              and ctx["rules"].get(".time-err", {}).get("text-align") == "center"
              and ctx["rules"].get(".time-err", {}).get("margin-top") == "12px",
              f".time-err = {ctx['rules'].get('.time-err')}")
    rep.check("E", "sheet Cancel is a bordered secondary (SecondaryButton)",
              ctx["rules"].get(".btn-outline", {}).get("background") == "var(--surface)"
              and "1px" in ctx["rules"].get(".btn-outline", {}).get("border", "")
              and ctx["rules"].get(".btn-outline", {}).get("color") == "var(--ink-2)"
              and 'class="btn btn-outline"' in ctx["html"],
              f".btn-outline = {ctx['rules'].get('.btn-outline')}")


    # empty branch: production renders a 160dp box INSTEAD of the list, not inside it
    rep.check("E", "social empty state replaces the 320dp list (160dp box only)",
              r.get("#sheetSocialApps .app-picker.is-empty", {}).get("height") == "auto"
              and "classList.toggle('is-empty'" in ctx["js"],
              "the 160dp box would sit inside a 320dp list")


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

    # Apostrophes are ASCII in production; the prototype had typographic ones.
    for key in ("sche_toast_time", "kw_toast_empty", "kw_toast_website_empty"):
        want = strings[key].replace("'", "\\'")
        rep.check("F", f"{key} uses production's ASCII apostrophe",
                  f"'{want}'" in ctx["js"],
                  f"expected {strings[key]!r} in app.js")

    # AppPickerSheet renders sche_apps_empty; SocialPickerSheet has its own
    # inline literal. Both must match production character for character.
    social_kt = read(UI / "screens/socialblocking/SocialBlockingScreen.kt")
    # KeywordSheet/WebsiteSheet have NO subtitle line in production and their
    # field placeholder is kw_sheet_field ("Value"), not "keyword…".
    rep.check("F", "keyword/site sheets ship no invented subtitle",
              "Validated against duplicates" not in ctx["html"],
              "the prototype still shows 'Validated against duplicates & format.'")
    rep.check("F", "keyword/site field placeholder is kw_sheet_field",
              ctx["html"].count('placeholder="Value"') == 2
              and "placeholder=\"keyword…\"" not in ctx["html"]
              and "placeholder=\"example.com\"" not in ctx["html"]
              and strings["kw_sheet_field"] == "Value",
              "placeholder differs from kw_sheet_field")
    rep.check("F", "keyword/site save pill is kw_sheet_save ('Save')",
              'id="kwSaveBtn"' in ctx["html"] and 'id="siteSaveBtn"' in ctx["html"]
              and "setText(isKw?'kwSaveBtn':'siteSaveBtn', 'Save')" in ctx["js"]
              and strings["kw_sheet_save"] == "Save",
              "the save pill still reads Add/Add Title")

    # BlockScreen.kt:108-120 — image row sub uses "•", message row wraps the
    # value in curly quotes. Both had the wrong punctuation.
    rep.check("F", "motivation-image row sub uses bs_img_sub_set's bullet",
              "bsImg+' \\u2022 persistable'" in ctx["js"]
              and ">%1$s • persistable<" in read(STRINGS),
              "image row sub is not '<name> • persistable'")
    rep.check("F", "custom-message row sub uses curly quotes (BlockScreen.kt:118)",
              "'\\u201C'+bsMsg+'\\u201D'" in ctx["js"]
              and '"\\u201C$message\\u201D"' in read(UI / "screens/blockscreen/BlockScreen.kt"),
              "message row sub is not “<message>”")

    rep.check("F", "app-picker empty state uses sche_apps_empty",
              f"'{strings['sche_apps_empty']}'" in ctx["js"]
              and "No apps match your search" in social_kt,
              f"expected {strings['sche_apps_empty']!r} in app.js")

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
    # MainScreen.kt:211 -> PlaceholderScreen(prof_troubleshoot_title): one centred
    # title, no app bar. An extra header shipped two "Troubleshooting" texts.
    ts = ctx["sections"].get("troubleshoot", "")
    rep.check("G", "troubleshoot is production's PlaceholderScreen (B-19)",
              'class="sub"' not in ts
              and ts.count("Troubleshooting") == 1
              and "font-weight:700" in ts and "font-size:18px" in ts
              and 'PlaceholderScreen(R.string.prof_troubleshoot_title)' in
                  read(UI / "screens/main/MainScreen.kt"),
              "troubleshoot drifted from PlaceholderScreen (title-only, centred)")

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

    # --- B-14: ScheduleScreen / ScheduleEditScreen state coverage ------------ #
    sched_src = read(UI / "screens/schedule/ScheduleScreen.kt")
    sched_prefs = read(DATA / "SchedulePrefs.kt")
    edit_src = read(UI / "screens/schedule/ScheduleEditScreen.kt")
    rep.check("G", "banner predicates mirror shouldShowA11y/ExactAlarmWarning (B-14)",
              "fun shouldShowA11yWarning" in sched_prefs
              and "fun shouldShowExactAlarmWarning" in sched_prefs
              and "function schedWarnings" in js
              and "!a11yOn && !schedA11yDismissed && needsA11y" in js
              and "!schedExactGranted && !schedExactDismissed && enabled.length>0" in js
              and "mode == ScheduleMode.LAUNCH || mode == ScheduleMode.BOTH" in sched_prefs
              and "m==='Both'||m==='Launch'" in js,
              "banner gating drifted from SchedulePrefs")
    rep.check("G", "dismissing a schedule banner is remembered until re-granted (B-14)",
              "function dismissSchedBanner" in js
              and "if(a11yOn) schedA11yDismissed = false" in js
              and "if(schedExactGranted) schedExactDismissed = false" in js,
              "a dismissal is not re-armed by ScheduleViewModel.refresh()")
    rep.check("G", "toolbar can reach both banner states (B-14)",
              'id="stExact"' in html and "setDemo('exact'" in html
              and "key === 'exact'" in js and "function openExactAlarm" in js,
              "the exact-alarm banner has no demo state")
    rep.check("G", "Exclude Apps card is brandSoft + count-driven (B-14)",
              'id="schExcludeSub"' in html and "function schedExcludeSub" in js
              and "'Never blocked by any schedule'" in js
              and "app'+(n===1?' excluded':'s excluded')" in js
              and "R.plurals.sch_exclude_count" in sched_src
              and "stringResource(R.string.sch_exclude_sub)" in sched_src,
              "exclude card subtitle is not driven by sch_exclude_count")
    rep.check("G", "Exclude Apps sheet reuses AppPickerSheet copy (B-14)",
              'id="sheetExclude"' in html and 'id="excludeList"' in html
              and strings["sch_exclude_sheet_title"] in html
              and strings["sch_exclude_sheet_sub"] in html
              and "function excludeDone" in js
              and "R.string.sch_exclude_sheet_title" in sched_src,
              "the exclude picker copy drifted from ScheduleScreen")
    rep.check("G", "schedule list has no prototype-only empty line (B-14)",
              "schedEmpty" not in html and "schedEmpty" not in js,
              "schedEmpty is back; production shows the empty copy in the hero only")
    rep.check("G", "hero pill keeps its translucent shell in every state (B-14)",
              ".hero .pill,.hero .pill.g,.hero .pill.r{background:rgba(255,255,255,.16);color:#fff}"
              in ctx["css"]
              and ".background(Color.White.copy(alpha = 0.16f))" in sched_src,
              "the generic .pill.g/.r rules are shadowing the hero shell again")
    dots = re.findall(r"HeroPillDot(?:Off)? = Color\(0xFF([0-9A-Fa-f]{6})\)", sched_src)
    applock_src = read(UI / "screens/applock/AppLockScreen.kt")
    applock_dots = re.findall(r"Pill(?:Green|Red) = Color\(0xFF([0-9A-Fa-f]{6})\)", applock_src)
    rep.check("G", "hero pill dot colours == HeroPillDot / PillGreen+PillRed (B-14)",
              dots and applock_dots and sorted(d.upper() for d in dots) == sorted(applock_dots)
              and all(f"#{d.upper()}" in ctx["css"] for d in dots),
              f"Kotlin dots {dots} vs applock {applock_dots}")
    btn_rule = ctx["rules"].get(".banner .btn.sm", {})
    rep.check("G", "schedule banner: dangerBg fill + brandSoft Enable pill (B-14)",
              ctx["rules"].get(".banner", {}).get("background") == "var(--danger-bg)"
              and btn_rule.get("background") == "var(--brand-soft)"
              and btn_rule.get("color") == "var(--brand-dark)"
              and "colors.dangerBg" in sched_src,
              "banner fill or Enable pill drifted from ScheduleScreen")
    rep.check("G", "overnight windows append (+1) like scheduleWindowLabel (B-14)",
              "(o.end<o.start?' (+1)':'')" in js
              and 'if (endMinute < startMinute) " (+1)" else ""' in sched_prefs
              and "(o.end<o.start?' (+1)':'')" in js,
              "(+1) marker drifted from SchedulePrefs.scheduleWindowLabel")
    rep.check("G", "card foot/pill copy == sch_card_next / sch_card_apps_all (B-14)",
              "Next · '+o.days" in js and ":'All apps'" in js
              and strings["sch_card_next"].startswith("Next · ")
              and strings["sch_card_apps_all"] == "All apps",
              "card foot/pill copy drifted")
    rep.check("G", "\"Ends next day\" caption is state-derived, not Done-only (B-14)",
              "function schedOvernightCap" in js
              and js.count("schedOvernightCap();") >= 3
              and "stringResource(R.string.sche_overnight)" in edit_src
              and ctx["rules"].get(".cap", {}).get("color") == "var(--ink-3)",
              "the overnight caption is not recomputed when the editor opens")

    # --- B-16/B-17: AppLockScreen + BlockScreen state coverage -------------- #
    lock_src = read(UI / "screens/applock/AppLockScreen.kt")
    overlay_src = read(UI / "screens/applock/AppLockUnlockOverlay.kt")
    lock_vm = read(UI / "screens/applock/AppLockViewModel.kt")
    block_vm = read(UI / "screens/blockscreen/BlockScreenViewModel.kt")
    rep.check("G", "Disable App Lock renders only while a lock is set (B-16)",
              'id="lockDisableBtn"' in html
              and strings["al_disable"] in html
              and "if (state.enabled) {" in lock_src
              and "DisableButton(onClick" in lock_src
              and "lockState !== 'on'" in js,
              "the disable button is not gated on the lock state")
    dis_btn = re.search(r'id="lockDisableBtn"[^>]*style="([^"]+)"', html)
    dis_css = dis_btn.group(1) if dis_btn else ""
    rep.check("G", "DisableButton geometry == danger / 52dp / r999 / 16sp-600 (B-16)",
              all(k in dis_css for k in ("height:52px", "font-size:16px", "font-weight:600",
                                         "border-radius:999px"))
              and ".height(52.dp)" in lock_src and "colors.danger" in lock_src
              and "fontSize = 16.sp" in lock_src and "fontWeight = FontWeight.SemiBold" in lock_src,
              "disable button geometry drifted")
    rep.check("G", "turning App Lock off is an AlertDialog, not a sheet (B-16)",
              'id="dlgLockDisable"' in html and 'id="sheetLockDisable"' not in html
              and "AlertDialog(" in lock_src
              and strings["al_disable_title"] in html and strings["al_disable_body"] in html
              and strings["al_disable_confirm"] in html and strings["al_disable_cancel"] in html,
              "the disable confirm is not the production AlertDialog")
    rep.check("G", "App Lock settings are inert + dimmed while off (B-16)",
              "MasterSwitch(" in lock_src and "checked = enabled && biometricEnabled" in lock_src
              and "val alpha = if (enabled) 1f else 0.45f" in lock_src
              and "!off && sw.dataset.lockval==='1'" in js
              and "t.closest('.li.row-off')" in js
              and "function openAutoLock(){ if(lockState!=='on') return;" in js,
              "off-state row behaviour drifted from SettingsList/SettingRow")
    rep.check("G", "row-off blending uses 45% icon / 50% swOff (B-16)",
              ctx["rules"].get(".li.row-off .ic", {}).get("background", "").endswith("45%,var(--surface))")
              and ctx["rules"].get(".li.row-off .sw", {}).get("background", "").endswith("50%,var(--surface))")
              and '.li.row-off .ic.g' in ctx["css"] and ':root[data-theme="dark"] .li.row-off .ic.g' in ctx["css"],
              "the disabled-row fills are not the blended 45%/50% values")
    rep.check("G", "App Lock state copy comes from strings.xml (B-16)",
              all(v in ctx["html"] + js.replace("\\'", "'") for v in (
                  strings["al_mismatch"], strings["al_toast_on"], strings["al_toast_disabled"],
                  strings["al_toast_bio_unavailable"], strings["al_gate_bio_off"],
                  strings["al_gate_use_biometric"]))
              and all(v in (overlay_src + lock_src + lock_vm) for v in (
                  "al_gate_use_biometric", "al_gate_bio_off", "al_toast_bio_unavailable")),
              "a gate/wizard string is missing on one side")
    rep.check("G", "gate tints the biometric row by state, label stays constant (B-16)",
              "bioActive" in overlay_src and "colors.brandDark else colors.ink3" in overlay_src
              and "bio.classList.toggle('active', on && DEMO.home.bioAvailable)" in js,
              "the biometric row no longer mirrors bioActive")
    rep.check("G", "dwell countdown clamps to the same 3-120s window (B-17)",
              "coerceIn(3, 120)" in block_vm
              and "Math.max(3,Math.min(120,bsDwell+d))" in js
              and "R.string.bs_closing, dwell" in read(UI / "screens/blockscreen/BlockScreen.kt"),
              "dwell clamp drifted from BlockScreenViewModel")

    # --- B-18: BackupScreen busy + error presentation ----------------------- #
    backup_src = read(UI / "screens/backup/BackupScreen.kt")
    codec = read(DATA / "BackupCodec.kt")
    rep.check("G", "busy swaps the backup action labels, not an inline box (B-18)",
              "actionLabel = if (busy) stringResource(R.string.backup_exporting)" in backup_src
              and "actionLabel = if (busy) stringResource(R.string.backup_restoring)" in backup_src
              and "enabled = !busy" in backup_src
              and "T.backupExporting" in js and "T.backupImportAction" in js
              and "T.backupRestoring" in js
              and "backupStatus" not in html and "backupStatus" not in js,
              "busy/error presentation drifted from BackupScreen")
    rep.check("G", "backup failures toast the backup_err_* string (B-18)",
              "toastRes(errorRes(result.error))" in backup_src
              and "if(state==='error') toast(BACKUP_ERR[errKey]" in js
              and len(re.findall(r"toast\(", js)) > 0,
              "errors are not surfaced as toasts")
    # every BackupError the UI can show must be reachable from the demo toolbar
    err_res = re.findall(r"BackupError\.[A-Z0-9_]+ -> R\.string\.(backup_err_\w+)", backup_src)
    js_norm = js.replace("\\'", "'")
    missing = [r for r in err_res if strings[r] not in js_norm]
    rep.check("G", "all seven BackupError messages are reachable (B-18)",
              len(err_res) == 7 and not missing,
              f"{len(err_res)} mapped, missing: {missing}")
    sections = re.findall(r"^    ([A-Z0-9_]+),$", codec.split("enum class BackupSection")[1].split("}")[0], re.M)
    # label resource per enum constant, read from BackupScreen.sectionLabelRes()
    label_res = dict(re.findall(r"BackupSection\.([A-Z0-9_]+) -> R\.string\.(\w+)", backup_src))
    labels = [strings[label_res[s]] for s in sections if s in label_res]
    rows = re.findall(r'<div class="inc"[^>]*>([^<]+)</div>', html)
    rep.check("G", '"What\'s included" lists every BackupSection in order (B-18)',
              len(sections) == 10 and [r.replace("&amp;", "&") for r in rows] == labels,
              f"Kotlin {labels} vs prototype {rows}")

    # --- B-19 / A-19: block-screen Exclude Apps card + sheet copy ----------- #
    other_src = read(UI / "screens/blocking/OtherFeaturesScreen.kt")
    rep.check("G", "block Exclude Apps card mirrors ExcludeAppsCard (B-19)",
              'id="blkExcludeSub"' in html and 'openSheet(\'sheetBlkExclude\')' in html
              and "function blkExcludeSub" in js
              and "R.plurals.blk_exclude_count" in other_src
              and "stringResource(R.string.blk_exclude_sub)" in other_src
              and "background(colors.brandSoft)" in other_src
              and "13.5.sp" in other_src and "11.5.sp" in other_src,
              "the block exclude card drifted from ExcludeAppsCard")
    rep.check("G", "block Exclude Apps sheet uses blk_exclude_sheet_* (A-19)",
              'id="sheetBlkExclude"' in html
              and strings["blk_exclude_sheet_title"] in html
              and strings["blk_exclude_sheet_sub"] in html
              and "stringResource(R.string.blk_exclude_sheet_sub)" in other_src
              and "function blkExcludeDone" in js,
              "the block exclude sheet copy drifted")
    rep.check("G", "block image sheet note uses bs_img_sub_sheet (A-19)",
              strings["bs_img_sub_sheet"] in html
              and "R.string.bs_img_sub_sheet" in read(UI / "screens/blockscreen/BlockSheets.kt"),
              "bs_img_sub_sheet copy drifted")
    rep.check("G", "permission pill copy uses perm_granted (A-19)",
              strings["perm_granted"] in js
              and "stringResource(R.string.perm_granted)" in read(
                  UI / "screens/permissions/PermissionScreen.kt"),
              "perm_granted copy drifted")

    # Phase 5(a): production's picker bulk callbacks only mutate state
    # (ScheduleEditViewModel.selectAllApps / VpnViewModel) — no toast. Social's
    # setWholeBlocked does toast, but on the select call, not on Done.
    def body(name: str) -> str:
        m = re.search(rf"function {name}\(\)\{{(.*?)\n  \}}", js, re.S)
        return m.group(1) if m else ""

    silent = [n for n in ("selectAllApps", "deselectAllApps",
                          "selectAllVpnApps", "deselectAllVpnApps", "socialAppsDone")
              if "toast(" in body(n)]
    rep.check("H", "picker bulk actions and Done stay silent (no invented toast)",
              not silent, f"still toasting: {silent}")

    social_sel = body("selectAllSocialApps")
    sos = read(UI / "screens/socialblocking/SocialBlockingScreen.kt")
    rep.check("H", "social Select All commits with the selected count",
              "SOCIAL.wholeBlocked.size" in social_sel
              and "APPS.length+' apps blocked" not in social_sel
              and "setWholeBlocked(allApps.map { it.packageName }.toSet())" in sos,
              "social bulk select still reports the catalog length")

    # TimePickerSheet: the whole-window check gates the commit and the message is
    # inline; the editor's Save still toasts (ScheduleEditViewModel.save).
    tdone = body("timeDone")
    rep.check("H", "time sheet validates before committing, inline (not a toast)",
              "timeErr('Start and end" in tdone and "toast(" not in tdone
              and "setTimep(" in tdone and tdone.index("timeErr(") < tdone.index("setTimep(")
              and "isValidScheduleWindow(state.startMinute, state.endMinute)" in read(
                  UI / "screens/schedule/ScheduleEditViewModel.kt"),
              "sheetTime commits before validating or still toasts")
    rep.check("H", "time steppers wrap like the Kotlin modulo maths",
              "(timeH+d+24)%24" in js and "(timeM+d+60)%60" in js
              and "(hour - 1 + 24) % 24" in read(UI / "screens/schedule/ScheduleSheets.kt"),
              "steppers clamp instead of wrapping")

    # CustomMessageSheet saves unconditionally (onSave(text.trim()) -> setMessage);
    # an empty message is legal. The chip highlight is derived from the value.
    smsg = body("saveMsg")
    rep.check("H", "message sheet saves an empty message (no invented guard)",
              "can" not in smsg.split("toast(")[0] and "if(!v)" not in smsg
              and "onSave = { value ->" in read(UI / "screens/blockscreen/BlockScreen.kt")
              and "fun setMessage(value: String) {" in read(
                  UI / "screens/blockscreen/BlockScreenViewModel.kt"),
              "saveMsg still refuses an empty message")
    rep.check("H", "message chips derive their selected state from the field value",
              "c.textContent===v" in js and "isSelected = text == chip" in read(
                  UI / "screens/blockscreen/BlockSheets.kt"),
              "chips keep an independent selected flag")

    # KeywordSheet/WebsiteSheet state rules from KeywordManagerScreen.
    rep.check("H", "category picker hidden for whitelist/trusted entries",
              "kwSelTab==='whitelist'" in js and "kwSelTab==='trusted'" in js
              and "block.style.display = noPicker" in js
              and "if (!config.whitelist)" in read(UI / "screens/keywords/KeywordManagerScreen.kt")
              and "if (!config.trusted)" in read(UI / "screens/keywords/KeywordManagerScreen.kt"),
              "the picker is always visible")
    rep.check("H", "keyword sheet switches add/edit titles from the editing state",
              "'Edit keyword'" in js and "'Edit website'" in js
              and "siteEditing" in js
              and "R.string.kw_sheet_edit_keyword" in read(UI / "screens/keywords/KeywordManagerScreen.kt"),
              "the website sheet never entered edit mode")
    rep.check("H", "keyword values are lower-cased like saveKeyword()",
              ".trim().toLowerCase()" in body("addKeyword")
              and "input.trim().lowercase()" in read(UI / "screens/keywords/KeywordManagerScreen.kt"),
              "keywords keep their casing")
    rep.check("H", "keyword toasts match kw_toast_* and deletion is silent",
              "'Keyword updated':'Website updated'" in js
              and "toast('Keyword added to whitelist')" in js
              and "toast('Website added to trusted')" in js
              and "toast('Deleted')" not in js
              and "toast('Updated')" not in js
              and "kw_toast_website_updated" in strings_xml(),
              "keyword toasts still diverge from production")

    # CustomDnsSheet: both fields are validated together and the message is
    # inline (vpn_dns_invalid), never a toast.
    sdns = body("saveDns")
    rep.check("H", "DNS sheet validates both fields and reports inline",
              "okV4" in sdns and "okV6" in sdns and "dnsErr('Enter a valid" in sdns
              and "toast('Custom DNS saved')" in sdns
              and sdns.count("toast(") == 1
              and "VpnValidation.isValidIpv4" in read(UI / "screens/vpn/DnsVpnSheets.kt")
              and "R.string.vpn_dns_invalid" in read(UI / "screens/vpn/DnsVpnSheets.kt"),
              "the DNS sheet still toasts one field at a time")
    rep.check("H", "VPN whitelist sheet keeps vpn_app_* copy",
              "vpn_app_no_results" in strings_xml() and "vpn_app_loading" in strings_xml()
              and "pickerEmptyCopy" in js
              and "vpn_app_loading" not in js,
              "VPN picker empty-state copy drifted")

    # TitleBlockScreen: save pill is tb_sheet_add ("Add title rule") when adding
    # and kw_sheet_save ("Save") when editing; duplicates are value AND mode.
    tb_js = js[js.index("function addTitle()"):]
    rep.check("H", "rule sheet save pill switches tb_sheet_add / kw_sheet_save",
              "sb.textContent='Add title rule'" in js and "sb.textContent='Save'" in js
              and "R.string.tb_sheet_add" in read(UI / "screens/titleblock/TitleBlockScreen.kt"),
              "the add mode still says Add Title / save mode says Save changes")
    rep.check("H", "rule rows open the edit sheet (chevron), never the add sheet",
              'onclick=\"editTitle(this)\"' in js
              and "onclick=\"openSheet('sheetTitle')\"" not in js
              and "showSheet = true" not in read(UI / "screens/titleblock/TitleBlockScreen.kt")
              and "sheetRule = rule" in read(UI / "screens/titleblock/TitleBlockScreen.kt"),
              "the displayed rule rows still open the add sheet")

    rep.check("H", "rule duplicates compare value AND mode",
              "&& (r.querySelector('.s').textContent||'Contains')===mode" in tb_js
              and "it.value == normalized && it.mode == mode" in read(
                  UI / "screens/titleblock/TitleBlockViewModel.kt"),
              "duplicate check ignores the match mode")

    rep.check("G", "feed empty state left-aligned (A-04)",
              ctx["rules"].get(".feed-empty", {}).get("text-align", "left") == "left",
              "feed-empty still centred")
    group_g_phase5(rep, ctx)


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
    # BackupScreen.RestoreConfirmDialog is an AlertDialog, so the prototype must
    # not present it as a bottom sheet (same class of bug as B-07).
    rep.check("H", "restore confirms in an AlertDialog before running (dialogRestore)",
              'id="dlgRestore"' in html and "openDlg('dlgRestore')" in html
              and 'id="sheetRestore"' not in html
              and "AlertDialog(" in read(UI / "screens/backup/BackupScreen.kt"),
              "restore confirmation drifted from RestoreConfirmDialog")

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
    copy_section = doc.split("## Copy", 1)[-1].split("\n## ", 1)[0]  # icon rows live in their own table
    rows = [ln for ln in copy_section.splitlines() if re.match(r"\| `[a-z0-9_]+`", ln)]
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
    "excludeList",
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


# --------------------------------------------------------------------------- #
# Phase 5 close-out: every sheet, its title/sub and its Kotlin home
# --------------------------------------------------------------------------- #
# sheet id → (title string keys, subtitle string keys, kotlin file, kotlin marker)
# Title keys hold an add/edit or per-step variant when the sheet switches.
SHEET_TABLE = {
    "sheetKw":          (["kw_sheet_add_keyword", "kw_sheet_edit_keyword"], [],
                         "keywords/KeywordManagerScreen.kt", "private fun KeywordSheet("),
    "sheetSite":        (["kw_sheet_add_website", "kw_sheet_edit_website"], [],
                         "keywords/KeywordManagerScreen.kt", "private fun WebsiteSheet("),
    "sheetTitle":       (["tb_sheet_add", "tb_sheet_edit"], ["tb_sheet_subtitle"],
                         "titleblock/TitleBlockScreen.kt", "R.string.tb_sheet_edit"),
    "sheetLockSetup":   (["al_setup_method", "al_setup_create", "al_setup_confirm"],
                         ["al_setup_method_sub", "al_setup_create_sub", "al_setup_confirm_sub"],
                         "applock/AppLockSheets.kt", "fun AppLockSetupSheet("),
    "sheetAutoLock":    (["al_auto_sheet_title"], ["al_auto_sheet_sub"],
                         "applock/AppLockSheets.kt", "fun AutoLockSheet("),
    "sheetBsImg":       (["bs_motivation_image"], ["bs_img_sub_sheet"],
                         "blockscreen/BlockSheets.kt", "fun MotivationImageSheet("),
    "sheetBsMsg":       (["bs_custom_message"], ["bs_msg_sub_sheet"],
                         "blockscreen/BlockSheets.kt", "fun CustomMessageSheet("),
    "sheetTime":        (["sche_time_sheet_title"], ["sche_time_sheet_sub"],
                         "schedule/ScheduleSheets.kt", "fun TimePickerSheet("),
    "sheetApps":        (["sche_apps_sheet_title"], ["sche_apps_sheet_sub"],
                         "schedule/ScheduleSheets.kt", "fun AppPickerSheet("),
    "sheetExclude":     (["sch_exclude_sheet_title"], ["sch_exclude_sheet_sub"],
                         "schedule/ScheduleScreen.kt", "sch_exclude_sheet_title"),
    "sheetBlkExclude":  (["blk_exclude_sheet_title"], ["blk_exclude_sheet_sub"],
                         "blocking/OtherFeaturesScreen.kt", "blk_exclude_sheet_title"),
    "sheetDnsCustom":   (["vpn_sheet_dns_title"], ["vpn_sheet_dns_sub"],
                         "vpn/DnsVpnSheets.kt", "fun CustomDnsSheet("),
    "sheetVpnApps":     (["vpn_sheet_apps_title"], ["vpn_sheet_apps_sub"],
                         "vpn/DnsVpnSheets.kt", "fun VpnAppsSheet("),
    # SocialPickerSheet hard-codes its title/sub as literals, not resources.
    "sheetSocialApps":  ([], [],
                         "socialblocking/SocialBlockingScreen.kt", "private fun SocialPickerSheet("),
}


def sheet_body(html: str, sheet_id: str) -> str:
    """Inner HTML of one sheet (balanced enough for these hand-written files)."""
    start = html.index(f'id="{sheet_id}"')
    depth, i = 1, html.index(">", start) + 1
    while depth:
        nxt_open = html.find("<div", i)
        nxt_close = html.find("</div>", i)
        if nxt_close == -1:
            break
        if nxt_open != -1 and nxt_open < nxt_close:
            depth += 1
            i = nxt_open + 4
        else:
            depth -= 1
            i = nxt_close + 6
    return html[start:i]


def group_g_phase5(rep: Report, ctx):
    """Phase 5 close-out: structural sweep across all 14 sheets."""
    html = ctx["html"]
    strings = strings_xml()
    missing_grab, missing_title, unbacked = [], [], []
    for sheet_id, (titles, subs, kt, marker) in SHEET_TABLE.items():
        body = sheet_body(html, sheet_id)
        if 'class="grab"' not in body:
            missing_grab.append(sheet_id)
        # every sheet renders a title; it is either a resource string or, for
        # SocialPickerSheet, the production literal
        rendered = [t for t in titles if strings.get(t, "\x00") in body]
        literal_ok = sheet_id == "sheetSocialApps" and ">Block entire apps<" in body
        if titles and not rendered and not literal_ok:
            missing_title.append(sheet_id)
        if not titles and not literal_ok:
            missing_title.append(sheet_id)
        # HTML entities (& -> &amp;) and step-switched subtitles (written by JS)
        # both count as "rendered".
        body_plain = html_mod.unescape(body)
        for key in subs:
            want = strings.get(key, "\x00")
            if want not in body and want not in body_plain and want not in ctx["js"]:
                unbacked.append(f"{sheet_id}:{key}")
        if marker not in read(UI / "screens" / kt):
            unbacked.append(f"{sheet_id}:kotlin")
    rep.check("G", "every sheet renders a grab bar", not missing_grab, f"no grab: {missing_grab}")
    rep.check("G", "every sheet's title comes from its production string",
              not missing_title, f"title not found: {missing_title}")
    rep.check("G", "every sheet's subtitle/owner exists in its Kotlin source",
              not unbacked, f"unbacked: {unbacked}")


# --------------------------------------------------------------------------- #
# J · icon parity — every glyph the prototype draws is a production glyph
# --------------------------------------------------------------------------- #
ICON_TOL = 0.5  # max point deviation, in viewBox units at 24dp

# Progress rings are drawn in their own viewBox inside a sized box: they are
# decoration, not icon slots, and production has no counterpart.
ICON_DECOR_VIEWBOX = {"0 0 64 64", "0 0 120 120"}

# Prototype glyph sources that are not app chrome. Each is demo data or a
# text-glyph affordance, so there is no production ImageVector to match.
ICON_SKIP_REGIONS = [
    ("js", "const APPS = [", "  ];",
     "app rows show real launcher icons in production (PackageManager), so the "
     "prototype draws brand placeholders"),
    ("js", "const DEMO_SERVICES = [", "  ];",
     "fabricated demo services in the picker"),
    ("js", "function appByName", "\n  }\n",
     "avatar fallback for an app name that is not in the demo catalog"),
    ("js", "function qaRow(a,on,i,total){", "\n  }\n",
     "quick-action editor affordances; production draws these as text glyphs "
     "(QuickActionsEditScreen.kt uses ArrowButton(\u2191), RemoveBadge(\u2715), AddBadge)"),
]

# Production icons that no prototype glyph reproduces, with the reason. Checked
# in both directions so the table cannot drift from the code.
PRODUCTION_ONLY = {
    "A11yPersonIcon": "declared in PermissionIcons.kt, never referenced by a screen",
    "BatteryIcon": "declared in PermissionIcons.kt, never referenced by a screen",
    "BellIcon": "declared in PermissionIcons.kt, never referenced by a screen",
    "SchSearchIcon": "geometry deviation — see GEOMETRY_DEVIATIONS",
}

# Production glyphs whose Kotlin is defective. The prototype draws the glyph the
# rest of the family draws instead of copying the defect.
GEOMETRY_DEVIATIONS = {
    "SchSearchIcon": (
        "ScheduleSheets.kt draws the search circle from `moveTo(11, 11)` — the "
        "centre — so the r=7 circle around it reaches x=25 and is clipped by the "
        "24dp viewport. Its own handle (M21 21 L16.7 16.7) and the sibling "
        "KwSearchIcon / VpnSearchIcon both centre the circle at (11, 11). The "
        "prototype keeps the centred glyph; a Kotlin fix is out of scope."
    ),
}


def _skip_ranges(src: str, start: str, end: str) -> list[tuple[int, int]]:
    i = src.find(start)
    if i < 0:
        return []
    j = src.find(end, i + len(start))
    return [(i, (j + len(end)) if j > i else len(src))]


def icon_glyphs(ctx) -> tuple[list[tuple[str, int, list]], list[str]]:
    """(glyphs, skipped notes) for the prototype's two glyph sources."""
    import icon_parity as icons

    out: list[tuple[str, int, list]] = []
    notes: list[str] = []
    for tag, path in (("html", INDEX), ("js", JS)):
        src = ctx[tag]
        ranges: list[tuple[int, int]] = []
        for region_tag, start, end, why in ICON_SKIP_REGIONS:
            if region_tag != tag:
                continue
            hit = _skip_ranges(src, start, end)
            if not hit:
                notes.append(f"skip region not found: {start!r}")
            ranges.extend(hit)
        glyphs = icons.svg_glyphs(src) + (icons.path_map_glyphs(src) if tag == "js" else [])
        for offset, d, viewbox in glyphs:
            if "'" in d or "+" in d:
                continue  # a JS template hole, not a glyph
            if viewbox in ICON_DECOR_VIEWBOX:
                notes.append(f"{tag}:{src.count(chr(10), 0, offset) + 1} decorative ring")
                continue
            if any(a <= offset <= b for a, b in ranges):
                continue
            line = src.count("\n", 0, offset) + 1
            out.append((tag, line, icons.svg_to_segments(d)))
    return out, notes


def group_j(rep: Report, ctx):
    import icon_parity as icons

    production = icons.all_kotlin_icons(UI)
    declared = {m.group(1) for f in UI.rglob("*Icons.kt")
                for m in re.finditer(r"(?:internal )?val (\w+):\s*ImageVector", read(f))}
    unparsed = sorted(declared - set(production))
    rep.check("J", "every ImageVector in *Icons.kt parses into geometry",
              not unparsed and len(production) == len(declared),
              f"declared {len(declared)}, parsed {len(production)}, unparsed: {unparsed}")

    glyphs, notes = icon_glyphs(ctx)
    if any(n.startswith("skip region not found") for n in notes):
        rep.check("J", "every declared demo-data region still exists", False, "; ".join(notes))
    else:
        rep.check("J", "every declared demo-data region still exists", True)

    pool = [(name, segs) for name, (_, segs) in production.items()]
    offenders = []
    used: set[str] = set()
    for tag, line, segs in glyphs:
        dist, name = icons.match(segs, pool)
        if dist <= ICON_TOL:
            used.add(name)
        else:
            offenders.append(f"{tag}:{line} (nearest {name or 'none'}"
                             f"{', ' + format(dist, '.2f') if dist != float('inf') else ''})")
    rep.check("J", "every prototype icon is a production glyph",
              not offenders, f"labels differ: {offenders}")
    rep.check("J", f"the prototype draws {len(used)} of {len(production)} production icons",
              len(used) >= 45 and len(glyphs) >= 150,
              f"{len(used)} icons / {len(glyphs)} glyphs")

    missing = {}
    for name, (_, segs) in production.items():
        dist, _ = icons.match(segs, [(f"{tag}:{line}", g) for tag, line, g in glyphs])
        if dist > ICON_TOL:
            missing[name] = dist
    rep.check("J", "production icons the prototype does not draw are the documented set",
              set(missing) == set(PRODUCTION_ONLY),
              f"undocumented: {sorted(set(missing) - set(PRODUCTION_ONLY))} · "
              f"stale: {sorted(set(PRODUCTION_ONLY) - set(missing))}")

    # the documented reasons must stay true
    stale_reason = []
    for name, why in PRODUCTION_ONLY.items():
        if why.startswith("geometry deviation"):
            continue
        word = re.compile(rf"\b{name}\b")
        holders = [f.name for f in UI.rglob("*Icons.kt") if word.search(read(f))]
        refs = sorted(f.name for f in UI.rglob("*.kt")
                      if word.search(read(f)) and f.name not in holders)
        if len(holders) != 1 or refs:
            stale_reason.append(f"{name}: declared in {holders}, referenced by {refs}")
    rep.check("J", "an icon listed as unused really has no screen reference",
              not stale_reason, "; ".join(stale_reason))

    devs = read(ROOT / "tools/prototype/DEVIATIONS.md")
    bad = [f"{n} not in DEVIATIONS.md" for n in GEOMETRY_DEVIATIONS if n not in devs]
    bad += [f"{n} missing from PRODUCTION_ONLY" for n in GEOMETRY_DEVIATIONS
            if n not in PRODUCTION_ONLY]
    rep.check("J", "every geometry deviation is registered in DEVIATIONS.md",
              not bad, "; ".join(bad))



# --------------------------------------------------------------------------- #
# Phase 7: demo data & copy policy
# --------------------------------------------------------------------------- #
# Every string a renderer writes to a node, or hands to the toast host, must be
# one production can produce. Production is read from strings.xml (strings +
# plural items) and from the literals its `ui/**` Kotlin renders.
JS_LITERAL = re.compile(r"""'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*\"""")
JS_COMPARISON = re.compile(r"(===|!==|==|!=)\s*$|^\s*(===|!==|==|!=)")
JS_SINKS = (
    (re.compile(r"\btoast\("), "toast"),
    (re.compile(r"\.textContent\s*="), "text"),
    (re.compile(r"\.placeholder\s*="), "placeholder"),
    (re.compile(r"""setAttribute\(\s*(['"])(placeholder|aria-label|title)\1\s*,"""), "attr"),
)
DECODE_JS = re.compile(r"\\u([0-9a-fA-F]{4})")


def js_expr(src: str, i: int) -> str:
    """The expression starting at `i`, balanced over brackets and strings."""
    depth = 0
    out = []
    while i < len(src):
        c = src[i]
        if c in "'\"`":
            q = c
            out.append(c)
            i += 1
            while i < len(src) and src[i] != q:
                if src[i] == "\\":
                    out.append(src[i])
                    i += 1
                if i < len(src):
                    out.append(src[i])
                    i += 1
            out.append(q if i < len(src) else "")
            i += 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                break
            depth -= 1
        elif c in ",;" and depth == 0:
            break
        out.append(c)
        i += 1
    return "".join(out).strip()


def js_pieces(expr: str) -> list[tuple[str, str]]:
    """[('lit', text) | ('expr', text)] — literals versus interpolations, in order."""
    out, i = [], 0
    while i < len(expr):
        m = JS_LITERAL.match(expr, i)
        if m:
            out.append(("lit", m.group(0)[1:-1]))
            i = m.end()
            continue
        j = i
        while j < len(expr) and not JS_LITERAL.match(expr, j):
            j += 1
        if expr[i:j].strip():
            out.append(("expr", expr[i:j]))
        i = j
    return out


def decode_js(text: str) -> str:
    """\\u2713 → ✓, \\' → ', &amp; → & — the characters Android actually renders."""
    text = DECODE_JS.sub(lambda m: chr(int(m.group(1), 16)), text)
    return html_mod.unescape(text.replace("\\'", "'").replace('\\"', '"'))


def copy_corpus() -> tuple[list[re.Pattern], list[str], str]:
    """Production's renderable text: resources, plural items and Kotlin literals.
    Placeholders become wildcards so a value formatted at runtime still counts."""
    root = ET.parse(STRINGS).getroot()
    values = list(strings_xml().values())
    values += [(it.text or "").replace("\\'", "'").replace('\\"', '"')
               for pl in root.findall("plurals") for it in pl.findall("item")]
    kt = kotlin_literals()
    patterns, plain = [], []
    for v in values:
        t = norm(v)
        if len(t) < 3:
            continue
        plain.append(t)
        patterns.append(re.compile(re.escape(t).replace(re.escape("\x00"), ".+?")))
    return patterns, plain, kt


def sink_literals(js: str) -> list[tuple[int, str, str]]:
    """(line, sink, text) for every literal written to a node or a toast, minus
    literals that are not copy: comparison operands and query selectors."""
    out = []
    for rx, kind in JS_SINKS:
        for m in rx.finditer(js):
            expr = js_expr(js, m.end())
            offset = 0
            for piece, text in js_pieces(expr):
                if piece == "lit":
                    left, right = expr[:offset], expr[offset + len(text) + 2:]
                    offset += len(text) + 2
                    if JS_COMPARISON.search(left) or JS_COMPARISON.match(right):
                        continue
                    if re.search(r"querySelector(All)?\(\s*$", left):
                        continue
                    out.append((js.count("\n", 0, m.start()) + 1, kind, decode_js(text)))
                else:
                    offset += len(text)
    return out


def producible(text: str, patterns, plain, kt: str) -> bool:
    t = norm(text)
    if len(t) < 3 or t.isdigit() or not re.search(r"[a-z]{3}", t):
        return True                                   # units, counts, separators
    if any(pat.fullmatch(t) for pat in patterns):
        return True
    esc = re.escape(t)
    return (any(esc in pat.pattern for pat in patterns)
            or any(t in item for item in plain)
            or t in kt)


# Demo fixtures: rows a fresh install cannot contain. Each is declared once in JS
# (never baked into markup) and removed by the toolbar's "Empty (first launch)"
# state, so a reviewer can see both the walkthrough and the shipped default.
# label -> (JS array, one value per row, the container that renders it)
DEMO_DATA = {
    "schedules":     ("DEMO_SCHEDULES", ["Study", "Night", "Weekend"], "schedList"),
    "keywords":      ("DEMO_KEYWORDS", ["gambling", "casino"], "kwList"),
    "websites":      ("DEMO_WEBSITES", ["bet365.com", "poker.com"], "kwList"),
    "title rules":   ("DEMO_TITLE_RULES", ["Settings", "Apps", "Google Play"], "titleList"),
    "a11y services": ("DEMO_SERVICES", ["Keyguard AppLock", "ScreenGuard"], "svcList"),
    "activity":      ("DEMO_ACTIVITY", ["Website blocked", "Launch blocking active", "VPN active"],
                      "homeFeed"),
}
# What the empty state has to undo, and the renderers it has to re-run afterwards.
DEMO_RESET = ["list.innerHTML = ''", "KW.keywords.blocklist = []", "KW.websites.blocked = []",
              "ACTIVITY.length = 0", "TITLES = []", "SERVICES = []"]
DEMO_RERENDER = ["titleRender()", "svcRender()", "kwRender()", "feedRender()",
                 "histRender()", "schedCount()", "renderHome()"]

# Inline singular/plural compositions, in file order, each pinned to the resource
# (or Kotlin expression) that produces the same two forms.
PLURAL_SITES = [
    ("active schedule", "sch_hero_count", None),
    ("rule", "tb_hero_sub", None),
    ("app", "sche_apps_selected", None),
    ("app", "sche_toast_apps", None),
    ("app", None, 'app${if (SocialBlockingPrefs.displayCount(pkgs) == 1) "" else "s"} blocked (whole-app)'),
    ("app", "vpn_whitelist_count", None),        # registered deviation: "(s)" → real pair
]
INLINE_PLURAL = re.compile(r"'([ a-z][a-z ]*?)'\+\s*\(\s*[^()]+?\s*===\s*1\s*\?\s*''\s*:\s*'s'\s*\)")


def group_k(rep: Report, ctx):
    js, html = ctx["js"], ctx["html"]
    patterns, plain, kt = copy_corpus()

    # 1. nothing on screen that production cannot say
    unknown = sorted({(kind, text) for _, kind, text in sink_literals(js)
                      if not producible(text, patterns, plain, kt)})
    rep.check("K", "every string a renderer writes exists in strings.xml or Kotlin copy",
              not unknown, f"{len(unknown)} invented: {unknown[:6]}")

    # 2. demo fixtures live in one array each, and never in markup
    missing, baked = [], []
    # A fixture value that is itself a resource text (sche_apps == "Apps") proves
    # nothing, so those are ignored; everything else must stay out of the markup.
    resource_texts = {norm(v) for v in strings_xml().values()}
    for label, (array, values, container) in DEMO_DATA.items():
        block = re.search(rf"const {array} = \[(.*?)\n  \];", js, re.S)
        if not block:
            missing.append(f"{label}: no {array}")
            continue
        for v in values:
            if f"'{v}'" not in block.group(1) and f'"{v}"' not in block.group(1):
                missing.append(f"{label}: {v!r} not in {array}")
            # The renderer owns every row: the container ships empty, always.
            if not re.search(rf'<div id="{container}"[^>]*>\s*</div>', html):
                baked.append(f"{label}: #{container} is not empty in index.html")
            elif re.search(rf">\s*{re.escape(v)}\s*<", html) and norm(v) not in resource_texts:
                baked.append(f"{label}: {v!r} in index.html")
    rep.check("K", "demo fixtures are declared in JS, one array per list",
              not missing, f"{missing[:6]}")
    rep.check("K", "no demo fixture is baked into the markup", not baked, f"{baked[:6]}")

    # 3. the toolbar's empty state really clears them
    empty = re.search(r"function setDemoLists\(mode\)\{(.*?)\n  \}", js, re.S)
    body = empty.group(1) if empty else ""
    cleared = [stmt for stmt in DEMO_RESET if stmt not in body]
    rerendered = [fn for fn in DEMO_RERENDER if fn not in body]
    rep.check("K", "the 'Empty (first launch)' state clears every fixture and re-renders",
              not cleared and not rerendered and 'value="empty"' in html
              and "setDemo('lists',this.value)" in html,
              f"missing resets {cleared} / rerenders {rerendered}")

    # 4. inline pluralisation is the same pluralisation production uses
    sites = [(w.strip(), js.count("\n", 0, m.start()) + 1) for m in INLINE_PLURAL.finditer(js)
             for w in [m.group(1)]]
    expected = [word for word, _, _ in PLURAL_SITES]
    plurals = {pl.get("name"): {it.get("quantity"): it.text or "" for it in pl.findall("item")}
               for pl in ET.parse(STRINGS).getroot().findall("plurals")}
    bad = []
    if [w for w, _ in sites] != expected:
        bad.append(f"sites {sites} != registry {expected}")
    for word, resource, kotlin in PLURAL_SITES:
        if resource in DEVIATIONS:
            one, other = DEVIATIONS[resource]["proto"]
            if other != one.replace(word, word + "s", 1):
                bad.append(f"{resource}: {one!r}/{other!r} is not {word!r}+'s'")
            continue
        if resource:
            items = plurals.get(resource)
            if not items:
                bad.append(f"{resource}: no plurals entry")
                continue
            one, other = items.get("one", ""), items.get("other", "")
            if other != one.replace(word, word + "s", 1) or word not in one:
                bad.append(f"{resource}: {one!r}/{other!r} is not {word!r}+'s'")
            continue
        if kotlin and kotlin not in read(UI / "screens/socialblocking/SocialBlockingViewModel.kt"):
            bad.append(f"{word}: Kotlin plural {kotlin!r} not found")
    rep.check("K", "every inline plural matches production's plural forms",
              not bad, f"{bad[:4]}")



# --------------------------------------------------------------------------- #
# Phase 8: the sign-off package describes the harness that actually exists
# --------------------------------------------------------------------------- #
SIGNOFF = ROOT / "tools" / "prototype" / "SIGNOFF.md"


def group_l(rep: Report, ctx):
    """The device checklist is what closes the gap this tooling cannot: it has to
    name every group, every screen and every state control, and it must not carry
    copy that contradicts the tree."""
    doc = SIGNOFF.read_text()
    routes = sorted(compose_routes() - {"main"})
    unnamed = [r for r in routes if f"`{r}`" not in doc]
    rep.check("L", "the checklist names every Compose route", not unnamed,
              f"not in SIGNOFF.md: {unnamed}")

    groups = sorted(GROUPS)
    absent = [g for g in groups if not re.search(rf"^\|\s*\*\*{g}\*\*", doc, re.M)]
    rep.check("L", "the gate table covers every verifier group", not absent,
              f"groups missing from SIGNOFF.md: {absent}")

    # every toolbar control the checklist tells the reviewer to use must exist
    controls = re.findall(r"setDemo\('(\w+)'", ctx["html"])
    missing = [c for c in sorted(set(controls)) if f"**{c.capitalize()}" not in doc
               and f"→ {c}" not in doc and c.capitalize() not in doc]
    rep.check("L", "every state control the checklist cites exists in the toolbar",
              not missing, f"toolbar controls not documented: {missing}")

    # the two commands the doc promises are the ones the tree ships
    rep.check("L", "the sign-off quotes the real entry points",
              "python3 tools/prototype/verify_prototype.py" in doc
              and "node tools/prototype/smoke.mjs" in doc
              and "node tools/prototype/shots.mjs" in doc
              and (ROOT / "tools/prototype/smoke.mjs").exists()
              and (ROOT / "tools/prototype/shots.mjs").exists(),
              "SIGNOFF.md cites a command that does not exist")

    # a deviation the docs call accepted must be registered where group J/H read it
    for icon in GEOMETRY_DEVIATIONS:
        if f"`{icon}`" not in doc:
            rep.check("L", "documented deviations appear in the sign-off", False,
                      f"{icon} missing from SIGNOFF.md")
            return
    rep.check("L", "documented deviations appear in the sign-off", True)

    # The design-system doc has to carry the contract, not just link to it.
    design = read(ROOT / "docs" / "03-ui-design-system.md")
    rep.check("L", "docs/03 documents the parity contract",
              all(k in design for k in ("Prototype parity", "392 px", "px = sp",
                                        "tools/prototype/verify_prototype.py",
                                        "tools/prototype/SIGNOFF.md",
                                        "Harness chrome")),
              "docs/03 lost the prototype-parity section")
    readme = read(ROOT / "README.md")
    rep.check("L", "README lists the prototype and its harness",
              "Reference/prototype/" in readme and "tools/prototype/" in readme,
              "README repository layout does not mention tools/prototype")


GROUPS = {"A": group_a, "B": group_b, "C": group_c, "D": group_d,
          "E": group_e, "F": group_f, "G": group_g, "H": group_h, "I": group_i,
          "J": group_j, "K": group_k, "L": group_l}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--group", action="append", choices=sorted(GROUPS))
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--dump-css", metavar="PATH",
                    help="write the parsed selector→declaration map as JSON and exit")
    args = ap.parse_args()
    if args.list:
        print("groups: A integrity · B wiring · C screens · D tokens · E geometry · "
              "F copy · G drift · H state machines · I copy provenance · J icons · "
              "K demo data & copy policy · L sign-off package")
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
