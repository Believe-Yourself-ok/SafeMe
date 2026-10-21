package com.safeme.app.ui.screens.blockscreen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safeme.app.R
import com.safeme.app.ui.components.UnsavedChangesDialog
import com.safeme.app.ui.components.blurredShadow
import com.safeme.app.ui.screens.permissions.ChevronIcon
import com.safeme.app.ui.theme.LocalAppColors
import com.safeme.app.ui.theme.SerifFamily
import kotlinx.coroutines.launch

@Composable
fun BlockScreen(onBack: () -> Unit) {
    val vm: BlockScreenViewModel = viewModel()
    val dwell by vm.dwell.collectAsState()
    val message by vm.message.collectAsState()
    val img by vm.img.collectAsState()
    val whyOn by vm.whyOn.collectAsState()

    var showImgSheet by remember { mutableStateOf(false) }
    var showMsgSheet by remember { mutableStateOf(false) }
    var showOverlay by remember { mutableStateOf(false) }

    // COD-81: guard user-initiated exits (chevron + system Back) while the
    // working copy holds unsaved changes. A successful Save Changes refreshes
    // the baseline, so a later exit is clean and never warns.
    val hasUnsavedChanges by vm.hasUnsavedChanges.collectAsState()
    var showUnsavedDialog by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = hasUnsavedChanges) { showUnsavedDialog = true }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val colors = LocalAppColors.current

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HeaderRow(
                    onBack = {
                        if (hasUnsavedChanges) showUnsavedDialog = true else onBack()
                    },
                )

                PreviewCard(dwell, message, img)

                GroupLabel(stringResource(R.string.bs_customization))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.line, RoundedCornerShape(20.dp))
                ) {
                    ListRow(
                        icon = ImageIcon,
                        title = stringResource(R.string.bs_motivation_image),
                        sub = if (img.isEmpty()) {
                            stringResource(R.string.bs_img_sub_none)
                        } else {
                            context.getString(R.string.bs_img_sub_set, img)
                        },
                        actionLabel = stringResource(R.string.bs_choose),
                        action = { showImgSheet = true }
                    )
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                    ListRow(
                        icon = MessageIcon,
                        title = stringResource(R.string.bs_custom_message),
                        sub = "\u201C$message\u201D",
                        actionLabel = stringResource(R.string.bs_edit),
                        action = { showMsgSheet = true }
                    )
                }

                GroupLabel(stringResource(R.string.bs_close_gate))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.line, RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.bs_dwell),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.ink
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.bs_dwell_sub),
                                fontSize = 12.5.sp,
                                color = colors.ink2
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SecondarySmallButton("-") { vm.stepDwell(-1) }
                            Text(
                                text = "${dwell}s",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.ink,
                                modifier = Modifier.widthIn(min = 34.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            SecondarySmallButton("+") { vm.stepDwell(1) }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.line, RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.bs_why),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.ink
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.bs_why_sub),
                                fontSize = 12.5.sp,
                                color = colors.ink2
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        CustomSwitch(checked = whyOn) { vm.toggleWhy() }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // The only commit point for the whole screen: saves every
                // setting shown above in one atomic DataStore write. Edits are
                // live in the working copy/preview but are discarded when the
                // user leaves without tapping Save.
                PrimaryBlockButton(stringResource(R.string.bs_save_changes)) {
                    scope.launch {
                        val ok = vm.save()
                        Toast.makeText(
                            context,
                            if (ok) R.string.bs_toast_changes_saved else R.string.bs_toast_save_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                PrimaryBlockButton(stringResource(R.string.bs_preview_block)) { showOverlay = true }

                Spacer(modifier = Modifier.height(6.dp))

                GhostBlockButton(stringResource(R.string.bs_rate)) {
                    Toast.makeText(context, R.string.bs_toast_rate, Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (showOverlay) {
            BlockOverlay(
                dwell = dwell,
                msg = message,
                whyOn = whyOn,
                onClose = {
                    showOverlay = false
                    Toast.makeText(context, R.string.bs_toast_back_app, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    if (showImgSheet) {
        MotivationImageSheet(
            current = img,
            onCancel = { showImgSheet = false },
            onDone = { value ->
                showImgSheet = false
                vm.setImg(value)
                val text = if (value.isEmpty()) {
                    context.getString(R.string.bs_toast_img_none)
                } else {
                    context.getString(R.string.bs_toast_img_set, value)
                }
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showMsgSheet) {
        CustomMessageSheet(
            current = message,
            onCancel = { showMsgSheet = false },
            onSave = { value ->
                showMsgSheet = false
                vm.setMessage(value)
                Toast.makeText(
                    context, R.string.bs_toast_msg_saved, Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    if (showUnsavedDialog) {
        UnsavedChangesDialog(
            onKeepEditing = { showUnsavedDialog = false },
            onSaveAndExit = {
                showUnsavedDialog = false
                scope.launch {
                    // Same persistence + feedback as the Save Changes button
                    // (android Toast survives navigation); only a successful
                    // save performs the requested exit.
                    val ok = vm.save()
                    Toast.makeText(
                        context,
                        if (ok) R.string.bs_toast_changes_saved else R.string.bs_toast_save_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                    if (ok) onBack()
                }
            },
            onDiscardChanges = {
                showUnsavedDialog = false
                onBack()
            },
        )
    }

}

@Composable
private fun HeaderRow(onBack: () -> Unit) {
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
                .background(colors.surface)
                .border(1.dp, colors.line, RoundedCornerShape(14.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = ChevronIcon,
                contentDescription = stringResource(R.string.perm_back),
                modifier = Modifier.size(20.dp),
                tint = colors.ink
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.bs_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            color = colors.ink
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.bs_header_sub),
            fontSize = 12.sp,
            lineHeight = 20.sp,
            color = colors.ink2
        )
    }
}

@Composable
private fun PreviewCard(dwell: Int, message: String, img: String) {
    val density = LocalDensity.current
    val colors = LocalAppColors.current
    val logoBrush = remember(density, colors.brandDark, colors.brand) {
        with(density) {
            Brush.linearGradient(
                colors = listOf(colors.brandDark, colors.brand),
                start = androidx.compose.ui.geometry.Offset.Zero,
                end = androidx.compose.ui.geometry.Offset(52.dp.toPx(), 52.dp.toPx())
            )
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val brush = with(LocalDensity.current) {
            Brush.linearGradient(
                colors = listOf(colors.cardDark1, colors.cardDark2),
                start = androidx.compose.ui.geometry.Offset.Zero,
                end = androidx.compose.ui.geometry.Offset(maxWidth.toPx(), (maxHeight * 0.36f).toPx())
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(brush)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.bs_preview).uppercase(),
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold,
                color = colors.previewLabel
            )
            if (img.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                bsImgColors(img)?.let { colors ->
                    GradientTile(
                        colors = colors,
                        modifier = Modifier.size(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(logoBrush),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = ShieldCheckIcon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.bs_site_blocked),
                fontFamily = SerifFamily,
                fontSize = 20.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                fontSize = 12.5.sp,
                color = colors.previewMsg,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.bs_closing, dwell),
                fontSize = 12.sp,
                color = colors.iconDarkFg
            )
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.5.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.6.sp,
        color = LocalAppColors.current.ink3,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, end = 2.dp, top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun ColumnScope.ListRow(
    icon: ImageVector,
    title: String,
    sub: String,
    actionLabel: String,
    action: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(colors.brandSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.brandDark
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sub,
                fontSize = 12.sp,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        SecondarySmallButton(actionLabel, action)
    }
}

@Composable
private fun SecondarySmallButton(text: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .height(38.dp)
            .clip(CircleShape)
            .background(colors.brandSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = colors.brandDark)
    }
}

@Composable
private fun CustomSwitch(checked: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 31.dp)
            .clip(CircleShape)
            .background(if (checked) colors.brand else colors.swOff)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .padding(3.dp)
                .size(25.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
        )
    }
}

@Composable
private fun PrimaryBlockButton(text: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .blurredShadow(
                cornerRadius = 26.dp,
                color = colors.brand.copy(alpha = 0.35f),
                blurRadius = 20.dp,
                offsetY = 8.dp
            )
            .clip(CircleShape)
            .background(colors.brand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

@Composable
private fun GhostBlockButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = LocalAppColors.current.ink2)
    }
}

@Composable
fun GradientTile(colors: List<Color>, modifier: Modifier, shape: Shape) {
    BoxWithConstraints(modifier = modifier) {
        val brush = with(LocalDensity.current) {
            Brush.linearGradient(
                colors = colors,
                start = androidx.compose.ui.geometry.Offset.Zero,
                end = androidx.compose.ui.geometry.Offset(maxWidth.toPx(), maxHeight.toPx())
            )
        }
        Box(modifier = Modifier.matchParentSize().clip(shape).background(brush))
    }
}

fun bsImgColors(name: String): List<Color>? = when (name) {
    "sunset" -> listOf(Color(0xFFF4A25B), Color(0xFFE2545B), Color(0xFF7C3F8C))
    "ocean" -> listOf(Color(0xFF1B3B5C), Color(0xFF2C7FBE), Color(0xFF8FD3F4))
    "forest" -> listOf(Color(0xFF0E3B2E), Color(0xFF1E7A54), Color(0xFFA8E6C4))
    "aurora" -> listOf(Color(0xFF172554), Color(0xFF5B2A86), Color(0xFF34D399))
    "mono" -> listOf(Color(0xFF171310), Color(0xFF2A211B), Color(0xFF6B5D4F))
    "peak" -> listOf(Color(0xFF3A2C1F), Color(0xFFB07A4B), Color(0xFFF4D9B8))
    else -> null
}
