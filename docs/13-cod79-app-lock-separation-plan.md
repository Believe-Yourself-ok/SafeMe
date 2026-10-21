# COD-79 — "Open app without the app lock"

**Implementation plan** · Linear [COD-79](https://linear.app/code-bro/issue/COD-79/open-app-without-the-app-lock) · project *SafeMe* · label `Feature` · priority Urgent

> Plan-only document. No production code has been modified. Every claim below is
> verified against the working tree at `faf4ede` (branch `main`).

---

## 1. Goal

Split today's single App Lock concept into **two independent protections**, each
with its own toggle card:

| # | Card | Controls |
|---|---|---|
| 1 | **App Entry Password Protection** | Whether the full-screen unlock gate appears when the app is opened / returns from background |
| 2 | **Feature Disabling Password Protection** | Whether a password must be verified before any protection is turned **off** |

Hard rules from the issue:

- The two settings are independent — flipping one never flips the other.
- **Enabling** anything never requires a password, regardless of card 2.
- Card 2 must be applied *consistently across all relevant feature controls and
  entry points*.
- Cancel / failed verification must leave the feature **enabled** (unchanged).
- Existing behaviour and the design system must be preserved.

### Decisions locked with the requester

| Question | Decision |
|---|---|
| Where do the cards live? | **On the existing App Lock screen** (`ui/screens/applock/AppLockScreen.kt`, route `"applock"`). No new nav route. |
| What happens to the red "Disable App Lock" button? | **Removed**, together with its `AlertDialog`. Card 1's toggle becomes the only way to turn entry lock off. |
| Scope of card 2 gating | **Every feature switch turned OFF, including per-item toggles** (schedules, title rules, per-app social rows, quick-action removal). |
| Is card 2's own switch gated? | **Yes.** Turning card 2 OFF is itself a protected disable. Turning it ON never is. |
| Verification UI | **Bottom sheet** reusing `PinKeypad` / `PinDots` / `PasswordField` / `PatternGrid` from `AppLockInputs.kt` + `AppLockManager.verify()` + `ToastHost`-style inline errors. No biometric shortcut (not selected). |

---

## 2. Current implementation (verified)

### 2.1 Persistence — `data/AppLockPrefs.kt`

```
AppLockPrefsState(
    lockType: LockType = OFF,        // OFF | PIN | PASSWORD | PATTERN
    storedHash: String = "",         // "<saltHex>:<hashHex>" PBKDF2
    credentialLength: Int = 0,
    biometricEnabled: Boolean = false,
    forgotPasswordDisabled: Boolean = false,
    autoLock: AutoLockDelay = IMMEDIATELY,
)
```

DataStore file `safeme_applock_prefs`; keys `KEY_APP_LOCK_TYPE`, `_HASH`,
`_LENGTH`, `_BIOMETRIC`, `_FORGOT`, `_AUTO`. Mutators: `setAppLock`,
`setAppLockBiometric`, `setAppLockForgotDisabled`, `setAppLockAutoLock`,
`writeAppLockPrefs` (backup restore).

**There is no "gate on/off" flag today.** `lockType != OFF` *is* both
"a credential exists" **and** "the app must be unlocked".

### 2.2 Cached state — `protect/AppLockManager.kt`

`AppLockStateHolder` (a `@Volatile` singleton) mirrors the prefs so the gate can
decide **synchronously** on cold start:

```kotlin
val enabled: Boolean get() = lockType != LockType.OFF   // ← the conflation
```

It is fed from two places: the `appLockPrefs()` collector in `SafeMeApp.onCreate`
(lines 101–109) and `AppLockViewModel.init` / `refresh()`.

`AppLockManager` also owns:
- `isValidInput(type, input)` — PIN 4–6 digits, password ≥ 4 chars, pattern ≥ 4 dots.
- PBKDF2-HMAC-SHA256, 100k iterations, 256-bit key, 16-byte salt; `constantTimeEquals`.
- `setLock(context, type, input)` → persists + `resetRateLimiter` + holder update.
- `disableLock(context)` → **wipes** type/hash/length/biometric/forgot + resets the rate limiter.
- `verify(context, input)` → `false` while locked out; records failures; resets on success.
- Rate limiter in SharedPreferences `safeme_applock_rate`: free 1–4, backoff
  1/2/4/8/16 s for 5–9, **5-minute lockout** at 10 (`lockoutDeadlineFor`, pure + tested).

### 2.3 Gate — `ui/screens/applock/AppLockGateController.kt`

A singleton `StateFlow<Boolean> locked` + `sessionUnlocked` + `backgroundedAt`.
All four decision points read `AppLockStateHolder.enabled`:

| Method | Trigger | Behaviour |
|---|---|---|
| `onStateLoaded()` | every `appLockPrefs()` emission | `!enabled` → force unlocked; else `!sessionUnlocked` → lock |
| `onAppForeground()` | `MainActivity.onStart` (line 54) | `!enabled` → unlocked; else apply `autoLock` delay |
| `onAppBackground()` | `MainActivity.onStop` (line 58) | records timestamp; `IMMEDIATELY` → lock |
| `lockNow()` | App Lock screen "Lock now" | locks only when `enabled` |

`AppLockGateHost()` (in `AppLockUnlockOverlay.kt`, lines 60–66) is composed in
`MainActivity.setContent` **above** `OnboardingNavHost()`, so it covers the whole
app including onboarding.

`AppLockUnlockOverlay` reads `lockType` / `credentialLength` / `biometricEnabled`
/ `forgotPasswordDisabled` from the holder, re-reads the freshest prefs in a
`LaunchedEffect`, clears input on every `ON_RESUME`, auto-submits at
`credentialLength`, and renders `LockType.OFF -> Unit` (i.e. **an overlay shown
with `lockType == OFF` renders no input at all — a dead end that must never
become reachable**).

### 2.4 App Lock screen — `ui/screens/applock/AppLockScreen.kt` (706 lines)

Composition order inside the scrolling `Column`:

1. `Header(title = al_title)`
2. `HeroCard(enabled = state.enabled, lockType = state.lockType)` — two states only
   (`al_hero_on_*` / `al_hero_off_*`) + green/red pill (`al_hero_pill_on/off`).
3. `if (state.enabled)` → `LockActions(onLockNow, onChangeLock)`
4. `if (!state.enabled)` → `SetupCta` (gradient "Set up App Lock")
5. `Text(al_settings_label)` ("Lock settings")
6. `SettingsList(...)` → three `SettingRow`s: Auto-lock (chevron), Touch ID /
   Face ID (`MasterSwitch`), Disable "Forgot Password" (`MasterSwitch`). All
   three are dimmed to `alpha 0.45` and non-interactive when `!enabled`.
7. `if (state.enabled)` → **`DisableButton`** (red pill) → `AlertDialog`
   (`al_disable_title` / `_body` / `_cancel` / `_confirm`) → `viewModel.disableLock()`
8. `Note()` (`al_note`)

`MasterSwitch(checked, onToggle, enabled)` is **public in this file** (declared line 651)
and already implements the disabled/dimmed state — it is the exact component the
two new cards need.

`AppLockUiState` (in `AppLockViewModel.kt`) = `lockType`, `enabled`, `autoLock`,
`biometricEnabled`, `forgotPasswordDisabled`, `biometricAvailable`.

### 2.5 What consumes `lockType != OFF` today

| Call site | Effect |
|---|---|
| `AppLockGateController` (4 branches) | gate decision |
| `AppLockUiState.enabled` + `AppLockScreen` | hero / actions / settings / disable button |
| `HomeViewModel.rebuild()` line 152 → `ProtectionLayersEvaluator(appLockEnabled = …)` | layer 4 of the Home hero ring ("x of 10 layers active") |
| `AppLockUnlockOverlay` | method-specific input rendering |

Nothing in `service/` or `protect/` (other than the gate) reads App Lock state —
so **enforcement machinery is untouched by this change**. Verified:
`SafeMeAccessibilityService`, `SafeMeProtectionService`, `SafeMeVpnService`,
`ScheduleEngine`, `A11yProtectionGuard` contain no `AppLock*` reference.

### 2.6 Existing "turn a protection off" call sites (verified inventory)

| Feature | ViewModel function | Line | UI entry point | Optimistic? |
|---|---|---|---|---|
| Master blocking | `HomeViewModel.toggleMasterProtection()` | 202 | `HomeScreen.kt:129` `MasterProtectionCard` | **Yes** (flips `_uiState` then writes) |
| Master blocking | `BlockingViewModel.toggleBlocking()` | 88 | `BlockingScreen.kt:100` `ShieldCard` | **Yes** |
| Master blocking | `KeywordManagerViewModel.toggleBlocking(enabled)` | 71 | **no UI caller — dead code** (verified by grep) | n/a |
| Anti-Tamper | `AntiTamperViewModel.deactivate()` | 88 | `AntiTamperScreen.kt:89` (`if (state.adminActive)`) | No |
| Anti-Tamper | `AntiTamperViewModel.refreshAdminActive()` | 60 | system-page return; can write `false` when the user deactivated in **system Settings** | No |
| Accessibility Protection | `AccessibilityProtectionViewModel.setEnabled(false)` | 70 | `AccessibilityProtectionScreen.kt:118` `MasterCard` | No |
| Social blocking master | `SocialBlockingViewModel.toggleMaster()` | 109 | `SocialBlockingScreen.kt:104` `MasterCard` | No |
| Social "Relax" preset | `SocialBlockingViewModel.applyPreset("relax")` | ~119 | `SocialBlockingScreen.kt:109` `onPreset` | No — **sets `KEY_SOCIAL_ENABLED = false`** (`SocialBlockingPrefs.kt:139-147`), so it *is* a master disable |
| Social per-app | `SocialBlockingViewModel.toggleLaunch(pkg)` | 131 | `SocialBlockingScreen.kt:116, 142` `LaunchRow` | No |
| Social per-tab | `toggleYoutube()` / `toggleFacebook()` / `toggleSnapchat()` | 155 / 167 / 179 | `SocialBlockingScreen.kt:126-128` `TabRow` | No |
| DNS & VPN | `DnsVpnViewModel.toggle()` → `disable()` | 165 → 211 | `DnsVpnScreen` master card | Partially (`optimisticEnabled`) |
| Image/Video search | `BlockingViewModel.toggleImageVideoSearch()` | 105 | `OtherFeaturesScreen.kt:72` `ImageVideoSearchCard` | **Yes** |
| Schedule item | `ScheduleViewModel.toggleSchedule(id)` | 249 | `ScheduleScreen.kt:143` → `MasterSwitch` at 610 | No |
| Schedule (editor) | `ScheduleEditViewModel.setEnabled(false)` | 107 | working copy, committed by `save()` | In-memory only |
| Title rule | `TitleBlockViewModel.toggleRule(id, false)` | 117 | `TitleBlockScreen.kt:130` | No |
| Quick action | `QuickActionsEditViewModel.remove(action)` | ~81 | `QuickActionsEditScreen.kt:141` `onRemove` | No |
| Block-screen "why blocked" | `BlockScreenViewModel.toggleWhy()` | 65 | `BlockScreen.kt:200` `CustomSwitch` (working copy, saved by `save()`) | In-memory only |
| **App Lock** | `AppLockViewModel.disableLock()` | 109 | `AppLockScreen.kt:124` `DisableButton` → dialog 161 | No — **to be deleted** |

### 2.7 Reusable precedents in the codebase

| Need | Precedent |
|---|---|
| "ViewModel asks the screen to show UI" | `DnsVpnViewModel._consentRequest: MutableSharedFlow<Unit>` (line 71) collected in `DnsVpnScreen.kt:76-85` inside `LaunchedEffect(Unit)` |
| Bottom sheet | `AppLockSheets.kt:139, 305`, `DnsVpnSheets.kt:71, 215`, `BlockSheets.kt:59, 185` — `ModalBottomSheet` + `rememberModalBottomSheetState(skipPartiallyExpanded = true)` + `GrabBar()` |
| Method-specific credential input | `AppLockInputs.kt`: `PinDots(filled)`, `PinKeypad(enabled, onDigit, onDelete)`, `PatternGrid(selected, enabled, onDotTap)`, `PasswordField(value, onValueChange, placeholder, enabled, focusRequester)`, `Modifier.shakeEffect(trigger)` |
| Verification + rate limiting | `AppLockManager.verify/isLockedOut/getLockoutRemainingMs` |
| Toggle card with switch | `AntiTamperScreen.PreventUninstallCard` (icon chip + title + sub + trailing control, `cardShape(radius = 20.dp)`, 16 dp padding) |
| Async feedback | `ToastHost(flow = viewModel.toasts)` |
| Backup parity | `docs/11-development-guide.md` §3: *"any new persisted domain should be added to `BackupSection` + `BackupSnapshot` + `backupStores()` + the codec's `toJson`/`parse` pair"* |
| Testability convention | `docs/11-development-guide.md` §2: *"extract pure logic into an `object`/top-level function, keep Android I/O in suspend functions, test the pure part"* |

---

## 3. The root problem

`lockType != OFF` currently means **three different things at once**:

1. a credential exists (`storedHash` is non-blank),
2. the entry gate is armed,
3. `AppLockUiState.enabled` → hero copy, dimmed settings rows, layer 4 of the shield.

COD-79 requires (2) to be switchable **without** destroying (1), because (1) is
what card 2 verifies against. Therefore:

> **The credential must be separable from the entry gate.**

`AppLockManager.disableLock()` (which wipes the hash) must stop being reachable
from the UI — hence the agreed removal of the red "Disable App Lock" button.
The credential is then destroyed only by being **overwritten** (`setLock`, i.e.
"Change lock" / the setup wizard) or by a **backup restore** that carries an
empty one.

**Chosen credential lifecycle (rationale):** retain `storedHash` while the user
has configured a lock, independent of both toggles. It is a PBKDF2 hash with a
per-user salt, not a plaintext secret, so retaining it while both toggles are
off is not a new exposure — and destroying it would break independence
(re-enabling card 1 would silently require re-typing the credential, and card 2
would become un-usable). Card 1 ON with no credential is impossible (the switch
is disabled), so `hasCredential == false` ⇒ nothing is gated and the app opens
freely — exactly the "open app without the app lock" outcome the issue asks for.

---

## 4. Target model

Two new persisted booleans on the existing `AppLockPrefsState` — **no new
DataStore file, no new `BackupSection`**:

```kotlin
data class AppLockPrefsState(
    val lockType: LockType = LockType.OFF,
    val storedHash: String = "",
    val credentialLength: Int = 0,
    val biometricEnabled: Boolean = false,
    val forgotPasswordDisabled: Boolean = false,
    val autoLock: AutoLockDelay = AutoLockDelay.IMMEDIATELY,
    /** COD-79 card 1 — arm the full-screen unlock gate on app entry. */
    val entryLockEnabled: Boolean = true,
    /** COD-79 card 2 — require the credential before any protection is turned off. */
    val disableProtectionEnabled: Boolean = true,
) {
    /** A usable credential is stored. Both new toggles depend on this. */
    val hasCredential: Boolean
        get() = lockType != LockType.OFF && storedHash.isNotBlank()

    /** The entry gate is armed. Never true without a credential. */
    val entryLockActive: Boolean
        get() = entryLockEnabled && hasCredential

    /** Card 2 is armed. Never true without a credential. */
    val disableProtectionActive: Boolean
        get() = disableProtectionEnabled && hasCredential
}
```

**Defaults are `true` so the upgrade is a no-op:** an existing install with a
configured lock keeps locking on entry (`entryLockActive == true`, same as today)
and gains disable protection (`disableProtectionActive == true`, the new
behaviour the issue asks for). An install with no lock has `hasCredential ==
false`, so both derived flags are `false` — again identical to today.

### 4.1 Single source of truth for the two decisions

New pure object `protect/DisableProtectionPolicy.kt`:

```kotlin
package com.safeme.app.protect

/**
 * COD-79 decision core. Pure and Android-free so the whole truth table is
 * JVM-testable (docs/11 §2).
 *
 * The two protections are deliberately independent: neither flag is derived
 * from the other, and neither setter touches the other's key.
 */
object DisableProtectionPolicy {

    /**
     * Card 1 — is the entry gate armed? A gate without a stored credential
     * would render [AppLockUnlockOverlay]'s `LockType.OFF -> Unit` branch: a
     * full-screen overlay with no input, i.e. an unrecoverable dead end.
     * Requiring [hasCredential] makes that state unreachable.
     */
    fun entryLockActive(entryLockEnabled: Boolean, hasCredential: Boolean): Boolean =
        entryLockEnabled && hasCredential

    /**
     * Card 2 — must [action] be preceded by credential verification?
     * Enabling is NEVER gated: [action] only ever describes turning something
     * OFF, and callers must not invoke it for the enable direction.
     */
    fun passwordRequired(
        disableProtectionEnabled: Boolean,
        hasCredential: Boolean,
        action: ProtectedAction,
    ): Boolean = disableProtectionEnabled && hasCredential && action.protected
}

/**
 * Every "turn a protection off" surface in the app. The id is used for logging
 * and for the verification sheet's title; [protected] exists so a future
 * non-gated surface can be added without a second enum.
 */
enum class ProtectedAction(val id: String, val protected: Boolean = true) {
    MASTER_BLOCKING("master_blocking"),
    ANTI_TAMPER("anti_tamper"),
    ACCESSIBILITY_PROTECTION("a11y_protection"),
    SOCIAL_BLOCKING_MASTER("social_master"),
    SOCIAL_BLOCKING_APP("social_app"),
    SOCIAL_BLOCKING_TAB("social_tab"),
    DNS_VPN("dns_vpn"),
    IMAGE_VIDEO_SEARCH("image_video_search"),
    SCHEDULE_ITEM("schedule_item"),
    SCHEDULE_EDITOR_OFF("schedule_editor_off"),
    TITLE_RULE("title_rule"),
    QUICK_ACTION_REMOVE("quick_action_remove"),
    BLOCK_SCREEN_WHY("block_screen_why"),
    DISABLE_PROTECTION_ITSELF("disable_protection"),
    // NOTE: there is deliberately NO action for card 1 (`setEntryLock`). Turning
    // the entry gate on or off is never password-gated — only card 2's own
    // switch is (decision: "yes_gate").
}
```

### 4.2 Truth table (the 4-way matrix COD-79 asks to test)

`H` = `hasCredential`.

| H | `entryLockEnabled` | `disableProtectionEnabled` | App opens… | Turning a protection OFF… |
|---|---|---|---|---|
| ✗ | any | any | straight in | no prompt (nothing to verify with) |
| ✓ | ✓ | ✓ | **unlock gate** | **password sheet** |
| ✓ | ✓ | ✗ | **unlock gate** | no prompt |
| ✓ | ✗ | ✓ | straight in | **password sheet** |
| ✓ | ✗ | ✗ | straight in | no prompt |

Independence invariants:

- `setEntryLockEnabled(x)` writes **only** `KEY_APP_LOCK_ENTRY`.
- `setDisableProtectionEnabled(x)` writes **only** `KEY_APP_LOCK_DISABLE_PROT`.
- `setLock(type, input)` writes only type/hash/length (existing behaviour).
- No code path writes both keys in one action.

---

## 5. Phase-by-phase plan

Each phase ends compilable (`./gradlew :app:compileDebugKotlin`) so regressions
are bisectable. Nothing in Phases 1–2 changes observable behaviour.

### Phase 0 — branch

```bash
git checkout -b feat/cod-79-app-lock-separation
```

### Phase 1 — data layer (`data/AppLockPrefs.kt`)

1. Add the two fields + the three derived properties to `AppLockPrefsState` (§4).
2. Add keys and read them in the `Flow` mapper:

```kotlin
val KEY_APP_LOCK_ENTRY = booleanPreferencesKey("entry_lock_enabled")
val KEY_APP_LOCK_DISABLE_PROT = booleanPreferencesKey("disable_protection_enabled")

fun Context.appLockPrefs(): Flow<AppLockPrefsState> =
    appLockDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            AppLockPrefsState(
                lockType = LockType.fromStorage(prefs[KEY_APP_LOCK_TYPE]),
                storedHash = prefs[KEY_APP_LOCK_HASH] ?: "",
                credentialLength = prefs[KEY_APP_LOCK_LENGTH] ?: 0,
                biometricEnabled = prefs[KEY_APP_LOCK_BIOMETRIC] ?: false,
                forgotPasswordDisabled = prefs[KEY_APP_LOCK_FORGOT] ?: false,
                autoLock = AutoLockDelay.fromStorage(prefs[KEY_APP_LOCK_AUTO]),
                // Default TRUE: an existing install with a lock keeps gating on
                // entry exactly as before this field existed.
                entryLockEnabled = prefs[KEY_APP_LOCK_ENTRY] ?: true,
                disableProtectionEnabled = prefs[KEY_APP_LOCK_DISABLE_PROT] ?: true,
            )
        }
```

3. One mutator per flag — **each edits exactly one key**, which is what makes the
   two settings independent at the storage level:

```kotlin
/** COD-79 card 1. Writes only the entry-gate flag; the credential and card 2
 *  are untouched, so turning entry lock off can never weaken (or strengthen)
 *  feature-disabling protection. */
suspend fun Context.setAppLockEntryEnabled(enabled: Boolean) {
    appLockDataStore.edit { prefs -> prefs[KEY_APP_LOCK_ENTRY] = enabled }
}

/** COD-79 card 2. Writes only the disable-protection flag. */
suspend fun Context.setAppLockDisableProtectionEnabled(enabled: Boolean) {
    appLockDataStore.edit { prefs -> prefs[KEY_APP_LOCK_DISABLE_PROT] = enabled }
}
```

4. Extend `writeAppLockPrefs` (backup restore path) with the two keys. Because
   the values come from the parsed snapshot, a restore reproduces both toggles
   exactly.

**No change** to `setAppLock`, `setAppLockBiometric`, `setAppLockForgotDisabled`,
`setAppLockAutoLock`.

### Phase 2 — cached state + gate

**`protect/AppLockManager.kt` → `AppLockStateHolder`:**

```kotlin
object AppLockStateHolder {
    @Volatile var lockType: LockType = LockType.OFF
    @Volatile var autoLock: AutoLockDelay = AutoLockDelay.IMMEDIATELY
    @Volatile var credentialLength: Int = 0
    @Volatile var biometricEnabled: Boolean = false
    @Volatile var forgotPasswordDisabled: Boolean = false
    @Volatile var storedHash: String = ""
    /** Default TRUE so a cold start before the first DataStore emission still
     *  gates when a lock exists — identical to today's behaviour. */
    @Volatile var entryLockEnabled: Boolean = true
    @Volatile var disableProtectionEnabled: Boolean = true

    /** @deprecated by COD-79: conflates "credential exists" with "gate armed".
     *  Kept only until every reader is migrated; use [entryLockActive]. */
    val enabled: Boolean get() = lockType != LockType.OFF

    val hasCredential: Boolean get() = lockType != LockType.OFF && storedHash.isNotEmpty()
    val entryLockActive: Boolean
        get() = DisableProtectionPolicy.entryLockActive(entryLockEnabled, hasCredential)
    val disableProtectionActive: Boolean
        get() = disableProtectionEnabled && hasCredential

    fun update(state: AppLockPrefsState) {
        lockType = state.lockType
        autoLock = state.autoLock
        credentialLength = state.credentialLength
        biometricEnabled = state.biometricEnabled
        forgotPasswordDisabled = state.forgotPasswordDisabled
        storedHash = state.storedHash
        entryLockEnabled = state.entryLockEnabled
        disableProtectionEnabled = state.disableProtectionEnabled
    }
}
```

> `storedHash` is a hash, not a secret, and the holder already caches
> `credentialLength` for the same "the UI needs it, the hash can't reveal it"
> reason. It is read only to answer `hasCredential`.

#### ⚠ Mandatory companion fix — `setLock()` / `disableLock()` reconstruction

**This is the single highest-risk line in the whole change.** Both existing
`AppLockManager` mutators rebuild the holder from a *hand-written*
`AppLockPrefsState(...)` that copies field-by-field from the holder
(`AppLockManager.kt:165-174` and `:183-192`):

```kotlin
AppLockStateHolder.update(
    AppLockPrefsState(
        lockType = type,
        storedHash = stored,
        credentialLength = credentialLength,
        biometricEnabled = AppLockStateHolder.biometricEnabled,      // ← copied
        forgotPasswordDisabled = AppLockStateHolder.forgotPasswordDisabled,
        autoLock = AppLockStateHolder.autoLock,                      // ← copied
    ),
)
```

Any field **not** listed silently falls back to its constructor default. So once
`entryLockEnabled` exists, "Change lock" / the setup wizard would reset it to
`true` — i.e. a user who turned card 1 OFF would have the entry gate switch
itself back ON the moment they rotate their credential. That is precisely the
"influences other existing features" failure this task must avoid, and it is
invisible until the DataStore collector re-emits.

**Fix — replace both reconstructions with a copy from a holder snapshot.** Add
to `AppLockStateHolder`:

```kotlin
/** Snapshot for the copy-based mutators below, so a new prefs field can never
 *  be silently reset to its default by a hand-written reconstruction. */
fun snapshot() = AppLockPrefsState(
    lockType = lockType,
    storedHash = storedHash,
    credentialLength = credentialLength,
    biometricEnabled = biometricEnabled,
    forgotPasswordDisabled = forgotPasswordDisabled,
    autoLock = autoLock,
    entryLockEnabled = entryLockEnabled,
    disableProtectionEnabled = disableProtectionEnabled,
)
```

then in `setLock` / `disableLock`:

```kotlin
// setLock — only the credential triple changes:
AppLockStateHolder.update(
    AppLockStateHolder.snapshot().copy(
        lockType = type, storedHash = stored, credentialLength = credentialLength,
    ),
)

// disableLock — clears the credential + its dependents, keeps both COD-79 flags:
AppLockStateHolder.update(
    AppLockStateHolder.snapshot().copy(
        lockType = LockType.OFF, storedHash = "", credentialLength = 0,
        biometricEnabled = false, forgotPasswordDisabled = false,
    ),
)
```

Covered by `AppLockPrefsStateTest` case: *set a lock while `entryLockEnabled =
false` ⇒ still `false` afterwards.*

**`AppLockManager` additions** (mirroring the existing `setLock`/`disableLock`
pattern: write DataStore, then update the holder so the gate re-evaluates
without waiting for the collector):

```kotlin
/** COD-79 card 1. Never touches the credential or card 2. */
suspend fun setEntryLockEnabled(context: Context, enabled: Boolean) {
    context.setAppLockEntryEnabled(enabled)
    AppLockStateHolder.entryLockEnabled = enabled
    AppLockGateController.onStateLoaded()   // re-evaluate immediately
}

/** COD-79 card 2. Caller must already have verified when turning this OFF. */
suspend fun setDisableProtectionEnabled(context: Context, enabled: Boolean) {
    context.setAppLockDisableProtectionEnabled(enabled)
    AppLockStateHolder.disableProtectionEnabled = enabled
}
```

`disableLock()` is **kept but becomes UI-unreachable** (the red button is
deleted in Phase 5). Leaving it avoids touching `BackupCodecTest` /
`BackupManagerTest` semantics and keeps a tested path for "clear everything".
Add a KDoc line stating it is no longer reachable from the UI.

**`ui/screens/applock/AppLockGateController.kt`** — swap the four
`AppLockStateHolder.enabled` reads for `entryLockActive`, and extract the
decisions into pure functions so the matrix is unit-testable:

```kotlin
object AppLockGateController {
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    @Volatile private var sessionUnlocked = false
    @Volatile private var backgroundedAt = 0L

    fun onStateLoaded() {
        _locked.value = stateLoadedLocked(
            gateActive = AppLockStateHolder.entryLockActive,
            sessionUnlocked = sessionUnlocked,
        )
        if (!AppLockStateHolder.entryLockActive) {
            sessionUnlocked = false
            backgroundedAt = 0L
        }
    }

    fun onAppForeground() {
        val now = System.currentTimeMillis()
        val next = foregroundLocked(
            gateActive = AppLockStateHolder.entryLockActive,
            sessionUnlocked = sessionUnlocked,
            autoLock = AppLockStateHolder.autoLock,
            backgroundedAt = backgroundedAt,
            now = now,
        )
        _locked.value = next
        backgroundedAt = 0L
    }

    fun onAppBackground() {
        backgroundedAt = System.currentTimeMillis()
        if (AppLockStateHolder.entryLockActive &&
            AppLockStateHolder.autoLock == AutoLockDelay.IMMEDIATELY
        ) {
            _locked.value = true
        }
    }

    fun lockNow() {
        if (AppLockStateHolder.entryLockActive) _locked.value = true
    }

    fun unlock() {
        sessionUnlocked = true
        _locked.value = false
    }
}

/** Pure: prefs-load decision. Gate off ⇒ never locked; gate on ⇒ locked until
 *  the session has been unlocked once. */
internal fun stateLoadedLocked(gateActive: Boolean, sessionUnlocked: Boolean): Boolean =
    if (!gateActive) false else !sessionUnlocked

/** Pure: ON_START decision, including the auto-lock delay. */
internal fun foregroundLocked(
    gateActive: Boolean,
    sessionUnlocked: Boolean,
    autoLock: AutoLockDelay,
    backgroundedAt: Long,
    now: Long,
): Boolean {
    if (!gateActive) return false
    if (!sessionUnlocked) return true
    // AutoLockDelay.OFF carries millis == null, so one null check reproduces
    // the old `delay != OFF && delayMs != null` guard exactly.
    val delayMs = autoLock.millis ?: return false          // OFF ⇒ manual only
    if (backgroundedAt <= 0L) return false
    return now - backgroundedAt >= delayMs
}
```

Behaviour for the existing cases is bit-identical (the old code's
`delay != OFF && delayMs != null` guard is preserved); only the source of
"enabled" changes.

**`SafeMeApp.onCreate`** — the existing collector at lines 101–109 already calls
`AppLockStateHolder.update(state)` + `AppLockGateController.onStateLoaded()`, so
**no change is needed**. Turning card 1 ON while the app is open re-arms the gate
through this path (and immediately via `setEntryLockEnabled`).

#### Holder write-ordering (known race, safe by construction)

`AppLockViewModel.init` (line 50) also calls `AppLockStateHolder.update(state)`
on every emission. Because a DataStore `Flow` replays the last persisted value
to a new collector, an in-flight emission can momentarily overwrite the value
`setEntryLockEnabled` / `setDisableProtectionEnabled` just wrote, until the
authoritative emission arrives.

This is **already** true today for `autoLock` / `biometricEnabled`, and it is
safe here for two reasons, both of which must hold in review:

1. The window is milliseconds and self-heals on the next emission; the worst
   case for card 1 is a momentary lock/unlock of a gate the user is looking at.
2. **Card 2 decisions must never be read from the holder.** `AppLockStateHolder
   .disableProtectionActive` is informational (UI sub-labels) only. Every
   `passwordRequired(...)` call site reads the *ViewModel's own collected state*
   — see Phase 4. A stale holder can then only ever cause an extra prompt
   (safe direction), never a skipped one.

### Phase 3 — verification sheet

**New file `ui/screens/applock/AppLockVerifySheet.kt`.** A `ModalBottomSheet`
that reuses the existing inputs and the existing rate limiter:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockVerifySheet(
    action: ProtectedAction,
    onVerified: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Read the freshest config, exactly like AppLockUnlockOverlay does.
    var lockType by remember { mutableStateOf(AppLockStateHolder.lockType) }
    var credentialLength by remember { mutableIntStateOf(AppLockStateHolder.credentialLength) }
    var pin by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf<List<Int>>(emptyList()) }
    val passwordFocus = remember { FocusRequester() }
    var retry by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var shake by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var lockedOut by remember { mutableStateOf(false) }
    var lockoutLeftMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        runCatching { context.appLockPrefs().first() }.getOrNull()?.let {
            lockType = it.lockType
            credentialLength = it.credentialLength
        }
        lockedOut = AppLockManager.isLockedOut(context)
        lockoutLeftMs = AppLockManager.getLockoutRemainingMs(context)
    }
    LaunchedEffect(lockedOut) { /* 1 s countdown ticker, same as the gate */ }

    fun submit(value: String) {
        if (checking || value.isBlank() || lockedOut) return
        checking = true
        scope.launch {
            val ok = AppLockManager.verify(context, value)   // shared rate limiter
            checking = false
            if (ok) {
                sheetState.hide()
                onVerified()
            } else if (AppLockManager.isLockedOut(context)) {
                lockedOut = true; error = ""
                lockoutLeftMs = AppLockManager.getLockoutRemainingMs(context)
            } else {
                error = context.getString(R.string.al_verify_wrong)
                shake = true; pin = ""; password = ""; pattern = emptyList(); retry++
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,        // cancel ⇒ nothing is applied
        sheetState = sheetState,
        containerColor = colors.surface,
        dragHandle = { GrabBar() },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)
            .shakeEffect(shake)) {
            // 40 dp icon chip (brandSoft / brandDark) + title + subtitle, per
            // docs/03 §"Card language".
            Text(stringResource(R.string.al_verify_title), /* 18 sp bold, ink */)
            Text(stringResource(R.string.al_verify_sub, actionLabel(action)), /* 13 sp, ink2 */)
            val inputEnabled = !checking && !lockedOut
            when (lockType) {
                LockType.PIN -> { PinDots(pin.length); PinKeypad(inputEnabled,
                    onDigit = { if (pin.length < credentialLength) { pin += it;
                        if (pin.length == credentialLength) submit(pin) } },
                    onDelete = { pin = pin.dropLast(1) }) }
                LockType.PASSWORD -> { LaunchedEffect(retry) { passwordFocus.requestFocus() }
                    PasswordField(password, { password = it;
                        if (it.length >= credentialLength) submit(it) },
                        stringResource(R.string.al_gate_password_placeholder),
                        inputEnabled, passwordFocus) }
                LockType.PATTERN -> PatternGrid(pattern, inputEnabled,
                    onDotTap = { i -> if (i !in pattern) { pattern += i;
                        if (pattern.size >= credentialLength) submit(pattern.joinToString("-")) } })
                LockType.OFF -> Unit    // unreachable: the sheet is only shown when hasCredential
            }
            Text(if (lockedOut) stringResource(R.string.al_gate_locked_out, formatLockout(lockoutLeftMs))
                 else error, /* 12.5 sp semibold, danger, 16 dp fixed height */)
            // Ghost "Cancel" button (al_disable_cancel) → onDismiss
        }
    }
}
```

Notes:
- `GrabBar` is currently `private` in `AppLockSheets.kt` — make it `internal`
  (single-word change) rather than duplicating it.
- `formatLockout` is `private` in `AppLockUnlockOverlay.kt` — move it to
  `AppLockInputs.kt` as `internal` so both overlays share it (no behaviour change).
- **No biometric path** (per the decision). `forgotPasswordDisabled` is
  irrelevant here: the sheet offers no recovery link.
- The rate limiter is **shared with the entry gate** by design — brute-forcing
  the disable prompt burns the same counter and can trigger the 5-minute
  lockout, which then also blocks app entry. That is the intended security
  posture for one credential; call it out in the KDoc.

**New file `ui/screens/applock/RememberProtectedAction.kt`** — the per-screen
glue, so no screen repeats the collect/state plumbing:

```kotlin
/**
 * Hosts the COD-79 verification sheet for one screen. [request] is the
 * ViewModel's verify-request flow (same shape as DnsVpnViewModel.consentRequest);
 * [pending] holds the action awaiting verification so the ViewModel does not
 * have to keep UI state. Returns nothing — call [ProtectedToggleHost] once per
 * screen root.
 */
@Composable
fun ProtectedActionHost(
    request: SharedFlow<ProtectedAction>,
    onVerified: (ProtectedAction) -> Unit,
    onCancelled: (ProtectedAction) -> Unit = {},
) {
    var pending by remember { mutableStateOf<ProtectedAction?>(null) }
    LaunchedEffect(Unit) { request.collect { pending = it } }
    pending?.let { action ->
        AppLockVerifySheet(
            action = action,
            onVerified = { pending = null; onVerified(action) },
            onDismiss = { pending = null; onCancelled(action) },
        )
    }
}
```

### Phase 4 — ViewModel pattern

Every gated ViewModel gets the same three additions. Template:

```kotlin
// 1. request channel (precedent: DnsVpnViewModel._consentRequest)
private val _verifyRequest = MutableSharedFlow<ProtectedAction>(extraBufferCapacity = 1)
val verifyRequest: SharedFlow<ProtectedAction> = _verifyRequest.asSharedFlow()

// 2. the action awaiting verification — held in the VM so the screen stays dumb
private var pendingAction: ProtectedAction? = null

/**
 * COD-79: the public entry point keeps its old name and signature, so every
 * existing call site compiles unchanged. The optimistic UI flip moved INTO
 * [applyToggle] so a cancelled/failed verification leaves the switch exactly
 * where it was (the issue's "canceling leaves the feature enabled").
 */
fun toggleBlocking() {
    val action = ProtectedAction.MASTER_BLOCKING
    if (protectionRequired(action)) {
        pendingAction = action
        _verifyRequest.tryEmit(action)
        return
    }
    applyToggle()
}

/** Called by the screen after a successful verification. */
fun onVerified() {
    val action = pendingAction ?: return
    pendingAction = null
    applyToggle()          // same code path as the ungated case
    Log.i(AppLockManager.TAG, "protected disable applied: ${action.id}")
}

/** Called when the sheet is dismissed without success. */
fun onCancelled() { pendingAction = null }   // no state was touched ⇒ nothing to revert

private fun applyToggle() {
    val next = !_uiState.value.blocking
    _uiState.update { it.copy(blocking = next) }        // optimistic flip, post-gate
    _toasts.tryEmit(app.getString(if (next) R.string.blk_toast_on else R.string.blk_toast_off))
    viewModelScope.launch {
        try { app.setBlockingEnabled(next) }
        catch (t: Throwable) { _uiState.update { it.copy(blocking = !next) } }
    }
}

/** Reads the ViewModel's own collected state, not the holder, so the decision
 *  can never race a DataStore emission. */
private fun protectionRequired(action: ProtectedAction): Boolean =
    DisableProtectionPolicy.passwordRequired(
        disableProtectionEnabled = _uiState.value.disableProtectionEnabled,
        hasCredential = _uiState.value.hasCredential,
        action = action,
    )
```

#### Feeding the gate inputs: one base class, not 13 copies of a collector

Thirteen ViewModels need the same `_verifyRequest` + `pendingAction` +
`onVerified/onCancelled` + `protectionRequired` plumbing. Rather than
copy-pasting ~45 lines into each, add **one shared base class**.

New file `data/DisableGate.kt` — the flow helper:

```kotlin
/** The COD-79 gate inputs, as a hot snapshot any ViewModel can collect. */
data class DisableGate(val disableProtectionEnabled: Boolean, val hasCredential: Boolean)

fun Context.disableGate(): Flow<DisableGate> =
    appLockPrefs()
        .map { DisableGate(it.disableProtectionEnabled, it.hasCredential) }
        .distinctUntilChanged()
```

New file `ui/screens/common/ProtectedViewModel.kt`:

```kotlin
/**
 * Base for any screen ViewModel with a COD-79-gated "turn off" control.
 *
 * Subclasses keep their own `_uiState`; the gate inputs live here so the
 * decision can never race the holder (see AppLockGateController notes) and so
 * the 13 integration points stay byte-identical.
 *
 * Request channel shape mirrors DnsVpnViewModel.consentRequest (line 71) —
 * the codebase's existing "ViewModel asks the screen to show UI" precedent.
 */
abstract class ProtectedViewModel(application: Application) : AndroidViewModel(application) {

    private val _verifyRequest = MutableSharedFlow<ProtectedAction>(extraBufferCapacity = 1)
    val verifyRequest: SharedFlow<ProtectedAction> = _verifyRequest.asSharedFlow()

    @Volatile private var gate = DisableGate(disableProtectionEnabled = true, hasCredential = false)
    private var pendingAction: ProtectedAction? = null

    init {
        viewModelScope.launch {
            try {
                getApplication<Application>().disableGate().collect { gate = it }
            } catch (_: Throwable) {
            }
        }
    }

    /** False ⇒ caller applies the change immediately (enabling, or no guard). */
    protected fun requestVerificationIfRequired(action: ProtectedAction): Boolean {
        if (!DisableProtectionPolicy.passwordRequired(
                disableProtectionEnabled = gate.disableProtectionEnabled,
                hasCredential = gate.hasCredential,
                action = action,
            )
        ) return false
        pendingAction = action
        _verifyRequest.tryEmit(action)
        return true
    }

    /** Screen calls this after a successful verification. */
    fun onVerified() {
        val action = pendingAction ?: return
        pendingAction = null
        Log.i(AppLockManager.TAG, "protected disable applied: ${action.id}")
        applyProtectedDisable(action)
    }

    /** Sheet dismissed / cancelled / wrong code ⇒ nothing was ever mutated. */
    fun onCancelled() { pendingAction = null }

    /** The OFF-direction mutation, run only after the gate is satisfied. */
    protected abstract fun applyProtectedDisable(action: ProtectedAction)
}
```

`gate` starts at `hasCredential = false` ⇒ `passwordRequired == false` before the
first emission, so a tap in the first few milliseconds of a screen's life is
never blocked by an unloaded preference (fail-open, matching `docs/11` §3).

**Which ViewModels extend it:** the 13 in the Phase 6 table that do not already
collect `appLockPrefs()`. Each becomes
`class XViewModel(application: Application) : ProtectedViewModel(application)`
— a one-line signature change; their existing `getApplication<Application>()`
calls keep working unchanged.

**Two deliberate exceptions:**

- **`HomeViewModel`** — already collects `app.appLockPrefs()` inside its
  `combine` (lines 112–131) and stores it in `lastAppLock`. Do **not**
  restructure those combines (5-flow typed `combine` + a nested 2-flow
  `combine`); instead add a plain `app.disableGate()` collector for the gate
  inputs and use the already-available `lastAppLock.entryLockActive` in
  `rebuild()`. Extending the base class here would be a second, redundant
  subscription to the same store.
- **`AppLockViewModel`** — already collects `appLockPrefs()` and needs bespoke
  logic for its two cards (card 1 is never gated; card 2 gates only its own OFF
  direction). Give it the same `_verifyRequest` / `pendingAction` /
  `onVerified` / `onCancelled` members directly, reading
  `_uiState.value.disableProtectionEnabled` + `.hasCredential`. Do not extend
  the base class.

### Phase 5 — App Lock screen

**`AppLockViewModel.kt`**

```kotlin
data class AppLockUiState(
    val lockType: LockType = LockType.OFF,
    val enabled: Boolean = false,          // retained: "a credential is configured"
    val hasCredential: Boolean = false,    // NEW
    val entryLockEnabled: Boolean = true,  // NEW — card 1
    val entryLockActive: Boolean = false,  // NEW — card 1 armed (drives hero/pill)
    val disableProtectionEnabled: Boolean = true, // NEW — card 2
    val autoLock: AutoLockDelay = AutoLockDelay.IMMEDIATELY,
    val biometricEnabled: Boolean = false,
    val forgotPasswordDisabled: Boolean = false,
    val biometricAvailable: Boolean = false,
)
```

- Populate the four new fields in both `init`'s collector and `refresh()`.
- New `setEntryLock(enabled: Boolean)`:
  - `enabled == true` and `!hasCredential` → toast `al_entry_need_lock` and return
    (defensive; the switch is also visually disabled).
  - otherwise `AppLockManager.setEntryLockEnabled(app, enabled)`, update UI state,
    toast `al_toast_entry_on` / `al_toast_entry_off`. **Never gated** (card 1 is
    not a "protected feature disable" — it *is* the entry protection, and gating
    it would mean the user must already be past a gate to open the gate).
- New `setDisableProtection(enabled: Boolean)`:
  - `enabled == true` → apply immediately (**enabling is never gated**).
  - `enabled == false` → if `DisableProtectionPolicy.passwordRequired(…)`, set
    `pendingAction = DISABLE_PROTECTION_ITSELF`, emit `_verifyRequest`; else apply.
  - `onVerified()` / `onCancelled()` as in the Phase 4 template.
- **Delete** `disableLock()` from the ViewModel (its only caller was the red
  button). `AppLockManager.disableLock` stays in the engine.

**`AppLockScreen.kt`**

- **Delete** `DisableButton` (lines 599–624), its `showDisableDialog` state
  (line 72), the call site (lines 122–125) and the `AlertDialog` (lines 161–203).
  Also drop the now-unused `al_disable*` string references (keep the strings
  themselves — `al_disable_cancel` is reused by the verify sheet's Cancel button).
- Add a `ProtectionToggles` section between `SettingsList` and `Note`, rendered
  **always** (dimmed when `!hasCredential`, exactly like `SettingsList` does):

```kotlin
Spacer(Modifier.height(20.dp))
Text(stringResource(R.string.al_protection_label), /* same style as al_settings_label */)
Spacer(Modifier.height(10.dp))
Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
    .background(colors.surface).border(1.dp, colors.line, RoundedCornerShape(20.dp))) {
    ProtectionRow(
        icon = AlLockIcon, iconBackground = colors.iconGreenBg, iconTint = colors.success,
        title = stringResource(R.string.al_entry_title),
        sub = stringResource(if (state.hasCredential) R.string.al_entry_sub else R.string.al_entry_sub_no_lock),
        checked = state.entryLockActive,
        enabled = state.hasCredential,          // dimmed + inert without a credential
        onToggle = { viewModel.setEntryLock(!state.entryLockEnabled) },
    )
    HorizontalDivider(color = colors.line)
    ProtectionRow(
        icon = AlShieldIcon, iconBackground = colors.iconAmberBg, iconTint = colors.warning,
        title = stringResource(R.string.al_dis_title),
        sub = stringResource(if (state.hasCredential) R.string.al_dis_sub else R.string.al_dis_sub_no_lock),
        checked = state.disableProtectionEnabled && state.hasCredential,
        enabled = state.hasCredential,
        onToggle = { viewModel.setDisableProtection(!state.disableProtectionEnabled) },
    )
}
```

`ProtectionRow` is a copy of the existing private `SettingRow` shape (40 dp icon
chip, 15 sp semibold title, 12 sp `ink2` sub, `MasterSwitch` trailing, `alpha
0.45` when disabled) — reuse `SettingRow` directly by passing
`trailing = { MasterSwitch(...) }` and `onClick = null`; **no new component is
needed**. `MasterSwitch` is already public in this file.

- `HeroCard`: pass `entryActive = state.entryLockActive` and
  `hasCredential = state.hasCredential` and select copy three ways:

| State | Title | Sub | Pill |
|---|---|---|---|
| `!hasCredential` | `al_hero_off_title` | `al_hero_off_sub` | red `al_hero_pill_off` |
| `hasCredential && entryActive` | `al_hero_on_title` (%1$s = method) | `al_hero_on_sub` | green `al_hero_pill_on` |
| `hasCredential && !entryActive` | **new** `al_hero_gate_off_title` | **new** `al_hero_gate_off_sub` | amber-ish red `al_hero_pill_off` |

  The third state is what COD-79 is *about* — the hero must say "SafeMe opens
  without a password" while making clear the credential is still stored and
  protecting feature changes.

- `LockActions` (Lock now / Change lock): keep the `state.enabled`
  (= `hasCredential`) condition. "Lock now" must call
  `AppLockGateController.lockNow()`, which is now a no-op when the entry gate is
  off — **improve the UX** by hiding "Lock now" when `!entryLockActive` and
  keeping "Change lock" visible whenever `hasCredential` (a user with entry lock
  off but card 2 on still needs to rotate the credential).
- `SettingsList` (auto-lock / biometric / forgot): keep dimming on
  `hasCredential`. Auto-lock and biometrics are entry-gate settings, so
  additionally dim them when `!entryLockActive` **but keep them interactive**
  (changing them must stay possible; they simply have no effect while the gate
  is off). Simplest, least-risk choice: **leave `SettingsList` exactly as-is**
  (gated on `hasCredential`) and let the new hero sub-line explain the gate
  state. Recommended.
- Host the sheet once at the screen root:

```kotlin
ProtectedActionHost(
    request = viewModel.verifyRequest,
    onVerified = { viewModel.onVerified() },
    onCancelled = { viewModel.onCancelled() },
)
```

### Phase 6 — integration across every gated surface

For each row below: add the `_verifyRequest` channel + `pendingAction` +
`onVerified()/onCancelled()` to the ViewModel, rename the mutating body into a
private `apply…()`, gate the public function on the **OFF direction only**, add
`ProtectedActionHost(...)` to the screen root, and pass
`onVerified = viewModel::onVerified`.

| # | ViewModel | Function to gate | Gate condition | Action | Screen to host the sheet |
|---|---|---|---|---|---|
| 1 | `HomeViewModel` | `toggleMasterProtection()` | `!enabled` → `applyToggle` ungated; `enabled` → gated | `MASTER_BLOCKING` | `HomeScreen.kt` |
| 2 | `BlockingViewModel` | `toggleBlocking()` | same | `MASTER_BLOCKING` | `BlockingScreen.kt` |
| 3 | `BlockingViewModel` | `toggleImageVideoSearch()` | same | `IMAGE_VIDEO_SEARCH` | `OtherFeaturesScreen.kt` (same VM instance type, separate screen ⇒ separate host) |
| 4 | `AntiTamperViewModel` | `deactivate()` | always gated (it is only ever called when `adminActive`) | `ANTI_TAMPER` | `AntiTamperScreen.kt:89` |
| 5 | `AccessibilityProtectionViewModel` | `setEnabled(false)` | `enabled == false` gated; `true` ungated | `ACCESSIBILITY_PROTECTION` | `AccessibilityProtectionScreen.kt:118` |
| 6 | `SocialBlockingViewModel` | `toggleMaster()` | OFF gated | `SOCIAL_BLOCKING_MASTER` | `SocialBlockingScreen.kt` |
| 7 | `SocialBlockingViewModel` | `applyPreset("relax")` | gated (`SocialBlockingPrefs.kt:139` sets `KEY_SOCIAL_ENABLED=false`); `deep`/`balanced` ungated | `SOCIAL_BLOCKING_MASTER` | `SocialBlockingScreen.kt:109` |
| 8 | `SocialBlockingViewModel` | `toggleLaunch(pkg)` | gated only when `pkg ∈ wholeBlocked` (removal = allowing the app) | `SOCIAL_BLOCKING_APP` | `SocialBlockingScreen.kt:116, 142` |
| 9 | `SocialBlockingViewModel` | `toggleYoutube/Facebook/Snapchat()` | gated only when currently `true` | `SOCIAL_BLOCKING_TAB` | `SocialBlockingScreen.kt:126-128` |
| 10 | `DnsVpnViewModel` | `toggle()` → `disable()` branch (line 169) | gated; the `reEnable()` and `enable()` branches stay ungated | `DNS_VPN` | `DnsVpnScreen.kt` (already hosts a `LaunchedEffect` collector — add a second host) |
| 11 | `ScheduleViewModel` | `toggleSchedule(id)` | gated only when `current == true` | `SCHEDULE_ITEM` | `ScheduleScreen.kt:143` |
| 12 | `ScheduleEditViewModel` | `setEnabled(false)` marks the working copy; gate at **`save()` (line 137, `fun save(): Boolean`)** | gated only when the **stored** schedule was enabled and the working copy is now `false` (compare against `storedEnabled`, which `resolveEditedEnabled` already tracks at lines 216-217) | `SCHEDULE_EDITOR_OFF` | `ScheduleEditScreen.kt:147` (`onClick = { viewModel.save() }`). VM comes from an explicit `ScheduleEditViewModel.Factory` at line 72-77 — **not** a default `viewModel()` param, so the sheet host must use that same `viewModel` instance |
| 13 | `TitleBlockViewModel` | `toggleRule(id, false)` | gated only when `enabled == false` | `TITLE_RULE` | `TitleBlockScreen.kt:130` |
| 14 | `QuickActionsEditViewModel` | `remove(action)` (line 81) | always gated (removal only) — `add`, `moveUp`, `moveDown`, `resetDefaults` ungated. **Gating this one is security-relevant, not pedantry:** removing the `APPLOCK` quick action (`QuickActionType.APPLOCK`, `QuickActionsPrefs.kt:26`) is how someone would hide the route back into the lock settings | `QUICK_ACTION_REMOVE` | `QuickActionsEditScreen.kt:141` (`onRemove = { viewModel.remove(action) }`) |
| 15 | `BlockScreenViewModel` | `toggleWhy()` (line 65) marks the working copy; gate at **`save()` (line 75, `suspend fun save(): Boolean`)** | gated only when stored `whyOn == true` and working copy `whyOn == false` (the only disable on that screen; dwell/message/image edits are not feature disables) | `BLOCK_SCREEN_WHY` | `BlockScreen.kt:200` (`CustomSwitch(checked = whyOn) { vm.toggleWhy() }`), commit at line 212 (`val ok = vm.save()`). VM is local: `val vm: BlockScreenViewModel = viewModel()` line 62 |
| 16 | `AppLockViewModel` | `setDisableProtection(false)` | gated | `DISABLE_PROTECTION_ITSELF` | `AppLockScreen.kt` |

**Explicitly NOT gated** (enabling, or not a protection toggle):

- Every enable direction: `setEnabled(true)`, `toggleBlocking` → on, `activate()`
  (Anti-Tamper), `enable()` / `reEnable()` (VPN), `deep` / `balanced` presets,
  `QuickActionsEditViewModel.add`, `setEntryLockEnabled(true)`,
  `setDisableProtectionEnabled(true)`, `saveLock`, `setAutoLock`,
  `setBiometricEnabled`, `setForgotDisabled`.
- `KeywordManagerViewModel.toggleBlocking(enabled)` — **no UI caller** (dead
  code, verified). Leave the function untouched; do not add a gate that cannot
  be exercised. Optionally note it in the PR description.
- `AntiTamperViewModel.refreshAdminActive()` — fires on return from the *system*
  Device-Admin page. A password prompt there would race a system dialog and can
  be bypassed anyway by deactivating in system Settings. It only syncs the
  DataStore flag to `DeviceAdminUtils.isActive()`. **Leave ungated**, add a KDoc
  comment explaining why (this is the one place where the guard is
  intentionally not applied; the accessibility PU guards still block the system
  page itself while PU is on).
- Deletion flows that are not feature toggles: keyword/website/title-rule
  *deletion*, `ProfileViewModel.deleteAccount()`, backup restore, and
  **`ScheduleEditViewModel.delete()`** (wired at `ScheduleEditScreen.kt:152` —
  deleting a schedule removes it entirely rather than switching it off). Out of
  scope for "feature switch turned OFF"; flag to the requester if they want them
  later. Note the asymmetry: toggling a schedule **off** is gated (row 11/12),
  deleting it is not — see §11 item 3.
- `ScheduleEditViewModel.toggleApp` / day toggles — these configure a schedule,
  they do not disable a protection.

**Two behavioural bugs this phase must not introduce:**

1. *Optimistic flip before the gate.* `HomeViewModel.toggleMasterProtection`
   (lines 203–208) and `BlockingViewModel.toggleBlocking` /
   `toggleImageVideoSearch` mutate `_uiState` **before** writing. If the gate
   kept that order, a cancelled verification would leave the switch visually off
   while DataStore still says on — and because nothing was written, no DataStore
   emission would ever correct it. Moving the flip into `apply…()` fixes this.
2. *Toast before verification.* `ScheduleViewModel.toggleSchedule` (lines 255–257)
   emits the on/off toast unconditionally and `SocialBlockingViewModel` emits
   toasts in every toggle. Move the toast into `apply…()` so a cancelled prompt
   is silent.

### Phase 7 — backup parity (`data/BackupCodec.kt`)

```kotlin
private fun AppLockPrefsState.toJson(): JSONObject {
    val o = JSONObject()
    o.put("lockType", lockType.storage)
    o.put("storedHash", storedHash)
    o.put("credentialLength", credentialLength)
    o.put("biometricEnabled", biometricEnabled)
    o.put("forgotPasswordDisabled", forgotPasswordDisabled)
    o.put("autoLock", autoLock.storage)
    o.put("entryLockEnabled", entryLockEnabled)                       // NEW
    o.put("disableProtectionEnabled", disableProtectionEnabled)       // NEW
    return o
}

private fun JSONObject.parseAppLock(): AppLockPrefsState = AppLockPrefsState(
    lockType = LockType.fromStorage(string("lockType")),
    storedHash = string("storedHash") ?: "",
    credentialLength = int("credentialLength", 0),
    biometricEnabled = boolean("biometricEnabled", false),
    forgotPasswordDisabled = boolean("forgotPasswordDisabled", false),
    autoLock = AutoLockDelay.fromStorage(string("autoLock")),
    // Default TRUE ⇒ a pre-COD-79 backup restores to "both protections on",
    // matching the on-device upgrade default. Never silently downgrades security.
    entryLockEnabled = boolean("entryLockEnabled", true),
    disableProtectionEnabled = boolean("disableProtectionEnabled", true),
)
```

- **No new `BackupSection`** — the fields ride in the existing `APP_LOCK`
  section, so `backupStores()`, `BackupSnapshot`, `presentSections` and the
  rollback logic are untouched.
- Old backups (keys absent) parse to `true/true`; `executeRestore` writes them
  through `writeAppLockPrefs`, which now persists both keys.
- `bundled-dataset.md` and `tools/ci/check_bundled_counts.py` are about keyword
  counts — **not affected**.

### Phase 8 — strings (`res/values/strings.xml`, App Lock block ≈ line 568–650)

```xml
<!-- COD-79: protection toggles -->
<string name="al_protection_label">Protection</string>
<string name="al_entry_title">App entry password</string>
<string name="al_entry_sub">Ask for your lock every time SafeMe is opened</string>
<string name="al_entry_sub_no_lock">Set up a lock first to require it on entry</string>
<string name="al_dis_title">Password to disable features</string>
<string name="al_dis_sub">Require your lock before any protection is turned off</string>
<string name="al_dis_sub_no_lock">Set up a lock first to protect feature changes</string>
<string name="al_toast_entry_on">App entry password is on</string>
<string name="al_toast_entry_off">SafeMe now opens without the app lock</string>
<string name="al_toast_dis_on">Password required to disable features</string>
<string name="al_toast_dis_off">Features can be disabled without a password</string>
<string name="al_entry_need_lock">Set up a lock first</string>
<string name="al_hero_gate_off_title">Opens without a lock</string>
<string name="al_hero_gate_off_sub">Your lock is still stored and protects feature changes.</string>

<!-- COD-79: verification sheet -->
<string name="al_verify_title">Confirm it\'s you</string>
<string name="al_verify_sub">Enter your %1$s to turn this off</string>
<string name="al_verify_wrong">Wrong code — try again</string>
<string name="al_verify_action_master_blocking">Blocking</string>
<string name="al_verify_action_anti_tamper">Anti-Tamper</string>
<string name="al_verify_action_a11y_protection">Accessibility Protection</string>
<string name="al_verify_action_social_master">Social Media Blocking</string>
<string name="al_verify_action_social_app">this app block</string>
<string name="al_verify_action_social_tab">this tab block</string>
<string name="al_verify_action_dns_vpn">DNS &amp; VPN filtering</string>
<string name="al_verify_action_image_video_search">Image &amp; video search blocking</string>
<string name="al_verify_action_schedule_item">this schedule</string>
<string name="al_verify_action_schedule_editor_off">this schedule</string>
<string name="al_verify_action_title_rule">this title rule</string>
<string name="al_verify_action_quick_action_remove">this quick action</string>
<string name="al_verify_action_block_screen_why">the \"Why blocked\" explanation</string>
<string name="al_verify_action_disable_protection">Password to disable features</string>
```

`actionLabel(action)` is a `when` over `ProtectedAction` returning the matching
resource — put it next to the sheet. Reuse existing `al_gate_locked_out`,
`al_gate_password_placeholder`, `al_disable_cancel`, `al_method_*`.
No new lint risk (`&` and quotes escaped as the file already does).

### Phase 9 — tests

**New JVM tests** (`app/src/test/java/com/safeme/app/protect/`), pure-logic only,
no Robolectric (matches `ProtectionLayersTest` / `AppLockManagerTest` style):

1. `DisableProtectionPolicyTest.kt`
   - the full 4-way matrix (5 rows of §4.2), for every `ProtectedAction`.
   - `entryLockActive(entryEnabled = true, hasCredential = false) == false`
     (the dead-end guard).
   - `passwordRequired(disableProtectionEnabled = false, hasCredential = true, …) == false`.
   - no action is gated when `hasCredential == false`.
2. `AppLockGateControllerDecisionTest.kt`
   - `stateLoadedLocked`: 2×2 table.
   - `foregroundLocked`: gate off ⇒ false; gate on + not session-unlocked ⇒
     true; each `AutoLockDelay` (IMMEDIATELY / 15 s / 30 s / 1 m / 5 m / OFF)
     at `elapsed = delay - 1` and `elapsed = delay`; `backgroundedAt == 0`.
   - These assert the **old** semantics still hold, i.e. no regression for
     existing users.
3. `AppLockPrefsStateTest.kt` (in `data/`)
   - derived properties: all 8 combinations of
     (`lockType`, `storedHash` blank/non-blank) × the two flags.
   - upgrade default: `AppLockPrefsState(lockType = PIN, storedHash = "a:b")`
     has both new flags `true` ⇒ `entryLockActive` and `disableProtectionActive`.
   - **Fix-1 guard (pure form):** `AppLockStateHolder.snapshot().copy(lockType =
     PASSWORD, storedHash = "s:h", credentialLength = 7)` with
     `entryLockEnabled = false` must keep `entryLockEnabled == false` and
     `disableProtectionEnabled` unchanged. This is the assertion that would have
     caught the draft's defect #1; `snapshot()` itself is Android-free, so it is
     JVM-testable even though it lives on the holder.
4. `ProtectedActionTest.kt`
   - `ProtectedAction.entries.size == 14` — the checklist guard from §7. Adding a
     15th action, or deleting one, must be a conscious, reviewed change.
   - every entry has `protected == true` and a non-blank, unique `id`.
5. `BackupCodecTest.kt` — extend, don't rewrite:
   - round-trip with both flags `false` (they must survive).
   - `parseAppLock` on a JSON object **without** the two keys ⇒ both `true`
     (old-backup compatibility). The existing assertions at lines 39 and 237
     use named args and keep compiling because the new fields have defaults.

**Existing tests that must stay green with no edits** (verified reasoning):
`AppLockManagerTest` (pure crypto/lockout — untouched), `ProtectionLayersTest`
(`evaluate` signature unchanged), `BackupManagerTest` (line 58/80 construct
`AppLockPrefsState` with named args), all `service/`, `vpn/`, `schedule/` tests.

**Optional instrumentation test** (`app/src/androidTest/…/applock/`), following
`AccessibilityProtectionCopyTest`'s `createComposeRule()` pattern:
`AppLockScreenTogglesTest` — with no credential, both `MasterSwitch`es are
disabled and show the `*_sub_no_lock` copy; the red "Disable App Lock" button is
gone. This is the only UI-level assertion worth an emulator in CI-free local runs
(CI runs `testDebugUnitTest` + `lintDebug` + `assembleDebug/Release` only — see
`.github/workflows/ci.yml`; instrumentation tests are **not** in CI).

**Manual device matrix** (must be run — COD-79 explicitly asks for it):

| # | Setup | Expected |
|---|---|---|
| 1 | No lock configured | App opens freely; both cards dimmed; no prompt anywhere |
| 2 | Lock set, both cards ON (default after upgrade) | Gate on cold start + on return per auto-lock; prompt on every OFF toggle |
| 3 | Card 1 OFF, card 2 ON | App opens straight in; prompt on every OFF toggle; card 1's state unchanged after each prompt |
| 4 | Card 1 ON, card 2 OFF | Gate on entry; no prompt on any OFF toggle |
| 5 | Both OFF | App opens freely; no prompts; credential still stored (hero shows "Opens without a lock") |
| 6 | Case 3 → cancel the sheet | the target switch stays ON, no toast, no write |
| 7 | Case 3 → wrong code ×10 | sheet shows the 5-min lockout countdown; cold-starting the app also shows the gate locked out (shared limiter) |
| 8 | Case 3 → turn card 2 OFF | prompt appears; on success no further prompts; card 1 still ON and still gating entry |
| 9 | Any case → enable anything (Anti-Tamper activate, VPN on, schedule on, quick action add) | **never** a prompt |
| 10 | Case 2 → backup, wipe data, restore | both toggles restored exactly |
| 11 | Pre-COD-79 backup file → restore | both toggles ON |
| 12 | Case 3 → Home shield ring | layer 4 (`appLock`) counts **off** while card 1 is off; back on when card 1 is on |
| 13 | **Case 5 → "Change lock" (rotate the credential)** | Both cards stay **OFF**. This is the on-device check for defect #1 (§10.1) — the old hand-written holder reconstruction would have snapped card 1 back ON |
| 14 | Case 3 → force-stop the app mid-prompt, reopen | No stale pending action; the target feature is still ON; the app opens without a gate (card 1 off) |
| 15 | Case 2 → open the verify sheet, press Back / swipe it down | Sheet dismisses, `onCancelled()` clears the pending action, switch unchanged, no toast |

### Phase 10 — docs & verification

- `docs/04-security-architecture.md` §2: add "Entry gate vs disable protection"
  describing the two flags, the derived `entryLockActive` /
  `disableProtectionActive`, the shared rate limiter, and the
  `refreshAdminActive()` exception.
- `docs/03-ui-design-system.md`: note the two new rows on the App Lock screen
  and that the red "Disable App Lock" button was removed.
- `README.md` feature table, App Lock row: mention both protections.
- Commands (from `docs/11-development-guide.md`):

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug          # must stay green — baseline covers only WRITE_SECURE_SETTINGS
./gradlew :app:assembleDebug
```

---

## 6. Impact on existing features (the "do not break" review)

| Area | Impact | Why it is safe |
|---|---|---|
| **Keyword / website / title blocking** (`SafeMeAccessibilityService`, `BlockGateActivity`, `BlockOverlayController`) | **None** | Enforcement reads `blockingPrefs()` directly; it never reads App Lock state. Only the *UI path* to `setBlockingEnabled(false)` gains a prompt. |
| **Schedule blocking** (`ScheduleEngine`, `ScheduleEvaluator`, `ScheduleAlarmReceiver`) | **None** | Same: engine consumes `schedulePrefs()` from the `SafeMeApp` collector. Gating happens before `app.toggleSchedule(...)`. |
| **VPN / DNS filtering** | **None** | `toggle()`'s `enable()`/`reEnable()` branches untouched; only `disable()` is gated. `TunnelRestartPolicy`, `PrivateDnsFilter`, `VpnStatusStore` unchanged. |
| **Prevent Uninstall / Device Admin / a11y guards** | **None** | `PreventUninstallBlockers`, `ProtectedSystemScreens`, `DeviceAdminUtils`, the watchdog and the a11y kill-vector rules are untouched. Only the in-app `deactivate()` entry point gains a prompt. |
| **a11y self-heal** (`A11yProtectionGuard`, `A11yProtectionUtils`, boot receiver) | **None** | Add-only rewrite logic unchanged; only the screen's master OFF gains a prompt. |
| **Home shield ring / protection layers** | **One intentional change** | `HomeViewModel.kt:152` becomes `appLockEnabled = lastAppLock.entryLockActive`. Layer 4 now means "the entry gate is genuinely armed", which is the documented semantics ("a layer counts only when the mechanism is genuinely functional right now"). `ProtectionLayersEvaluator` signature and `ALL_LAYERS` unchanged ⇒ `ProtectionLayersTest` still passes. |
| **Onboarding / permissions flow** | **None** | `AppLockGateHost` still sits above `OnboardingNavHost` in `MainActivity`; `entryLockActive` defaults preserve today's cold-start lock. |
| **Backup & restore** | **Additive** | Two keys inside the existing `APP_LOCK` section; absent keys default to `true`; validate-before-write and rollback logic untouched. |
| **Activity log / history / quick actions grid** | **None** | No new log types. Quick-action *removal in the editor* gains a prompt; the Home grid itself is unchanged. |
| **Cold-start lock timing** | **None** | Holder defaults (`entryLockEnabled = true`) + `hasCredential == false` before the first emission ⇒ identical decision to today. No `runBlocking` added (unlike the theme pref). |
| **Rate limiter / lockout** | **Shared by design** | Disable-prompt failures feed the same `safeme_applock_rate` counters. Documented, and asserted in the manual matrix (#7). |
| **Lint baseline** | **No new findings expected** | No new permissions, no new manifest entries, no `Color(0x…)` literals (tokens only), strings escaped. |

---

## 7. Risks & mitigations

| Risk | Mitigation |
|---|---|
| A gate shown with `lockType == OFF` renders no input ⇒ user locked out of the app | `entryLockActive` requires `hasCredential`; `AppLockGateController` never locks without it; asserted by `DisableProtectionPolicyTest` + `AppLockGateControllerDecisionTest`. |
| Optimistic UI leaves a switch visually off after a cancelled prompt | Move every optimistic flip and toast **inside** `apply…()` (Phase 6, bug #1/#2). Verify manually with matrix row #6. |
| Prompt fatigue from per-item gating (`all_incl_items` was chosen) | The sheet auto-submits at `credentialLength` (one tap for PIN/pattern), reuses the existing keypad, and `skipPartiallyExpanded = true` keeps it compact. If it proves too heavy in review, downgrading to masters-only is a one-line change per call site (drop the gate call) — no architecture change. |
| 16 integration points ⇒ easy to miss one | The `ProtectedAction` enum is the checklist: **14** values, each wired at exactly one gate site (card 1 / `setEntryLock` has no value because it is never password-gated — agreed). Add a test asserting `ProtectedAction.entries.size == 14` so adding an action without wiring it, or dropping one, shows up in review. |
| `refreshAdminActive()` is a real, ungated disable path | Documented as an accepted exception (system-page race, bypassable outside the app, PU guards still block the system page). Flagged here for the reviewer. |
| Changing `HomeViewModel` layer-4 input could be seen as a regression | It is the documented semantics; called out in §6 and in the PR description, with matrix row #12 as the acceptance check. |
| Two DataStore writes racing (card 1 + card 2) | Each mutator edits exactly one key; DataStore serialises edits per file. Independence asserted by the matrix and by the separate-key design. |
| **New prefs fields silently reset by `setLock()` / `disableLock()`** (the draft's defect #1) | Both mutators rebuilt the holder from a hand-written constructor that lists fields explicitly. Phase 2 replaces them with `AppLockStateHolder.snapshot().copy(...)`; `AppLockPrefsStateTest` asserts a lock set while `entryLockEnabled = false` leaves it `false`. |
| Changing 13 ViewModel superclasses to `ProtectedViewModel` | The base extends `AndroidViewModel`, which every one of them already extends, so `getApplication<Application>()` and `viewModelScope` keep working; the change is one line per class. Two VMs (`HomeViewModel`, `AppLockViewModel`) are explicit exceptions and are **not** reparented — see Phase 4. `ProtectedViewModel` adds one extra `disableGate()` subscription per VM; it is `distinctUntilChanged()` on a store those screens already touch, so there is no measurable cost. |
| A gated screen forgets to host `ProtectedActionHost` ⇒ the prompt never appears and the toggle looks dead | The VM emits into a `SharedFlow(extraBufferCapacity = 1)` and the action stays pending, so the failure is visible in review (a `verifyRequest` with no collector) and at runtime (switch does not move). The Phase 6 table lists the exact host screen per row; §10.2 records which screens take a default `viewModel()` param and which need an explicit instance (`ScheduleEditScreen`, `BlockScreen`). |

---

## 8. Files touched

**New (5 production + 3 test)**

| File | Purpose |
|---|---|
| `app/src/main/java/com/safeme/app/protect/DisableProtectionPolicy.kt` | pure decision core + `ProtectedAction` enum (14 values) |
| `app/src/main/java/com/safeme/app/data/DisableGate.kt` | `DisableGate` snapshot + `Context.disableGate()` flow |
| `app/src/main/java/com/safeme/app/ui/screens/common/ProtectedViewModel.kt` | shared base class: verify-request channel, pending action, `onVerified`/`onCancelled`, `requestVerificationIfRequired` |
| `app/src/main/java/com/safeme/app/ui/screens/applock/AppLockVerifySheet.kt` | verification bottom sheet + `ProtectedActionHost` + `actionLabel` |
| `app/src/test/java/com/safeme/app/protect/DisableProtectionPolicyTest.kt` | truth-table + dead-end guard tests |
| `app/src/test/java/com/safeme/app/protect/AppLockGateControllerDecisionTest.kt` | `stateLoadedLocked` / `foregroundLocked` regression tests |
| `app/src/test/java/com/safeme/app/data/AppLockPrefsStateTest.kt` | derived properties, upgrade defaults, **the `snapshot().copy()` guard from Fix 1** |

> `ui/screens/common/` would be a **new package** holding cross-screen
> infrastructure — which `ui/components/` (pure composables) and `ui/util/`
> (stateless helpers) do not cover. If the reviewer prefers no new package,
> `ProtectedViewModel.kt` can live in `ui/screens/applock/` beside the sheet;
> nothing else in the plan changes.

**Modified — core (6)**

`data/AppLockPrefs.kt` · `data/BackupCodec.kt` · `protect/AppLockManager.kt` ·
`ui/screens/applock/AppLockGateController.kt` ·
`ui/screens/applock/AppLockViewModel.kt` · `ui/screens/applock/AppLockScreen.kt`

**Modified — integration (13 ViewModels reparented + their screens)**

Each ViewModel below changes its superclass to `ProtectedViewModel` (one line),
gains an `applyProtectedDisable(action)` override, and moves its OFF-direction
mutation + toast inside it; each screen gains one `ProtectedActionHost(...)` at
its root. Line-level detail per row is in the Phase 6 table.

`ui/screens/home/HomeViewModel.kt` + `HomeScreen.kt` (**exception — not reparented**, see Phase 4) ·
`ui/screens/blocking/BlockingViewModel.kt` + `BlockingScreen.kt` + `OtherFeaturesScreen.kt` ·
`ui/screens/antitamper/AntiTamperViewModel.kt` + `AntiTamperScreen.kt` +
`AccessibilityProtectionViewModel.kt` + `AccessibilityProtectionScreen.kt` ·
`ui/screens/socialblocking/SocialBlockingViewModel.kt` + `SocialBlockingScreen.kt` ·
`ui/screens/vpn/DnsVpnViewModel.kt` + `DnsVpnScreen.kt` ·
`ui/screens/schedule/ScheduleViewModel.kt` + `ScheduleScreen.kt` + `ScheduleEditViewModel.kt` + `ScheduleEditScreen.kt` ·
`ui/screens/titleblock/TitleBlockViewModel.kt` + `TitleBlockScreen.kt` ·
`ui/screens/home/QuickActionsEditScreen.kt` (VM is in the same file) ·
`ui/screens/blockscreen/BlockScreenViewModel.kt` + `BlockScreen.kt`

(`AppLockViewModel` + `AppLockScreen` are the second exception and are listed
under "Modified — core".)

**Modified — small/shared (4)**

`res/values/strings.xml` · `ui/screens/applock/AppLockSheets.kt`
(`GrabBar` → `internal`) · `ui/screens/applock/AppLockUnlockOverlay.kt`
(`formatLockout` → `internal`, moved to `AppLockInputs.kt`) ·
`ui/screens/home/HomeViewModel.kt` layer-4 input (counted above)

**Docs (3)** — `docs/04-security-architecture.md`, `docs/03-ui-design-system.md`, `README.md`

**Untouched on purpose** — everything in `service/`, `vpn/`, `protect/` except
`AppLockManager.kt`; `BlockGateActivity`, `BlockOverlayController`,
`ScheduleEngine`, `ScheduleEvaluator`, `A11yProtection*`, `PreventUninstall*`,
`ProtectedSystemScreens`, `DeviceAdminUtils`, `BackupManager`, `AppCatalog`,
`BundledKeywords`, theme files, `AndroidManifest.xml`, `build.gradle.kts`,
`lint-baseline.xml`, CI workflow.

---

## 9. Suggested commit sequence

1. `feat(applock): add entryLockEnabled + disableProtectionEnabled prefs (COD-79)` — Phase 1 + 7 + codec tests
2. `feat(applock): separate entry gate from credential existence (COD-79)` — Phase 2 + gate decision tests
3. `feat(applock): add DisableProtectionPolicy + ProtectedAction (COD-79)` — Phase 3 policy + tests
4. `feat(applock): verification sheet reusing lock inputs (COD-79)` — Phase 3 UI
5. `feat(applock): two protection toggle cards, drop Disable button (COD-79)` — Phase 5 + 8
6. `refactor(vm): add ProtectedViewModel base + disableGate flow (COD-79)` — Phase 4 base class, no call sites yet (compiles clean on its own)
7. `feat(protection): gate every feature-disable behind the password (COD-79)` — Phase 6 (13 VMs reparented + hosts wired)
8. `docs(security): document entry vs disable protection (COD-79)` — Phase 10

---

## 10. Re-verification pass

This plan was re-checked line-by-line against the working tree after drafting.
Everything below was **confirmed by reading the file**, not inferred.

### 10.1 Defects found in the draft and corrected here

| # | Defect in the first draft | Consequence if shipped | Correction |
|---|---|---|---|
| **1** | Adding fields to `AppLockPrefsState` without touching `AppLockManager.setLock()` (`:165-174`) / `disableLock()` (`:183-192`), which rebuild the holder from a **hand-written constructor call** that copies only the fields it lists | Any new field silently resets to its default on "Change lock" / setup — `entryLockEnabled` would snap back to `true`, re-arming the entry gate the user had just turned off. Directly violates "must not influence other existing features" | Phase 2 now mandates `AppLockStateHolder.snapshot()` + `.copy()`, with a regression test |
| **2** | Phase 4 told the implementer to add `disableGate()` into `HomeViewModel`'s existing `combine` chain | `HomeViewModel` uses a 5-flow typed `combine` (`:112-120`) plus a nested 2-flow `combine` (`:122`); a 7th flow forces restructuring both and risks the shield-ring regression | `HomeViewModel` is now an explicit exception: it already stores `lastAppLock`, so `rebuild()` just reads `lastAppLock.entryLockActive`; the gate inputs come from a plain extra collector |
| **3** | 13 near-identical `_verifyRequest` / `pendingAction` blocks copy-pasted into 13 ViewModels | ~585 duplicated lines, 13 places to get the fail-open default wrong | Replaced by one `ProtectedViewModel` base class + one-line signature change per VM |
| **4** | `foregroundLocked` had a redundant `autoLock == OFF` check after the `millis ?: return` null check | Dead code in a security decision path | Removed; a comment now records that `OFF.millis == null` reproduces the old guard |
| **5** | Silent holder write-ordering race (`AppLockViewModel.init:50` also calls `AppLockStateHolder.update`) not documented | A reviewer could "fix" it by reading card-2 decisions from the holder, or miss that a stale value is possible | Phase 2 now documents the race, why it is safe, and the hard rule that card-2 decisions never read the holder |
| **6** | `enum class ProtectedAction` listed `APP_LOCK_ENTRY`, which has no gate site (card 1 is never password-gated) | An enum value with no wiring invites someone to "complete" it by gating card 1, contradicting the agreed decision | Value removed; enum is 14 entries with an in-code comment explaining the deliberate absence |
| **7** | Imprecise citations: `MasterSwitch` at 651 (it is declared at 651, `@Composable` at 650); rows 12/15 named `save()` without saying the *toggle* only marks a working copy; row 14 gave no reason to gate quick-action removal | Implementer would gate the wrong function, or gate the in-memory toggle instead of the commit | Corrected, with the VM-acquisition caveat for `ScheduleEditScreen` (explicit `Factory` at `:72-77`, not a default param) and `BlockScreen` (local `vm` at `:62`) |
| **8** | `ScheduleEditViewModel.delete()` (wired at `ScheduleEditScreen.kt:152`) was not classified | Ambiguity: toggling a schedule off is gated, deleting it was unaddressed | Explicitly listed as not-gated, and surfaced as §11 item 3 |

### 10.2 Facts confirmed (spot-check list)

| Claim | Verified at |
|---|---|
| `AppLockStateHolder.enabled == (lockType != OFF)` is the sole conflation point | `protect/AppLockManager.kt:42` |
| Gate reads `enabled` in exactly 4 branches | `AppLockGateController.kt:37, 48, 72, 81` |
| `MainActivity` drives the gate from `onStart`/`onStop` | `MainActivity.kt:54, 58` |
| `SafeMeApp` collector feeds the holder + gate | `SafeMeApp.kt:101-109` |
| Red "Disable App Lock" button + dialog + its only caller | `AppLockScreen.kt:72, 122-125, 161-203, 599-624`; `AppLockViewModel.kt:109-118`; `AppLockManager.kt:178` |
| `disableLock()` / `setLock()` have exactly one caller each, both in `AppLockViewModel` | grep: `AppLockViewModel.kt:99, 111` only |
| `writeAppLockPrefs` is backup-restore-only | grep: `BackupManager.kt:150` only |
| `MasterSwitch(checked, onToggle, enabled)` is public in `AppLockScreen.kt` | declaration at `:651` |
| `SettingRow` is private and already renders `alpha 0.45` when `enabled == false` | `AppLockScreen.kt:531, 543` |
| Overlay renders **no input** for `LockType.OFF` (the dead-end to avoid) | `AppLockUnlockOverlay.kt:289` |
| `HomeViewModel` layer-4 input is the only shield-ring dependency | `HomeViewModel.kt:152` |
| Optimistic flips that must move behind the gate | `HomeViewModel.kt:203-208`; `BlockingViewModel.kt:89-94, 106-111` |
| Unconditional toasts that must move behind the gate | `ScheduleViewModel.kt:255-257`; all `SocialBlockingViewModel` toggles |
| `KeywordManagerViewModel.toggleBlocking` is dead code | grep across `ui/` returns only its own declaration (`:71`) |
| `refreshAdminActive()` can write `preventUninstallEnabled = false` | `AntiTamperViewModel.kt:60-80` |
| `applyPreset("relax")` is a master disable | `SocialBlockingPrefs.kt:139-147` sets `KEY_SOCIAL_ENABLED = false` |
| `DnsVpnViewModel.toggle()` has three branches; only `disable()` is a disable | `DnsVpnViewModel.kt:165-179`; `disable()` at `:211` |
| Request-channel precedent exists | `DnsVpnViewModel.kt:71` → `DnsVpnScreen.kt:76-85` |
| Sheet precedent + `GrabBar` is private (needs `internal`) | `AppLockSheets.kt:139, 305`; `GrabBar` at `:380` |
| `formatLockout` is private (needs moving/sharing) | `AppLockUnlockOverlay.kt:399` |
| Reusable inputs are already public | `AppLockInputs.kt`: `shakeEffect:52`, `PinDots:66`, `PinKeypad:95`, `PatternGrid:187`, `PasswordField:242` |
| **SafeMe's own UI is never blocked**, so the verify sheet cannot be covered by the block gate | `SafeMeAccessibilityService.kt:83-87` (`isOwnUiEvent`), used at `:732`; commit `77d3c16 fix(service): never gate SafeMe's own UI` |
| Backup: `appLock` toJson/parse live in the `APP_LOCK` section | `BackupCodec.kt:23, 82, 95, 108, 146, 202, 315-333`; `BackupManager.kt:43, 111, 147-151` |
| Test deps available: JUnit + real `org.json`; no MockK, no Robolectric | `app/build.gradle.kts:89-100` |
| CI runs `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease`; **no instrumentation tests** | `.github/workflows/ci.yml` |
| Instrumentation-test pattern to copy if wanted | `androidTest/.../AccessibilityProtectionCopyTest.kt` (`createComposeRule()`) |
| 17 screens take a default `viewModel()` param (all Phase 6 hosts except `ScheduleEditScreen`, `BlockScreen`, `ScheduleScreen`) | grep for `viewModel: …ViewModel = viewModel()` |

### 10.3 What the re-verification did **not** change

The architecture, the persistence model, the truth table, the 16-row integration
inventory, the backup strategy, the string set, the test plan and the
"do not break" review all survived verification unchanged. Only the eight items
in §10.1 were wrong or imprecise.

---

## 11. Open items to confirm during review

1. **`refreshAdminActive()`** stays ungated (Anti-Tamper can still be turned off
   from system Settings, which the PU accessibility guards already block while
   PU is on). Confirm this is acceptable, or gate it too and accept a possible
   race with the system deactivation dialog.
2. **Hero copy** for the new third state ("Opens without a lock") — wording is a
   proposal; the prototype has no such state, so this is net-new copy.
3. **Deletion flows are not gated** — keyword/website/title-rule delete,
   `ProfileViewModel.deleteAccount()`, and `ScheduleEditViewModel.delete()`
   (`ScheduleEditScreen.kt:152`). They remove data rather than switch a
   protection off. Note the resulting asymmetry: toggling a schedule **off**
   prompts for the password, **deleting** it does not. Say the word if deletions
   should be gated too.
4. **`KeywordManagerViewModel.toggleBlocking`** is dead code (no caller). Leave,
   or delete in a separate cleanup commit?
