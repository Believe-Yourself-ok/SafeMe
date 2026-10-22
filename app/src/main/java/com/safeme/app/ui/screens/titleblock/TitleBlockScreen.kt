package com.safeme.app.ui.screens.titleblock

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.collectAsState
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safeme.app.R
import com.safeme.app.data.TitleBlockRule
import com.safeme.app.data.TitleMatchMode
import com.safeme.app.ui.components.SafeMeTextField
import com.safeme.app.ui.components.ToastHost
import com.safeme.app.ui.screens.blocking.BlkChevronRightIcon
import com.safeme.app.ui.screens.blocking.BlkCrossIcon
import com.safeme.app.ui.screens.blocking.BlkMenuIcon
import com.safeme.app.ui.screens.blockscreen.InfoIcon
import com.safeme.app.ui.screens.common.ProtectedActionHost
import com.safeme.app.ui.screens.keywords.KwSearchIcon
import com.safeme.app.ui.screens.keywords.KwTrashIcon
import com.safeme.app.ui.screens.permissions.ChevronIcon
import com.safeme.app.ui.theme.LocalAppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleBlockScreen(
    onBack: () -> Unit,
    viewModel: TitleBlockViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalAppColors.current

    var sheetRule by remember { mutableStateOf<TitleBlockRule?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Header(
                title = stringResource(R.string.tb_title),
                subtitle = stringResource(R.string.tb_subtitle),
                onBack = onBack,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                HeroCard(rules = state.rules, activeCount = state.activeCount)
                Spacer(Modifier.height(16.dp))
                SearchField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = stringResource(R.string.tb_search_placeholder),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.tb_rules_label).uppercase(),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.6.sp,
                    color = colors.ink3,
                    modifier = Modifier.padding(start = 2.dp),
                )
                Spacer(Modifier.height(8.dp))

                if (state.rules.isEmpty()) {
                    EmptyCard()
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.filteredRules.forEach { rule ->
                            RuleRow(
                                rule = rule,
                                onToggle = { viewModel.toggleRule(rule.id, it) },
                                onEdit = { sheetRule = rule },
                            )
                        }
                    }
                }

                if (state.query.isNotBlank() && state.filteredRules.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.tb_search_no_match),
                        fontSize = 13.sp,
                        color = colors.ink2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(CircleShape)
                        .background(colors.brandSoft)
                        .clickable { sheetRule = TitleBlockRule("", "", TitleMatchMode.CONTAINS, true) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.tb_add_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.brandDark,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = InfoIcon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp),
                        tint = colors.brand,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.tb_note),
                        fontSize = 12.sp,
                        color = colors.ink2,
                        lineHeight = 18.sp,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        ToastHost(
            flow = viewModel.toasts,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        )

        if (sheetRule != null) {
            val editing = sheetRule?.value?.isNotBlank() == true
            var value by rememberSaveable(sheetRule?.value) { mutableStateOf(sheetRule?.value.orEmpty()) }
            var modeName by rememberSaveable(sheetRule?.value) {
                mutableStateOf((sheetRule?.mode ?: TitleMatchMode.CONTAINS).name)
            }
            val mode = runCatching { TitleMatchMode.valueOf(modeName) }
                .getOrDefault(TitleMatchMode.CONTAINS)

            ModalBottomSheet(
                onDismissRequest = { sheetRule = null },
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
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 40.dp, height = 5.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.line)
                        )
                    }
                    Text(
                        text = stringResource(if (editing) R.string.tb_sheet_edit else R.string.tb_sheet_add),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.ink,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.tb_sheet_subtitle),
                        fontSize = 13.sp,
                        color = colors.ink2,
                    )
                    Spacer(Modifier.height(16.dp))
                    SheetField(
                        value = value,
                        onValueChange = { value = it },
                        placeholder = stringResource(R.string.tb_sheet_field_placeholder),
                        icon = BlkCrossIcon,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.tb_sheet_match_label).uppercase(),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.6.sp,
                        color = colors.ink3,
                    )
                    Spacer(Modifier.height(10.dp))
                    Seg(
                        options = listOf(
                            TitleMatchMode.CONTAINS to stringResource(R.string.tb_mode_contains),
                            TitleMatchMode.EXACT to stringResource(R.string.tb_mode_exact),
                            TitleMatchMode.STARTS_WITH to stringResource(R.string.tb_mode_starts_with),
                        ),
                        selected = mode,
                        onSelect = { modeName = it.name },
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.brandSoft)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = InfoIcon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = colors.brandDark,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.tb_sheet_scope_note),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.brandDark,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(CircleShape)
                                .background(colors.surface)
                                .border(1.dp, colors.line, CircleShape)
                                .clickable { sheetRule = null },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.kw_sheet_cancel),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.ink2,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(CircleShape)
                                .background(colors.brand)
                                .clickable {
                                    if (editing) {
                                        sheetRule?.id?.let { viewModel.updateRule(it, value, mode) }
                                    } else {
                                        viewModel.addRule(value, mode)
                                    }
                                    sheetRule = null
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(if (editing) R.string.kw_sheet_save else R.string.tb_sheet_add),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                    if (editing) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(CircleShape)
                                .clickable {
                                    sheetRule?.id?.let { viewModel.deleteRule(it) }
                                    sheetRule = null
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = KwTrashIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = colors.danger,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.tb_sheet_delete),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.danger,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // COD-79: prompts before a title rule can be switched off or deleted.
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

@Composable
private fun HeroCard(rules: List<TitleBlockRule>, activeCount: Int) {
    val colors = LocalAppColors.current
    val hasRules = rules.isNotEmpty()
    val allActive = hasRules && activeCount == rules.size

    val title = when {
        !hasRules -> stringResource(R.string.tb_hero_title_empty)
        activeCount > 0 -> stringResource(R.string.tb_hero_title_on)
        else -> stringResource(R.string.tb_hero_title_paused)
    }
    val tag = when {
        !hasRules -> stringResource(R.string.tb_hero_tag_setup)
        activeCount > 0 -> stringResource(R.string.tb_hero_tag_active)
        else -> stringResource(R.string.tb_hero_tag_setup)
    }
    val sub = when {
        !hasRules -> stringResource(R.string.tb_hero_sub_empty)
        else -> pluralStringResource(R.plurals.tb_hero_sub, rules.size, rules.size, activeCount)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(colors.brandDark, colors.brand, Color(0xFFE8A07E))
                )
            )
            .padding(22.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = BlkCrossIcon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = Color.White,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = tag.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = title,
                fontSize = 27.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                text = sub,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val colors = LocalAppColors.current
    val focusRequester = remember { FocusRequester() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
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
            imageVector = KwSearchIcon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colors.ink3,
        )
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(text = placeholder, fontSize = 14.sp, color = colors.ink3)
            }
            SafeMeTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(fontSize = 14.sp, color = colors.ink),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
    }
}

@Composable
private fun EmptyCard() {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(18.dp))
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = BlkCrossIcon,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = colors.brand,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.tb_empty_title),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = colors.ink,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.tb_empty_subtitle),
            fontSize = 12.sp,
            color = colors.ink2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RuleRow(
    rule: TitleBlockRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(colors.iconDarkBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = BlkMenuIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.iconDarkFg,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rule.value.replaceFirstChar { it.uppercase() },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = modeLabel(rule.mode),
                fontSize = 11.5.sp,
                color = colors.ink2,
            )
        }
        Switch(checked = rule.enabled, onToggle = { onToggle(it) })
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .clickable(onClick = onEdit),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = BlkChevronRightIcon,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = colors.ink2,
            )
        }
    }
}

@Composable
private fun Switch(checked: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    val bg by animateColorAsState(
        targetValue = if (checked) colors.brand else colors.swOff,
        animationSpec = tween(200),
        label = "switchBg",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 21.dp else 0.dp,
        animationSpec = tween(200),
        label = "switchThumb",
    )
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 31.dp)
            .clip(CircleShape)
            .background(bg)
            .semantics { role = Role.Switch }
            .clickable(role = Role.Switch, onClick = { onToggle(!checked) }),
        contentAlignment = Alignment.CenterStart,
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
private fun Seg(
    options: List<Pair<TitleMatchMode, String>>,
    selected: TitleMatchMode,
    onSelect: (TitleMatchMode) -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (mode, label) ->
            val isSelected = mode == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) colors.brand else Color.Transparent)
                    .clickable { onSelect(mode) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) Color.White else colors.ink2,
                )
            }
        }
    }
}

@Composable
private fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
) {
    val colors = LocalAppColors.current
    val focusRequester = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { focusRequester.requestFocus() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = colors.ink3,
            )
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(text = placeholder, fontSize = 14.sp, color = colors.ink3)
                }
                SafeMeTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = TextStyle(fontSize = 14.sp, color = colors.ink),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
        }
    }
}

@Composable
private fun modeLabel(mode: TitleMatchMode): String = when (mode) {
    TitleMatchMode.CONTAINS -> stringResource(R.string.tb_mode_contains)
    TitleMatchMode.EXACT -> stringResource(R.string.tb_mode_exact)
    TitleMatchMode.STARTS_WITH -> stringResource(R.string.tb_mode_starts_with)
}

