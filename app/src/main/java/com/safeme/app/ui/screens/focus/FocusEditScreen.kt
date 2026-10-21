package com.safeme.app.ui.screens.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safeme.app.R
import com.safeme.app.data.FOCUS_DAY_NAMES
import com.safeme.app.data.FocusMode
import com.safeme.app.data.focusTimeLabel
import com.safeme.app.ui.components.SafeMeTextField
import com.safeme.app.ui.components.ToastHost
import com.safeme.app.ui.screens.permissions.ChevronIcon
import com.safeme.app.ui.theme.LocalAppColors

/**
 * Prototype `sc-scheduleedit`: name field, day circles, time window boxes,
 * block-mode segmented control, apps card, save + (edit-only) delete.
 */
@Composable
fun FocusEditScreen(
    editId: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: FocusEditViewModel = viewModel(
        key = "focusedit_${editId ?: "new"}",
        factory = FocusEditViewModel.Factory(
            context.applicationContext as android.app.Application,
            editId,
        ),
    )
    val state by viewModel.uiState.collectAsState()
    val colors = LocalAppColors.current

    var timeTarget by remember { mutableStateOf<TimeTarget?>(null) }
    var showApps by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.done.collect { onBack() }
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
                title = stringResource(
                    if (editId != null) R.string.sche_title_edit else R.string.sche_title_new
                ),
                onBack = onBack,
            )
            EnabledRow(
                enabled = state.enabled,
                onToggle = viewModel::setEnabled,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                NameField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    placeholder = stringResource(R.string.sche_name_placeholder),
                )
                GroupLabel(text = stringResource(R.string.sche_repeat))
                DayCircles(
                    selected = state.days,
                    onToggle = viewModel::toggleDay,
                )
                GroupLabel(text = stringResource(R.string.sche_time_window))
                TimeWindowRow(
                    startMinute = state.startMinute,
                    endMinute = state.endMinute,
                    onStart = { timeTarget = TimeTarget.START },
                    onEnd = { timeTarget = TimeTarget.END },
                )
                GroupLabel(text = stringResource(R.string.sche_block_mode))
                ModeSegment(
                    mode = state.mode,
                    onSelect = viewModel::setMode,
                )
                GroupLabel(text = stringResource(R.string.sche_apps))
                AppsCard(
                    selectedCount = state.selectedApps.size,
                    summary = appSummary(state, viewModel),
                    editing = editId != null,
                    onChoose = { showApps = true },
                )
                Spacer(Modifier.height(18.dp))
                PrimaryPill(
                    text = stringResource(
                        if (editId != null) R.string.sche_save else R.string.sche_create
                    ),
                    onClick = { viewModel.save() },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (editId != null) {
                    Spacer(Modifier.height(10.dp))
                    DeleteButton(onClick = viewModel::delete)
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

    timeTarget?.let { target ->
        TimePickerSheet(
            target = target,
            startMinute = state.startMinute,
            endMinute = state.endMinute,
            onDismiss = { timeTarget = null },
            onDone = { minute ->
                if (target == TimeTarget.START) viewModel.setStartMinute(minute)
                else viewModel.setEndMinute(minute)
                timeTarget = null
            },
        )
    }
    if (showApps) {
        AppPickerSheet(
            apps = state.installedApps,
            selected = state.selectedApps,
            onToggle = viewModel::toggleApp,
            onSelectAll = viewModel::selectAllApps,
            onDeselectAll = viewModel::deselectAllApps,
            onDone = {
                val count = viewModel.uiState.value.selectedApps.size
                val message = context.resources.getQuantityString(
                    R.plurals.sche_toast_apps,
                    count,
                    count,
                )
                viewModel.showToast(message)
                showApps = false
            },
            onDismiss = { showApps = false },
        )
    }
}

@Composable
private fun appSummary(state: FocusEditUiState, viewModel: FocusEditViewModel): String {
    val context = LocalContext.current
    if (state.selectedApps.isEmpty()) {
        return context.getString(R.string.sche_apps_sub_none)
    }
    val labelOf = state.installedApps.associate { it.packageName to it.label }
    val names = state.selectedApps
        .mapNotNull { labelOf[it] }
        .take(4)
    return if (names.isEmpty()) {
        context.getString(R.string.sche_apps_sub_none)
    } else {
        context.getString(R.string.sche_apps_sub_list, names.joinToString(", "))
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

/** B6: visible enabled state. Edits preserve the stored flag; new schedules default on. */
@Composable
private fun EnabledRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(14.dp))
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle)
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.sche_enabled),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
            Text(
                text = stringResource(R.string.sche_enabled_sub),
                fontSize = 12.sp,
                color = colors.ink2,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = enabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colors.brand,
                checkedBorderColor = colors.brand,
            ),
        )
    }
}

/** Prototype `.field` name input. Whole row is tap-to-focus. */
@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val colors = LocalAppColors.current
    val focusRequester = remember { FocusRequester() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { focusRequester.requestFocus() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = FocusNameIcon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = colors.ink3,
        )
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(text = placeholder, fontSize = 15.sp, color = colors.ink3)
            }
            SafeMeTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(fontSize = 15.sp, color = colors.ink),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    val colors = LocalAppColors.current
    Text(
        text = text,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.6.sp,
        color = colors.ink3,
        modifier = Modifier.padding(start = 2.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** Prototype `.days` row of 42dp day circles (M T W T F S S). */
@Composable
private fun DayCircles(selected: Set<Int>, onToggle: (Int) -> Unit) {
    val colors = LocalAppColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FOCUS_DAY_NAMES.forEachIndexed { index, label ->
            val on = index in selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) colors.brand else colors.surface)
                    .border(1.dp, if (on) colors.brand else colors.line, RoundedCornerShape(14.dp))
                    .clickable { onToggle(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (on) Color.White else colors.ink2,
                )
            }
        }
    }
}

/**
 * Prototype `.timep` start/end boxes with "to" between. Overnight windows
 * (end < start) add an "Ends next day" caption under the row.
 */
@Composable
private fun TimeWindowRow(
    startMinute: Int,
    endMinute: Int,
    onStart: () -> Unit,
    onEnd: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TimeBox(minute = startMinute, onClick = onStart, modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.sche_to),
                fontSize = 14.sp,
                color = colors.ink3,
            )
            TimeBox(minute = endMinute, onClick = onEnd, modifier = Modifier.weight(1f))
        }
        if (endMinute < startMinute) {
            Text(
                text = stringResource(R.string.sche_overnight),
                fontSize = 12.sp,
                color = colors.ink3,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun TimeBox(minute: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val hour24 = minute / 60
    val h12 = (hour24 % 12).let { if (it == 0) 12 else it }
    val mm = (minute % 60).toString().padStart(2, '0')
    val ampm = if (hour24 < 12) "AM" else "PM"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.brandSoft)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
    ) {
        Text(
            text = h12.toString(),
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            color = colors.brandDark,
        )
        Text(
            text = ":",
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            color = colors.brand,
        )
        Text(
            text = mm,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            color = colors.brandDark,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = ampm,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = colors.brandDark,
        )
    }
}

/** Prototype `.seg` block-mode control (Internet | Launch | Both). */
@Composable
private fun ModeSegment(mode: FocusMode, onSelect: (FocusMode) -> Unit) {
    val options = listOf(
        FocusMode.INTERNET to stringResource(R.string.sche_mode_internet),
        FocusMode.LAUNCH to stringResource(R.string.sche_mode_launch),
        FocusMode.BOTH to stringResource(R.string.sche_mode_both),
    )
    Segmented(
        options = options.map { (m, label) -> label to (m == mode) },
        onSelect = { index -> onSelect(options[index].first) },
    )
}

/** Prototype apps card: "{n} apps selected" + summary + Choose. */
@Composable
private fun AppsCard(
    selectedCount: Int,
    summary: String,
    editing: Boolean,
    onChoose: () -> Unit,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = context.resources.getQuantityString(
                    R.plurals.sche_apps_selected,
                    selectedCount,
                    selectedCount,
                ),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
            )
            Text(
                text = summary,
                fontSize = 12.sp,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Box(
            modifier = Modifier
                .height(38.dp)
                .clip(CircleShape)
                .background(colors.brandSoft)
                .clickable(onClick = onChoose)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.sche_choose),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.brandDark,
            )
        }
    }
}

/** Prototype `.sched-del`: ghost-style block button, danger text. */
@Composable
private fun DeleteButton(onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(colors.brandSoft)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.sche_delete),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.danger,
        )
    }
}

/** Prototype `.note`: info icon + 12sp ink2 copy. */
@Composable
private fun Note() {
    val colors = LocalAppColors.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = FocusInfoIcon,
            contentDescription = null,
            tint = colors.brand,
            modifier = Modifier.size(16.dp).padding(top = 1.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.sche_note),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colors.ink2,
        )
    }
}
