package com.safeme.app.protect

import android.content.Context
import com.safeme.app.data.ACTIVITY_SCHEDULE
import com.safeme.app.data.ScheduleBlock
import com.safeme.app.data.addActivity
import com.safeme.app.data.schedulePrefs
import com.safeme.app.service.SafeMeAccessibilityService
import com.safeme.app.service.SafeMeVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Process-wide coordinator for Schedule-Based App Blocking.
 *
 * The ONLY component that turns schedule rules into enforcement:
 *  - launch-blocked apps → [SafeMeAccessibilityService] (gates the app on open)
 *  - internet-blocked apps → [SafeMeVpnService] (per-app-block tunnel mode)
 *  - next boundary → [ScheduleAlarmReceiver] (precise re-application at the
 *    next window start/end)
 *
 * Mirrors the reference `ScheduleEngine` singleton: idempotent, safe to call
 * from any thread, and only restarts the VPN when the active set actually
 * changes (so a no-op re-evaluation never tears down a healthy tunnel).
 */
object ScheduleEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var internetBlockedPackages: Set<String> = emptySet()

    @Volatile
    private var internetBlockAll = false

    @Volatile
    private var launchBlockedPackages: Set<String> = emptySet()

    @Volatile
    private var launchBlockAll = false

    /** Packages excluded from every schedule (never blocked, internet or launch). */
    @Volatile
    private var excludedPackages: Set<String> = emptySet()

    /** True when [pkg] must not launch right now (launch / UI blocking). */
    fun isLaunchBlocked(pkg: String): Boolean =
        !isExcluded(pkg) && (launchBlockAll || pkg in launchBlockedPackages)

    /** True when [pkg] must not reach the internet right now. */
    fun isInternetBlocked(pkg: String): Boolean =
        !isExcluded(pkg) && (internetBlockAll || pkg in internetBlockedPackages)

    /** True when [pkg] is on the global schedule-exclusion list. */
    fun isExcluded(pkg: String): Boolean = pkg in excludedPackages

    fun hasLaunchBlock(): Boolean = launchBlockAll || launchBlockedPackages.isNotEmpty()

    fun hasInternetBlock(): Boolean = internetBlockAll || internetBlockedPackages.isNotEmpty()

    /** True when a "block everything" schedule is currently launch-blocking. */
    fun isLaunchBlockAllActive(): Boolean = launchBlockAll

    // Test/diagnostic accessors.
    fun activeLaunchBlockedPackages(): Set<String> = launchBlockedPackages
    fun activeInternetBlockedPackages(): Set<String> = internetBlockedPackages

    /**
     * Re-load schedules from DataStore and apply. Called by [SafeMeApp] on
     * every persisted change and by [ScheduleAlarmReceiver] at boundaries.
     */
    suspend fun reevaluate(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val state = context.schedulePrefs().first()
        apply(context, state.schedules, state.excludedApps, nowMillis)
    }

    /**
     * Apply a schedule snapshot. Idempotent; safe to call repeatedly.
     * Never throws — enforcement failures degrade gracefully (a missed
     * boundary is caught by the alarm / safety ticker retry).
     */
    fun apply(
        context: Context,
        schedules: List<ScheduleBlock>,
        excludedApps: Set<String> = emptySet(),
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        try {
            val active = ScheduleEvaluator.evaluate(schedules, nowMillis)
            // Capture the prior exclusion set so a change to ONLY the exclusion
            // list (while a block is active) is detected and re-applied.
            val prevExcluded = excludedPackages
            excludedPackages = excludedApps
            // Filter the evaluator's active sets so excluded packages are never
            // enforced by any schedule (including "block all" schedules).
            val filteredInternet = active.internetBlockedPackages - excludedApps
            val filteredLaunch = active.launchBlockedPackages - excludedApps

            // Accessibility (launch / UI blocking): push the new set and poke
            // the service to re-check the current foreground window so a block
            // that starts while the app is already open takes effect.
            val launchChanged =
                filteredLaunch != launchBlockedPackages ||
                    active.launchBlockAll != launchBlockAll ||
                    excludedApps != prevExcluded
            launchBlockedPackages = filteredLaunch
            launchBlockAll = active.launchBlockAll
            if (launchChanged) {
                SafeMeAccessibilityService.onScheduleSetsChanged()
            }

            // VPN (internet blocking): restart only when the active set changed.
            val internetChanged =
                filteredInternet != internetBlockedPackages ||
                    active.internetBlockAll != internetBlockAll ||
                    excludedApps != prevExcluded
            internetBlockedPackages = filteredInternet
            internetBlockAll = active.internetBlockAll
            if (internetChanged) {
                SafeMeVpnService.applyScheduledBlocks(
                    filteredInternet,
                    active.internetBlockAll,
                    excludedApps,
                    context,
                )
            }

            // Re-arm the next boundary alarm.
            val next = ScheduleEvaluator.nextBoundary(schedules, nowMillis)
            ScheduleAlarmReceiver.scheduleBoundary(context, next)

            // Activity feed: log when a block actually turns ON (deduped by
            // the store, so re-applies and the safety ticker stay quiet).
            val launchOn = active.launchBlockAll || filteredLaunch.isNotEmpty()
            val internetOn = active.internetBlockAll || filteredInternet.isNotEmpty()
            if (launchChanged && launchOn) {
                logScheduleEvent(context, "Focus session active", "Apps paused until it ends")
            }
            if (internetChanged && internetOn) {
                logScheduleEvent(context, "Focus internet pause active", "Paused apps can't reach the web")
            }
        } catch (t: Throwable) {
            // Coordinator failures must never crash callers (App, receiver).
        }
    }

    private fun logScheduleEvent(context: Context, title: String, sub: String) {
        scope.launch {
            try {
                context.applicationContext.addActivity(ACTIVITY_SCHEDULE, title, sub)
            } catch (_: Throwable) {
            }
        }
    }
}
