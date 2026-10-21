package com.safeme.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Activity feed event kinds (drives dot color + icon choice on the Home feed). */
const val ACTIVITY_BLOCK = "block"
const val ACTIVITY_FOCUS = "schedule"
const val ACTIVITY_VPN = "vpn"
const val ACTIVITY_A11Y = "a11y"

/**
 * One entry in the recent-activity log shown on the Home feed and the History
 * screen. [sub] is the secondary line; [timeMillis] is when the event happened.
 */
data class ActivityEntry(
    val type: String,
    val title: String,
    val sub: String,
    val timeMillis: Long,
)

private val Context.activityDataStore by preferencesDataStore(name = "activity_log_prefs")

val KEY_ACTIVITY_JSON = stringPreferencesKey("activity_json")
const val ACTIVITY_LOG_CAP = 50

fun Context.activityLog(): Flow<List<ActivityEntry>> =
    activityDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> activityFromJson(prefs[KEY_ACTIVITY_JSON]) }

/**
 * Prepends an entry, dropping a consecutive duplicate of the same (type,title)
 * so service restarts / re-arms never spam the feed, and caps the list.
 */
suspend fun Context.addActivity(
    type: String,
    title: String,
    sub: String,
    timeMillis: Long = System.currentTimeMillis(),
) {
    activityDataStore.edit { prefs ->
        val current = activityFromJson(prefs[KEY_ACTIVITY_JSON])
        prefs[KEY_ACTIVITY_JSON] = activityToJson(
            appendActivity(current, ActivityEntry(type, title, sub, timeMillis))
        )
    }
}

/** Pure append-with-dedupe-and-cap — unit-testable without Android. */
fun appendActivity(
    current: List<ActivityEntry>,
    entry: ActivityEntry,
    cap: Int = ACTIVITY_LOG_CAP,
): List<ActivityEntry> {
    if (current.firstOrNull()?.let { it.type == entry.type && it.title == entry.title } == true) {
        return current
    }
    return (listOf(entry) + current).take(cap)
}

/** "h:mm a" for today, "EEE h:mm a" for earlier days. */
fun formatActivityTime(timeMillis: Long, now: Long = System.currentTimeMillis()): String {
    val cal = Calendar.getInstance().apply { timeInMillis = timeMillis }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "h:mm a" else "EEE h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timeMillis))
}

fun activityToJson(list: List<ActivityEntry>): String {
    val arr = JSONArray()
    list.forEach { e ->
        val o = JSONObject()
        o.put("t", e.type)
        o.put("ti", e.title)
        o.put("s", e.sub)
        o.put("m", e.timeMillis)
        arr.put(o)
    }
    return arr.toString()
}

fun activityFromJson(json: String?): List<ActivityEntry> {
    val text = json ?: ""
    if (text.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(text)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = o.optString("t")
                val title = o.optString("ti")
                if (type.isBlank() || title.isBlank()) continue
                add(
                    ActivityEntry(
                        type = type,
                        title = title,
                        sub = o.optString("s"),
                        timeMillis = o.optLong("m", 0L),
                    )
                )
            }
        }
    } catch (e: JSONException) {
        emptyList()
    }
}
