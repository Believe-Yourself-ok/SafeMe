# 04 — Security architecture

This document covers permissions, App Lock, Prevent Uninstall / Device Admin,
and Accessibility Service protection. For the content-blocking engine itself
see [05 — Blocking engine](05-blocking-engine.md).

## 1. Permissions model

Declared in `AndroidManifest.xml`:

| Permission | Purpose | Notes |
|---|---|---|
| `POST_NOTIFICATIONS` | Android 13+ notification permission | requested during onboarding |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | onboarding battery-optimization step | the app requests exemption, it does not hold it silently |
| `INTERNET`, `ACCESS_NETWORK_STATE` | DNS resolution / tunnel | |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | VPN foreground service | `specialUse` FGS type with a declared subtype string |
| `RECEIVE_BOOT_COMPLETED` | boot re-arm of VPN / a11y protection / schedules | |
| `WRITE_SECURE_SETTINGS` | a11y self-heal writes | **dev-only, ADB-granted**; see below |
| `USE_BIOMETRIC` | biometric App Lock unlock | |
| `SCHEDULE_EXACT_ALARM` | exact schedule-boundary alarms | user-granted via Settings ("Alarms & reminders"); inexact fallback retained |

The manifest also declares a `<queries>` for `ACTION_MAIN`/`LAUNCHER` so the
App Picker can enumerate launcher activities on Android 11+.

### `SCHEDULE_EXACT_ALARM` (user-granted, Play-declared)

Schedule boundaries (blocking starts/stops at parent-set times) need
precisely-timed alarms: without the grant, `FocusAlarmReceiver` degrades
to inexact `set()` and boundaries can land minutes late. The manifest
declares `SCHEDULE_EXACT_ALARM` — NOT the Play-restricted `USE_EXACT_ALARM`,
which is reserved for clock/calendar apps. On Android 14+ the grant is
denied by default, so the Schedule screen shows a contextual banner (only
when schedules are active) that deep-links to Settings → "Alarms &
reminders". Denied/revoked states keep the inexact fallback plus the 60 s
safety ticker — protection degrades gracefully, never silently breaks.

- Publish-time action: complete the Play Console exact-alarm declaration,
  justifying parental-schedule timing as the user-facing, precisely-timed
  function.

### `WRITE_SECURE_SETTINGS` (intentional lint baseline)

The accessibility self-heal rewrites
`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` / `ACCESSIBILITY_ENABLED`.
That permission is only grantable to development/privileged apps:

```bash
adb shell pm grant com.safeme.app android.permission.WRITE_SECURE_SETTINGS
```

- **Without the grant**, `A11yProtectionUtils.selfHealAll` returns `false`
  and writes nothing; the guard still detects disabled services and posts a
  notification (throttled to once/hour).
- **With the grant**, writes are strictly additive (see §5).
- Android lint flags the manifest entry as `ProtectedPermissions` — this is
  the project's known, pre-existing baseline finding, recorded in
  `app/lint-baseline.xml` so `lintDebug` stays green; new findings still fail
  the build.

## 2. App Lock

### Storage (`data/AppLockPrefs.kt`)

- Lock types: `OFF`, `PIN` (4–6 digits), `PASSWORD` (4+ chars), `PATTERN`
  (4+ dots).
- The credential is **never stored**. `AppLockManager.setLock` hashes with
  PBKDF2-HMAC-SHA256 (100k iterations, 256-bit key, 16-byte random salt) and
  persists `"<saltHex>:<hashHex>"`.
- `credentialLength` is stored separately because the hash cannot reveal it;
  the lock UI needs it to render the dot row and auto-submit. It leaks only
  length, which the prototype's dot row already shows.
- Auto-lock delays: immediately / 15 s / 30 s / 1 m / 5 m / manual-only.

### Verification & rate limiting (`protect/AppLockManager.kt`)

- `verify()` recomputes the hash on `Dispatchers.Default` and compares with a
  constant-time digest compare (`MessageDigest.isEqual`).
- Failed attempts 1–4 are free; 5–9 add exponential backoff
  (1 s, 2 s, 4 s, 8 s, 16 s); the 10th failed attempt locks the app for
  **5 minutes**. State lives in SharedPreferences (`safeme_applock_rate`), so
  killing the process does not reset the counter. A successful unlock resets
  it.
- Corrupt stored hashes (wrong format / non-hex) are rejected, never crashed
  on.

### Gate (`AppLockGateHost` / `AppLockGateController`)

- `AppLockStateHolder` is fed by a DataStore collector in `SafeMeApp`; the
  gate reads it synchronously so a cold start can lock before the first frame.
- `MainActivity.onStart` → `onAppForeground()` applies the auto-lock delay;
  `onStop` → `onAppBackground()`.
- `AppLockBiometrics` wraps the BiometricPrompt for the biometric unlock path.

## 3. Prevent Uninstall & Device Admin

### Device Admin (`protect/DeviceAdminUtils.kt`)

When SafeMe is a Device Admin, Android replaces "Uninstall" in Settings with
"Disable"; tapping Disable first opens the Device-Admin deactivation screen,
which the accessibility guards intercept while Prevent Uninstall is on.

- **Minimal usage**: empty `<uses-policies/>` (no lock/wipe/device-owner
  capabilities). The receiver only handles notifications when deactivated
  (throttled 5 min).
- `onDisableRequested` returns an empty `CharSequence` so Android shows its
  own confirmation dialog — custom messages can cause dismissals on some OEM
  ROMs (MIUI, EMUI).
- Activation goes through `DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN`; there
  is no trusted result, so the Anti-Tamper screen re-checks `isActive()` on
  resume.
- Every call is try/catch wrapped; the safe fallback is `false` / no-op.

### Accessibility guards (`service/SafeMeAccessibilityService.handlePreventUninstall`)

While the PU flag is on, exactly three surfaces are guarded — all identified
by SafeMe's own app name / service label on a settings-family window:

1. **SafeMe's own accessibility-service detail page** — the PU gate (Block
   screen) is raised **first** on every activation; dismissing it bounces to
   HOME, so the user never reaches the page and the gate is never left
   covering an a11y-management screen (the a11y kill vector: Android
   auto-disables a service whose window persistently obscures them).
   Enforcement runs on **every** activation: a background watchdog re-probes
   the active window every 2 s while the foreground is a Settings-family
   package, so the gate appears whether the page is opened directly, reopened,
   reached through any Settings entry point, or already active when the PU
   flag flips on. The kick/probe throttle windows only dedupe event floods —
   they never suppress a genuine reopen.
2. **Other a11y-management screens** (lists, other services' details) — never
   blocked, only self-healed.
3. **SafeMe's own App Info / Device-Admin deactivation / force-stop /
   uninstall-confirmation pages** — blocked with the PU gate. The stock
   uninstall confirmation (`com.android.packageinstaller` UninstallerActivity /
   AlertDialog with "uninstall" text) is part of the surface. The watchdog
   re-checks these surfaces too: a page that is ALREADY active when the PU
   flag flips on, or one the event path failed to classify (tree not ready at
   the window-state-changed event), raises the gate on the next 2 s tick —
   the block is not dependent on a fresh window-state-changed event. While a
   gate is on screen the watchdog pauses probing (the active tree is the
   gate's, not the protected page's) and resumes immediately when the gate is
   dismissed onto a new window — the event path kicks a tick at that instant
   (re-arming the PU gate cooldown so a protected page underneath is re-gated
   at once) rather than waiting for the next cadence. The framework app-name
   probe is throttled above the watchdog cadence (5 s) so it never runs on
   every tick; the walked-texts checks run unthrottled, so a genuine
   activation is never delayed.

Detection rules live in `protect/ProtectedSystemScreens.kt` (pure,
unit-tested):

- `isPuSurface(pkg)` — explicit OEM settings packages + the package-installer
  packages; a `".settings"` substring fallback was deliberately removed so
  third-party apps with `.settings` in their package name never trigger PU
  processing.
- `isAccessibilityManagementScreen(pkg, cls)` — class-name markers for
  a11y-management screens; these are **never** blocked.
- `detailOnlyFingerprint(...)` — a normalized text fingerprint that appears
  only on SafeMe's own service **detail** page (description minus summary),
  used to distinguish detail from list.
- The Device-Admin **activation** screen (`DeviceAdminAdd` with admin not
  active) is never blocked — a stale PU flag must not lock the user out of
  turning the feature on.
- Text/class markers are in `PreventUninstallBlockers.kt`
  (`DEVICE_ADMIN_TEXTS_TO_MATCH`, `FORCE_STOP_TEXTS_TO_MATCH`,
  `APP_INFO_CLASS_MARKERS`, `UNINSTALL_KEYWORDS` — locale-aware for
  force-stop).

**Fail-open**: every branch returns "not blocked" on any error or
uncertainty. PU blocks use a separate cooldown key so they never suppress
keyword blocking.

### The a11y kill vector (critical)

Android actively protects accessibility-management screens: a normal blocking
window over the a11y service detail/enable page causes the system to disable
that service automatically (anti-tapjacking / consent-integrity protection)
within ~1–5 s. This is why the block gate never covers those pages and why
SafeMe's own detail page is evicted rather than blocked.

## 4. Protection layers summary

`ProtectionLayersEvaluator` counts 10 layers; each is "active" only when the
mechanism is genuinely functional right now (e.g. the VPN layer needs the
feature on AND consent granted):

1. `master` — blocking switch
2. `accessibility` — service enabled
3. `vpn` — DNS filter active
4. `appLock` — a lock type configured
5. `a11yProtection` — protection switch on
6. `preventUninstall` — PU flag on
7. `schedules` — ≥1 enabled schedule
8. `contentRules` — ≥1 keyword or website rule
9. `titleRules` — ≥1 title rule
10. `deviceAdmin` — admin active

## 5. Accessibility protection (self-heal)

`A11yProtectionUtils` + `A11yProtectionGuard` keep protected accessibility
services enabled.

- **Detection, layer 1** — `ContentObserver` on
  `ENABLED_ACCESSIBILITY_SERVICES` and `ACCESSIBILITY_ENABLED` fires
  instantly; triggers a background self-heal.
- **Detection, layer 2** — a 30 s poll (runs only while protection is on)
  catches OEMs that don't deliver change notifications, including
  *listed-but-unbound* services (OEM battery killers), detected via
  `AccessibilityManager.getEnabledAccessibilityServiceList`.
- **Repair** — `selfHealAll` is strictly **additive** and writes only when
  something changed (no observer loop). It appends SafeMe's own service plus
  the user-selected components. `rebindIfListedButUnbound` churns a listed
  but unbound entry to force a rebind (throttled 5 min per component).
- **Guard gating** — every action is a no-op while the protection toggle is
  off; the poll self-cancels in that state.
- **Boot re-arm** — `A11yBootReceiver` restores protection after
  `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`.
- **Feed** — a protected service going down appends an `a11y` activity-log
  entry (deduped).
- **Failure UX** — when a write is impossible (no `WRITE_SECURE_SETTINGS`,
  OEM blocked), the guard posts a high-priority notification, throttled to
  once per hour.

The protected-components picker (`ServicePickerScreen`) enumerates every
installed accessibility service via `listAllAccessibilityServices` (own
service first, then alphabetical), and the selected set is stored in
`a11y_protection_prefs`. SafeMe's own service is always protected while the
toggle is on and is not stored.
