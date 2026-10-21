package com.safeme.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * Blocking mode of a focus session. Mirrors the prototype's segmented control
 * (Internet | Launch | Both).
 */
enum class FocusMode(val label: String) {
    INTERNET("Internet"),
    LAUNCH("Launch"),
    BOTH("Both");

    companion object {
        /** Resilient lookup — unknown/corrupt persisted names fall back to null. */
        fun fromName(name: String?): FocusMode? = entries.firstOrNull { it.name == name }
    }
}

/**
 * A recurring app-blocking rule.
 *
 * @param days Day-of-week indices in the prototype's order: 0=Mon … 6=Sun.
 * @param startMinute Minutes from midnight when the window starts (0..1439).
 * @param endMinute Minutes from midnight when the window ends; must not equal
 *   [startMinute]. May be less than [startMinute] for overnight windows,
 *   which spill into the next day (the UI validates "Start and end can't be
 *   the same").
 * @param appPackages Packages targeted by this rule. Empty means "no apps
 *   picked" → the focus session blocks everything ([blocksAllApps]).
 */
data class FocusSession(
    val id: String,
    val name: String,
    val days: List<Int>,
    val startMinute: Int,
    val endMinute: Int,
    val mode: FocusMode,
    val appPackages: List<String>,
    val enabled: Boolean = true,
) {
    val blocksAllApps: Boolean get() = appPackages.isEmpty()
}

data class FocusPrefsState(
    val schedules: List<FocusSession> = emptyList(),
    /** User dismissed the "Accessibility Service required" banner. */
    val a11yWarningDismissed: Boolean = false,
    /** User dismissed the "Precise timing" banner. */
    val exactAlarmWarningDismissed: Boolean = false,
    /** Global exclusion list: packages never blocked by any focus session. */
    val excludedApps: Set<String> = emptySet(),
)

private val Context.focusDataStore by preferencesDataStore(name = "schedule_prefs")

val KEY_FOCUS_SESSIONS_JSON = stringPreferencesKey("schedules_json")
val KEY_A11Y_WARN_DISMISSED = booleanPreferencesKey("a11y_warn_dismissed")
val KEY_EXACT_ALARM_WARN_DISMISSED = booleanPreferencesKey("exact_alarm_warn_dismissed")
val KEY_EXCLUDED_APPS = stringSetPreferencesKey("excluded_apps")

/** Prototype day order: Mon … Sun. */
val FOCUS_DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun Context.focusPrefs(): Flow<FocusPrefsState> =
    focusDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            FocusPrefsState(
                schedules = focusSessionsFromJson(prefs[KEY_FOCUS_SESSIONS_JSON]),
                a11yWarningDismissed = prefs[KEY_A11Y_WARN_DISMISSED] ?: false,
                exactAlarmWarningDismissed = prefs[KEY_EXACT_ALARM_WARN_DISMISSED] ?: false,
                excludedApps = prefs[KEY_EXCLUDED_APPS] ?: emptySet(),
            )
        }

/** Persist the "Accessibility Service required" banner dismissal. */
suspend fun Context.setA11yWarningDismissed(dismissed: Boolean) {
    focusDataStore.edit { prefs ->
        prefs[KEY_A11Y_WARN_DISMISSED] = dismissed
    }
}

/** Persist the "Precise timing" banner dismissal. */
suspend fun Context.setExactAlarmWarningDismissed(dismissed: Boolean) {
    focusDataStore.edit { prefs ->
        prefs[KEY_EXACT_ALARM_WARN_DISMISSED] = dismissed
    }
}

/** Persist the global focus-session exclusion list. */
suspend fun Context.setExcludedApps(apps: Set<String>) {
    focusDataStore.edit { prefs ->
        prefs[KEY_EXCLUDED_APPS] = apps
    }
}

fun focusSessionsToJson(list: List<FocusSession>): String {
    val arr = JSONArray()
    list.forEach { s ->
        val o = JSONObject()
        o.put("id", s.id)
        o.put("n", s.name)
        o.put("d", JSONArray(s.days))
        o.put("st", s.startMinute)
        o.put("en", s.endMinute)
        o.put("m", s.mode.name)
        o.put("a", JSONArray(s.appPackages))
        o.put("e", s.enabled)
        arr.put(o)
    }
    return arr.toString()
}

fun focusSessionsFromJson(json: String?): List<FocusSession> {
    val text = json ?: ""
    if (text.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(text)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val name = o.optString("n").trim()
                if (id.isEmpty() || name.isEmpty()) continue
                val days = buildList {
                    val d = o.optJSONArray("d")
                    if (d != null) {
                        for (j in 0 until d.length()) {
                            val v = d.optInt(j, -1)
                            if (v in 0..6 && v !in this) add(v)
                        }
                    }
                }
                if (days.isEmpty()) continue
                val start = o.optInt("st", -1)
                val end = o.optInt("en", -1)
                if (start !in 0..1439 || end !in 0..1439 || end == start) continue
                val mode = FocusMode.fromName(o.optString("m")) ?: continue
                val apps = buildList {
                    val a = o.optJSONArray("a")
                    if (a != null) {
                        for (j in 0 until a.length()) {
                            val p = a.optString(j).trim()
                            if (p.isNotEmpty() && p !in this) add(p)
                        }
                    }
                }
                add(
                    FocusSession(
                        id = id,
                        name = name,
                        days = days,
                        startMinute = start,
                        endMinute = end,
                        mode = mode,
                        appPackages = apps,
                        enabled = o.optBoolean("e", true),
                    )
                )
            }
        }
    } catch (e: JSONException) {
        emptyList()
    }
}

suspend fun Context.addFocusSession(session: FocusSession) {
    focusDataStore.edit { prefs ->
        val list = focusSessionsFromJson(prefs[KEY_FOCUS_SESSIONS_JSON])
        if (list.none { it.id == session.id }) {
            prefs[KEY_FOCUS_SESSIONS_JSON] = focusSessionsToJson(list + session)
        }
    }
}

suspend fun Context.updateFocusSession(session: FocusSession) {
    focusDataStore.edit { prefs ->
        val list = focusSessionsFromJson(prefs[KEY_FOCUS_SESSIONS_JSON])
        val updated = list.map { if (it.id == session.id) session else it }
        prefs[KEY_FOCUS_SESSIONS_JSON] = focusSessionsToJson(updated)
    }
}

suspend fun Context.deleteFocusSession(id: String) {
    focusDataStore.edit { prefs ->
        val list = focusSessionsFromJson(prefs[KEY_FOCUS_SESSIONS_JSON])
        prefs[KEY_FOCUS_SESSIONS_JSON] = focusSessionsToJson(list.filter { it.id != id })
    }
}

suspend fun Context.toggleFocusSession(id: String, enabled: Boolean) {
    focusDataStore.edit { prefs ->
        val list = focusSessionsFromJson(prefs[KEY_FOCUS_SESSIONS_JSON])
        val updated = list.map { if (it.id == id) it.copy(enabled = enabled) else it }
        prefs[KEY_FOCUS_SESSIONS_JSON] = focusSessionsToJson(updated)
    }
}

/** Replaces all focus-session configuration in one atomic edit (backup restore). */
suspend fun Context.writeFocusPrefs(state: FocusPrefsState) {
    focusDataStore.edit { prefs ->
        prefs[KEY_FOCUS_SESSIONS_JSON] = focusSessionsToJson(state.schedules)
        prefs[KEY_A11Y_WARN_DISMISSED] = state.a11yWarningDismissed
        prefs[KEY_EXACT_ALARM_WARN_DISMISSED] = state.exactAlarmWarningDismissed
        prefs[KEY_EXCLUDED_APPS] = state.excludedApps
    }
}

/** Factory for new focus-session ids — mirrors [addTitleBlockRule] in BlockingPrefs. */
fun newFocusSessionId(): String = UUID.randomUUID().toString()

/** True when a focus-blocking mode needs SafeMe's accessibility service (launch blocking). */
fun requiresAccessibility(mode: FocusMode): Boolean =
    mode == FocusMode.LAUNCH || mode == FocusMode.BOTH

/**
 * Visibility of the "Accessibility Service required" banner: at least one
 * ENABLED focus session needs launch blocking, the service is off, and the user
 * hasn't dismissed the warning. A paused focus session never nags.
 */
fun shouldShowA11yWarning(
    schedules: List<FocusSession>,
    a11yEnabled: Boolean,
    dismissed: Boolean,
): Boolean {
    if (a11yEnabled || dismissed) return false
    return schedules.any { it.enabled && requiresAccessibility(it.mode) }
}

/**
 * Visibility of the "Precise timing" banner: API 31+, at least one
 * ENABLED focus session exists, exact alarms are not granted, and the user hasn't
 * dismissed the warning. A paused focus session never nags. [sdkInt] is injectable
 * for unit tests (defaults to the device SDK).
 */
fun shouldShowExactAlarmWarning(
    schedules: List<FocusSession>,
    granted: Boolean,
    dismissed: Boolean,
    sdkInt: Int = android.os.Build.VERSION.SDK_INT,
): Boolean {
    if (sdkInt < android.os.Build.VERSION_CODES.S || granted || dismissed) return false
    return schedules.any { it.enabled }
}

// ---------------------------------------------------------------- pure helpers

/** Prototype `daysLabel`: "Daily" for all 7 days, else "Mon · Wed · Fri". */
fun focusDaysLabel(days: List<Int>): String {
    val normalized = days.distinct().filter { it in 0..6 }
    if (normalized.isEmpty()) return ""
    if (normalized.size == 7) return "Daily"
    return normalized.sorted().joinToString(" · ") { FOCUS_DAY_NAMES[it] }
}

/** Prototype `modeTxt`: Both → "Internet + Launch", etc. */
fun focusModeLabel(mode: FocusMode): String = when (mode) {
    FocusMode.BOTH -> "Internet + Launch"
    FocusMode.INTERNET -> "Internet blocked"
    FocusMode.LAUNCH -> "Launch blocked"
}

/** 24h "HH:mm" formatting (prototype `.sched-time-txt` / hero boundary text). */
fun focusTimeLabel(minute: Int): String =
    "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"

/**
 * "21:00 – 23:00" card time row (prototype uses an en-dash with spaces).
 * Overnight windows append " (+1)" to mark the spill into the next day.
 */
fun focusWindowLabel(startMinute: Int, endMinute: Int): String =
    "${focusTimeLabel(startMinute)} – ${focusTimeLabel(endMinute)}" +
        if (endMinute < startMinute) " (+1)" else ""
