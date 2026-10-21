package com.safeme.app.data

import com.safeme.app.vpn.DnsPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the JSONC backup codec: encode, decode and validation. */
class BackupCodecTest {

    private fun fullSnapshot() = BackupSnapshot(
        appVersion = "0.1.0",
        createdAt = "2026-08-11T07:45:00Z",
        blocking = BlockingPrefsState(
            blocklistKeywords = listOf(BlockedKeyword("instagram", BlockedCategory.SOCIAL_MEDIA)),
            whitelistKeywords = listOf("work"),
            blockedWebsites = listOf(BlockedWebsite("example.com", BlockedCategory.ADULT)),
            trustedWebsites = listOf("safe.example.com"),
            titleBlockRules = listOf(TitleBlockRule("id-1", "settings", TitleMatchMode.CONTAINS, true)),
            blockingEnabled = true,
        ),
        schedules = FocusPrefsState(
            schedules = listOf(
                FocusSession(
                    id = "s1", name = "Study", days = listOf(0, 2), startMinute = 540, endMinute = 1380,
                    mode = FocusMode.BOTH, appPackages = listOf("com.instagram.android"), enabled = true,
                )
            ),
            a11yWarningDismissed = true,
            excludedApps = setOf("com.example.x"),
        ),
        vpn = DnsVpnSettings(
            enabled = true, preset = DnsPreset.ADGUARD_FAMILY, customV4 = "1.1.1.1", customV6 = "",
            whitelist = setOf("com.example.app"), notifMode = NOTIF_CUSTOM, notifCustom = "Filtering on",
        ),
        quickActions = listOf(QuickActionType.HISTORY, QuickActionType.VPN),
        appLock = AppLockPrefsState(
            lockType = LockType.PIN, storedHash = "salt:hash", credentialLength = 4,
            biometricEnabled = true, forgotPasswordDisabled = false, autoLock = AutoLockDelay.AFTER_1M,
        ),
        preventUninstall = PreventUninstallPrefsState(preventUninstallEnabled = true),
        a11yProtection = A11yProtectionPrefsState(
            protectionEnabled = true, protectedComponents = setOf("com.pkg/com.pkg.Service"),
        ),
        blockScreen = BlockScreenPrefsState(
            dwell = 12, message = "Stay safe, Alex.", img = "sunset", whyOn = false,
        ),
    )

    private fun encode(snapshot: BackupSnapshot = fullSnapshot()): String =
        BackupCodec.toJsonc(snapshot, appVersion = snapshot.appVersion, createdAt = snapshot.createdAt)

    private fun decode(raw: String): BackupParseResult = BackupCodec.fromJsonc(raw)

    private fun successOf(result: BackupParseResult): BackupSnapshot {
        assertTrue("expected success but got $result", result is BackupParseResult.Success)
        return (result as BackupParseResult.Success).snapshot
    }

    private fun errorOf(result: BackupParseResult): BackupError {
        assertTrue("expected failure but got $result", result is BackupParseResult.Failure)
        return (result as BackupParseResult.Failure).error
    }

    // ------------------------------------------------------------- roundtrip

    @Test
    fun fullRoundTripPreservesEverything() {
        val original = fullSnapshot()
        assertEquals(original, successOf(decode(encode(original))))
    }

    @Test
    fun jsoncHeaderCommentsAndTrailingCommasAreAccepted() {
        val raw = """
            // SafeMe backup — JSONC
            // Created 2026-08-11T07:45:00Z · app 0.1.0 · schema v1
            {
              "format": "safeme-backup",
              "schemaVersion": 1,
              "appVersion": "0.1.0",
              "createdAt": "2026-08-11T07:45:00Z",
              "blocking": {
                "blocklistKeywords": [{"v": "instagram", "c": "SOCIAL_MEDIA"},],
                "whitelistKeywords": ["work",],
                "blockedWebsites": [],
                "trustedWebsites": [],
                "titleBlockRules": [],
                "blockingEnabled": true,
              },
              "schedules": {
                "schedules": [],
                "a11yWarningDismissed": false,
              },
            }
        """.trimIndent()
        val snapshot = successOf(decode(raw))
        assertEquals(1, snapshot.blocking?.blocklistKeywords?.size)
        assertEquals(BlockedCategory.SOCIAL_MEDIA, snapshot.blocking?.blocklistKeywords?.first()?.category)
        assertEquals(listOf(BackupSection.BLOCKING, BackupSection.FOCUS_SESSIONS), snapshot.presentSections)
    }

    @Test
    fun keywordsWithCommentLikeTextSurviveRoundTrip() {
        val original = fullSnapshot().copy(
            blocking = BlockingPrefsState(
                blocklistKeywords = listOf(
                    BlockedKeyword("https://example.com//path", BlockedCategory.CUSTOM),
                    BlockedKeyword("a /* weird */ keyword", BlockedCategory.CUSTOM),
                ),
                blockingEnabled = true,
            ),
        )
        assertEquals(original, successOf(decode(encode(original))))
    }

    @Test
    fun partialSnapshotOmitsMissingSections() {
        val original = BackupSnapshot(
            appVersion = "0.1.0",
            createdAt = "t",
            blocking = BlockingPrefsState(blockingEnabled = false),
        )
        val decoded = successOf(decode(encode(original)))
        assertEquals(listOf(BackupSection.BLOCKING), decoded.presentSections)
        assertNull(decoded.schedules)
        assertNull(decoded.vpn)
    }

    // ------------------------------------------------------------- failures

    @Test
    fun notJson_isRejected() {
        assertEquals(BackupError.NOT_JSON, errorOf(decode("this is not json")))
        assertEquals(BackupError.NOT_JSON, errorOf(decode("")))
        assertEquals(BackupError.NOT_JSON, errorOf(decode("{\"format\": \"safeme-backup\", "))) // truncated
    }

    @Test
    fun unclosedBlockComment_isRejected() {
        // The comment swallows the closing brace → the file is truncated JSON.
        assertEquals(BackupError.NOT_JSON, errorOf(decode("{\"format\": \"safeme-backup\" /* nope")))
    }

    @Test
    fun wrongFormatMarker_isRejected() {
        val raw = """{"format": "some-other-app", "schemaVersion": 1, "blocking": {}}"""
        assertEquals(BackupError.NOT_SAFEME, errorOf(decode(raw)))
    }

    @Test
    fun missingFormatMarker_isRejected() {
        val raw = """{"schemaVersion": 1, "blocking": {}}"""
        assertEquals(BackupError.NOT_SAFEME, errorOf(decode(raw)))
    }

    @Test
    fun missingSchemaVersion_isInvalidStructure() {
        val raw = """{"format": "safeme-backup", "blocking": {}}"""
        assertEquals(BackupError.INVALID_STRUCTURE, errorOf(decode(raw)))
    }

    @Test
    fun newerSchemaVersion_isUnsupported() {
        val raw = """{"format": "safeme-backup", "schemaVersion": 99, "blocking": {}}"""
        assertEquals(BackupError.UNSUPPORTED_VERSION, errorOf(decode(raw)))
    }

    @Test
    fun olderSchemaVersion_isAccepted() {
        val raw = """{"format": "safeme-backup", "schemaVersion": 0, "blocking": {"blockingEnabled": false}}"""
        val snapshot = successOf(decode(raw))
        assertEquals(0, snapshot.schemaVersion)
    }

    @Test
    fun headerOnlyFile_isEmpty() {
        val raw = """{"format": "safeme-backup", "schemaVersion": 1}"""
        assertEquals(BackupError.EMPTY, errorOf(decode(raw)))
    }

    @Test
    fun sectionOfWrongType_isInvalidStructure() {
        val raw = """{"format": "safeme-backup", "schemaVersion": 1, "blocking": "nope"}"""
        assertEquals(BackupError.INVALID_STRUCTURE, errorOf(decode(raw)))
    }

    @Test
    fun fieldOfWrongTypeInsideSection_isInvalidStructure() {
        val raw = """{"format": "safeme-backup", "schemaVersion": 1, "blocking": {"blockingEnabled": "true"}}"""
        assertEquals(BackupError.INVALID_STRUCTURE, errorOf(decode(raw)))
    }

    @Test
    fun arrayOfWrongTypeInsideSection_isInvalidStructure() {
        val raw = """{"format": "safeme-backup", "schemaVersion": 1, "vpn": {"whitelist": "com.a"}}"""
        assertEquals(BackupError.INVALID_STRUCTURE, errorOf(decode(raw)))
    }

    // ------------------------------------------------------------- tolerance

    @Test
    fun blockScreenRoundTripsAndAbsentKeysFallBackToDefaults() {
        val original = fullSnapshot()
        assertEquals(original, successOf(decode(encode(original))))

        // Only some keys present → the rest take defaults.
        val partial = """{"format": "safeme-backup", "schemaVersion": 1,
            "blockScreen": {"dwell": 300, "message": "Focus"}}"""
        val snapshot = successOf(decode(partial))
        assertEquals(120, snapshot.blockScreen?.dwell) // clamped to the valid range
        assertEquals("Focus", snapshot.blockScreen?.message)
        assertEquals("", snapshot.blockScreen?.img)
        assertEquals(true, snapshot.blockScreen?.whyOn)
    }

    @Test
    fun blockScreenFieldOfWrongType_isInvalidStructure() {
        val raw = """{"format": "safeme-backup", "schemaVersion": 1, "blockScreen": {"whyOn": "yes"}}"""
        assertEquals(BackupError.INVALID_STRUCTURE, errorOf(decode(raw)))
    }

    @Test
    fun unknownQuickActionIdsAreDropped() {
        // "focus" is a legacy id from before the Focus tab removal — it must be
        // dropped exactly like any other unknown id when restoring old backups.
        val raw = """{"format": "safeme-backup", "schemaVersion": 1, "quickActions": ["focus", "bogus", "vpn"]}"""
        val snapshot = successOf(decode(raw))
        assertEquals(listOf(QuickActionType.VPN), snapshot.quickActions)
    }

    @Test
    fun unknownLockTypeFallsBackToOff() {
        val raw = """{"format": "safeme-backup", "schemaVersion": 1, "appLock": {"lockType": "retina-scan"}}"""
        val snapshot = successOf(decode(raw))
        assertEquals(LockType.OFF, snapshot.appLock?.lockType)
    }

    @Test
    fun unknownVpnPresetFallsBackToDefault() {
        val raw = """{"format": "safeme-backup", "schemaVersion": 1, "vpn": {"preset": "nope"}}"""
        val snapshot = successOf(decode(raw))
        assertEquals(DnsPreset.CLOUDFLARE_FAMILY, snapshot.vpn?.preset)
    }

    /** A keyword entry that isn't a {v,c} object would be silently lost → reject. */
    @Test
    fun malformedKeywordEntries_areRejected() {
        val raw = """
            {"format": "safeme-backup", "schemaVersion": 1,
             "blocking": {"blocklistKeywords": [
                 {"v": "good", "c": "CUSTOM"},
                 {"v": ""},
                 {"c": "ADULT"},
                 42
             ]}}
        """.trimIndent()
        assertEquals(BackupError.INVALID_STRUCTURE, errorOf(decode(raw)))
    }

    /** Plain strings are not the keyword format — rejecting beats restoring zero keywords. */
    @Test
    fun keywordArrayOfStrings_isRejected() {
        val raw = """{"format": "safeme-backup", "schemaVersion": 1,
            "blocking": {"blocklistKeywords": ["word"]}}"""
        assertEquals(BackupError.INVALID_STRUCTURE, errorOf(decode(raw)))
    }

    @Test
    fun blockedWebsiteArrayOfStrings_isRejected() {
        val raw = """{"format": "safeme-backup", "schemaVersion": 1,
            "blocking": {"blockedWebsites": ["example.com"]}}"""
        assertEquals(BackupError.INVALID_STRUCTURE, errorOf(decode(raw)))
    }

    @Test
    fun nonStringWhitelistEntry_isRejected() {
        val raw = """{"format": "safeme-backup", "schemaVersion": 1,
            "blocking": {"whitelistKeywords": ["work", 42]}}"""
        assertEquals(BackupError.INVALID_STRUCTURE, errorOf(decode(raw)))
    }

    @Test
    fun malformedTitleRuleEntry_isRejected() {
        val raw = """{"format": "safeme-backup", "schemaVersion": 1,
            "blocking": {"titleBlockRules": [{"v": "settings"}]}}"""
        assertEquals(BackupError.INVALID_STRUCTURE, errorOf(decode(raw)))
    }

    /** Valid {v,c} entries (including unknown categories) still restore. */
    @Test
    fun validKeywordObjectsStillRestore() {
        val raw = """{"format": "safeme-backup", "schemaVersion": 1,
            "blocking": {"blocklistKeywords": [{"v": "ok", "c": "CUSTOM"}, {"v": "future", "c": "NEW_CATEGORY"}]}}"""
        val snapshot = successOf(decode(raw))
        assertEquals(2, snapshot.blocking?.blocklistKeywords?.size)
        assertEquals(BlockedCategory.CUSTOM, snapshot.blocking?.blocklistKeywords?.last()?.category)
    }

    @Test
    fun blankQuickActionsSectionStaysEmptyList() {
        val raw = """{"format": "safeme-backup", "schemaVersion": 1, "quickActions": []}"""
        val snapshot = successOf(decode(raw))
        assertEquals(emptyList<QuickActionType>(), snapshot.quickActions)
        assertEquals(listOf(BackupSection.QUICK_ACTIONS), snapshot.presentSections)
    }

    @Test
    fun exactAlarmDismissalSurvivesRoundTrip() {
        val base = fullSnapshot()
        val original = base.copy(
            schedules = base.schedules?.copy(exactAlarmWarningDismissed = true),
        )
        assertEquals(original, successOf(decode(encode(original))))
    }

    @Test
    fun schedules_wrapRuleSurvivesRoundTrip() {
        val base = fullSnapshot()
        val wrap = FocusSession(
            id = "bedtime", name = "Bedtime", days = listOf(0),
            startMinute = 22 * 60, endMinute = 7 * 60,
            mode = FocusMode.BOTH, appPackages = listOf("com.tiktok"), enabled = true,
        )
        val original = base.copy(schedules = base.schedules?.copy(schedules = listOf(wrap)))
        assertEquals(original, successOf(decode(encode(original))))
    }

    @Test
    fun schedules_equalStartEndDroppedOnDecode() {
        val base = fullSnapshot()
        val bad = FocusSession(
            id = "bad", name = "Bad", days = listOf(0),
            startMinute = 22 * 60, endMinute = 22 * 60,
            mode = FocusMode.BOTH, appPackages = listOf("com.tiktok"), enabled = true,
        )
        val snapshot = base.copy(schedules = base.schedules?.copy(schedules = listOf(bad)))
        val decoded = successOf(decode(encode(snapshot)))
        assertTrue(decoded.schedules?.schedules.isNullOrEmpty())
    }
}
