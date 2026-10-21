# Prototype parity tooling (SAD-319)

Everything here reads the repo and reports facts. Nothing in this folder is
imported by the Android build — `Reference/` is not a Gradle source set — so a
failure here can never affect the app.

| Command | What it proves |
|---|---|
| `python3 tools/prototype/verify_prototype.py` | Static parity: CSS/HTML integrity, JS wiring, screen-set equality with the Compose routes, design tokens vs `ui/theme/Color.kt`, Appendix-A component geometry, production copy, and closure of the SAD-319 drift register. |
| `python3 tools/prototype/verify_prototype.py --group C` | Run one group (A integrity · B wiring · C screens · D tokens · E geometry · F copy · G drift · H state machines · I copy provenance · J icons · K demo data & copy policy). |
| `python3 tools/prototype/verify_prototype.py --dump-css out.json` | Emit the parsed `selector → declarations` map used for the geometry comparisons. |
| `node tools/prototype/smoke.mjs` | Runtime smoke in Chromium (43 checks): 24 screens navigate, 4 tabs, 14 sheets, permission pill, dark theme, renderers don't throw, the Home/schedule/VPN/backup/social state matrices render every variant, the block countdown and App Lock guard run live, the fresh-install state computes production's zero values, silent surfaces stay silent, zero console errors. |
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

## Copy provenance for rendered text (group K)

Markup copy is checked in group I. Text a *renderer* produces is checked in group
K, because it never appears in `index.html`: the verifier walks every
`toast(...)`, `.textContent =`, `.placeholder =` and `setAttribute('placeholder'|…)`
sink in `js/app.js`, takes the string literals out of that expression (skipping
comparison operands and `querySelector` arguments) and requires each one to exist
in `strings.xml` (including plural items) or in a literal the Kotlin renders. A
sentence the app cannot say fails the gate.

**Demo data.** Rows a fresh install cannot contain live in exactly one JS array
each (`DEMO_SCHEDULES`, `DEMO_KEYWORDS`, `DEMO_WEBSITES`, `DEMO_TITLE_RULES`,
`DEMO_SERVICES`, `DEMO_ACTIVITY`), are declared in the verifier's `DEMO_DATA`
registry, and are never baked into markup — the containers that render them ship
empty. The toolbar's **State → Lists → Empty (first launch)** clears every array
and re-runs the renderers, so both the walkthrough and the shipped zero state are
reachable without touching code; `smoke` asserts both directions, including the
derived counts (`0 keywords · 0 websites · 0 trusted`, `133` bundled keywords,
`0 active schedules`).

**Inline plurals.** `' app' + (n === 1 ? '' : 's')` is only allowed where
production pluralises the same word the same way. `PLURAL_SITES` in the verifier
pins each site, in file order, to the `plurals` resource or Kotlin expression
that produces the identical pair, and fails when a site is added, removed or
re-worded.

**Silent where production is silent.** Surfaces that hand over to the OS —
`Settings.ACTION_ACCESSIBILITY_SETTINGS`, the exact-alarm settings intent,
`BiometricPrompt`, the `ACTION_SENDTO` mail chooser — change state in the
prototype without toasting a sentence, because production emits no app copy
there either. See `DEVIATIONS.md`.
