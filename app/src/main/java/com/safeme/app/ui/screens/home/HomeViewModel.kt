package com.safeme.app.ui.screens.home

import android.app.Application
import android.net.VpnService
import androidx.lifecycle.viewModelScope
import com.safeme.app.R
import com.safeme.app.data.A11yProtectionPrefsState
import com.safeme.app.data.ActivityEntry
import com.safeme.app.data.AppLockPrefsState
import com.safeme.app.data.BlockingPrefsState
import com.safeme.app.data.DnsVpnSettings
import com.safeme.app.data.PreventUninstallPrefsState
import com.safeme.app.data.QuickActionType
import com.safeme.app.data.SchedulePrefsState
import com.safeme.app.data.a11yProtectionPrefs
import com.safeme.app.data.activityLog
import com.safeme.app.data.appLockPrefs
import com.safeme.app.data.blockingPrefs
import com.safeme.app.data.dnsVpnSettings
import com.safeme.app.data.maybeRolloverBlockedCounter
import com.safeme.app.data.preventUninstallPrefs
import com.safeme.app.data.quickActionPrefs
import com.safeme.app.data.schedulePrefs
import com.safeme.app.data.setBlockingEnabled
import com.safeme.app.protect.DeviceAdminUtils
import com.safeme.app.protect.ProtectedAction
import com.safeme.app.protect.ProtectionLayersEvaluator
import com.safeme.app.ui.screens.common.ProtectedViewModel
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
    val scheduleCount: String = "0",
    val heroProgress: Float = 0f,
    val heroTitleRes: Int = R.string.home_hero_title,
    val heroSubtitle: String = "",
    /** Header pill: green "Protected" vs amber "Paused"/"Attention". */
    val pillGreen: Boolean = true,
    val pillTextRes: Int = R.string.home_protected,
    /** Latest 3 real activity entries, newest first. */
    val feed: List<ActivityEntry> = emptyList(),
)

/**
 * Home dashboard.
 *
 * Extends [ProtectedViewModel] for COD-79 so [toggleMasterProtection] can gate
 * its OFF direction. Note this screen deliberately does **not** fold
 * `disableGate()` into the five-flow `combine` in [init]: the shield ring needs
 * `entryLockActive`, which the App Lock snapshot it already caches provides, and
 * restructuring that `combine` (plus its nested two-flow schedule combine) to add
 * a sixth input would risk the hero/progress calculation for no benefit. The
 * base class keeps its own small collector for the guard instead.
 */
class HomeViewModel(application: Application) : ProtectedViewModel(application) {

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
    private var lastSchedules = SchedulePrefsState()

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
            combine(prefs, app.schedulePrefs().distinctUntilChanged()) { snapshot, schedules ->
                lastBlocking = snapshot.blocking
                lastAppLock = snapshot.appLock
                lastA11yProt = snapshot.a11yProt
                lastPu = snapshot.pu
                lastVpn = snapshot.vpn
                lastSchedules = schedules
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
            // COD-79: layer 4 now means "the entry gate is genuinely armed",
            // not merely "a credential happens to be stored". With card 1 off
            // the app opens without a password, so counting that as an active
            // protection layer would overstate the shield ring. Reads the
            // snapshot this ViewModel already keeps, so the five-flow `combine`
            // above is untouched and evaluate()'s signature does not change —
            // ProtectionLayersTest's total stays valid.
            appLockEnabled = lastAppLock.entryLockActive,
            a11yProtectionEnabled = lastA11yProt.protectionEnabled,
            preventUninstallEnabled = lastPu.preventUninstallEnabled,
            hasEnabledSchedule = lastSchedules.schedules.any { it.enabled },
            hasContentRules =
                blocking.blocklistKeywords.isNotEmpty() || blocking.blockedWebsites.isNotEmpty(),
            hasTitleRules = blocking.titleBlockRules.isNotEmpty(),
            deviceAdminActive = deviceAdminActive,
        )
        val paused = !blocking.blockingEnabled
        val enabledSchedules = lastSchedules.schedules.count { it.enabled }
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
                scheduleCount = enabledSchedules.toString(),
                heroProgress = if (paused) 0f else layers.progress,
                heroTitleRes = titleRes,
                heroSubtitle = subtitle,
                pillGreen = pillGreen,
                pillTextRes = pillTextRes,
            )
        }
    }

    /**
     * Master blocking switch.
     *
     * Split by direction for COD-79: turning protection ON runs straight
     * through and is never gated, turning it OFF is a
     * [ProtectedAction.MASTER_BLOCKING] disable and may need the credential.
     */
    fun toggleMasterProtection() {
        if (_uiState.value.masterProtection) {
            guardDisable(ProtectedAction.MASTER_BLOCKING) { applyMasterProtection(false) }
        } else {
            applyMasterProtection(true)
        }
    }

    /**
     * The actual write, plus the optimistic UI flip and toast.
     *
     * Those two moved *inside* the guarded path deliberately. They used to run
     * before the write, which was harmless while nothing could intervene — but
     * with a verification prompt in between, flipping first would leave the
     * switch looking off after the user cancels, and the toast would announce a
     * change that never happened.
     */
    private fun applyMasterProtection(enabled: Boolean) {
        _uiState.update { it.copy(masterProtection = enabled) }
        _toasts.tryEmit(
            app.getString(if (enabled) R.string.home_toast_on else R.string.home_toast_off),
        )
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
