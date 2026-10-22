package com.safeme.app.ui.screens.vpn

import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safeme.app.R
import com.safeme.app.data.NOTIF_CUSTOM
import com.safeme.app.data.NOTIF_DEFAULT
import com.safeme.app.data.NOTIF_HIDE
import com.safeme.app.protect.ScheduleEngine
import com.safeme.app.ui.components.SafeMeTextField
import com.safeme.app.ui.components.ToastHost
import com.safeme.app.ui.screens.common.ProtectedActionHost
import com.safeme.app.ui.theme.LocalAppColors
import com.safeme.app.vpn.DnsPreset
import com.safeme.app.vpn.PrivateDnsFilter

@Composable
fun DnsVpnScreen(
    onBack: () -> Unit = {},
    viewModel: DnsVpnViewModel = viewModel(),
) {
    val colors = LocalAppColors.current
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showCustomDnsSheet by remember { mutableStateOf(false) }
    var showAppsSheet by remember { mutableStateOf(false) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onConsentResult()
    }

    LaunchedEffect(Unit) {
        viewModel.consentRequest.collect {
            val intent = VpnService.prepare(context)
            if (intent != null) {
                consentLauncher.launch(intent)
            } else {
                viewModel.onConsentResult()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            VpnHeaderRow(onBack = onBack)

            VpnStatusCard(
                enabled = state.enabled,
                running = state.running,
                preset = state.preset,
                customV4 = state.customV4,
                customV6 = state.customV6,
                exemptCount = state.whitelist.size,
                systemDns = state.enabled &&
                    PrivateDnsFilter.dotHostname(state.preset) != null &&
                    PrivateDnsFilter.hasPermission(context),
                scheduleBlocking = remember { ScheduleEngine.hasInternetBlock() },
                onToggle = viewModel::toggle,
            )

            GroupLabel(text = stringResource(R.string.vpn_group_dns))

            DnsPresetList(
                preset = state.preset,
                customV4 = state.customV4,
                customV6 = state.customV6,
                onSelectPreset = viewModel::selectPreset,
                onCustomClick = { showCustomDnsSheet = true },
            )

            Spacer(Modifier.height(12.dp))

            VpnWhitelistCard(
                exemptCount = state.whitelist.size,
                onManage = { showAppsSheet = true },
            )

            GroupLabel(text = stringResource(R.string.vpn_group_notification))

            NotifSeg(
                mode = state.notifMode,
                onSelect = viewModel::setNotifMode,
            )

            if (state.notifMode == NOTIF_CUSTOM) {
                VpnNotifField(
                    value = state.notifCustom,
                    onChange = viewModel::setNotifCustom,
                )
            }

            Spacer(Modifier.height(12.dp))

            VpnNoteRow()
        }

        ToastHost(
            flow = viewModel.toasts,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }

    if (showCustomDnsSheet) {
        CustomDnsSheet(
            v4 = state.customV4,
            v6 = state.customV6,
            onCancel = { showCustomDnsSheet = false },
            onSave = { v4, v6 ->
                if (viewModel.saveCustomDns(v4, v6)) showCustomDnsSheet = false
            },
        )
    }

    if (showAppsSheet) {
        VpnAppsSheet(
            apps = state.installedApps,
            loading = !state.appsLoaded,
            whitelist = state.whitelist,
            onToggle = viewModel::toggleWhitelistApp,
            onSelectAll = viewModel::selectAllWhitelistApps,
            onDeselectAll = viewModel::deselectAllWhitelistApps,
            onDone = {
                showAppsSheet = false
                viewModel.applyWhitelist()
            },
            onDismiss = { showAppsSheet = false },
        )
    }

    // COD-79: prompts before DNS filtering is turned off. The re-enable path and
    // the whitelist controls are not gated — see DisableProtectionPolicy's scope
    // boundary.
    ProtectedActionHost(viewModel)
}

@Composable
private fun VpnHeaderRow(onBack: () -> Unit) {
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
                .background(colors.surface, RoundedCornerShape(14.dp))
                .border(1.dp, colors.line, RoundedCornerShape(14.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = VpnBackIcon,
                contentDescription = stringResource(R.string.vpn_back),
                modifier = Modifier.size(20.dp),
                tint = colors.ink,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.vpn_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            color = colors.ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.vpn_header_sub),
            fontSize = 12.sp,
            lineHeight = 20.sp,
            color = colors.ink2,
        )
    }
}

@Composable
private fun VpnStatusCard(
    enabled: Boolean,
    running: Boolean,
    preset: DnsPreset,
    customV4: String,
    customV6: String,
    exemptCount: Int,
    systemDns: Boolean,
    scheduleBlocking: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onToggle)
            .background(colors.brandSoft, RoundedCornerShape(20.dp))
            .border(1.dp, colors.brand, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VpnPill(enabled = running)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            if (running) R.string.vpn_status_on else R.string.vpn_status_off
                        ),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.brandDark,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = vpnStatusSub(
                        running,
                        preset,
                        customV4,
                        customV6,
                        exemptCount,
                        systemDns,
                        scheduleBlocking,
                    ),
                    fontSize = 12.5.sp,
                    color = colors.brandDark,
                )
            }
            Spacer(Modifier.width(12.dp))
            VpnSwitch(checked = enabled, onToggle = onToggle)
        }
    }
}

@Composable
private fun vpnStatusSub(
    running: Boolean,
    preset: DnsPreset,
    customV4: String,
    customV6: String,
    exemptCount: Int,
    systemDns: Boolean,
    scheduleBlocking: Boolean,
): String {
    if (!running) return stringResource(R.string.vpn_status_re_enable)
    val base = if (preset == DnsPreset.CUSTOM && customV4.isNotBlank()) {
        if (customV6.isNotBlank()) {
            stringResource(R.string.vpn_custom_status_v4v6, customV4, customV6)
        } else {
            stringResource(R.string.vpn_custom_status_v4, customV4)
        }
    } else {
        preset.label
    }
    val scoped = if (systemDns) stringResource(R.string.vpn_sub_system_prefix, base) else base
    val withExempt = stringResource(R.string.vpn_sub_exempt, scoped, exemptCount)
    return if (scheduleBlocking) {
        stringResource(R.string.vpn_sub_schedule_suffix, withExempt)
    } else {
        withExempt
    }
}

@Composable
private fun VpnPill(enabled: Boolean) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (enabled) colors.iconGreenBg else colors.dangerBg)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(if (enabled) colors.success else colors.danger, CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(
                    if (enabled) R.string.vpn_pill_active else R.string.vpn_pill_off
                ),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) colors.success else colors.danger,
            )
        }
    }
}

@Composable
private fun VpnSwitch(checked: Boolean, onToggle: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 31.dp)
            .clip(CircleShape)
            .background(if (checked) colors.brand else colors.swOff)
            .clickable(onClick = onToggle),
    ) {
        Box(
            modifier = Modifier
                .padding(3.dp)
                .size(25.dp)
                .background(Color.White, CircleShape)
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart),
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    val colors = LocalAppColors.current
    Text(
        text = text.uppercase(),
        fontSize = 11.5.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.6.sp,
        color = colors.ink3,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, end = 2.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun DnsPresetList(
    preset: DnsPreset,
    customV4: String,
    customV6: String,
    onSelectPreset: (DnsPreset) -> Unit,
    onCustomClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface, RoundedCornerShape(20.dp))
            .border(1.dp, colors.line, RoundedCornerShape(20.dp)),
    ) {
        VpnDnsRow(
            icon = VpnCheckIcon,
            iconBg = colors.iconGreenBg,
            iconTint = colors.success,
            title = stringResource(R.string.vpn_preset_cloudflare),
            sub = stringResource(R.string.vpn_preset_cloudflare_sub),
            checked = preset == DnsPreset.CLOUDFLARE_FAMILY,
            onClick = { onSelectPreset(DnsPreset.CLOUDFLARE_FAMILY) },
        )
        VpnDivider()
        VpnDnsRow(
            icon = VpnShieldIcon,
            iconBg = colors.iconAmberBg,
            iconTint = colors.warning,
            title = stringResource(R.string.vpn_preset_adguard),
            sub = stringResource(R.string.vpn_preset_adguard_sub),
            checked = preset == DnsPreset.ADGUARD_FAMILY,
            onClick = { onSelectPreset(DnsPreset.ADGUARD_FAMILY) },
        )
        VpnDivider()
        val customSub = when {
            customV4.isBlank() -> stringResource(R.string.vpn_custom_sub_default)
            customV6.isBlank() -> stringResource(R.string.vpn_custom_sub_v4, customV4)
            else -> stringResource(R.string.vpn_custom_sub_v4v6, customV4, customV6)
        }
        VpnDnsRow(
            icon = VpnCustomSquareIcon,
            iconBg = colors.iconDarkBg,
            iconTint = colors.iconDarkFg,
            title = stringResource(R.string.vpn_preset_custom),
            sub = customSub,
            checked = preset == DnsPreset.CUSTOM,
            onClick = onCustomClick,
        )
    }
}

@Composable
private fun VpnDnsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBg: Color,
    iconTint: Color,
    title: String,
    sub: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(iconBg, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = iconTint,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
            )
            Text(
                text = sub,
                fontSize = 12.sp,
                color = colors.ink2,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        VpnCheckbox(checked = checked)
    }
}

@Composable
private fun VpnDivider() {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.line)
    )
}

@Composable
private fun VpnWhitelistCard(
    exemptCount: Int,
    onManage: () -> Unit,
) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface, RoundedCornerShape(20.dp))
            .border(1.dp, colors.line, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(colors.iconGreenBg, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = VpnSwapIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = colors.success,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.vpn_whitelist_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                )
                Text(
                    text = if (exemptCount == 0) {
                        stringResource(R.string.vpn_whitelist_none)
                    } else {
                        stringResource(R.string.vpn_whitelist_count, exemptCount)
                    },
                    fontSize = 12.5.sp,
                    color = colors.ink2,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .height(38.dp)
                    .clip(CircleShape)
                    .background(colors.brandSoft)
                    .clickable(onClick = onManage)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.vpn_whitelist_manage),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.brandDark,
                )
            }
        }
    }
}

@Composable
private fun NotifSeg(
    mode: String,
    onSelect: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface, RoundedCornerShape(12.dp))
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(3.dp),
    ) {
        VpnSegButton(
            text = stringResource(R.string.vpn_notif_default),
            selected = mode == NOTIF_DEFAULT,
            onClick = { onSelect(NOTIF_DEFAULT) },
            modifier = Modifier.weight(1f),
        )
        VpnSegButton(
            text = stringResource(R.string.vpn_notif_hide),
            selected = mode == NOTIF_HIDE,
            onClick = { onSelect(NOTIF_HIDE) },
            modifier = Modifier.weight(1f),
        )
        VpnSegButton(
            text = stringResource(R.string.vpn_notif_custom),
            selected = mode == NOTIF_CUSTOM,
            onClick = { onSelect(NOTIF_CUSTOM) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun VpnSegButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) colors.brand else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Color.White else colors.ink2,
        )
    }
}

@Composable
private fun VpnNotifField(
    value: String,
    onChange: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    val focusRequester = remember { FocusRequester() }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface, RoundedCornerShape(14.dp))
            .border(1.dp, colors.line, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { focusRequester.requestFocus() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = VpnBellIcon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = colors.ink3,
        )
        Spacer(Modifier.width(10.dp))
        SafeMeTextField(
            value = value,
            onValueChange = onChange,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 15.sp,
                color = colors.ink,
            ),
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.vpn_notif_custom_placeholder),
                        fontSize = 15.sp,
                        color = colors.ink3,
                    )
                }
                innerTextField()
            },
        )
    }
}

@Composable
private fun VpnNoteRow() {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = VpnInfoIcon,
            contentDescription = null,
            modifier = Modifier
                .size(16.dp)
                .padding(top = 2.dp),
            tint = colors.brand,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.vpn_note),
            fontSize = 12.sp,
            color = colors.ink2,
            lineHeight = 18.sp,
        )
    }
}
