package com.safeme.app.ui.screens.applock

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.safeme.app.R
import com.safeme.app.data.LockType
import com.safeme.app.data.appLockPrefs
import com.safeme.app.protect.AppLockBiometrics
import com.safeme.app.protect.AppLockManager
import com.safeme.app.protect.AppLockStateHolder
import com.safeme.app.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Renders the unlock gate whenever the controller reports locked. */
@Composable
fun AppLockGateHost() {
    val locked by AppLockGateController.locked.collectAsState()
    if (locked) {
        AppLockUnlockOverlay(onUnlocked = { AppLockGateController.unlock() })
    }
}

/**
 * The prototype's `lockov` overlay: opaque full-screen background + centered
 * card with the method-specific input, plus the biometric and forgot-credential
 * options. Input state resets on every engagement and whenever the activity
 * resumes (a backgrounded-while-locked app must not show stale input — the
 * reference's LOCKSESSION bug class).
 */
@Composable
fun AppLockUnlockOverlay(onUnlocked: () -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var lockType by remember { mutableStateOf(AppLockStateHolder.lockType) }
    var credentialLength by remember { mutableStateOf(AppLockStateHolder.credentialLength) }
    var biometricEnabled by remember { mutableStateOf(AppLockStateHolder.biometricEnabled) }
    var forgotPasswordDisabled by remember { mutableStateOf(AppLockStateHolder.forgotPasswordDisabled) }

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

    // Load the freshest lock config when the gate appears.
    LaunchedEffect(Unit) {
        val state = runCatching { context.appLockPrefs().first() }.getOrNull()
        if (state != null) {
            lockType = state.lockType
            credentialLength = state.credentialLength
            biometricEnabled = state.biometricEnabled
            forgotPasswordDisabled = state.forgotPasswordDisabled
        }
        lockedOut = AppLockManager.isLockedOut(context)
        lockoutLeftMs = AppLockManager.getLockoutRemainingMs(context)
    }

    // Fresh engagement: clear any stale input whenever the activity resumes.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pin = ""
                password = ""
                pattern = emptyList()
                error = ""
                lockedOut = AppLockManager.isLockedOut(context)
                lockoutLeftMs = AppLockManager.getLockoutRemainingMs(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Lockout countdown ticker.
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

    // Biometric auto-launch once per engagement.
    var biometricLaunched by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (biometricEnabled && AppLockBiometrics.isAvailable(context) && !biometricLaunched) {
            biometricLaunched = true
            AppLockBiometrics.launch(
                context = context,
                title = context.getString(R.string.al_gate_bio_title),
                subtitle = context.getString(R.string.al_gate_sub, methodLabel(context, lockType)),
                negativeText = context.getString(R.string.al_gate_bio_negative),
                onSuccess = onUnlocked,
            )
        }
    }

    fun submit(value: String) {
        if (checking || value.isBlank() || lockedOut) return
        checking = true
        scope.launch {
            val ok = AppLockManager.verify(context, value)
            checking = false
            if (ok) {
                onUnlocked()
            } else if (AppLockManager.isLockedOut(context)) {
                lockedOut = true
                error = ""
                lockoutLeftMs = AppLockManager.getLockoutRemainingMs(context)
            } else {
                error = context.getString(R.string.al_gate_wrong)
                shake = true
                pin = ""
                password = ""
                pattern = emptyList()
                // The field was disabled during verify and dropped focus;
                // re-focus (via LaunchedEffect, after recomposition re-enables
                // it) so the next keystrokes land in it.
                failedPasswordAttempts++
            }
        }
    }

    Box(
        // Fully opaque background — the gate must completely cover the app
        // behind it (the prototype's translucent scrim leaked the main UI).
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .shadow(16.dp, RoundedCornerShape(26.dp))
                .clip(RoundedCornerShape(26.dp))
                .background(colors.surface)
                .padding(24.dp)
                .shakeEffect(shake),
        ) {
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
                text = stringResource(R.string.al_gate_title),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.al_gate_sub, methodLabel(context, lockType)),
                fontSize = 13.sp,
                color = colors.ink2,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))

            val inputEnabled = !checking && !lockedOut
            when (lockType) {
                LockType.PIN -> {
                    PinDots(
                        filled = pin.length,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    PinKeypad(
                        enabled = inputEnabled,
                        onDigit = { d ->
                            if (pin.length < credentialLength) {
                                pin += d
                                if (pin.length == credentialLength) submit(pin)
                            }
                        },
                        onDelete = { pin = pin.dropLast(1) },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                LockType.PASSWORD -> {
                    LaunchedEffect(Unit) { passwordFocusRequester.requestFocus() }
                    LaunchedEffect(failedPasswordAttempts) {
                        if (failedPasswordAttempts > 0) {
                            passwordFocusRequester.requestFocus()
                        }
                    }
                    Spacer(Modifier.height(10.dp))
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
                    Spacer(Modifier.height(10.dp))
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
                    )
                }
                LockType.OFF -> Unit
            }

            // Biometric option — always displayed so the affordance is visible
            // on the gate. It is active (launches the system prompt) only when
            // Touch ID is enabled AND the device can authenticate; otherwise it
            // is shown dimmed and explains why on tap.
            val bioAvailable = remember(context) { AppLockBiometrics.isAvailable(context) }
            val bioActive = biometricEnabled && bioAvailable
            Spacer(Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable {
                        when {
                            bioActive -> AppLockBiometrics.launch(
                                context = context,
                                title = context.getString(R.string.al_gate_bio_title),
                                subtitle = context.getString(
                                    R.string.al_gate_sub, methodLabel(context, lockType)
                                ),
                                negativeText = context.getString(R.string.al_gate_bio_negative),
                                onSuccess = onUnlocked,
                            )
                            !bioAvailable -> Toast.makeText(
                                context,
                                context.getString(R.string.al_toast_bio_unavailable),
                                Toast.LENGTH_SHORT,
                            ).show()
                            else -> Toast.makeText(
                                context,
                                context.getString(R.string.al_gate_bio_off),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = AlFingerprintIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (bioActive) colors.brandDark else colors.ink3,
                )
                Text(
                    text = stringResource(R.string.al_gate_use_biometric),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (bioActive) colors.brandDark else colors.ink3,
                )
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

            // Forgot credential — the recovery option (hidden when the user
            // disables it in App Lock settings).
            if (!forgotPasswordDisabled && lockType != LockType.OFF) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        if (lockType == LockType.PIN) R.string.al_gate_forgot_pin
                        else R.string.al_gate_forgot_password
                    ),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink2,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable {
                            val subject = if (lockType == LockType.PIN) "Forgot PIN" else "Forgot Password"
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse(
                                    "mailto:support@safeme.app?subject=${Uri.encode(subject)}"
                                )
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * Human name of the lock method, for prompts that say "Enter your PIN…".
 * `internal` (was `private`) so [AppLockVerifySheet] words itself exactly like
 * the unlock gate instead of shipping a second, drifting copy.
 */
internal fun methodLabel(context: android.content.Context, type: LockType): String = when (type) {
    LockType.PIN -> context.getString(R.string.al_method_pin)
    LockType.PASSWORD -> context.getString(R.string.al_method_password)
    LockType.PATTERN -> context.getString(R.string.al_method_pattern)
    LockType.OFF -> ""
}

/**
 * `"4m 12s"` / `"38s"` for the brute-force lockout countdown.
 * `internal` (was `private`) because [AppLockVerifySheet] shares
 * [AppLockManager]'s rate limiter and therefore has to render the same
 * countdown — see the note on that shared limiter in the sheet's KDoc.
 */
internal fun formatLockout(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min > 0) "${min}m ${sec}s" else "${sec}s"
}
