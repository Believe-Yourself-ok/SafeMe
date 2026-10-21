package com.safeme.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.safeme.app.MainActivity
import com.safeme.app.R
import com.safeme.app.data.ACTIVITY_VPN
import com.safeme.app.data.DnsVpnSettings
import com.safeme.app.data.NOTIF_CUSTOM
import com.safeme.app.data.NOTIF_HIDE
import com.safeme.app.data.addActivity
import com.safeme.app.data.dnsVpnSettings
import com.safeme.app.data.setVpnEnabled
import com.safeme.app.vpn.DnsPreset
import com.safeme.app.vpn.TunnelRestartPolicy
import com.safeme.app.vpn.VpnStatusStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * SafeMe DNS-filtering VPN service (reference-style, matching the
 * Protect-Yourself architecture).
 *
 * The tunnel is established WITHOUT routing any traffic into the TUN: the
 * builder only advertises a family-safe DNS resolver via [VpnService.Builder.addDnsServer]
 * (Cloudflare Family / AdGuard Family / custom preset). Android's system
 * resolver then sends every app's DNS query to that resolver, which performs
 * the actual adult-content filtering. The app does not relay packets and does
 * not evaluate a local blocklist — filtering is delegated entirely to the
 * family-safe DNS provider.
 *
 * - Whitelisted apps (and the app itself) bypass the VPN via [VpnService.Builder.addDisallowedApplication].
 * - [VpnService.Builder.allowBypass] lets apps that explicitly request it skip
 *   the VPN (required for some system services).
 * - Lifecycle: START_STICKY, serialized start/stop/restart, clean teardown on
 *   destroy, persisted enabled state restored at boot by [VpnBootReceiver].
 *
 * Known limitation (inherent to this architecture): because no traffic flows
 * through the TUN, the app cannot inspect or block encrypted DNS (DoH/DoT/DoQ).
 * Apps that use their own resolver (Chrome Secure DNS, hardcoded DNS) can
 * bypass the filter — the same tradeoff the reference project accepts.
 *
 * On-screen URL/keyword blocking for browsers is handled separately by
 * [SafeMeAccessibilityService] (which raises [com.safeme.app.BlockGateActivity]).
 */
class SafeMeVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.safeme.app.action.START_VPN"
        const val ACTION_STOP = "com.safeme.app.action.STOP_VPN"
        const val ACTION_STOP_PERSIST = "com.safeme.app.action.STOP_VPN_PERSIST"
        const val ACTION_UPDATE_NOTIF = "com.safeme.app.action.UPDATE_NOTIF"
        const val ACTION_RESTART = "com.safeme.app.action.RESTART_VPN"
        const val CHANNEL_ID = "vpn_filtering"
        const val NOTIFICATION_ID = 1

        const val TUN_ADDR_V4 = "10.0.0.2"
        const val TUN_ADDR_V6 = "fd00:10:0:0:2::2"
        const val TUN_MTU = 1280

        const val TUNNEL_RESTART_COOLDOWN_MS = 5000L

        @Volatile
        var isActive = false

        /**
         * Packages whose internet must be blocked right now (per-app-block
         * tunnel mode). Managed by [com.safeme.app.protect.FocusEngine].
         */
        @Volatile
        var focusBlockApps: Set<String> = emptySet()

        /**
         * True when a "block everything" schedule is internet-blocking: the
         * whole device's traffic is routed into the TUN and black-holed,
         * except SafeMe itself and the VPN whitelist.
         */
        @Volatile
        var focusBlockAll: Boolean = false

        /**
         * Global schedule exclusions: apps that must never be blocked by any
         * schedule. In block-all mode they are disallowed from the TUN alongside
         * SafeMe and the whitelist; targeted mode never lists them because the
         * engine filters them out before applying.
         */
        @Volatile
        var focusExcludeApps: Set<String> = emptySet()

        /**
         * Applies the current scheduled internet-block set. Updates the
         * service-side state, then:
         *  - tunnel already running → restarts it so it rebuilds in the mode
         *    matching the new set (per-app block / block-all / DNS-filter);
         *  - tunnel not running but a block is now active → STARTS the tunnel
         *    (consent is sticky once granted, so this works whenever the user
         *    has ever accepted the VPN; without consent there is nothing we
         *    can do silently, so the start is skipped);
         *  - nothing active → leaves the tunnel as-is.
         */
        fun applyFocusBlocks(packages: Set<String>, blockAll: Boolean, excluded: Set<String>, context: Context) {
            focusBlockApps = packages
            focusBlockAll = blockAll
            focusExcludeApps = excluded
            val intent = Intent(context, SafeMeVpnService::class.java)
            when {
                isActive || VpnStatusStore.active.value -> intent.setAction(ACTION_RESTART)
                (focusBlockAll || focusBlockApps.isNotEmpty()) &&
                    VpnService.prepare(context) == null -> intent.setAction(ACTION_START)
                else -> return
            }
            runCatching { context.startService(intent) }
        }
    }

    private val commandExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SafeMe-VpnCommand").apply { isDaemon = true }
    }
    // Written by the command executor, read by the main thread in onDestroy's
    // teardown — must be volatile so closeInterface() never misses a freshly
    // assigned fd and leaves an orphaned tunnel running behind a dead service.
    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null

    /** Detects unexpected tunnel death (fd closed by the system without onRevoke). */
    @Volatile
    private var watchdogThread: Thread? = null
    private var lastTunnelRestartAt = 0L

    /**
     * Set once the service is being torn down. Guards against a command that
     * was already in flight (or queued) when [onDestroy] ran: it must not
     * establish a tunnel behind a destroyed service, which would orphan a
     * live VPN with no notification and no clean teardown.
     */
    @Volatile
    private var destroyed = false

    /** Last activity-log state (active vs stopped) so restarts don't spam the feed. */
    @Volatile
    private var lastLoggedVpnActive: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("SafeMeVpn", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                // User turned the VPN off from the UI (persisted state is already
                // handled by the caller). Stop the service itself, or the
                // foreground notification would linger with no tunnel behind it.
                dispatchCommand {
                    stopTunnel()
                    // Scheduled internet blocks are independent of the cosmetic
                    // switch — re-arm the tunnel in block mode instead of
                    // letting the restriction silently lapse.
                    if (hasFocusInternetBlock()) {
                        startTunnel()
                    } else {
                        stopSelf()
                    }
                }
            }
            ACTION_STOP_PERSIST -> {
                dispatchCommand {
                    stopTunnel()
                    runBlocking { setVpnEnabled(false) }
                    if (hasFocusInternetBlock()) {
                        startTunnel()
                    } else {
                        stopSelf()
                    }
                }
            }
            ACTION_UPDATE_NOTIF -> {
                dispatchCommand {
                    if (isActive) {
                        runCatching {
                            startForegroundWithNotification(runBlocking { dnsVpnSettings().first() })
                        }
                    }
                }
            }
            ACTION_RESTART -> {
                dispatchCommand {
                    restartTunnel()
                }
            }
            else -> {
                dispatchCommand {
                    if (vpnInterface == null || !isActive) {
                        startTunnel()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun dispatchCommand(command: () -> Unit) {
        if (commandExecutor.isShutdown) return
        runCatching { commandExecutor.execute(command) }
    }

    override fun onDestroy() {
        Log.d("SafeMeVpn", "onDestroy called")
        destroyed = true
        runBlocking {
            stopTunnel()
        }
        commandExecutor.shutdown()
        super.onDestroy()
    }

    private fun startTunnel() {
        try {
            Log.d("SafeMeVpn", "startTunnel begin")
            stopTunnel()

            val vpnSettings = runBlocking { dnsVpnSettings().first() }
            // The tunnel runs when the VPN feature is on OR a schedule is
            // internet-blocking right now (mirrors the reference: scheduled
            // blocks keep enforcement even when the cosmetic switch is off).
            if (!vpnSettings.enabled && !hasFocusInternetBlock()) {
                stopSelf()
                return
            }

            if (!establishAndActivate(vpnSettings)) {
                isActive = false
                VpnStatusStore.setActive(false)
                stopSelf()
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            isActive = false
            VpnStatusStore.setActive(false)
            closeInterface()
            stopSelf()
        }
    }

    private fun hasFocusInternetBlock(): Boolean =
        focusBlockAll || focusBlockApps.isNotEmpty()

    /**
     * Reference-style DNS-filtering tunnel: advertise the family-safe resolver
     * via [VpnService.Builder.addDnsServer] and deliberately add NO routes, so
     * no traffic is routed into the TUN. Android's resolver uses the advertised
     * DNS servers and the family-safe provider performs the filtering.
     */
    private fun establishTunnel(vpnSettings: DnsVpnSettings): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .addAddress(TUN_ADDR_V4, 32)
                .addAddress(TUN_ADDR_V6, 128)
                .setMtu(TUN_MTU)

            when (vpnSettings.preset) {
                DnsPreset.ADGUARD_FAMILY -> {
                    builder.addDnsServer("94.140.14.15")
                    builder.addDnsServer("2a10:50c0::ad1:ff")
                }
                DnsPreset.CLOUDFLARE_FAMILY -> {
                    builder.addDnsServer("1.1.1.3")
                    builder.addDnsServer("2606:4700:4700::1113")
                }
                DnsPreset.CUSTOM -> {
                    val v4 = vpnSettings.customV4
                    val v6 = vpnSettings.customV6
                    if (v4.isBlank() && v6.isBlank()) {
                        // A tunnel with no resolver would silently disable
                        // filtering — fall back to the family-safe default.
                        builder.addDnsServer("1.1.1.3")
                        builder.addDnsServer("2606:4700:4700::1113")
                    } else {
                        if (v4.isNotBlank()) builder.addDnsServer(v4)
                        if (v6.isNotBlank()) builder.addDnsServer(v6)
                    }
                }
            }

            // PER_APP_BLOCK mode: active when a schedule targets specific apps
            // or the whole device. Only the targeted apps' traffic enters the
            // TUN (addRoute + addAllowedApplication) and, since nothing is
            // ever forwarded from the TUN, it is black-holed — those apps get
            // no internet while every other app keeps working. allowBypass()
            // must NOT be called here: it would let targeted apps circumvent
            // the block (same pattern as the reference / NetGuard).
            val perAppBlockMode = focusBlockAll || focusBlockApps.isNotEmpty()
            if (perAppBlockMode) {
                runCatching { builder.addRoute("0.0.0.0", 0) }
                runCatching { builder.addRoute("::", 0) }
                if (focusBlockAll) {
                    // Block everything except SafeMe + the VPN whitelist.
                    runCatching { builder.addDisallowedApplication(packageName) }
                    vpnSettings.whitelist.forEach { pkg ->
                        runCatching { builder.addDisallowedApplication(pkg) }
                    }
                    // Excluded apps are also exempt from the block-all tunnel.
                    focusExcludeApps.forEach { pkg ->
                        runCatching { builder.addDisallowedApplication(pkg) }
                    }
                } else {
                    // Block only the targeted apps; SafeMe and whitelisted apps
                    // are not in the allowed list, so they bypass automatically.
                    focusBlockApps.forEach { pkg ->
                        runCatching { builder.addAllowedApplication(pkg) }
                    }
                }
            } else {
                // DNS_FILTER mode (normal): whitelisted apps (and the app
                // itself) bypass the VPN entirely; everyone else uses the
                // family-safe resolver.
                runCatching { builder.addDisallowedApplication(packageName) }
                vpnSettings.whitelist.forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
                // Apps that explicitly request it may bypass the VPN (some
                // system services require this). DNS filtering still applies
                // to all other apps.
                builder.allowBypass()
            }

            builder.establish()
        } catch (_: Exception) {
            null
        }
    }

    private fun establishAndActivate(vpnSettings: DnsVpnSettings): Boolean {
        // A teardown may have been queued while this command was in flight —
        // never start a foreground notification or a tunnel for a dead service.
        if (destroyed) return false
        startForegroundWithNotification(vpnSettings)
        Log.d("SafeMeVpn", "startForeground called, establishing tunnel")
        val pfd = establishTunnel(vpnSettings) ?: return false
        vpnInterface = pfd
        if (destroyed) {
            // The service was destroyed while the tunnel was being established
            // (onDestroy runs on the main thread, not the command executor).
            // Check AFTER assigning vpnInterface so the teardown path (and any
            // concurrent onDestroy stopTunnel) always sees the fresh fd and
            // closes it — never leaving an orphaned VPN with no service.
            isActive = false
            VpnStatusStore.setActive(false)
            closeInterface()
            return false
        }
        isActive = true
        VpnStatusStore.setActive(true)
        logVpnTransition("VPN active", "Internet filtering is on")
        // A successful establish is a fresh attempt: reset the anti-storm
        // window so a user-initiated restart (preset/whitelist change) is
        // never gated by a stale stamp from an earlier watchdog cycle — the
        // watchdog only gives up if the tunnel dies within 5s of the LAST
        // successful establish.
        lastTunnelRestartAt = System.currentTimeMillis()
        startWatchdog(pfd)
        Log.d("SafeMeVpn", "tunnel established (DNS-filter mode)")
        return true
    }

    private fun restartTunnel() {
        try {
            stopTunnel()
            val vpnSettings = runBlocking { dnsVpnSettings().first() }
            // A scheduled internet block keeps the tunnel alive even when the
            // cosmetic VPN switch is off (mirrors startTunnel's guard).
            if (!vpnSettings.enabled && !hasFocusInternetBlock()) {
                stopSelf()
                return
            }
            if (!establishAndActivate(vpnSettings)) {
                isActive = false
                VpnStatusStore.setActive(false)
                stopSelf()
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            isActive = false
            VpnStatusStore.setActive(false)
            closeInterface()
            stopSelf()
        }
    }

    /**
     * A healthy DNS-only tunnel never delivers data, so a blocking read on the
     * fd simply waits until the system closes the tunnel under us (network
     * handover, another VPN taking over, airplane mode). That return is the
     * only signal that filtering silently stopped — re-establish it so the
     * UI state, the notification and the filter stay honest. A cooldown
     * guards against a restart loop if the tunnel keeps dying immediately.
     */
    private fun startWatchdog(pfd: ParcelFileDescriptor) {
        watchdogThread?.interrupt()
        val watcher = Thread({
            // Captured before the thread starts, so it is never read before
            // assignment; used to identify this watchdog instance later.
            val me = Thread.currentThread()
            try {
                val buffer = ByteArray(2048)
                while (true) {
                    val outcome = try {
                        // A DNS-only tunnel never delivers data, so a blocking
                        // read just waits; a non-blocking fd reports EAGAIN.
                        // Only a genuine error (EBADF / closed fd) means the
                        // tunnel died. Treating EAGAIN/EINTR as death would
                        // tear the tunnel down right after establish.
                        TunnelRestartPolicy.onRead(
                            Os.read(pfd.fileDescriptor, buffer, 0, buffer.size).toLong(),
                            errno = null,
                        )
                    } catch (e: ErrnoException) {
                        TunnelRestartPolicy.onRead(-1, e.errno)
                    }
                    when (outcome) {
                        TunnelRestartPolicy.Outcome.KEEP_WAITING -> {
                            // Transient (EAGAIN / interrupt wake): back off
                            // briefly before re-reading. Data never arrives in
                            // this DNS-only architecture; the loop exists only
                            // as a liveness detector, so the same backoff is
                            // fine for a delivered read too.
                            Thread.sleep(50)
                            continue
                        }
                        TunnelRestartPolicy.Outcome.TUNNEL_DEAD -> break
                    }
                }
            } catch (_: Throwable) {
                // fd closed / read error / interrupt — fall through to restart handling.
            }
            dispatchCommand {
                // Only the CURRENT watchdog may act. A stale thread whose
                // tunnel was deliberately torn down (or already replaced by a
                // restart) must not re-establish anything — otherwise every
                // ACTION_RESTART could trigger a second, spurious teardown
                // once the new tunnel is already up.
                if (watchdogThread !== me) return@dispatchCommand
                if (!isActive) return@dispatchCommand
                val now = System.currentTimeMillis()
                if (TunnelRestartPolicy.shouldStopInsteadOfRestart(
                        lastTunnelRestartAt,
                        now,
                        TUNNEL_RESTART_COOLDOWN_MS,
                    )
                ) {
                    stopTunnel()
                    stopSelf()
                    return@dispatchCommand
                }
                lastTunnelRestartAt = now
                restartTunnel()
            }
        }, "SafeMe-VpnWatchdog")
        watchdogThread = watcher
        watcher.isDaemon = true
        watcher.start()
    }

    private fun closeInterface() {
        vpnInterface?.let { runCatching { it.close() } }
        vpnInterface = null
    }

    private fun stopTunnel() {
        Log.d("SafeMeVpn", "stopTunnel called")
        watchdogThread?.interrupt()
        watchdogThread = null
        // Only report a user-visible stop when the tunnel was actually up
        // (startTunnel calls stopTunnel() as a reset on a cold start).
        val wasActive = isActive
        closeInterface()
        if (wasActive) {
            logVpnTransition("VPN stopped", "Internet filtering is off")
        }
        isActive = false
        VpnStatusStore.setActive(false)
    }

    /**
     * Appends a VPN state event to the activity log, deduped so restarts and
     * re-arms only appear once per real on/off transition.
     */
    private fun logVpnTransition(title: String, sub: String) {
        val active = title.startsWith("VPN active")
        if (lastLoggedVpnActive == active) return
        lastLoggedVpnActive = active
        try {
            commandExecutor.execute {
                runCatching {
                    runBlocking { applicationContext.addActivity(ACTIVITY_VPN, title, sub) }
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onRevoke() {
        Log.d("SafeMeVpn", "onRevoke called")
        dispatchCommand {
            stopTunnel()
            runBlocking { setVpnEnabled(false) }
            stopSelf()
        }
        super.onRevoke()
    }

    private fun startForegroundWithNotification(vpnSettings: DnsVpnSettings) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val contentText = when (vpnSettings.notifMode) {
            NOTIF_CUSTOM -> if (vpnSettings.notifCustom.isNotBlank()) {
                vpnSettings.notifCustom
            } else {
                getString(R.string.vpn_notif_active)
            }
            NOTIF_HIDE -> getString(R.string.vpn_notif_hidden)
            else -> getString(R.string.vpn_notif_active)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, SafeMeVpnService::class.java).setAction(ACTION_STOP_PERSIST)
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notif_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.vpn_notif_stop), stopPending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
