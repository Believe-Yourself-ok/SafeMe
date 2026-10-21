package com.safeme.app.ui.screens.applock

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safeme.app.R
import com.safeme.app.data.AutoLockDelay
import com.safeme.app.data.LockType
import com.safeme.app.ui.components.ToastHost
import com.safeme.app.ui.screens.common.ProtectedActionHost
import com.safeme.app.ui.screens.permissions.ChevronIcon
import com.safeme.app.ui.theme.LocalAppColors

private val PillGreen = Color(0xFF7CE0B3)
private val PillRed = Color(0xFFFFB4AB)

@Composable
fun AppLockScreen(
    onBack: () -> Unit,
    viewModel: AppLockViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalAppColors.current

    var showSetup by remember { mutableStateOf(false) }
    var showAutoLock by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Header(title = stringResource(R.string.al_title), onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                HeroCard(
                    hasCredential = state.hasCredential,
                    entryLockActive = state.entryLockActive,
                    lockType = state.lockType,
                )
                if (state.hasCredential) {
                    Spacer(Modifier.height(14.dp))
                    LockActions(
                        // "Lock now" is a no-op unless the entry gate is armed
                        // (AppLockGateController.lockNow checks it), so showing
                        // it with card 1 off would be a button that does nothing.
                        showLockNow = state.entryLockActive,
                        onLockNow = viewModel::lockNow,
                        onChangeLock = { showSetup = true },
                    )
                } else {
                    Spacer(Modifier.height(14.dp))
                    SetupCta(onClick = { showSetup = true })
                }

                // COD-79: the two protections, as separate independent switches.
                Spacer(Modifier.height(20.dp))
                SectionLabel(R.string.al_protection_label)
                ProtectionToggles(
                    hasCredential = state.hasCredential,
                    entryLockEnabled = state.entryLockEnabled,
                    disableProtectionEnabled = state.disableProtectionEnabled,
                    onEntryLock = viewModel::setEntryLockEnabled,
                    onDisableProtection = viewModel::setDisableProtectionEnabled,
                )

                Spacer(Modifier.height(20.dp))
                SectionLabel(R.string.al_settings_label)
                SettingsList(
                    // All three of these configure the entry gate, so they are
                    // live only while it is armed. A stored credential with
                    // card 1 off dims them exactly as "no lock at all" used to.
                    enabled = state.entryLockActive,
                    autoLock = state.autoLock,
                    biometricEnabled = state.biometricEnabled,
                    forgotDisabled = state.forgotPasswordDisabled,
                    onAutoLock = { showAutoLock = true },
                    onBiometric = viewModel::setBiometricEnabled,
                    onForgot = viewModel::setForgotDisabled,
                )

                // Deleting the credential outright is only offered once neither
                // protection is using it, so it cannot undermine either one.
                if (state.credentialIdle) {
                    Spacer(Modifier.height(14.dp))
                    RemoveLockButton(onClick = { showRemoveDialog = true })
                }
                Spacer(Modifier.height(12.dp))
                Note()
                Spacer(Modifier.height(16.dp))
            }
        }

        ToastHost(
            flow = viewModel.toasts,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        )
    }

    if (showSetup) {
        AppLockSetupSheet(
            initialMethod = if (state.hasCredential) state.lockType else LockType.PIN,
            onDismiss = { showSetup = false },
            onSave = { type, input ->
                showSetup = false
                viewModel.saveLock(type, input)
            },
            onError = viewModel::showToast,
        )
    }

    if (showAutoLock) {
        AutoLockSheet(
            selected = state.autoLock,
            onSelect = { delay ->
                showAutoLock = false
                viewModel.setAutoLock(delay)
            },
            onDismiss = { showAutoLock = false },
        )
    }

    // COD-79 card 2: prompts before this screen's own protected action, which is
    // switching "Feature disabling password" off.
    ProtectedActionHost(viewModel)

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            containerColor = colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.al_remove_lock_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.danger,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.al_remove_lock_body),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = colors.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveDialog = false
                    viewModel.removeLock()
                }) {
                    Text(
                        text = stringResource(R.string.al_remove_lock_confirm),
                        color = colors.danger,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text(
                        text = stringResource(R.string.al_remove_lock_cancel),
                        color = colors.ink2,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
        )
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 16.dp),
    ) {
        Box(
            modifier = Modifier
                .offset(x = (-8).dp)
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .border(1.dp, colors.line, RoundedCornerShape(14.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = ChevronIcon,
                contentDescription = stringResource(R.string.perm_back),
                modifier = Modifier.size(20.dp),
                tint = colors.ink,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            color = colors.ink,
        )
    }
}

/** The gradient hero card (prototype `.hero` + `lockHero`). */
@Composable
private fun HeroCard(
    hasCredential: Boolean,
    entryLockActive: Boolean,
    lockType: LockType,
) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(colors.brandDark, colors.brand, Color(0xFFE8A07E)),
                )
            )
            .padding(22.dp),
    ) {
        // Decorative rings (prototype `.rings` / `.rings.r2`).
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 30.dp, y = (-30).dp)
                .size(150.dp)
                .border(2.dp, Color.White.copy(alpha = 0.18f), CircleShape),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 50.dp, y = 60.dp)
                .size(200.dp)
                .border(2.dp, Color.White.copy(alpha = 0.18f), CircleShape),
        )
        Column {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AlLockIcon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = Color.White,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.al_hero_tag).uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(6.dp))
            // Three states, not two: COD-79 separates "a lock is stored" from
            // "the app asks for it on entry". The middle state is the one the
            // issue is about — the app opens freely, but the credential is still
            // there guarding feature changes.
            val heroTitle = when {
                entryLockActive -> stringResource(R.string.al_hero_on_title, methodLabel(lockType))
                hasCredential -> stringResource(R.string.al_hero_stored_title)
                else -> stringResource(R.string.al_hero_off_title)
            }
            val heroSub = when {
                entryLockActive -> stringResource(R.string.al_hero_on_sub)
                hasCredential -> stringResource(R.string.al_hero_stored_sub)
                else -> stringResource(R.string.al_hero_off_sub)
            }
            Text(
                text = heroTitle,
                fontSize = 27.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                text = heroSub,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (entryLockActive) PillGreen else PillRed),
                        )
                        Text(
                            text = stringResource(
                                if (entryLockActive) R.string.al_hero_pill_on
                                else R.string.al_hero_pill_off,
                            ),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Lock-now / change-lock actions, placed below the hero (prototype's
 * `.lock-on-only` row moved out of the hero card).
 */
@Composable
private fun LockActions(
    showLockNow: Boolean,
    onLockNow: () -> Unit,
    onChangeLock: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showLockNow) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(colors.brandDark, colors.brand)))
                    .clickable(onClick = onLockNow),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.al_lock_now),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .border(1.dp, colors.line, CircleShape)
                .clickable(onClick = onChangeLock),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.al_change_lock),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
            )
        }
    }
}

/** Gradient "Set up App Lock" CTA (prototype `.setup-lock-cta`). */
@Composable
private fun SetupCta(onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = colors.brand.copy(alpha = 0.32f),
                spotColor = colors.brand.copy(alpha = 0.32f),
            )
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(colors.brandDark, colors.brand, Color(0xFFE8A07E)),
                )
            )
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AlLockIcon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color.White,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.al_setup_cta_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 19.sp,
                color = Color.White,
            )
            Text(
                text = stringResource(R.string.al_setup_cta_sub),
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Icon(
            imageVector = AlChevronRightIcon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = Color.White.copy(alpha = 0.95f),
        )
    }
}

/** Small caps label above each settings card. */
@Composable
private fun SectionLabel(@StringRes text: Int) {
    val colors = LocalAppColors.current
    Text(
        text = stringResource(text),
        fontSize = 13.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.2.sp,
        color = colors.ink,
        modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
    )
}

/**
 * COD-79's two protections, as separate switches in one card.
 *
 * Built from the same [SettingRow] + [MasterSwitch] pair the "Lock settings"
 * card below uses, so the two cards are visually identical and neither reads as
 * subordinate — which is the point: they are independent settings, and the
 * issue asks for exactly that.
 *
 * Both switches are inert until a credential exists. Arming either without one
 * would be a promise the app cannot keep: card 1 would raise the unlock
 * overlay's `LockType.OFF` branch, which renders no input at all, and card 2
 * would prompt for a password the user never set.
 *
 * `checked` is the *active* state (flag AND credential), never the raw flag, so
 * a switch can't read "on" while doing nothing.
 */
@Composable
private fun ProtectionToggles(
    hasCredential: Boolean,
    entryLockEnabled: Boolean,
    disableProtectionEnabled: Boolean,
    onEntryLock: (Boolean) -> Unit,
    onDisableProtection: (Boolean) -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(20.dp)),
    ) {
        SettingRow(
            icon = AlShieldIcon,
            iconBackground = colors.iconGreenBg,
            iconTint = colors.success,
            title = stringResource(R.string.al_protect_entry),
            sub = stringResource(
                when {
                    !hasCredential -> R.string.al_protect_needs_lock
                    entryLockEnabled -> R.string.al_protect_entry_sub
                    else -> R.string.al_protect_entry_sub_off
                },
            ),
            enabled = hasCredential,
            trailing = {
                MasterSwitch(
                    checked = entryLockEnabled && hasCredential,
                    enabled = hasCredential,
                    onToggle = { onEntryLock(!entryLockEnabled) },
                )
            },
            onClick = null,
            modifier = Modifier.padding(top = 3.dp),
        )
        HorizontalDivider(color = colors.line)
        SettingRow(
            icon = AlLockIcon,
            iconBackground = colors.iconAmberBg,
            iconTint = colors.warning,
            title = stringResource(R.string.al_protect_disable),
            sub = stringResource(
                when {
                    !hasCredential -> R.string.al_protect_needs_lock
                    disableProtectionEnabled -> R.string.al_protect_disable_sub
                    else -> R.string.al_protect_disable_sub_off
                },
            ),
            enabled = hasCredential,
            trailing = {
                MasterSwitch(
                    checked = disableProtectionEnabled && hasCredential,
                    enabled = hasCredential,
                    onToggle = { onDisableProtection(!disableProtectionEnabled) },
                )
            },
            onClick = null,
        )
    }
}

@Composable
private fun SettingsList(
    enabled: Boolean,
    autoLock: AutoLockDelay,
    biometricEnabled: Boolean,
    forgotDisabled: Boolean,
    onAutoLock: () -> Unit,
    onBiometric: (Boolean) -> Unit,
    onForgot: (Boolean) -> Unit,
) {
    val colors = LocalAppColors.current
    val rowAlpha = if (enabled) 1f else 0.45f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(20.dp)),
    ) {
        SettingRow(
            icon = AlClockIcon,
            iconBackground = colors.iconDarkBg,
            iconTint = colors.iconDarkFg,
            title = stringResource(R.string.al_auto_lock),
            sub = autoLockValue(autoLock),
            enabled = enabled,
            trailing = { Chevron(tint = colors.ink3) },
            onClick = onAutoLock,
            modifier = Modifier.padding(top = 3.dp),
        )
        HorizontalDivider(color = colors.line)
        SettingRow(
            icon = AlLockIcon,
            iconBackground = colors.iconGreenBg,
            iconTint = colors.success,
            title = stringResource(R.string.al_touch_id),
            sub = stringResource(R.string.al_touch_id_sub),
            enabled = enabled,
            trailing = {
                MasterSwitch(
                    checked = enabled && biometricEnabled,
                    enabled = enabled,
                    onToggle = { onBiometric(!biometricEnabled) },
                )
            },
            onClick = null,
        )
        HorizontalDivider(color = colors.line)
        SettingRow(
            icon = AlLockIcon,
            iconBackground = colors.iconAmberBg,
            iconTint = colors.warning,
            title = stringResource(R.string.al_forgot),
            sub = stringResource(R.string.al_forgot_sub),
            enabled = enabled,
            trailing = {
                MasterSwitch(
                    checked = enabled && forgotDisabled,
                    enabled = enabled,
                    onToggle = { onForgot(!forgotDisabled) },
                )
            },
            onClick = null,
        )
    }
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBackground: Color,
    iconTint: Color,
    title: String,
    sub: String,
    enabled: Boolean,
    trailing: @Composable () -> Unit,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val alpha = if (enabled) 1f else 0.45f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(iconBackground.copy(alpha = alpha)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = iconTint,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
                maxLines = 1,
            )
            Text(
                text = sub,
                fontSize = 12.sp,
                color = colors.ink2,
                maxLines = 1,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        trailing()
    }
}

@Composable
private fun Chevron(tint: Color) {
    Icon(
        imageVector = AlChevronRightIcon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = tint,
    )
}

/**
 * COD-79: delete the stored credential outright.
 *
 * Replaces the old red "Disable App Lock" button, which conflated "stop asking
 * on entry" with "destroy the credential" — the exact confusion this issue
 * splits apart. Rendered only when neither protection is using the credential
 * (`AppLockUiState.credentialIdle`), so it cannot undermine either one and needs
 * no verification prompt of its own.
 */
@Composable
private fun RemoveLockButton(onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .shadow(
                elevation = 8.dp,
                shape = CircleShape,
                ambientColor = colors.danger.copy(alpha = 0.35f),
                spotColor = colors.danger.copy(alpha = 0.35f),
            )
            .clip(CircleShape)
            .background(colors.danger)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.al_remove_lock),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

@Composable
private fun Note() {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = AlShieldIcon,
            contentDescription = null,
            modifier = Modifier.size(16.dp).padding(top = 1.dp),
            tint = colors.brand,
        )
        Text(
            text = stringResource(R.string.al_note),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colors.ink2,
        )
    }
}

/** The app's custom animated switch, with a disabled state. */
@Composable
fun MasterSwitch(
    checked: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalAppColors.current
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> colors.swOff.copy(alpha = 0.5f)
            checked -> colors.brand
            else -> colors.swOff
        },
        animationSpec = tween(200),
        label = "switchBg",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 21.dp else 0.dp,
        animationSpec = tween(200),
        label = "switchThumb",
    )
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 31.dp)
            .clip(CircleShape)
            .background(bg)
            .semantics { role = Role.Switch }
            .clickable(enabled = enabled, role = Role.Switch, onClick = onToggle),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = 3.dp + thumbOffset)
                .size(25.dp)
                .shadow(2.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

@Composable
private fun methodLabel(type: LockType): String = when (type) {
    LockType.PIN -> stringResource(R.string.al_method_pin)
    LockType.PASSWORD -> stringResource(R.string.al_method_password)
    LockType.PATTERN -> stringResource(R.string.al_method_pattern)
    LockType.OFF -> ""
}

@Composable
private fun autoLockValue(delay: AutoLockDelay): String = when (delay) {
    AutoLockDelay.IMMEDIATELY -> stringResource(R.string.al_auto_val_immediately)
    AutoLockDelay.AFTER_15S -> stringResource(R.string.al_auto_val_15s)
    AutoLockDelay.AFTER_30S -> stringResource(R.string.al_auto_val_30s)
    AutoLockDelay.AFTER_1M -> stringResource(R.string.al_auto_val_1m)
    AutoLockDelay.AFTER_5M -> stringResource(R.string.al_auto_val_5m)
    AutoLockDelay.OFF -> stringResource(R.string.al_auto_val_off)
}
