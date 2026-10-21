# Intentional deviations from production (SAD-319)

The prototype reproduces production **verbatim**, including wording and geometry
that looks odd. The only exceptions are strings and glyphs that are defective in
the source itself; each one is listed here, mirrored by a table in
`verify_prototype.py`, and asserted in both directions so this file cannot drift
from the code.

## Copy

| Resource | Production text | Prototype text | Why |
|---|---|---|---|
| `vpn_whitelist_count` | `%1$d app(s) exempt` | `%1$d app exempt` / `%1$d apps exempt` | literal `(s)` renders "1 app(s) exempt"; replaced with a real singular/plural pair (owner decision, SAD-319) |

Nothing else may deviate: group F fails if a production string is missing, group
H fails if a `(s)`-style plural exists in `strings.xml` without a registry entry,
and group K fails on any string a renderer writes that production cannot produce.

## Icon geometry

| Icon | Where production is defective | Prototype draws | Why |
|---|---|---|---|
| `SchSearchIcon` | `schedule/ScheduleSheets.kt` — the circle starts at `moveTo(11, 11)`, the centre, so the r=7 circle around it reaches x=25 and is clipped by the 24dp viewport; the join to the handle (`M21 21 L16.7 16.7`) ends up hanging off the arc | the centred magnifier, r=7 at (11, 11) | the same file's handle and the sibling `KwSearchIcon` / `VpnSearchIcon` both centre at (11, 11), so the missing `moveTo(4, 11)` is a slip, not a design. A Kotlin fix is out of scope (SAD-319: no Kotlin edits), so the prototype keeps the glyph the family draws instead of copying the clip. |

Group J fails if a glyph whose Kotlin is defective is not registered here, and
fails on any other icon the prototype does not reproduce within 0.5 viewBox
units of production's geometry.

## Harness hand-offs (not deviations — the same silence as production)

Four prototype flows stand in for a surface the browser cannot reach. None of
them toasts anything, because production emits no app copy for them either: it
starts a system activity or an OS prompt and returns.

| Prototype flow | Production counterpart | Prototype shows |
|---|---|---|
| `openA11y()` | `ScheduleScreen.kt:118` → `Settings.ACTION_ACCESSIBILITY_SETTINGS` | the banner clears after the simulated return |
| `openExactAlarm()` | `ScheduleScreen.kt:127` → `exactAlarmSettingsIntent(context)` | the banner clears after the simulated return |
| `bioUnlock()` | `AppLockUnlockOverlay.kt` → `BiometricPrompt` | the gate closes (when the lock is configured and the device has biometrics) |
| `lockForgotTap()` | `AppLockUnlockOverlay.kt:376` → `ACTION_SENDTO mailto:support@safeme.app` | nothing — the chooser and mail app are system UI |

The two toasts production *does* own on those screens (`al_toast_bio_unavailable`,
`al_gate_bio_off`) are rendered verbatim.

### Demo data

The walkthrough shows rows a fresh install cannot contain (three schedules, two
keywords, two websites, three title rules, two accessibility services, three
activity entries). Each set lives in exactly one JS array, is registered in the
verifier's `DEMO_DATA`, is never written into markup, and is cleared by the
toolbar's *Lists → Empty (first launch)* state. Activity titles/subs are the
exact strings production logs: `Website blocked` (`BlockGateActivity.kt:78-91`),
`Launch blocking active` / `Scheduled apps can't open` (`ScheduleEngine.kt:140`),
`VPN active` / `Internet filtering is on` (`SafeMeVpnService.kt:369`).
