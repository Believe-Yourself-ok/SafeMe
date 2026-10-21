package com.safeme.app.ui.screens.applock

import com.safeme.app.data.AppLockPrefsState
import com.safeme.app.data.AutoLockDelay
import com.safeme.app.data.LockType
import com.safeme.app.protect.AppLockStateHolder
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * COD-79: the entry gate must follow card 1 only.
 *
 * [AppLockGateController] has no Android dependencies — it reads
 * [AppLockStateHolder] and `System.currentTimeMillis()` — so the whole decision
 * surface is testable on the JVM. Only the deterministic branches are asserted;
 * the delay-based auto-lock path depends on wall-clock timing and is covered by
 * the manual device matrix instead.
 */
class AppLockGateControllerTest {

    @Before
    fun resetBefore() = reset()

    @After
    fun resetAfter() = reset()

    private fun reset() {
        AppLockStateHolder.update(AppLockPrefsState())
        AppLockGateController.onStateLoaded()
    }

    private val armed = AppLockPrefsState(
        lockType = LockType.PIN,
        storedHash = "salt:hash",
        credentialLength = 4,
        autoLock = AutoLockDelay.IMMEDIATELY,
    )

    /**
     * Reproduce a cold start: the holder is fed by the SafeMeApp collector, and
     * `onStateLoaded` clears `sessionUnlocked` whenever the gate is unarmed, so
     * passing through the unarmed state first is what a fresh process looks like.
     */
    private fun coldStart(state: AppLockPrefsState) {
        AppLockStateHolder.update(state.copy(lockType = LockType.OFF, storedHash = ""))
        AppLockGateController.onStateLoaded()
        AppLockStateHolder.update(state)
        AppLockGateController.onStateLoaded()
    }

    private val locked: Boolean get() = AppLockGateController.locked.value

    // ------------------------------------------------- existing behaviour

    /** Must not regress: a configured lock still gates a cold start. */
    @Test
    fun coldStartWithLockConfigured_staysLocked() {
        coldStart(armed)
        assertTrue(locked)
    }

    /** Must not regress: unlocking holds for the rest of the foreground session. */
    @Test
    fun unlockHoldsTheSessionOpen() {
        coldStart(armed)
        assertTrue(locked)
        AppLockGateController.unlock()
        assertFalse(locked)
        AppLockGateController.onAppForeground()
        assertFalse("an unlocked session must not re-lock on foreground", locked)
    }

    /** Must not regress: "Lock now" is a no-op with no lock configured. */
    @Test
    fun lockNowIsANoOpWithoutACredential() {
        coldStart(AppLockPrefsState())
        AppLockGateController.lockNow()
        assertFalse(locked)
    }

    // -------------------------------------------------------- COD-79 card 1

    /** The headline requirement: the app opens without the app-lock password. */
    @Test
    fun coldStartWithEntryProtectionOff_opensWithoutTheLock() {
        coldStart(armed.copy(entryLockEnabled = false))
        assertFalse("card 1 off must not gate entry", locked)
        assertTrue("the credential must still be stored", AppLockStateHolder.hasCredential)
    }

    @Test
    fun foregroundingWithEntryProtectionOff_neverLocks() {
        coldStart(armed.copy(entryLockEnabled = false))
        AppLockGateController.onAppBackground()
        AppLockGateController.onAppForeground()
        assertFalse(locked)
    }

    @Test
    fun lockNowIsANoOpWithEntryProtectionOff() {
        coldStart(armed.copy(entryLockEnabled = false))
        AppLockGateController.lockNow()
        assertFalse("manual lock must not re-arm a gate the user turned off", locked)
    }

    /** Turning card 1 off while locked must drop the gate immediately. */
    @Test
    fun turningEntryProtectionOffWhileLocked_dropsTheGate() {
        coldStart(armed)
        assertTrue(locked)

        AppLockStateHolder.update(AppLockStateHolder.state.copy(entryLockEnabled = false))
        AppLockGateController.onStateLoaded()

        assertFalse("the user must not be stranded behind a gate they just disarmed", locked)
        assertTrue("and card 2 must still have a credential to verify", AppLockStateHolder.hasCredential)
    }

    /** Turning card 1 back on re-arms, exactly like setting a lock does today. */
    @Test
    fun turningEntryProtectionBackOn_rearmsTheGate() {
        coldStart(armed.copy(entryLockEnabled = false))
        assertFalse(locked)

        AppLockStateHolder.update(AppLockStateHolder.state.copy(entryLockEnabled = true))
        AppLockGateController.onStateLoaded()

        assertTrue(locked)
    }

    /** Auto-lock on background must respect card 1 too, not just the cold start. */
    @Test
    fun immediateAutoLockOnlyFiresWhenCard1IsArmed() {
        coldStart(armed)
        AppLockGateController.unlock()
        AppLockGateController.onAppBackground()
        assertTrue("armed + Immediately must lock on background", locked)

        coldStart(armed.copy(entryLockEnabled = false))
        AppLockGateController.onAppBackground()
        assertFalse("disarmed must not lock on background", locked)
    }

    // ------------------------------------------------- card 2 is invisible

    /**
     * The issue requires the two settings to be independent in both directions:
     * card 2 must never change whether the app gates entry.
     */
    @Test
    fun card2NeverAffectsTheEntryGate() {
        for (card2 in listOf(true, false)) {
            coldStart(armed.copy(disableProtectionEnabled = card2))
            assertTrue("card 2 = $card2 must leave the entry gate armed", locked)

            AppLockStateHolder.update(AppLockStateHolder.state.copy(entryLockEnabled = false))
            AppLockGateController.onStateLoaded()
            assertFalse("card 2 = $card2 must not hold the gate up", locked)
        }
    }

    // ------------------------------------------------------- lockout guard

    /**
     * No credential ⇒ the gate can never arm. This is what keeps card 1 from
     * putting up the overlay's `LockType.OFF` branch, which renders no input.
     */
    @Test
    fun gateNeverArmsWithoutACredential() {
        coldStart(AppLockPrefsState(lockType = LockType.OFF, entryLockEnabled = true))
        assertFalse(locked)
        AppLockGateController.lockNow()
        assertFalse(locked)

        coldStart(armed.copy(storedHash = ""))
        assertFalse(locked)
    }
}
