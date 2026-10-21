package com.safeme.app.ui.screens.applock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safeme.app.R
import com.safeme.app.data.AutoLockDelay
import com.safeme.app.data.LockType
import com.safeme.app.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The prototype's `sheetLockSetup` wizard: 3 steps (method → create →
 * confirm) with step dots, per-method inputs (keypad / password field /
 * pattern grid), a mismatch shake, and a success check overlay before
 * [onSave] fires.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockSetupSheet(
    initialMethod: LockType,
    onDismiss: () -> Unit,
    onSave: (LockType, String) -> Unit,
    onError: (String) -> Unit = {},
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var step by remember { mutableIntStateOf(1) }
    var method by remember {
        mutableStateOf(if (initialMethod == LockType.OFF) LockType.PIN else initialMethod)
    }

    var pin by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf<List<Int>>(emptyList()) }
    var patternConfirm by remember { mutableStateOf<List<Int>>(emptyList()) }

    var shake by remember { mutableStateOf(false) }
    var showOk by remember { mutableStateOf(false) }
    val okScale = remember { Animatable(0.4f) }

    fun code(stage: Int): String = when (method) {
        LockType.PIN -> if (stage == 2) pin else pinConfirm
        LockType.PASSWORD -> if (stage == 2) password else passwordConfirm
        LockType.PATTERN ->
            (if (stage == 2) pattern else patternConfirm).joinToString("-")
        LockType.OFF -> ""
    }

    fun valid(stage: Int): Boolean = when (method) {
        LockType.PIN -> code(stage).length in 4..6
        LockType.PASSWORD -> code(stage).length >= 4
        LockType.PATTERN -> code(stage).split('-').size >= 4
        LockType.OFF -> false
    }

    fun clearCreate() {
        pin = ""
        password = ""
        pattern = emptyList()
    }

    fun clearConfirm() {
        pinConfirm = ""
        passwordConfirm = ""
        patternConfirm = emptyList()
    }

    fun onConfirm() {
        if (code(2) != code(3)) {
            shake = true
            scope.launch {
                delay(450)
                shake = false
            }
            clearConfirm()
            onError(context.getString(R.string.al_mismatch))
            return
        }
        showOk = true
    }

    // Success check overlay → then hand off to the caller.
    LaunchedEffect(showOk) {
        if (showOk) {
            okScale.snapTo(0.4f)
            okScale.animateTo(1f, animationSpec = tween(400))
            delay(700)
            onSave(method, code(2))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
                    .shakeEffect(shake && step == 3),
            ) {
                GrabBar()
                Steps(active = step)
                Text(
                    text = stringResource(
                        when (step) {
                            1 -> R.string.al_setup_method
                            2 -> R.string.al_setup_create
                            else -> R.string.al_setup_confirm
                        }
                    ),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        when (step) {
                            1 -> R.string.al_setup_method_sub
                            2 -> R.string.al_setup_create_sub
                            else -> R.string.al_setup_confirm_sub
                        }
                    ),
                    fontSize = 13.sp,
                    color = colors.ink2,
                )
                Spacer(Modifier.height(16.dp))

                when (step) {
                    1 -> MethodList(
                        selected = method,
                        onSelect = { method = it },
                    )
                    2 -> CreateInput(
                        method = method,
                        pin = pin,
                        password = password,
                        pattern = pattern,
                        onPin = { d -> if (pin.length < 6) pin += d },
                        onPinDelete = { pin = pin.dropLast(1) },
                        onPassword = { password = it },
                        onPattern = { i ->
                            if (i !in pattern) pattern = pattern + i
                        },
                    )
                    else -> ConfirmInput(
                        method = method,
                        pin = pinConfirm,
                        password = passwordConfirm,
                        pattern = patternConfirm,
                        onPin = { d -> if (pinConfirm.length < 6) pinConfirm += d },
                        onPinDelete = { pinConfirm = pinConfirm.dropLast(1) },
                        onPassword = { passwordConfirm = it },
                        onPattern = { i ->
                            if (i !in patternConfirm) patternConfirm = patternConfirm + i
                        },
                    )
                }

                Spacer(Modifier.height(16.dp))
                when (step) {
                    1 -> PrimaryButton(
                        text = stringResource(R.string.al_continue),
                        enabled = true,
                        onClick = {
                            clearCreate()
                            step = 2
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    2 -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        GhostButton(
                            text = stringResource(R.string.al_back),
                            onClick = { step = 1 },
                            modifier = Modifier.weight(1f),
                        )
                        PrimaryButton(
                            text = stringResource(R.string.al_continue),
                            enabled = valid(2),
                            onClick = {
                                clearConfirm()
                                step = 3
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    else -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        GhostButton(
                            text = stringResource(R.string.al_back),
                            onClick = { step = 2 },
                            modifier = Modifier.weight(1f),
                        )
                        PrimaryButton(
                            text = stringResource(R.string.al_enable),
                            enabled = valid(3),
                            onClick = ::onConfirm,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Success overlay (prototype `.setup-ok`): surface cover + pop check.
            if (showOk) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(colors.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AlCheckIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(64.dp)
                            .graphicsLayer {
                                scaleX = okScale.value
                                scaleY = okScale.value
                            },
                        tint = colors.success,
                    )
                }
            }
        }
    }
}

/** The prototype's `sheetAutoLock`: option list + Done. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoLockSheet(
    selected: AutoLockDelay,
    onSelect: (AutoLockDelay) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val options = listOf(
        AutoLockDelay.IMMEDIATELY to (R.string.al_auto_immediately to R.string.al_auto_immediately_sub),
        AutoLockDelay.AFTER_15S to (R.string.al_auto_15s to R.string.al_auto_15s_sub),
        AutoLockDelay.AFTER_30S to (R.string.al_auto_30s to R.string.al_auto_30s_sub),
        AutoLockDelay.AFTER_1M to (R.string.al_auto_1m to R.string.al_auto_1m_sub),
        AutoLockDelay.AFTER_5M to (R.string.al_auto_5m to R.string.al_auto_5m_sub),
        AutoLockDelay.OFF to (R.string.al_auto_off to R.string.al_auto_off_sub),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            GrabBar()
            Text(
                text = stringResource(R.string.al_auto_sheet_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.al_auto_sheet_sub),
                fontSize = 13.sp,
                color = colors.ink2,
            )
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.line, RoundedCornerShape(20.dp)),
            ) {
                options.forEachIndexed { index, (delay, res) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(delay) }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(res.first),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.ink,
                            )
                            Text(
                                text = stringResource(res.second),
                                fontSize = 12.sp,
                                color = colors.ink2,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                        CheckBox(checked = delay == selected)
                    }
                    if (index != options.lastIndex) {
                        HorizontalDivider(color = colors.line)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = stringResource(R.string.al_done),
                enabled = true,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The 40×5dp grab handle every App Lock sheet opens with.
 * `internal` (was `private`) so [AppLockVerifySheet] renders the identical
 * handle instead of duplicating it — COD-79's verify prompt must look like the
 * rest of the App Lock sheets, not like a new component.
 */
@Composable
internal fun GrabBar() {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
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

/** Prototype `.steps`: 8dp dots, active becomes a 24dp brand pill. */
@Composable
private fun Steps(active: Int) {
    val colors = LocalAppColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(if (index < active) 24.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (index < active) colors.brand else colors.line),
            )
        }
    }
}

/** Step 1 — method radio list (prototype `#setM`). */
@Composable
private fun MethodList(
    selected: LockType,
    onSelect: (LockType) -> Unit,
) {
    val colors = LocalAppColors.current
    val options = listOf(
        LockType.PIN to (AlLockIcon to (R.string.al_method_pin to R.string.al_method_pin_sub)),
        LockType.PASSWORD to (AlKeyboardIcon to (R.string.al_method_password to R.string.al_method_password_sub)),
        LockType.PATTERN to (AlPatternIcon to (R.string.al_method_pattern to R.string.al_method_pattern_sub)),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(20.dp)),
    ) {
        options.forEachIndexed { index, (type, entry) ->
            val (icon, res) = entry
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(type) }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(colors.brandSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = colors.brandDark,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(res.first),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink,
                    )
                    Text(
                        text = stringResource(res.second),
                        fontSize = 12.sp,
                        color = colors.ink2,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                CheckBox(checked = type == selected)
            }
            if (index != options.lastIndex) {
                HorizontalDivider(color = colors.line)
            }
        }
    }
}

/** Step 2 / 3 — method-specific input. */
@Composable
private fun CreateInput(
    method: LockType,
    pin: String,
    password: String,
    pattern: List<Int>,
    onPin: (Char) -> Unit,
    onPinDelete: () -> Unit,
    onPassword: (String) -> Unit,
    onPattern: (Int) -> Unit,
) {
    MethodInput(
        method = method,
        pin = pin,
        password = password,
        pattern = pattern,
        hintRes = R.string.al_pin_hint,
        passwordPlaceholder = R.string.al_password_placeholder,
        patternHintRes = R.string.al_pattern_hint,
        onPin = onPin,
        onPinDelete = onPinDelete,
        onPassword = onPassword,
        onPattern = onPattern,
    )
}

@Composable
private fun ConfirmInput(
    method: LockType,
    pin: String,
    password: String,
    pattern: List<Int>,
    onPin: (Char) -> Unit,
    onPinDelete: () -> Unit,
    onPassword: (String) -> Unit,
    onPattern: (Int) -> Unit,
) {
    MethodInput(
        method = method,
        pin = pin,
        password = password,
        pattern = pattern,
        hintRes = R.string.al_pin_reenter,
        passwordPlaceholder = R.string.al_password_reenter_placeholder,
        patternHintRes = R.string.al_pattern_reenter,
        onPin = onPin,
        onPinDelete = onPinDelete,
        onPassword = onPassword,
        onPattern = onPattern,
    )
}

@Composable
private fun MethodInput(
    method: LockType,
    pin: String,
    password: String,
    pattern: List<Int>,
    hintRes: Int,
    passwordPlaceholder: Int,
    patternHintRes: Int,
    onPin: (Char) -> Unit,
    onPinDelete: () -> Unit,
    onPassword: (String) -> Unit,
    onPattern: (Int) -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (method) {
            LockType.PIN -> {
                Text(
                    text = stringResource(hintRes),
                    fontSize = 13.sp,
                    color = colors.ink2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                PinDots(filled = pin.length)
                PinKeypad(
                    onDigit = onPin,
                    onDelete = onPinDelete,
                )
            }
            LockType.PASSWORD -> {
                Spacer(Modifier.height(16.dp))
                PasswordField(
                    value = password,
                    onValueChange = onPassword,
                    placeholder = stringResource(passwordPlaceholder),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.al_password_note),
                    fontSize = 12.sp,
                    color = colors.ink2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LockType.PATTERN -> {
                PatternGrid(
                    selected = pattern,
                    onDotTap = onPattern,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(patternHintRes),
                    fontSize = 13.sp,
                    color = colors.ink2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LockType.OFF -> Unit
        }
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
                imageVector = AlCheckIcon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Color.White,
            )
        }
    }
}

/** Prototype `.btn-primary` (52dp pill, brand, soft shadow; disabled dimmed). */
@Composable
private fun PrimaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .height(52.dp)
            .shadow(
                elevation = if (enabled) 8.dp else 0.dp,
                shape = CircleShape,
                ambientColor = colors.brand.copy(alpha = 0.35f),
                spotColor = colors.brand.copy(alpha = 0.35f),
            )
            .clip(CircleShape)
            .background(if (enabled) colors.brand else colors.brand.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick),
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

/**
 * Prototype `.btn-ghost`: transparent, ink-2 text.
 * `internal` (was `private`) so [AppLockVerifySheet] can reuse the project's
 * cancel affordance rather than inventing a second one.
 */
@Composable
internal fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink2,
        )
    }
}
