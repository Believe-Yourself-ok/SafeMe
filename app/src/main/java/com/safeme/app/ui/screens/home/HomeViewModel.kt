package com.safeme.app.ui.screens.home

import android.app.Application
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safeme.app.R
import com.safeme.app.data.A11yProtectionPrefsState
import com.safeme.app.data.ActivityEntry
import com.safeme.app.data.AppLockPrefsState
import com.safeme.app.data.BlockingPrefsState
import com.safeme.app.data.DnsVpnSettings
import com.safeme.app.data.LockType
import com.safeme.app.data.PreventUninstallPrefsState
import com.safeme.app.data.QuickActionType
import com.safeme.app.data.FocusPrefsState
import com.safeme.app.data.a11yProtectionPrefs
import com.safeme.app.data.activityLog
import com.safeme.app.data.appLockPrefs
import com.safeme.app.data.blockingPrefs
import com.safeme.app.data.dnsVpnSettings
import com.safeme.app.data.maybeRolloverBlockedCounter
import com.safeme.app.data.preventUninstallPrefs
import com.safeme.app.data.quickActionPrefs
import com.safeme.app.data.focusPrefs
import com.safeme.app.data.setBlockingEnabled
import com.safeme.app.protect.DeviceAdminUtils
import com.safeme.app.protect.ProtectionLayersEvaluator
import com.safeme.app.ui.util.isAccessibilityEnabled
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class HomeUiState(
    val masterProtection: Boolean = true,
    val a11yEnabled: Boolean = false,
    val a11yStateKnown: Boolean = false,
    val a11yChecking: Boolean = true,
    val greeting: String = "",
    val dateLine: String = "",
    val blockedToday: String = "0",
    val focusSessionCount: String = "0",
    val heroProgress: Float = 0f,
    val heroTitleRes: Int = R.string.home_hero_title,
    val heroSubtitle: String = "",
    /** Header pill: green "Protected" vs amber "Paused"/"Attention". */
    val pillGreen: Boolean = true,
    val pillTextRes: Int = R.string.home_protected,
    /** Latest 3 real activity entries, newest first. */
    val feed: List<ActivityEntry> = emptyList(),
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val initialA11yEnabled = isAccessibilityEnabled(app)

    @Volatile
    private var a11yStatus = A11yStatus(
        enabled = initialA11yEnabled,
        stateKnown = initialA11yEnabled,
        checking = !initialA11yEnabled,
    )

    private val _uiState = MutableStateFlow(
        HomeUiState(
            greeting = currentGreeting(),
            dateLine = currentDateLine(),
            a11yEnabled = initialA11yEnabled,
            a11yStateKnown = initialA11yEnabled,
            a11yChecking = !initialA11yEnabled,
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    /** The user-curated Quick Actions grid (enabled, in display order). */
    private val _quickActions = MutableStateFlow<List<QuickActionType>>(emptyList())
    val quickActions: StateFlow<List<QuickActionType>> = _quickActions.asStateFlow()

    private var a11yRefreshJob: Job? = null
    private var a11yRefreshGeneration = 0L
    @Volatile private var deviceAdminActive = DeviceAdminUtils.isActive(app)
    @Volatile private var vpnConsentGranted = VpnService.prepare(app) == null

    // Last prefs snapshots so refresh() can recompute immediately instead of
    // waiting for the next DataStore emission.
    private var lastBlocking = BlockingPrefsState()
    private var lastAppLock = AppLockPrefsState()
    private var lastA11yProt = A11yProtectionPrefsState()
    private var lastPu = PreventUninstallPrefsState()
    private var lastVpn = DnsVpnSettings()
    private var lastFocus = FocusPrefsState()

    init {
        val prefs = combine(
            app.blockingPrefs().distinctUntilChanged(),
            app.appLockPrefs().distinctUntilChanged(),
            app.a11yProtectionPrefs().distinctUntilChanged(),
            app.preventUninstallPrefs().distinctUntilChanged(),
            app.dnsVpnSettings().distinctUntilChanged(),
        ) { blocking, appLock, a11yProt, pu, vpn ->
            PrefsSnapshot(blocking, appLock, a11yProt, pu, vpn)
        }
        viewModelScope.launch {
            combine(prefs, app.focusPrefs().distinctUntilChanged()) { snapshot, schedules ->
                lastBlocking = snapshot.blocking
                lastAppLock = snapshot.appLock
                lastA11yProt = snapshot.a11yProt
                lastPu = snapshot.pu
                lastVpn = snapshot.vpn
                lastFocus = schedules
                rebuild()
            }.collect { }
        }
        // Real activity feed (blocks, schedules, VPN/a11y changes).
        viewModelScope.launch {
            app.activityLog().distinctUntilChanged().collect { entries ->
                _uiState.update { it.copy(feed = entries.take(3)) }
            }
        }
        // User-curated Quick Actions grid.
        viewModelScope.launch {
            app.quickActionPrefs().collect { _quickActions.value = it }
        }
        refresh()
    }

    private fun rebuild() {
        val blocking = lastBlocking
        val a11y = a11yStatus
        val layers = ProtectionLayersEvaluator.evaluate(
            masterBlocking = blocking.blockingEnabled,
            accessibilityEnabled = a11y.enabled,
            vpnEnabled = lastVpn.enabled && vpnConsentGranted,
            appLockEnabled = lastAppLock.lockType != LockType.OFF,
            a11yProtectionEnabled = lastA11yProt.protectionEnabled,
            preventUninstallEnabled = lastPu.preventUninstallEnabled,
            hasEnabledFocusSession = lastFocus.schedules.any { it.enabled },
            hasContentRules =
                blocking.blocklistKeywords.isNotEmpty() || blocking.blockedWebsites.isNotEmpty(),
            hasTitleRules = blocking.titleBlockRules.isNotEmpty(),
            deviceAdminActive = deviceAdminActive,
        )
        val paused = !blocking.blockingEnabled
        val enabledFocusSessions = lastFocus.schedules.count { it.enabled }
        val titleRes = when {
            paused -> R.string.home_hero_paused
            layers.attention.isEmpty() -> R.string.home_hero_title
            else -> R.string.home_hero_partial
        }
        val subtitle = when {
            paused -> app.getString(R.string.home_hero_resume)
            layers.attention.isEmpty() ->
                app.getString(R.string.home_hero_all_active, layers.total)
            else ->
                app.getString(R.string.home_hero_layers_active, layers.active, layers.total) +
                    " · " +
                    app.getString(R.string.home_hero_attention, layers.attention.size)
        }
        val pillGreen = !paused && layers.attention.isEmpty()
        val pillTextRes = when {
            paused -> R.string.home_paused
            pillGreen -> R.string.home_protected
            else -> R.string.home_attention
        }
        _uiState.update {
            it.copy(
                masterProtection = blocking.blockingEnabled,
                // Keep the banner in sync with the cached system state on
                // every rebuild, not only after an explicit refresh().
                a11yEnabled = a11y.enabled,
                a11yStateKnown = a11y.stateKnown,
                a11yChecking = a11y.checking,
                blockedToday = blocking.blockedToday.toString(),
                focusSessionCount = enabledFocusSessions.toString(),
                heroProgress = if (paused) 0f else layers.progress,
                heroTitleRes = titleRes,
                heroSubtitle = subtitle,
                pillGreen = pillGreen,
                pillTextRes = pillTextRes,
            )
        }
    }

    fun toggleMasterProtection() {
        val enabled = !_uiState.value.masterProtection
        _uiState.update { it.copy(masterProtection = enabled) }
        val text = app.getString(
            if (enabled) R.string.home_toast_on else R.string.home_toast_off
        )
        _toasts.tryEmit(text)
        viewModelScope.launch {
            runCatching { app.setBlockingEnabled(enabled) }
        }
    }

    fun showToast(message: String) {
        _toasts.tryEmit(message)
    }

    fun refresh() {
        // Daily counter freshness: separate launch so the rollover is never
        // subject to the a11y job's delays/cancellation. No-op write when current.
        viewModelScope.launch {
            runCatching { app.maybeRolloverBlockedCounter() }
        }
        a11yRefreshJob?.cancel()
        val generation = ++a11yRefreshGeneration
        val previous = a11yStatus
        val keepKnownEnabled = previous.stateKnown && previous.enabled
        a11yStatus = previous.copy(
            stateKnown = keepKnownEnabled,
            checking = true,
        )
        _uiState.update {
            it.copy(
                a11yEnabled = previous.enabled,
                a11yStateKnown = keepKnownEnabled,
                a11yChecking = true,
            )
        }
        a11yRefreshJob = viewModelScope.launch {
            // AccessibilityManager can report a false negative during process
            // startup while the already-enabled service is still binding.
            delay(A11Y_STATE_SETTLE_MS)
            val readings = buildList {
                for (index in 0 until A11Y_NEGATIVE_CONFIRMATIONS) {
                    val enabled = isAccessibilityEnabled(app)
                    add(enabled)
                    if (enabled) break
                    if (index < A11Y_NEGATIVE_CONFIRMATIONS - 1) {
                        delay(A11Y_FALSE_CONFIRM_DELAY_MS)
                    }
                }
            }
            val enabledNow = !isStableA11yDisabled(readings, A11Y_NEGATIVE_CONFIRMATIONS)
            if (!isActive || generation != a11yRefreshGeneration) return@launch
            a11yStatus = A11yStatus(enabled = enabledNow, stateKnown = true, checking = false)
            deviceAdminActive = DeviceAdminUtils.isActive(app)
            vpnConsentGranted = VpnService.prepare(app) == null
            _uiState.update {
                it.copy(
                    a11yEnabled = enabledNow,
                    a11yStateKnown = true,
                    a11yChecking = false,
                    dateLine = currentDateLine(),
                )
            }
            rebuild()
        }
    }

    private companion object {
        const val A11Y_STATE_SETTLE_MS = 250L
        const val A11Y_FALSE_CONFIRM_DELAY_MS = 250L
        const val A11Y_NEGATIVE_CONFIRMATIONS = 4
    }

    private fun currentGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val res = when (hour) {
            in 5..11 -> R.string.home_greeting_morning
            in 12..16 -> R.string.home_greeting_afternoon
            else -> R.string.home_greeting_evening
        }
        return app.getString(res)
    }

    private fun currentDateLine(): String =
        SimpleDateFormat("EEEE, h:mm a", Locale.getDefault()).format(Date())
}

/** Bundle of the five independent prefs states consumed by the Home screen. */
private data class PrefsSnapshot(
    val blocking: BlockingPrefsState,
    val appLock: AppLockPrefsState,
    val a11yProt: A11yProtectionPrefsState,
    val pu: PreventUninstallPrefsState,
    val vpn: DnsVpnSettings,
)

private data class A11yStatus(
    val enabled: Boolean,
    val stateKnown: Boolean,
    val checking: Boolean,
)

internal fun isStableA11yDisabled(readings: List<Boolean>, requiredReads: Int): Boolean =
    readings.size >= requiredReads && readings.takeLast(requiredReads).none { it }
