package com.safeme.app.ui.screens.focus

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safeme.app.R
import com.safeme.app.data.AppCatalog
import com.safeme.app.data.InstalledApp
import com.safeme.app.ui.components.GroupedAppPickerList
import com.safeme.app.ui.components.SafeMeTextField
import com.safeme.app.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which time box the time sheet is editing. */
enum class TimeTarget { START, END }

/** 12-hour clock helper (prototype `h12`). */
private fun h12(hour24: Int): Int = (hour24 % 12).let { if (it == 0) 12 else it }

/**
 * Prototype `sheetTime`: Hours/Minutes steppers with 40dp circular −/+ buttons,
 * 30sp tabular values, an AM/PM segment and Cancel/Done. Done validates the
 * degenerate window (start == end, "Start and end can't be the same") and
 * refuses to close otherwise; overnight windows (end < start) are allowed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerSheet(
    target: TimeTarget,
    startMinute: Int,
    endMinute: Int,
    onDismiss: () -> Unit,
    onDone: (minute: Int) -> Unit,
) {
    val colors = LocalAppColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val initial = if (target == TimeTarget.START) startMinute else endMinute
    var hour by remember { mutableIntStateOf(initial / 60) }
    var minute by remember { mutableIntStateOf(initial % 60) }
    var error by remember { mutableStateOf<String?>(null) }

    fun minuteValue(): Int = (hour % 24) * 60 + minute

    fun clearError() {
        error = null
    }

    fun toggleAmPm(am: Boolean) {
        hour = if (am) hour % 12 else (hour % 12) + 12
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        // The custom GrabBar below is the sheet's only drag handle; suppress
        // Material3's default one so the pill isn't duplicated.
        dragHandle = {},
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            GrabBar()
            Text(
                text = stringResource(R.string.sche_time_sheet_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.sche_time_sheet_sub),
                fontSize = 13.sp,
                color = colors.ink2,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
            ) {
                StepperColumn(
                    label = stringResource(R.string.sche_hours),
                    value = h12(hour).toString(),
                    onMinus = {
                        clearError()
                        hour = (hour - 1 + 24) % 24
                    },
                    onPlus = {
                        clearError()
                        hour = (hour + 1) % 24
                    },
                )
                Text(
                    text = ":",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.ink3,
                    // The colon's line box centers on the row while the 30sp value
                    // boxes sit ~14dp lower, so nudge it down to align optically
                    // with the hour/minute digits (measured at 2.75x density).
                    modifier = Modifier.offset(y = 14.dp).padding(horizontal = 12.dp),
                )
                StepperColumn(
                    label = stringResource(R.string.sche_minutes),
                    value = minute.toString().padStart(2, '0'),
                    onMinus = {
                        clearError()
                        minute = (minute - 1 + 60) % 60
                    },
                    onPlus = {
                        clearError()
                        minute = (minute + 1) % 60
                    },
                )
            }

            val isAm = hour < 12
            val timeError = stringResource(R.string.sche_toast_time)
            Segmented(
                options = listOf(
                    stringResource(R.string.sche_ampm_am) to isAm,
                    stringResource(R.string.sche_ampm_pm) to !isAm,
                ),
                onSelect = { index ->
                    clearError()
                    toggleAmPm(index == 0)
                },
                modifier = Modifier
                    .widthIn(max = 200.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 14.dp),
            )

            error?.let { message ->
                Text(
                    text = message,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.danger,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 12.dp),
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SecondaryButton(
                    text = stringResource(R.string.sche_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                PrimaryPill(
                    text = stringResource(R.string.sche_done),
                    onClick = {
                        val value = minuteValue()
                        val ok = if (target == TimeTarget.START) {
                            isValidFocusWindow(value, endMinute)
                        } else {
                            isValidFocusWindow(startMinute, value)
                        }
                        if (!ok) {
                            error = timeError
                            return@PrimaryPill
                        }
                        onDone(value)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StepperColumn(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            color = colors.ink3,
        )
        Spacer(Modifier.height(10.dp))
        StepButton(text = "−", onClick = onMinus)
        Spacer(Modifier.height(10.dp))
        Text(
            text = value,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            color = colors.ink,
            // Prototype `.time-val` uses text-align:center so the digit stays
            // centered between the −/+ buttons for any width/value.
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 56.dp),
        )
        Spacer(Modifier.height(10.dp))
        StepButton(text = "+", onClick = onPlus)
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.brandSoft)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = colors.brandDark,
        )
    }
}

/**
 * Prototype `sheetApps`: search field + multi-select app rows (icon box +
 * label + 24dp checkbox) + block Done. Done reports the selected count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(
    apps: List<InstalledApp>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onDone: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    // Search-then-group: categories stay in fixed order, empty groups hidden.
    val groups = remember(apps, query) { AppCatalog.groupApps(apps, query) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        // Custom GrabBar is the only drag handle; see TimePickerSheet.
        dragHandle = {},
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            GrabBar()
            Text(
                text = title ?: stringResource(R.string.sche_apps_sheet_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle ?: stringResource(R.string.sche_apps_sheet_sub),
                fontSize = 13.sp,
                color = colors.ink2,
            )
            Spacer(Modifier.height(16.dp))
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.sche_apps_search),
            )
            Spacer(Modifier.height(14.dp))
            SelectAllRow(
                selectedCount = selected.size,
                onSelectAll = onSelectAll,
                onDeselectAll = onDeselectAll,
            )
            Spacer(Modifier.height(14.dp))
            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.sche_apps_empty),
                        fontSize = 14.sp,
                        color = colors.ink3,
                    )
                }
            } else {
                // Bounded height so the list scrolls internally and the Done
                // button stays visible above the fold on every screen size.
                // Each category gets its own bordered box (rendered by
                // GroupedAppPickerList), so no container styling is needed here.
                GroupedAppPickerList(
                    groups = groups,
                    selected = selected,
                    onToggle = onToggle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                ) { app, checked, onToggleApp ->
                    AppRow(
                        app = app,
                        checked = checked,
                        onToggle = onToggleApp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryPill(
                text = stringResource(R.string.sche_done),
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val d = context.packageManager.getApplicationIcon(app.packageName)
                (d as? BitmapDrawable)?.bitmap?.asImageBitmap()
                    ?: run {
                        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.TRANSPARENT)
                        d.setBounds(0, 0, 64, 64)
                        d.draw(android.graphics.Canvas(bmp))
                        bmp.asImageBitmap()
                    }
            }.getOrNull()
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(colors.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon!!,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Text(
                    text = app.label.firstOrNull()?.uppercase() ?: "?",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.brandDark,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                fontSize = 12.sp,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        CheckBox(checked = checked)
    }
}

/** Prototype `.checkbox`: 24dp, radius 8, brand when on. */
@Composable
private fun CheckBox(checked: Boolean) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked) colors.brand else Color.Transparent)
            .border(
                2.dp,
                if (checked) colors.brand else colors.ink3,
                RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = FocusCheckIcon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Color.White,
            )
        }
    }
}

/**
 * Prototype `.field`: surface, 14 radius, 48dp, search icon. The BasicTextField
 * fills the whole remaining width and the row itself is tap-to-focus, so tapping
 * anywhere in the field (icon, padding, empty space) focuses the input instead of
 * only the narrow text extent.
 */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
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
            imageVector = FocusSearchIcon,
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

/** Prototype `.btn.sm.btn-secondary`: 38dp pills, one per bulk action. */
@Composable
private fun SelectAllRow(selectedCount: Int, onSelectAll: () -> Unit, onDeselectAll: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SelectAllButton(
            text = stringResource(R.string.picker_select_all, selectedCount),
            onClick = onSelectAll,
            modifier = Modifier.weight(1f),
        )
        SelectAllButton(
            text = stringResource(R.string.picker_deselect_all, selectedCount),
            onClick = onDeselectAll,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SelectAllButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(CircleShape)
            .background(colors.brandSoft)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.brandDark,
        )
    }
}

@Composable
private fun GrabBar() {
    val colors = LocalAppColors.current
    Box(
        // Prototype `.grab { margin: 6px auto 16px }`.
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 5.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.line),
        )
    }
}

/** Prototype `.btn-primary` pill (52dp, brand, soft shadow). */
@Composable
internal fun PrimaryPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(colors.brand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

/** Prototype `.btn-secondary` pill (surface + line border). */
@Composable
internal fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(colors.surface)
            .border(1.dp, colors.line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink2,
        )
    }
}

/** Prototype `.seg` segmented control. */
@Composable
internal fun Segmented(
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, (label, selected) ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) colors.brand else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) Color.White else colors.ink2,
                )
            }
        }
    }
}
