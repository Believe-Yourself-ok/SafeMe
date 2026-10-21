package com.safeme.app.data

import com.safeme.app.protect.FocusAlarmReceiver
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COD-80 renamed the Schedule feature to FocusMe.
 *
 * Kotlin names (classes, files, functions, routes) are free to change, but the
 * identifiers below are persisted: they live in DataStore, inside backup files,
 * in the activity feed, in the quick-action list, or in armed alarms. Changing
 * any of them would silently drop data for existing installs or break restores.
 *
 * A failure here means the *value*, not the test, is wrong.
 */
class FrozenIdentifiersTest {

    // ---------------------------------------------------------------- DataStore

    @Test
    fun dataStoreKeysAreFrozen() {
        assertEquals("schedules_json", KEY_FOCUS_SESSIONS_JSON.name)
        assertEquals("a11y_warn_dismissed", KEY_A11Y_WARN_DISMISSED.name)
        assertEquals("exact_alarm_warn_dismissed", KEY_EXACT_ALARM_WARN_DISMISSED.name)
        assertEquals("excluded_apps", KEY_EXCLUDED_APPS.name)
    }

    // ------------------------------------------------------------ session JSON

    @Test
    fun sessionJsonShapeIsFrozen() {
        val session = FocusSession(
            id = "id-1",
            name = "Study",
            days = listOf(0),
            startMinute = 60,
            endMinute = 120,
            mode = FocusMode.BOTH,
            appPackages = listOf("com.x"),
        )
        // Key ORDER is not part of the contract (org.json is unordered); the
        // exact set of short field names is.
        val row = JSONArray(focusSessionsToJson(listOf(session))).getJSONObject(0)
        assertEquals(
            setOf("id", "n", "d", "st", "en", "m", "a", "e"),
            row.keys().asSequence().toSet(),
        )
        assertEquals("id-1", row.getString("id"))
        assertEquals("Study", row.getString("n"))
        assertEquals(0, row.getJSONArray("d").getInt(0))
        assertEquals(60, row.getInt("st"))
        assertEquals(120, row.getInt("en"))
        assertEquals("BOTH", row.getString("m"))
        assertEquals("com.x", row.getJSONArray("a").getString(0))
        assertTrue(row.getBoolean("e"))
    }

    @Test
    fun modeWireNamesAreFrozen() {
        assertEquals("INTERNET", FocusMode.INTERNET.name)
        assertEquals("LAUNCH", FocusMode.LAUNCH.name)
        assertEquals("BOTH", FocusMode.BOTH.name)
        // decoding of values written by an older install must keep working
        assertEquals(FocusMode.BOTH, FocusMode.fromName("BOTH"))
        assertEquals(FocusMode.INTERNET, FocusMode.fromName("INTERNET"))
    }

    // ---------------------------------------------------------------- backups

    @Test
    fun backupFormatIdentityIsFrozen() {
        assertEquals("safeme-backup", BACKUP_FORMAT_ID)
        assertEquals(1, BACKUP_SCHEMA_VERSION)
    }

    @Test
    fun preRenameBackupStillDecodes() {
        // Shape written by v0.1.0 (before the FocusMe rename): the section key is
        // still "schedules" and the session rows still use the short field names.
        val legacy = """
            {
              "format": "safeme-backup",
              "schemaVersion": 1,
              "appVersion": "0.1.0",
              "createdAt": "2026-08-11T07:45:00Z",
              "schedules": {
                "schedules": [
                  {"id":"s1","n":"Study","d":[0,2],"st":540,"en":1380,"m":"BOTH","a":["com.instagram.android"],"e":true}
                ],
                "a11yWarningDismissed": true,
                "exactAlarmWarningDismissed": false,
                "excludedApps": ["com.example.x"]
              }
            }
        """.trimIndent()

        val parsed = BackupCodec.fromJsonc(legacy)
        assertTrue("legacy backup must parse", parsed is BackupParseResult.Success)
        val sessions = (parsed as BackupParseResult.Success).snapshot.schedules
        assertEquals(1, sessions?.schedules?.size)
        val only = sessions!!.schedules.single()
        assertEquals("Study", only.name)
        assertEquals(listOf(0, 2), only.days)
        assertEquals(540, only.startMinute)
        assertEquals(1380, only.endMinute)
        assertEquals(FocusMode.BOTH, only.mode)
        assertEquals(listOf("com.instagram.android"), only.appPackages)
        assertTrue(only.enabled)
        assertEquals(setOf("com.example.x"), sessions.excludedApps)
        assertTrue(sessions.a11yWarningDismissed)
    }

    // ------------------------------------------------- cross-feature tokens

    @Test
    fun activityAndQuickActionTokensAreFrozen() {
        assertEquals("schedule", ACTIVITY_FOCUS)
        assertEquals("schedule", QuickActionType.FOCUS_SESSIONS.id)
    }

    @Test
    fun legacyQuickActionIdStillResolves() {
        // A v0.1.0 user has ["keyword","schedule","backup","applock"] on disk.
        // The enum constant is now FOCUS_SESSIONS but the persisted id is not.
        assertEquals(
            listOf(
                QuickActionType.KEYWORD,
                QuickActionType.FOCUS_SESSIONS,
                QuickActionType.BACKUP,
                QuickActionType.APPLOCK,
            ),
            quickActionsFromJson("""["keyword","schedule","backup","applock"]"""),
        )
        assertEquals(DEFAULT_QUICK_ACTIONS[1], QuickActionType.FOCUS_SESSIONS)
    }

    // -------------------------------------------------------------- alarms

    @Test
    fun alarmActionIsFrozen() {
        assertEquals("com.safeme.app.action.SCHEDULE_BOUNDARY", FocusAlarmReceiver.ACTION_BOUNDARY)
    }
}
