# 01 — Architecture

This document maps the SafeMe codebase: layers, modules, data flow, and how
the components relate. It is derived from the current implementation — if a
mechanism is not described here, it does not exist in the code.

## 1. Big picture

SafeMe is a single-`applicationId` Android app. The UI is Jetpack Compose
hosted by a `FragmentActivity` (`MainActivity`) plus one auxiliary
`ComponentActivity` (`BlockGateActivity`) that renders the full-screen block
overlay over *other* apps.

There are two long-running services, both declared in the manifest:

- `service.SafeMeAccessibilityService` — the content-blocking engine and the
  enforcer of schedule launch-blocks and Prevent-Uninstall guards.
- `service.SafeMeVpnService` — a `VpnService` that performs DNS filtering by
  advertising a family-safe resolver, and per-app internet blocking for
  schedules.

Neither service holds app state. All state lives in Jetpack DataStore files;
services and UI read the same flows.

## 2. Layer map

```
┌────────────────────────── UI (ui/) ───────────────────────────┐
│ OnboardingNavHost → MainScreen (5 tabs) → per-feature screens  │
│ AppLockGateHost (overlay) · BlockGateActivity (over other apps)│
│ theme/ (colors, typography) · components/ (nav bar, pickers)   │
└──────────────┬─────────────────────────────────┬───────────────┘
               │ flows (collectAsState)          │ suspend calls
┌──────────────▼─────────────────────────────────▼───────────────┐
│                        data/ (DataStore)                        │
│ BlockingPrefs · BlockScreenPrefs · FocusPrefs · VpnPrefs     │
│ QuickActionsPrefs · AppLockPrefs · OnboardingPrefs · A11y-      │
│ ProtectionPrefs · PreventUninstallPrefs · ActivityLog ·         │
│ BackupCodec · AppCatalog                                        │
└───────┬──────────────────┬──────────────────────┬───────────────┘
        │ flows            │ calls                │ calls
┌───────▼───────┐   ┌──────▼──────────┐   ┌────────▼───────────────┐
│ protect/      │   │ service/        │   │ vpn/                   │
│ FocusEngine│   │ SafeMeAccessi-  │   │ DnsPreset · VpnConfig  │
│ A11yProtection│   │ bilityService   │   │ TunnelRestartPolicy    │
│ Guard/Utils   │   │ SafeMeVpnService│   │ VpnStatusStore         │
│ AppLockManager│   │ VpnBootReceiver │   │                        │
│ DeviceAdmin   │   │                 │   │                        │
└───────────────┘   └─────────────────┘   └────────────────────────┘
```

**Layering rule:** `ui/` never touches Android services directly — it calls
`data/` suspend functions or collects flows. `protect/` and `service/` may
call `data/`, never the reverse. `vpn/` is a thin, mostly pure layer consumed
by `service/SafeMeVpnService`.

## 3. Module-by-module

### `data/` — persistence and pure domain logic

One `preferencesDataStore` per domain (each is a separate file on disk):

| DataStore file | Contents |
|---|---|
| `safeme_prefs` | blocklist keywords, whitelist keywords, blocked websites, trusted websites, title-block rules, master `blockingEnabled`, `blockedToday` counter |
| `schedule_prefs` | schedules (JSON array), a11y warning dismissed flag |
| `quick_actions_prefs` | enabled quick actions in display order |
| `safeme_applock_prefs` | lock type, PBKDF2 hash (`salt:hash`), credential length, biometric flag, forgot-disabled flag, auto-lock delay |
| `onboarding_prefs` | onboarding-complete flag, theme preference |
| `a11y_protection_prefs` | protection master switch, protected component set |
| `safeme_pu_prefs` | prevent-uninstall switch |
| `block_screen_prefs` | block-gate appearance: dwell countdown, custom message, motivation image, "why" toggle |
| `vpn_prefs` | enabled, preset, custom v4/v6, whitelist, notification mode/copy |
| `activity_log_prefs` | recent-activity entries (capped at 50, deduped) |

Conventions (see `BlockingPrefs.kt` as the template):

- Every file exposes a `Context.xxxPrefs(): Flow<T>` reader with
  `.catch { emit(emptyPreferences()) }` so a corrupt store degrades to
  defaults instead of crashing collectors.
- Mutators are `suspend fun Context.xxx(...)` that use
  `dataStore.edit { ... }` — atomic per file.
- Enum-like persisted values go through resilient lookup
  (`fromName` / `fromStorage`) that falls back to a default on unknown values.
- `writeXxxPrefs(state)` functions exist for backup restore — each replaces a
  whole domain in one atomic edit.

Other `data/` files:

- `BackupCodec.kt`, `BackupManager.kt`, `Jsonc.kt` — see
  [08 — Backup & Restore](08-backup-restore.md).
- `AppCatalog.kt` — app discovery + categorization; see
  [09 — App Picker](09-app-picker.md).
- `BundledKeywords.kt`, `BundledAdult.kt` — built-in adult keyword/website
  lists that seed blocking without any user configuration.
- `ActivityLog.kt` — feed entries: `block`, `schedule`, `vpn`, `a11y` types,
  with a pure `appendActivity` dedupe-and-cap helper.

### `protect/` — enforcement and anti-tamper

- `FocusEngine` — the **only** component that turns schedule rules into
  enforcement. Idempotent; pushes launch blocks to the a11y service and
  internet blocks to the VPN, re-arms the boundary alarm, and logs
  transitions. See [06 — Schedule blocking](06-schedule-blocking.md).
- `FocusEvaluator` — pure, Android-free decision core (day/window
  evaluation, `nextBoundary`). Unit-tested.
- `FocusAlarmReceiver` — boot/update re-arm + exact boundary alarm
  (inexact fallback on API 31+ without the exact-alarm permission; a
  Schedule-screen banner offers the grant).
- `A11yProtectionGuard` / `A11yProtectionUtils` / `A11yProtectionStateHolder` —
  watches protected accessibility services (ContentObserver on the enabled
  list + master switch, plus a 30 s polling fallback) and re-enables them.
  See [04 — Security architecture](04-security-architecture.md).
- `A11yBootReceiver` — restores the a11y protection after boot / update.
- `AppLockManager` / `AppLockStateHolder` / `AppLockBiometrics` — PBKDF2
  credential store, rate limiting, biometric unlock. See
  [04 — Security architecture](04-security-architecture.md).
- `DeviceAdminUtils` — Device Admin receiver + activation/removal for
  anti-uninstall.
- `PreventUninstallBlockers.kt` — text/class markers for the PU guards.
- `ProtectedSystemScreens.kt` — pure rules identifying Settings screens; the
  single source of truth for the a11y-management and uninstall surfaces.
- `ProtectionLayers.kt` — pure evaluator for the 10-layer Home shield summary.

### `service/` — the enforcement engines

- `SafeMeAccessibilityService` — window-state-change engine: collects visible
  text, matches keywords/websites/title rules, raises `BlockGateActivity`,
  applies schedule launch blocks, and runs the Prevent-Uninstall page guards.
  See [05 — Blocking engine](05-blocking-engine.md).
- `SafeMeVpnService` — DNS-filter tunnel + per-app schedule blocks + watchdog.
  See [07 — VPN / DNS filtering](07-vpn-dns-filtering.md).
- `VpnBootReceiver` — restarts the tunnel after boot / update when the
  persisted enabled flag is set.

### `ui/` — Compose

- `screens/main/MainScreen.kt` — `NavHost` with 4 bottom-tab destinations
  (`home`, `block`, `focus`, `profile`) and ~20 secondary routes.
- `screens/permissions/OnboardingNavHost.kt` — first-run flow
  (`welcome` → notifications → battery → accessibility → `main`), with a
  skip-if-permissions-already-granted fast path.
- One directory per feature screen; each screen is paired with a
  `XxxViewModel` (where state is needed) and `XxxIcons.kt`.
- `theme/` — `AppColors` light/dark palettes, `SafeMeTheme`, typography.
- `components/` — `BottomNavBar`, `ToastHost`, `Effects` (blurred shadow),
  `GroupedAppPicker` (shared grouped picker list).

### `vpn/` — DNS + tunnel policy

- `VpnConfig.kt` — `DnsPreset` (Cloudflare Family / AdGuard Family / Custom)
  and pure IPv4/IPv6 validation.
- `TunnelRestartPolicy.kt` — pure watchdog decision logic (errno
  classification, anti-storm cooldown). Unit-tested.
- `VpnStatusStore.kt` — process-wide `StateFlow` of "tunnel is up", kept in
  sync with the real network state so the UI reflects system-side revocation.

## 4. Startup sequence

```
SafeMeApp.onCreate (process start)
├─ ThemePrefHolder.pref = runBlocking { themePref().first() }   // no first-frame flash
├─ appScope (SupervisorJob + Default)
│  ├─ collect themePref → ThemePrefHolder.pref                  // live theme changes
│  ├─ collect a11yProtectionPrefs → StateHolder + guard start/stop
│  ├─ collect appLockPrefs → AppLockStateHolder + gate re-eval
│  ├─ collect focusPrefs → FocusEngine.apply(...)         // re-applies + re-arms alarm
│  └─ 60 s safety ticker → FocusEngine.reevaluate (only while a schedule is enabled)

MainActivity.onCreate
├─ window background set from ThemePrefHolder.pref (before first frame)
├─ setContent { SafeMeApp { OnboardingNavHost() + AppLockGateHost() } }
└─ onResume → a11y self-heal + ensureWatching (no-ops when protection off)

BlockGateActivity (raised by the a11y service over an offending app)
└─ BlockOverlay (dwell countdown, "why on" toggle) + blockedToday++
```

## 5. Key data flows

### Content blocking (per window change)

```
AccessibilityEvent (TYPE_WINDOW_STATE_CHANGED)
  → SafeMeAccessibilityService.handleEvent
  → skip own package; PU guards; schedule launch gate
  → cached BlockingPrefsState (DataStore flow)
  → collectTexts() (event text + bounded node walk, max depth 12 / 200 strings)
  → findMatch(): whitelist beats blocklist → bundled + custom keywords →
    websites (domain-suffix) → bundled websites
  → findTitleMatchIfSettings(): title rules against window title only
  → BlockGateActivity (cooldown 4 s per window+match)
```

### Schedule enforcement

```
DataStore change / boundary alarm / 60 s ticker
  → FocusEngine.apply / reevaluate
  → FocusEvaluator.evaluate (union of active rules)
  → a11y service (launch set) · VPN (internet set) · next boundary alarm
```

### App Lock

```
AppLockStateHolder (cached from DataStore) → AppLockGateHost overlay
  MainActivity.onStart → onAppForeground() → lock per auto-lock delay
  unlock → AppLockManager.verify (PBKDF2, constant-time compare, rate limit)
```

## 6. Process components and receivers

| Component | Manifest | Purpose |
|---|---|---|
| `SafeMeApp` | `android:name` | caches + coordinators |
| `MainActivity` | exported, launcher | app UI host |
| `BlockGateActivity` | non-exported, excludeFromRecents | block overlay over other apps |
| `SafeMeAccessibilityService` | `BIND_ACCESSIBILITY_SERVICE` | content blocking, schedule launch, PU guards |
| `SafeMeVpnService` | `BIND_VPN_SERVICE`, specialUse FGS | DNS filter + schedule internet blocks |
| `VpnBootReceiver` | boot + package-replaced | restart VPN if enabled |
| `A11yBootReceiver` | boot + package-replaced | restore a11y protection |
| `FocusAlarmReceiver` | custom action + boot + replaced | boundary re-apply |
| `DeviceAdminUtils$SafeMeDeviceAdminReceiver` | `BIND_DEVICE_ADMIN` | anti-uninstall |

## 7. State holders (process-wide caches)

Several hot paths must not block on DataStore reads. These objects are fed by
DataStore collectors started in `SafeMeApp` and read synchronously:

- `ui.theme.ThemePrefHolder` — stored theme pref (also fixes the window color
  before the first frame).
- `protect.AppLockStateHolder` — lock config for the gate overlay.
- `protect.A11yProtectionStateHolder` — protection switch + component set.
- `service.SafeMeAccessibilityService.cachedState` / `cachedPuEnabled` —
  blocking + PU state for event matching.
- `vpn.VpnStatusStore` — live tunnel state.
- `FocusEngine` fields — the active launch/internet block sets.

These are intentionally `@Volatile` singletons; a stale read is always safer
than a blocking one on a hot path.
