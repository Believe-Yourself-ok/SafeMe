# 02 — Design philosophy

This document records the principles and constraints that shape SafeMe's
implementation. New code should conform to these; deviations need a
documented reason.

## 1. Core principles

### 1.1 Fail open, never fail closed on detection

Every blocking decision degrades to *not blocking* on any error. A missed
block is acceptable; a false positive that locks the user out of a legitimate
page or app is not. Concretely:

- Every `onAccessibilityEvent` is wrapped so a malformed event can never crash
  the service.
- The Prevent-Uninstall guards are fail-open by construction: any exception in
  `handlePreventUninstall` returns "not blocked".
- DataStore read failures degrade to safe defaults (empty lists, blocking
  enabled), never to a crash.

### 1.2 Add-only writes for system settings

The accessibility self-heal (`A11yProtectionUtils.selfHealAll`) is strictly
**additive**: it appends SafeMe's service and the user-selected services and
never removes entries the user configured. It also only writes when the value
would actually change, so the write cannot loop with its own ContentObserver.

### 1.3 Never lock the user out

- A "block everything" schedule exempts critical system surfaces
  (`SCHEDULE_SYSTEM_EXEMPT`: launcher, Settings, package installer,
  permission controllers, phone/telecom, DocumentsUI, media provider) so the
  device stays reachable.
- The Device Admin activation screen is never blocked (a stale PU flag must
  not trap the user in the off state).
- App Lock has bounded rate limiting: after 10 failed attempts it locks for
  5 minutes, then resets — it never becomes a permanent brick.

### 1.4 Idempotent coordinators

`FocusEngine.apply`, the guard's `ensureWatching`, and the VPN's
apply/restart paths are all safe to call repeatedly and from any thread.
Coordinators restart the VPN only when the active set actually changes, so a
no-op re-evaluation never tears down a healthy tunnel.

### 1.5 Never crash on the user's data

All persisted data is untrusted. Every parse path that reads DataStore or a
backup file either defaults (runtime reads) or rejects with a typed error
(backup restore). Unit tests pin both behaviors.

### 1.6 Mirror the prototype, not the reference code

UI is implemented to match the **design prototype** (the reference HTML
prototype) pixel-for-pixel — colors, typography, spacing, component shapes.
The "Protect Yourself" project is a *reference for architecture*, not a code
base to copy. When the two disagree, the prototype wins for UI; the reference
informs enforcement machinery (e.g. `FocusEngine` mirrors the reference
singleton's idempotent semantics).

## 2. Safety rules (hard constraints)

| # | Rule | Why |
|---|---|---|
| 1 | The block gate must **never** be raised over accessibility-management screens (list, detail, toggle). | Android disables an accessibility service whose window obscures a11y-management screens (consent-integrity / anti-tapjacking protection). Covering them would kill the engine. |
| 2 | The a11y self-heal requires `WRITE_SECURE_SETTINGS`; without it, every write is a no-op and the user gets a throttled notification. | The permission is signature/privileged/dev-only; the app degrades to detection + notification. |
| 3 | The VPN tunnel never inspects or relays traffic. | Filtering is delegated to the family-safe DNS provider; the app must not grow into a traffic-capture engine. |
| 4 | Enum-like persisted values always parse through a resilient lookup with a default. | A stale/corrupt stored value must never leave the UI without a selected option or crash a service. |
| 5 | Services own no mutable user state; all state is in DataStore flows. | Single source of truth, process-death safe, observable by UI and services alike. |
| 6 | Every externally-visible write (settings, DataStore) is wrapped so a failure is a graceful no-op, never a crash. | OEM/rooted-device behavior is unpredictable; the safe fallback is documented per call. |

## 3. Security posture

- **Credentials are never stored.** App Lock stores only
  `PBKDF2-HMAC-SHA256` (100k iterations, 256-bit key, 16-byte random salt) as
  `salt:hash`, verified with a constant-time compare.
- **Anti-tamper is layered**: Device Admin makes Uninstall a "Disable"
  flow; accessibility guards intercept the remaining pages (App Info,
  Device-Admin deactivation, force-stop, uninstall confirmation) — but only
  while the user opted in (the PU flag), and only for pages that identify
  SafeMe itself.
- **Minimal Device Admin**: an empty `<uses-policies/>` — no admin
  capabilities, no lock/wipe. Just the uninstall-protection semantics.
- **Notification throttling** for alarm conditions (a11y disabled, admin
  removed) prevents notification spam while staying informative.

## 4. Reliability posture

- **Background work is gated**: the 60 s schedule ticker only runs while a
  schedule is enabled; the 30 s a11y guard poll only runs while protection is
  on. Idle processes do no periodic reads.
- **Boundary alarms degrade gracefully**: on API 31+ without the exact-alarm
  permission, schedule alarms are inexact; the 60 s ticker bounds the drift.
  A Schedule-screen banner offers the Settings grant when schedules are active.
- **Boot re-arm**: all three mechanisms (VPN, a11y protection, schedules)
  restore after `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`.
- **Deduped logging**: the activity feed collapses consecutive duplicate
  events so restarts and re-arms stay quiet.
- **Watchdog with anti-storm**: the VPN restarts a silently-died tunnel only
  if the last establish is older than 5 s — a tunnel that keeps dying stops
  instead of looping.

## 5. Performance posture

- Startup does one synchronous DataStore read (theme) to eliminate a first
  frame flash; everything else is async.
- Recomposition is guarded: Home prefs flows use `distinctUntilChanged`, and
  the per-frame draw path in `Effects.blurredShadow` reuses a lazily-built
  `Paint`/`BlurMaskFilter` instead of allocating per frame.
- Release builds ship with R8 minify + resource shrinking (10.3 MB → 3.1 MB).
- See [10 — Performance](10-performance.md) for the measured numbers.

## 6. Decision log (ADRs)

### ADR-1: DNS-delegated filtering instead of a local DNS blocklist

The VPN advertises a family-safe resolver and deliberately routes **no**
traffic into the TUN. Earlier iterations contained a full local relay/blocklist
engine (packet parsing, TCP/UDP relays); that code was removed. Tradeoffs
accepted: the app cannot block encrypted DNS (DoH/DoT/DoQ) or inspect
traffic. In exchange the tunnel is simple, auditable, and cannot be accused
of user-data capture. Browsers with their own resolver (e.g. Chrome Secure
DNS) can bypass filtering — the same limitation the reference project accepts.

### ADR-2: Strict blocking-section validation in backups

Keyword/website/string/title-rule parsers run in a strict mode during backup
restore: a malformed entry (non-object, missing value, wrong type) rejects the
whole file with "This backup is missing required data" instead of silently
restoring fewer entries. Runtime DataStore reads stay lenient so a corrupt
store can never crash a service.

### ADR-3: Export files keep the `.jsonc` extension

The SAF save sheet uses `application/octet-stream` so DocumentsUI keeps the
suggested `SafeMe-backup-<timestamp>.jsonc` name (an `application/json` MIME
made it append `.json`, producing a confusing `.jsonc.json`).

### ADR-4: Process-wide volatile state holders for hot paths

The gate, guard, and a11y service read `@Volatile` singletons fed by
DataStore collectors instead of blocking on reads in event handlers. Stale
reads are always safer than blocking ones.

### ADR-5: `WRITE_SECURE_SETTINGS` is intentionally requested

It exists solely for the opt-in accessibility self-heal and triggers the
lint `ProtectedPermissions` baseline finding by design (recorded in
`app/lint-baseline.xml`); it is ADB-granted and never requested at runtime
from the user.
