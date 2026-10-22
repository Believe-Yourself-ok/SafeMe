package com.safeme.app.ui.screens.antitamper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safeme.app.R
import com.safeme.app.ui.components.ToastHost
import com.safeme.app.ui.screens.blockscreen.InfoIcon
import com.safeme.app.ui.screens.common.ProtectedActionHost
import com.safeme.app.ui.screens.permissions.ChevronIcon
import com.safeme.app.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Accessibility Protection — the prototype's "self-protect" screen.
 *
 * The master on/off toggle is the feature's control: when ON the guard
 * watches SafeMe's own Accessibility Service and every selected third-party
 * service, restoring them (or notifying) when they are disabled or stopped.
 * The screen shows the live WRITE_SECURE_SETTINGS grant state (what the
 * auto-restore path actually needs) and the list of protected services with
 * their live enabled state.
 */
@Composable
fun AccessibilityProtectionScreen(
    onBack: () -> Unit,
    onOpenServicePicker: () -> Unit = {},
    viewModel: AccessibilityProtectionViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalAppColors.current

    // Live state refreshes whenever the screen regains focus, so a toggle
    // made in system settings is reflected immediately.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Header(
                title = stringResource(R.string.ap_title),
                subtitle = stringResource(R.string.ap_subtitle),
                onBack = onBack,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                MasterCard(
                    enabled = state.protectionEnabled,
                    onToggle = { viewModel.setEnabled(!state.protectionEnabled) },
                )
                Spacer(Modifier.height(12.dp))
                PermissionCard(
                    granted = state.writeSecureGranted,
                    onRecheck = { viewModel.recheckPermission() },
                )
                Spacer(Modifier.height(16.dp))
                ProtectAnotherServiceCard(onClick = onOpenServicePicker)
                Spacer(Modifier.height(12.dp))
                Note()
                Spacer(Modifier.height(16.dp))
            }
        }
        ToastHost(flow = viewModel.toasts)
    }

    // COD-79: prompts before the master switch stops the guard watching.
    ProtectedActionHost(viewModel)
}

@Composable
private fun Header(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 16.dp),
    ) {
        Box(
            modifier = Modifier
                // Back button nudged 8px left, matching the reference header.
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
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            fontSize = 12.sp,
            lineHeight = 20.sp,
            color = colors.ink2,
        )
    }
}

/** Master on/off toggle — the feature's control. */
@Composable
private fun MasterCard(
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .cardShape(radius = 20.dp)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ap_master_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                )
                Text(
                    text = stringResource(R.string.ap_master_sub),
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = colors.ink2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            MasterSwitch(checked = enabled, onToggle = onToggle)
        }
    }
}

/** WRITE_SECURE_SETTINGS grant state + one-time ADB setup instructions. */
@Composable
private fun PermissionCard(
    granted: Boolean,
    onRecheck: () -> Unit,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val command = stringResource(R.string.ap_perm_command, "com.safeme.app")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .cardShape(radius = 20.dp)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ap_perm_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                )
                Text(
                    text = stringResource(
                        if (granted) R.string.ap_perm_granted else R.string.ap_perm_missing,
                    ),
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = colors.ink2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (granted) colors.successBg else colors.warningBg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (granted) R.string.ap_perm_pill_granted else R.string.ap_perm_pill_missing,
                    ),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    color = if (granted) colors.success else colors.warning,
                )
            }
        }
        if (!granted) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.brandSoft)
                    .clickable { expanded = !expanded }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.ap_perm_hide else R.string.ap_perm_show,
                    ),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.brandDark,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface.copy(alpha = 0.6f))
                        .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ap_perm_step_1),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.ink2,
                    )
                    Text(
                        text = stringResource(R.string.ap_perm_step_2),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.ink2,
                    )
                    Text(
                        text = command,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.brandDark,
                    )
                    // Copy the complete ADB command to the clipboard, with a
                    // brief "Copied" confirmation (resets after 2 s).
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (copied) colors.successBg else colors.brandSoft)
                            .clickable {
                                copyToClipboard(context, command)
                                copied = true
                                scope.launch {
                                    delay(2000)
                                    copied = false
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (copied) R.string.ap_perm_copied else R.string.ap_perm_copy,
                            ),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (copied) colors.success else colors.brandDark,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.brandSoft)
                            .clickable(onClick = onRecheck)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.ap_perm_recheck),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.brandDark,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-width "Protect Another App's Accessibility Service" card. Opens the
 * eligible-apps list so the user can add third-party services to protection.
 * Mirrors the Anti-Tamper screen's protect button design.
 */
@Composable
private fun ProtectAnotherServiceCard(onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.brandSoft)
            .border(1.dp, colors.brand.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .shadow(
                    elevation = 6.dp,
                    shape = RoundedCornerShape(13.dp),
                    ambientColor = colors.brand.copy(alpha = 0.32f),
                    spotColor = colors.brand.copy(alpha = 0.32f),
                )
                .clip(RoundedCornerShape(13.dp))
                .background(colors.brand),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AtShieldPlusIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.White,
            )
        }
        Text(
            text = stringResource(R.string.ap_add_service),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 20.sp,
            color = colors.brandDark,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = AtChevronRightIcon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colors.brand,
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
            imageVector = InfoIcon,
            contentDescription = null,
            modifier = Modifier.size(16.dp).padding(top = 1.dp),
            tint = colors.brand,
        )
        Text(
            text = stringResource(R.string.ap_note),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colors.ink2,
        )
    }
}

/** Copies [text] to the system clipboard; swallows any failure (never crash the screen). */
private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("SafeMe ADB command", text))
    }
}

/** Replica of the Blocking screen's animated switch. */
@Composable
private fun MasterSwitch(checked: Boolean, onToggle: () -> Unit) {
    val colors = LocalAppColors.current
    val bg by animateColorAsState(
        targetValue = if (checked) colors.brand else colors.swOff,
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
            .clickable(role = Role.Switch, onClick = onToggle),
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
private fun Modifier.cardShape(radius: Dp): Modifier {
    val colors = LocalAppColors.current
    return this
        .shadow(
            elevation = 1.dp,
            shape = RoundedCornerShape(radius),
            ambientColor = colors.ink.copy(alpha = 0.02f),
            spotColor = colors.ink.copy(alpha = 0.02f),
        )
        .clip(RoundedCornerShape(radius))
        .background(colors.surface)
        .border(1.dp, colors.line, RoundedCornerShape(radius))
}
