package com.safeme.app.ui.screens.home

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safeme.app.R
import com.safeme.app.data.DEFAULT_QUICK_ACTIONS
import com.safeme.app.data.QuickActionType
import com.safeme.app.data.quickActionPrefs
import com.safeme.app.data.setQuickActions
import com.safeme.app.protect.ProtectedAction
import com.safeme.app.ui.screens.common.ProtectedActionHost
import com.safeme.app.ui.screens.common.ProtectedViewModel
import com.safeme.app.ui.screens.permissions.ChevronIcon
import com.safeme.app.ui.theme.LocalAppColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class QuickActionsEditViewModel(application: Application) : ProtectedViewModel(application) {

    private val _actions = MutableStateFlow<List<QuickActionType>>(DEFAULT_QUICK_ACTIONS)
    val actions: StateFlow<List<QuickActionType>> = _actions.asStateFlow()

    init {
        viewModelScope.launch {
            getApplication<Application>().quickActionPrefs().collect { _actions.value = it }
        }
    }

    fun moveUp(action: QuickActionType) {
        val current = _actions.value
        val index = current.indexOf(action)
        if (index <= 0) return
        persist(current.toMutableList().apply {
            removeAt(index)
            add(index - 1, action)
        })
    }

    fun moveDown(action: QuickActionType) {
        val current = _actions.value
        val index = current.indexOf(action)
        if (index < 0 || index >= current.size - 1) return
        persist(current.toMutableList().apply {
            removeAt(index)
            add(index + 1, action)
        })
    }

    /**
     * Removing a quick action is gated — not because the grid is security
     * furniture, but because `QuickActionType.APPLOCK` is a route back into the
     * lock settings, so removing it hides the guard's own configuration.
     * Adding and reordering never prompt: they can only make the grid more
     * useful, and `resetDefaults` cannot remove APPLOCK either, since
     * DEFAULT_QUICK_ACTIONS includes it.
     */
    fun remove(action: QuickActionType) {
        guardDisable(ProtectedAction.QUICK_ACTION_REMOVE) {
            persist(_actions.value.filter { it != action })
        }
    }

    fun add(action: QuickActionType) {
        if (action in _actions.value) return
        persist(_actions.value + action)
    }

    fun resetDefaults() {
        persist(DEFAULT_QUICK_ACTIONS)
    }

    private fun persist(next: List<QuickActionType>) {
        _actions.value = next
        viewModelScope.launch {
            try {
                getApplication<Application>().setQuickActions(next)
            } catch (_: Throwable) {
                // Persistence failure must never crash the editor.
            }
        }
    }
}

@Composable
fun QuickActionsEditScreen(
    onBack: () -> Unit,
    viewModel: QuickActionsEditViewModel = viewModel()
) {
    val colors = LocalAppColors.current
    val actions by viewModel.actions.collectAsState()
    val hidden = QuickActionType.entries.filter { it !in actions }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            EditHeader(onBack)
            EditSectionTitle(stringResource(R.string.home_qa_on_screen))
            if (actions.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_qa_empty),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = colors.ink2,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                actions.forEachIndexed { index, action ->
                    EditActionRow(
                        action = action,
                        canMoveUp = index > 0,
                        canMoveDown = index < actions.size - 1,
                        onUp = { viewModel.moveUp(action) },
                        onDown = { viewModel.moveDown(action) },
                        onRemove = { viewModel.remove(action) }
                    )
                }
            }
            if (hidden.isNotEmpty()) {
                EditSectionTitle(stringResource(R.string.home_qa_add_more))
                hidden.forEach { action ->
                    EditActionRow(
                        action = action,
                        canMoveUp = false,
                        canMoveDown = false,
                        onUp = null,
                        onDown = null,
                        onRemove = null,
                        onAdd = { viewModel.add(action) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_qa_reset),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.brandDark,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = viewModel::resetDefaults)
                    .padding(horizontal = 2.dp, vertical = 8.dp)
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    // COD-79: prompts before a quick action can be removed from the grid.
    ProtectedActionHost(viewModel)
}

@Composable
private fun EditHeader(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp)) {
        Box(
            modifier = Modifier
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
            text = stringResource(R.string.home_quick_actions_edit_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            color = colors.ink
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.home_quick_actions_edit_sub),
            fontSize = 13.sp,
            color = colors.ink2
        )
    }
}

@Composable
private fun EditSectionTitle(title: String) {
    val colors = LocalAppColors.current
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.2.sp,
        color = colors.ink,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
    )
}

@Composable
private fun EditActionRow(
    action: QuickActionType,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    onAdd: (() -> Unit)? = null
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .clickable(enabled = onAdd != null, onClick = onAdd ?: {})
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(colors.brandSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = action.icon(),
                contentDescription = null,
                tint = colors.brandDark,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(action.titleRes()),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink
            )
            Text(
                text = stringResource(action.subRes()),
                fontSize = 11.5.sp,
                color = colors.ink2
            )
        }
        if (onAdd != null) {
            AddBadge(onClick = onAdd)
        } else {
            Row {
                ArrowButton(text = "\u2191", enabled = canMoveUp, onClick = { onUp?.invoke() })
                Spacer(Modifier.width(6.dp))
                ArrowButton(text = "\u2193", enabled = canMoveDown, onClick = { onDown?.invoke() })
                Spacer(Modifier.width(6.dp))
                RemoveBadge(onClick = onRemove ?: {})
            }
        }
    }
}

@Composable
private fun ArrowButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (enabled) colors.brandSoft else colors.line.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = if (enabled) colors.brandDark else colors.ink3
        )
    }
}

@Composable
private fun RemoveBadge(onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(colors.dangerBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "\u2715", fontSize = 13.sp, color = colors.danger)
    }
}

@Composable
private fun AddBadge(onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.brandSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.home_qa_add),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.brandDark
        )
    }
}
