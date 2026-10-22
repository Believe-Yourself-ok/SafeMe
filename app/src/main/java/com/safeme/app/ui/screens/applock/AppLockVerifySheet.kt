package com.safeme.app.ui.screens.applock

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safeme.app.R
import com.safeme.app.data.LockType
import com.safeme.app.data.appLockPrefs
import com.safeme.app.protect.AppLockManager
import com.safeme.app.protect.AppLockStateHolder
import com.safeme.app.protect.ProtectedAction
import com.safeme.app.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * COD-79 card 2 — the prompt shown before a protection is weakened.
 *
 * This is the *verification* half of "Feature Disabling Password Protection".
 * The other half, [AppLockUnlockOverlay], is the entry gate; the two share a
 * credential but are otherwise independent, which is the whole point of the
 * issue. Card 1 off still leaves this sheet prompting.
 *
 * ## Why a sheet and not the full-screen overlay
 *
 * Disabling a feature happens from inside a settings screen the user is already
 * looking at. Replacing it with an opaque gate would hide the switch they just
 * tapped and make the prompt feel like a hijack. A [ModalBottomSheet] keeps the
 * context visible and, importantly, is **dismissible**: back, swipe-down and
 * scrim-tap all route to [onCancelled] so a cancelled prompt leaves the feature
 * enabled, exactly as COD-79 requires.
 *
 * ## Shared rate limiter (intentional)
 *
 * This sheet verifies through [AppLockManager.verify], which is the same rate
 * limiter the entry gate uses — backoff from the 5th failure, 5-minute lockout
 * at the 10th. Brute-forcing the disable prompt therefore also locks the entry
 * gate. That is deliberate: two independent counters would let an attacker burn
 * 10 guesses on each surface, and the credential being protected is one secret.
 *
 * ## No biometric path
 *
 * Biometrics stay on the entry gate only. A fingerprint is a fine way to say
 * "this is the owner" but a weak way to authorise weakening a protection — the
 * system prompt is easy to trigger by accident, and `AppLockBiometrics.launch`
 * needs a `FragmentActivity` host that not every gated screen guarantees.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppLockVerifySheet(
    action: ProtectedAction,
    onVerified: () -> Unit,
    onCancelled: () -> Unit,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Read synchronously from the holder so the first frame already shows the
    // right input, then refreshed from DataStore below.
    var lockType by remember { mutableStateOf(AppLockStateHolder.lockType) }
    var credentialLength by remember { mutableIntStateOf(AppLockStateHolder.credentialLength) }

    var pin by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf<List<Int>>(emptyList()) }
    val passwordFocusRequester = remember { FocusRequester() }
    var failedPasswordAttempts by remember { mutableIntStateOf(0) }

    var error by remember { mutableStateOf("") }
    var shake by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var lockedOut by remember { mutableStateOf(false) }
    var lockoutLeftMs by remember { mutableLongStateOf(0L) }

    // Exactly-once settlement. The sheet can be dismissed by back, swipe or
    // scrim tap *after* a successful verify has started the hide animation, and
    // firing both callbacks would apply the disable and then report a cancel.
    var settled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val state = runCatching { context.appLockPrefs().first() }.getOrNull()
        if (state != null) {
            lockType = state.lockType
            credentialLength = state.credentialLength
        }
        lockedOut = AppLockManager.isLockedOut(context)
        lockoutLeftMs = AppLockManager.getLockoutRemainingMs(context)
    }

    // Lockout countdown ticker — same shape as the entry gate's.
    LaunchedEffect(lockedOut) {
        while (lockedOut) {
            lockoutLeftMs = AppLockManager.getLockoutRemainingMs(context)
            if (lockoutLeftMs <= 0L) {
                lockedOut = false
                break
            }
            delay(1000)
        }
    }

    /** Hide the sheet, then hand the outcome to the caller. Fires at most once. */
    fun settle(verified: Boolean) {
        if (settled) return
        settled = true
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (verified) onVerified() else onCancelled()
        }
    }

    fun submit(value: String) {
        if (checking || settled || value.isBlank() || lockedOut) return
        checking = true
        scope.launch {
            val ok = AppLockManager.verify(context, value)
            checking = false
            when {
                ok -> settle(true)
                AppLockManager.isLockedOut(context) -> {
                    lockedOut = true
                    error = ""
                    lockoutLeftMs = AppLockManager.getLockoutRemainingMs(context)
                }
                else -> {
                    error = context.getString(R.string.al_gate_wrong)
                    shake = true
                    // Reset after the ~0.4s keyframes so the next wrong entry
                    // re-triggers the shake (shakeEffect keys off the flip).
                    scope.launch {
                        delay(450)
                        shake = false
                    }
                    pin = ""
                    password = ""
                    pattern = emptyList()
                    // The field is disabled while verifying and drops focus;
                    // re-focus once recomposition re-enables it.
                    failedPasswordAttempts++
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { settle(false) },
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .shakeEffect(shake),
        ) {
            GrabBar()

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.brandSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AlLockIcon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = colors.brandDark,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.al_verify_title),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.al_verify_sub,
                    methodLabel(context, lockType),
                    stringResource(action.purposeRes()),
                ),
                fontSize = 13.sp,
                color = colors.ink2,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))

            val inputEnabled = !checking && !lockedOut && !settled
            when (lockType) {
                LockType.PIN -> {
                    PinDots(filled = pin.length)
                    PinKeypad(
                        enabled = inputEnabled,
                        onDigit = { d ->
                            if (pin.length < credentialLength) {
                                pin += d
                                if (pin.length == credentialLength) submit(pin)
                            }
                        },
                        onDelete = { pin = pin.dropLast(1) },
                    )
                }
                LockType.PASSWORD -> {
                    LaunchedEffect(Unit) { passwordFocusRequester.requestFocus() }
                    LaunchedEffect(failedPasswordAttempts) {
                        if (failedPasswordAttempts > 0) {
                            passwordFocusRequester.requestFocus()
                        }
                    }
                    PasswordField(
                        value = password,
                        onValueChange = { value ->
                            password = value
                            if (value.length >= credentialLength) submit(value)
                        },
                        placeholder = stringResource(R.string.al_gate_password_placeholder),
                        enabled = inputEnabled,
                        focusRequester = passwordFocusRequester,
                    )
                }
                LockType.PATTERN -> {
                    PatternGrid(
                        selected = pattern,
                        enabled = inputEnabled,
                        onDotTap = { index ->
                            if (index !in pattern) {
                                pattern = pattern + index
                                if (pattern.size >= credentialLength) {
                                    submit(pattern.joinToString("-"))
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.al_pattern_hint),
                        fontSize = 13.sp,
                        color = colors.ink2,
                        textAlign = TextAlign.Center,
                    )
                }
                // Unreachable in practice: the host only shows this sheet when
                // `disableProtectionActive` is true, which requires a stored
                // credential. Handled rather than assumed so that a future
                // caller cannot end up with a prompt that has no way to answer
                // it — Cancel still works.
                LockType.OFF -> Unit
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = if (lockedOut) {
                    context.getString(
                        R.string.al_gate_locked_out,
                        formatLockout(lockoutLeftMs),
                    )
                } else {
                    error
                },
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.danger,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().height(16.dp),
            )

            Spacer(Modifier.height(6.dp))
            GhostButton(
                text = stringResource(R.string.al_verify_cancel),
                onClick = { settle(false) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * What [action] is about to do, in the user's words.
 *
 * The `when` is exhaustive with **no `else`** on purpose: adding a
 * [ProtectedAction] without deciding how to describe it to the user is a
 * compile error rather than a silently generic prompt. The mapping lives here
 * and not on the enum so [ProtectedAction] stays free of Android resource
 * references and remains testable on the JVM.
 */
@StringRes
internal fun ProtectedAction.purposeRes(): Int = when (this) {
    ProtectedAction.MASTER_BLOCKING -> R.string.al_verify_purpose_master_blocking
    ProtectedAction.ANTI_TAMPER -> R.string.al_verify_purpose_anti_tamper
    ProtectedAction.ANTI_TAMPER_ADMIN_SYNC -> R.string.al_verify_purpose_admin_sync
    ProtectedAction.ACCESSIBILITY_PROTECTION -> R.string.al_verify_purpose_a11y
    ProtectedAction.SOCIAL_BLOCKING_MASTER -> R.string.al_verify_purpose_social_master
    ProtectedAction.SOCIAL_BLOCKING_APP -> R.string.al_verify_purpose_social_app
    ProtectedAction.SOCIAL_BLOCKING_TAB -> R.string.al_verify_purpose_social_tab
    ProtectedAction.SOCIAL_PRESET_WEAKEN -> R.string.al_verify_purpose_social_preset
    ProtectedAction.DNS_VPN -> R.string.al_verify_purpose_dns_vpn
    ProtectedAction.IMAGE_VIDEO_SEARCH -> R.string.al_verify_purpose_image_video
    ProtectedAction.SCHEDULE_ITEM -> R.string.al_verify_purpose_schedule_item
    ProtectedAction.SCHEDULE_EDITOR_OFF -> R.string.al_verify_purpose_schedule_editor
    ProtectedAction.SCHEDULE_DELETE -> R.string.al_verify_purpose_schedule_delete
    ProtectedAction.TITLE_RULE -> R.string.al_verify_purpose_title_rule
    ProtectedAction.TITLE_RULE_DELETE -> R.string.al_verify_purpose_title_rule_delete
    ProtectedAction.KEYWORD_DELETE -> R.string.al_verify_purpose_keyword_delete
    ProtectedAction.WEBSITE_DELETE -> R.string.al_verify_purpose_website_delete
    ProtectedAction.BLOCKLIST_RESET -> R.string.al_verify_purpose_blocklist_reset
    ProtectedAction.QUICK_ACTION_REMOVE -> R.string.al_verify_purpose_quick_action
    ProtectedAction.BLOCK_SCREEN_WHY -> R.string.al_verify_purpose_block_why
    ProtectedAction.DISABLE_PROTECTION_ITSELF -> R.string.al_verify_purpose_disable_protection
}
