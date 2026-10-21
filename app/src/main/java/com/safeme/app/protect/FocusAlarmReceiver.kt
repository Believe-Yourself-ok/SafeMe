package com.safeme.app.protect

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-applies schedule rules at the next boundary, and re-arms alarms after
 * boot / package update — the prototype's "boot re-arm" guarantee.
 *
 * [FocusEngine.apply] schedules one exact alarm for the next start/end
 * boundary; when it fires, this receiver re-evaluates (which may restart the
 * VPN or poke the accessibility service) and schedules the following boundary.
 * On API 31+ without the exact-alarm permission the alarm degrades to inexact
 * — the 60s safety ticker in [com.safeme.app.SafeMeApp] bounds the drift.
 */
class FocusAlarmReceiver : BroadcastReceiver() {

    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_BOUNDARY,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val result = goAsync()
                receiverScope.launch {
                    try {
                        FocusEngine.reevaluate(context)
                    } catch (_: Throwable) {
                        // Re-evaluation failures are swallowed by the engine;
                        // nothing more to do here.
                    } finally {
                        result.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_BOUNDARY = "com.safeme.app.action.SCHEDULE_BOUNDARY"
        private const val REQUEST_CODE = 9107

        /** (Re)arms the next boundary alarm. [atMillis] == Long.MAX_VALUE cancels. */
        fun scheduleBoundary(context: Context, atMillis: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, FocusAlarmReceiver::class.java).setAction(ACTION_BOUNDARY)
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching { am.cancel(pi) }
            if (atMillis == Long.MAX_VALUE) return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    // No exact-alarm permission → inexact is the best allowed.
                    am.set(AlarmManager.RTC_WAKEUP, atMillis, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                }
            } catch (t: Throwable) {
                // Fall back to an inexact alarm — never crash the caller.
                runCatching { am.set(AlarmManager.RTC_WAKEUP, atMillis, pi) }
            }
        }
    }
}
