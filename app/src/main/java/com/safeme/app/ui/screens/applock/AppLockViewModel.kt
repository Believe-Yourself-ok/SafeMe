package com.safeme.app.ui.screens.applock

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.safeme.app.R
import com.safeme.app.data.AppLockPrefsState
import com.safeme.app.data.AutoLockDelay
import com.safeme.app.data.LockType
import com.safeme.app.data.appLockPrefs
import com.safeme.app.data.setAppLockAutoLock
import com.safeme.app.data.setAppLockBiometric
import com.safeme.app.data.setAppLockDisableProtectionEnabled
import com.safeme.app.data.setAppLockEntryEnabled
import com.safeme.app.data.setAppLockForgotDisabled
import com.safeme.app.protect.AppLockBiometrics
import com.safeme.app.protect.AppLockManager
import com.safeme.app.protect.AppLockStateHolder
import com.safeme.app.protect.ProtectedAction
import com.safeme.app.ui.screens.common.ProtectedViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppLockUiState(
    val lockType: LockType = LockType.OFF,
    val autoLock: AutoLockDelay = AutoLockDelay.IMMEDIATELY,
    val biometricEnabled: Boolean = false,
    val forgotPasswordDisabled: Boolean = false,
    val biometricAvailable: Boolean = false,
    // COD-79: the two independent protections. Both default to true, matching
    // the persisted defaults, so the first frame never shows a switch in a
    // state the store disagrees with.
    val entryLockEnabled: Boolean = true,
    val disableProtectionEnabled: Boolean = true,
    /** A credential is stored, so there is something to verify against. */
    val hasCredential: Boolean = false,
) {
    /**
     * Card 1 is armed: SafeMe asks for the credential on entry.
     * Mirrors [AppLockPrefsState.entryLockActive] so the screen and the gate
     * controller can never disagree about what "on" means.
     */
    val entryLockActive: Boolean get() = entryLockEnabled && hasCredential

    /** Card 2 is armed: weakening a protection asks for the credential. */
    val disableProtectionActive: Boolean get() = disableProtectionEnabled && hasCredential

    /**
     * A credential is stored but neither protection is using it. This is the
     * only state where deleting it outright is safe to offer — see
     * [AppLockViewModel.removeLock].
     */
    val credentialIdle: Boolean get() = hasCredential && !entryLockActive && !disableProtectionActive
}

/**
 * State for the App Lock screen (hero, the two COD-79 protection cards, lock
 * settings, setup wizard).
 *
 * Extends [ProtectedViewModel] because card 2's own OFF direction is a
 * protected action — see [setDisableProtectionEnabled].
 */
class AppLockViewModel(application: Application) : ProtectedViewModel(application) {

    private val app = getApplication<Application>()

    private val _uiState = MutableStateFlow(AppLockUiState())
    val uiState: StateFlow<AppLockUiState> = _uiState.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    init {
        viewModelScope.launch {
            try {
                app.appLockPrefs().collect { state ->
                    AppLockStateHolder.update(state)
                    _uiState.update { it.applyPrefs(state) }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun AppLockUiState.applyPrefs(state: AppLockPrefsState) = copy(
        lockType = state.lockType,
        autoLock = state.autoLock,
        biometricEnabled = state.biometricEnabled,
        forgotPasswordDisabled = state.forgotPasswordDisabled,
        biometricAvailable = AppLockBiometrics.isAvailable(app),
        entryLockEnabled = state.entryLockEnabled,
        disableProtectionEnabled = state.disableProtectionEnabled,
        hasCredential = state.hasCredential,
    )

    // ------------------------------------------------------ COD-79 card 1

    /**
     * Arm or disarm the entry gate.
     *
     * **Never gated, in either direction.** Arming only adds protection, and
     * disarming must not require the credential: demanding it would mean the
     * user has to unlock in order to stop being locked out, which is precisely
     * the trap COD-79 exists to remove.
     *
     * The stored credential is untouched, so card 2 keeps working — that is the
     * independence the issue asks for. Disarming also drops the gate
     * immediately: persisting fires the SafeMeApp collector, which refreshes
     * [AppLockStateHolder] and calls `AppLockGateController.onStateLoaded()`.
     *
     * Arming re-locks at once, exactly as setting a lock for the first time
     * already does — the user proves the credential works the moment they turn
     * the protection on.
     */
    fun setEntryLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { app.setAppLockEntryEnabled(enabled) }
            AppLockStateHolder.update(
                AppLockStateHolder.snapshot().copy(entryLockEnabled = enabled),
            )
            _uiState.update { it.copy(entryLockEnabled = enabled) }
            _toasts.tryEmit(
                app.getString(
                    if (enabled) R.string.al_toast_entry_on else R.string.al_toast_entry_off,
                ),
            )
        }
    }

    // ------------------------------------------------------ COD-79 card 2

    /**
     * Require the credential before a protection is weakened.
     *
     * Turning this ON is never gated — enabling a protection only strengthens
     * it, and COD-79 forbids prompting for that.
     *
     * Turning it OFF **is** gated, by [ProtectedAction.DISABLE_PROTECTION_ITSELF].
     * This is the single most consequential disable in the app: it disarms every
     * other guard at once. Without the prompt, anyone who reached this screen
     * with card 1 off could simply switch card 2 off too and the whole feature
     * would be worthless.
     */
    fun setDisableProtectionEnabled(enabled: Boolean) {
        if (enabled) {
            viewModelScope.launch {
                runCatching { app.setAppLockDisableProtectionEnabled(true) }
                AppLockStateHolder.update(
                    AppLockStateHolder.snapshot().copy(disableProtectionEnabled = true),
                )
                _uiState.update { it.copy(disableProtectionEnabled = true) }
                _toasts.tryEmit(app.getString(R.string.al_toast_disable_prot_on))
            }
            return
        }
        guardDisable(ProtectedAction.DISABLE_PROTECTION_ITSELF) {
            viewModelScope.launch {
                runCatching { app.setAppLockDisableProtectionEnabled(false) }
                AppLockStateHolder.update(
                    AppLockStateHolder.snapshot().copy(disableProtectionEnabled = false),
                )
                _uiState.update { it.copy(disableProtectionEnabled = false) }
                _toasts.tryEmit(app.getString(R.string.al_toast_disable_prot_off))
            }
        }
    }

    /**
     * Delete the stored credential outright.
     *
     * Replaces the old red "Disable App Lock" button, which destroyed the
     * credential as a side effect of turning the lock off — the conflation
     * COD-79 splits apart. Offered only when [AppLockUiState.credentialIdle]:
     * with both protections already off there is nothing left to undermine, so
     * no prompt is needed, and the user still has a way to delete a credential
     * they no longer want. Turning either card off is the supported way to stop
     * being asked for it.
     */
    fun removeLock() {
        viewModelScope.launch {
            runCatching { AppLockManager.disableLock(app) }
                .onFailure { _toasts.tryEmit(app.getString(R.string.kw_toast_error)) }
                .onSuccess {
                    refresh()
                    _toasts.tryEmit(app.getString(R.string.al_toast_removed))
                }
        }
    }

    // ------------------------------------------------- existing behaviour

    fun setAutoLock(delay: AutoLockDelay) {
        viewModelScope.launch {
            runCatching { app.setAppLockAutoLock(delay) }
            AppLockStateHolder.autoLock = delay
            _uiState.update { it.copy(autoLock = delay) }
            _toasts.tryEmit(app.getString(R.string.al_toast_auto, autoLockLabel(delay)))
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        if (enabled && !AppLockBiometrics.isAvailable(app)) {
            _toasts.tryEmit(app.getString(R.string.al_toast_bio_unavailable))
            return
        }
        viewModelScope.launch {
            runCatching { app.setAppLockBiometric(enabled) }
            AppLockStateHolder.biometricEnabled = enabled
            _uiState.update { it.copy(biometricEnabled = enabled) }
        }
    }

    fun setForgotDisabled(disabled: Boolean) {
        viewModelScope.launch {
            runCatching { app.setAppLockForgotDisabled(disabled) }
            AppLockStateHolder.forgotPasswordDisabled = disabled
            _uiState.update { it.copy(forgotPasswordDisabled = disabled) }
        }
    }

    /** Save a new/updated lock from the setup wizard. */
    fun saveLock(type: LockType, input: String) {
        viewModelScope.launch {
            runCatching { AppLockManager.setLock(app, type, input) }
                .onFailure { _toasts.tryEmit(app.getString(R.string.kw_toast_error)) }
                .onSuccess {
                    refresh()
                    _toasts.tryEmit(app.getString(R.string.al_toast_on))
                }
        }
    }

    fun lockNow() {
        AppLockGateController.lockNow()
    }

    /** Surface a message from a child sheet (e.g. wizard mismatch). */
    fun showToast(message: String) {
        _toasts.tryEmit(message)
    }

    private suspend fun refresh() {
        val state = runCatching { app.appLockPrefs().first() }.getOrNull() ?: return
        AppLockStateHolder.update(state)
        _uiState.update { it.applyPrefs(state) }
    }

    private fun autoLockLabel(delay: AutoLockDelay): String = when (delay) {
        AutoLockDelay.IMMEDIATELY -> app.getString(R.string.al_auto_immediately)
        AutoLockDelay.AFTER_15S -> app.getString(R.string.al_auto_15s)
        AutoLockDelay.AFTER_30S -> app.getString(R.string.al_auto_30s)
        AutoLockDelay.AFTER_1M -> app.getString(R.string.al_auto_1m)
        AutoLockDelay.AFTER_5M -> app.getString(R.string.al_auto_5m)
        AutoLockDelay.OFF -> app.getString(R.string.al_auto_off)
    }
}
