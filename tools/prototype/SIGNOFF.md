# Prototype sign-off (SAD-319, Phase 8)

Two things have to be true before SAD-319 can be reviewed: **the harness is green**
and **you have walked the prototype on a device against production**. The first is
mechanical. The second is yours — I cannot run Compose here (no SDK, no
emulator), so the visual claim rests on your pass plus every value being traced
to a file:line in the repo.

## 1. The gate

| Command | What it must print |
|---|---|
| `python3 tools/prototype/verify_prototype.py` | `<n>/<n> assertions passed`, exit 0 (12 groups A–L) |
| `node tools/prototype/smoke.mjs` | `smoke: <n>/<n> passed`, `no console errors`, exit 0 |
| `node tools/prototype/shots.mjs` | 48 screenshots (24 screens × light/dark) into `.prototype-shots/` |
| `node tools/prototype/shots.mjs --sheets` | the same plus 14 sheets × light/dark (76 images) |

Node needs Playwright resolvable — `PLAYWRIGHT_MODULE`, a local `node_modules`, or
the sandbox copy. Nothing installs automatically; a missing browser shows up as
`playwright not found`, not as a silent skip.

The verifier prints one line per assertion and exits non-zero on the first
failing group's tally, so it can be pasted into a review as-is. Do not quote the
counts above from this file — run the command; the numbers are what the command
says on your machine.

### What each group proves

| Group | Proves |
|---|---|
| **A** integrity | HTML sections/comments balance, CSS parses, every class that is intentionally style-free is declared with a reason |
| **B** wiring | every `onclick`/handler named in markup exists in JS; the frozen JS surface (function names, ids, `data-*`, the two `localStorage` keys, router) is intact |
| **C** screens | the prototype's screen set equals the Compose routes from `MainScreen.kt` + `OnboardingNavHost.kt` (24 == 24) |
| **D** tokens | all 13 colour tokens, light and dark, equal `ui/theme/Color.kt` |
| **E** geometry | Appendix-A component geometry, px→dp contract (`392 px` = 392 dp) |
| **F** copy | every drift-register string is present, verbatim, with its placeholders |
| **G** drift | the 42-row drift register is closed: deletions gone, additions present, version/package from one constant, troubleshoot is `PlaceholderScreen` |
| **H** state machines | every state variant renders production's copy, including plural pairs and the App Lock gate numbers (`MAX_ATTEMPTS`, 5-minute lockout, 1/2/4/8/16 s backoff) |
| **I** copy provenance | no visible run of markup text exists outside `strings.xml` + Kotlin literals |
| **J** icons | 81 `ImageVector`s parse, every prototype glyph matches a production glyph within 0.5 viewBox units, un-drawn icons are exactly the documented set |
| **K** demo data & copy policy | every string a *renderer* writes is producible by production, demo fixtures are declared once and never baked in, the fresh-install state is real, inline plurals match production's |
| **L** sign-off package | this file still names every Compose route, every group, and every toolbar state control it tells you to use |

## 2. The screenshot pack

`shots.mjs` writes `.prototype-shots/` (gitignored) with `index.html` as a contact
sheet. Screens are captured at 392×852 CSS px — the same number as the dp
viewport — so a screenshot can be compared with a device screenshot pixel for
pixel at 1×.

Compare: `home`, `block`, `schedule`, `keywords`, `titleblock`, `vpn`,
`socialblocking`, `applock`, `backup`, `profile`, `blockscreen`, `about`,
`history`, `servicepicker`, `selfprotect`, `antitamper`, `otherfeatures`,
`quickactions`, `scheduleedit`, `troubleshoot`, and the onboarding trio
(`welcome`, `permissions`, `permbattery`, `perma11y`).

## 3. Device checklist

Run the prototype in one window and the app in another, same screen, same state.
Every row below is reachable in the prototype from the toolbar's **State** panel
(never by editing markup), and `smoke` already asserts the state renders.

Legend: ☐ = to sign. "Proto" = prototype toolbar setting.

### Chrome & navigation

- ☐ Status bar / notch area: the prototype draws its own device chrome (time, 5G, battery). On the device the system draws it — this is the only intentional visual difference, and it is confined to `.statusbar`, `.notch`, `.ptb` (harness chrome, group A).
- ☐ Tab bar: 4 tabs (Home · Block · Schedule · Profile), active pill and glyph weight match.
- ☐ Push/pop animation: full-width slide, ~250 ms in and 200 ms out (`MainScreen.kt:76-82`), reversed on pop.
- ☐ Toast: appears 24 dp above the screen bottom, above the tab bar, never over it.
- ☐ Back: every pushed screen's chip returns to its parent; the tab roots return to Home.

### Home — `home`

- ☐ Proto **Hero → Partially protected / Fully protected / Protection paused**: title, subtitle (`8 of 10 layers active · 2 need attention` / `All 10 layers active` / `Turn on master protection to resume`) and pill (`Attention` / `Protected` / `Paused`) all swap together.
- ☐ The hero ring arc is white on the brand card, track stays warm-grey.
- ☐ Proto **Accessibility → Off** shows the banner; **Checking** and **On** hide it (`HomeScreen.kt:129`).
- ☐ Banner: arc sweep, the glyph's direction, no separate head dot.
- ☐ Proto **Greeting → Morning / Afternoon / Evening** (and **Live**, which follows the clock): `Good <part of day>, Alex`.
- ☐ Quick actions: Add keyword · New schedule · Backup · App lock — icons are production's, the empty variant shows `home_quick_actions_empty` after the last card is removed (Quick actions → Edit).
- ☐ Activity feed: three rows, left-aligned empty state when `Lists → Empty`.

### Block — `block`

- ☐ Stats: `Blocked today` from the activity log, `133` keywords (blocklist + bundled), `3` layers active.
- ☐ Custom Keywords & Web Blocking subtitle reads `N keywords · N websites · N trusted` and matches the keyword screen after an add/remove.
- ☐ Layers card: the layer glyphs (the middle sheet is a rounded sheet, not a sliver).
- ☐ More-blocking tiles: 40 dp icon box, radius 13, 20 dp glyph, 12 dp gap (`BlockingScreen.kt:598-606`).
- ☐ Shield card inner gap 12 dp.

### Schedule — `schedule`

- ☐ Hero count pluralises (`1 active schedule` / `2 active schedules`) and matches the enabled cards.
- ☐ Proto **Exact alarm → Not granted** shows the exact-alarm banner; **Granted** hides it.
- ☐ Proto **Accessibility → Off** with a launch-blocking schedule shows the accessibility banner; both banners disappear when no schedule is enabled.
- ☐ Schedule cards: day summary, `Next · <time>`, app count, disabled card dimmed.
- ☐ Overnight window (e.g. 23:00 → 06:00): the card shows `(+1)` and "Ends next day".
- ☐ Exclude apps card subtitle uses the plural pair.

### Schedule edit — `scheduleedit`

- ☐ Name field placeholder, day chips, time row (start/end wheel), mode selector, `Apps` row (`No apps — schedule blocks everything` when empty).
- ☐ Validation order: empty name → `Give your schedule a name`; no day → `Pick at least one day`; equal times → `Start and end can't be the same`.
- ☐ Time sheet: inline error blocks Done, 0→23 wrap.

### Keyword manager — `keywords`

- ☐ Segments Keywords/Websites and Blocklist/Whitelist; search placeholder switches per type.
- ☐ Add pill is labelled by **type** (`Add keyword` / `Add website`) on every tab — production never says "Add whitelist".
- ☐ Row actions: production's pencil and bin glyphs; delete on a blocked row asks nothing, on a user row removes immediately.
- ☐ Sheet: `Value` field, category chips, `Save` pill; a duplicate shows `Already on the list`; an empty value shows `Keyword can't be empty`.
- ☐ Whitelist tab shows the note `Whitelist keywords override the blocklist.` and hides the category picker.
- ☐ Add a keyword: the toast is `Keyword added to <Category>`, matching production's `kw_toast_added`.

### Title block — `titleblock`

- ☐ Hero: `N rules · N active · matching in real time`; with no rules, `Add a rule to start blocking Settings pages by title.`
- ☐ Search placeholder `Search rules…`; empty state copy.
- ☐ Add flow: empty title → `Enter a title to block`; duplicate → `Rule already exists`; success → `Rule added`.

### DNS & VPN — `vpn`

- ☐ Pill and subtitle for each proto **Vpn** state (toolbar label `DNS / VPN`): On · Off · Custom (IPv4 + IPv6) · System-wide · Schedule block active.
- ☐ Whitelist row: `No apps exempt` or `N app(s) exempt` — production prints the literal `(s)`; the prototype deliberately shows `1 app exempt` / `2 apps exempt` (registered deviation, see `DEVIATIONS.md`).
- ☐ Notification mode chips (Default/Hide/Custom) change silently, as production does.
- ☐ Selecting a preset changes state without a toast; **Custom preset** opens the DNS sheet and saves with `Custom DNS saved`.
- ☐ Custom DNS validation: bad IPv4 shows the inline error, not a toast.

### Social Media Blocking — `socialblocking`

- ☐ Master on/off: subtitle `Whole apps & tabs protected` / `Paused — tap to protect`, pill `Master ON`/`Master OFF`.
- ☐ Quick Focus Mode presets (Deep Work / Balanced / Relax) and their toasts.
- ☐ Launch block list: five default rows, extras from the picker rendered below them with the package name as subtitle, disabled rows at 45 % alpha.
- ☐ Tab rows: YouTube Shorts / Facebook Reels / Snapchat Spotlight with their own subtitles.
- ☐ Footnote card: `How it works — Whole-app blocks on launch. …`

### App Lock — `applock`

- ☐ Off state: hero `App Lock is off`, the single "Set up App Lock" card, the lock-method rows dimmed and untappable.
- ☐ Setup sheet: method → create → confirm; a mismatch keeps the sheet open showing `Codes don't match — try again`.
- ☐ Proto **App Lock gate → Gate (PIN 1234)**: the gate overlay, wrong PIN shakes and shows `al_gate_wrong`, the dots clear.
- ☐ Proto **Gate → Lockout · 5 min**: the guard shows `Too many attempts — try again in Xs` counting down, input disabled.
- ☐ Biometrics available → the biometric row authenticates and closes the gate; unavailable → `Biometrics aren't available on this device`; off in settings → `Turn on Touch ID / Face ID in App Lock settings`.
- ☐ Forgot link switches copy with the method (`Forgot PIN?` / `Forgot password?`) and silently hands to the mail app.
- ☐ Disable → dialog (not a sheet) → confirm → `App Lock is off`.

### Backup & Restore — `backup`

- ☐ Idle: two cards (Export / Restore), the last-backup line, the "what's included" list.
- ☐ Proto **Backup → Exporting… / Restoring…**: both buttons disable and relabel (`Creating backup…` / `Restoring…`).
- ☐ Proto **Backup → Error · not JSONC**: toast `This file isn't valid JSONC`; **Error · rollback**: `Restore failed and couldn't be fully undone`.
- ☐ Restore opens the confirm dialog with `Created — · app 0.1.0 · schema v1`.

### Block screen — `blockscreen`

- ☐ Countdown ticks down live; `Closing in Ns.` with the live number.
- ☐ Dwell slider clamps to 3–120 s; the label reads `Time before Close unlocks • 3-120s`.
- ☐ Rows: motivation image (`None • SAF persistable URI` / `<name> • persistable`), custom message in curly quotes, `Why` row.
- ☐ `Back to the app` and the rating row both toast production's strings.

### Profile — `profile`

- ☐ Greeting block, theme chips (System/Light/Dark) with their three toasts.
- ☐ App Lock card, Backup card, Manage permissions card (`N of 3 granted · finish setup`, recomputed after a return), Troubleshoot card.
- ☐ Support list: version line `v0.1.0 · version info`; About / Website / Privacy rows toast production's copy.
- ☐ Delete account → Material3-style dialog → `Account deletion requested`.
- ☐ Footer: `SafeMe v0.1.0 · open://protectyourself` + `No ads · no trackers · no analytics`.

### About — `about`

- ☐ `Version 0.1.0 · package com.safeme.app`, deep link `open://protectyourself`, Website and Privacy Policy rows.

### Anti-tamper / Self-protection / Service picker

- ☐ Anti-Tamper: hero pill, activate/deactivate toasts (`Activating Prevent Uninstall…` → `Prevent Uninstall activated`).
- ☐ Accessibility Protection: master switch, permission state pill, `ap_toast_permission_granted` / `ap_toast_permission_missing`.
- ☐ Service picker: protected rows outlined in brand @ 50 %, toggling is silent, the empty state appears with `Lists → Empty`.

### Other features / Quick actions / History

- ☐ Other features: Block Image & Video Search switch, the excluded-apps card and its sheet subtitle.
- ☐ Quick actions editor: the seven types, reorder/remove affordances, Add sheet, the empty state.
- ☐ History: entries mirror the Home feed, empty state when `Lists → Empty`.

### Troubleshooting — `troubleshoot`

- ☐ One centred `Troubleshooting` and nothing else — production routes this screen to `PlaceholderScreen(R.string.prof_troubleshoot_title)`. Use the system back gesture to leave.

### Onboarding — `welcome`, `permissions`, `permbattery`, `perma11y`

- ☐ Welcome: logo, serif headline with the accent word, `Get started`, `Resume Setup` for a returning install.
- ☐ Permission steps: `Grant` / `Skip for now`, the progress dots, `Granted ✓` after granting, and the two gating toasts (`Grant the required permissions to continue` / `Skipped — you can grant it later from Profile`).
- ☐ Completing onboarding toasts `Welcome to SafeMe — you're protected` the first time and `All permissions granted — you're fully protected` afterwards.

### Cross-cutting

- ☐ Proto **Lists → Empty (first launch)** on all four screens that own lists: everything empties *and* the numbers follow (`0 keywords · 0 websites · 0 trusted`, `0 active schedules`, `0` blocked today, `133` keywords, `3` layers).
- ☐ Proto **Lists → Populated**: the walkthrough rows come back.
- ☐ Dark theme: all 13 tokens, no hardcoded light-only colour anywhere (tokens are asserted against `Color.kt`, but eyeball the cards and the block screen).
- ☐ Long content: no clipped text at 392 dp; every scroll view scrolls.

## 4. Known, accepted differences

| Difference | Why | Where it is asserted |
|---|---|---|
| Device chrome (status bar, notch, toolbar) | the harness needs a frame to simulate a phone | declared as harness chrome, group A |
| `1 app exempt` vs `1 app(s) exempt` | production's literal `(s)` is a copy defect; decision recorded | registered deviation, groups H + K |
| `SchSearchIcon` drawn centred | production's circle starts at the centre and is clipped | registered deviation, group J |
| Four OS hand-offs are silent | accessibility settings, exact alarms, `BiometricPrompt`, mail chooser — production emits no app copy either | `DEVIATIONS.md`, group K |
| Demo rows | the walkthrough needs content; cleared by `Lists → Empty` | registered in `DEMO_DATA`, group K + smoke |
| Four production icons are not drawn | declared but never referenced by a screen (`A11yPersonIcon`, `BatteryIcon`, `BellIcon`) | `PRODUCTION_ONLY`, group J |

## 5. Sign-off

| | |
|---|---|
| Harness green (`verify` + `smoke`, 0 console errors) | ☐ |
| Screenshot pack regenerated and compared | ☐ |
| Checklist above executed on a device | ☐ |
| Reviewed commit | ☐ `______________` |
| Reviewer / date | ☐ |
