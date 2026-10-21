package com.safeme.app.ui.screens.focus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.safeme.app.R
import com.safeme.app.data.AppCatalog
import com.safeme.app.data.InstalledApp
import com.safeme.app.data.FocusSession
import com.safeme.app.data.FocusMode
import com.safeme.app.data.addFocusSession
import com.safeme.app.data.deleteFocusSession
import com.safeme.app.data.newFocusSessionId
import com.safeme.app.data.focusPrefs
import com.safeme.app.data.updateFocusSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FocusEditUiState(
    val loading: Boolean = true,
    val editId: String? = null,
    val name: String = "",
    val days: Set<Int> = setOf(0, 1, 2),
    val startMinute: Int = 21 * 60,
    val endMinute: Int = 23 * 60,
    val mode: FocusMode = FocusMode.BOTH,
    val enabled: Boolean = true,
    val selectedApps: Set<String> = emptySet(),
    val installedApps: List<InstalledApp> = emptyList(),
    val appsLoaded: Boolean = false,
)

class FocusEditViewModel(
    application: Application,
    private val editId: String?,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(FocusEditUiState(editId = editId))
    val uiState: StateFlow<FocusEditUiState> = _uiState.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    /** Emitted after a successful save/delete so the screen can navigate back. */
    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done: SharedFlow<Unit> = _done.asSharedFlow()

    init {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val schedules = app.focusPrefs().first().schedules
            val existing = editId?.let { id -> schedules.firstOrNull { it.id == id } }
            _uiState.update {
                it.copy(
                    loading = false,
                    name = existing?.name ?: "",
                    days = (existing?.days ?: listOf(0, 1, 2)).toSet(),
                    startMinute = existing?.startMinute ?: (21 * 60),
                    endMinute = existing?.endMinute ?: (23 * 60),
                    mode = existing?.mode ?: FocusMode.BOTH,
                    enabled = resolveEditedEnabled(editId, existing?.enabled),
                    selectedApps = existing?.appPackages?.toSet() ?: emptySet(),
                )
            }
        }
        viewModelScope.launch {
            val excluded = getApplication<Application>().focusPrefs().first().excludedApps
            val apps = withContext(Dispatchers.Default) { enumerateApps().filter { it.packageName !in excluded } }
            _uiState.update { it.copy(installedApps = apps, appsLoaded = true) }
        }
    }

    fun setName(value: String) {
        _uiState.update { it.copy(name = value) }
    }

    fun toggleDay(day: Int) {
        _uiState.update {
            val days = if (day in it.days) it.days - day else it.days + day
            it.copy(days = days)
        }
    }

    fun setStartMinute(minute: Int) {
        _uiState.update { it.copy(startMinute = minute) }
    }

    fun setEndMinute(minute: Int) {
        _uiState.update { it.copy(endMinute = minute) }
    }

    fun setMode(mode: FocusMode) {
        _uiState.update { it.copy(mode = mode) }
    }

    fun setEnabled(value: Boolean) {
        _uiState.update { it.copy(enabled = value) }
    }

    fun toggleApp(pkg: String) {
        _uiState.update {
            val apps = if (pkg in it.selectedApps) it.selectedApps - pkg else it.selectedApps + pkg
            it.copy(selectedApps = apps)
        }
    }

    /** Selects every app in the picker (empty search results are a no-op). */
    fun selectAllApps() {
        _uiState.update {
            it.copy(selectedApps = it.selectedApps + it.installedApps.map { app -> app.packageName })
        }
    }

    /** Deselects every app in the picker. */
    fun deselectAllApps() {
        _uiState.update {
            it.copy(selectedApps = it.selectedApps - it.installedApps.map { app -> app.packageName })
        }
    }

    fun applyApps(apps: Set<String>) {
        _uiState.update { it.copy(selectedApps = apps) }
    }

    /** Validates and persists. Returns true when the schedule was saved. */
    fun save(): Boolean {
        val app = getApplication<Application>()
        val state = _uiState.value
        if (state.name.isBlank()) {
            _toasts.tryEmit(app.getString(R.string.sche_toast_name))
            return false
        }
        if (state.days.isEmpty()) {
            _toasts.tryEmit(app.getString(R.string.sche_toast_day))
            return false
        }
        if (!isValidFocusWindow(state.startMinute, state.endMinute)) {
            _toasts.tryEmit(app.getString(R.string.sche_toast_time))
            return false
        }
        val session = FocusSession(
            id = state.editId ?: newFocusSessionId(),
            name = state.name.trim(),
            days = state.days.toList(),
            startMinute = state.startMinute,
            endMinute = state.endMinute,
            mode = state.mode,
            appPackages = state.selectedApps.toList(),
            enabled = state.enabled,
        )
        viewModelScope.launch {
            if (state.editId != null) {
                app.updateFocusSession(session)
            } else {
                app.addFocusSession(session)
            }
            _toasts.tryEmit(app.getString(R.string.sche_toast_saved))
            _done.tryEmit(Unit)
        }
        return true
    }

    fun delete() {
        val app = getApplication<Application>()
        val id = _uiState.value.editId ?: return
        viewModelScope.launch {
            app.deleteFocusSession(id)
            _toasts.tryEmit(app.getString(R.string.sche_toast_deleted))
            _done.tryEmit(Unit)
        }
    }

    fun showToast(message: String) {
        _toasts.tryEmit(message)
    }

    private fun enumerateApps(): List<InstalledApp> = AppCatalog.load(getApplication())

    class Factory(
        private val app: Application,
        private val editId: String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FocusEditViewModel(app, editId) as T
    }
}

/**
 * B10: a schedule window is valid unless start and end coincide. Overnight
 * windows (`endMinute < startMinute`) spill into the next day. Range
 * 0..1439 is enforced by the time picker (`hour % 24`, `minute % 60`), so
 * only the degenerate case is rejected here. Single source of truth for
 * the editor save path and the time-picker sheet.
 */
internal fun isValidFocusWindow(startMinute: Int, endMinute: Int): Boolean =
    startMinute != endMinute

/**
 * B6: resolves the editor's `enabled` flag. New schedules default on; edits
 * preserve the stored flag. A missing stored value (e.g. the schedule was
 * deleted elsewhere while editing) fails safe to on — and the subsequent
 * `updateFocusSession()` already no-ops when the id matches nothing.
 */
internal fun resolveEditedEnabled(editId: String?, storedEnabled: Boolean?): Boolean =
    if (editId == null) true else storedEnabled ?: true
