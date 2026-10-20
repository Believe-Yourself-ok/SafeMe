# Prototype parity tooling (SAD-319)

Everything here reads the repo and reports facts. Nothing in this folder is
imported by the Android build — `Reference/` is not a Gradle source set — so a
failure here can never affect the app.

| Command | What it proves |
|---|---|
| `python3 tools/prototype/verify_prototype.py` | Static parity: CSS/HTML integrity, JS wiring, screen-set equality with the Compose routes, design tokens vs `ui/theme/Color.kt`, Appendix-A component geometry, production copy, and closure of the SAD-319 drift register. |
| `python3 tools/prototype/verify_prototype.py --group C` | Run one group (A integrity · B wiring · C screens · D tokens · E geometry · F copy · G drift · H state machines). |
| `python3 tools/prototype/verify_prototype.py --dump-css out.json` | Emit the parsed `selector → declarations` map used for the geometry comparisons. |
| `node tools/prototype/smoke.mjs` | Runtime smoke in Chromium: 24 screens navigate, 4 tabs, 15 sheets, permission pill, dark theme, renderers don't throw, the Home state matrix renders every variant, zero console errors. |
| `node tools/prototype/shots.mjs [--sheets] [--screen home]` | Screenshot every screen in both themes (392×852 CSS px = the px→dp contract) into `.prototype-shots/` with a contact sheet. |

Requirements: Python 3.11+ (stdlib only). Node 20+ with Playwright resolvable
via `PLAYWRIGHT_MODULE`, a local `node_modules`, or the sandbox copy at
`/opt/playwright/node_modules`. No `npm install` is performed by the repo.

## What is asserted, and against what

- **Authority is production.** Colour tokens, strings, routes, and geometry are
  read from `app/src/main/java/...` and `app/src/main/res/values/strings.xml`.
  The prototype is the thing under test.
- **Screen set** is parsed from `MainScreen.kt` *and* `OnboardingNavHost.kt`
  (literal routes plus the `PERM_ROUTE_*` constants in `PermissionSteps.kt`);
  the `main` host route is excluded because it renders the tab `NavHost`.
- **Units.** `.phone` is 392 px wide with the bezel as an inset shadow, so
  1 CSS px = 1 dp and px = sp. CSS `line-height: 1.3` on 13 px → `17.sp`.
- **Hook classes** that are intentionally style-free (JS lookup hooks, App Lock
  containers) are declared in `HOOK_CLASSES` inside the verifier with a reason,
  so group A stays meaningful instead of being weakened.

## Driving states

The prototype's `.ptb` toolbar has a **State** panel. Every state a screen can be
in is reachable from it — never by editing markup — so a reviewer can see each
production variant (partially protected / fully protected / paused, the three
accessibility states, morning/afternoon/evening greeting) and every variant is
covered by a `smoke` assertion. Add the control in the same commit as the state
object in `js/app.js`, and a matching group-H assertion that pins the rendered
copy to `strings.xml`.

## Formatted copy

Any string production formats from resources (`%1$s`, `%2$d`) must live verbatim
in the prototype's `T` table in `js/app.js` and be rendered with `fmt(T.key, …)`,
never by concatenating fragments. Group F searches the prototype for the
resource text with its placeholders intact, and group H additionally asserts
that every `T` entry is byte-identical to its `strings.xml` value — concatenation
silently passes the first check and fails the second.

See `PROTOTYPE_PARITY_PLAN_SAD-319.md` at the repo root for the full drift
register, phase plan, and the device sign-off checklist.
