package com.safeme.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.safeme.app.BlockGateActivity
import com.safeme.app.BlockOverlayController
import com.safeme.app.R
import com.safeme.app.data.BlockingPrefsState
import com.safeme.app.data.BundledKeywords
import com.safeme.app.data.SocialBlockingState
import com.safeme.app.data.TitleBlockRule
import com.safeme.app.data.TitleMatchMode
import com.safeme.app.data.blockingPrefs
import com.safeme.app.data.contentEnginePrefs
import com.safeme.app.data.normalizeDomain
import com.safeme.app.data.preventUninstallPrefs
import com.safeme.app.data.socialBlockingPrefs
import com.safeme.app.protect.A11yProtectionGuard
import com.safeme.app.protect.A11yProtectionUtils
import com.safeme.app.protect.BrowserUrlGate
import com.safeme.app.protect.DeviceAdminUtils
import com.safeme.app.protect.ImageVideoSearchGate
import com.safeme.app.protect.PrivateDnsBlockers
import com.safeme.app.protect.ProtectedSystemPages
import com.safeme.app.protect.FocusEngine
import com.safeme.app.protect.SocialBlockingGate
import com.safeme.app.protect.UninstallBlockers
import com.safeme.app.protect.VpnBlockers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.util.Locale

internal const val SCHEDULE_RECHECK_THROTTLE_MS = 5_000L

internal fun shouldThrottleFocusRecheck(
    lastRecheckMs: Long,
    nowMs: Long,
    force: Boolean,
): Boolean = !force && nowMs - lastRecheckMs < SCHEDULE_RECHECK_THROTTLE_MS

/**
 * True when either the event-emitting package or the actual foreground
 * package is on the exclude list. Dual-checking is required because events
 * arrive from every installed package (the accessibility config has no
 * packageNames filter): an IME / GMS / systemui event can carry the
 * foreground app's texts through the rootInActiveWindow tree walk, so
 * checking only the event package would let a foreign event block an
 * excluded app.
 */
internal fun isExcludedFromContentEngine(
    eventPkg: String?,
    fgPkg: String?,
    excluded: Set<String>,
): Boolean = (eventPkg != null && eventPkg in excluded) ||
    (fgPkg != null && fgPkg in excluded)

/**
 * True when the content engine would be evaluating SafeMe's OWN UI: either
 * the event was emitted by our own package, or the foreground window being
 * walked belongs to us. Our screens legitimately contain blocklist words
 * (the keyword editor, the exclude-apps sheet copy, the gate's own
 * "Why:" text), and foreign-package events (IME, systemui) can carry those
 * texts through the rootInActiveWindow tree walk — so without this guard
 * SafeMe gates itself. Unconditional: the app picker deliberately hides
 * our own package, so users cannot self-exclude via the exclude list.
 */
internal fun isOwnUiEvent(
    ownPackage: String?,
    eventPkg: String?,
    fgPkg: String?,
): Boolean = ownPackage != null && (eventPkg == ownPackage || fgPkg == ownPackage)

/**
 * [App content re-check] Search results pages usually paint via
 * CONTENT_CHANGED events AFTER the window-state event already saw an
 * unpainted page; without probing those events the keyword/URL engine
 * stays blind until a later window-state carries the rendered text.
 * Deliberate clicks probe immediately (the gate cooldown dedupes
 * double-fires); content-changed/focus floods are throttled.
 */
internal const val APP_CONTENT_RECHECK_THROTTLE_MS = 250L

internal fun shouldThrottleAppContentRecheck(
    lastRecheckMs: Long,
    nowMs: Long,
    isClick: Boolean,
): Boolean = !isClick && nowMs - lastRecheckMs < APP_CONTENT_RECHECK_THROTTLE_MS

internal fun shouldThrottleSocialTabRecheck(
    lastRecheckMs: Long,
    nowMs: Long,
    isClick: Boolean,
): Boolean = !isClick && nowMs - lastRecheckMs < SocialBlockingGate.APP_CONTENT_RECHECK_THROTTLE_MS

/**
 * Core blocking engine.
 *
 * Reacts to [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED] (plus a PU-only,
 * tree-based path for content-changed/click/focus events that catch in-page
 * Settings navigation), walks the active window's node tree collecting
 * visible text/titles/descriptions and URL-ish strings, then matches
 * them against the merged (bundled + user custom) keyword and website lists. Title rules
 * are matched against the window title only ([AccessibilityEvent.getText] on
 * window-state-changed events), never against body text, so a rule like "Apps" can't fire
 * just because the Settings home list shows an "Apps" entry. On a match it raises the
 * block gate over the offending app by launching [BlockGateActivity].
 *
 * Robustness guarantees:
 *  - Every event is processed inside a try/catch; a malformed event or node tree can never
 *    crash the service.
 *  - DataStore failures degrade to a safe default state (empty lists, blocking enabled).
 *  - Dedup + cooldown prevents re-launching the gate for the same window in a loop.
 *  - The engine skips SafeMe's own package, so the gate itself never re-triggers a block.
 */
class SafeMeAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Serial background dispatcher for accessibility-event processing. The
     * framework recycles [AccessibilityEvent] objects once onAccessibilityEvent
     * returns, so the fields are snapshotted on the main thread and the actual
     * detection (node-tree walks, Binder probes, gate launches) runs HERE —
     * never on the main looper, which is the SAME thread BlockGateActivity
     * renders on. Before this, heavy Settings pages (and the App Info page's
     * render event storm) jammed the main thread, delaying the gate and
     * degrading the whole app. Serial (limitedParallelism(1)) so events are
     * processed in arrival order, exactly like the old main-thread dispatch.
     */
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    /**
     * Immutable snapshot of the event fields the engine uses. Extracted on the
     * main thread in [onAccessibilityEvent] before the framework recycles the
     * live event; [clickedTexts] carries the clicked node's subtree texts (the
     * source node dies with the event).
     */
    private data class EventSnapshot(
        val type: Int,
        val pkg: String?,
        val cls: String?,
        val texts: List<String>,
        val clickedTexts: List<String>,
        val windowId: Int,
    )

    @Volatile
    private var cachedState: BlockingPrefsState? = null

    @Volatile
    private var cachedPuEnabled: Boolean = false

    @Volatile
    private var cachedImageVideoSearchEnabled: Boolean = false

    @Volatile
    private var cachedSocialState: SocialBlockingState? = null

    @Volatile
    private var lastSocialWholeBlockKey: String? = null

    @Volatile
    private var lastSocialWholeBlockAt: Long = 0L

    @Volatile
    private var lastSocialTabProbeMs: Long = 0L

    private val socialTabCooldown = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastBlockKey: String? = null

    @Volatile
    private var lastBlockAt: Long = 0L

    @Volatile
    private var titleScopeWarned: Boolean = false

    @Volatile
    private var lastA11yPageKickMs: Long = 0L

    /** Last app-name framework probe timestamp — throttles [nodeTreeContainsText] on watchdog ticks. */
    @Volatile
    private var lastAppNameProbeMs: Long = 0L

    /**
     * Last app-name framework probe timestamp for the EVENT path — throttles
     * [nodeTreeContainsText] separately from the watchdog so a recent watchdog
     * probe can never starve a fresh window-state detection (the previous
     * shared 5 s window silently defeated the event path on pages whose tree
     * isn't ready at event time).
     */
    @Volatile
    private var lastEventAppNameProbeMs: Long = 0L

    @Volatile
    private var lastPuKickToastMs: Long = 0L

    /** Latest foreground package seen on a window-state-changed event — gates the PU watchdog cheaply. */
    @Volatile
    private var lastForegroundPkg: String? = null

    /** Latest foreground window class — gives the PU watchdog the current page identity for gating. */
    @Volatile
    private var lastForegroundCls: String? = null

    /** PU watchdog job — periodically re-probes the active window so our a11y detail page is evicted on every activation. */
    private var puWatchdogJob: Job? = null

    /** Separate cooldown tracker for PU blocks — [M1 fix] prevents PU cooldown from suppressing keyword blocks. */
    @Volatile
    private var lastPuBlockAt: Long = 0L

    /** Last PU content-event tree probe timestamp — throttles CONTENT_CHANGED/focus floods. */
    @Volatile
    private var lastPuContentProbeMs: Long = 0L

    /** Last image/video-search content-event tree probe timestamp — throttles event floods. */
    @Volatile
    private var lastImageVideoProbeMs: Long = 0L

    /** Last app content-event re-check timestamp — throttles event floods. */
    @Volatile
    private var lastAppContentRecheckMs: Long = 0L

    /** Stored runnable for the eviction toast — [M3 fix] allows cancellation on service destroy. */
    private var pendingToastRunnable: Runnable? = null

    /** Last post-dismissal re-probe chain start — dedupes chains per dismissal window. */
    @Volatile
    private var lastPostDismissalChainMs: Long = 0L

    /** Last post-page re-probe chain start — dedupes chains per Settings page open. */
    @Volatile
    private var lastPuSurfaceReprobeMs: Long = 0L

    /**
     * [Launcher pre-empt] Our app name recorded when the user LONG-PRESSES
     * our launcher icon — the first gesture toward the "App info" menu item.
     * A subsequent menu click within [LAUNCHER_MENU_PREEMPT_WINDOW_MS] raises
     * the PU gate BEFORE Settings renders the App Info page (Settings cold
     * start on Vivo is 0.2–3.6 s — the entire window the page would otherwise
     * be exposed). Cleared on any launcher click that is NOT a pre-empt.
     */
    @Volatile
    private var lastLauncherLongPressApp: String? = null

    /** Window id of the long-pressed icon's window — popup clicks live in a DIFFERENT window. */
    @Volatile
    private var lastLauncherLongPressWindowId: Int = -1

    @Volatile
    private var lastLauncherLongPressMs: Long = 0L

    /** Separate cooldown tracker for schedule blocks — never suppresses keyword/PU blocks. */
    @Volatile
    private var lastFocusBlockKey: String? = null

    @Volatile
    private var lastFocusBlockAt: Long = 0L

    @Volatile
    private var lastFocusRecheckMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // One-time synchronous read to ensure cachedState has the correct values
        // even if the collect flow fails or lags on rebind.
        runCatching {
            runBlocking {
                val initial = this@SafeMeAccessibilityService.blockingPrefs().first()
                cachedState = initial
                Log.d(TAG, "exclude: initial cachedState excluded=${initial.excludedApps}")
            }
        }.onFailure { t ->
            cachedState = BlockingPrefsState()
            Log.w(TAG, "exclude: initial cachedState read failed — using defaults", t)
        }
        runCatching {
            runBlocking {
                val initialSocial = this@SafeMeAccessibilityService.socialBlockingPrefs().first()
                cachedSocialState = initialSocial
                Log.d(TAG, "social: initial cachedSocial enabled=${initialSocial.enabled} whole=${initialSocial.wholeBlocked.size} tabs=${initialSocial.activeTabs}")
            }
        }.onFailure { t ->
            cachedSocialState = SocialBlockingState()
            Log.w(TAG, "social: initial read failed — using defaults", t)
        }
        serviceScope.launch {
            try {
                preventUninstallPrefs().collect {
                    cachedPuEnabled = it.preventUninstallEnabled
                }
            } catch (t: Throwable) {
                Log.d(TAG, "preventUninstallPrefs collect failed — assuming PU disabled", t)
                cachedPuEnabled = false
            }
        }
        serviceScope.launch {
            try {
                contentEnginePrefs().collect {
                    cachedImageVideoSearchEnabled = it.blockImageVideoSearch
                }
            } catch (t: Throwable) {
                cachedImageVideoSearchEnabled = false
            }
        }
        serviceScope.launch {
            try {
                blockingPrefs().collect { state ->
                    cachedState = state
                }
            } catch (t: Throwable) {
                Log.d(TAG, "blockingPrefs collect failed — using initial cached state", t)
            }
        }
        serviceScope.launch {
            try {
                socialBlockingPrefs().collect { state ->
                    cachedSocialState = state
                }
            } catch (t: Throwable) {
                Log.w(TAG, "socialBlockingPrefs collect failed — using last cached", t)
            }
        }
        // PU watchdog: enforces eviction of our own a11y detail page on every
        // activation (not just window-state-changed events).
        startPuWatchdog()
        // [Overlay gate] a11y overlay windows belong to the service connection;
        // if the system reset it (OEM rebind), the window is silently removed
        // while the controller still thinks it is showing — re-assert it now.
        BlockOverlayController.reassertIfShowing(this)
        // Re-arm the accessibility-protection guard + self-heal (background;
        // no-op when the protection toggle is off).
        A11yProtectionUtils.selfHealAllAsync(this)
        A11yProtectionGuard.getInstance().ensureWatching(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // [Main-thread fix] Extract every field we use NOW — the framework
        // recycles the event (and its source node) as soon as this callback
        // returns. The heavy detection runs on [eventScope] (background), so
        // the main looper — which also renders BlockGateActivity — is never
        // blocked by tree walks or Binder probes.
        val type = event.eventType
        val pkg = runCatching { event.packageName?.toString() }.getOrNull()
        val cls = runCatching { event.className?.toString() }.getOrNull()
        val texts = runCatching {
            event.text?.map { it.toString() } ?: emptyList()
        }.getOrDefault(emptyList())
        val windowId = runCatching { event.windowId }.getOrDefault(-1)
        val clickedTexts = if (type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
        ) {
            runCatching { readClickedSourceTexts(event) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val snapshot = EventSnapshot(type, pkg, cls, texts, clickedTexts, windowId)
        eventScope.launch {
            try {
                handleEvent(snapshot)
            } catch (t: Throwable) {
                // Never crash the service on a malformed event.
            }
        }
    }

    /**
     * Bounded texts of the CLICKED node's subtree, read on the main thread
     * (the event's source node is recycled with the event). Used by the
     * App Info click pre-empt.
     */
    private fun readClickedSourceTexts(event: AccessibilityEvent): List<String> {
        val source = runCatching { event.source }.getOrNull() ?: return emptyList()
        try {
            return collectTextsFrom(source)
        } finally {
            recycle(source)
        }
    }

    /**
     * Consumes the gate-dismissal signal set by [BlockGateActivity.onDestroy]
     * and re-arms the PU + keyword cooldowns. Called at the top of every event
     * and watchdog tick so a rapid re-open of a protected surface gates
     * immediately — the previous dismissal-only re-arm depended on the
     * window-state event for the revealed window actually being delivered,
     * which OEMs (Vivo/FuntouchOS) drop or delay.
     */
    private fun rearmCooldownsIfGateDismissed() {
        if (Companion.consumeGateDismissedPending()) {
            lastPuBlockAt = 0L
            lastBlockAt = 0L
            lastSocialWholeBlockAt = 0L
            lastSocialWholeBlockKey = null
            socialTabCooldown.clear()
            lastSocialTabProbeMs = 0L
            Log.d(TAG, "PU: gate dismissed — cooldowns re-armed (incl social)")
            schedulePostDismissalReprobes()
        }
    }

    /**
     * Called by [Companion.onGateDismissed] — re-arms the cooldowns and starts
     * the post-dismissal re-probe chain immediately, with no dependency on
     * accessibility-event delivery (the same rationale as the re-arm flag:
     * OEMs like Vivo drop or delay the reveal window-state event).
     */
    fun onGateDismissedImmediate() {
        rearmCooldownsIfGateDismissed()
    }

    /**
     * [Re-gate latency] Probes the window revealed by a gate dismissal at
     * short, event-independent intervals so a protected page underneath is
     * re-covered as soon as its tree is ready — waiting for the next 1 s
     * watchdog tick would leave the page exposed for up to a second.
     *
     * The probe mirrors the gate-dismissal branch of [handleEvent]: our own
     * a11y detail page is EVICTED (one-shot cover discipline — never re-gate
     * over an a11y-management screen, the a11y kill vector), every other
     * protected page is gated. Deduped by the gate cooldown and the
     * 2 s eviction throttle, so overlapping probes are harmless.
     */
    private fun schedulePostDismissalReprobes() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPostDismissalChainMs < POST_DISMISSAL_CHAIN_MIN_INTERVAL_MS) return
        lastPostDismissalChainMs = now
        for (delayMs in POST_DISMISSAL_PROBE_DELAYS) {
            serviceScope.launch {
                delay(delayMs)
                try {
                    if (cachedPuEnabled) probeAfterGateDismissal()
                } catch (t: Throwable) {
                    // Never let a malformed probe break the chain.
                }
            }
        }
    }

    /** One post-dismissal probe — see [schedulePostDismissalReprobes]. */
    private fun probeAfterGateDismissal() {
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return
        try {
            val pkg = runCatching { root.packageName?.toString() }.getOrNull() ?: return
            val ownPackage = applicationContext.packageName ?: return
            // Gate window still animating out — active window is still ours.
            if (pkg == ownPackage) return
            if (!ProtectedSystemPages.isPuSurface(pkg)) return
            val cls = runCatching { root.className?.toString() }.getOrDefault("")
            if (isOurA11yDetailPageInTree(root)) {
                // One-shot cover discipline over a11y screens — evict, never
                // re-gate (re-gating over an a11y page is the kill vector).
                evictFromOurA11yServicePage()
                return
            }
            if (isPrivateDnsTargetPageInTree(root, pkg, cls.orEmpty())) {
                launchPuGate(pkg)
                return
            }
            if (isVpnTargetPageInTree(root, pkg, cls.orEmpty())) {
                launchPuGate(pkg)
                return
            }
            if (isOurUninstallTargetPageInTree(root, pkg, cls.orEmpty())) {
                launchPuGate(pkg)
            }
        } finally {
            recycle(root)
        }
    }

    /**
     * [App Info latency] After a window-state event on a Settings-family page
     * whose detection found nothing, re-probe the page at short intervals: the
     * tree is often still populating when the event arrives (Vivo renders
     * slowly), so the App Info detection can miss and the page would sit open
     * until the next 1 s watchdog tick — the variability the user sees. These
     * probes collapse that gap to a few hundred ms. Same list-safe detectors as
     * the watchdog (a11y detail → gate-or-evict; app-info / device-admin /
     * force-stop → gate; everything else → no-op), deduped by the gate cooldown
     * and a per-page-chain throttle, so overlapping probes are harmless.
     */
    private fun schedulePuSurfaceReprobe() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPuSurfaceReprobeMs < PU_SURFACE_REPROBE_MIN_INTERVAL_MS) return
        lastPuSurfaceReprobeMs = now
        for (delayMs in PU_SURFACE_REPROBE_DELAYS) {
            serviceScope.launch {
                delay(delayMs)
                try {
                    if (!cachedPuEnabled) return@launch
                    val root = runCatching { rootInActiveWindow }.getOrNull()
                        ?: return@launch
                    try {
                        val pkg = runCatching { root.packageName?.toString() }.getOrNull()
                            ?: return@launch
                        val ownPackage = applicationContext.packageName ?: return@launch
                        if (pkg == ownPackage || pkg == "com.android.systemui") return@launch
                        if (!ProtectedSystemPages.isPuSurface(pkg)) return@launch
                        val cls = runCatching { root.className?.toString() }
                            .getOrDefault("").orEmpty()
                        if (isOurA11yDetailPageInTree(root)) {
                            gateOrEvictOurA11yDetailPage(pkg)
                        } else if (isPrivateDnsTargetPageInTree(root, pkg, cls)) {
                            launchPuGate(pkg)
                        } else if (isVpnTargetPageInTree(root, pkg, cls)) {
                            launchPuGate(pkg)
                        } else {
                            // Side-effecting: gates when the page matches.
                            isOurUninstallTargetPageInTree(root, pkg, cls)
                        }
                    } finally {
                        recycle(root)
                    }
                } catch (_: Throwable) {
                    // Never let a malformed probe break the chain.
                }
            }
        }
    }

    private fun handleEvent(snapshot: EventSnapshot) {
        rearmCooldownsIfGateDismissed()
        // [Overlay gate] While the overlay is up, the foreground stays the
        // app BELOW (the overlay is NOT_FOCUSABLE, so our package never
        // becomes the active window). Skip all detection during the cover:
        // the underlying page's events would otherwise re-trigger gates on
        // every tick (deduped by cooldowns, but wasteful). Dismissal re-arms
        // the cooldowns via [BlockOverlayController.dismiss] →
        // [onGateDismissed], which pokes this service directly.
        if (BlockOverlayController.isShowing()) return
        if (snapshot.type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // [PU content events] In-page Settings navigation — most
            // importantly OUR OWN a11y detail page hosted in a generic
            // SubSettings container — opens without a window-state change, so
            // the window path misses it and only the 1 s PU watchdog caught it.
            // Route the new event types (content-changed / click / focus) into
            // a deliberately narrow, PU-only tree check; they never touch the
            // keyword/schedule engines.
            if (cachedPuEnabled) {
                try {
                    handlePreventUninstallContentEvent(snapshot)
                } catch (t: Throwable) {
                    Log.w(TAG, "PU content-event check failed — fail open", t)
                }
            }
            // [Image/video search gate] Browser-scoped content-event probe:
            // image-search results pages usually render without a fresh
            // window-state change, so the window path misses them. Same
            // fail-open discipline as the PU branch above.
            if (cachedImageVideoSearchEnabled &&
                ImageVideoSearchGate.isImageSearchBrowser(snapshot.pkg)
            ) {
                try {
                    handleImageVideoSearchProbe(snapshot)
                } catch (t: Throwable) {
                    Log.w(TAG, "imgvid content-event check failed — fail open", t)
                }
            }
            // [App content re-check] Search results pages in ANY app
            // (browsers, YouTube, Gmail, Play Store, …) usually paint
            // via CONTENT_CHANGED events AFTER the window-state event saw
            // an unpainted page — without this probe the keyword/URL engine
            // stays blind until a later window-state carries the rendered
            // text. Same fail-open discipline as the branches above.
            try {
                handleAppContentRecheck(snapshot)
            } catch (t: Throwable) {
                Log.w(TAG, "app content-event check failed — fail open", t)
            }
            // [Social tab gate] Tab overlay probe for Shorts/Reels/Spotlight on content events
            if (cachedSocialState?.enabled == true) {
                try {
                    handleSocialTabContentEvent(snapshot)
                } catch (t: Throwable) {
                    Log.w(TAG, "social tab content-event check failed — fail open", t)
                }
            }
            return
        }

        val pkg = snapshot.pkg ?: return
        val ownPackage = applicationContext.packageName ?: return
        // True when OUR full-screen window (the block gate) was foreground
        // before this event — i.e. the gate was just dismissed onto [pkg].
        val prevOwnWindow = lastForegroundPkg == ownPackage
        lastForegroundPkg = pkg
        lastForegroundCls = snapshot.cls.orEmpty()
        if (pkg == ownPackage) return

        // Gate dismissed onto a new window: kick the PU watchdog immediately
        // so a protected page underneath is re-probed now instead of on the
        // next cadence tick. While the gate was up the watchdog skipped
        // probing (see [puWatchdogTick]), so this is the resume point.
        if (prevOwnWindow) {
            // A gate was dismissed: re-arm the PU AND keyword cooldowns so a
            // protected page underneath is re-gated at once (not after the 4 s
            // window), then kick the watchdog now. (Also handled via
            // [rearmCooldownsIfGateDismissed] from the gate's onDestroy — this
            // event path is the second, redundant signal.)
            lastPuBlockAt = 0L
            lastBlockAt = 0L
            try {
                // If the dismissed gate was over OUR a11y detail page, it has
                // been shown once — bounce to HOME now (never let the user
                // reach the page, and never leave a cover looping over an
                // a11y-management screen). Otherwise kick the watchdog to
                // re-probe the revealed window.
                val root = runCatching { rootInActiveWindow }.getOrNull()
                var a11yDetailActive = false
                if (root != null) {
                    try {
                        a11yDetailActive = isOurA11yDetailPageInTree(root)
                    } finally {
                        recycle(root)
                    }
                }
                if (a11yDetailActive) {
                    evictFromOurA11yServicePage()
                } else {
                    puWatchdogTick()
                }
            } catch (_: Throwable) {
            }
        }

        // Prevent Uninstall guards are independent of the master blocking
        // switch: they gate only on the PU DataStore flag.
        if (cachedPuEnabled) {
            if (handlePreventUninstall(snapshot, pkg)) return
            // [App Info latency] The window-state detection can miss when the
            // page tree is still populating (Vivo renders slowly); re-probe the
            // page shortly so a tree that fills in just after the event still
            // gates before the next 1 s watchdog tick.
            if (ProtectedSystemPages.isPuSurface(pkg)) {
                schedulePuSurfaceReprobe()
            }
        }

        // FocusMe: launch/UI blocking is independent of the
        // keyword engine and the master blocking switch.
        if (isFocusBlocked(pkg)) {
            launchFocusGate(pkg)
            return
        }

        // Social Media Blocking — independent of BlockingPrefsState.blockingEnabled
        // Whole-app launch gate (persistent) + in-app tab gate (Shorts/Reels/Spotlight)
        try {
            val social = cachedSocialState
            if (social != null && social.enabled) {
                // Whole-app: block on launch (TYPE_WINDOW_STATE_CHANGED)
                if (SocialBlockingGate.isWholeAppBlocked(pkg, social.wholeBlocked)) {
                    val key = "socialWhole|$pkg"
                    val now = SystemClock.elapsedRealtime()
                    if (!(lastSocialWholeBlockKey == key && now - lastSocialWholeBlockAt < COOLDOWN_MS)) {
                        lastSocialWholeBlockKey = key
                        lastSocialWholeBlockAt = now
                        launchSocialWholeGate(pkg)
                    }
                    return
                }
                // Tab gate: only for allow-list pkgs, throttled 250ms + 4s cooldown per pkg|vertical
                val vertical = SocialBlockingGate.verticalFor(pkg)
                if (vertical != null && SocialBlockingGate.isVerticalEnabled(vertical, social.youtube, social.facebook, social.snapchat)) {
                    val now = SystemClock.elapsedRealtime()
                    if (!shouldThrottleSocialTabRecheck(lastSocialTabProbeMs, now, false)) {
                        lastSocialTabProbeMs = now
                        val key = SocialBlockingGate.throttleKey(pkg, vertical)
                        val last = socialTabCooldown[key] ?: 0L
                        if (now - last >= SocialBlockingGate.GATE_COOLDOWN_MS) {
                            val root = try { rootInActiveWindow } catch (_: Throwable) { null }
                            if (root != null) {
                                try {
                                    val tabNode = SocialBlockingGate.findTabNode(root, vertical)
                                    if (tabNode != null) {
                                        try {
                                            socialTabCooldown[key] = now
                                            launchSocialTabGate(pkg, vertical)
                                            return
                                        } finally {
                                            try { tabNode.recycle() } catch (_: Throwable) {}
                                        }
                                    }
                                } finally {
                                    recycle(root)
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "social gate check failed — fail open", t)
        }

        val state = cachedState ?: return
        if (!state.blockingEnabled) return
        // Exclude-apps: selected apps are NEVER blocked by the keyword /
        // porn-keyword / title / URL content engine — no master toggle, the
        // membership alone suppresses. Dual-check (event + foreground pkg)
        // because foreign-package events can carry this app's window texts.
        val fgPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            ?: lastForegroundPkg ?: pkg
        // Own UI: our screens legitimately contain blocklist words (keyword
        // editor, exclude-apps sheet, gate "Why:" text) — never evaluate
        // them, whoever emitted the event (IME events carry our texts).
        if (isOwnUiEvent(ownPackage, pkg, fgPkg)) {
            Log.d(TAG, "exclude: own UI suppressed (event=$pkg fg=$fgPkg)")
            return
        }
        if (isExcludedFromContentEngine(pkg, fgPkg, state.excludedApps)) {
            Log.d(TAG, "exclude: suppressed content engine (event=$pkg fg=$fgPkg)")
            return
        }

        evaluateContentEngine(snapshot, pkg, state)
    }

    /**
     * Shared content-engine evaluation: browser URL-bar gate first, then the
     * generic keyword/title engine. Used by BOTH the window-state path and
     * the [handleAppContentRecheck] probe so the two paths can never
     * drift apart. Callers own the exclusion guard + master-switch checks.
     */
    private fun evaluateContentEngine(
        snapshot: EventSnapshot,
        pkg: String,
        state: BlockingPrefsState,
    ) {
        // Browser URL-bar gate: dedicated site detection for supported
        // browsers, evaluated BEFORE the generic keyword engine so URL-shaped
        // text matches domain rules exactly instead of relying on body-text
        // substrings. Suppression-first inside [BrowserUrlGate.evaluate];
        // cooldown dedup shares the keyword-engine key space.
        if (BrowserUrlGate.isBrowserPackage(pkg)) {
            collectUrlCandidates(snapshot)?.let { candidates ->
                BrowserUrlGate.evaluate(candidates, state)
            }?.let { urlMatch ->
                val key = "$pkg|${urlMatch.value}"
                val now = SystemClock.elapsedRealtime()
                if (!(lastBlockKey == key && now - lastBlockAt < COOLDOWN_MS)) {
                    lastBlockKey = key
                    lastBlockAt = now
                    launchGate(pkg, MatchResult(urlMatch.value, urlMatch.type))
                }
                return
            }
        }

        val texts = collectTexts(snapshot.texts)
        if (texts.isEmpty()) return

        val match = findMatch(texts, state)
            ?: findTitleMatchIfSettings(pkg, snapshot.texts, state)
            ?: return
        val key = "$pkg|${match.value}"
        val now = SystemClock.elapsedRealtime()
        if (lastBlockKey == key && now - lastBlockAt < COOLDOWN_MS) return

        lastBlockKey = key
        lastBlockAt = now
        launchGate(pkg, match)
    }

    /**
     * Prevent Uninstall (anti-tamper) guards, scoped to SafeMe only.
     *
     * While the feature is ON it protects exactly four surfaces. The first three
     * are identified by SafeMe's own package/app-name/service-description on a
     * settings-family window; the fourth (Private DNS) by class + window title
     * only, since it carries no SafeMe identity:
     *
     *  1. Our accessibility-service DETAIL page — the PU gate (Block screen)
     *     is raised FIRST on every activation; dismissing it bounces to HOME
     *     (see [handleEvent]), so the user never reaches the page and the gate
     *     is never left covering an a11y-management screen. A persistent cover
     *     is the a11y kill vector (Android auto-disables a service whose
     *     window obscures those screens), so the gate is one-shot, never a
     *     loop.
     *  2. All OTHER a11y-management screens (lists, other apps' detail pages)
     *     — never blocked, only self-healed.
     *  3. SafeMe's own App Info / Device Admin deactivation / force-stop /
     *     uninstall-confirmation pages — blocked with the PU gate. The stock
     *     uninstall confirmation (packageinstaller UninstallerActivity) is
     *     included in the guard surface.
     *  4. Private DNS settings page — blocked with the PU gate. The app sets
     *     the system-wide DNS resolver here, so editing it bypasses SafeMe's
     *     DNS protection. Sits alongside the app-info branch in [handleEvent]
     *     and every PU tree probe; see [PrivateDnsBlockers] for the
     *     false-positive discipline (class + window title, never body text).
     *  5. VPN settings page — blocked with the PU gate. The app drives per-app
     *     blocking through its own VPN tunnel, so editing this page can disable
     *     or reset that tunnel. It sits with the Private DNS page (class +
     *     window title only, never body text); see [VpnBlockers].
     *
     * Every branch is fail-open: on any error the event is NOT blocked
     * (a false positive on a legitimate Settings page is worse than a false
     * negative).
     *
     * @return true when the event was consumed by a PU guard.
     */
    private fun handlePreventUninstall(snapshot: EventSnapshot, pkg: String): Boolean {
        return try {
            if (pkg == "com.android.systemui") return false
            if (!ProtectedSystemPages.isPuSurface(pkg)) return false

            val cls = snapshot.cls.orEmpty()
            // [H2 fix] Removed SubSettings catch-all — only actual a11y management
            // markers should enter the a11y branch. SubSettings is the AOSP container
            // for every Settings sub-page; catching all of them adds unnecessary
            // throttled node-tree probes on non-a11y pages.
            val a11yContext =
                ProtectedSystemPages.isAccessibilityManagementScreen(pkg, cls)

            // 1. Our own a11y detail page → gate first (Block screen), then
            //    HOME on dismissal (see [handleEvent]) — never a silent
            //    redirect, never a looping cover over an a11y screen. Within
            //    the post-dismissal window it is EVICTED, never re-gated (a
            //    reveal window-state event can race the eviction when the
            //    gate was launched by the watchdog, so prevOwnWindow was
            //    never set and this branch is the last line of defense).
            if (a11yContext) {
                if (isOurA11yServiceDetailPage(snapshot)) {
                    gateOrEvictOurA11yDetailPage(pkg)
                    return true
                }
                // Any other a11y-management screen is left untouched.
                return false
            }

            // 2. Private DNS settings page — a tamper surface (the app sets the
            //    system-wide resolver here). No SafeMe app name is on it, so it
            //    can't go through the app-info path below; class + window-title
            //    only (see [PrivateDnsBlockers] for the FP discipline).
            if (isPrivateDnsTargetPage(snapshot, pkg, cls)) {
                Log.d(TAG, "PU: blocking private dns page (pkg=$pkg cls=$cls)")
                launchPuGate(pkg)
                return true
            }

            // 3. VPN settings page — a tamper surface (the app drives per-app
            //    blocking through its own VPN tunnel). No SafeMe app name is
            //    reliably on it, so it can't go through the app-info path below;
            //    class + window-title only (see [VpnBlockers] for the FP
            //    discipline).
            if (isVpnTargetPage(snapshot, pkg, cls)) {
                Log.d(TAG, "PU: blocking vpn page (pkg=$pkg cls=$cls)")
                launchPuGate(pkg)
                return true
            }

            // 4. App Info / Device Admin / force-stop / uninstall pages for OUR app.
            if (!isOurUninstallTargetPage(snapshot, pkg, cls)) {
                Log.d(TAG, "PU: not a target (pkg=$pkg cls=$cls)")
                return false
            }
            Log.d(TAG, "PU: blocking page (pkg=$pkg cls=$cls)")
            // [M1 fix] PU blocks use a separate cooldown key (set inside
            // [launchPuGate]) so they don't suppress keyword blocking during
            // the cooldown window.
            launchPuGate(pkg)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "handlePreventUninstall failed — fail open", t)
            false
        }
    }

    /**
     * [PU content events] Tree-based PU check for non-window events (clicks,
     * focus, content-changed). In-page Settings navigation — most importantly
     * our OWN a11y detail page hosted in a generic SubSettings container —
     * fires these events but no window-state change, so the window path misses
     * it and the 2 s watchdog was the only backstop. This narrow path probes
     * the active window with the SAME list-safe fingerprints as the watchdog,
     * but on the event path (~100 ms latency instead of up to 2 s).
     *
     * Safety: only [isOurUninstallTargetPageInTree] runs. Its service-label
     * guard means a11y-management content is either gated (detail page) or
     * skipped (list page), and every other branch still requires SafeMe's own
     * app name on the page — so a click on a list row or a scroll through
     * Settings can never raise the gate. CONTENT_CHANGED / focus floods are
     * throttled; deliberate clicks probe immediately (deduped by the PU gate
     * cooldown).
     */
    private fun handlePreventUninstallContentEvent(snapshot: EventSnapshot) {
        rearmCooldownsIfGateDismissed()
        val pkg = snapshot.pkg ?: return
        val ownPackage = applicationContext.packageName ?: return
        if (pkg == ownPackage || pkg == "com.android.systemui") return

        // [Launcher pre-empt] Long-press on OUR launcher icon → "App info" /
        // "Remove" menu click: raise the gate NOW, before Settings starts.
        // The launcher is never a PU surface, so this branch runs ahead of the
        // surface check below; launcher clicks never reach the tree probe.
        if (isLauncherPackage(pkg)) {
            handleLauncherPreempt(snapshot, pkg)
            return
        }

        if (!ProtectedSystemPages.isPuSurface(pkg)) return

        val now = SystemClock.elapsedRealtime()
        val throttled = snapshot.type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            snapshot.type == AccessibilityEvent.TYPE_VIEW_FOCUSED
        if (throttled && now - lastPuContentProbeMs < PU_CONTENT_PROBE_THROTTLE_MS) return
        lastPuContentProbeMs = now

        val cls = lastForegroundCls.orEmpty()
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return
        try {
            // [Re-gate race fix] Same post-dismissal discipline as the
            // watchdog: a content/click event right after a gate dismissal
            // must EVICT our own a11y detail page, never re-gate it (the
            // shared core below would otherwise raise the gate over the
            // just-revealed page and loop the cover). Only a fresh
            // activation — outside the window — lets the core gate.
            if (Companion.isWithinPostDismissalWindow() &&
                isOurA11yDetailPageInTree(root)
            ) {
                evictFromOurA11yServicePage()
                return
            }
            // [App Info latency] Pre-emptive cover on the navigating click: the
            // user tapped a Settings row that is ABOUT SafeMe (the Apps-list
            // row, a search result, a notifications row) — the navigation to
            // the tamper surface (App Info) is about to happen. Raise the gate
            // NOW, before the page renders, so the Uninstall / Force-stop /
            // Clear-data buttons are never tappable. The clicked row's subtree
            // (snapshot by [readClickedSourceTexts]) must carry the APP NAME
            // and NOT the service label (a11y rows go through the normal
            // detail-page path below — this pre-empt deliberately never
            // touches a11y-management content).
            if (snapshot.type == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                clickedNodeIsOurAppRow(snapshot.clickedTexts)
            ) {
                launchPuGate(pkg)
                return
            }
            // Private DNS settings page: gates on class + window title (no SafeMe
            // app name). The PDNS page/dialog often fires content/focus events
            // (not a window-state change) after an in-place row tap, so probe the
            // active window's title here too.
            if (isPrivateDnsTargetPageInTree(root, pkg, cls)) {
                launchPuGate(pkg)
                return
            }
            // VPN settings page: gates on class + window title (no SafeMe app
            // name). Like PDNS, the VPN page can fire content/focus events (not
            // a window-state change) after an in-place row tap, so probe the
            // active window's title here too.
            if (isVpnTargetPageInTree(root, pkg, cls)) {
                launchPuGate(pkg)
                return
            }
            // The shared core raises the PU gate ITSELF for our own a11y
            // detail page (the service-label guard calls [launchPuGate] and
            // then returns false), so this call is side-effecting — its return
            // value is not a "not gated" signal and the gate cooldown dedupes.
            isOurUninstallTargetPageInTree(root, pkg, cls)
        } finally {
            recycle(root)
        }
    }

    /**
     * [Image/video search gate] Browser-scoped tree probe for non-window-state
     * events: collects the active-window text pool (event texts + bounded walk),
     * then requires BOTH an images/videos search-URL signature AND an adult
     * keyword in the same pool before raising the gate. Throttled on
     * content-changed/focus floods like the PU path; deliberate clicks probe
     * immediately (the cooldown dedupes double-fires).
     */
    private fun handleImageVideoSearchProbe(snapshot: EventSnapshot) {
        rearmCooldownsIfGateDismissed()
        val pkg = snapshot.pkg ?: return
        val ownPackage = applicationContext.packageName ?: return
        if (pkg == ownPackage || pkg == "com.android.systemui") return
        if (!ImageVideoSearchGate.isImageSearchBrowser(pkg)) return

        val now = SystemClock.elapsedRealtime()
        val throttled = snapshot.type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            snapshot.type == AccessibilityEvent.TYPE_VIEW_FOCUSED
        if (throttled && now - lastImageVideoProbeMs < IMGVID_PROBE_THROTTLE_MS) return
        lastImageVideoProbeMs = now

        // Exclude-apps: bypass the image/video search gate for excluded
        // packages — membership alone suppresses, no master toggle. Dual-check
        // mirrors the window path (foreign events can carry browser texts).
        val s = cachedState
        val probeFgPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            ?: lastForegroundPkg ?: pkg
        if (s != null && isExcludedFromContentEngine(pkg, probeFgPkg, s.excludedApps)) {
            Log.d(TAG, "exclude: suppressed image/video probe (event=$pkg fg=$probeFgPkg)")
            return
        }

        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return
        try {
            // Scope the probe to the browser's own active window so another
            // app's transient window can never supply matching text.
            val rootPkg = runCatching { root.packageName?.toString() }.getOrNull()
            if (rootPkg != null && rootPkg != pkg) return
            val texts = collectTexts(snapshot.texts).map { it.lowercase(Locale.US) }
            val match = ImageVideoSearchGate.matches(
                pkg,
                texts,
                cachedState ?: return,
            ) ?: return
            val key = "$pkg|imgvid"
            if (lastBlockKey == key && now - lastBlockAt < COOLDOWN_MS) return
            lastBlockKey = key
            lastBlockAt = now
            Log.d(TAG, "imgvid: gate launched (pkg=$pkg kind=${match.kind})")
            launchGate(pkg, MatchResult(match.signature, ImageVideoSearchGate.GATE_TYPE))
        } finally {
            recycle(root)
        }
    }

    /**
     * [App content re-check] Keyword/URL engine probe for non-window-state
     * events from ANY app (browsers, YouTube, Gmail, Play Store, …):
     * search results pages usually paint via CONTENT_CHANGED events after
     * the window-state event already saw an unpainted page, so without this
     * the engine stays blind until a later window-state carries the rendered
     * text. Throttled on content-changed/focus floods like the PU and
     * image/video probes; deliberate clicks probe immediately (the cooldown
     * dedupes double-fires). Scoped to the app's own active window and
     * fail-open like every other probe.
     */
    private fun handleAppContentRecheck(snapshot: EventSnapshot) {
        rearmCooldownsIfGateDismissed()
        val pkg = snapshot.pkg ?: return
        val ownPackage = applicationContext.packageName ?: return
        if (pkg == ownPackage || pkg == "com.android.systemui") return

        // Master switch first: don't burn the shared throttle slot (or pay a
        // binder fetch for the root window) when blocking is disabled.
        val state = cachedState ?: return
        if (!state.blockingEnabled) return

        val now = SystemClock.elapsedRealtime()
        val isClick = snapshot.type == AccessibilityEvent.TYPE_VIEW_CLICKED
        if (shouldThrottleAppContentRecheck(lastAppContentRecheckMs, now, isClick)) return
        lastAppContentRecheckMs = now

        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return
        try {
            // Scope to the app's own active window so another app's
            // transient window can never supply matching text (same
            // discipline as the image/video probe).
            val rootPkg = runCatching { root.packageName?.toString() }.getOrNull()
            if (rootPkg != null && rootPkg != pkg) return

            // Exclude-apps: same contract as the window path.
            val fgPkg = rootPkg ?: lastForegroundPkg ?: pkg
            if (isExcludedFromContentEngine(pkg, fgPkg, state.excludedApps)) {
                Log.d(TAG, "exclude: suppressed content engine recheck (event=$pkg fg=$fgPkg)")
                return
            }

            evaluateContentEngine(snapshot, pkg, state)
        } finally {
            recycle(root)
        }
    }

    /**
     * [Launcher pre-empt] The user's flow to SafeMe's App Info on the launcher:
     * long-press our icon (popup opens) → tap "App info" (or "Remove"). The
     * long-press event's source is OUR icon (carries the app name); the menu
     * click's source is a menu item in a DIFFERENT a11y window. When both
     * happen in sequence, raise the PU gate IMMEDIATELY — Settings has not
     * even started rendering, so the App Info page is never interactive (on
     * Vivo the Settings cold-start alone is 0.2–3.6 s, and our normal
     * detection adds another ~0.4 s on top of the page's render).
     *
     * Discriminators (all must hold → near-zero false positives):
     *  1. The long-press target's subtree carries OUR app name (never any
     *     other app's icon).
     *  2. The click follows within [LAUNCHER_MENU_PREEMPT_WINDOW_MS].
     *  3. The click is a DIFFERENT window than the long-pressed icon (the
     *     popup window) OR its text is a known tamper menu item ("app info",
     *     "remove", "uninstall" + locales) — a plain workspace tap (dismissing
     *     the popup, tapping another icon) stays in the icon's window and
     *     never matches.
     *
     * Deduped by the PU gate cooldown; a dismissal re-arms the cooldown, so a
     * reveal of the (already-started) App Info page re-gates through the
     * normal window path.
     */
    private fun handleLauncherPreempt(snapshot: EventSnapshot, pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (snapshot.type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            val appName = getString(R.string.app_name)
            if (appName.isNotBlank() &&
                snapshot.clickedTexts.any { it.equals(appName, ignoreCase = true) }
            ) {
                lastLauncherLongPressApp = appName
                lastLauncherLongPressWindowId = snapshot.windowId
                lastLauncherLongPressMs = now
            }
            return
        }
        if (snapshot.type != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val appName = lastLauncherLongPressApp ?: return
        if (now - lastLauncherLongPressMs > LAUNCHER_MENU_PREEMPT_WINDOW_MS) {
            lastLauncherLongPressApp = null
            return
        }
        val popupClick = snapshot.windowId != lastLauncherLongPressWindowId
        val tamperItem = snapshot.clickedTexts.any { t ->
            val n = ProtectedSystemPages.normalize(t)
            LAUNCHER_MENU_TAMPER_ITEMS.any { n.contains(it) }
        }
        if (popupClick || tamperItem) {
            launchPuGate(pkg)
        }
        // Any other launcher click after our-icon long-press (dismissal, other
        // icon) invalidates the pending pre-empt.
        lastLauncherLongPressApp = null
    }

    /** True for known launcher packages (AOSP + OEM + generic "launcher" suffix). */
    private fun isLauncherPackage(pkg: String): Boolean {
        val p = pkg.lowercase(Locale.ROOT)
        if (p == "com.android.launcher3" || p == "com.google.android.apps.nexuslauncher" ||
            p == "com.android.launcher" || p == "com.bbk.launcher2" ||
            p == "com.miui.home" || p == "com.sec.android.app.launcher" ||
            p == "com.oppo.launcher" || p == "com.huawei.android.launcher" ||
            p == "org.cyanogenmod.trebuchet"
        ) {
            return true
        }
        return p.contains("launcher") || p.contains("trebuchet")
    }

    /**
     * [App Info latency] True when the CLICKED node's subtree is SafeMe's own
     * row in a Settings list — the Apps list, a search result, a notifications
     * row — i.e. a tap that navigates to a tamper surface. The row must carry
     * the APP NAME and must NOT carry the service label: a11y-management rows
     * ("SafeMe Accessibility") are deliberately excluded so this pre-emptive
     * path never touches a11y content (the detail page has its own gating /
     * eviction flow below). The walked subtree is bounded like every other
     * collect; the source node is recycled by the caller.
     */
    private fun clickedNodeIsOurAppRow(texts: List<String>): Boolean {
        val appName = getString(R.string.app_name).lowercase(Locale.ROOT)
        val label = getString(R.string.accessibility_service_label).lowercase(Locale.ROOT)
        if (appName.isBlank()) return false
        var hasAppName = false
        var hasLabel = false
        for (t in texts) {
            val lower = t.lowercase(Locale.ROOT)
            if (lower.contains(appName)) hasAppName = true
            if (label.isNotBlank() && lower.contains(label)) hasLabel = true
        }
        return hasAppName && !hasLabel
    }

    /**
     * True when the visible settings window is SafeMe's OWN accessibility
     * service DETAIL page. The event text carries the window title — the
     * detail page's title is our service label while the a11y LIST page's is
     * "Accessibility" — so a cheap label-prefix scan fires first; if that
     * misses (Android 16 does not expose the description to the event) the
     * active-window tree is probed via the shared [isOurA11yDetailPageInTree].
     */
    private fun isOurA11yServiceDetailPage(snapshot: EventSnapshot): Boolean {
        return try {
            val labelNorm = ProtectedSystemPages.normalize(
                getString(R.string.accessibility_service_label)
            )
            if (labelNorm.length >= 4 &&
                eventTextNormalized(snapshot.texts).startsWith(labelNorm)
            ) {
                return true
            }

            val root = rootInActiveWindow ?: return false
            try {
                isOurA11yDetailPageInTree(root)
            } finally {
                recycle(root)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "isOurA11yServiceDetailPage failed — assuming not our page", t)
            false
        }
    }

    /**
     * Tree-only detection of SafeMe's OWN accessibility-service DETAIL page,
     * shared by the event path (as a fallback when the event text isn't
     * exposed) and the PU watchdog (which has no event at all). [root] is the
     * active-window root and is NOT recycled here — the caller owns it.
     *
     * Every signal is chosen so the a11y LIST page — which renders the service
     * label exactly once (the row title) plus the summary/description — can
     * never match:
     *  1. Window title (API 33+) contains our service label. The detail
     *     page's title IS the label; the list's is "Accessibility".
     *  2. The label appears in two or more distinct walked texts. The detail
     *     page renders it in the "Use <label>" toggle row, the
     *     "<label> shortcut" row and the "About <label>" row; the list
     *     renders it once. Locale-robust: the label is SafeMe's own string,
     *     never localized by the system.
     *  3. A single walked text contains the label and "shortcut" — the
     *     "<label> shortcut" row. Catches devices where the toggle row text
     *     doesn't carry the label.
     *
     * The old description-fingerprint layers are intentionally gone: some OEM
     * a11y LISTS render the full service description, so matching the
     * description could never distinguish detail from list — it gated the
     * whole list page.
     */
    private fun isOurA11yDetailPageInTree(root: AccessibilityNodeInfo): Boolean {
        // Layer 1: window title (cheap, no tree walk).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val title = runCatching { root.window?.title?.toString() }.getOrDefault("").orEmpty()
            val label = getString(R.string.accessibility_service_label)
            if (title.isNotBlank() && label.isNotBlank() &&
                ProtectedSystemPages.normalize(title)
                    .contains(ProtectedSystemPages.normalize(label))
            ) {
                return true
            }
        }

        val label = getString(R.string.accessibility_service_label)
        val labelLower = label.lowercase(Locale.ROOT)
        if (labelLower.isBlank()) return false // fail open: can't match anything
        val labelNorm = ProtectedSystemPages.normalize(label)

        // Layers 2 + 3 share one bounded walk of the node tree.
        val texts = collectTextsFrom(root)
        if (texts.count { ProtectedSystemPages.normalize(it).contains(labelNorm) } >= 2) {
            return true
        }
        if (texts.any {
                val t = it.lowercase(Locale.ROOT)
                t.contains(labelLower) && t.contains("shortcut")
            }
        ) {
            return true
        }
        return false
    }

    /**
     * Starts the PU watchdog loop. Every [PU_WATCHDOG_INTERVAL_MS] while the
     * service is alive it re-checks the active window. The window-state-changed
     * path only fires when the foreground itself changes windows, so it misses
     * activations that arrive without a fresh state change — a guarded page
     * already active when the PU flag flips on, a detection that failed because
     * the tree wasn't ready at the event, or a gate dismissed back onto the
     * page. The tick is a cheap no-op unless PU is on AND the foreground is a
     * PU-surface package (Settings-family or package-installer), and it is
     * skipped entirely while our own gate window is up — [handleEvent] kicks
     * it early the moment the gate dismisses.
     */
    private fun startPuWatchdog() {
        if (puWatchdogJob?.isActive == true) return
        puWatchdogJob = serviceScope.launch {
            while (true) {
                try {
                    puWatchdogTick()
                } catch (t: Throwable) {
                    // Never let a malformed probe kill the watchdog loop.
                }
                delay(PU_WATCHDOG_INTERVAL_MS)
            }
        }
    }

    /**
     * One watchdog tick: if PU is on and the foreground is a PU-surface
     * package (Settings-family or the package-installer), probe the active
     * window and enforce the full guard surface on a page that is ALREADY
     * active — our own a11y detail page raises the PU gate (Block screen
     * first; dismissal bounces to HOME), and app-info / device-admin /
     * force-stop / uninstall pages raise the gate too. This catches
     * activations the event path misses: a page already open when the
     * PU flag flips on, a detection that failed because the tree wasn't ready
     * at the window-state-changed event, or a gate dismissed back onto the
     * page. Kicks and gate launches are throttled (dedupe event floods) and
     * never suppress a genuine re-activation.
     *
     * The tick never probes while our own gate window is foreground (the
     * active tree is the gate's, not the protected page's) — [handleEvent]
     * kicks it immediately when the gate dismisses onto a new window.
     */
    private fun puWatchdogTick() {
        rearmCooldownsIfGateDismissed()
        if (!cachedPuEnabled) return
        // [Re-gate latency] Resolve the foreground identity from the ACTIVE
        // window on every tick — never trust the event-derived cache alone.
        // OEMs (Vivo/FuntouchOS) can drop or delay the window-state-changed
        // event on task-resume, leaving lastForegroundPkg stuck on the
        // launcher so the tick would skip forever even though a protected
        // page is active (a "no block" the user actually sees). One cheap
        // Binder call per second; falls back to the cache when the fetch
        // returns null mid-transition.
        var pkg: String? = null
        var cls: String? = null
        val identityRoot = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        }
        if (identityRoot != null) {
            pkg = try {
                identityRoot.packageName?.toString()
            } catch (t: Throwable) {
                null
            }
            cls = try {
                identityRoot.className?.toString()
            } catch (t: Throwable) {
                null
            }
            recycle(identityRoot)
            if (pkg != null) lastForegroundPkg = pkg
            if (cls != null) lastForegroundCls = cls
        } else {
            pkg = lastForegroundPkg
            cls = lastForegroundCls
        }
        if (pkg == null) return
        val ownPackage = applicationContext.packageName ?: return
        // Our own full-screen window (the block gate) is foreground — the
        // active tree is the gate's, not the protected page's, so probing is
        // pointless. Skip; [handleEvent] kicks a tick the moment the gate
        // dismisses onto a new window.
        if (pkg == ownPackage) return
        // [Overlay gate] The overlay is NOT_FOCUSABLE, so the active window is
        // the page BELOW (Settings), not our package — without this guard the
        // tick would re-probe and re-attempt gates every cadence while the
        // cover is up. The overlay's dismissal re-arms via [onGateDismissed].
        if (BlockOverlayController.isShowing()) return
        if (!ProtectedSystemPages.isPuSurface(pkg)) return

        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return
        try {
            // 1. Our own a11y detail page → raise the PU gate (Block screen)
            //    first. Dismissing it bounces to HOME (see [handleEvent]), so
            //    the cover is one-shot — never left looping over an
            //    a11y-management screen (the a11y kill vector).
            //    [Re-gate race fix] Within the post-dismissal window the
            //    eviction must WIN: the 1 s tick can land before the
            //    eviction's HOME takes effect (Vivo is slow to finish the
            //    gate activity), so without this guard the watchdog re-raises
            //    the gate over the just-dismissed page and the cover loops
            //    forever (observed live on the device, nondeterministic).
            if (isOurA11yDetailPageInTree(root)) {
                gateOrEvictOurA11yDetailPage(pkg)
                return
            }
            // 2. Private DNS settings page → gate. A PDNS page has no SafeMe
            //    app name, so it can never match the app-info path below; checked
            //    first and returned to skip that path's (throttled) app-name
            //    framework probe on a page it can't possibly match.
            if (isPrivateDnsTargetPageInTree(root, pkg, cls.orEmpty())) {
                launchPuGate(pkg)
                return
            }
            // VPN settings page → gate. No SafeMe app name is reliably on it, so
            // it can never match the app-info path below; checked before it to
            // skip that path's (throttled) app-name framework probe.
            if (isVpnTargetPageInTree(root, pkg, cls.orEmpty())) {
                launchPuGate(pkg)
                return
            }
            // 3. App Info / Device Admin / force-stop / uninstall pages → gate.
            //    The framework probe uses the watchdog's 5 s bucket — the tick
            //    runs every second, so a fresh probe on every tick would flood.
            if (isOurUninstallTargetPageInTree(root, pkg, cls.orEmpty(), isWatchdogCall = true)) {
                launchPuGate(pkg)
            }
        } finally {
            recycle(root)
        }
    }

    /**
     * Bounded node-tree substring search for [needle] (the app name). Every
     * node obtained from the active window is recycled afterwards (required on
     * API 26–32, a no-op on 33+). [root] may be a root the caller already
     * holds (the PU watchdog tick) — it is then NOT recycled here and the
     * caller keeps ownership.
     *
     * Throttled: this is the only framework-side search and must not run on
     * every 2 s watchdog tick; the walked-texts checks in the shared core run
     * unthrottled and catch genuine targets, so the throttle never delays a
     * real activation.
     */
    private fun nodeTreeContainsText(
        needle: String,
        root: AccessibilityNodeInfo? = null,
        isWatchdogCall: Boolean = false,
    ): Boolean {
        if (needle.isBlank()) return false
        val now = SystemClock.elapsedRealtime()
        // [App Info latency] Throttle buckets by CALLER, not by root-nullness:
        // the watchdog probes at most once per 5 s (it ticks every second),
        // while every event-driven path (window-state, content events, clicks,
        // post-page re-probes) shares the short event window so a fresh App
        // Info detection is never starved by a recent probe.
        val lastProbe = if (isWatchdogCall) lastAppNameProbeMs else lastEventAppNameProbeMs
        val throttle = if (isWatchdogCall) PU_WATCHDOG_PROBE_THROTTLE_MS else PU_EVENT_PROBE_THROTTLE_MS
        if (now - lastProbe < throttle) return false
        if (isWatchdogCall) lastAppNameProbeMs = now else lastEventAppNameProbeMs = now
        val owned = root == null
        val actualRoot = root ?: runCatching { rootInActiveWindow }.getOrNull() ?: return false
        val nodes = try {
            actualRoot.findAccessibilityNodeInfosByText(needle)
        } catch (t: Throwable) {
            if (owned) recycle(actualRoot)
            return false
        }
        return try {
            nodes != null && nodes.isNotEmpty()
        } finally {
            if (nodes != null) nodes.forEach { recycle(it) }
            if (owned) recycle(actualRoot)
        }
    }

    /**
     * Nodes obtained from the active-window tree hold Binder references on
     * API 26–32 and must be recycled; leaking them across a long service
     * session can exhaust the framework's node pool. No-op on API 33+.
     */
    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                node.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Evict our own a11y detail page: HOME + delayed toast. Used as the
     * post-gate bounce-back — the PU gate is shown first (see
     * [handlePreventUninstall] / [puWatchdogTick]); dismissing it lands here
     * so the user never reaches the page and no cover lingers over an
     * a11y-management screen. Delayed toast lands over the launcher, never
     * over the a11y screen (a toast is still one of our windows — keep the
     * zero-obscure discipline). Throttled.
     */
    private fun evictFromOurA11yServicePage() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastA11yPageKickMs < A11Y_PAGE_KICK_THROTTLE_MS) return
        lastA11yPageKickMs = now
        try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (t: Throwable) {
            Log.w(TAG, "PU: HOME global action failed", t)
        }
        if (now - lastPuKickToastMs < PU_KICK_TOAST_THROTTLE_MS) return
        lastPuKickToastMs = now
        // [M3 fix] Store the Runnable so it can be cancelled on service destroy.
        try {
            pendingToastRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
            val runnable = Runnable {
                try {
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.pu_evicted_toast),
                        Toast.LENGTH_LONG
                    ).show()
                } catch (t: Throwable) {
                    Log.w(TAG, "PU: eviction toast failed", t)
                }
            }
            pendingToastRunnable = runnable
            Handler(Looper.getMainLooper()).postDelayed(runnable, PU_KICK_TOAST_DELAY_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "PU: failed to schedule eviction toast", t)
        }
    }

    /**
     * Port of the reference app-info / device-admin / force-stop detection,
     * scoped to SafeMe. Every branch requires our app name on the page (event
     * text OR node tree); a page that is not about SafeMe is never blocked.
     */
    private fun isOurUninstallTargetPage(
        snapshot: EventSnapshot,
        pkg: String,
        cls: String,
    ): Boolean {
        return try {
            // [App Info latency] Also fold in the window TITLE(s): on API 33+
            // the a11y window title of an app-info page is often the app label
            // itself ("SafeMe"), available BEFORE the page tree populates — so
            // the app-name check can pass at the window-state event instead of
            // waiting for the tree to render (the Vivo tree-readiness variance
            // that made App Info latency swing 0.6 s → 2 s+). Event text is
            // included in both collectors; duplicates are harmless.
            val lowerText = (collectTexts(snapshot.texts) + collectTitles(snapshot.texts))
                .joinToString(" ").lowercase(Locale.ROOT)
            isOurUninstallTargetPage(pkg, cls, lowerText) {
                isOurA11yServiceDetailPage(snapshot)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "isOurUninstallTargetPage failed — fail open", t)
            false
        }
    }

    /**
     * Event-free variant used by the PU watchdog: same detection as
     * [isOurUninstallTargetPage], but the page texts come from [root]'s node
     * tree, so a surface that is ALREADY active (no fresh window-state-changed
     * event) is still gated. The root is NOT recycled here — the caller owns it.
     */
    private fun isOurUninstallTargetPageInTree(
        root: AccessibilityNodeInfo,
        pkg: String,
        cls: String,
        isWatchdogCall: Boolean = false,
    ): Boolean {
        return try {
            // [App Info latency] Fold the a11y window TITLE into the text: an
            // app-info window's title is the app label ("SafeMe") and is
            // available BEFORE the page tree populates on slow OEM renders, so
            // the reprobe/watchdog passes succeed as soon as the title exists
            // instead of waiting for the tree.
            val lowerText = (collectTextsFrom(root) + windowTitleOf(root))
                .joinToString(" ").lowercase(Locale.ROOT)
            isOurUninstallTargetPage(
                pkg, cls, lowerText,
                probeRoot = root,
                isWatchdogCall = isWatchdogCall,
            ) {
                isOurA11yDetailPageInTree(root)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "isOurUninstallTargetPageInTree failed — fail open", t)
            false
        }
    }

    /**
     * Private DNS settings page — the event path (window-state-changed). Gated
     * on window TITLE only (via [collectTitles]), never page body text, and it
     * deliberately never runs the app-name node-tree probe (the PDNS page has
     * no SafeMe app name — probing for it is the closest thing to a false
     * positive and pure waste). See [PrivateDnsBlockers] for the FP discipline.
     */
    private fun isPrivateDnsTargetPage(
        snapshot: EventSnapshot,
        pkg: String,
        cls: String,
    ): Boolean {
        return try {
            val titles = collectTitles(snapshot.texts).joinToString(" ")
            PrivateDnsBlockers.isPrivateDnsTargetPage(pkg, cls.lowercase(Locale.ROOT), titles)
        } catch (t: Throwable) {
            Log.w(TAG, "isPrivateDnsTargetPage failed — fail open", t)
            false
        }
    }

    /**
     * Event-free variant used by the watchdog / reprobe / post-dismissal paths:
     * same class + window-title detection, with the title read from [root]'s
     * own a11y window title ([windowTitleOf]) so a surface that is ALREADY
     * active (no fresh window-state event) is still gated. The root is NOT
     * recycled here — the caller owns it.
     */
    private fun isPrivateDnsTargetPageInTree(
        root: AccessibilityNodeInfo,
        pkg: String,
        cls: String,
    ): Boolean {
        return try {
            val titles = windowTitleOf(root).joinToString(" ")
            PrivateDnsBlockers.isPrivateDnsTargetPage(pkg, cls.lowercase(Locale.ROOT), titles)
        } catch (t: Throwable) {
            Log.w(TAG, "isPrivateDnsTargetPageInTree failed — fail open", t)
            false
        }
    }

    /**
     * Event-path VPN page detector: pure [VpnBlockers] rule matched on the
     * event's WINDOW TITLE only (via [collectTitles]), never page body text; it
     * deliberately never runs the app-name node-tree probe (a VPN page has no
     * reliable SafeMe name — probing for it is false-positive-adjacent waste).
     * Mirrors [isPrivateDnsTargetPage]. See [VpnBlockers] for the FP discipline.
     */
    private fun isVpnTargetPage(
        snapshot: EventSnapshot,
        pkg: String,
        cls: String,
    ): Boolean {
        return try {
            val titles = collectTitles(snapshot.texts).joinToString(" ")
            VpnBlockers.isVpnTargetPage(pkg, cls.lowercase(Locale.ROOT), titles)
        } catch (t: Throwable) {
            Log.w(TAG, "isVpnTargetPage failed — fail open", t)
            false
        }
    }

    /**
     * Event-free variant used by the watchdog / reprobe / post-dismissal paths:
     * same class + window-title detection, with the title read from [root]'s
     * own a11y window title ([windowTitleOf]) so a surface that is ALREADY
     * active (no fresh window-state event) is still gated. The root is NOT
     * recycled here — the caller owns it. Mirrors [isPrivateDnsTargetPageInTree].
     */
    private fun isVpnTargetPageInTree(
        root: AccessibilityNodeInfo,
        pkg: String,
        cls: String,
    ): Boolean {
        return try {
            val titles = windowTitleOf(root).joinToString(" ")
            VpnBlockers.isVpnTargetPage(pkg, cls.lowercase(Locale.ROOT), titles)
        } catch (t: Throwable) {
            Log.w(TAG, "isVpnTargetPageInTree failed — fail open", t)
            false
        }
    }

    /** The a11y window title of [root]'s window (API 33+), or empty. */
    private fun windowTitleOf(root: AccessibilityNodeInfo): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyList()
        return try {
            val title = root.window?.title?.toString()
            if (title.isNullOrBlank()) emptyList() else listOf(title)
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * Shared core of the app-info / device-admin / force-stop / uninstall
     * detection. [lowerText] is the collected page text (event + tree for the
     * event path, tree only for the watchdog) and [isOurA11yDetailPage] is the
     * caller-appropriate detail-page check that keeps our own a11y page out of
     * the gate. [probeRoot] is the active-window root when the caller already
     * holds one (the watchdog tick) — the framework app-name probe reuses it
     * instead of fetching a second root; the caller keeps ownership.
     *
     * The decision logic lives in [UninstallBlockers.isOurUninstallTargetPage]
     * (pure + unit-tested); this wrapper gathers the framework-dependent inputs
     * (resources, node-tree probe, Device Admin state) and owns the a11y-label
     * eviction side effect.
     */
    private fun isOurUninstallTargetPage(
        pkg: String,
        cls: String,
        lowerText: String,
        probeRoot: AccessibilityNodeInfo? = null,
        isWatchdogCall: Boolean = false,
        isOurA11yDetailPage: () -> Boolean,
    ): Boolean {
        return try {
            val lowerClass = cls.lowercase(Locale.ROOT)
            val appName = getString(R.string.app_name)
            if (appName.isBlank()) return false
            val appNameLower = appName.lowercase(Locale.ROOT)

            // Never gate a11y-management content for OUR service. Any page
            // whose texts contain our SERVICE LABEL (distinct from the app
            // name) is the service's list row or detail page — on Android 16
            // the detail page is hosted in a generic SubSettings container
            // whose class carries no a11y marker and whose description text is
            // NOT exposed to the tree, so the description probes fail there.
            // The tree-based detail detector is the only reliable signal — the
            // DETAIL page is gated here as a fallback for devices the watchdog
            // missed; the LIST page (label row + summary/description) never
            // matches it, so it is never gated. Checked before the app-name
            // probe so a11y pages never trigger it.
            val serviceLabelLower = getString(R.string.accessibility_service_label)
                .lowercase(Locale.ROOT)
            if (serviceLabelLower.isNotBlank() && lowerText.contains(serviceLabelLower)) {
                if (isOurA11yDetailPage()) {
                    // [Re-gate race fix] Same one-shot discipline: within the
                    // post-dismissal window this label-guard EVICTS the detail
                    // page instead of re-gating it. Reached when a reveal
                    // event's class misses the a11y markers on OEM builds
                    // (a11yContext false) so the App Info branch runs this
                    // guard — the last unguarded detail-page gate site.
                    gateOrEvictOurA11yDetailPage(pkg)
                }
                return false
            }

            val appNameInText = lowerText.contains(appNameLower)
            val appNameInNodeTree = if (!appNameInText) {
                // Throttled framework probe (see [nodeTreeContainsText]) — the
                // walked-texts check above already ran, so this only rescues
                // trees the walk missed; it never runs on a11y pages.
                runCatching {
                    nodeTreeContainsText(appName, probeRoot, isWatchdogCall)
                }.getOrDefault(false)
            } else {
                false
            }
            val appIsOnPage = appNameInText || appNameInNodeTree

            val matched = UninstallBlockers.isOurUninstallTargetPage(
                pkg = pkg,
                lowerClass = lowerClass,
                lowerText = lowerText,
                appIsOnPage = appIsOnPage,
                isOurA11yDetailPage = isOurA11yDetailPage,
                adminActive = DeviceAdminUtils.isActive(applicationContext),
            )
            if (ProtectedSystemPages.isUninstallerPackage(pkg)) {
                Log.d(
                    TAG,
                    "PU: packageinstaller pkg=$pkg cls=$lowerClass appIsOnPage=$appIsOnPage matched=$matched"
                )
            }
            matched
        } catch (t: Throwable) {
            Log.w(TAG, "isOurUninstallTargetPage failed — fail open", t)
            false
        }
    }

    /**
     * Raises the PU gate over [pkg]'s current window. Shares the same cooldown
     * key as the event path, so a block raised by either path is deduped by
     * the other and the watchdog never double-fires right after a
     * window-state-changed block.
     */
    private fun launchPuGate(pkg: String) {
        val appName = getString(R.string.app_name)
        val key = "$pkg|pu"
        val now = SystemClock.elapsedRealtime()
        if (lastBlockKey == key && now - lastPuBlockAt < COOLDOWN_MS) return
        lastBlockKey = key
        lastPuBlockAt = now
        // One-line latency marker: correlates with the event logs in logcat to
        // measure detection→gate time (the user-visible App Info latency).
        Log.d(TAG, "PU: gate launched (pkg=$pkg)")
        launchGate(pkg, MatchResult(appName, "pu"))
    }

    /**
     * One-shot cover discipline for OUR OWN a11y detail page, shared by every
     * detail-page gate site (event path, watchdog, shared-core label guard):
     * a FRESH activation raises the PU gate first; within the post-dismissal
     * window [POST_DISMISSAL_EVICT_WINDOW_MS] it is EVICTED (bounce to HOME),
     * never re-gated — the 1 s watchdog tick, the reveal window-state event
     * and content events can all race the eviction, and a re-gate over an
     * a11y-management screen is the a11y kill vector (observed live on the
     * device: dismiss → instant new-gate loop until this guard landed).
     */
    private fun gateOrEvictOurA11yDetailPage(pkg: String) {
        if (Companion.isWithinPostDismissalWindow()) {
            evictFromOurA11yServicePage()
        } else {
            launchPuGate(pkg)
        }
    }

    /** Normalized (lowercased, spaces stripped) concatenation of the event text. */
    private fun eventTextNormalized(texts: List<String>): String {
        val sb = StringBuilder()
        try {
            texts.forEach { sb.append(it) }
        } catch (_: Throwable) {
        }
        return ProtectedSystemPages.normalize(sb.toString())
    }

    /**
     * Collects candidate window-title strings for a window-state-changed event.
     * The framework populates [AccessibilityEvent.getText] with the window's
     * title (verified on-device: "Settings", "Apps", "Notifications" for the
     * corresponding Settings pages). On API 33+ the window's own title attribute
     * is used as a fallback when the event carries no text.
     */
    private fun collectTitles(texts: List<String>): List<String> {
        val out = ArrayList<String>()
        try {
            texts.forEach { t ->
                t.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        } catch (_: Throwable) {
        }
        if (out.isEmpty() && Build.VERSION.SDK_INT >= 33) {
            try {
                val root = rootInActiveWindow ?: return out.distinct()
                try {
                    root.window?.title?.toString()
                        ?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                } finally {
                    recycle(root)
                }
            } catch (_: Throwable) {
            }
        }
        return out.distinct()
    }

    private fun collectTexts(texts: List<String>): List<String> {
        val out = ArrayList<String>()
        try {
            texts.forEach { out.add(it) }
        } catch (_: Throwable) {
        }
        try {
            val root = rootInActiveWindow ?: return out.distinct()
            try {
                out.addAll(collectTextsFrom(root))
            } finally {
                recycle(root)
            }
        } catch (_: Throwable) {
        }
        return out.distinct()
    }

    /**
     * URL candidates for [BrowserUrlGate]: event strings plus a bounded
     * EditText tree-walk fallback (NopoX-parity address-bar extraction).
     * Fail-open — returns null so the caller falls through to the keyword
     * engine.
     */
    private fun collectUrlCandidates(snapshot: EventSnapshot): List<String>? = try {
        val raw = ArrayList<String>()
        try {
            snapshot.texts.forEach { raw.add(it) }
        } catch (_: Throwable) {
        }
        try {
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    val entries = ArrayList<Pair<String?, String>>()
                    collectEditTextEntries(root, entries, 0)
                    raw.addAll(BrowserUrlGate.urlCandidatesFromNodeEntries(entries))
                } finally {
                    recycle(root)
                }
            }
        } catch (_: Throwable) {
        }
        BrowserUrlGate.extractUrlCandidates(raw).distinct()
    } catch (t: Throwable) {
        Log.w(TAG, "collectUrlCandidates failed — fail open", t)
        null
    }

    /**
     * Bounded walk of [root]'s node tree collecting (className, text) pairs of
     * EditText nodes only (capped by MAX_DEPTH/MAX_STRINGS). The root itself
     * is NOT recycled — the caller owns it.
     */
    private fun collectEditTextEntries(
        node: AccessibilityNodeInfo,
        out: MutableList<Pair<String?, String>>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_STRINGS) return
        try {
            val cls = node.className?.toString()
            if (cls != null && cls.endsWith("EditText")) {
                val t = node.text?.toString()
                if (!t.isNullOrBlank()) out.add(cls to t)
            }
        } catch (_: Throwable) {
        }
        val childCount = try {
            node.childCount
        } catch (_: Throwable) {
            0
        }
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            } ?: continue
            try {
                collectEditTextEntries(child, out, depth + 1)
            } finally {
                recycle(child)
            }
        }
    }

    /**
     * Bounded walk of [root]'s node tree collecting visible text and
     * contentDescription (capped by MAX_DEPTH/MAX_STRINGS). The root itself is
     * NOT recycled — the caller owns it.
     */
    private fun collectTextsFrom(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        try {
            walk(root, out, 0)
        } catch (_: Throwable) {
        }
        return out.distinct()
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > MAX_DEPTH || out.size >= MAX_STRINGS) return
        try {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        } catch (_: Throwable) {
        }
        val childCount = try {
            node.childCount
        } catch (_: Throwable) {
            0
        }
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            } ?: continue
            try {
                walk(child, out, depth + 1)
            } finally {
                recycle(child)
            }
        }
    }

    private fun findMatch(
        texts: List<String>,
        state: BlockingPrefsState,
    ): MatchResult? {
        val lowerTexts = texts.map { it.lowercase() }

        // Whitelist keywords override the blocklist entirely.
        if (state.whitelistKeywords.any { wl ->
                val needle = wl.lowercase()
                needle.isNotBlank() && lowerTexts.any { it.contains(needle) }
            }
        ) {
            return null
        }

        // Keyword match: case-insensitive substring against collected text.
        state.blocklistKeywords.forEach { kw ->
            val needle = kw.value.lowercase()
            if (needle.isNotBlank() && lowerTexts.any { it.contains(needle) }) {
                return MatchResult(kw.value, "keyword")
            }
        }
        BundledKeywords.keywords.forEach { kw ->
            val needle = kw.value.lowercase()
            if (needle.isNotBlank() && lowerTexts.any { it.contains(needle) }) {
                return MatchResult(kw.value, "keyword")
            }
        }

        // Website match: domain-suffix match at label boundaries.
        val hosts = lowerTexts.mapNotNull { text ->
            normalizeDomain(text).takeIf { it.isNotEmpty() }
        }
        if (hosts.isEmpty()) return null

        if (state.trustedWebsites.any { trusted ->
                val t = trusted.lowercase()
                t.isNotEmpty() && hosts.any { it == t || it.endsWith(".$t") }
            }
        ) {
            return null
        }

        state.blockedWebsites.forEach { site ->
            val d = site.domain.lowercase()
            if (d.isNotEmpty() && hosts.any { it == d || it.endsWith(".$d") }) {
                return MatchResult(site.domain, "website")
            }
        }
        BundledKeywords.websites.forEach { site ->
            val d = site.domain.lowercase()
            if (d.isNotEmpty() && hosts.any { it == d || it.endsWith(".$d") }) {
                return MatchResult(site.domain, "website")
            }
        }

        return null
    }

    private fun findTitleMatch(
        titles: List<String>,
        rules: List<TitleBlockRule>,
        state: BlockingPrefsState,
    ): MatchResult? {
        val enabledRules = rules.filter { it.enabled }
        if (enabledRules.isEmpty() || titles.isEmpty()) return null
        val lowerTitles = titles.map { it.lowercase() }

        // Whitelist keywords suppress title rules too, mirroring findMatch so the
        // whitelist escape hatch is honored consistently across all match paths.
        if (state.whitelistKeywords.any { wl ->
                val needle = wl.lowercase()
                needle.isNotBlank() && lowerTitles.any { it.contains(needle) }
            }
        ) {
            return null
        }

        enabledRules.forEach { rule ->
            val needle = rule.value.lowercase()
            if (needle.isBlank()) return@forEach
            val matched = when (rule.mode) {
                TitleMatchMode.CONTAINS -> lowerTitles.any { it.contains(needle) }
                TitleMatchMode.EXACT -> lowerTitles.any { it == needle }
                TitleMatchMode.STARTS_WITH -> lowerTitles.any { it.startsWith(needle) }
            }
            if (matched) {
                return MatchResult(rule.value, "title")
            }
        }
        return null
    }

    private fun findTitleMatchIfSettings(
        pkg: String,
        texts: List<String>,
        state: BlockingPrefsState,
    ): MatchResult? {
        // [H3 fix] Delegate to the shared ProtectedSystemPages implementation
        // so title-blocking and PU guards use the same OEM-aware package list.
        if (!ProtectedSystemPages.isSettingsPackage(pkg)) {
            // Warn once per service session when a Settings-looking package is seen
            // but isn't allowlisted. On OEM forks that ship Settings under a
            // different package this is the only diagnostic that title blocking
            // can't fire; plain app windows are ignored so the warning stays
            // meaningful instead of firing on every window change.
            if (!titleScopeWarned && looksLikeSettingsPackage(pkg) && state.titleBlockRules.any { it.enabled }) {
                titleScopeWarned = true
                Log.w(
                    TAG,
                    "Title rules configured, but Settings package \"$pkg\" is not in " +
                        "ProtectedSystemPages allowlist; title blocking won't fire on this device's Settings"
                )
            }
            return null
        }
        return findTitleMatch(collectTitles(texts), state.titleBlockRules, state)
    }

    private fun launchGate(pkg: String, match: MatchResult) {
        // Matched keyword/URL is sensitive; log only on debuggable builds.
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Log.d(TAG, "gate: pkg=$pkg value=${match.value} type=${match.type}")
        }
        // [Overlay gate] Universal block-gate host: NopoX-style overlay window
        // (fast cover, no activity launch) with an automatic fallback to
        // [BlockGateActivity] when SYSTEM_ALERT_WINDOW is not granted — so no
        // gate type can ever break on a device that denies overlays.
        BlockOverlayController.show(this, pkg, match.value, match.type)
    }

    /**
     * True when [pkg] is launch-blocked by an active schedule. With a
     * "block everything" schedule active, critical system surfaces stay
     * reachable so the user is never locked out of the launcher/Settings.
     */
    private fun isFocusBlocked(pkg: String): Boolean {
        if (!FocusEngine.isLaunchBlocked(pkg)) return false
        if (FocusEngine.isLaunchBlockAllActive() && pkg in FOCUS_SYSTEM_EXEMPT) return false
        return true
    }

    private fun launchFocusGate(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (lastFocusBlockKey == pkg && now - lastFocusBlockAt < SCHEDULE_COOLDOWN_MS) return
        lastFocusBlockKey = pkg
        lastFocusBlockAt = now
        // [Overlay gate] Same universal overlay host as every other gate type.
        BlockOverlayController.show(this, pkg, "", "schedule")
    }

    private fun launchSocialWholeGate(pkg: String) {
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        Log.d(TAG, "social whole gate launched (pkg=$pkg label=$label)")
        BlockOverlayController.show(this, pkg, label, "socialWhole")
    }

    private fun launchSocialTabGate(pkg: String, vertical: SocialBlockingGate.SocialVertical) {
        val label = when (vertical) {
            SocialBlockingGate.SocialVertical.SHORTS -> "YouTube Shorts"
            SocialBlockingGate.SocialVertical.REELS -> "Facebook Reels"
            SocialBlockingGate.SocialVertical.SPOTLIGHT -> "Snapchat Spotlight"
        }
        Log.d(TAG, "social tab gate launched (pkg=$pkg vertical=$vertical)")
        BlockOverlayController.show(this, pkg, label, "socialTab")
    }

    /**
     * Social tab re-check on content-changed / click / focus inside FEATURE_PACKAGES.
     * Throttled 250ms (non-click) + 4s cooldown per pkg|vertical. Only runs when
     * social.enabled && vertical enabled. Finds tab label node (Shorts/Reels/Spotlight)
     * and overlay the tab node; keeps Feed/Messages/Profile scrollable.
     */
    private fun handleSocialTabContentEvent(snapshot: EventSnapshot) {
        rearmCooldownsIfGateDismissed()
        val pkg = snapshot.pkg ?: return
        val ownPackage = applicationContext.packageName ?: return
        if (pkg == ownPackage || pkg == "com.android.systemui") return
        val social = cachedSocialState ?: return
        if (!social.enabled) return
        val vertical = SocialBlockingGate.verticalFor(pkg) ?: return
        if (!SocialBlockingGate.isVerticalEnabled(vertical, social.youtube, social.facebook, social.snapchat)) return

        val now = SystemClock.elapsedRealtime()
        val isClick = snapshot.type == AccessibilityEvent.TYPE_VIEW_CLICKED
        if (shouldThrottleSocialTabRecheck(lastSocialTabProbeMs, now, isClick)) return
        lastSocialTabProbeMs = now

        val key = SocialBlockingGate.throttleKey(pkg, vertical)
        val last = socialTabCooldown[key] ?: 0L
        if (now - last < SocialBlockingGate.GATE_COOLDOWN_MS) return

        val root = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return
        try {
            val rootPkg = runCatching { root.packageName?.toString() }.getOrNull()
            if (rootPkg != null && rootPkg != pkg) return
            val tabNode = try { SocialBlockingGate.findTabNode(root, vertical) } catch (_: Throwable) { null } ?: return
            try {
                socialTabCooldown[key] = now
                launchSocialTabGate(pkg, vertical)
            } finally {
                try { tabNode.recycle() } catch (_: Throwable) {}
            }
        } finally {
            recycle(root)
        }
    }

    /**
     * Re-checks the current foreground window against the schedule sets.
     * Called by [FocusEngine] when the active launch-block set changes, so
     * a block that starts while the app is already open still takes effect.
     * Throttled.
     */
    private fun recheckFocusBlock(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (shouldThrottleFocusRecheck(lastFocusRecheckMs, now, force)) return
        lastFocusRecheckMs = now
        try {
            val root = rootInActiveWindow ?: return
            try {
                val pkg = root.packageName?.toString() ?: return
                val ownPackage = applicationContext.packageName ?: return
                if (pkg == ownPackage) return
                if (isFocusBlocked(pkg)) launchFocusGate(pkg)
            } finally {
                recycle(root)
            }
        } catch (_: Throwable) {
        }
    }

    override fun onInterrupt() {
        // No-op.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // [M3 fix] Cancel any pending eviction toast before destroying.
        pendingToastRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        pendingToastRunnable = null
        BlockOverlayController.onAccessibilityServiceDisconnected()
        if (instance === this) instance = null
        // Re-arm on the shared heal executor (survives serviceScope.cancel())
        // — an unbind may be the precursor to the service being disabled.
        A11yProtectionUtils.selfHealAllAsync(this)
        serviceScope.cancel()
        eventScope.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // [M3 fix] Cancel any pending eviction toast before destroying.
        pendingToastRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        pendingToastRunnable = null
        BlockOverlayController.onAccessibilityServiceDisconnected()
        if (instance === this) instance = null
        A11yProtectionUtils.selfHealAllAsync(this)
        serviceScope.cancel()
        eventScope.cancel()
        super.onDestroy()
    }

    private data class MatchResult(
        val value: String,
        val type: String,
    )

    companion object {
        const val TAG = "SafeMeA11y"
        const val COOLDOWN_MS = 4_000L
        const val MAX_DEPTH = 12
        const val MAX_STRINGS = 200
        // [PU watchdog] Short throttle window: the a11y-detail eviction must
        // fire on EVERY activation, so it only dedupes event floods — it
        // never suppresses a genuine reopen of the page.
        const val A11Y_PAGE_KICK_THROTTLE_MS = 2_000L
        // [Latency fix] 500 ms cadence (was 1 s, originally 2 s): the tick is
        // cheap — a bounded walk on PU-surface packages only, skipped while
        // our gate is up, running on the background scope after the
        // main-thread fix — and a shorter window closes the worst case when
        // the event path misses entirely (OEMs like Vivo DROP the
        // window-state event on task-resume, so no reprobe chain ever starts
        // and the watchdog is the ONLY detector — 1 s cadence made those
        // opens take up to 2 s).
        // [App Info latency] 250 ms cadence: the watchdog is the backstop when
        // the window-state event is dropped (OEM quirk) — halving the cadence
        // halves the worst-case exposure. Each tick is cheap (identity Binder
        // call; the framework tree probe stays 5 s-throttled).
        const val PU_WATCHDOG_INTERVAL_MS = 250L
        // [Latency fix] The watchdog's app-name framework probe stays above
        // the tick cadence (the walked-texts checks run unthrottled and catch
        // genuine targets). The EVENT path probes with its own short window
        // (PU_EVENT_PROBE_THROTTLE_MS) so a fresh window-state detection is
        // never starved by a recent watchdog probe.
        const val PU_WATCHDOG_PROBE_THROTTLE_MS = 5_000L
        // [App Info latency] Event-path app-name probe window: the window-state
        // path fires at most one probe per page event, and the content path one
        // per click, so a short window never starves a fresh detection — the
        // previous 1 s window let a probe from a recent event suppress the App
        // Info detection entirely (page sat open until the next watchdog tick).
        const val PU_EVENT_PROBE_THROTTLE_MS = 250L
        // [App Info latency] After a window-state event on a Settings-family
        // page whose tree was not ready yet (Vivo renders slowly and the
        // populate time varies run to run — measured up to 1.5 s+), re-probe
        // the page on a growing schedule so a tree that fills in after the
        // event still gates — collapsing the "missed at event → wait for the
        // 1 s watchdog → still unready → next tick" cascade that made App
        // Info latency swing from ~0.6 s to 2 s+. Each probe is one bounded
        // tree walk (the walked-texts check is unthrottled), so the chain is
        // cheap; the 1 s watchdog covers anything past the last probe.
        // [App Info latency] First probe at 50 ms: the window-state detection
        // misses when the page tree is still populating (Vivo renders slowly),
        // and the tree is usually ready within ~50-150 ms of the event — so
        // the earliest probe catches it instead of the next watchdog tick.
        private val PU_SURFACE_REPROBE_DELAYS = longArrayOf(50L, 150L, 350L, 700L, 1_200L)
        const val PU_SURFACE_REPROBE_MIN_INTERVAL_MS = 1_000L
        const val PU_KICK_TOAST_THROTTLE_MS = 60_000L
        const val PU_KICK_TOAST_DELAY_MS = 700L
        // [PU content events] CONTENT_CHANGED floods Settings; probe the tree
        // at most once per window on those events. Deliberate clicks probe
        // immediately — the PU gate cooldown dedupes double-fires.
        const val PU_CONTENT_PROBE_THROTTLE_MS = 250L
        // [Image/video search gate] Same flood discipline as the PU content
        // probe: CONTENT_CHANGED/focus events probe at most once per window;
        // deliberate clicks probe immediately.
        const val IMGVID_PROBE_THROTTLE_MS = 250L

        /**
         * [Launcher pre-empt] A launcher "App info"/"Remove" click qualifies
         * only when it follows a long-press of OUR icon within this window
         * (long-press → popup → deliberate tap takes ~1–3 s).
         */
        const val LAUNCHER_MENU_PREEMPT_WINDOW_MS = 5000L

        /**
         * [Launcher pre-empt] Tamper menu-item labels (normalized: lowercase,
         * spaces stripped). Fallback discriminator when the popup shares the
         * icon's a11y window (some launchers render menus in-window) — the
         * primary signal is the window-id difference.
         */
        val LAUNCHER_MENU_TAMPER_ITEMS = listOf(
            "appinfo",
            "remove",
            "uninstall",
            "delete",
            "应用信息",
            "移除",
            "卸载",
            "アプリ情報",
            "削除",
            "앱 정보",
            "제거",
        )
        // [Re-gate latency] Post-dismissal re-probe chain. Probe the window
        // revealed by a gate dismissal immediately and at short intervals so a
        // protected page underneath is re-covered as soon as its tree is ready
        // — Vivo/FuntouchOS can delay or drop the reveal window-state event,
        // and waiting for the next watchdog tick would expose the page for up
        // to a second. One chain per dismissal window.
        const val POST_DISMISSAL_CHAIN_MIN_INTERVAL_MS = 500L
        private val POST_DISMISSAL_PROBE_DELAYS = longArrayOf(0L, 150L, 400L, 800L)
        // [Re-gate race fix] After a gate dismissal, our own a11y detail page
        // is EVICTED (bounce to HOME), never re-gated — the 1 s watchdog tick
        // can beat the eviction's HOME to the revealed page, and without this
        // window the cover loops forever over the a11y screen (the kill
        // vector). Longer than the gate-finish animation + one watchdog tick,
        // short enough that a genuine re-activation gates immediately after.
        const val POST_DISMISSAL_EVICT_WINDOW_MS = 1_500L
        const val SCHEDULE_COOLDOWN_MS = 4_000L
        @Volatile
        private var instance: SafeMeAccessibilityService? = null

        /**
         * [Re-gate race fix] Uptime of the most recent gate dismissal, set by
         * [onGateDismissed] (event-independent — same rationale as
         * [gateDismissedPending]). The watchdog and content-event path use it
         * to evict the a11y detail page instead of re-gating it within
         * [POST_DISMISSAL_EVICT_WINDOW_MS].
         */
        @Volatile
        private var lastGateDismissalMs = 0L

        /** True when a gate dismissal happened within [POST_DISMISSAL_EVICT_WINDOW_MS]. */
        fun isWithinPostDismissalWindow(): Boolean {
            return SystemClock.elapsedRealtime() - lastGateDismissalMs <
                POST_DISMISSAL_EVICT_WINDOW_MS
        }

        /**
         * Set by [BlockGateActivity.onDestroy] — the ONLY dismissal signal that
         * does not depend on accessibility-event delivery (OEMs like Vivo drop
         * or delay those events). Consumed by the live service on the next
         * event/watchdog tick to re-arm the PU + keyword cooldowns, so a rapid
         * re-open of a protected surface gates immediately instead of being
         * silently suppressed inside the 4 s dedupe window.
         */
        @Volatile
        private var gateDismissedPending = false

        /** Called by [com.safeme.app.BlockGateActivity] whenever the gate finishes. */
        fun onGateDismissed() {
            gateDismissedPending = true
            lastGateDismissalMs = SystemClock.elapsedRealtime()
            // [Re-gate latency] Poke the live service directly — no
            // accessibility-event-delivery dependency — so the cooldowns
            // re-arm and the post-dismissal re-probe chain starts NOW, not on
            // the next event or 1 s watchdog tick.
            try {
                instance?.onGateDismissedImmediate()
            } catch (_: Throwable) {
            }
        }

        /** Returns true once per gate dismissal; consumed by the service instance. */
        fun consumeGateDismissedPending(): Boolean {
            if (gateDismissedPending) {
                gateDismissedPending = false
                return true
            }
            return false
        }

        /**
         * Pokes the live service to re-check the current foreground window.
         * Called by [FocusEngine] when the active launch-block set changes.
         * This bypasses the normal recheck throttle so a newly active schedule
         * gates an already-open targeted app without waiting several seconds.
         */
        fun onFocusSetsChanged() {
            val service = instance ?: return
            try {
                Handler(Looper.getMainLooper()).post {
                    runCatching { service.recheckFocusBlock(force = true) }
                }
            } catch (_: Throwable) {
            }
        }

        /**
         * Surfaces that must stay reachable during a "block everything"
         * schedule so the user is never locked out of the device.
         */
        private val FOCUS_SYSTEM_EXEMPT = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.documentsui",
            "com.android.providers.media",
            "com.android.phone",
            "com.android.server.telecom",
        )
    }

    // [H3 fix] Removed private isSettingsPackage + SETTINGS_PACKAGES — now
    // delegates to ProtectedSystemPages.isSettingsPackage for a single source
    // of truth across PU guards and title-blocking.

    private fun looksLikeSettingsPackage(pkg: String): Boolean =
        pkg.contains("settings", ignoreCase = true)
}
