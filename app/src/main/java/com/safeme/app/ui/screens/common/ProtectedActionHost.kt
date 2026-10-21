package com.safeme.app.ui.screens.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.safeme.app.ui.screens.applock.AppLockVerifySheet

/**
 * Renders the COD-79 verification prompt for a [ProtectedViewModel].
 *
 * One line per gated screen, placed anywhere in its content — `ModalBottomSheet`
 * opens its own window, so position in the composition does not affect where it
 * appears:
 *
 * ```
 * ProtectedActionHost(viewModel)
 * ```
 *
 * Deliberately takes the ViewModel rather than the three raw values
 * (`request`, `onVerified`, `onCancelled`). Passing the ViewModel makes it
 * impossible for a screen to wire `onVerified` to something other than the
 * action it just requested, which is the mistake that would let a prompt
 * authorise one change and apply another.
 *
 * `verifyRequest` is a `StateFlow`, so this recomposes to nothing the moment
 * [ProtectedViewModel.onVerified] or [ProtectedViewModel.onCancelled] clears
 * it — including across a configuration change, where the pending request
 * survives and the sheet correctly reappears.
 */
@Composable
fun ProtectedActionHost(viewModel: ProtectedViewModel) {
    val request by viewModel.verifyRequest.collectAsState()
    val action = request ?: return
    AppLockVerifySheet(
        action = action,
        onVerified = viewModel::onVerified,
        onCancelled = viewModel::onCancelled,
    )
}
