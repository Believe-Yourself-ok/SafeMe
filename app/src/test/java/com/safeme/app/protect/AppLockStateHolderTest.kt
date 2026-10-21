package com.safeme.app.protect

import com.safeme.app.data.AppLockPrefsState
import com.safeme.app.data.AutoLockDelay
import com.safeme.app.data.LockType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * COD-79 regression cover for [AppLockStateHolder].
 *
 * The holder is a process-wide singleton, so every test starts from and returns
 * to the fresh-install state.
 */
class AppLockStateHolderTest {

    @Before
    fun resetBefore() = reset()

    @After
    fun resetAfter() = reset()

    private fun reset() = AppLockStateHolder.update(AppLockPrefsState())

    private fun stored(
        entryLockEnabled: Boolean = true,
        disableProtectionEnabled: Boolean = true,
    ) = AppLockPrefsState(
        lockType = LockType.PIN,
        storedHash = "salt:hash",
        credentialLength = 4,
        autoLock = AutoLockDelay.AFTER_1M,
        entryLockEnabled = entryLockEnabled,
        disableProtectionEnabled = disableProtectionEnabled,
    )

    // --------------------------------------------------- the reported defect

    /**
     * Guard for the bug found while re-verifying the plan.
     *
     * `setLock` / `disableLock` used to rebuild the holder from a hand-written
     * `AppLockPrefsState(...)` listing each field, so any field added later
     * silently fell back to its default. With COD-79's flags defaulting to true,
     * changing your lock would have snapped a protection you deliberately turned
     * off back ON. The rebuild now goes through `snapshot().copy(...)`.
     */
    @Test
    fun credentialRewriteKeepsBothProtectionFlagsOff() {
        AppLockStateHolder.update(stored(entryLockEnabled = false, disableProtectionEnabled = false))

        // What AppLockManager.setLock does after hashing the new credential.
        AppLockStateHolder.update(
            AppLockStateHolder.snapshot().copy(
                lockType = LockType.PASSWORD,
                storedHash = "salt2:hash2",
                credentialLength = 9,
            ),
        )

        assertFalse("card 1 must stay off", AppLockStateHolder.state.entryLockEnabled)
        assertFalse("card 2 must stay off", AppLockStateHolder.state.disableProtectionEnabled)
        assertFalse("and the entry gate must stay down", AppLockStateHolder.enabled)
        // The credential change itself did land.
        assertEquals(LockType.PASSWORD, AppLockStateHolder.lockType)
        assertEquals(9, AppLockStateHolder.credentialLength)
        assertTrue(AppLockStateHolder.hasCredential)
    }

    /** Same guard for the destroy path: auto-lock and both flags survive. */
    @Test
    fun disableLockCarriesOverAutoLockAndBothFlags() {
        AppLockStateHolder.update(stored(entryLockEnabled = false, disableProtectionEnabled = false))

        // What AppLockManager.disableLock does after wiping the credential.
        AppLockStateHolder.update(
            AppLockStateHolder.snapshot().copy(
                lockType = LockType.OFF,
                storedHash = "",
                credentialLength = 0,
                biometricEnabled = false,
                forgotPasswordDisabled = false,
            ),
        )

        assertEquals(AutoLockDelay.AFTER_1M, AppLockStateHolder.autoLock)
        assertFalse(AppLockStateHolder.state.entryLockEnabled)
        assertFalse(AppLockStateHolder.state.disableProtectionEnabled)
        // No credential ⇒ neither protection can be active, whatever the flags say.
        assertFalse(AppLockStateHolder.hasCredential)
        assertFalse(AppLockStateHolder.enabled)
    }

    // ------------------------------------------------------------ card 1

    @Test
    fun enabledFollowsCard1WhenACredentialExists() {
        AppLockStateHolder.update(stored(entryLockEnabled = true))
        assertTrue(AppLockStateHolder.enabled)

        AppLockStateHolder.update(AppLockStateHolder.state.copy(entryLockEnabled = false))
        assertFalse("card 1 off ⇒ app opens without the lock", AppLockStateHolder.enabled)
        assertTrue("…but the credential is still there for card 2", AppLockStateHolder.hasCredential)
    }

    @Test
    fun enabledIsNeverTrueWithoutACredential() {
        // The unlock overlay renders no input for LockType.OFF, so an armed gate
        // without a credential would be a dead end with no way past it.
        AppLockStateHolder.update(AppLockPrefsState(lockType = LockType.OFF, entryLockEnabled = true))
        assertFalse(AppLockStateHolder.enabled)

        AppLockStateHolder.update(stored(entryLockEnabled = true).copy(storedHash = ""))
        assertFalse(AppLockStateHolder.enabled)
    }

    // -------------------------------------------------- card 2 independence

    /** Card 2 must be invisible to the entry gate. */
    @Test
    fun disableProtectionFlagNeverAffectsTheEntryGate() {
        for (card2 in listOf(true, false)) {
            AppLockStateHolder.update(stored(entryLockEnabled = true, disableProtectionEnabled = card2))
            assertTrue("card 2 = $card2 must not unlock the app", AppLockStateHolder.enabled)

            AppLockStateHolder.update(AppLockStateHolder.state.copy(entryLockEnabled = false))
            assertFalse("card 2 = $card2 must not keep the app locked", AppLockStateHolder.enabled)
        }
    }

    // ------------------------------------------------------------- lenses

    /** The lenses still write through, as AppLockViewModel relies on. */
    @Test
    fun lensSettersWriteThroughWithoutDisturbingOtherFields() {
        AppLockStateHolder.update(stored(entryLockEnabled = false, disableProtectionEnabled = false))

        AppLockStateHolder.autoLock = AutoLockDelay.AFTER_5M
        AppLockStateHolder.biometricEnabled = true
        AppLockStateHolder.forgotPasswordDisabled = true
        AppLockStateHolder.credentialLength = 6
        AppLockStateHolder.lockType = LockType.PATTERN

        assertEquals(AutoLockDelay.AFTER_5M, AppLockStateHolder.autoLock)
        assertTrue(AppLockStateHolder.biometricEnabled)
        assertTrue(AppLockStateHolder.forgotPasswordDisabled)
        assertEquals(6, AppLockStateHolder.credentialLength)
        assertEquals(LockType.PATTERN, AppLockStateHolder.lockType)
        assertFalse("lens writes must not re-arm card 1", AppLockStateHolder.state.entryLockEnabled)
        assertFalse("lens writes must not re-arm card 2", AppLockStateHolder.state.disableProtectionEnabled)
    }

    /** `snapshot()` is the same object the holder serves, not a partial copy. */
    @Test
    fun snapshotMatchesState() {
        val s = stored(entryLockEnabled = false)
        AppLockStateHolder.update(s)
        assertEquals(s, AppLockStateHolder.snapshot())
        assertEquals(s, AppLockStateHolder.state)
    }
}
