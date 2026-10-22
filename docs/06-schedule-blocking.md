# 06 — FocusMe (scheduled focus sessions)

FocusMe — scheduled focus sessions — is coordinated by `protect/FocusEngine` —
the **only** component that turns focus-session rules into enforcement.

## 1. Data model (`data/FocusPrefs.kt`)

```kotlin
data class FocusSession(
    val id: String,               // UUID
    val name: String,
    val days: List<Int>,          // 0=Mon … 6=Sun (prototype order)
    val startMinute: Int,         // minutes from midnight, 0..1439
    val endMinute: Int,           // != startMinute; < startMinute ⇒ overnight, spills into the next day
    val mode: FocusMode,       // INTERNET | LAUNCH | BOTH
    val appPackages: List<String>,// empty ⇒ blocks every app (blocksAllApps)
    val enabled: Boolean = true,
)
```

- **Day convention:** schedules store `0=Mon … 6=Sun` (prototype order);
  `FocusEvaluator` converts to `Calendar.DAY_OF_WEEK` internally.
- **Empty `appPackages`** means "no apps picked → blocks everything", per the
  prototype's "No apps — schedule blocks everything" editor copy.
- Persisted as a JSON array in `schedule_prefs` (`schedules_json`); plus an
  `a11y_warn_dismissed` flag for the "Accessibility Service required"
  banner (`shouldShowA11yWarning`).

## 2. Pure decision core (`protect/FocusEvaluator.kt`)

Android-free, unit-tested:

- `isActiveAt(rule, dayIndex, minuteOfDay)` — enabled, valid window
  (`end == start` never matches), `minuteOfDay in start until end` on
  selected days for normal windows; overnight windows (`end < start`) match
  `[start, 24:00)` on selected days plus `[00:00, end)` spilling into the
  following day.
- `evaluate(rules, now)` — **union semantics**: if any active rule targets a
  package, it is blocked. Produces `ActiveRules` with four fields:
  `internetBlockedPackages`, `internetBlockAll`, `launchBlockedPackages`,
  `launchBlockAll`.
- `nextBoundary(rules, now)` — earliest future start **or** end of any
  enabled rule (day offsets 0..7 cover every day-of-week combination
  exactly once; a wrapped rule's end is emitted while scanning the day it
  spills into), or `Long.MAX_VALUE` when no boundary exists.

## 3. Coordinator (`protect/FocusEngine.kt`)

Process-wide singleton, idempotent, safe from any thread. `apply(context,
schedules, now)`:

1. Evaluate the active rules.
2. **Launch blocking** → if the set changed, push to
   `SafeMeAccessibilityService.onScheduleSetsChanged()`, which re-checks the
   current foreground window so a block starting while an app is already open
   takes effect immediately.
3. **Internet blocking** → if the set changed, call
   `SafeMeVpnService.applyFocusBlocks(...)`, which restarts the tunnel in
   the matching mode (per-app block / block-all) or starts it when consent
   was previously granted. See [07](07-vpn-dns-filtering.md).
4. **Re-arm the next boundary alarm** via `FocusAlarmReceiver`.
5. **Activity feed** — log "Launch blocking active" / "Internet blocking
   active" only when a block actually turns on (deduped by the store).

`reevaluate(context)` re-reads schedules from DataStore and applies — called
by `SafeMeApp` on every persisted change, by the alarm receiver at
boundaries, and by the safety ticker.

## 4. Alarm & boot (`protect/FocusAlarmReceiver.kt`)

- One exact `RTC_WAKEUP` alarm is scheduled for the next boundary
  (`setExactAndAllowWhileIdle`); on API 31+ without the exact-alarm
  permission it degrades to inexact (`set`). A Schedule-screen banner
  offers the Settings grant whenever schedules are active.
- When it fires (or on `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`) the receiver
  runs `FocusEngine.reevaluate` in `goAsync()` and re-arms the next alarm.
- `Long.MAX_VALUE` cancels the alarm (no enabled schedules).

## 5. Safety ticker (`SafeMeApp`)

A 60 s ticker bounds drift from inexact alarms / missed doze wakeups. It is
**gated**: it only calls `reevaluate` while at least one schedule is enabled
(`hasEnabledSchedules`), so an idle device with no schedules does zero
periodic work.

## 6. Enforcement surfaces

| Mode | Launch blocking (a11y) | Internet blocking (VPN) |
|---|---|---|
| `LAUNCH` | gates app on open | — |
| `INTERNET` | — | per-app tunnel block |
| `BOTH` | gates app on open | per-app tunnel block |
| any mode, empty `appPackages` | gates **all** apps except `SCHEDULE_SYSTEM_EXEMPT` | block-all tunnel mode (everything except SafeMe + whitelist) |

The a11y service checks `FocusEngine.isLaunchBlocked(pkg)` before the
keyword engine, independent of the master blocking switch; `launchBlockAll`
exempts critical system surfaces (`com.android.systemui`, Settings, launchers,
package installer, permission controllers, DocumentsUI, media provider,
phone/telecom) so the user can always escape.

## 7. Editing flow (`ui/screens/focus/`)

- `FocusScreen` — list of focus-session cards with day/window labels
  (`focusDaysLabel`, `focusWindowLabel` helpers) and enable toggles.
- `FocusEditScreen` — create/edit a focus session with `?editId=`; days, start/end pickers
  (validated "start != end", overnight allowed), mode segmented control (Internet | Launch |
  Both), and the shared app picker for `appPackages` (see
  [09](09-app-picker.md)).
- Mutations go through `Context.addFocusSession` / `updateFocusSession` /
  `deleteFocusSession` / `toggleFocusSession`; the `focusPrefs()` flow notifies
  `SafeMeApp`, which calls `FocusEngine.apply` and re-arms the alarm.

## 8. Persisted identifiers (frozen — do not rename)

The FocusMe rename changed class names, files, routes and copy, but the values
below live on disk, in backup files, in armed `PendingIntent`s or in the
manifest. Changing any one of them silently drops user data or breaks restores,
so `app/src/test/java/com/safeme/app/data/FrozenIdentifiersTest.kt` pins them:
a rename that touches one of these values fails the unit-test suite instead of
silently costing users their sessions or their backups.

| Kind | Frozen value | Where |
|---|---|---|
| DataStore file | `schedule_prefs` | `FocusPrefs.kt` |
| DataStore keys | `schedules_json`, `a11y_warn_dismissed`, `exact_alarm_warn_dismissed`, `excluded_apps` | `FocusPrefs.kt` |
| Session JSON fields | `id`, `n`, `d`, `st`, `en`, `m`, `a`, `e` | `focusSessionsToJson` |
| Mode wire names | `INTERNET`, `LAUNCH`, `BOTH` | `FocusMode.name` |
| Backup section key | `schedules` (top level and nested) | `BackupCodec` |
| Backup format | `safeme-backup`, schema `1` | `BackupCodec` |
| Activity type | `schedule` (`ACTIVITY_FOCUS`) | `ActivityLog.kt` |
| Quick-action id | `schedule` (`QuickActionType.FOCUS_SESSIONS.id`) | `QuickActionsPrefs.kt` |
| Gate type token | `schedule` | a11y service → `BlockOverlayController` |
| Alarm action | `com.safeme.app.action.SCHEDULE_BOUNDARY` | `FocusAlarmReceiver` + manifest |
| Platform permission | `android.permission.SCHEDULE_EXACT_ALARM` | manifest |

Kotlin property names `FocusPrefsState.schedules` and `BackupSnapshot.schedules`
are intentionally identical to the frozen JSON key, so the model and the wire
format can never drift apart. The same applies to `FocusMode`'s constant names
and to the `"schedule"` string that the block gate passes between the
accessibility service and the overlay.
