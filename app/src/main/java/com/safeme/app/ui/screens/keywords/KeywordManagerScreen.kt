package com.safeme.app.ui.screens.keywords

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safeme.app.R
import com.safeme.app.data.BlockedCategory
import com.safeme.app.data.normalizeDomain
import com.safeme.app.ui.components.SafeMeTextField
import com.safeme.app.ui.components.ToastHost
import com.safeme.app.ui.screens.blocking.BlkGlobeIcon
import com.safeme.app.ui.screens.blocking.BlkSquarePlusIcon
import com.safeme.app.ui.screens.common.ProtectedActionHost
import com.safeme.app.ui.screens.permissions.ChevronIcon
import com.safeme.app.ui.theme.LocalAppColors

private const val TYPE_KEYWORDS = "keywords"
private const val TYPE_WEBSITES = "websites"
private const val TAB_BLOCKLIST = "blocklist"
private const val TAB_WHITELIST = "whitelist"
private const val TAB_BLOCKED = "blocked"
private const val TAB_TRUSTED = "trusted"

private sealed interface SheetConfig {
    data class Keyword(val editing: String? = null, val whitelist: Boolean = false) : SheetConfig
    data class Website(val editing: String? = null, val trusted: Boolean = false) : SheetConfig
}

@Composable
fun KeywordManagerScreen(
    initialType: String,
    initialTab: String,
    onBack: () -> Unit,
    viewModel: KeywordManagerViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalAppColors.current
    val context = LocalContext.current

    var type by rememberSaveable { mutableStateOf(initialType) }
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var query by rememberSaveable { mutableStateOf("") }
    var sheet by remember { mutableStateOf<SheetConfig?>(null) }

    LaunchedEffect(initialType, initialTab) {
        type = initialType
        tab = initialTab
    }

    val effectiveTab = when (type) {
        TYPE_WEBSITES ->
            if (tab == TAB_BLOCKED || tab == TAB_TRUSTED) tab else TAB_BLOCKED
        else ->
            if (tab == TAB_BLOCKLIST || tab == TAB_WHITELIST) tab else TAB_BLOCKLIST
    }

    val filteredKeywords =
        state.blocklistKeywords.filter { it.value.contains(query, ignoreCase = true) }
    val filteredWhitelist =
        state.whitelistKeywords.filter { it.contains(query, ignoreCase = true) }
    val filteredWebsites =
        state.blockedWebsites.filter { it.domain.contains(query, ignoreCase = true) }
    val filteredTrusted =
        state.trustedWebsites.filter { it.contains(query, ignoreCase = true) }

    val showWhitelistTab = type == TYPE_KEYWORDS && effectiveTab == TAB_WHITELIST
    val showTrustedTab = type == TYPE_WEBSITES && effectiveTab == TAB_TRUSTED

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            SubHeader(title = stringResource(R.string.kw_title), onBack = onBack)
            Spacer(Modifier.height(18.dp))

            Seg(
                options = listOf(
                    TYPE_KEYWORDS to stringResource(R.string.kw_seg_keywords),
                    TYPE_WEBSITES to stringResource(R.string.kw_seg_websites),
                ),
                selected = type,
                onSelect = { type = it; query = "" },
            )
            Spacer(Modifier.height(12.dp))

            val tabOptions = if (type == TYPE_WEBSITES) {
                listOf(
                    TAB_BLOCKED to stringResource(R.string.kw_tab_blocked),
                    TAB_TRUSTED to stringResource(R.string.kw_tab_trusted),
                )
            } else {
                listOf(
                    TAB_BLOCKLIST to stringResource(R.string.kw_tab_blocklist),
                    TAB_WHITELIST to stringResource(R.string.kw_tab_whitelist),
                )
            }
            Seg(options = tabOptions, selected = effectiveTab, onSelect = { tab = it })

            if (showWhitelistTab || showTrustedTab) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        if (showTrustedTab) R.string.kw_trusted_note else R.string.kw_whitelist_note
                    ),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colors.ink2,
                )
            }

            Spacer(Modifier.height(14.dp))
            SearchField(
                placeholder = stringResource(
                    if (type == TYPE_KEYWORDS) R.string.kw_search_keywords
                    else R.string.kw_search_websites
                ),
                value = query,
                onValueChange = { query = it },
            )
            Spacer(Modifier.height(14.dp))

            Box(modifier = Modifier.weight(1f)) {
                if (type == TYPE_WEBSITES) {
                    if (showTrustedTab) {
                        if (filteredTrusted.isEmpty()) {
                            EmptyState(stringResource(R.string.kw_empty_trusted))
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(filteredTrusted, key = { "t|$it" }) { domain ->
                                    EntryRow(
                                        value = domain,
                                        sub = stringResource(R.string.kw_sub_trusted),
                                        onEdit = {
                                            sheet = SheetConfig.Website(
                                                editing = domain, trusted = true
                                            )
                                        },
                                        onRemove = { viewModel.removeTrusted(domain) },
                                    )
                                    Spacer(Modifier.height(10.dp))
                                }
                            }
                        }
                    } else if (filteredWebsites.isEmpty()) {
                        EmptyState(stringResource(R.string.kw_empty_blocked))
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(filteredWebsites, key = { "w|${it.domain}" }) { site ->
                                EntryRow(
                                    value = site.domain,
                                    sub = stringResource(R.string.kw_blocked_suffix, site.category.label),
                                    onEdit = {
                                        sheet = SheetConfig.Website(editing = site.domain, trusted = false)
                                    },
                                    onRemove = { viewModel.removeWebsite(site.domain) },
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                } else {
                    if (showWhitelistTab) {
                        if (filteredWhitelist.isEmpty()) {
                            EmptyState(stringResource(R.string.kw_empty_whitelist))
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(filteredWhitelist, key = { "wl|$it" }) { word ->
                                    EntryRow(
                                        value = word,
                                        sub = stringResource(R.string.kw_sub_whitelist),
                                        onEdit = {
                                            sheet = SheetConfig.Keyword(editing = word, whitelist = true)
                                        },
                                        onRemove = { viewModel.removeWhitelist(word) },
                                    )
                                    Spacer(Modifier.height(10.dp))
                                }
                            }
                        }
                    } else if (filteredKeywords.isEmpty()) {
                        EmptyState(stringResource(R.string.kw_empty_blocklist))
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(filteredKeywords, key = { "k|${it.value}" }) { kw ->
                                EntryRow(
                                    value = kw.value,
                                    sub = stringResource(R.string.kw_blocked_suffix, kw.category.label),
                                    onEdit = {
                                        sheet = SheetConfig.Keyword(editing = kw.value, whitelist = false)
                                    },
                                    onRemove = { viewModel.removeKeyword(kw.value) },
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(CircleShape)
                        .background(colors.brand)
                        .clickable {
                            sheet = when {
                                showWhitelistTab && type == TYPE_KEYWORDS ->
                                    SheetConfig.Keyword(editing = null, whitelist = true)
                                showTrustedTab ->
                                    SheetConfig.Website(editing = null, trusted = true)
                                type == TYPE_KEYWORDS ->
                                    SheetConfig.Keyword(editing = null, whitelist = false)
                                else ->
                                    SheetConfig.Website(editing = null, trusted = false)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = BlkSquarePlusIcon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color.White,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (type == TYPE_KEYWORDS) R.string.kw_add_keyword
                                else R.string.kw_add_website
                            ),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .height(50.dp)
                        .clip(CircleShape)
                        .background(colors.surface)
                        .border(1.dp, colors.line, CircleShape)
                        .clickable { viewModel.resetCustom() }
                        .padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.kw_reset),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink2,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        ToastHost(flow = viewModel.toasts)

        when (val config = sheet) {
            is SheetConfig.Keyword -> KeywordSheet(
                config = config,
                initialCategory = categoryOf(state, config.editing, isKeyword = true),
                onSave = { input, cat ->
                    saveKeyword(context, viewModel, state, config, input, cat)
                    sheet = null
                },
                onDismiss = { sheet = null },
            )
            is SheetConfig.Website -> WebsiteSheet(
                config = config,
                initialCategory = categoryOf(state, config.editing, isKeyword = false),
                onSave = { input, cat ->
                    saveWebsite(context, viewModel, state, config, input, cat)
                    sheet = null
                },
                onDismiss = { sheet = null },
            )
            null -> Unit
        }
    }

    // COD-79: prompts before a blocklist entry — or the whole blocklist, via
    // "Reset" — can be deleted.
    ProtectedActionHost(viewModel)
}

@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
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
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = ChevronIcon,
                contentDescription = null,
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
            text = stringResource(R.string.kw_header_sub),
            fontSize = 12.sp,
            lineHeight = 20.sp,
            color = colors.ink2,
        )
    }
}

@Composable
private fun Seg(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
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
        options.forEach { (key, label) ->
            val isSelected = key == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) colors.brand else Color.Transparent)
                    .clickable { onSelect(key) },
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
private fun SearchField(placeholder: String, value: String, onValueChange: (String) -> Unit) {
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
private fun EntryRow(
    value: String,
    sub: String,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
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
                .background(colors.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = BlkGlobeIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.brandDark,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(text = sub, fontSize = 11.5.sp, color = colors.ink2)
        }
        ActionButton(
            icon = KwEditIcon,
            bg = colors.brandSoft,
            tint = colors.brandDark,
            onClick = onEdit,
        )
        Spacer(Modifier.width(8.dp))
        ActionButton(
            icon = KwTrashIcon,
            bg = colors.dangerBg,
            tint = colors.danger,
            onClick = onRemove,
        )
    }
}

@Composable
private fun ActionButton(icon: ImageVector, bg: Color, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = tint,
        )
    }
}

@Composable
private fun EmptyState(text: String) {
    val colors = LocalAppColors.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = colors.ink3,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun KeywordSheet(
    config: SheetConfig.Keyword,
    initialCategory: BlockedCategory,
    onSave: (String, BlockedCategory) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(config.editing.orEmpty()) }
    var category by remember { mutableStateOf(initialCategory) }
    val title = stringResource(
        if (config.editing != null) R.string.kw_sheet_edit_keyword else R.string.kw_sheet_add_keyword
    )
    SheetBase(title = title, onDismiss = onDismiss) {
        if (!config.whitelist) {
            CategoryPicker(selected = category, onSelect = { category = it })
            Spacer(Modifier.height(16.dp))
        }
        SheetField(
            value = input,
            onValueChange = { input = it },
            placeholder = stringResource(R.string.kw_sheet_field),
        )
        Spacer(Modifier.height(20.dp))
        SheetActions(onCancel = onDismiss, onSave = { onSave(input, category) })
    }
}

@Composable
private fun WebsiteSheet(
    config: SheetConfig.Website,
    initialCategory: BlockedCategory,
    onSave: (String, BlockedCategory) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(config.editing.orEmpty()) }
    var category by remember { mutableStateOf(initialCategory) }
    val title = stringResource(
        if (config.editing != null) R.string.kw_sheet_edit_website else R.string.kw_sheet_add_website
    )
    SheetBase(title = title, onDismiss = onDismiss) {
        if (!config.trusted) {
            CategoryPicker(selected = category, onSelect = { category = it })
            Spacer(Modifier.height(16.dp))
        }
        SheetField(
            value = input,
            onValueChange = { input = it },
            placeholder = stringResource(R.string.kw_sheet_field),
        )
        Spacer(Modifier.height(20.dp))
        SheetActions(onCancel = onDismiss, onSave = { onSave(input, category) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetBase(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
            )
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun CategoryPicker(selected: BlockedCategory, onSelect: (BlockedCategory) -> Unit) {
    val colors = LocalAppColors.current
    Text(
        text = stringResource(R.string.kw_sheet_category),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.ink3,
    )
    Spacer(Modifier.height(10.dp))
    val categories = listOf(
        BlockedCategory.CUSTOM,
        BlockedCategory.ADULT,
        BlockedCategory.GAMBLING,
        BlockedCategory.SOCIAL_MEDIA,
        BlockedCategory.SHOPPING,
        BlockedCategory.DISTRACTION,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cat ->
                    val isSelected = cat == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (isSelected) colors.brandSoft else colors.surface)
                            .border(
                                1.dp,
                                if (isSelected) colors.brand else colors.line,
                                RoundedCornerShape(999.dp),
                            )
                            .clickable { onSelect(cat) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = cat.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) colors.brandDark else colors.ink2,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(text = placeholder, fontSize = 14.sp, color = colors.ink3)
        }
        SafeMeTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(fontSize = 14.sp, color = colors.ink),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SheetActions(onCancel: () -> Unit, onSave: () -> Unit) {
    val colors = LocalAppColors.current
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
                .clickable(onClick = onCancel),
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
                .clickable(onClick = onSave),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.kw_sheet_save),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

private fun categoryOf(
    state: KeywordManagerUiState,
    editing: String?,
    isKeyword: Boolean,
): BlockedCategory {
    if (editing == null) return BlockedCategory.CUSTOM
    if (isKeyword) {
        return state.blocklistKeywords.firstOrNull { it.value == editing }?.category
            ?: BlockedCategory.CUSTOM
    }
    return state.blockedWebsites.firstOrNull { it.domain == editing }?.category
        ?: BlockedCategory.CUSTOM
}

private fun saveKeyword(
    context: Context,
    viewModel: KeywordManagerViewModel,
    state: KeywordManagerUiState,
    config: SheetConfig.Keyword,
    input: String,
    category: BlockedCategory,
) {
    val value = input.trim().lowercase()
    val onError: (String) -> Unit = { viewModel.showToast(it) }
    if (value.isEmpty()) {
        onError(context.getString(R.string.kw_toast_empty))
        return
    }
    if (config.whitelist) {
        if (state.whitelistKeywords.any { it == value }) {
            onError(context.getString(R.string.kw_toast_duplicate))
            return
        }
        val success = context.getString(R.string.kw_toast_whitelist_added)
        val editing = config.editing
        if (editing != null && editing != value) {
            viewModel.removeWhitelist(editing)
            viewModel.addWhitelist(value, success)
        } else if (editing == null) {
            viewModel.addWhitelist(value, success)
        }
        return
    }
    val editing = config.editing
    if (state.blocklistKeywords.any { it.value == value && it.value != editing }) {
        onError(context.getString(R.string.kw_toast_duplicate))
        return
    }
    if (editing != null) {
        viewModel.updateKeyword(editing, value, category, context.getString(R.string.kw_toast_updated))
    } else {
        viewModel.addKeyword(value, category, context.getString(R.string.kw_toast_added, category.label))
    }
}

private fun saveWebsite(
    context: Context,
    viewModel: KeywordManagerViewModel,
    state: KeywordManagerUiState,
    config: SheetConfig.Website,
    input: String,
    category: BlockedCategory,
) {
    val domain = normalizeDomain(input)
    val onError: (String) -> Unit = { viewModel.showToast(it) }
    if (domain.isEmpty()) {
        onError(context.getString(R.string.kw_toast_website_empty))
        return
    }
    if (!domain.contains('.') || domain.any { it.isWhitespace() }) {
        onError(context.getString(R.string.kw_toast_invalid_domain))
        return
    }
    if (config.trusted) {
        if (state.trustedWebsites.any { it == domain }) {
            onError(context.getString(R.string.kw_toast_duplicate))
            return
        }
        val success = context.getString(R.string.kw_toast_trusted_added)
        val editing = config.editing
        if (editing != null && editing != domain) {
            viewModel.removeTrusted(editing)
            viewModel.addTrusted(domain, success)
        } else if (editing == null) {
            viewModel.addTrusted(domain, success)
        }
        return
    }
    val editing = config.editing
    if (state.blockedWebsites.any { it.domain == domain && it.domain != editing }) {
        onError(context.getString(R.string.kw_toast_duplicate))
        return
    }
    if (editing != null) {
        viewModel.updateWebsite(editing, domain, category, context.getString(R.string.kw_toast_website_updated))
    } else {
        viewModel.addWebsite(domain, category, context.getString(R.string.kw_toast_website_added, category.label))
    }
}
