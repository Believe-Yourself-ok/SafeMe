package com.safeme.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The two inputs the COD-79 feature-disabling guard needs, as one small
 * snapshot that any ViewModel can collect.
 *
 * Split out from [AppLockPrefsState] so the 13 gated ViewModels do not each
 * hold a whole App Lock snapshot they do not use, and so the guard's inputs are
 * impossible to confuse with the entry gate's.
 *
 * [hasCredential] is included because card 2 verifies *against the stored
 * credential*: with nothing stored there is nothing to verify, and demanding a
 * password the user never set would lock them out of their own settings.
 */
data class DisableGate(
    val disableProtectionEnabled: Boolean,
    val hasCredential: Boolean,
)

/**
 * Hot flow of the COD-79 guard inputs. `distinctUntilChanged` keeps it cheap:
 * the App Lock store is written rarely, and screens that already collect it
 * would otherwise recompose on every unrelated edit.
 */
fun Context.disableGate(): Flow<DisableGate> =
    appLockPrefs()
        .map { DisableGate(it.disableProtectionEnabled, it.hasCredential) }
        .distinctUntilChanged()
