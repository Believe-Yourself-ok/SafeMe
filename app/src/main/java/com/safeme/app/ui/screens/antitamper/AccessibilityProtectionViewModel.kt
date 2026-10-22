package com.safeme.app.ui.screens.antitamper

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.safeme.app.R
import com.safeme.app.data.A11yProtectionPrefsState
import com.safeme.app.data.a11yProtectionPrefs
import com.safeme.app.data.setA11yProtectionEnabled
import com.safeme.app.protect.A11yProtectionGuard
import com.safeme.app.protect.A11yProtectionStateHolder
import com.safeme.app.protect.A11yProtectionUtils
import com.safeme.app.protect.ProtectedAction
import com.safeme.app.ui.screens.common.ProtectedViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class A11yProtectionUiState(
    val protectionEnabled: Boolean = false,
    val writeSecureGranted: Boolean = false,
)

/**
 * State for the Accessibility Protection screen: the master toggle and the
 * WRITE_SECURE_SETTINGS grant state.
 */
class AccessibilityProtectionViewModel(application: Application) : ProtectedViewModel(application) {

    private val app = getApplication<Application>()

    private val _uiState = MutableStateFlow(A11yProtectionUiState())
    val uiState: StateFlow<A11yProtectionUiState> = _uiState.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    init {
        viewModelScope.launch {
            try {
                app.a11yProtectionPrefs().collect { state ->
                    A11yProtectionStateHolder.protectionEnabled = state.protectionEnabled
                    A11yProtectionStateHolder.protectedComponents = state.protectedComponents
                    refresh(state)
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val state = runCatching { app.a11yProtectionPrefs().first() }
                .getOrDefault(A11yProtectionPrefsState())
            refresh(state)
        }
    }

    private fun refresh(state: A11yProtectionPrefsState) {
        _uiState.value = A11yProtectionUiState(
            protectionEnabled = state.protectionEnabled,
            writeSecureGranted = A11yProtectionUtils.isWriteSecureSettingsGranted(app),
        )
    }

    /**
     * Master on/off toggle — the feature's control.
     *
     * COD-79: the ON direction runs straight through and also starts the guard
     * watching. The OFF direction is gated, because `stopWatching()` is what
     * actually disarms the protection — prompting only after that would leave
     * the guard down while the user was still deciding.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            applyEnabled(true)
        } else {
            guardDisable(ProtectedAction.ACCESSIBILITY_PROTECTION) { applyEnabled(false) }
        }
    }

    private fun applyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                app.setA11yProtectionEnabled(enabled)
                A11yProtectionStateHolder.protectionEnabled = enabled
                if (enabled) {
                    A11yProtectionUtils.selfHealAllAsync(app)
                    A11yProtectionGuard.getInstance().ensureWatching(app)
                } else {
                    A11yProtectionGuard.getInstance().stopWatching()
                }
            } catch (t: Throwable) {
                _toasts.tryEmit(app.getString(R.string.kw_toast_error))
            }
        }
    }

    fun recheckPermission() {
        viewModelScope.launch {
            val granted = A11yProtectionUtils.isWriteSecureSettingsGranted(app)
            _uiState.value = _uiState.value.copy(writeSecureGranted = granted)
            if (granted) {
                A11yProtectionUtils.selfHealAllAsync(app)
                _toasts.tryEmit(app.getString(R.string.ap_toast_permission_granted))
            } else {
                _toasts.tryEmit(app.getString(R.string.ap_toast_permission_missing))
            }
        }
    }
}
