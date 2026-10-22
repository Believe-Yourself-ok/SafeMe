package com.safeme.app.ui.screens.antitamper

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safeme.app.R
import com.safeme.app.protect.DeviceAdminUtils
import com.safeme.app.ui.components.ToastHost
import com.safeme.app.ui.screens.blockscreen.InfoIcon
import com.safeme.app.ui.screens.common.ProtectedActionHost
import com.safeme.app.ui.screens.permissions.ChevronIcon
import com.safeme.app.ui.theme.LocalAppColors

@Composable
fun AntiTamperScreen(
    onBack: () -> Unit,
    onOpenAccessibilityProtection: () -> Unit = {},
    viewModel: AntiTamperViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val adminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // No trusted result from the system Device Admin page — re-check on return.
        viewModel.refreshAdminActive()
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
                title = stringResource(R.string.at_title),
                subtitle = stringResource(R.string.at_subtitle),
                onBack = onBack,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                PreventUninstallCard(
                    adminActive = state.adminActive,
                    onToggle = {
                        if (state.adminActive) {
                            viewModel.deactivate()
                        } else {
                            viewModel.activate()
                            adminLauncher.launch(DeviceAdminUtils.activationIntent(context))
                        }
                    },
                )
                Spacer(Modifier.height(14.dp))
                ProtectBtn(onClick = onOpenAccessibilityProtection)
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

    // COD-79: prompts before Deactivate, and before the PU flag is synced down
    // after the system admin page reports the admin is gone.
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

/**
 * The Prevent Uninstall toggle card: trash icon in a danger box, title + sub,
 * and a pill button that opens the Device Admin activation page (or revokes
 * it in-app, since the system deactivation page is itself blocked by the
 * anti-tamper guards).
 */
@Composable
private fun PreventUninstallCard(
    adminActive: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cardShape(radius = 20.dp)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(colors.dangerBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AtTrashIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.danger,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.at_card_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
            Text(
                text = stringResource(R.string.at_card_sub),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = colors.ink2,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .height(38.dp)
                .clip(CircleShape)
                .background(colors.brandSoft)
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (adminActive) R.string.at_deactivate else R.string.at_activate,
                ),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                color = colors.brandDark,
            )
        }
    }
}

/**
 * Full-width "Protect Another App's Accessibility Service" row. Navigates to
 * the in-app Accessibility Protection screen (matching the prototype); it
 * never jumps straight into the system accessibility settings.
 */
@Composable
private fun ProtectBtn(onClick: () -> Unit) {
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
            text = stringResource(R.string.at_protect_btn_title),
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
            text = stringResource(R.string.at_note),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colors.ink2,
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
