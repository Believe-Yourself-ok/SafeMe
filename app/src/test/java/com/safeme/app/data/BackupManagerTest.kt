package com.safeme.app.data

import com.safeme.app.vpn.DnsPreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the atomic restore pipeline ([executeRestore]) using
 * in-memory fake stores — no Android needed. Covers successful restores,
 * validation failures (nothing written), interrupted writes (rollback) and
 * rollback failures.
 */
class BackupManagerTest {

    /** In-memory store; optionally fails writes to simulate I/O errors. */
    private class FakeStore(
        initial: Any?,
        private val failEveryWrite: Boolean = false,
        private val failOnWriteNumber: Int = -1,
    ) : BackupStateStore {
        var value: Any? = initial
            private set
        var writes = 0
            private set
        var reads = 0
            private set

        override suspend fun read(): Any? {
            reads++
            return value
        }

        override suspend fun write(value: Any?) {
            writes++
            if (failEveryWrite || writes == failOnWriteNumber) {
                throw IllegalStateException("simulated write failure")
            }
            this.value = value
        }
    }

    private fun sampleSnapshot() = BackupSnapshot(
        blocking = BlockingPrefsState(
            blocklistKeywords = listOf(BlockedKeyword("instagram", BlockedCategory.SOCIAL_MEDIA)),
            whitelistKeywords = listOf("work"),
            blockingEnabled = false,
        ),
        schedules = FocusPrefsState(
            schedules = listOf(
                FocusSession("s1", "Study", listOf(1), 60, 120, FocusMode.LAUNCH, emptyList(), enabled = false),
            ),
        ),
        vpn = DnsVpnSettings(enabled = true, preset = DnsPreset.CLOUDFLARE_FAMILY),
        quickActions = listOf(QuickActionType.HISTORY),
        appLock = AppLockPrefsState(
            lockType = LockType.PASSWORD, storedHash = "salt:hash", credentialLength = 5, biometricEnabled = true,
        ),
        preventUninstall = PreventUninstallPrefsState(preventUninstallEnabled = true),
        a11yProtection = A11yProtectionPrefsState(protectionEnabled = true, protectedComponents = setOf("pkg/svc")),
        contentEngine = ContentEnginePrefsState(blockImageVideoSearch = true),
        blockScreen = BlockScreenPrefsState(dwell = 8, message = "Focus", whyOn = false),
        socialBlocking = SocialBlockingState(
            enabled = true,
            wholeBlocked = setOf("com.zhiliaoapp.musically"),
            youtube = true, facebook = false, snapchat = true,
        ),
    )

    /** All stores seeded with "old" values that differ from the backup. */
    private fun seededStores(
        failEveryWriteOn: BackupSection? = null,
        failOnWriteNumber: BackupSection? = null,
    ): Map<BackupSection, FakeStore> = buildMap {
        put(BackupSection.BLOCKING, FakeStore(BlockingPrefsState(blockingEnabled = true)))
        put(BackupSection.FOCUS_SESSIONS, FakeStore(FocusPrefsState(schedules = emptyList())))
        put(BackupSection.VPN, FakeStore(DnsVpnSettings(enabled = false)))
        put(BackupSection.QUICK_ACTIONS, FakeStore(listOf(QuickActionType.HISTORY)))
        put(BackupSection.APP_LOCK, FakeStore(AppLockPrefsState(lockType = LockType.OFF)))
        put(BackupSection.PREVENT_UNINSTALL, FakeStore(PreventUninstallPrefsState(preventUninstallEnabled = false)))
        put(BackupSection.A11Y_PROTECTION, FakeStore(A11yProtectionPrefsState(protectionEnabled = false)))
        put(BackupSection.CONTENT_ENGINE, FakeStore(ContentEnginePrefsState(blockImageVideoSearch = false)))
        put(BackupSection.BLOCK_SCREEN, FakeStore(BlockScreenPrefsState(dwell = 5)))
        put(BackupSection.SOCIAL_BLOCKING, FakeStore(SocialBlockingState(enabled = false, wholeBlocked = emptySet(), youtube = false, facebook = false, snapchat = false)))
    }.mapValues { (section, store) ->
        when (section) {
            failEveryWriteOn -> FakeStore(store.value, failEveryWrite = true)
            failOnWriteNumber -> FakeStore(store.value, failOnWriteNumber = 1)
            else -> store
        }
    }

    private fun encode(snapshot: BackupSnapshot) =
        BackupCodec.toJsonc(snapshot, appVersion = "0.1.0", createdAt = "2026-08-11T07:45:00Z")

    @Test
    fun successfulRestoreAppliesEveryPresentSection() = runBlocking {
        val stores = seededStores()
        val result = executeRestore(encode(sampleSnapshot()), stores)

        assertTrue("expected success, got $result", result is RestoreResult.Success)
        assertEquals(BackupSection.entries.toList(), (result as RestoreResult.Success).restoredSections)

        val restored = sampleSnapshot()
        assertEquals(restored.blocking, stores[BackupSection.BLOCKING]?.value)
        assertEquals(restored.schedules, stores[BackupSection.FOCUS_SESSIONS]?.value)
        assertEquals(restored.vpn, stores[BackupSection.VPN]?.value)
        assertEquals(restored.quickActions, stores[BackupSection.QUICK_ACTIONS]?.value)
        assertEquals(restored.appLock, stores[BackupSection.APP_LOCK]?.value)
        assertEquals(restored.preventUninstall, stores[BackupSection.PREVENT_UNINSTALL]?.value)
        assertEquals(restored.a11yProtection, stores[BackupSection.A11Y_PROTECTION]?.value)
        assertEquals(restored.contentEngine, stores[BackupSection.CONTENT_ENGINE]?.value)
        assertEquals(restored.blockScreen, stores[BackupSection.BLOCK_SCREEN]?.value)
        assertEquals(restored.socialBlocking, stores[BackupSection.SOCIAL_BLOCKING]?.value)
    }

    @Test
    fun invalidBackupWritesNothing() = runBlocking {
        val stores = seededStores()
        val result = executeRestore("this is not jsonc", stores)

        assertTrue(result is RestoreResult.Failure)
        assertEquals(BackupError.NOT_JSON, (result as RestoreResult.Failure).error)
        stores.values.forEach { assertEquals("no store may be written", 0, it.writes) }
    }

    @Test
    fun incompatibleVersionWritesNothing() = runBlocking {
        val stores = seededStores()
        val raw = """{"format": "safeme-backup", "schemaVersion": 99, "blocking": {"blockingEnabled": false}}"""
        val result = executeRestore(raw, stores)

        assertTrue(result is RestoreResult.Failure)
        assertEquals(BackupError.UNSUPPORTED_VERSION, (result as RestoreResult.Failure).error)
        stores.values.forEach { assertEquals(0, it.writes) }
    }

    @Test
    fun midWriteFailureRollsBackEveryStore() = runBlocking {
        // VPN is the 3rd section in canonical order; its first write fails.
        val stores = seededStores(failOnWriteNumber = BackupSection.VPN)
        val before = stores.mapValues { it.value.value }
        val result = executeRestore(encode(sampleSnapshot()), stores)

        assertTrue("expected failure, got $result", result is RestoreResult.Failure)
        assertEquals(BackupError.WRITE_FAILED, (result as RestoreResult.Failure).error)
        // Every store is back to its original value — no partial state.
        before.forEach { (section, old) -> assertEquals(old, stores[section]?.value) }
        // The first two sections were written then rolled back.
        assertTrue((stores[BackupSection.BLOCKING]?.writes ?: 0) >= 2)
        assertTrue((stores[BackupSection.FOCUS_SESSIONS]?.writes ?: 0) >= 2)
    }

    @Test
    fun failingRollbackReportsRollbackFailed() = runBlocking {
        // VPN always fails — both the apply write and the rollback write.
        val stores = seededStores(failEveryWriteOn = BackupSection.VPN)
        val result = executeRestore(encode(sampleSnapshot()), stores)

        assertTrue(result is RestoreResult.Failure)
        assertEquals(BackupError.ROLLBACK_FAILED, (result as RestoreResult.Failure).error)
    }

    @Test
    fun absentSectionsAreNeverTouched() = runBlocking {
        val stores = seededStores()
        val partial = BackupSnapshot(blocking = BlockingPrefsState(blockingEnabled = false))
        val result = executeRestore(encode(partial), stores)

        assertTrue(result is RestoreResult.Success)
        assertEquals(listOf(BackupSection.BLOCKING), (result as RestoreResult.Success).restoredSections)
        assertEquals(1, stores[BackupSection.BLOCKING]?.writes)
        BackupSection.entries.filter { it != BackupSection.BLOCKING }.forEach { section ->
            assertEquals("$section must not be written", 0, stores[section]?.writes)
            assertEquals("$section must keep its value", seededStores()[section]?.value, stores[section]?.value)
        }
    }
}
