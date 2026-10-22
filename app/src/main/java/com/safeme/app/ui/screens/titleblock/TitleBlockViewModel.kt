package com.safeme.app.ui.screens.titleblock

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.safeme.app.R
import com.safeme.app.data.TitleBlockRule
import com.safeme.app.data.TitleMatchMode
import com.safeme.app.data.addTitleBlockRule
import com.safeme.app.data.blockingPrefs
import com.safeme.app.data.deleteTitleBlockRule
import com.safeme.app.data.toggleTitleBlockRule
import com.safeme.app.data.updateTitleBlockRule
import com.safeme.app.protect.ProtectedAction
import com.safeme.app.ui.screens.common.ProtectedViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TitleBlockUiState(
    val rules: List<TitleBlockRule> = emptyList(),
    val query: String = "",
) {
    val filteredRules: List<TitleBlockRule>
        get() = if (query.isBlank()) {
            rules
        } else {
            rules.filter { it.value.contains(query, ignoreCase = true) }
        }

    val activeCount: Int
        get() = rules.count { it.enabled }
}

class TitleBlockViewModel(application: Application) : ProtectedViewModel(application) {

    private val app = getApplication<Application>()

    private val _uiState = MutableStateFlow(TitleBlockUiState())
    val uiState: StateFlow<TitleBlockUiState> = _uiState.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    init {
        viewModelScope.launch {
            try {
                app.blockingPrefs().collect { state ->
                    _uiState.value = TitleBlockUiState(
                        rules = state.titleBlockRules,
                        query = _uiState.value.query,
                    )
                }
            } catch (t: Throwable) {
                _uiState.value = TitleBlockUiState()
            }
        }
    }

    fun setQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun addRule(value: String, mode: TitleMatchMode) {
        val normalized = value.trim().lowercase()
        if (normalized.isEmpty()) {
            _toasts.tryEmit(app.getString(R.string.tb_toast_empty))
            return
        }
        if (_uiState.value.rules.any { it.value == normalized && it.mode == mode }) {
            _toasts.tryEmit(app.getString(R.string.tb_toast_duplicate))
            return
        }
        viewModelScope.launch {
            try {
                app.addTitleBlockRule(value, mode)
                _toasts.tryEmit(app.getString(R.string.tb_toast_added))
            } catch (t: Throwable) {
                _toasts.tryEmit(failure())
            }
        }
    }

    fun updateRule(id: String, value: String, mode: TitleMatchMode) {
        val normalized = value.trim().lowercase()
        if (normalized.isEmpty()) {
            _toasts.tryEmit(app.getString(R.string.tb_toast_empty))
            return
        }
        if (_uiState.value.rules.any { it.id != id && it.value == normalized && it.mode == mode }) {
            _toasts.tryEmit(app.getString(R.string.tb_toast_duplicate))
            return
        }
        viewModelScope.launch {
            try {
                app.updateTitleBlockRule(id, value, mode)
                _toasts.tryEmit(app.getString(R.string.tb_toast_updated))
            } catch (t: Throwable) {
                _toasts.tryEmit(failure())
            }
        }
    }

    /** Deleting a rule removes protection the user configured, so COD-79 gates it. */
    fun deleteRule(id: String) {
        guardDisable(ProtectedAction.TITLE_RULE_DELETE) {
            viewModelScope.launch {
                try {
                    app.deleteTitleBlockRule(id)
                    _toasts.tryEmit(app.getString(R.string.tb_toast_deleted))
                } catch (t: Throwable) {
                    _toasts.tryEmit(failure())
                }
            }
        }
    }

    /** Only the OFF direction weakens protection; turning a rule on never prompts. */
    fun toggleRule(id: String, enabled: Boolean) {
        if (enabled) {
            applyToggleRule(id, true)
        } else {
            guardDisable(ProtectedAction.TITLE_RULE) { applyToggleRule(id, false) }
        }
    }

    private fun applyToggleRule(id: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                app.toggleTitleBlockRule(id, enabled)
            } catch (t: Throwable) {
                _toasts.tryEmit(failure())
            }
        }
    }

    fun showToast(message: String) {
        _toasts.tryEmit(message)
    }

    private fun failure(): String =
        app.getString(R.string.kw_toast_error)
}
