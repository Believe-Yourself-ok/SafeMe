package com.safeme.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException

/**
 * The Quick Actions available on the Home screen. The user curates which ones
 * are shown and in what order (see [quickActionPrefs]); the persisted list is
 * the *enabled, ordered* subset — anything not present is hidden and can be
 * re-added from the editor.
 */
enum class QuickActionType(val id: String) {
    KEYWORD("keyword"),
    FOCUS_SESSIONS("schedule"),
    BACKUP("backup"),
    WEBSITES("websites"),
    VPN("vpn"),
    APPLOCK("applock"),
    HISTORY("history");

    companion object {
        /** Resilient lookup — unknown/corrupt persisted ids fall back to null. */
        fun fromId(id: String?): QuickActionType? = entries.firstOrNull { it.id == id }
    }
}

/** The default actions, in their original order — used when no prefs exist yet. */
val DEFAULT_QUICK_ACTIONS: List<QuickActionType> =
    listOf(QuickActionType.KEYWORD, QuickActionType.FOCUS_SESSIONS, QuickActionType.BACKUP, QuickActionType.APPLOCK)

private val Context.quickActionsDataStore by preferencesDataStore(name = "quick_actions_prefs")

val KEY_QUICK_ACTIONS_JSON = stringPreferencesKey("quick_actions_json")

fun Context.quickActionPrefs(): Flow<List<QuickActionType>> =
    quickActionsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> quickActionsFromJson(prefs[KEY_QUICK_ACTIONS_JSON]) }

/** Persists the enabled actions in their display order. */
suspend fun Context.setQuickActions(actions: List<QuickActionType>) {
    quickActionsDataStore.edit { prefs ->
        prefs[KEY_QUICK_ACTIONS_JSON] = quickActionsToJson(actions)
    }
}

/** Pure JSON codec — unit-testable without Android. */
fun quickActionsToJson(actions: List<QuickActionType>): String =
    JSONArray(actions.map { it.id }).toString()

/**
 * Decodes the persisted ordered list. `null`/blank (no prefs yet) → the
 * defaults; an explicit `[]` stays empty (the user removed everything);
 * unknown ids are silently dropped so a stale/corrupt entry never crashes.
 */
fun quickActionsFromJson(raw: String?): List<QuickActionType> {
    if (raw.isNullOrBlank()) return DEFAULT_QUICK_ACTIONS
    return try {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                QuickActionType.fromId(arr.optString(i))?.let { add(it) }
            }
        }
    } catch (_: JSONException) {
        DEFAULT_QUICK_ACTIONS
    }
}
