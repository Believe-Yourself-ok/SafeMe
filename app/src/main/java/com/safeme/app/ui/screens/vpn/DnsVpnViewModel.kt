package com.safeme.app.ui.screens.vpn

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.viewModelScope
import com.safeme.app.data.AppCatalog
import com.safeme.app.data.InstalledApp
import com.safeme.app.data.NOTIF_DEFAULT
import com.safeme.app.data.dnsVpnSettings
import com.safeme.app.data.setVpnCustomDns
import com.safeme.app.data.setVpnEnabled
import com.safeme.app.data.setVpnNotifCustom
import com.safeme.app.data.setVpnNotifMode
import com.safeme.app.data.setVpnPreset
import com.safeme.app.data.setVpnWhitelist
import com.safeme.app.protect.ProtectedAction
import com.safeme.app.service.SafeMeVpnService
import com.safeme.app.ui.screens.common.ProtectedViewModel
import com.safeme.app.vpn.DnsPreset
import com.safeme.app.vpn.PrivateDnsFilter
import com.safeme.app.vpn.VpnStatusStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


data class DnsVpnUiState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val preset: DnsPreset = DnsPreset.CLOUDFLARE_FAMILY,
    val customV4: String = "",
    val customV6: String = "",
    val whitelist: Set<String> = emptySet(),
    val notifMode: String = NOTIF_DEFAULT,
    val notifCustom: String = "",
    val running: Boolean = false,
    val installedApps: List<InstalledApp> = emptyList(),
    val appsLoaded: Boolean = false,
)

class DnsVpnViewModel(application: Application) : ProtectedViewModel(application) {

    private companion object {
        /** Pause before persisting/refreshing the custom notification text. */
        const val NOTIF_CUSTOM_DEBOUNCE_MS = 400L
    }

    private var notifCustomJob: Job? = null

    // Application-scoped so a pending notification-text flush survives the
    // ViewModel being cleared (viewModelScope is cancelled at that point).
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(DnsVpnUiState())
    val uiState: StateFlow<DnsVpnUiState> = _uiState.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private val _consentRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val consentRequest: SharedFlow<Unit> = _consentRequest.asSharedFlow()

    private var pendingEnable = false

    /**
     * Set while the user has explicitly turned the VPN on but the tunnel is
     * still being established. Keeps the toggle "on" during that brief window
     * even though [VpnStatusStore.active] is not yet true, so enabling does not
     * cause a visual flip. Cleared once the tunnel comes up, or if the
     * persisted state is (re)written to false (tunnel failed / revoked).
     */
    private var optimisticEnabled = false

    init {
        viewModelScope.launch {
            getApplication<Application>().dnsVpnSettings().collect { settings ->
                // A persisted "disabled" write (onRevoke, tunnel-establishment
                // failure, ACTION_STOP_PERSIST) also means the optimistic enable
                // is no longer valid — and the toggle must flip back off even if
                // the tunnel-state flow never emitted (e.g. establish() failed
                // while the store was already false).
                if (!settings.enabled) optimisticEnabled = false
                _uiState.update {
                    it.copy(
                        loading = false,
                        // Only ever force the toggle OFF from persisted state.
                        // A persisted "true" is intentionally NOT applied here:
                        // it may be stale (process died while the VPN was running
                        // and onRevoke() never fired), and the real signal for
                        // "on" is the live tunnel state from VpnStatusStore.
                        enabled = if (settings.enabled) it.enabled else false,
                        preset = settings.preset,
                        customV4 = settings.customV4,
                        customV6 = settings.customV6,
                        whitelist = settings.whitelist,
                        notifMode = settings.notifMode,
                        notifCustom = settings.notifCustom,
                    )
                }
            }
        }
        // Real-time tunnel status: reflects the actual system VPN state,
        // including revocations that never reach the ViewModel otherwise.
        // The toggle's `enabled` is DERIVED from the live tunnel state rather
        // than the persisted preference, so a system-side disable (notification
        // shade / Settings, another VPN taking over, or a dead process whose
        // onRevoke() never ran) is reflected immediately — the persisted value
        // alone can be stale for a long time in those cases.
        viewModelScope.launch {
            VpnStatusStore.active.collect { active ->
                if (active) optimisticEnabled = false
                _uiState.update { state ->
                    state.copy(
                        running = active,
                        enabled = active || optimisticEnabled,
                    )
                }
            }
        }
        loadInstalledApps()
    }

    override fun onCleared() {
        // Flush any value still inside the debounce window so the last
        // keystrokes before leaving the screen aren't lost — including an
        // empty string, otherwise a cleared field would revert to stale text
        // (viewModelScope is cancelled right after this, so the pending job
        // can't run). onCleared runs on the main thread, so the write is done
        // on an application-scoped IO scope instead of blocking teardown with
        // runBlocking.
        notifCustomJob?.cancel()
        val pending = _uiState.value.notifCustom
        flushScope.launch {
            getApplication<Application>().setVpnNotifCustom(pending)
        }
        super.onCleared()
    }

    fun showToast(message: String) {
        _toasts.tryEmit(message)
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            // PackageManager queries + label loading are slow; never run them on
            // the main thread or the screen's first frame is delayed.
            val apps = withContext(Dispatchers.Default) { enumerateApps() }
            _uiState.update { it.copy(installedApps = apps, appsLoaded = true) }
        }
    }

    private fun enumerateApps(): List<InstalledApp> = AppCatalog.load(getApplication())

    fun toggle() {
        val state = _uiState.value
        if (state.loading) return
        when {
            // COD-79: this is the only weakening branch. `disable()` sets
            // optimisticEnabled = false before it writes, so gating the whole
            // call — not just the write inside it — is what keeps a cancelled
            // prompt from flipping the switch visually.
            state.enabled && state.running ->
                guardDisable(ProtectedAction.DNS_VPN) { disable() }
            state.enabled && !state.running -> {
                // The user's intent is "on" but the tunnel is down (e.g. the
                // system disabled it, or a START_STICKY restart failed).
                // Re-establish rather than flip the persisted state off.
                // Never gated: this restores protection the user already asked
                // for, it does not remove any.
                reEnable()
            }
            else -> enable()
        }
    }

    private fun reEnable() {
        viewModelScope.launch {
            startService()
            showToast("Re-enabling VPN filtering")
        }
    }

    private fun enable() {
        val app = getApplication<Application>()
        if (VpnService.prepare(app) != null) {
            pendingEnable = true
            _consentRequest.tryEmit(Unit)
            return
        }
        doEnable()
    }

    private fun doEnable() {
        pendingEnable = false
        optimisticEnabled = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            app.setVpnEnabled(true)
            startService()
            applySystemDnsFor(_uiState.value.preset)
            _uiState.update { it.copy(enabled = true) }
            checkRunning()
            showToast("VPN filtering enabled")
        }
    }

    private fun disable() {
        optimisticEnabled = false
        viewModelScope.launch {
            val app = getApplication<Application>()
            stopService()
            app.setVpnEnabled(false)
            PrivateDnsFilter.restore(app)
            _uiState.update { it.copy(enabled = false, running = false) }
            showToast("VPN filtering disabled")
        }
    }

    fun onConsentResult() {
        val app = getApplication<Application>()
        if (VpnService.prepare(app) == null) {
            if (pendingEnable) {
                doEnable()
            }
        } else {
            pendingEnable = false
            showToast("VPN permission not granted")
        }
    }

    fun checkRunning() {
        _uiState.update { state ->
            val active = VpnStatusStore.active.value
            if (active) optimisticEnabled = false
            state.copy(running = active, enabled = active || optimisticEnabled)
        }
    }

    fun selectPreset(preset: DnsPreset) {
        viewModelScope.launch {
            getApplication<Application>().setVpnPreset(preset)
            _uiState.update { it.copy(preset = preset) }
            if (_uiState.value.enabled) applySystemDnsFor(preset)
            restartIfRunning()
        }
    }

    /**
     * Keeps the system-wide Private DNS filter in step with the preset while
     * filtering is on: family presets map to a DoT hostname, CUSTOM cannot
     * (an IP has no TLS identity) so the previous system settings come back
     * and filtering falls back to the VPN-advertised resolver only.
     */
    private suspend fun applySystemDnsFor(preset: DnsPreset) {
        val app = getApplication<Application>()
        val applied = PrivateDnsFilter.apply(app, preset)
        if (!applied && PrivateDnsFilter.dotHostname(preset) != null) {
            if (!PrivateDnsFilter.hasPermission(app)) {
                showToast("Grant WRITE_SECURE_SETTINGS for system-wide filtering")
            }
        }
    }

    fun saveCustomDns(v4: String, v6: String): Boolean {
        val cleanV4 = v4.trim()
        val cleanV6 = v6.trim()
        if (!isValidIpv4(cleanV4)) {
            showToast("Invalid IPv4 address")
            return false
        }
        if (cleanV6.isNotEmpty() && !isValidIpv6(cleanV6)) {
            showToast("Invalid IPv6 address")
            return false
        }
        viewModelScope.launch {
            val app = getApplication<Application>()
            app.setVpnCustomDns(cleanV4, cleanV6)
            app.setVpnPreset(DnsPreset.CUSTOM)
            _uiState.update {
                it.copy(customV4 = cleanV4, customV6 = cleanV6, preset = DnsPreset.CUSTOM)
            }
            if (_uiState.value.enabled) PrivateDnsFilter.restore(app)
            restartIfRunning()
            showToast("Custom DNS saved")
        }
        return true
    }

    fun setNotifMode(mode: String) {
        viewModelScope.launch {
            getApplication<Application>().setVpnNotifMode(mode)
            _uiState.update { it.copy(notifMode = mode) }
            refreshNotificationIfRunning()
        }
    }

    fun setNotifCustom(text: String) {
        // Keep the field responsive, but defer the DataStore write and the
        // live notification refresh until the user pauses typing — otherwise
        // every keystroke rewrites prefs and re-posts the foreground
        // notification while the VPN runs.
        _uiState.update { it.copy(notifCustom = text) }
        notifCustomJob?.cancel()
        notifCustomJob = viewModelScope.launch {
            delay(NOTIF_CUSTOM_DEBOUNCE_MS)
            // Flush the latest value, not the text from the cancelled event.
            getApplication<Application>().setVpnNotifCustom(_uiState.value.notifCustom)
            refreshNotificationIfRunning()
        }
    }

    fun toggleWhitelistApp(pkg: String) {
        val current = _uiState.value.whitelist
        val updated = if (pkg in current) current - pkg else current + pkg
        _uiState.update { it.copy(whitelist = updated) }
        persistWhitelistOnce()
    }

    /** Selects every app in the picker; one atomic persist + tunnel restart. */
    fun selectAllWhitelistApps() {
        _uiState.update {
            it.copy(whitelist = it.whitelist + it.installedApps.map { app -> app.packageName })
        }
        persistWhitelistOnce()
    }

    /** Deselects every app in the picker; one atomic persist + tunnel restart. */
    fun deselectAllWhitelistApps() {
        _uiState.update {
            it.copy(whitelist = it.whitelist - it.installedApps.map { app -> app.packageName })
        }
        persistWhitelistOnce()
    }

    /**
     * Persists the current whitelist once and restarts the tunnel if needed.
     *
     * Single choke point for all three whitelist controls (toggle one app,
     * select all, deselect all), and deliberately ungated: whitelisting an app
     * exempts it from DNS filtering while filtering itself stays ON, which makes
     * it an *exemption* control rather than a disable. See the scope boundary in
     * [com.safeme.app.protect.DisableProtectionPolicy] — the same rule that
     * leaves the excluded-apps lists and the keyword/site allow-lists ungated.
     */
    private fun persistWhitelistOnce() {
        viewModelScope.launch {
            getApplication<Application>().setVpnWhitelist(_uiState.value.whitelist)
            restartIfRunning()
        }
    }

    fun applyWhitelist() {
        val count = _uiState.value.whitelist.size
        showToast(if (count == 0) "Whitelist cleared" else "$count whitelisted")
    }

    private fun restartIfRunning() {
        if (_uiState.value.enabled && VpnStatusStore.active.value) {
            // The restart tears the tunnel down and re-establishes it, flipping
            // VpnStatusStore.active false→true. Keep the toggle on during that
            // window with the same optimistic mechanism enable() uses; the
            // active-flow collector clears it once the new tunnel is up (or a
            // failed re-establish leaves the toggle on so a tap re-enables).
            optimisticEnabled = true
            _uiState.update { it.copy(enabled = true) }
            val app = getApplication<Application>()
            val intent = Intent(app, SafeMeVpnService::class.java)
                .setAction(SafeMeVpnService.ACTION_RESTART)
            runCatching { app.startService(intent) }
        }
    }

    /**
     * Notification-only changes must not tear down and rebuild the tunnel
     * (that would drop every app's connections for a cosmetic change).
     * Instead the running service is asked to refresh its notification.
     */
    private fun refreshNotificationIfRunning() {
        if (!VpnStatusStore.active.value) return
        val app = getApplication<Application>()
        val intent = Intent(app, SafeMeVpnService::class.java)
            .setAction(SafeMeVpnService.ACTION_UPDATE_NOTIF)
        runCatching { app.startService(intent) }
    }

    private fun startService() {
        val app = getApplication<Application>()
        val intent = Intent(app, SafeMeVpnService::class.java)
            .setAction(SafeMeVpnService.ACTION_START)
        app.startForegroundService(intent)
    }

    private fun stopService() {
        val app = getApplication<Application>()
        val intent = Intent(app, SafeMeVpnService::class.java)
            .setAction(SafeMeVpnService.ACTION_STOP)
        runCatching { app.startService(intent) }
    }

    private fun isValidIpv4(ip: String): Boolean {
        if (ip.isEmpty()) return false
        val parts = ip.split('.')
        if (parts.size != 4) return false
        for (part in parts) {
            if (part.isEmpty()) return false
            if (part.length > 1 && part.startsWith("0")) return false
            if (part.any { !it.isDigit() }) return false
            val value = part.toIntOrNull() ?: return false
            if (value < 0 || value > 255) return false
        }
        return true
    }

    private fun isValidIpv6(ip: String): Boolean {
        if (ip.isEmpty()) return false
        val candidate = ip.trim('[', ']')
        if (candidate.contains('.')) return false
        val addr = runCatching { java.net.InetAddress.getByName(candidate) }.getOrNull()
        return addr is java.net.Inet6Address
    }
}
