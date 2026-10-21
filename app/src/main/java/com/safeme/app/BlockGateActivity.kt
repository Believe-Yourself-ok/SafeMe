package com.safeme.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.safeme.app.R
import com.safeme.app.data.ACTIVITY_BLOCK
import com.safeme.app.data.BlockScreenPrefsState
import com.safeme.app.data.addActivity
import com.safeme.app.data.blockScreenPrefs
import com.safeme.app.data.incrementBlockedToday
import com.safeme.app.service.SafeMeAccessibilityService
import com.safeme.app.ui.screens.blockscreen.BlockOverlay
import com.safeme.app.ui.theme.SafeMeApp
import kotlinx.coroutines.launch

/**
 * Full-screen block gate raised by [service.SafeMeAccessibilityService] over an offending
 * app. Reuses the self-contained [BlockOverlay] composable (dwell countdown + ready-gated
 * Close) and honors the persisted Block Screen settings: dwell countdown, custom message
 * and the "Why am I seeing this?" toggle. Increments the persisted
 * blocked-today counter for every real block it shows.
 *
 * Robustness:
 *  - Increment happens only on first creation (never re-incremented on recreation/rotation).
 *  - Close ALWAYS returns the user to the launcher (HOME) — never back to the page that
 *    triggered the block. This applies globally to every gate type and source.
 *  - Settings load asynchronously (defaults render first); failure to persist the counter
 *    is swallowed and never crashes the gate.
 */
class BlockGateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val matched = intent.getStringExtra(EXTRA_MATCHED).orEmpty()
        val type = intent.getStringExtra(EXTRA_TYPE).orEmpty()

        if (savedInstanceState == null) {
            lifecycleScope.launch {
                try {
                    incrementBlockedToday()
                    addBlockActivity(pkg, matched, type)
                } catch (t: Throwable) {
                    // Persistence failure must never crash the gate.
                }
            }
        }

        setContent {
            SafeMeApp {
                BlockGate(
                    pkg = pkg,
                    matched = matched,
                    type = type,
                    onClose = { closeGate() },
                )
            }
        }
    }

    private suspend fun addBlockActivity(pkg: String, matched: String, type: String) {
        val label = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        }.getOrDefault(pkg.ifBlank { "an app" })
        val title = when (type) {
            "website" -> "Website blocked"
            "title" -> "Settings page blocked"
            "schedule" -> "Blocked $label"
            "pu" -> "Uninstall blocked"
            else -> if (matched.isNotEmpty()) "Keyword blocked" else "Blocked $label"
        }
        val sub = when {
            matched.isNotEmpty() -> matched
            type == "schedule" -> "Launch blocked by focus session"
            type == "pu" -> "Prevent Uninstall is on"
            else -> "Blocked by SafeMe"
        }
        addActivity(ACTIVITY_BLOCK, title, sub)
    }

    override fun onDestroy() {
        // [Fix] Signal the accessibility service that the gate finished. This
        // is the ONLY dismissal signal independent of accessibility-event
        // delivery (OEMs drop or delay those events), so the PU + keyword
        // cooldowns are re-armed and a rapid re-open of the protected page is
        // gated again immediately instead of being suppressed by the 4 s dedupe
        // window. Runs on every finish (Close, BACK, task removal, rotation).
        SafeMeAccessibilityService.onGateDismissed()
        super.onDestroy()
    }

    private fun closeGate() {
        // [Global] Close ALWAYS goes to the launcher — never back to the page
        // that triggered the block, regardless of gate type or how it was
        // raised; finishing without this would return the user to the blocked
        // page (and a PU gate would be re-raised over it).
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (t: Throwable) {
            // If the launcher can't be started, a plain finish still exits the gate.
        }
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "safeme.gate.package"
        const val EXTRA_MATCHED = "safeme.gate.matched"
        const val EXTRA_TYPE = "safeme.gate.type"
    }
}

@Composable
private fun BlockGate(
    pkg: String,
    matched: String,
    type: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // Persisted Block Screen settings (dwell, custom message, why toggle). The
    // flow renders defaults on the first frame, then the saved values, so the
    // gate never blocks on DataStore.
    val prefs by context.blockScreenPrefs().collectAsState(initial = BlockScreenPrefsState())
    // [L1 fix] locale is captured as a remember key so the PU/schedule reason
    // text updates when the system locale changes mid-display.
    val locale = LocalConfiguration.current.locales[0]
    val defaultMessage = stringResource(R.string.bs_preview_msg_default)
    val puGateMessage = stringResource(R.string.pu_gate_message)
    val scheduleGateMessage = stringResource(R.string.schedule_gate_message)
    // The persisted custom message is the gate message (default fallback), so
    // the live gate matches the Block Screen preview and the prototype.
    val msg = remember(prefs.message, defaultMessage, locale) {
        prefs.message.ifEmpty { defaultMessage }
    }
    // The block context is rendered inline by the shared overlay.
    val whyReason = remember(pkg, matched, type, puGateMessage, scheduleGateMessage, locale) {
        blockGateWhyReason(type, matched, puGateMessage, scheduleGateMessage)
    }
    BlockOverlay(
        dwell = prefs.dwell,
        msg = msg,
        whyOn = prefs.whyOn,
        whyReason = whyReason,
        onClose = onClose,
    )
}
