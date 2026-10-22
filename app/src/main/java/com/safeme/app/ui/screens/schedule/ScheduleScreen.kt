package com.safeme.app.ui.screens.schedule

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safeme.app.R
import com.safeme.app.ui.components.ToastHost
import com.safeme.app.ui.screens.common.ProtectedActionHost
import com.safeme.app.ui.screens.home.HomeAccessibilityIcon
import com.safeme.app.ui.theme.LocalAppColors
import com.safeme.app.ui.theme.SerifFamily

private val HeroAccent = Color(0xFFE8A07E)
private val HeroPillDot = Color(0xFF7CE0B3)
private val HeroPillDotOff = Color(0xFFFFB4AB)

@Composable
fun ScheduleScreen(
    onNewSchedule: () -> Unit = {},
    onEditSchedule: (String) -> Unit = {},
    viewModel: ScheduleViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-check the accessibility service every time the tab regains focus, so
    // the warning clears as soon as the user enables it (and any prior
    // dismissal is forgotten so a future disable re-arms the banner).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        var showExcludeSheet by remember { mutableStateOf(false) }
        var excludeSelection by remember { mutableStateOf(setOf<String>()) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            SubHeader(
                title = stringResource(R.string.sch_title),
                onNew = onNewSchedule
            )
            HeroCard(
                count = state.heroCount,
                pill = state.heroPill,
                nextBoundary = state.nextBoundary
            )
            if (state.showA11yWarning) {
                Spacer(Modifier.height(12.dp))
                A11yWarningBanner(
                    onEnable = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onDismiss = viewModel::dismissA11yWarning
                )
            }
            if (state.showExactAlarmWarning) {
                Spacer(Modifier.height(12.dp))
                ExactAlarmBanner(
                    onEnable = { context.startActivity(exactAlarmSettingsIntent(context)) },
                    onDismiss = viewModel::dismissExactAlarmWarning
                )
            }
            Spacer(Modifier.height(12.dp))
            ExcludeAppsCard(
                count = state.excludedApps.size,
                onManage = {
                    viewModel.ensureAppsLoaded()
                    excludeSelection = state.excludedApps
                    showExcludeSheet = true
                }
            )
            SectionTitle(text = stringResource(R.string.sch_your_schedules))
            ScheduleList(
                cards = state.cards,
                onToggle = viewModel::toggleSchedule,
                onEdit = onEditSchedule
            )
            Spacer(Modifier.size(12.dp))
            Note()
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
                title = stringResource(R.string.sch_exclude_sheet_title),
                subtitle = stringResource(R.string.sch_exclude_sheet_sub),
            )
        }
    }

    // COD-79: prompts before a schedule can be switched off from the list.
    ProtectedActionHost(viewModel)
}

@Composable
private fun SubHeader(title: String, onNew: () -> Unit) {
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
                .height(38.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = CircleShape,
                    ambientColor = colors.brand.copy(alpha = 0.35f),
                    spotColor = colors.brand.copy(alpha = 0.35f)
                )
                .clip(CircleShape)
                .background(colors.brand)
                .clickable(onClick = onNew)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.sch_new),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun HeroCard(count: String, pill: String, nextBoundary: String) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(colors.brandDark, colors.brand, HeroAccent),
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset.Infinite
                )
            )
            .padding(22.dp)
    ) {
        HeroRings()
        Column {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = SchCalendarIcon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.size(14.dp))
            Text(
                text = stringResource(R.string.sch_hero_tag),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = Color.White.copy(alpha = 0.85f)
            )
            Text(
                text = count,
                fontFamily = SerifFamily,
                fontSize = 27.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
            )
            Text(
                text = nextBoundary,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.92f)
            )
            Spacer(Modifier.size(14.dp))
            HeroPill(
                text = pill,
                dotColor = if (pill == stringResource(R.string.sch_hero_pill_on)) HeroPillDot else HeroPillDotOff
            )
        }
    }
}

@Composable
private fun HeroRings() {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 30.dp, y = (-30).dp)
                .size(150.dp)
                .border(2.dp, Color.White.copy(alpha = 0.18f), CircleShape)
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 58.dp, y = 42.dp)
                .size(170.dp)
                .border(2.dp, Color.White.copy(alpha = 0.18f), CircleShape)
        )
    }
}

@Composable
private fun HeroPill(text: String, dotColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun A11yWarningBanner(onEnable: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cardShape(radius = 20.dp)
            .background(colors.dangerBg)
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
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.sch_warn_a11y_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.sch_warn_a11y_sub),
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = colors.ink2
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .height(38.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.brandSoft)
                .clickable(onClick = onEnable)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.sch_warn_a11y_enable),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.brandDark
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = SchCloseIcon,
            contentDescription = stringResource(R.string.sch_warn_a11y_dismiss),
            tint = colors.ink3,
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onDismiss)
                .padding(3.dp)
        )
    }
}

// Banner only renders on API 31+ (see shouldShowExactAlarmWarning); the
// Settings constant is compile-time-inlined so referencing it is safe.
@SuppressLint("InlinedApi")
private fun exactAlarmSettingsIntent(context: Context): Intent = Intent(
    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
    "package:${context.packageName}".toUri()
)

@Composable
private fun ExactAlarmBanner(onEnable: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cardShape(radius = 20.dp)
            .background(colors.dangerBg)
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
                imageVector = SchClockIcon,
                contentDescription = null,
                tint = colors.danger,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.sch_warn_exact_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.sch_warn_exact_sub),
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = colors.ink2
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .height(38.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.brandSoft)
                .clickable(onClick = onEnable)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.sch_warn_exact_enable),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.brandDark
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = SchCloseIcon,
            contentDescription = stringResource(R.string.sch_warn_exact_dismiss),
            tint = colors.ink3,
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onDismiss)
                .padding(3.dp)
        )
    }
}

@Composable
private fun ExcludeAppsCard(
    count: Int,
    onManage: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .cardShape(radius = 20.dp)
            .clickable(onClick = onManage)
            .padding(16.dp)
    ) {
        // Mirrors VpnWhitelistCard's "icon · title · count-as-subtitle · Manage"
        // row: a single trailing action keeps the text column full-width, so the
        // subtitle never wraps into a cramped stack between two pills.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(
                icon = SchInfoIcon,
                background = colors.brandSoft,
                tint = colors.brand,
                size = 40.dp,
                iconSize = 20.dp,
                radius = 13.dp
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sch_exclude_title),
                    fontSize = 15.sp,
                    lineHeight = 19.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (count == 0) {
                        stringResource(R.string.sch_exclude_sub)
                    } else {
                        pluralStringResource(R.plurals.sch_exclude_count, count, count)
                    },
                    fontSize = 12.5.sp,
                    color = colors.ink2,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .height(38.dp)
                    .clip(CircleShape)
                    .background(colors.brandSoft)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.sch_exclude_manage),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.brandDark
                )
            }
        }
    }
}

@Composable
private fun ScheduleList(
    cards: List<ScheduleCard>,
    onToggle: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    // Prototype `#schedList`: transparent column, 12px gap, each card is its
    // own bordered box.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        cards.forEach { card ->
            ScheduleCard(
                card = card,
                onToggle = { onToggle(card.id) },
                onEdit = { onEdit(card.id) }
            )
        }
    }
}

@Composable
private fun ScheduleCard(
    card: ScheduleCard,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .cardShape(radius = 20.dp)
            .padding(16.dp)
    ) {
        // Prototype `.sched-head`: icon · title/subtitle · switch.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(
                icon = SchCalendarIcon,
                background = colors.iconAmberBg,
                tint = colors.warning,
                size = 40.dp,
                iconSize = 20.dp,
                radius = 13.dp
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.name,
                    fontSize = 15.sp,
                    lineHeight = 19.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = card.days,
                    fontSize = 12.sp,
                    color = colors.ink2,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            MasterSwitch(checked = card.enabled, onToggle = onToggle)
        }
        Spacer(Modifier.size(14.dp))
        // Prototype `.sched-body`: bordered box (radius 14, 14dp padding) that
        // holds the time row and the summary pills.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background, RoundedCornerShape(14.dp))
                .border(1.dp, colors.line, RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = SchClockIcon,
                    contentDescription = null,
                    tint = colors.brand,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = card.time,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.brandDark,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
            }
            // Prototype `.sched-pills`: border-top + 12px padding separates the
            // pills from the time row above.
            Spacer(Modifier.size(12.dp))
            HorizontalDivider(color = colors.line)
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextPill(text = card.mode, background = colors.successBg, contentColor = colors.success)
                TextPill(text = card.apps, background = colors.brandSoft, contentColor = colors.brandDark)
            }
        }
        Spacer(Modifier.size(14.dp))
        // Prototype `.sched-foot`: next boundary + edit, space-between.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = SchCalendarIcon,
                contentDescription = null,
                tint = colors.ink3,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = card.next,
                fontSize = 12.sp,
                color = colors.ink2,
                style = TextStyle(fontFeatureSettings = "tnum"),
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .height(38.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onEdit)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.sch_edit),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink2
                )
            }
        }
    }
}

@Composable
private fun TextPill(text: String, background: Color, contentColor: Color) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
    }
}

@Composable
private fun MasterSwitch(checked: Boolean, onToggle: () -> Unit) {
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

@Composable
private fun Note() {
    val colors = LocalAppColors.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = SchInfoIcon,
            contentDescription = null,
            tint = colors.brand,
            modifier = Modifier.size(16.dp).padding(top = 1.dp)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.sch_note),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colors.ink2
        )
    }
}

@Composable
private fun IconBox(
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
private fun Modifier.cardShape(radius: Dp = 20.dp): Modifier {
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
