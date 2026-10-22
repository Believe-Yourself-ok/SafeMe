package com.safeme.app.ui.screens.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safeme.app.R
import com.safeme.app.data.ThemePref
import com.safeme.app.data.setThemePref
import com.safeme.app.data.themePref
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val themePref: ThemePref = ThemePref.SYSTEM,
)

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    init {
        val app = getApplication<Application>()
        viewModelScope.launch {
            app.themePref().collect { pref ->
                _uiState.update { it.copy(themePref = pref) }
            }
        }
    }

    fun selectTheme(pref: ThemePref) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            app.setThemePref(pref)
        }
        _uiState.update { it.copy(themePref = pref) }
        val message = when (pref) {
            ThemePref.SYSTEM -> app.getString(R.string.prof_theme_system_toast)
            ThemePref.DARK -> app.getString(R.string.prof_theme_dark_toast)
            ThemePref.LIGHT -> app.getString(R.string.prof_theme_light_toast)
        }
        _toasts.tryEmit(message)
    }

    fun share() {
        val app = getApplication<Application>()
        _toasts.tryEmit(app.getString(R.string.prof_share))
    }

    fun contact() {
        val app = getApplication<Application>()
        _toasts.tryEmit(app.getString(R.string.prof_contact_toast))
    }

    fun comingSoon() {
        val app = getApplication<Application>()
        _toasts.tryEmit(app.getString(R.string.prof_soon_toast))
    }

    fun toast(message: String) {
        _toasts.tryEmit(message)
    }

    /**
     * Not gated by COD-79, deliberately: this is a stub that emits a toast and
     * deletes nothing, so there is no protection to weaken. If it ever becomes
     * real it must gain a [com.safeme.app.protect.ProtectedAction] — deleting an
     * account would remove every protection at once.
     */
    fun deleteAccount() {
        val app = getApplication<Application>()
        _toasts.tryEmit(app.getString(R.string.prof_delete_toast))
    }
}