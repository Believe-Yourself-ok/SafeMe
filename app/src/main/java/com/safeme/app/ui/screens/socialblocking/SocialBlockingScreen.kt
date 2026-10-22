package com.safeme.app.ui.screens.socialblocking

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safeme.app.R
import com.safeme.app.data.AppCatalog
import com.safeme.app.data.InstalledApp
import com.safeme.app.data.SocialBlockingPrefs
import com.safeme.app.ui.components.GroupedAppPickerList
import com.safeme.app.ui.components.SafeMeTextField
import com.safeme.app.ui.components.ToastHost
import com.safeme.app.ui.screens.common.ProtectedActionHost
import com.safeme.app.ui.theme.LocalAppColors
import com.safeme.app.ui.theme.SerifFamily

private data class LaunchItem(
    val label: String,
    val pkg: String,
    val subtitle: String,
    val bg: Color,
    val fg: Color,
)

private val DEFAULT_LAUNCH: List<LaunchItem> = listOf(
    LaunchItem("TikTok", "com.zhiliaoapp.musically", "Blocked entirely before opening", Color(0xFFFDEEE2), Color(0xFFF97316)),
    LaunchItem("Instagram", "com.instagram.android", "Blocked entirely before opening", Color(0xFFFDEAF4), Color(0xFFE1306C)),
    LaunchItem("X / Twitter", "com.twitter.android", "Timeline & notifications restricted", Color(0xFFEFEFEF), Color(0xFF0F1419)),
    LaunchItem("Reddit", "com.reddit.frontpage", "Infinite feeds restricted", Color(0xFFFDE7E7), Color(0xFFFF4500)),
    LaunchItem("Twitch", "tv.twitch.android.app", "Live streams restricted", Color(0xFFF3E8FF), Color(0xFF9146FF)),
)

@Composable
fun SocialBlockingScreen(
    onBack: () -> Unit,
    viewModel: SocialBlockingViewModel = viewModel(),
) {
    val colors = LocalAppColors.current
    val state by viewModel.uiState.collectAsState()
    val allApps by viewModel.allApps.collectAsState()
    val isLoading by viewModel.isLoadingApps.collectAsState()
    var showPicker by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            SocialSubHeader(onBack = onBack)
            Spacer(Modifier.height(12.dp))
            MasterCard(
                enabled = state.enabled,
                displayLaunch = state.displayLaunchCount,
                activeTabs = state.activeTabs,
                onToggle = viewModel::toggleMaster,
            )
            Spacer(Modifier.height(18.dp))
            QuickFocusModeRow(
                preset = state.preset,
                onPreset = viewModel::applyPreset,
            )
            Spacer(Modifier.height(18.dp))
            LaunchBlockSection(
                enabled = state.enabled,
                wholeBlocked = state.wholeBlocked,
                allApps = allApps,
                onToggleLaunch = viewModel::toggleLaunch,
                onAdd = { showPicker = true },
            )
            Spacer(Modifier.height(18.dp))
            TabBlockSection(
                enabled = state.enabled,
                youtube = state.youtube,
                facebook = state.facebook,
                snapchat = state.snapchat,
                activeTabs = state.activeTabs,
                onYoutube = viewModel::toggleYoutube,
                onFacebook = viewModel::toggleFacebook,
                onSnapchat = viewModel::toggleSnapchat,
            )
            Spacer(Modifier.height(16.dp))
            FootnoteCard()
            Spacer(Modifier.height(16.dp))
        }
        ToastHost(flow = viewModel.toasts, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
    }

    if (showPicker) {
        SocialPickerSheet(
            apps = allApps,
            loading = isLoading,
            selected = state.wholeBlocked,
            onToggle = viewModel::toggleLaunch,
            onSelectAll = {
                // Select all launchable apps as whole-blocked
                viewModel.setWholeBlocked(allApps.map { it.packageName }.toSet())
            },
            onDeselectAll = {
                viewModel.setWholeBlocked(emptySet())
            },
            onDone = { showPicker = false },
            onDismiss = { showPicker = false },
        )
    }

    // COD-79: prompts before the master switch, an app block, a tab block or a
    // weakening preset can remove protection.
    ProtectedActionHost(viewModel)
}

@Composable
private fun SocialSubHeader(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
        Box(
            modifier = Modifier
                .offset(x = (-8).dp)
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface, RoundedCornerShape(14.dp))
                .border(1.dp, colors.line, RoundedCornerShape(14.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = SocialBackIcon, contentDescription = "Back", tint = colors.ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Social Media Blocking",
            fontFamily = SerifFamily,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            color = colors.ink,
            lineHeight = 30.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Block a whole app at launch, or target specific addictive tabs.",
            fontSize = 13.sp,
            color = colors.ink2,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun MasterCard(enabled: Boolean, displayLaunch: Int, activeTabs: Int, onToggle: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface, RoundedCornerShape(20.dp))
            .border(1.dp, colors.line, RoundedCornerShape(20.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(colors.brandSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = SocialShieldIcon, contentDescription = null, tint = colors.brandDark, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Social Media Blocking", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = colors.ink, lineHeight = 18.sp)
                Text(text = if (enabled) "Whole apps & tabs protected" else "Paused — tap to protect", fontSize = 12.sp, color = colors.ink2, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(12.dp))
            SocialSwitch(checked = enabled, onToggle = onToggle)
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = colors.line)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(if (enabled) colors.success else colors.ink3))
                Spacer(Modifier.width(6.dp))
                Text(text = if (enabled) "Master ON" else "Master OFF", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) colors.success else colors.ink3)
                if (enabled) Spacer(Modifier.width(4.dp))
                if (enabled) Box(modifier = Modifier.size(7.dp)) // shadow placeholder not needed in compose gate — visual parity via dot only
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(colors.background).border(1.dp, colors.line, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "$displayLaunch launch", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.ink2)
                }
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(colors.background).border(1.dp, colors.line, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "$activeTabs/3 tabs", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.ink2)
                }
            }
        }
    }
}

@Composable
private fun QuickFocusModeRow(preset: String, onPreset: (String) -> Unit) {
    val colors = LocalAppColors.current
    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "QUICK FOCUS MODE", fontSize = 11.5.sp, letterSpacing = 0.9.sp, fontWeight = FontWeight.Bold, color = colors.ink)
            Text(text = "1-Tap Preset", fontSize = 11.sp, color = colors.ink3)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PresetCard(
                title = "Deep Work",
                subtitle = "All apps & tabs",
                emoji = "🔥",
                selected = preset == "deep",
                onClick = { onPreset("deep") },
                modifier = Modifier.weight(1f),
            )
            PresetCard(
                title = "Balanced",
                subtitle = "Tabs only",
                emoji = "⚖",
                selected = preset == "balanced",
                onClick = { onPreset("balanced") },
                modifier = Modifier.weight(1f),
            )
            PresetCard(
                title = "Relax",
                subtitle = "All paused",
                emoji = "☕",
                selected = preset == "relax",
                onClick = { onPreset("relax") },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PresetCard(title: String, subtitle: String, emoji: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .background(if (selected) colors.brandSoft else colors.surface, RoundedCornerShape(16.dp))
            .border(width = if (selected) 1.5.dp else 1.dp, color = if (selected) colors.brand else colors.line, shape = RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = emoji, fontSize = 13.sp)
            Spacer(Modifier.width(5.dp))
            Text(text = title, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, color = if (selected) colors.brandDark else colors.ink)
        }
        Spacer(Modifier.height(4.dp))
        Text(text = subtitle, fontSize = 11.sp, color = colors.ink2, lineHeight = 13.sp)
    }
}

@Composable
private fun LaunchBlockSection(
    enabled: Boolean,
    wholeBlocked: Set<String>,
    allApps: List<InstalledApp>,
    onToggleLaunch: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "LAUNCH BLOCK", fontSize = 11.5.sp, letterSpacing = 0.9.sp, fontWeight = FontWeight.Bold, color = colors.ink)
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(colors.brandSoft).border(1.dp, colors.brand.copy(alpha = 0.25f), RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(text = "Full App", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp, color = colors.brandDark) }
                }
                Spacer(Modifier.height(4.dp))
                Text(text = "Entire app is blocked before opening.", fontSize = 11.5.sp, color = colors.ink2, lineHeight = 15.sp)
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(colors.surface).border(1.dp, colors.line, RoundedCornerShape(999.dp)).clickable(onClick = onAdd).padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "+", fontSize = 14.sp, color = colors.brandDark, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Text(text = "Add", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colors.brandDark)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DEFAULT_LAUNCH.forEach { item ->
                val checked = enabled && item.pkg in wholeBlocked
                LaunchRow(item = item, checked = checked, enabled = enabled, onToggle = { onToggleLaunch(item.pkg) })
            }
            // Extra apps beyond defaults
            val defaultPkgs = DEFAULT_LAUNCH.map { it.pkg }.toSet()
            val extras = wholeBlocked.filterNot { it in defaultPkgs }
            extras.sorted().forEach { pkg ->
                val label = allApps.firstOrNull { it.packageName == pkg }?.label ?: pkg.substringAfterLast(".")
                val pkgLabel = allApps.firstOrNull { it.packageName == pkg }?.packageName ?: pkg
                val extraItem = LaunchItem(label, pkg, pkgLabel, Color(0xFFEFEFEF), Color(0xFF6B625A))
                val checked = enabled && pkg in wholeBlocked
                LaunchRow(item = extraItem, checked = checked, enabled = enabled, onToggle = { onToggleLaunch(pkg) })
            }
        }
    }
}

@Composable
private fun LaunchRow(item: LaunchItem, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(item.bg).border(1.dp, colors.line, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // Initial for custom; keep light rendering for known
            Text(text = item.label.take(1).uppercase(), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = item.fg)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, lineHeight = 17.sp)
            Text(text = item.subtitle, fontSize = 11.5.sp, color = colors.ink2, modifier = Modifier.padding(top = 2.dp), lineHeight = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        // Dim pointer when master off handled by alpha; still semantically disabled via role
        SocialSwitch(checked = checked, onToggle = { if (enabled) onToggle() })
    }
}

@Composable
private fun TabBlockSection(
    enabled: Boolean,
    youtube: Boolean,
    facebook: Boolean,
    snapchat: Boolean,
    activeTabs: Int,
    onYoutube: () -> Unit,
    onFacebook: () -> Unit,
    onSnapchat: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "IN-APP TAB BLOCK", fontSize = 11.5.sp, letterSpacing = 0.9.sp, fontWeight = FontWeight.Bold, color = colors.ink)
            Box(
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (enabled && activeTabs > 0) colors.brandSoft else colors.surface).border(1.dp, if (enabled && activeTabs > 0) colors.brand.copy(alpha = 0.25f) else colors.line, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) { Text(text = "$activeTabs active", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp, color = if (enabled && activeTabs > 0) colors.brandDark else colors.ink3) }
        }
        Spacer(Modifier.height(4.dp))
        Text(text = "Keep useful functions, block endless feeds & reels.", fontSize = 11.5.sp, color = colors.ink2, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 2.dp))
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TabRow(
                title = "YouTube Shorts",
                subtitle = "Main video search & subscriptions stay active",
                bg = Color(0xFFFDE7E7),
                fg = Color(0xFFFF0000),
                checked = enabled && youtube,
                enabled = enabled,
                onToggle = onYoutube,
            )
            TabRow(
                title = "Facebook Reels",
                subtitle = "Events, groups & Messenger stay active",
                bg = Color(0xFFE7F0FD),
                fg = Color(0xFF1877F2),
                checked = enabled && facebook,
                enabled = enabled,
                onToggle = onFacebook,
            )
            TabRow(
                title = "Snapchat Spotlight",
                subtitle = "Direct messaging & camera stay active",
                bg = Color(0xFFFDF3E3),
                fg = Color(0xFFB78A00),
                checked = enabled && snapchat,
                enabled = enabled,
                onToggle = onSnapchat,
            )
        }
    }
}

@Composable
private fun TabRow(title: String, subtitle: String, bg: Color, fg: Color, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(bg).border(1.dp, colors.line, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = title.take(1).uppercase(), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = fg)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, lineHeight = 17.sp)
            Text(text = subtitle, fontSize = 11.5.sp, color = colors.ink2, modifier = Modifier.padding(top = 2.dp), lineHeight = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        SocialSwitch(checked = checked, onToggle = { if (enabled) onToggle() })
    }
}

@Composable
private fun FootnoteCard() {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.background, RoundedCornerShape(14.dp))
            .border(1.dp, colors.line, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text(
            text = "How it works — Whole-app blocks on launch. Tab overlays the view. 250ms throttle · 4s cooldown. No root, no VPN.",
            fontSize = 11.5.sp,
            color = colors.ink2,
            lineHeight = 16.sp,
        )
    }
}

@Composable
internal fun SocialSwitch(checked: Boolean, onToggle: () -> Unit) {
    val colors = LocalAppColors.current
    val bg by animateColorAsState(targetValue = if (checked) colors.brand else colors.swOff, animationSpec = tween(200), label = "socialSwitchBg")
    val thumbOffset by animateDpAsState(targetValue = if (checked) 21.dp else 0.dp, animationSpec = tween(200), label = "socialSwitchThumb")
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
            modifier = Modifier.offset(x = 3.dp + thumbOffset).size(25.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SocialPickerSheet(
    apps: List<InstalledApp>,
    loading: Boolean,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    var query by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()
    val groups = remember(apps, query) { AppCatalog.groupApps(apps, query) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(width = 40.dp, height = 5.dp).clip(RoundedCornerShape(4.dp)).background(colors.line))
            Spacer(Modifier.height(16.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = "Block entire apps", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.ink)
                Spacer(Modifier.height(4.dp))
                Text(text = "Pick apps to block completely — All Apps (includes TikTok & Instagram). Persistent launch gate.", fontSize = 13.sp, color = colors.ink2, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(colors.surface).border(1.dp, colors.line, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "🔍", fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))
                SafeMeTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = colors.ink),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text(text = "Search apps… (try TikTok, Instagram)", fontSize = 15.sp, color = colors.ink3)
                        inner()
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.weight(1f).height(38.dp).clip(CircleShape).background(colors.brandSoft).clickable(onClick = onSelectAll),
                    contentAlignment = Alignment.Center,
                ) { Text(text = "Select All (${selected.size})", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = colors.brandDark) }
                Box(
                    modifier = Modifier.weight(1f).height(38.dp).clip(CircleShape).background(colors.brandSoft).clickable(onClick = onDeselectAll),
                    contentAlignment = Alignment.Center,
                ) { Text(text = "Deselect All (${selected.size})", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = colors.brandDark) }
            }
            Spacer(Modifier.height(12.dp))
            if (groups.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text(text = if (loading) "Loading apps…" else "No apps match your search", fontSize = 13.sp, color = colors.ink2)
                }
            } else {
                GroupedAppPickerList(
                    groups = groups,
                    selected = selected,
                    onToggle = onToggle,
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                ) { app, checked, toggle ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = toggle).padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(colors.brandSoft), contentAlignment = Alignment.Center) {
                            Text(text = app.label.take(1).uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.brandDark)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = app.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                            Text(text = app.packageName, fontSize = 11.sp, color = colors.ink2)
                        }
                        Box(
                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(if (checked) colors.brand else colors.surface).border(2.dp, if (checked) colors.brand else colors.ink3, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (checked) Text(text = "✓", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(colors.brand).clickable(onClick = onDone),
                contentAlignment = Alignment.Center,
            ) { Text(text = "Done", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
        }
    }
}
