package com.safeme.app.ui.screens.keywords

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.safeme.app.R
import com.safeme.app.data.BlockedCategory
import com.safeme.app.data.BlockedKeyword
import com.safeme.app.data.BlockedWebsite
import com.safeme.app.data.addBlockedKeyword
import com.safeme.app.data.addBlockedWebsite
import com.safeme.app.data.addTrustedWebsite
import com.safeme.app.data.addWhitelistKeyword
import com.safeme.app.data.blockingPrefs
import com.safeme.app.data.removeBlockedKeyword
import com.safeme.app.data.removeBlockedWebsite
import com.safeme.app.data.removeTrustedWebsite
import com.safeme.app.data.removeWhitelistKeyword
import com.safeme.app.data.resetUserBlockingPrefs
import com.safeme.app.data.setBlockingEnabled
import com.safeme.app.data.updateBlockedKeyword
import com.safeme.app.data.updateBlockedWebsite
import com.safeme.app.protect.ProtectedAction
import com.safeme.app.ui.screens.common.ProtectedViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class KeywordManagerUiState(
    val blocklistKeywords: List<BlockedKeyword> = emptyList(),
    val whitelistKeywords: List<String> = emptyList(),
    val blockedWebsites: List<BlockedWebsite> = emptyList(),
    val trustedWebsites: List<String> = emptyList(),
    val blockingEnabled: Boolean = true,
)

class KeywordManagerViewModel(application: Application) : ProtectedViewModel(application) {

    private val app = getApplication<Application>()

    private val _uiState = MutableStateFlow(KeywordManagerUiState())
    val uiState: StateFlow<KeywordManagerUiState> = _uiState.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    init {
        viewModelScope.launch {
            try {
                app.blockingPrefs().collect { state ->
                    _uiState.value = KeywordManagerUiState(
                        blocklistKeywords = state.blocklistKeywords,
                        whitelistKeywords = state.whitelistKeywords,
                        blockedWebsites = state.blockedWebsites,
                        trustedWebsites = state.trustedWebsites,
                        blockingEnabled = state.blockingEnabled,
                    )
                }
            } catch (t: Throwable) {
                _uiState.value = KeywordManagerUiState()
            }
        }
    }

    fun showToast(message: String) {
        _toasts.tryEmit(message)
    }

    fun toggleBlocking(enabled: Boolean) {
        viewModelScope.launch {
            try {
                app.setBlockingEnabled(enabled)
            } catch (t: Throwable) {
                _toasts.tryEmit(failure())
            }
        }
    }

    fun addKeyword(value: String, category: BlockedCategory, success: String) {
        viewModelScope.launch {
            try {
                app.addBlockedKeyword(value, category)
                _toasts.tryEmit(success)
            } catch (t: Throwable) {
                _toasts.tryEmit(failure())
            }
        }
    }

    fun updateKeyword(old: String, new: String, category: BlockedCategory, success: String) {
        viewModelScope.launch {
            try {
                app.updateBlockedKeyword(old, new, category)
                _toasts.tryEmit(success)
            } catch (t: Throwable) {
                _toasts.tryEmit(failure())
            }
        }
    }

    /** Deleting from the blocklist weakens blocking, so COD-79 gates it. */
    fun removeKeyword(value: String) {
        guardDisable(ProtectedAction.KEYWORD_DELETE) {
            viewModelScope.launch {
                try {
                    app.removeBlockedKeyword(value)
                } catch (t: Throwable) {
                    _toasts.tryEmit(failure())
                }
            }
        }
    }

    /**
     * Adding a whitelisted keyword is an *exemption*, not a disable: blocking
     * stays on and this carves one exception out of it. Deliberately ungated per
     * the scope boundary in [com.safeme.app.protect.DisableProtectionPolicy] —
     * prompting on routine exceptions is the friction that makes users switch
     * the whole protection off.
     */
    fun addWhitelist(value: String, success: String) {
        viewModelScope.launch {
            try {
                app.addWhitelistKeyword(value)
                _toasts.tryEmit(success)
            } catch (t: Throwable) {
                _toasts.tryEmit(failure())
            }
        }
    }

    /**
     * Deleting from the *allow*-list is deliberately NOT gated: removing an
     * allowed keyword leaves fewer exceptions, so blocking gets stronger.
     * COD-79's guard covers actions that weaken a protection, and this is the
     * opposite. Same reasoning as [removeTrusted] below.
     */
    fun removeWhitelist(value: String) {
        viewModelScope.launch {
            try {
                app.removeWhitelistKeyword(value)
            } catch (t: Throwable) {
                _toasts.tryEmit(failure())
            }
        }
    }

    fun addWebsite(domain: String, category: BlockedCategory, success: String) {
        viewModelScope.launch {
            try {
                app.addBlockedWebsite(domain, category)
                _toasts.tryEmit(success)
            } catch (t: Throwable) {
                _toasts.tryEmit(failure())
            }
        }
    }

    fun updateWebsite(old: String, new: String, category: BlockedCategory, success: String) {
        viewModelScope.launch {
            try {
                app.updateBlockedWebsite(old, new, category)
                _toasts.tryEmit(success)
            } catch (t: Throwable) {
                _toasts.tryEmit(failure())
            }
        }
    }

    /** Deleting from the blocked-site list weakens blocking, so COD-79 gates it. */
    fun removeWebsite(domain: String) {
        guardDisable(ProtectedAction.WEBSITE_DELETE) {
            viewModelScope.launch {
                try {
                    app.removeBlockedWebsite(domain)
                } catch (t: Throwable) {
                    _toasts.tryEmit(failure())
                }
            }
        }
    }

    /** Ungated exemption — see [addWhitelist] for the reasoning. */
    fun addTrusted(domain: String, success: String) {
        viewModelScope.launch {
            try {
                app.addTrustedWebsite(domain)
                _toasts.tryEmit(success)
            } catch (t: Throwable) {
                _toasts.tryEmit(failure())
            }
        }
    }

    /** Not gated — see [removeWhitelist]: un-trusting a site strengthens blocking. */
    fun removeTrusted(domain: String) {
        viewModelScope.launch {
            try {
                app.removeTrustedWebsite(domain)
            } catch (t: Throwable) {
                _toasts.tryEmit(failure())
            }
        }
    }

    /**
     * Wipes every user blocklist keyword, website and title rule back to the
     * bundled defaults. The largest single weakening in the app, so COD-79
     * gates it — more destructive than any of the per-item deletes above.
     */
    fun resetCustom() {
        guardDisable(ProtectedAction.BLOCKLIST_RESET) {
            viewModelScope.launch {
                try {
                    app.resetUserBlockingPrefs()
                } catch (t: Throwable) {
                    _toasts.tryEmit(failure())
                }
            }
        }
    }

    private fun failure(): String =
        app.getString(R.string.kw_toast_error)
}
