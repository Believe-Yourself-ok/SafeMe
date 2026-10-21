package com.safeme.app.ui.screens.focus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safeme.app.R
import com.safeme.app.data.AppCatalog
import com.safeme.app.data.InstalledApp
import com.safeme.app.data.FOCUS_DAY_NAMES
import com.safeme.app.data.FocusSession
import com.safeme.app.data.focusDaysLabel
import com.safeme.app.data.focusModeLabel
import com.safeme.app.data.focusPrefs
import com.safeme.app.data.focusTimeLabel
import com.safeme.app.data.focusWindowLabel
import com.safeme.app.data.setA11yWarningDismissed
import com.safeme.app.data.setExactAlarmWarningDismissed
import com.safeme.app.data.setExcludedApps
import com.safeme.app.data.shouldShowA11yWarning
import com.safeme.app.data.shouldShowExactAlarmWarning
import com.safeme.app.data.toggleFocusSession
import com.safeme.app.protect.FocusEvaluator
import com.safeme.app.ui.util.isAccessibilityEnabled
import com.safeme.app.ui.util.isExactAlarmGranted
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FocusSessionCard(
    val id: String,
    val name: String,
    val days: String,
    val time: String,
    val mode: String,
    val apps: String,
    val next: String,
    val enabled: Boolean = true,
)

data class FocusUiState(
    val cards: List<FocusSessionCard> = emptyList(),
    val heroCount: String = "",
    val heroPill: String = "",
    val nextBoundary: String = "",
    val showA11yWarning: Boolean = false,
    val showExactAlarmWarning: Boolean = false,
    val excludedApps: Set<String> = emptySet(),
    val installedApps: List<InstalledApp> = emptyList(),
    val appsLoaded: Boolean = false,
)

class FocusViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(FocusUiState())
    val uiState: StateFlow<FocusUiState> = _uiState.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    @Volatile
    private var lastFocus: List<FocusSession> = emptyList()

    @Volatile
    private var lastA11yWarningDismissed: Boolean = false

    @Volatile
    private var lastExactAlarmDismissed: Boolean = false

    init {
        viewModelScope.launch {
            getApplication<Application>().focusPrefs().collect { state ->
                lastFocus = state.schedules
                lastA11yWarningDismissed = state.a11yWarningDismissed
                lastExactAlarmDismissed = state.exactAlarmWarningDismissed
                _uiState.update {
                    buildUiState(
                        state.schedules,
                        state.a11yWarningDismissed,
                        state.exactAlarmWarningDismissed,
                        state.excludedApps,
                    )
                }
            }
        }
    }

    private fun buildUiState(
        schedules: List<FocusSession>,
        a11yWarningDismissed: Boolean,
        exactAlarmDismissed: Boolean,
        excludedApps: Set<String>,
    ): FocusUiState {
        val app = getApplication<Application>()
        val enabled = schedules.count { it.enabled }
        val heroCount = app.resources.getQuantityString(
            R.plurals.sch_hero_count,
            enabled,
            enabled,
        )
        val heroPill = app.getString(
            if (enabled > 0) R.string.sch_hero_pill_on else R.string.sch_hero_pill_off
        )
        val enabledFocusSessions = schedules.filter { it.enabled }
        val nextBoundary = when {
            enabledFocusSessions.isNotEmpty() -> {
                val nowMillis = System.currentTimeMillis()
                val next = enabledFocusSessions.mapNotNull { s ->
                    val m = FocusEvaluator.nextBoundary(listOf(s), nowMillis)
                    if (m == Long.MAX_VALUE) null else s to m
                }.minByOrNull { it.second }
                if (next == null) {
                    // No computable boundary (e.g. degenerate window); fall back to start time.
                    app.getString(
                        R.string.sch_hero_sub_on,
                        enabledFocusSessions.first().name,
                        focusTimeLabel(enabledFocusSessions.first().startMinute),
                    )
                } else {
                    app.getString(
                        R.string.sch_hero_sub_on,
                        next.first.name,
                        nextBoundaryLabel(next.second),
                    )
                }
            }
            schedules.isNotEmpty() -> app.getString(R.string.sch_hero_sub_paused)
            else -> app.getString(R.string.sch_hero_sub_empty)
        }
        val cards = schedules.map { s ->
            FocusSessionCard(
                id = s.id,
                name = s.name,
                days = focusDaysLabel(s.days),
                time = focusWindowLabel(s.startMinute, s.endMinute),
                mode = focusModeLabel(s.mode),
                apps = if (s.blocksAllApps) {
                    app.getString(R.string.sch_card_apps_all)
                } else {
                    app.resources.getQuantityString(
                        R.plurals.sch_card_apps,
                        s.appPackages.size,
                        s.appPackages.size,
                    )
                },
                next = app.getString(R.string.sch_card_next, focusDaysLabel(s.days)),
                enabled = s.enabled,
            )
        }
        return FocusUiState(
            cards = cards,
            heroCount = heroCount,
            heroPill = heroPill,
            nextBoundary = nextBoundary,
            showA11yWarning = shouldShowA11yWarning(
                schedules,
                isAccessibilityEnabled(app),
                a11yWarningDismissed,
            ),
            showExactAlarmWarning = shouldShowExactAlarmWarning(
                schedules,
                isExactAlarmGranted(app),
                exactAlarmDismissed,
            ),
            excludedApps = excludedApps,
        )
    }

    /** Hides the a11y banner until the service is enabled again. */
    fun dismissA11yWarning() {
        viewModelScope.launch {
            getApplication<Application>().setA11yWarningDismissed(true)
        }
    }

    /** Hides the exact-alarm banner until alarms are granted again. */
    fun dismissExactAlarmWarning() {
        viewModelScope.launch {
            getApplication<Application>().setExactAlarmWarningDismissed(true)
        }
    }

    /**
     * Lazily loads the installed-app catalog (used by the Exclude Apps sheet).
     * Mirrors [com.safeme.app.ui.screens.vpn.DnsVpnViewModel.loadInstalledApps]:
     * package queries are slow, so they run off the main thread and the result
     * is cached in state until the next process death.
     */
    fun ensureAppsLoaded() {
        if (_uiState.value.appsLoaded) return
        viewModelScope.launch {
            val apps = withContext(Dispatchers.Default) { AppCatalog.load(getApplication()) }
            _uiState.update { it.copy(installedApps = apps, appsLoaded = true) }
        }
    }

    /** Persists the global schedule-exclusion list. */
    fun setExcludedApps(apps: Set<String>) {
        val app = getApplication<Application>()
        viewModelScope.launch { app.setExcludedApps(apps) }
    }

    /**
     * Re-reads live service state (called on screen resume). When the
     * accessibility service is now enabled, clears any prior dismissal so a
     * future disable re-arms the banner. Same for the exact-alarm grant.
     */
    fun refresh() {
        val app = getApplication<Application>()
        val a11yEnabled = isAccessibilityEnabled(app)
        val exactGranted = isExactAlarmGranted(app)
        viewModelScope.launch {
            val dismissed = if (a11yEnabled) {
                if (lastA11yWarningDismissed) app.setA11yWarningDismissed(false)
                false
            } else {
                lastA11yWarningDismissed
            }
            val exactDismissed = if (exactGranted) {
                if (lastExactAlarmDismissed) app.setExactAlarmWarningDismissed(false)
                false
            } else {
                lastExactAlarmDismissed
            }
            _uiState.update {
                it.copy(
                    showA11yWarning = shouldShowA11yWarning(
                        lastFocus,
                        a11yEnabled,
                        dismissed,
                    ),
                    showExactAlarmWarning = shouldShowExactAlarmWarning(
                        lastFocus,
                        exactGranted,
                        exactDismissed,
                    )
                )
            }
        }
    }

    fun toggleFocusSession(id: String) {
        val current = _uiState.value.cards.firstOrNull { it.id == id }?.enabled ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            app.toggleFocusSession(id, !current)
        }
        _toasts.tryEmit(
            app.getString(if (current) R.string.sch_toast_off else R.string.sch_toast_on)
        )
    }

    fun showToast(message: String) {
        _toasts.tryEmit(message)
    }

    /** "HH:mm" for today's boundary, "Day HH:mm" when it falls on a later day. */
    private fun nextBoundaryLabel(nextMillis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = nextMillis }
        val now = Calendar.getInstance()
        val time = focusTimeLabel(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
        val sameDay = cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return time
        val dayIndex = ((cal.get(Calendar.DAY_OF_WEEK) - 1 + 6) % 7)
        return "${FOCUS_DAY_NAMES[dayIndex]} $time"
    }
}
