package com.safeme.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Lock method. OFF means App Lock is disabled. */
enum class LockType(val storage: String) {
    OFF("off"),
    PIN("pin"),
    PASSWORD("password"),
    PATTERN("pattern");

    companion object {
        fun fromStorage(value: String?): LockType =
            entries.firstOrNull { it.storage == value } ?: OFF
    }
}

/** Auto-lock delay applied when the app leaves the foreground. */
enum class AutoLockDelay(val storage: String, val millis: Long?) {
    IMMEDIATELY("immediately", 0L),
    AFTER_15S("after_15s", 15_000L),
    AFTER_30S("after_30s", 30_000L),
    AFTER_1M("after_1m", 60_000L),
    AFTER_5M("after_5m", 300_000L),
    /** Manual locking only ("Lock now"). */
    OFF("off", null);

    companion object {
        fun fromStorage(value: String?): AutoLockDelay =
            entries.firstOrNull { it.storage == value } ?: IMMEDIATELY
    }
}

/**
 * Persistent App Lock state. [storedHash] holds the PBKDF2 credential as
 * `"<saltHex>:<hashHex>"` — a one-way hash, never the raw credential.
 * [biometricEnabled] / [forgotPasswordDisabled] only matter while a lock is set.
 *
 * ## Two independent protections (COD-79)
 *
 * Storing a credential and *using* it are separate concerns, so the state
 * carries two flags that never imply each other:
 *
 *  - [entryLockEnabled] — card 1, "App entry password". Arms the full-screen
 *    unlock gate when the app is opened or returns from the background.
 *  - [disableProtectionEnabled] — card 2, "Password to disable features".
 *    Requires the credential before any protection is turned **off**.
 *
 * Both default to `true` so an install upgraded from a build without these
 * fields keeps behaving exactly as before: a configured lock still gates entry,
 * and (the new part) still guards feature changes. Neither flag has any effect
 * while no credential is stored — see [hasCredential].
 *
 * Because card 2 verifies against the stored credential, turning card 1 off
 * must NOT destroy it. The credential is therefore only ever replaced
 * ([com.safeme.app.protect.AppLockManager.setLock]) or cleared by a backup
 * restore that carries an empty one — never by a toggle.
 */
data class AppLockPrefsState(
    val lockType: LockType = LockType.OFF,
    val storedHash: String = "",
    /** Credential length (PIN digits / pattern dots / password chars). The
     *  hash cannot reveal it; the lock UI needs it to render dots and know
     *  when to auto-submit. Leaks only the length, which the prototype's dot
     *  row already shows. */
    val credentialLength: Int = 0,
    val biometricEnabled: Boolean = false,
    val forgotPasswordDisabled: Boolean = false,
    val autoLock: AutoLockDelay = AutoLockDelay.IMMEDIATELY,
    /** COD-79 card 1 — require the credential to open the app. */
    val entryLockEnabled: Boolean = true,
    /** COD-79 card 2 — require the credential to turn a protection off. */
    val disableProtectionEnabled: Boolean = true,
) {
    /**
     * A usable credential is stored. Both COD-79 protections are meaningless
     * without one, and arming the entry gate without one would be a lockout:
     * the unlock overlay renders no input at all for [LockType.OFF], so the
     * user would face a full-screen gate with nothing to type into.
     */
    val hasCredential: Boolean
        get() = lockType != LockType.OFF && storedHash.isNotBlank()

    /** Card 1 is actually armed. Never true without a credential. */
    val entryLockActive: Boolean
        get() = entryLockEnabled && hasCredential

    /** Card 2 is actually armed. Never true without a credential. */
    val disableProtectionActive: Boolean
        get() = disableProtectionEnabled && hasCredential
}

private val Context.appLockDataStore by preferencesDataStore(name = "safeme_applock_prefs")

val KEY_APP_LOCK_TYPE = stringPreferencesKey("lock_type")
val KEY_APP_LOCK_HASH = stringPreferencesKey("stored_hash")
val KEY_APP_LOCK_LENGTH = intPreferencesKey("lock_length")
val KEY_APP_LOCK_BIOMETRIC = booleanPreferencesKey("biometric_enabled")
val KEY_APP_LOCK_FORGOT = booleanPreferencesKey("forgot_disabled")
val KEY_APP_LOCK_AUTO = stringPreferencesKey("auto_lock")

/**
 * COD-79 card 1. Written by [setAppLockEntryEnabled] ONLY — no other mutator
 * touches this key, which is what keeps the two protections independent at the
 * storage level.
 */
val KEY_APP_LOCK_ENTRY = booleanPreferencesKey("entry_lock_enabled")

/** COD-79 card 2. Written by [setAppLockDisableProtectionEnabled] ONLY. */
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
                // Default TRUE: a store written by a pre-COD-79 build has no
                // such key, and its user expects the app to keep locking on
                // entry. Anything else would silently unlock every upgrade.
                entryLockEnabled = prefs[KEY_APP_LOCK_ENTRY] ?: true,
                disableProtectionEnabled = prefs[KEY_APP_LOCK_DISABLE_PROT] ?: true,
            )
        }

/** Persist the lock method + credential hash in one atomic edit. */
suspend fun Context.setAppLock(type: LockType, storedHash: String, credentialLength: Int) {
    appLockDataStore.edit { prefs ->
        prefs[KEY_APP_LOCK_TYPE] = type.storage
        prefs[KEY_APP_LOCK_HASH] = storedHash
        prefs[KEY_APP_LOCK_LENGTH] = credentialLength
    }
}

suspend fun Context.setAppLockBiometric(enabled: Boolean) {
    appLockDataStore.edit { prefs ->
        prefs[KEY_APP_LOCK_BIOMETRIC] = enabled
    }
}

suspend fun Context.setAppLockForgotDisabled(disabled: Boolean) {
    appLockDataStore.edit { prefs ->
        prefs[KEY_APP_LOCK_FORGOT] = disabled
    }
}

suspend fun Context.setAppLockAutoLock(delay: AutoLockDelay) {
    appLockDataStore.edit { prefs ->
        prefs[KEY_APP_LOCK_AUTO] = delay.storage
    }
}

/**
 * COD-79 card 1 — arm/disarm the entry gate.
 *
 * Writes exactly one key. The credential and card 2 are deliberately untouched
 * so that opening the app without a password can never weaken (or silently
 * strengthen) the protection applied to feature changes.
 */
suspend fun Context.setAppLockEntryEnabled(enabled: Boolean) {
    appLockDataStore.edit { prefs ->
        prefs[KEY_APP_LOCK_ENTRY] = enabled
    }
}

/** COD-79 card 2 — require the credential before turning a protection off. */
suspend fun Context.setAppLockDisableProtectionEnabled(enabled: Boolean) {
    appLockDataStore.edit { prefs ->
        prefs[KEY_APP_LOCK_DISABLE_PROT] = enabled
    }
}

/** Replaces all App Lock settings in one atomic edit (backup restore). */
suspend fun Context.writeAppLockPrefs(state: AppLockPrefsState) {
    appLockDataStore.edit { prefs ->
        prefs[KEY_APP_LOCK_TYPE] = state.lockType.storage
        prefs[KEY_APP_LOCK_HASH] = state.storedHash
        prefs[KEY_APP_LOCK_LENGTH] = state.credentialLength
        prefs[KEY_APP_LOCK_BIOMETRIC] = state.biometricEnabled
        prefs[KEY_APP_LOCK_FORGOT] = state.forgotPasswordDisabled
        prefs[KEY_APP_LOCK_AUTO] = state.autoLock.storage
        prefs[KEY_APP_LOCK_ENTRY] = state.entryLockEnabled
        prefs[KEY_APP_LOCK_DISABLE_PROT] = state.disableProtectionEnabled
    }
}
