package com.safeme.app.ui.screens.blocking

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safeme.app.R
import com.safeme.app.ui.components.ToastHost
import com.safeme.app.ui.screens.focus.AppPickerSheet
import com.safeme.app.ui.theme.LocalAppColors

@Composable
fun OtherFeaturesScreen(
    onBack: () -> Unit = {},
    viewModel: BlockingViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalAppColors.current
    var showExcludeSheet by remember { mutableStateOf(false) }
    var excludeSelection by remember { mutableStateOf(setOf<String>()) }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            OtherFeaturesHeaderRow(onBack = onBack)
            Spacer(Modifier.size(12.dp))
            ImageVideoSearchCard(
                enabled = state.blockImageVideoSearch,
                onToggle = viewModel::toggleImageVideoSearch
            )
            Spacer(Modifier.size(12.dp))
            ExcludeAppsCard(
                count = state.excludedApps.size,
                onManage = {
                    viewModel.ensureAppsLoaded()
                    excludeSelection = state.excludedApps
                    showExcludeSheet = true
                }
            )
            Spacer(Modifier.size(16.dp))
        }
        ToastHost(
            flow = viewModel.toasts,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
        )
        if (showExcludeSheet) {
            AppPickerSheet(
                apps = state.installedApps,
                selected = excludeSelection,
                onToggle = { pkg ->
                    excludeSelection = if (pkg in excludeSelection) excludeSelection - pkg else excludeSelection + pkg
                },
                onSelectAll = {
                    excludeSelection = excludeSelection + state.installedApps.map { it.packageName }.toSet()
                },
                onDeselectAll = {
                    excludeSelection = excludeSelection - state.installedApps.map { it.packageName }.toSet()
                },
                onDone = {
                    viewModel.setExcludedApps(excludeSelection)
                    showExcludeSheet = false
                },
                onDismiss = { showExcludeSheet = false },
                title = stringResource(R.string.blk_exclude_sheet_title),
                subtitle = stringResource(R.string.blk_exclude_sheet_sub),
            )
        }
    }
}

@Composable
private fun ExcludeAppsCard(
    count: Int,
    onManage: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .cardShape(radius = 18.dp)
            .padding(14.dp)
    ) {
        IconBox(
            icon = BlkLayersIcon,
            background = colors.brandSoft,
            tint = colors.brandDark,
            size = 40.dp,
            iconSize = 20.dp,
            radius = 13.dp
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.blk_exclude_title),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink
            )
            Text(
                text = if (count == 0) {
                    stringResource(R.string.blk_exclude_sub)
                } else {
                    pluralStringResource(R.plurals.blk_exclude_count, count, count)
                },
                fontSize = 11.5.sp,
                color = colors.ink2,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.size(12.dp))
        Box(
            modifier = Modifier
                .height(36.dp)
                .clip(CircleShape)
                .background(colors.brandSoft)
                .clickable(onClick = onManage)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.blk_exclude_manage),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.brandDark
            )
        }
    }
}

@Composable
private fun OtherFeaturesHeaderRow(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 16.dp)
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
                imageVector = BlkChevronLeftIcon,
                contentDescription = stringResource(R.string.perm_back),
                modifier = Modifier.size(20.dp),
                tint = colors.ink,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.blk_card_otherfeatures),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            color = colors.ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.blk_card_otherfeatures_sub),
            fontSize = 12.sp,
            lineHeight = 20.sp,
            color = colors.ink2,
        )
    }
}
