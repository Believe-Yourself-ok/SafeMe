package com.safeme.app.ui.screens.common

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safeme.app.data.DisableGate
import com.safeme.app.data.disableGate
import com.safeme.app.protect.AppLockStateHolder
import com.safeme.app.protect.DisableProtectionPolicy
import com.safeme.app.protect.ProtectedAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Base class for every ViewModel with a switch or delete action that weakens a
 * protection — COD-79 card 2.
 *
 * Subclassing this instead of `AndroidViewModel` is the only thing a screen has
 * to do to become gated; [ProtectedActionHost] renders the prompt. That is why
 * this exists rather than 13 copies of the same collector, pending-action field
 * and request flow.
 *
 * ## How a call site uses it
 *
 * ```
 * fun toggleMasterProtection() {
 *     if (enabled) {
 *         // Turning OFF — may need the credential first.
 *         guardDisable(ProtectedAction.MASTER_BLOCKING) { applyMasterOff() }
 *     } else {
 *         // Turning ON — never gated, never prompts.
 *         applyMasterOn()
 *     }
 * }
 * ```
 *
 * The lambda runs immediately when no prompt is needed, and otherwise runs only
 * after the credential verifies. **Nothing is written before verification**, so
 * a cancelled or failed prompt leaves the feature exactly as it was — the
 * cancel semantics COD-79 requires. This is also why the optimistic UI flips in
 * the existing ViewModels had to move *inside* the lambda: flipping first and
 * prompting second would strand the switch looking off after a cancel.
 *
 * ## Enabling is ungated by construction
 *
 * There is no `guardEnable`, and [ProtectedAction] has no enable-direction
 * value. Turning a protection on can only ever strengthen it, so COD-79's "must
 * not require verification when enabling" is enforced by the shape of the API
 * rather than by each call site remembering the rule.
 *
 * ## Fail-open, and why the window is effectively closed
 *
 * [gate] starts from [AppLockStateHolder], which SafeMeApp feeds at process
 * start, so by the time any screen's ViewModel exists it already holds the real
 * values. The DataStore collector then keeps it fresh. If neither has produced
 * a credential yet, `hasCredential` is false and no prompt is shown — the
 * fail-open direction docs/11 §3 mandates, and the safe one here: prompting
 * with no stored credential would render an input-less sheet the user could
 * never satisfy.
 */
abstract class ProtectedViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * The action awaiting verification, or null. A `StateFlow` rather than a
     * `SharedFlow` on purpose: the prompt must survive a configuration change
     * (the ViewModel outlives the composition, and a `replay = 0` SharedFlow
     * would drop the request if nothing was collecting yet), and clearing it is
     * what dismisses the sheet.
     */
    private val _verifyRequest = MutableStateFlow<ProtectedAction?>(null)
    val verifyRequest: StateFlow<ProtectedAction?> = _verifyRequest.asStateFlow()

    @Volatile
    private var gate: DisableGate = DisableGate(
        disableProtectionEnabled = AppLockStateHolder.state.disableProtectionEnabled,
        hasCredential = AppLockStateHolder.hasCredential,
    )

    /**
     * What to run once the credential verifies. Held as a lambda rather than an
     * action id plus an abstract `when`, so each call site states its own effect
     * once instead of every subclass maintaining a mapping that can drift.
     *
     * Main-thread only: set from a click handler, invoked from the sheet's
     * completion callback.
     */
    private var granted: (() -> Unit)? = null

    init {
        // `appLockPrefs()` already ends in `.catch { emit(emptyPreferences()) }`,
        // so a corrupt store degrades to defaults instead of cancelling this
        // collector. No extra try/catch needed — and catching here would swallow
        // the scope's normal CancellationException.
        viewModelScope.launch {
            getApplication<Application>().disableGate().collect { gate = it }
        }
    }

    /**
     * Run [onGranted] now if card 2 does not cover [action]; otherwise ask the
     * user to verify first and run it only on success.
     *
     * Returns nothing on purpose — the caller must not branch on whether a
     * prompt appeared, because either way [onGranted] is the only path to the
     * write. Branching on it is how a site ends up applying the disable twice
     * or not at all.
     */
    protected fun guardDisable(action: ProtectedAction, onGranted: () -> Unit) {
        val current = gate
        if (!DisableProtectionPolicy.passwordRequired(
                disableProtectionEnabled = current.disableProtectionEnabled,
                hasCredential = current.hasCredential,
                action = action,
            )
        ) {
            onGranted()
            return
        }
        granted = onGranted
        _verifyRequest.value = action
    }

    /** Credential accepted — apply the guarded change, then dismiss. */
    fun onVerified() {
        val pending = granted
        granted = null
        _verifyRequest.value = null
        pending?.invoke()
    }

    /**
     * Cancelled, dismissed, or the credential was wrong enough times to give
     * up. Nothing is written: the feature stays enabled.
     */
    fun onCancelled() {
        granted = null
        _verifyRequest.value = null
    }
}
