# 03 — UI design system

SafeMe's UI is a Jetpack Compose implementation of a fixed design prototype.
This document describes the design tokens, components, and navigation that
implement it, so new screens can be built without guessing.

## 1. Theme

### Colors (`ui/theme/Color.kt`)

The design system is exposed through `AppColors` (a data class) via a static
`CompositionLocal` (`LocalAppColors`). Two palettes exist: `LightAppColors`
and `DarkAppColors`. Components read `LocalAppColors.current` — never raw
`Color(...)` literals.

| Token | Light | Dark | Use |
|---|---|---|---|
| `brand` | `#D97757` | `#E08A68` | primary / accent (pill buttons, active states) |
| `brandDark` | `#B45A3B` | `#C97A5A` | darker accent (links, "on brand" text) |
| `brandSoft` | `#FBEFE8` | `#3B2A21` | soft brand fills (icon chips) |
| `brandMist` | `#FDF8F4` | `#241E19` | subtle brand-tinted backgrounds |
| `ink` / `ink2` / `ink3` | `#1F1A16` / `#6B625A` / `#A89E94` | `#F2ECE4` / `#C6BCB0` / `#8A8077` | text primary / secondary / tertiary |
| `background` | `#FAF7F3` | `#15120E` | app background |
| `surface` | `#FFFFFF` | `#211C16` | cards, sheets, dialogs |
| `line` | `#EAE3DB` | `#332B22` | borders, dividers |
| `success` / `successBg` | `#2E7D5B` / `#E7F0EC` | `#5FBF92` / `#22332B` | positive states |
| `warning` / `warningBg` | `#C0822B` / `#F7EDDD` | `#D89A4C` / `#3A2E1E` | attention states |
| `danger` / `dangerBg` | `#C4453C` / `#F9E7E5` | `#E2685F` / `#3B2321` | destructive states |
| `iconGreenBg` / `iconAmberBg` / `iconRedBg` | pastel fills | dark fills | status icon chips |
| `iconDarkBg` / `iconDarkFg` | `#3A2C1F` / `#E8B78F` | same | dark chip variants |
| `swOff` | `#DDD5CC` | `#3A3228` | disabled switch/segment |
| `cardDark1` / `cardDark2` | `#171310` / `#2A211B` | same | dark hero cards |
| `previewLabel` / `previewMsg` | `#9A8D80` / `#C9BEB3` | same | preview-screen text |
| `toastBg` / `toastFg` | `#3A332C` / `#FFFFFF` | `#F2ECE4` / `#211C16` | toast |

Material 3's `colorScheme` maps these tokens onto M3 roles (`primary`,
`background`, `surface`, `error`, …) in `Theme.kt`, so stock M3 components
(e.g. `AlertDialog`, switches) pick up the design system automatically.

### Typography (`ui/theme/Type.kt`)

- **Serif family** — `source_serif_4` bundled font. Used for display text:
  `displayLarge` (30 sp bold) and `displayMedium` (26 sp). This is the
  prototype's editorial serif for headlines.
- **UI family** — system `SansSerif` for everything else
  (`bodyLarge` 14 sp, `labelLarge` 16 sp semibold).
- Screens also set explicit `sp` sizes inline (e.g. section titles 18–24 sp
  bold, captions 11.5–12 sp) to match the prototype's pixel metrics.

### Theme resolution (`ui/theme/Theme.kt`)

- `SafeMeApp` (the composable) resolves the stored `ThemePref`
  (SYSTEM / DARK / LIGHT) from DataStore with `ThemePrefHolder.pref` as the
  synchronous initial value — the first frame composes in the correct theme
  and there is no light/dark flash.
- `SafeMeTheme(darkTheme)` installs `LocalAppColors` + the M3 scheme and the
  typography.
- Window chrome: `SafeMeApp` sets transparent status/navigation bars and the
  correct light/dark bar-icon appearance via `WindowCompat` insets controller.
- `MainActivity` additionally sets the window background color from the stored
  theme **before** the first frame (`R.color.bg` / `R.color.bg_night`), since
  the `values-night` theme XML only follows the system.

## 2. Shared components (`ui/components/`)

| Component | Responsibility |
|---|---|
| `BottomNavBar` | 4-tab bottom navigation (Home, Blocking, Schedule, Profile) with icons from `BottomNavIcons.kt`; highlighted current tab |
| `ToastHost` | Displays non-blocking toasts fed by a `SharedFlow<String>` — the standard "operation succeeded/failed" feedback channel |
| `Effects.blurredShadow` | Design-language soft shadow behind cards; lazily builds `Paint`/`BlurMaskFilter` once per modifier and reuses across frames |
| `GroupedAppPicker` | Shared grouped picker (category header + bordered card of rows + search filter) used by schedule apps and VPN whitelist; row rendering is injected per screen |
| `AppCategoryHeader` | The uppercase 11.5 sp/800 category label from the prototype |

### Card language

Cards are `RoundedCornerShape(20.dp)`, surface-colored, with a 1 dp `line`
border and a soft 1 dp shadow (see `cardShape()` in the backup screen for the
canonical modifier). Icon chips inside cards are 44 dp rounded squares
(`RoundedCornerShape(14.dp)`) filled with `brandSoft` and a `brandDark` icon.

## 3. Navigation

### Onboarding (`OnboardingNavHost`)

`startDestination` is `main` when onboarding is complete (or skippable),
else `welcome`:

```
welcome → notifications → battery → accessibility → main
```

`canSkip` checks whether all required permissions are already granted; if so
the flow jumps straight to the main app on first launch.

### Main tabs (`MainScreen`)

Routes `home`, `block`, `schedule`, `profile` show the bottom bar.
Other routes are pushed sub-screens with horizontal slide transitions
(`slideInHorizontally` + `fadeIn`, 250 ms; exit fade 200 ms).

| Route | Screen | Opens |
|---|---|---|
| `home` | `HomeScreen` | keywords, scheduleedit, backup, history, vpn, applock, quickactions, block tab |
| `block` | `BlockingScreen` | blockscreen, vpn, antitamper, keywords, websites, titleblock |
| `schedule` | `ScheduleScreen` | scheduleedit (+ `?editId=` for edit) |
| `profile` | `ProfileScreen` | permissions, backup, troubleshoot (placeholder), about, applock |
| sub | `AboutScreen`, `BackupScreen`, `HistoryScreen`, `QuickActionsEditScreen`, `TitleBlockScreen`, `AppLockScreen`, `AntiTamperScreen` + `AccessibilityProtectionScreen` + `ServicePickerScreen`, `DnsVpnScreen`, `KeywordManagerScreen` (`?type=&tab=`), `ScheduleEditScreen`, `ManagePermissionsFlow` | |

### Screen conventions

- Every screen is a full-bleed `Box`/`Column` with `statusBarsPadding()`;
  the `NavHost` only adds bottom padding from the Scaffold (top padding is
  intentionally not consumed — see the comment in `MainScreen.kt`).
- Each screen has a back-chevron header (`ChevronIcon` in a 40 dp rounded
  chip) except the bottom-tab roots.
- Long screens use `verticalScroll`; pickers use `LazyColumn`.

## 4. Home screen anatomy (reference screen)

`HomeScreen` composes, top to bottom:

1. Greeting ("Good evening, Alex") + date line.
2. **Shield status card** — hero ring driven by `ProtectionLayers` (active
   count over 10, e.g. "2 of 10 layers active"), with a "Review shield"
   action that jumps to the Blocking tab.
3. **Attention banner** — first off layer, e.g. "Accessibility service is
   off".
4. **Quick actions** — grid of the user's curated actions (keyword,
   schedule, backup, websites, vpn, applock, history), editable via
   `QuickActionsEditScreen`.
5. **Blocked today** / **activity feed** — `ActivityLog` entries with
   type-colored dots.
6. Accessibility shortcut row.

## 5. Prototype parity

`Reference/prototype/` is the design artefact this UI was built from, and it is
kept in lock-step with the Kotlin: **production is authoritative**. Colours,
strings, routes, icon geometry and component measurements are all read *from*
`app/src/main/java/...` and `app/src/main/res/values/strings.xml`, and the
prototype is the thing under test.

### Authority model

| Disagreement | Settled by |
|---|---|
| A colour, radius, size or spacing | the Kotlin that draws it (`Color.kt`, the composable) |
| A string | `strings.xml`, including placeholders and plural pairs |
| A screen or sheet existing at all | the Compose route / call site |
| A glyph | the `ImageVector` in `*Icons.kt` |
| Design intent the app has not implemented yet | the prototype, recorded as a drift-register row |

Where production is *defective* (a clipped icon, a literal `(s)` plural), the
prototype keeps the sane form and the deviation is registered in
`tools/prototype/DEVIATIONS.md` rather than silently copied. Production is never
edited to match the prototype.

### The px → dp contract

`.phone` is **392 px** wide, with the bezel as an inset shadow, so
**1 CSS px = 1 dp** and **px = sp**. That is why a screenshot can be compared
with a device screenshot at 1×, and why `shots.mjs` captures at 392×852 CSS px.

**Harness chrome** — the toolbar (`.ptb`), the stage backdrop, the phone frame,
the notch and the simulated status bar — has no production counterpart and is
excluded from every comparison by declaration, not by omission.

### Driving states

Every state a screen can be in is reachable from the toolbar's **State** panel
(hero, accessibility, exact alarm, App Lock gate, backup, lists, DNS/VPN,
biometrics, greeting) — never by editing markup. The rule for a new screen:

1. put the state in the `DEMO` object in `js/app.js`, not in the markup;
2. add the toolbar control in the same commit;
3. add a `smoke` assertion that the rendered copy matches `strings.xml`, and a
   verifier group-H assertion that pins any formatted string;
4. if the screen owns a list, make it empty-able from the same panel.

Demo rows live in one JS array per list and are never baked into markup; the
**Lists → Empty (first launch)** state clears them and recomputes the derived
counts, so the shipped zero state is reviewable too.

### What guards it

`python3 tools/prototype/verify_prototype.py` (static parity, 12 groups) and
`node tools/prototype/smoke.mjs` (runtime, Chromium) must both be green; neither
is part of the Gradle build, so a failure there can never affect the app. The
device checklist lives in `tools/prototype/SIGNOFF.md`.

## 6. Writing a new screen

1. Copy the design tokens: `LocalAppColors.current`, no literals.
2. Match the prototype's spacing (16 dp card padding, 14 dp gaps, 20 dp card
   radius, 40 dp icon chips).
3. Use `ToastHost` for async feedback, `statusBarsPadding()` for insets.
4. Wire it into `MainScreen.kt` `NavHost` with a slide transition.
5. If it edits persisted state, add a `Context.xxx()` mutator in `data/` and
   collect the corresponding flow; never write DataStore from the composable
   directly.
