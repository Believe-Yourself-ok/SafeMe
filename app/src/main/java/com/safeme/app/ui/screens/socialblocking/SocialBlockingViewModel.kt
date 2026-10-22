package com.safeme.app.ui.screens.socialblocking

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.safeme.app.data.InstalledApp
import com.safeme.app.data.SocialBlockingPrefs
import com.safeme.app.data.SocialBlockingState
import com.safeme.app.data.applySocialPreset
import com.safeme.app.data.setSocialBlockingEnabled
import com.safeme.app.data.setSocialFacebook
import com.safeme.app.data.setSocialSnapchat
import com.safeme.app.data.setSocialWholeBlocked
import com.safeme.app.data.setSocialYoutube
import com.safeme.app.data.socialBlockingPrefs
import com.safeme.app.protect.ProtectedAction
import com.safeme.app.ui.screens.common.ProtectedViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class SocialBlockingUiState(
    val enabled: Boolean = true,
    val wholeBlocked: Set<String> = SocialBlockingState.DEFAULT_WHOLE_BLOCKED,
    val youtube: Boolean = true,
    val facebook: Boolean = true,
    val snapchat: Boolean = true,
    val displayLaunchCount: Int = 5,
    val activeTabs: Int = 3,
    val preset: String = "deep", // deep / balanced / relax / none
) {
    val isDeep: Boolean get() = preset == "deep"
    val isBalanced: Boolean get() = preset == "balanced"
    val isRelax: Boolean get() = preset == "relax"
}

class SocialBlockingViewModel(application: Application) : ProtectedViewModel(application) {

    private val app: Application get() = getApplication()

    private val _uiState = MutableStateFlow(SocialBlockingUiState())
    val uiState: StateFlow<SocialBlockingUiState> = _uiState.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    // For GroupedAppPicker sheet — loaded off main.
    private val _allApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val allApps: StateFlow<List<InstalledApp>> = _allApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(true)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                app.socialBlockingPrefs().catch { emit(SocialBlockingState()) },
                // placeholder: apps loaded separately below
                MutableStateFlow(Unit),
            ) { state, _ -> state }
                .collect { state ->
                    _uiState.value = SocialBlockingUiState(
                        enabled = state.enabled,
                        wholeBlocked = state.wholeBlocked,
                        youtube = state.youtube,
                        facebook = state.facebook,
                        snapchat = state.snapchat,
                        displayLaunchCount = state.displayLaunchCount,
                        activeTabs = state.activeTabs,
                        preset = resolvePreset(state),
                    )
                }
        }
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isLoadingApps.value = true
            try {
                val list = com.safeme.app.data.AppCatalog.load(app)
                _allApps.value = list.sortedBy { it.label.lowercase() }
            } catch (_: Throwable) {
                _allApps.value = emptyList()
            } finally {
                _isLoadingApps.value = false
            }
        }
    }

    fun refreshApps() = loadApps()

    private fun resolvePreset(s: SocialBlockingState): String {
        if (!s.enabled) return "relax"
        val whole = s.displayLaunchCount
        val tabs = s.activeTabs
        // 3 tabs = youtube+facebook+snapchat
        return when {
            whole >= 4 && tabs == 3 -> "deep"
            whole == 0 && tabs == 3 -> "balanced"
            else -> "none"
        }
    }

    // Every control below is split by direction for COD-79: turning blocking ON
    // is never gated, while turning it OFF — or removing an app, a tab, or a
    // preset's worth of protection — goes through guardDisable first. The write
    // and its toast live inside the granted lambda, so a cancelled prompt cannot
    // leave the UI announcing a change that never happened.

    fun toggleMaster() {
        if (_uiState.value.enabled) {
            guardDisable(ProtectedAction.SOCIAL_BLOCKING_MASTER) { applyMaster(false) }
        } else {
            applyMaster(true)
        }
    }

    private fun applyMaster(next: Boolean) {
        viewModelScope.launch {
            app.setSocialBlockingEnabled(next)
            _toasts.emit(if (next) "Social Media Blocking — on" else "Social Media Blocking — off")
        }
    }

    fun applyPreset(which: String) {
        val cur = _uiState.value
        if (presetWeakens(which, cur.enabled, cur.wholeBlocked)) {
            guardDisable(ProtectedAction.SOCIAL_PRESET_WEAKEN) { applyPresetNow(which) }
        } else {
            applyPresetNow(which)
        }
    }

    private fun applyPresetNow(which: String) {
        viewModelScope.launch {
            app.applySocialPreset(which)
            val label = when (which) {
                "deep" -> "Deep Work — all apps & tabs"
                "balanced" -> "Balanced — tabs only"
                "relax" -> "Relax — all paused"
                else -> which
            }
            _toasts.emit(label)
        }
    }

    fun toggleLaunch(pkg: String) {
        val cur = _uiState.value
        if (!cur.enabled) {
            viewModelScope.launch { _toasts.emit("Turn on Master first") }
            return
        }
        if (pkg in cur.wholeBlocked) {
            guardDisable(ProtectedAction.SOCIAL_BLOCKING_APP) {
                applyLaunch(pkg, cur.wholeBlocked - pkg)
            }
        } else {
            applyLaunch(pkg, cur.wholeBlocked + pkg)
        }
    }

    /** Bulk set from the app picker. Gated only when it drops blocked apps. */
    fun setWholeBlocked(pkgs: Set<String>) {
        if ((_uiState.value.wholeBlocked - pkgs).isNotEmpty()) {
            guardDisable(ProtectedAction.SOCIAL_BLOCKING_APP) { applyWholeBlocked(pkgs) }
        } else {
            applyWholeBlocked(pkgs)
        }
    }

    private fun applyLaunch(pkg: String, next: Set<String>) {
        viewModelScope.launch {
            app.setSocialWholeBlocked(next)
            val name = pkg.substringAfterLast(".").takeIf { it.isNotEmpty() } ?: pkg
            _toasts.emit(if (pkg in next) "$name blocked" else "$name allowed")
        }
    }

    private fun applyWholeBlocked(next: Set<String>) {
        viewModelScope.launch {
            app.setSocialWholeBlocked(next)
            _toasts.emit(
                if (next.isEmpty()) "No whole-app blocks"
                else "${SocialBlockingPrefs.displayCount(next)} app${if (SocialBlockingPrefs.displayCount(next) == 1) "" else "s"} blocked (whole-app)"
            )
        }
    }

    fun toggleYoutube() =
        toggleTab("YouTube Shorts", _uiState.value.youtube) { app.setSocialYoutube(it) }

    fun toggleFacebook() =
        toggleTab("Facebook Reels", _uiState.value.facebook) { app.setSocialFacebook(it) }

    fun toggleSnapchat() =
        toggleTab("Snapchat Spotlight", _uiState.value.snapchat) { app.setSocialSnapchat(it) }

    /**
     * Shared shape for the three tab toggles, which were previously three
     * near-identical copies. Turning a tab block OFF weakens protection and is
     * gated; turning one ON never is. Toast wording and the "master first"
     * guard are unchanged from the originals.
     */
    private fun toggleTab(
        name: String,
        currentlyBlocked: Boolean,
        write: suspend (Boolean) -> Unit,
    ) {
        if (!_uiState.value.enabled) {
            viewModelScope.launch { _toasts.emit("Turn on Social Media Blocking first") }
            return
        }
        val next = !currentlyBlocked
        if (currentlyBlocked) {
            guardDisable(ProtectedAction.SOCIAL_BLOCKING_TAB) { applyTab(name, next, write) }
        } else {
            applyTab(name, next, write)
        }
    }

    private fun applyTab(name: String, next: Boolean, write: suspend (Boolean) -> Unit) {
        viewModelScope.launch {
            write(next)
            _toasts.emit(if (next) "$name blocked" else "$name allowed")
        }
    }

    fun showToast(msg: String) {
        viewModelScope.launch { _toasts.emit(msg) }
    }
}
