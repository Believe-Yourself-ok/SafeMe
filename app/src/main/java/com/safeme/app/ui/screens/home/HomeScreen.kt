package com.safeme.app.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safeme.app.R
import com.safeme.app.data.ActivityEntry
import com.safeme.app.data.QuickActionType
import com.safeme.app.data.formatActivityTime
import com.safeme.app.ui.components.ToastHost
import com.safeme.app.ui.screens.history.dotColor
import com.safeme.app.ui.theme.LocalAppColors
import com.safeme.app.ui.theme.SerifFamily

private val A11yBannerBg = Color(0x12F44336)
private val HeroAccent = Color(0xFFE8A07E)
private val HeroRingTrack = Color(0xFFEADFD6)

@Composable
fun HomeScreen(
    onReviewShield: () -> Unit = {},
    onAddKeyword: () -> Unit = {},
    onNewFocusSession: () -> Unit = {},
    onBackup: () -> Unit = {},
    onHistory: () -> Unit = {},
    onOpenWebsites: () -> Unit = {},
    onOpenVpn: () -> Unit = {},
    onOpenAppLock: () -> Unit = {},
    onEditQuickActions: () -> Unit = {},
    onOpenAccessibility: () -> Unit = {},
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val quickActions by viewModel.quickActions.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = LocalAppColors.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            HomeHeaderRow(
                greeting = state.greeting,
                dateLine = state.dateLine,
                pillText = stringResource(state.pillTextRes),
                pillGreen = state.pillGreen
            )
            Spacer(Modifier.height(16.dp))
            HeroCard(
                progress = state.heroProgress,
                titleRes = state.heroTitleRes,
                subtitle = state.heroSubtitle,
                onReviewShield = onReviewShield
            )
            if (!state.a11yEnabled && state.a11yStateKnown && !state.a11yChecking) {
                Spacer(Modifier.height(12.dp))
                A11yBanner(onOpenAccessibility = onOpenAccessibility)
            }
            Spacer(Modifier.height(14.dp))
            MasterProtectionCard(
                enabled = state.masterProtection,
                onToggle = viewModel::toggleMasterProtection
            )
            SectionTitle(
                title = stringResource(R.string.home_quick_actions),
                moreText = stringResource(R.string.home_edit),
                onMore = onEditQuickActions
            )
            QuickActionsGrid(
                actions = quickActions,
                onAddKeyword = onAddKeyword,
                onNewFocusSession = onNewFocusSession,
                onBackup = onBackup,
                onWebsites = onOpenWebsites,
                onVpn = onOpenVpn,
                onAppLock = onOpenAppLock,
                onHistory = onHistory
            )
            SectionTitle(title = stringResource(R.string.home_today))
            StatsRow(
                blocked = state.blockedToday,
                schedules = state.focusSessionCount
            )
            SectionTitle(
                title = stringResource(R.string.home_recent_activity),
                moreText = stringResource(R.string.home_history),
                onMore = onHistory
            )
            FeedCard(entries = state.feed)
            Spacer(Modifier.height(16.dp))
        }
        ToastHost(
            flow = viewModel.toasts,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
}

@Composable
private fun HomeHeaderRow(greeting: String, dateLine: String, pillText: String, pillGreen: Boolean) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(colors.brand, HeroAccent),
                        start = Offset.Zero,
                        end = Offset.Infinite
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.home_user_name).take(1),
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_header_greeting, greeting, stringResource(R.string.home_user_name)),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = dateLine,
                fontSize = 12.sp,
                color = colors.ink2
            )
        }
        Spacer(Modifier.width(10.dp))
        if (pillGreen) {
            Pill(
                text = pillText,
                dotColor = colors.success,
                bg = colors.successBg,
                contentColor = colors.success
            )
        } else {
            Pill(
                text = pillText,
                dotColor = colors.warning,
                bg = colors.warningBg,
                contentColor = colors.warning
            )
        }
    }
}

@Composable
private fun HeroCard(progress: Float, titleRes: Int, subtitle: String, onReviewShield: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .cardShape(radius = 20.dp)
            .background(
                Brush.linearGradient(
                    0f to colors.brandDark,
                    0.6f to colors.brand,
                    1f to HeroAccent,
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            )
            .padding(22.dp)
    ) {
        HeroRings()
        Column {
            Text(
                text = stringResource(R.string.home_shield_tag),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = Color.White.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(titleRes),
                fontFamily = SerifFamily,
                fontSize = 27.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.92f)
            )
            Spacer(Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HeroRing(progress = progress)
                Box(
                    modifier = Modifier
                        .height(52.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable(onClick = onReviewShield)
                        .padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.home_review_shield),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroRings() {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(150.dp)
                .offset(x = 30.dp, y = (-30).dp)
                .border(2.dp, Color.White.copy(alpha = 0.18f), CircleShape)
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(170.dp)
                .offset(x = 58.dp, y = 42.dp)
                .border(2.dp, Color.White.copy(alpha = 0.18f), CircleShape)
        )
    }
}

@Composable
private fun HeroRing(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(56.dp)) {
        val stroke = 6.dp.toPx() * (56f / 64f)
        val r = 26.dp.toPx() * (56f / 64f)
        val topLeft = Offset(
            size.width / 2f - r,
            size.height / 2f - r
        )
        val arcSize = Size(r * 2f, r * 2f)
        drawArc(
            color = HeroRingTrack,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke)
        )
        drawArc(
            color = Color.White,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun A11yBanner(onOpenAccessibility: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cardShape(radius = 20.dp)
            .background(A11yBannerBg)
            .border(1.dp, colors.danger, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(colors.dangerBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = HomeAccessibilityIcon,
                contentDescription = null,
                tint = colors.danger,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_a11y_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_a11y_sub),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = colors.ink2
            )
        }
        Spacer(Modifier.width(8.dp))
        Pill(
            text = stringResource(R.string.home_disabled),
            dotColor = colors.danger,
            bg = colors.dangerBg,
            contentColor = colors.danger
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .height(38.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.brandSoft)
                .clickable(onClick = onOpenAccessibility)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.home_open),
                color = colors.brandDark,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun MasterProtectionCard(enabled: Boolean, onToggle: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cardShape()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_master_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_master_sub),
                fontSize = 12.5.sp,
                color = colors.ink2
            )
        }
        Spacer(Modifier.width(14.dp))
        MasterSwitch(enabled = enabled, onToggle = onToggle)
    }
}

@Composable
private fun MasterSwitch(enabled: Boolean, onToggle: () -> Unit) {
    val colors = LocalAppColors.current
    val bg by animateColorAsState(if (enabled) colors.brand else colors.swOff, tween(200))
    val thumbOffset by animateDpAsState(if (enabled) 21.dp else 0.dp, tween(200))
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 31.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .semantics { role = Role.Switch }
            .clickable(role = Role.Switch, onClick = onToggle)
            .padding(3.dp)
    ) {
        Box(
            Modifier
                .size(25.dp)
                .offset(x = thumbOffset)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun SectionTitle(title: String, moreText: String? = null, onMore: () -> Unit = {}) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.2.sp,
            color = colors.ink
        )
        if (moreText != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = moreText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.brandDark,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onMore)
                    .padding(4.dp)
            )
        }
    }
}

@Composable
private fun QuickActionsGrid(
    actions: List<QuickActionType>,
    onAddKeyword: () -> Unit,
    onNewFocusSession: () -> Unit,
    onBackup: () -> Unit,
    onWebsites: () -> Unit,
    onVpn: () -> Unit,
    onAppLock: () -> Unit,
    onHistory: () -> Unit
) {
    val colors = LocalAppColors.current
    if (actions.isEmpty()) {
        Text(
            text = stringResource(R.string.home_quick_actions_empty),
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = colors.ink2
        )
        return
    }
    Column {
        actions.chunked(2).forEachIndexed { rowIndex, rowActions ->
            if (rowIndex > 0) Spacer(Modifier.height(10.dp))
            Row {
                rowActions.forEachIndexed { index, action ->
                    if (index > 0) Spacer(Modifier.width(10.dp))
                    QuickActionCard(
                        icon = action.icon(),
                        title = stringResource(action.titleRes()),
                        sub = stringResource(action.subRes()),
                        onClick = action.onClick(
                            onAddKeyword,
                            onNewFocusSession,
                            onBackup,
                            onWebsites,
                            onVpn,
                            onAppLock,
                            onHistory
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
                // Odd count: keep the last cell the same width as its sibling.
                if (rowActions.size == 1) Spacer(Modifier.width(10.dp).weight(1f))
            }
        }
    }
}

internal fun QuickActionType.icon() = when (this) {
    QuickActionType.KEYWORD -> HomeHashtagIcon
    QuickActionType.FOCUS_SESSIONS -> HomeCalendarIcon
    QuickActionType.BACKUP -> HomeDownloadIcon
    QuickActionType.WEBSITES -> HomeGlobeIcon
    QuickActionType.VPN -> HomeShieldIcon
    QuickActionType.APPLOCK -> HomeLockIcon
    QuickActionType.HISTORY -> HomeHistoryIcon
}

internal fun QuickActionType.titleRes() = when (this) {
    QuickActionType.KEYWORD -> R.string.home_add_keyword
    QuickActionType.FOCUS_SESSIONS -> R.string.home_new_schedule
    QuickActionType.BACKUP -> R.string.home_backup
    QuickActionType.WEBSITES -> R.string.home_websites
    QuickActionType.VPN -> R.string.home_vpn
    QuickActionType.APPLOCK -> R.string.home_app_lock
    QuickActionType.HISTORY -> R.string.home_history
}

internal fun QuickActionType.subRes() = when (this) {
    QuickActionType.KEYWORD -> R.string.home_add_keyword_sub
    QuickActionType.FOCUS_SESSIONS -> R.string.home_new_schedule_sub
    QuickActionType.BACKUP -> R.string.home_backup_sub
    QuickActionType.WEBSITES -> R.string.home_websites_sub
    QuickActionType.VPN -> R.string.home_vpn_sub
    QuickActionType.APPLOCK -> R.string.home_app_lock_sub
    QuickActionType.HISTORY -> R.string.home_history_sub
}

private fun QuickActionType.onClick(
    onAddKeyword: () -> Unit,
    onNewFocusSession: () -> Unit,
    onBackup: () -> Unit,
    onWebsites: () -> Unit,
    onVpn: () -> Unit,
    onAppLock: () -> Unit,
    onHistory: () -> Unit
): () -> Unit = when (this) {
    QuickActionType.KEYWORD -> onAddKeyword
    QuickActionType.FOCUS_SESSIONS -> onNewFocusSession
    QuickActionType.BACKUP -> onBackup
    QuickActionType.WEBSITES -> onWebsites
    QuickActionType.VPN -> onVpn
    QuickActionType.APPLOCK -> onAppLock
    QuickActionType.HISTORY -> onHistory
}

@Composable
private fun QuickActionCard(
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .shadow(
                1.dp,
                RoundedCornerShape(18.dp),
                ambientColor = colors.ink.copy(alpha = 0.02f),
                spotColor = colors.ink.copy(alpha = 0.02f)
            )
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.brandSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.brandDark,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            color = colors.ink
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = sub,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            color = colors.ink2
        )
    }
}

@Composable
private fun StatsRow(blocked: String, schedules: String) {
    val colors = LocalAppColors.current
    Row {
        StatCard(value = blocked, label = stringResource(R.string.home_stat_blocked), color = colors.brand, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        StatCard(value = schedules, label = stringResource(R.string.home_stat_schedules), color = colors.warning, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = color
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            color = colors.ink2
        )
    }
}

@Composable
private fun FeedCard(entries: List<ActivityEntry>) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .cardShape()
            .padding(horizontal = 22.dp)
    ) {
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.home_feed_empty),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = colors.ink2,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            entries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(color = colors.line)
                FeedRow(
                    dotColor = dotColor(entry.type),
                    title = entry.title,
                    sub = entry.sub,
                    time = formatActivityTime(entry.timeMillis)
                )
            }
        }
    }
}

@Composable
private fun FeedRow(dotColor: Color, title: String, sub: String, time: String) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = sub,
                fontSize = 12.sp,
                color = colors.ink2
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = time,
                fontSize = 11.sp,
                color = colors.ink3
            )
        }
    }
}

@Composable
private fun Pill(text: String, dotColor: Color, bg: Color, contentColor: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
    }
}

@Composable
private fun Modifier.cardShape(radius: Dp = 20.dp): Modifier {
    val colors = LocalAppColors.current
    return this
        .shadow(
            1.dp,
            RoundedCornerShape(radius),
            ambientColor = colors.ink.copy(alpha = 0.02f),
            spotColor = colors.ink.copy(alpha = 0.02f)
        )
        .clip(RoundedCornerShape(radius))
        .background(colors.surface)
        .border(1.dp, colors.line, RoundedCornerShape(radius))
}
