"""Icon parity: production ImageVector builders vs the prototype's inline SVGs.

Production draws every icon with Compose's vector DSL (``moveTo``, ``lineTo``,
``arcToRelative``, ``addPathNodes("M…")``), the prototype draws the same glyphs
as SVG shapes. The two notations are not comparable as text — ``h``/``v`` versus
``lineTo``, ``<rect rx>`` versus ``arcToRelative``, a circle written from its
left edge versus from its top — so both sides go through the same pipeline:

    source  ->  absolute segments (M L H V C S Q T A Z)
            ->  polylines (arcs and curves flattened)
            ->  points resampled evenly by arc length, 200 per stroke

Two glyphs match when they have the same number of strokes, each stroke is the
same length (within 0.5) and every resampled point of one is within 0.5 units of
the corresponding point of the other, in any direction and, for closed strokes,
from any starting point. That is the loosest comparison that still fails on a
moved point, a missing segment or a resized glyph — see ``compare``.

Glyphs are collected from every place the prototype draws one: ``<svg>``
elements in ``index.html``, ``<svg>`` strings built in ``app.js``, bare shape
fragments that JS interpolates into an element, and pipe-joined path data in the
``QA_ICON`` style lookup tables (``path_map_glyphs``).

The module is deliberately standalone (no imports from verify_prototype) so the
icon assertions can be run, fuzzed and reasoned about on their own.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass
from pathlib import Path

NUM = r"-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?"


# --------------------------------------------------------------------------- #
# canonical form
# --------------------------------------------------------------------------- #
Point = tuple[float, float]


def _r(v: float) -> float:
    r = round(float(v), 2)
    return 0.0 if r == 0 else r


@dataclass(frozen=True)
class Seg:
    cmd: str
    args: tuple[float, ...]


def hush(segs: list[Seg]) -> list[Seg]:
    """Collapse H/V into L so the two notations compare equal: Compose icons in
    this codebase draw straight runs with lineTo, while the prototype's SVGs
    mostly use h/v. Same geometry, different letters."""
    out: list[Seg] = []
    cur: Point = (0.0, 0.0)
    start: Point = (0.0, 0.0)
    for seg in segs:
        if seg.cmd == "M":
            cur = start = (seg.args[0], seg.args[1])
            out.append(seg)
        elif seg.cmd == "L":
            cur = (seg.args[0], seg.args[1])
            out.append(seg)
        elif seg.cmd == "H":
            cur = (seg.args[0], cur[1])
            out.append(Seg("L", cur))
        elif seg.cmd == "V":
            cur = (cur[0], seg.args[0])
            out.append(Seg("L", cur))
        elif seg.cmd == "Z":
            cur = start
            out.append(seg)
        else:
            out.append(seg)
            a = seg.args
            if seg.cmd in ("C", "S", "Q"):
                cur = (a[-2], a[-1])
            elif seg.cmd == "A":
                cur = (a[-2], a[-1])
    return out


# --------------------------------------------------------------------------- #
# Compose vector DSL -> canonical segments
# --------------------------------------------------------------------------- #
DSL_CALL = re.compile(r"(\w+)\s*\(([^()]*(?:\([^()]*\)[^()]*)*)\)")


def _split_args(raw: str) -> list[str]:
    depth, buf, out = 0, "", []
    for ch in raw:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(buf)
            buf = ""
        else:
            buf += ch
    if buf.strip():
        out.append(buf)
    return [a.strip() for a in out if a.strip()]


def _floats(raw: str) -> list[float]:
    """Numbers from a joined arg list; Kotlin booleans become 1/0 so
    ``arcToRelative(4f, 4f, 0f, false, true, 4f, 4f)`` keeps seven values."""
    out: list[float] = []
    for arg in _split_args(raw):
        low = arg.strip().lower()
        if low == "true":
            out.append(1.0)
            continue
        if low == "false":
            out.append(0.0)
            continue
        m = re.search(NUM, arg)
        if m:
            out.append(float(m.group(0)))
    return out


def kotlin_to_segments(body: str) -> list[Seg]:
    """Parse the calls inside one ``path(...) { … }`` DSL block."""
    segs: list[Seg] = []
    cur: Point = (0.0, 0.0)
    start: Point = (0.0, 0.0)

    for m in DSL_CALL.finditer(body):
        fn, args = m.group(1), _split_args(m.group(2))
        vals = _floats(",".join(args))
        if fn == "moveTo" and len(vals) == 2:
            cur = start = (vals[0], vals[1])
            segs.append(Seg("M", tuple(cur)))
        elif fn == "moveToRelative" and len(vals) == 2:
            cur = start = (cur[0] + vals[0], cur[1] + vals[1])
            segs.append(Seg("M", tuple(cur)))
        elif fn == "lineTo" and len(vals) == 2:
            cur = (vals[0], vals[1])
            segs.append(Seg("L", tuple(cur)))
        elif fn == "lineToRelative" and len(vals) == 2:
            cur = (cur[0] + vals[0], cur[1] + vals[1])
            segs.append(Seg("L", tuple(cur)))
        elif fn == "horizontalLineTo" and len(vals) == 1:
            cur = (vals[0], cur[1])
            segs.append(Seg("H", (vals[0],)))
        elif fn == "horizontalLineToRelative" and len(vals) == 1:
            cur = (cur[0] + vals[0], cur[1])
            segs.append(Seg("H", (cur[0],)))
        elif fn == "verticalLineTo" and len(vals) == 1:
            cur = (cur[0], vals[0])
            segs.append(Seg("V", (vals[0],)))
        elif fn == "verticalLineToRelative" and len(vals) == 1:
            cur = (cur[0], cur[1] + vals[0])
            segs.append(Seg("V", (cur[1],)))
        elif fn in ("curveTo", "curveToRelative") and len(vals) == 6:
            rel = fn.endswith("Relative")
            x1, y1, x2, y2, x3, y3 = vals
            if rel:
                x1, y1 = cur[0] + x1, cur[1] + y1
                x2, y2 = cur[0] + x2, cur[1] + y2
                x3, y3 = cur[0] + x3, cur[1] + y3
            segs.append(Seg("C", (x1, y1, x2, y2, x3, y3)))
            cur = (x3, y3)
        elif fn in ("reflectiveCurveTo", "reflectiveCurveToRelative") and len(vals) == 4:
            rel = fn.endswith("Relative")
            x2, y2, x, y = vals
            if rel:
                x2, y2 = cur[0] + x2, cur[1] + y2
                x, y = cur[0] + x, cur[1] + y
            segs.append(Seg("S", (x2, y2, x, y)))
            cur = (x, y)
        elif fn in ("quadTo", "quadToRelative") and len(vals) == 4:
            x1, y1, x, y = vals
            if fn.endswith("Relative"):
                x1, y1 = cur[0] + x1, cur[1] + y1
                x, y = cur[0] + x, cur[1] + y
            segs.append(Seg("Q", (x1, y1, x, y)))
            cur = (x, y)
        elif fn in ("reflectiveQuadTo", "reflectiveQuadToRelative") and len(vals) == 2:
            x, y = vals
            if fn.endswith("Relative"):
                x, y = cur[0] + x, cur[1] + y
            segs.append(Seg("T", (x, y)))
            cur = (x, y)
        elif fn == "arcTo" and len(vals) == 7:
            rx, ry, rot, large, sweep, x, y = vals
            segs.append(Seg("A", (rx, ry, rot, large, sweep, x, y)))
            cur = (x, y)
        elif fn == "arcToRelative" and len(vals) == 7:
            rx, ry, rot, large, sweep, dx, dy = vals
            x, y = cur[0] + dx, cur[1] + dy
            segs.append(Seg("A", (rx, ry, rot, large, sweep, x, y)))
            cur = (x, y)
        elif fn == "close":
            segs.append(Seg("Z", ()))
            cur = start
        elif fn in ("set,",):
            continue
    return segs


ICON_HEAD = re.compile(r"(?:internal )?val (\w+):\s*ImageVector")
ADD_PATH_NODES = re.compile(r'addPathNodes\(\s*"([^"]+)"\s*\)')


def _brace_block(src: str, open_index: int) -> str:
    """Body of the brace block that starts at ``open_index`` (which is '{')."""
    depth, i = 0, open_index
    while i < len(src):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[open_index + 1 : i]
        i += 1
    return src[open_index + 1 :]


def icon_segments(body: str) -> list[Seg]:
    """Segments for one icon body, whichever way the paths are written.

    Most icons use the stroke DSL (``moveTo``/``curveTo``/…); a few filled ones
    pass raw SVG path data to ``addPathNodes("M17.81 4.47c…")``. Both are read
    here, merged in document order so a mixed icon keeps its painting order.
    """
    nodes = [svg_to_segments(m.group(1)) for m in ADD_PATH_NODES.finditer(body)]
    dsl = kotlin_to_segments(ADD_PATH_NODES.sub("", body))
    return [seg for segs in nodes for seg in segs] + dsl


def kotlin_icons(path: Path) -> dict[str, list[Seg]]:
    """{icon name: canonical segments} for one *Icons.kt file.

    Handles both shapes used in this codebase: a direct ``path(...) { … }``
    builder and the ``iconBuilder(...).apply { strokePath { … } }`` helper —
    every DSL call in the body contributes, in document order.
    """
    out: dict[str, list[Seg]] = {}
    src = path.read_text()
    for m in ICON_HEAD.finditer(src):
        name = m.group(1)
        brace = src.find("{", m.end())  # `by lazy {` … or `.apply {` … or `path(…) {`
        if brace < 0:
            continue
        body = _brace_block(src, brace)
        segs = icon_segments(body)
        if segs:
            out[name] = segs
    return out


def all_kotlin_icons(root: Path) -> dict[str, tuple[str, list[Seg]]]:
    """{icon name: (file name, segments)} across every *Icons.kt under root."""
    out: dict[str, tuple[str, list[Seg]]] = {}
    for f in sorted(root.rglob("*Icons.kt")):
        for name, segs in kotlin_icons(f).items():
            out[name] = (f.name, segs)
    return out


# --------------------------------------------------------------------------- #
# SVG path -> canonical segments
# --------------------------------------------------------------------------- #
NUM_RE = re.compile(NUM)
_PATH_CMDS = "MmZzLlHhVvCcSsQqTtAa"


def svg_to_segments(d: str) -> list[Seg]:
    """SVG path data -> absolute canonical segments.

    Parsed with a scanner rather than a token stream because arc flags are
    written without separators: ``a4 4 0 100-8`` is rx=4 ry=4 rot=0, then the
    two one-character flags ``1`` and ``0``, then dx=0 dy=-8. A naive number
    regex reads ``100`` there and silently loses a whole semicircle.
    """
    segs: list[Seg] = []
    cur: Point = (0.0, 0.0)
    start: Point = (0.0, 0.0)
    pos = 0
    n = len(d)

    def skip() -> None:
        nonlocal pos
        while pos < n and d[pos] in " ,\t\r\n":
            pos += 1

    def at_number() -> bool:
        skip()
        return pos < n and (d[pos].isdigit() or d[pos] in "+-.")

    def number() -> float:
        nonlocal pos
        skip()
        m = NUM_RE.match(d, pos)
        if not m:
            raise ValueError(f"expected a number at {pos} in {d!r}")
        pos = m.end()
        return float(m.group(0))

    def flag() -> int:
        nonlocal pos
        skip()
        if pos < n and d[pos] in "01":
            pos += 1
            return int(d[pos - 1])
        raise ValueError(f"expected an arc flag at {pos} in {d!r}")

    def point(rel: bool) -> Point:
        x, y = number(), number()
        return (cur[0] + x, cur[1] + y) if rel else (x, y)

    while True:
        skip()
        if pos >= n:
            break
        if d[pos] not in _PATH_CMDS:
            raise ValueError(f"unexpected {d[pos]!r} at {pos} in {d!r}")
        cmd = d[pos]
        pos += 1
        rel, up = cmd.islower(), cmd.upper()
        if up == "Z":
            segs.append(Seg("Z", ()))
            cur, start = start, start
            continue
        first = True
        while first or at_number():
            first = False
            if up == "M":
                cur = point(rel)
                start = cur
                segs.append(Seg("M", tuple(cur)))
                up = "L"  # implicit lineto for the rest of the run
            elif up == "L":
                cur = point(rel)
                segs.append(Seg("L", tuple(cur)))
            elif up == "H":
                x = number()
                cur = (cur[0] + x, cur[1]) if rel else (x, cur[1])
                segs.append(Seg("H", (cur[0],)))
            elif up == "V":
                y = number()
                cur = (cur[0], cur[1] + y) if rel else (cur[0], y)
                segs.append(Seg("V", (cur[1],)))
            elif up in "CS":
                k = 6 if up == "C" else 4
                v = [number() for _ in range(k)]
                if rel:
                    v = [cur[i % 2] + x for i, x in enumerate(v)]
                segs.append(Seg(up, tuple(v)))
                cur = (v[-2], v[-1])
            elif up == "Q":
                v = [number() for _ in range(4)]
                if rel:
                    v = [cur[i % 2] + x for i, x in enumerate(v)]
                segs.append(Seg("Q", tuple(v)))
                cur = (v[2], v[3])
            elif up == "T":
                v = [number() for _ in range(2)]
                if rel:
                    v = [cur[0] + v[0], cur[1] + v[1]]
                segs.append(Seg("T", tuple(v)))
                cur = (v[0], v[1])
            elif up == "A":
                rx, ry, rot = number(), number(), number()
                large, sweep = flag(), flag()
                end = point(rel)
                segs.append(Seg("A", (rx, ry, rot, large, sweep, end[0], end[1])))
                cur = end
    return segs


ATTR = re.compile(r'([\w-]+)="([^"]*)"')


def segments_to_d(segs: list[Seg]) -> str:
    """Canonical segments back to SVG path data (for visual diffs and tests)."""
    out: list[str] = []
    for seg in segs:
        args = " ".join(f"{a:g}" for a in seg.args)
        out.append(f"{seg.cmd} {args}".strip())
    return " ".join(out)


def _attrs(raw: str) -> dict[str, float]:
    """Numeric attributes of a shape tag, keyed by name."""
    out: dict[str, float] = {}
    for name, value in ATTR.findall(raw):
        try:
            out[name] = float(value)
        except ValueError:
            continue
    return out


def shape_to_d(tag: str, a: dict[str, float], path_d: str | None) -> str:
    """Normalise an SVG shape to path data.

    Production has no <rect>/<circle> primitive — those glyphs are drawn with
    ``arcToRelative``/``curveTo`` in the DSL — so a rounded rect here has to
    become the equivalent path before the two can be compared.
    """
    if tag == "path":
        return path_d or ""
    if tag == "line":
        return f'M{a.get("x1", 0):g} {a.get("y1", 0):g} L{a.get("x2", 0):g} {a.get("y2", 0):g}'
    if tag == "circle":
        cx, cy, r = a.get("cx", 0), a.get("cy", 0), a.get("r", 0)
        return (f'M{cx - r:g} {cy:g} A{r:g} {r:g} 0 1 0 {cx + r:g} {cy:g} '
                f'A{r:g} {r:g} 0 1 0 {cx - r:g} {cy:g} Z')
    if tag == "rect":
        x, y = a.get("x", 0), a.get("y", 0)
        w, h = a.get("width", 0), a.get("height", 0)
        r = min(a.get("rx", 0) or a.get("ry", 0), w / 2, h / 2)
        if r <= 0:
            return f'M{x:g} {y:g} L{x + w:g} {y:g} L{x + w:g} {y + h:g} L{x:g} {y + h:g} Z'
        return (
            f'M{x + r:g} {y:g} L{x + w - r:g} {y:g} '
            f'A{r:g} {r:g} 0 0 1 {x + w:g} {y + r:g} L{x + w:g} {y + h - r:g} '
            f'A{r:g} {r:g} 0 0 1 {x + w - r:g} {y + h:g} L{x + r:g} {y + h:g} '
            f'A{r:g} {r:g} 0 0 1 {x:g} {y + h - r:g} L{x:g} {y + r:g} '
            f'A{r:g} {r:g} 0 0 1 {x + r:g} {y:g} Z'
        )
    return ""


GLYPH_TOKEN = re.compile(
    r'<svg\b[^>]*>|</svg>|<(?:path|rect|circle|line|polyline)\b[^>]*>', re.S)
GAP_OK = re.compile(r"^[\s'\"`+,]*$")
PATH_MAP_LINE = re.compile(r"^\s*\w+:\s*'((?:[MmLlHhVvCcSsQqTtAaZz0-9.,\-\s|])*M(?:[MmLlHhVvCcSsQqTtAaZz0-9.,\-\s|])*)',?\s*$")


def svg_glyphs(text: str) -> list[tuple[int, str, str]]:
    """Every glyph in a source file as (offset, path data).

    Glyphs arrive in three shapes in this prototype: whole ``<svg>`` elements in
    the HTML, ``<svg>`` elements built inside JS template strings, and bare
    shape tags that JS interpolates into an element (``'<svg>'+art+'</svg>'``,
    where ``art`` is a ``<path>`` in its own literal). A glyph is therefore a
    run of shape tags that nothing but quotes, concatenation or whitespace
    separates, with ``<svg>``/``</svg>`` as hard boundaries.
    """
    out: list[tuple[int, str, str]] = []
    cur: list[str] = []
    start = 0
    last_end = 0
    viewbox = ""

    def flush() -> None:
        nonlocal cur
        if cur:
            out.append((start, " ".join(cur), viewbox))
        cur = []

    for m in GLYPH_TOKEN.finditer(text):
        gap = text[last_end:m.start()]
        tok = m.group(0)
        if not GAP_OK.match(gap):
            flush()
        if tok.startswith("<svg"):
            flush()
            start = m.start()
            hit = re.search(r'viewBox="([^"]*)"', tok)
            viewbox = hit.group(1) if hit else ""
        elif tok.startswith("</svg>"):
            flush()
        else:
            if not cur:
                start = m.start()
            tag = re.match(r"<(\w+)", tok).group(1)
            frag = shape_to_d(tag, _attrs(tok), _d_attr(tok))
            if frag:
                cur.append(frag)
        last_end = m.end()
    flush()
    return out


def path_map_glyphs(src: str) -> list[tuple[int, str, str]]:
    """Glyphs written as pipe-joined path data in a JS lookup table.

    ``QA_ICON = { schedule: 'M4 5h16v16H4z|M4 10h16', … }`` is the same glyph
    library the quick actions render, one entry per row, so each entry is a
    glyph.
    """
    out: list[tuple[int, str, str]] = []
    for m in re.finditer(r"^[^\n]*$", src, re.M):
        hit = PATH_MAP_LINE.match(m.group(0))
        if not hit:
            continue
        out.append((m.start(),
                    " ".join(part.strip() for part in hit.group(1).split("|") if part.strip()),
                    ""))
    return out


def _d_attr(raw: str) -> str | None:
    m = re.search(r'\sd="([^"]+)"', raw)
    return m.group(1) if m else None


# --------------------------------------------------------------------------- #
# flattening — the comparison that survives notation, arc flags and subpath order
# --------------------------------------------------------------------------- #
def _cubic(p0: Point, p1: Point, p2: Point, p3: Point, t: float) -> Point:
    u = 1 - t
    return (u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
            u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1])


def _arc_points(p0: Point, rx: float, ry: float, rot_deg: float, large: int,
                sweep: int, p1: Point, steps: int = 12) -> list[Point]:
    """Endpoint-parameterised elliptical arc -> sampled points (SVG F.6.5)."""
    if rx == 0 or ry == 0 or p0 == p1:
        return [p1]
    rx, ry = abs(rx), abs(ry)
    phi = math.radians(rot_deg)
    cphi, sphi = math.cos(phi), math.sin(phi)
    dx2, dy2 = (p0[0] - p1[0]) / 2, (p0[1] - p1[1]) / 2
    x1p = cphi * dx2 + sphi * dy2
    y1p = -sphi * dx2 + cphi * dy2
    lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if lam > 1:
        scale = math.sqrt(lam)
        rx, ry = rx * scale, ry * scale
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        co = -co
    cxp = co * rx * y1p / ry
    cyp = -co * ry * x1p / rx
    cx = cphi * cxp - sphi * cyp + (p0[0] + p1[0]) / 2
    cy = sphi * cxp + cphi * cyp + (p0[1] + p1[1]) / 2

    def angle(ux: float, uy: float, vx: float, vy: float) -> float:
        dot = ux * vx + uy * vy
        n = math.hypot(ux, uy) * math.hypot(vx, vy)
        a = math.acos(max(-1.0, min(1.0, dot / n))) if n else 0.0
        return -a if (ux * vy - uy * vx) < 0 else a

    theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dtheta = angle((x1p - cxp) / rx, (y1p - cyp) / ry,
                   (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dtheta > 0:
        dtheta -= 2 * math.pi
    elif sweep and dtheta < 0:
        dtheta += 2 * math.pi
    pts = []
    for i in range(1, steps + 1):
        t = theta1 + dtheta * i / steps
        x = cx + rx * math.cos(t) * cphi - ry * math.sin(t) * sphi
        y = cy + rx * math.cos(t) * sphi + ry * math.sin(t) * cphi
        pts.append((x, y))
    return pts


def flatten(segs: list[Seg], cubic_steps: int = 8, arc_steps: int = 12) -> list[list[Point]]:
    """Signed-free polyline per subpath: the drawing as a set of points."""
    segs = hush(segs)
    subs: list[list[Point]] = []
    cur: Point = (0.0, 0.0)
    start: Point = (0.0, 0.0)
    sub: list[Point] = []
    last_ctrl: Point | None = None

    def begin() -> None:
        nonlocal sub
        if len(sub) > 1:
            subs.append(sub)
        sub = [cur]

    for seg in segs:
        a = seg.args
        if seg.cmd == "M":
            begin()
            cur = (a[0], a[1])
            start = cur
            sub = [cur]
            last_ctrl = None
        elif seg.cmd == "L":
            cur = (a[0], a[1])
            sub.append(cur)
            last_ctrl = None
        elif seg.cmd == "C":
            p1, p2, p3 = (a[0], a[1]), (a[2], a[3]), (a[4], a[5])
            for i in range(1, cubic_steps + 1):
                sub.append(_cubic(cur, p1, p2, p3, i / cubic_steps))
            last_ctrl = p2
            cur = p3
        elif seg.cmd == "S":
            p2, p3 = (a[0], a[1]), (a[2], a[3])
            p1 = (2 * cur[0] - last_ctrl[0], 2 * cur[1] - last_ctrl[1]) if last_ctrl else cur
            for i in range(1, cubic_steps + 1):
                sub.append(_cubic(cur, p1, p2, p3, i / cubic_steps))
            last_ctrl = p2
            cur = p3
        elif seg.cmd == "Q":
            q, p3 = (a[0], a[1]), (a[2], a[3])
            c1 = (cur[0] + 2 / 3 * (q[0] - cur[0]), cur[1] + 2 / 3 * (q[1] - cur[1]))
            c2 = (p3[0] + 2 / 3 * (q[0] - p3[0]), p3[1] + 2 / 3 * (q[1] - p3[1]))
            for i in range(1, cubic_steps + 1):
                sub.append(_cubic(cur, c1, c2, p3, i / cubic_steps))
            last_ctrl = None
            cur = p3
        elif seg.cmd == "A":
            pts = _arc_points(cur, a[0], a[1], a[2], int(a[3]), int(a[4]),
                              (a[5], a[6]), arc_steps)
            sub.extend(pts)
            cur = (a[5], a[6])
            last_ctrl = None
        elif seg.cmd == "Z":
            sub.append(start)
            cur = start
            last_ctrl = None
    begin()
    return [s for s in subs if len(s) > 1]


def _resample(pts: list[Point], n: int) -> list[Point]:
    """n points evenly spaced along the polyline (by arc length)."""
    if len(pts) < 2:
        return pts * n if pts else []
    dists = [0.0]
    for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
        dists.append(dists[-1] + math.hypot(x2 - x1, y2 - y1))
    total = dists[-1]
    if total == 0:
        return [pts[0]] * n
    out = []
    for i in range(n):
        target = total * i / (n - 1)
        j = 0
        while j < len(dists) - 2 and dists[j + 1] < target:
            j += 1
        span = dists[j + 1] - dists[j] or 1.0
        t = (target - dists[j]) / span
        (x1, y1), (x2, y2) = pts[j], pts[j + 1]
        out.append((x1 + (x2 - x1) * t, y1 + (y2 - y1) * t))
    return out


def _poly_len(pts: list[Point]) -> float:
    return sum(math.hypot(x2 - x1, y2 - y1) for (x1, y1), (x2, y2) in zip(pts, pts[1:]))


SAMPLE = 200  # points per subpath when comparing (arc length, uniform)


@dataclass(frozen=True)
class Stroke:
    """One subpath as it is drawn: dense points plus whether it is closed."""
    closed: bool
    pts: tuple[Point, ...]

    @property
    def length(self) -> float:
        return _poly_len(list(self.pts))

    def sample(self) -> tuple[Point, ...]:
        return tuple(_resample(list(self.pts), SAMPLE))


def strokes(segs: list[Seg]) -> list[Stroke]:
    """flatten() plus a closed flag per subpath."""
    out: list[Stroke] = []
    closed = False
    for sub in flatten(segs):
        # a subpath is closed when its last point returns to its first
        closed = math.hypot(sub[-1][0] - sub[0][0], sub[-1][1] - sub[0][1]) < 1e-6
        out.append(Stroke(closed, tuple(sub)))
    return out


def stroke_distance(a: Stroke, b: Stroke) -> float:
    """How far apart two strokes are, as the worst point deviation.

    Both strokes are resampled to ``SAMPLE`` points spaced evenly by arc
    length, so the same glyph written in two notations compares equal even when
    the writers started at different places: ``M3 12 A… 9 12 A… 3 12`` (circle
    starting at the left) and ``M6 9 A… 6 15 A… 6 9`` (same circle, starting at
    the top) are the same drawing. A closed loop is aligned by finding where the
    other stroke passes closest to this one's starting point, which is exact to
    one sample instead of one whole shift step. Strokes are also compared
    backwards, because a subpath drawn right-to-left is the same line.

    A gap, an extra segment or a moved point changes either the length or the
    shape, so both are checked.
    """
    la, lb = a.length, b.length
    if abs(la - lb) > 0.5:
        return math.inf
    if la < 0.5:  # a dot: position only
        return math.hypot(a.pts[0][0] - b.pts[0][0], a.pts[0][1] - b.pts[0][1])
    sa = _resample(list(a.pts), SAMPLE)
    sb = _resample(list(b.pts), SAMPLE)
    closed = a.closed and b.closed

    def worst(seq_a: list[Point], seq_b: list[Point], shift: int) -> float:
        n = len(seq_b)
        out = 0.0
        for i, p in enumerate(seq_a):
            q = seq_b[(i + shift) % n] if closed else seq_b[i]
            out = max(out, math.hypot(p[0] - q[0], p[1] - q[1]))
        return out

    best = math.inf
    for seq_b in (sb, sb[::-1]):
        if closed:
            shift = min(range(SAMPLE),
                        key=lambda k: math.hypot(sa[0][0] - seq_b[k][0],
                                                 sa[0][1] - seq_b[k][1]))
        else:
            shift = 0
        best = min(best, worst(sa, seq_b, shift))
    return best


def fingerprint(segs: list[Seg]) -> tuple:
    """Cheap pre-filter: stroke count plus each stroke's length to 0.5.

    Glyph pairs with different fingerprints cannot match (a stroke was added,
    dropped or resized), so they are never compared point by point.
    """
    st = strokes(segs)
    return (len(st), tuple(sorted(round(s.length * 2) / 2 for s in st)))


def match(segs: list[Seg], pool: list[tuple[str, list[Seg]]]) -> tuple[float, str]:
    """Closest glyph in ``pool`` as (deviation, name); ('', inf) when none is
    the same size."""
    key = fingerprint(segs)
    best, where = math.inf, ""
    for name, cand in pool:
        if fingerprint(cand) != key:
            continue
        d = compare(segs, cand)
        if d < best:
            best, where = d, name
    return best, where


def compare(segs_a: list[Seg], segs_b: list[Seg]) -> float:
    """Worst stroke deviation between two glyphs; inf if the stroke counts differ."""
    sa, sb = strokes(segs_a), strokes(segs_b)
    if len(sa) != len(sb):
        return math.inf
    worst = 0.0
    if not sa:
        return 0.0
    free = list(range(len(sb)))
    for stroke in sa:  # greedy pairing; glyphs have at most a handful of strokes
        dists = [(stroke_distance(stroke, sb[j]), j) for j in free]
        d, j = min(dists)
        free.remove(j)
        worst = max(worst, d)
    return worst


