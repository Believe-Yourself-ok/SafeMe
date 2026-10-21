package com.safeme.app.ui.screens.blockscreen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safeme.app.R
import com.safeme.app.data.BLOCK_SCREEN_DEFAULT_DWELL
import com.safeme.app.data.BlockScreenPrefsState
import com.safeme.app.data.blockScreenPrefs
import com.safeme.app.data.writeBlockScreenPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Working copy of the block-gate settings. Values are loaded from DataStore on
 * creation so saved settings reappear every time the screen opens (each nav
 * entry gets a fresh ViewModel), and the screen-level Save button commits the
 * whole working copy with [save]. Edits before a successful save stay in
 * memory only — leaving the screen without saving discards them.
 */
class BlockScreenViewModel(application: Application) : AndroidViewModel(application) {

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

    /** COD-81: default preview message; a blank working copy ≡ this value. */
    private val defaultPreviewMessage: String =
        application.getString(R.string.bs_preview_msg_default)

    /**
     * COD-81: working copy at load time / last successful save; null until
     * the initial load completes.
     */
    private val baseline = MutableStateFlow<BlockScreenSnapshot?>(null)

    /** COD-81: true while the working copy holds changes that were never saved. */
    val hasUnsavedChanges: StateFlow<Boolean> =
        combine(baseline, _dwell, _message, _img, _whyOn) { base, dwell, message, img, whyOn ->
            val current = BlockScreenSnapshot(dwell, message, img, whyOn)
            base != null && isBlockScreenDirty(base, current, defaultPreviewMessage)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
            // COD-81: the just-loaded working copy is the clean baseline.
            baseline.value =
                BlockScreenSnapshot(_dwell.value, _message.value, _img.value, _whyOn.value)
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

    fun toggleWhy() {
        _whyOn.value = !_whyOn.value
    }

    /**
     * Persists the current working copy of every setting in one atomic edit.
     * Returns true on success; a failed write is a graceful no-op (fail-open),
     * never a crash, and the in-memory working copy is left untouched so the
     * user can retry.
     */
    suspend fun save(): Boolean {
        val ok = runCatching {
            getApplication<Application>().writeBlockScreenPrefs(
                BlockScreenPrefsState(
                    dwell = _dwell.value,
                    message = _message.value,
                    img = _img.value,
                    whyOn = _whyOn.value,
                )
            )
        }.isSuccess
        if (ok) {
            // COD-81: saved → clean. A failed write leaves the baseline
            // stale so the user can retry or discard.
            baseline.value =
                BlockScreenSnapshot(_dwell.value, _message.value, _img.value, _whyOn.value)
        }
        return ok
    }
}

/** COD-81: comparable copy of the block-gate working state. */
internal data class BlockScreenSnapshot(
    val dwell: Int,
    val message: String,
    val img: String,
    val whyOn: Boolean,
)

/**
 * COD-81: true when the working copy differs from the baseline. An empty
 * message and the default preview message are the same content (the screen
 * resolves `message.ifEmpty { default }`), so blank ≡ [defaultMessage] here.
 */
internal fun isBlockScreenDirty(
    baseline: BlockScreenSnapshot,
    current: BlockScreenSnapshot,
    defaultMessage: String,
): Boolean =
    baseline.dwell != current.dwell ||
        baseline.img != current.img ||
        baseline.whyOn != current.whyOn ||
        normalizeMessage(baseline.message, defaultMessage) !=
            normalizeMessage(current.message, defaultMessage)

private fun normalizeMessage(message: String, defaultMessage: String): String =
    if (message.isBlank()) defaultMessage else message
