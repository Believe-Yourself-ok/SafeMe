package com.safeme.app.ui.screens.blocking

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safeme.app.R
import com.safeme.app.ui.components.ToastHost
import com.safeme.app.ui.screens.common.ProtectedActionHost
import com.safeme.app.ui.theme.LocalAppColors

@Composable
fun BlockingScreen(
    onOpenBlockScreen: () -> Unit = {},
    onOpenVpn: () -> Unit = {},
    onOpenAntiTamper: () -> Unit = {},
    onOpenKeywords: () -> Unit = {},
    onOpenWebsites: () -> Unit = {},
    onOpenTitleBlock: () -> Unit = {},
    onOpenOtherFeatures: () -> Unit = {},
    onOpenSocialBlocking: () -> Unit = {},
    viewModel: BlockingViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalAppColors.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val comingSoonToast = stringResource(R.string.blk_coming_soon)
    val helpToast = stringResource(R.string.blk_help_toast)

    // B1 freshness (mirrors HomeScreen): roll the daily counter on resume so a
    // screen left open across midnight corrects itself.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            SubHeader(
                title = stringResource(R.string.blk_title),
                onHelp = { viewModel.showToast(helpToast) }
            )
            ShieldCard(
                blocking = state.blocking,
                onToggle = viewModel::toggleBlocking
            )
            Spacer(Modifier.size(12.dp))
            StatsRow(
                blockedToday = state.blockedToday,
                keywords = state.keywords,
                layersActive = state.layersActive
            )
            Spacer(Modifier.size(12.dp))
            ManageCard(
                subtitle = state.manageSub,
                onKeywordsClick = onOpenKeywords,
                onWebsitesClick = onOpenWebsites
            )
            SectionTitle(text = stringResource(R.string.blk_more))
MoreGrid(
                onOpenBlockScreen = onOpenBlockScreen,
                onVpn = onOpenVpn,
                onOpenTitleBlock = onOpenTitleBlock,
                onOpenAntiTamper = onOpenAntiTamper,
                onOpenOtherFeatures = onOpenOtherFeatures,
                onOpenSocialBlocking = onOpenSocialBlocking,
                onComingSoon = { viewModel.showToast(comingSoonToast) }
            )
            Spacer(Modifier.size(16.dp))
        }
        ToastHost(
            flow = viewModel.toasts,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
        )
    }

    // COD-79: prompts before the blocking switch can be turned off.
    ProtectedActionHost(viewModel)
}

@Composable
internal fun SubHeader(title: String, onHelp: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp)
    ) {
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = colors.ink,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onHelp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = BlkHelpIcon,
                contentDescription = null,
                tint = colors.ink2,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ShieldCard(
    blocking: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalAppColors.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(colors.brandMist, colors.brandSoft)))
            .border(1.dp, colors.brand.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        ShieldRings()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(14.dp),
                        ambientColor = colors.brand.copy(alpha = 0.35f),
                        spotColor = colors.brand.copy(alpha = 0.35f)
                    )
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.brand),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = BlkShieldCheckIcon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.blk_shield_status),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.brandDark
                )
            }
            Spacer(Modifier.size(12.dp))
            MasterSwitch(checked = blocking, onToggle = onToggle)
        }
    }
}

@Composable
private fun BoxScope.ShieldRings() {
    val colors = LocalAppColors.current
    Box(modifier = Modifier.matchParentSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 34.dp, y = (-34).dp)
                .size(120.dp)
                .border(2.dp, colors.brand.copy(alpha = 0.16f), CircleShape)
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 58.dp, y = 42.dp)
                .size(170.dp)
                .border(2.dp, colors.brand.copy(alpha = 0.16f), CircleShape)
        )
    }
}

@Composable
internal fun MasterSwitch(checked: Boolean, onToggle: () -> Unit) {
    val colors = LocalAppColors.current
    val bg by animateColorAsState(
        targetValue = if (checked) colors.brand else colors.swOff,
        animationSpec = tween(200),
        label = "switchBg"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 21.dp else 0.dp,
        animationSpec = tween(200),
        label = "switchThumb"
    )
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 31.dp)
            .clip(CircleShape)
            .background(bg)
            .semantics { role = Role.Switch }
            .clickable(role = Role.Switch, onClick = onToggle),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = 3.dp + thumbOffset)
                .size(25.dp)
                .shadow(2.dp, CircleShape)
                .background(Color.White, CircleShape)
        )
    }
}

@Composable
private fun StatsRow(
    blockedToday: String,
    keywords: String,
    layersActive: String,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatCard(
            value = blockedToday,
            label = stringResource(R.string.blk_stat_blocked_today),
            valueColor = colors.brand,
            modifier = Modifier.weight(1f)
        )
        StatCard(
            value = keywords,
            label = stringResource(R.string.blk_stat_keywords),
            valueColor = colors.success,
            modifier = Modifier.weight(1f)
        )
        StatCard(
            value = layersActive,
            label = stringResource(R.string.blk_stat_layers),
            valueColor = colors.warning,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatCard(
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .cardShape(radius = 18.dp)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = valueColor
        )
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            color = colors.ink2,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun ManageCard(
    subtitle: String,
    onKeywordsClick: () -> Unit,
    onWebsitesClick: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) }
    val colors = LocalAppColors.current

    // Tapping anywhere on the card opens the screen of the currently selected
    // segment, so the whole surface is as tappable as the Keywords/Websites buttons.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .cardShape(radius = 18.dp)
            .clickable(onClick = {
                if (selectedTab == 0) onKeywordsClick() else onWebsitesClick()
            })
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(
                icon = BlkMenuIcon,
                background = colors.brandSoft,
                tint = colors.brandDark,
                size = 40.dp,
                iconSize = 20.dp,
                radius = 13.dp
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.blk_manage_title),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink
                )
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = colors.ink2,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                imageVector = BlkChevronRightIcon,
                contentDescription = null,
                tint = colors.ink3,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.size(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            SegButton(
                text = stringResource(R.string.blk_seg_keywords),
                selected = selectedTab == 0,
                onClick = {
                    selectedTab = 0
                    onKeywordsClick()
                },
                modifier = Modifier.weight(1f)
            )
            SegButton(
                text = stringResource(R.string.blk_seg_websites),
                selected = selectedTab == 1,
                onClick = {
                    selectedTab = 1
                    onWebsitesClick()
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * NopoX-parity image/video-search engine toggle. Independent of the master
 * blocking switch (own DataStore flag, PreventUninstall precedent): fires the
 * gate only when an images/videos search URL AND an adult keyword co-occur.
 */
@Composable
internal fun ImageVideoSearchCard(
    enabled: Boolean,
    onToggle: () -> Unit,
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
            icon = BlkGlobeIcon,
            background = colors.brandSoft,
            tint = colors.brandDark,
            size = 40.dp,
            iconSize = 20.dp,
            radius = 13.dp
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.blk_imgvid_title),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink
            )
            Text(
                text = stringResource(R.string.blk_imgvid_sub),
                fontSize = 11.5.sp,
                color = colors.ink2,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.size(12.dp))
        MasterSwitch(checked = enabled, onToggle = onToggle)
    }
}

@Composable
private fun SegButton(
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
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Color.White else colors.ink2
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    val colors = LocalAppColors.current
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.2.sp,
        color = colors.ink,
        modifier = Modifier.padding(start = 2.dp, top = 20.dp, bottom = 10.dp)
    )
}

private enum class IconVariant {
    Amber,
    Green,
    Red,
    Dark,
}

@Composable
private fun MoreGrid(
    onOpenBlockScreen: () -> Unit,
    onVpn: () -> Unit,
    onOpenTitleBlock: () -> Unit,
    onOpenAntiTamper: () -> Unit,
    onOpenOtherFeatures: () -> Unit,
    onOpenSocialBlocking: () -> Unit,
    onComingSoon: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MoreCard(
                icon = BlkLayersIcon,
                variant = IconVariant.Amber,
                title = stringResource(R.string.blk_card_appfeature),
                sub = stringResource(R.string.blk_card_appfeature_sub),
                onClick = onOpenSocialBlocking,
                modifier = Modifier.weight(1f)
            )
            MoreCard(
                icon = BlkSquarePlusIcon,
                variant = IconVariant.Dark,
                title = stringResource(R.string.blk_card_blockscreen),
                sub = stringResource(R.string.blk_card_blockscreen_sub),
                onClick = onOpenBlockScreen,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MoreCard(
                icon = BlkShieldIcon,
                variant = IconVariant.Green,
                title = stringResource(R.string.blk_card_vpn),
                sub = stringResource(R.string.blk_card_vpn_sub),
                onClick = onVpn,
                modifier = Modifier.weight(1f)
            )
            MoreCard(
                icon = BlkShieldAlertIcon,
                variant = IconVariant.Red,
                title = stringResource(R.string.blk_card_antitamper),
                sub = stringResource(R.string.blk_card_antitamper_sub),
                onClick = onOpenAntiTamper,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MoreCard(
                icon = BlkCrossIcon,
                variant = IconVariant.Amber,
                title = stringResource(R.string.blk_card_titleblock),
                sub = stringResource(R.string.blk_card_titleblock_sub),
                onClick = onOpenTitleBlock,
                modifier = Modifier.weight(1f)
            )
            MoreCard(
                icon = BlkLayersIcon,
                variant = IconVariant.Dark,
                title = stringResource(R.string.blk_card_otherfeatures),
                sub = stringResource(R.string.blk_card_otherfeatures_sub),
                onClick = onOpenOtherFeatures,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MoreCard(
    icon: ImageVector,
    variant: IconVariant,
    title: String,
    sub: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val (iconBg, iconTint) = when (variant) {
        IconVariant.Amber -> colors.iconAmberBg to colors.warning
        IconVariant.Green -> colors.iconGreenBg to colors.success
        IconVariant.Red -> colors.dangerBg to colors.danger
        IconVariant.Dark -> colors.iconDarkBg to colors.iconDarkFg
    }
    Column(
        modifier = modifier
            .cardShape(radius = 18.dp)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        IconBox(
            icon = icon,
            background = iconBg,
            tint = iconTint,
            size = 40.dp,
            iconSize = 20.dp,
            radius = 13.dp
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = title,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            color = colors.ink
        )
        Text(
            text = sub,
            fontSize = 11.5.sp,
            color = colors.ink2,
            lineHeight = 15.sp,
            // Reserve a fixed two-line slot so every card in the grid renders at
            // the same height whether or not its subtitle wraps.
            minLines = 2,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
internal fun IconBox(
    icon: ImageVector,
    background: Color,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    iconSize: Dp = 19.dp,
    radius: Dp = 12.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
internal fun Modifier.cardShape(radius: Dp = 20.dp): Modifier {
    val colors = LocalAppColors.current
    return this
        .shadow(
            elevation = 1.dp,
            shape = RoundedCornerShape(radius),
            ambientColor = colors.ink.copy(alpha = 0.02f),
            spotColor = colors.ink.copy(alpha = 0.02f)
        )
        .clip(RoundedCornerShape(radius))
        .background(colors.surface)
        .border(1.dp, colors.line, RoundedCornerShape(radius))
}
