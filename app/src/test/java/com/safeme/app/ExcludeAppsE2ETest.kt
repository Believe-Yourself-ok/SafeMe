package com.safeme.app

import com.safeme.app.data.BackupCodec
import com.safeme.app.data.BackupParseResult
import com.safeme.app.data.BackupSnapshot
import com.safeme.app.data.BlockedCategory
import com.safeme.app.data.BlockedKeyword
import com.safeme.app.data.BlockingPrefsState
import com.safeme.app.data.KEY_BLOCKING_EXCLUDED_APPS
import com.safeme.app.data.KEY_EXCLUDED_APPS
import com.safeme.app.service.isExcludedFromContentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the Exclude-Apps-from-Blocking feature.
 *
 * Semantics (v2, "always exclude"): there is NO master toggle — membership
 * in [BlockingPrefsState.excludedApps] alone suppresses the keyword / porn
 * keyword / title / URL content engine for that app, unconditionally.
 */
class ExcludeAppsE2ETest {

    // ------------------------------------------------------------ prefs layer

    @Test
    fun keysAreDistinctFromFocusPrefs() {
        // The blocking feature must not collide with Schedule's own
        // per-schedule exclusion list stored under "excluded_apps".
        assertEquals("blocking_excluded_apps", KEY_BLOCKING_EXCLUDED_APPS.name)
        assertEquals("excluded_apps", KEY_EXCLUDED_APPS.name)
    }

    @Test
    fun defaultStateHasEmptyExclusionSet() {
        val s = BlockingPrefsState()
        assertTrue(s.excludedApps.isEmpty())
    }

    @Test
    fun statePreservesPackageCase_andMultiplePackages() {
        val s = BlockingPrefsState(
            excludedApps = setOf("com.android.vending", "Com.CamelCase.App"),
        )
        assertTrue("com.android.vending" in s.excludedApps)
        assertTrue("Com.CamelCase.App" in s.excludedApps)
        assertEquals(2, s.excludedApps.size)
    }

    @Test
    fun excludedAppsSurviveCopyAndEquality() {
        val a = BlockingPrefsState(excludedApps = setOf("com.a"))
        val b = a.copy()
        assertEquals(a, b)
        assertTrue(b.excludedApps.contains("com.a"))
    }

    // ---------------------------------------------------------- backup codec

    @Test
    fun backupRoundTripPreservesExcludedApps() {
        val original = BlockingPrefsState(
            blockingEnabled = true,
            excludedApps = setOf("com.android.vending", "com.google.android.youtube"),
            blocklistKeywords = listOf(BlockedKeyword("porn", BlockedCategory.ADULT)),
        )
        val snapshot = BackupSnapshot(
            appVersion = "0.1.0",
            createdAt = "2026-08-23T00:00:00Z",
            blocking = original,
        )
        val restored = BackupCodec.fromJsonc(
            BackupCodec.toJsonc(snapshot, appVersion = snapshot.appVersion, createdAt = snapshot.createdAt),
        )
        assertTrue(restored is BackupParseResult.Success)
        val blocking = (restored as BackupParseResult.Success).snapshot.blocking!!
        assertEquals(original.excludedApps, blocking.excludedApps)
        assertEquals(original.blocklistKeywords, blocking.blocklistKeywords)
    }

    @Test
    fun backupBackwardCompat_missingExcludedAppsDefaultsToEmpty() {
        val raw = """
            {"format": "safeme-backup", "schemaVersion": 1,
             "blocking": {"blockingEnabled": true, "blocklistKeywords": []}}
        """.trimIndent()
        val result = BackupCodec.fromJsonc(raw)
        assertTrue(result is BackupParseResult.Success)
        val blocking = (result as BackupParseResult.Success).snapshot.blocking!!
        assertTrue(blocking.excludedApps.isEmpty())
    }

    @Test
    fun backupJsonContainsExcludedAppsKey() {
        val snapshot = BackupSnapshot(
            appVersion = "0.1.0",
            createdAt = "2026-08-23T00:00:00Z",
            blocking = BlockingPrefsState(excludedApps = setOf("com.a")),
        )
        val jsonc = BackupCodec.toJsonc(snapshot, appVersion = snapshot.appVersion, createdAt = snapshot.createdAt)
        assertTrue("excludedApps" in jsonc)
        assertTrue("com.a" in jsonc)
    }

    // --------------------------------------------------- service guard logic

    /**
     * Mirrors SafeMeAccessibilityService.handleEvent: an excluded app must be
     * suppressed when EITHER the event package OR the foreground package is
     * excluded. The IME scenario: user types an adult term inside an excluded
     * app; the keyboard emits events whose pkg is the IME while
     * rootInActiveWindow belongs to the excluded foreground app.
     */
    @Test
    fun guardSuppressedWhenEventPkgExcluded() {
        val excluded = setOf("com.android.vending")
        assertTrue(isExcludedFromContentEngine("com.android.vending", "com.android.vending", excluded))
    }

    @Test
    fun guardSuppressedWhenOnlyForegroundPkgExcluded_imeScenario() {
        // Event emitted by the keyboard, window tree belongs to Play Store.
        val excluded = setOf("com.android.vending")
        assertTrue(isExcludedFromContentEngine("com.google.android.inputmethod.latin", "com.android.vending", excluded))
    }

    @Test
    fun guardNotSuppressedWhenNeitherPkgExcluded() {
        val excluded = setOf("com.android.vending")
        assertFalse(isExcludedFromContentEngine("com.android.chrome", "com.android.chrome", excluded))
    }

    @Test
    fun guardEmptySetNeverSuppresses_evenForSameApp() {
        val excluded = emptySet<String>()
        assertFalse(isExcludedFromContentEngine("com.android.vending", "com.android.vending", excluded))
        assertFalse(isExcludedFromContentEngine("com.android.vending", null, excluded))
    }

    @Test
    fun guardHandlesNullPackages() {
        val excluded = setOf("com.a")
        assertFalse(isExcludedFromContentEngine(null, null, excluded))
        assertTrue(isExcludedFromContentEngine(null, "com.a", excluded))
        assertTrue(isExcludedFromContentEngine("com.a", null, excluded))
    }

    // ------------------------------------------------------------- regression

    @Test
    fun scheduleExcludedAppsKeyUnchanged() {
        assertEquals("excluded_apps", KEY_EXCLUDED_APPS.name)
    }
}
