package com.safeme.app.ui.screens.blockscreen

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.safeme.app.R
import com.safeme.app.data.BLOCK_SCREEN_DEFAULT_DWELL
import com.safeme.app.data.BlockScreenPrefsState
import com.safeme.app.data.blockScreenPrefs
import com.safeme.app.data.writeBlockScreenPrefs
import com.safeme.app.protect.ProtectedAction
import com.safeme.app.ui.screens.common.ProtectedViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Working copy of the block-gate settings. Values are loaded from DataStore on
 * creation so saved settings reappear every time the screen opens (each nav
 * entry gets a fresh ViewModel), and the screen-level Save button commits the
 * whole working copy with [save]. Edits before a successful save stay in
 * memory only — leaving the screen without saving discards them.
 */
class BlockScreenViewModel(application: Application) : ProtectedViewModel(application) {

    private val _dwell = MutableStateFlow(BLOCK_SCREEN_DEFAULT_DWELL)
    val dwell: StateFlow<Int> = _dwell.asStateFlow()

    private val _message = MutableStateFlow(
        application.getString(R.string.bs_preview_msg_default)
    )
    val message: StateFlow<String> = _message.asStateFlow()

    private val _img = MutableStateFlow("")
    val img: StateFlow<String> = _img.asStateFlow()

    private val _whyOn = MutableStateFlow(true)
    val whyOn: StateFlow<Boolean> = _whyOn.asStateFlow()

    init {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val stored = app.blockScreenPrefs().first()
            _dwell.value = stored.dwell
            _message.value = stored.message.ifEmpty {
                app.getString(R.string.bs_preview_msg_default)
            }
            _img.value = stored.img
            _whyOn.value = stored.whyOn
        }
    }

    fun stepDwell(delta: Int) {
        _dwell.value = (_dwell.value + delta).coerceIn(3, 120)
    }

    fun setMessage(value: String) {
        _message.value = value
    }

    fun setImg(value: String) {
        _img.value = value
    }

    /**
     * The "why blocked" explanation toggle.
     *
     * This ViewModel keeps a working copy that [save] persists in one atomic
     * edit, so the guard belongs here rather than in save(): gating the flip
     * means an unverified cancel leaves `_whyOn` true and save() therefore
     * persists true. Gating save() instead would mean interleaving a prompt with
     * a suspend call the screen awaits a Boolean from.
     */
    fun toggleWhy() {
        if (_whyOn.value) {
            guardDisable(ProtectedAction.BLOCK_SCREEN_WHY) { _whyOn.value = false }
        } else {
            _whyOn.value = true
        }
    }

    /**
     * Persists the current working copy of every setting in one atomic edit.
     * Returns true on success; a failed write is a graceful no-op (fail-open),
     * never a crash, and the in-memory working copy is left untouched so the
     * user can retry.
     */
    suspend fun save(): Boolean = runCatching {
        getApplication<Application>().writeBlockScreenPrefs(
            BlockScreenPrefsState(
                dwell = _dwell.value,
                message = _message.value,
                img = _img.value,
                whyOn = _whyOn.value,
            )
        )
    }.isSuccess
}
