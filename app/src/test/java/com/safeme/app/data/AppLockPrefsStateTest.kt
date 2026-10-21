package com.safeme.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COD-79: the derived protection properties on [AppLockPrefsState].
 *
 * These three properties are the whole separation between "a credential
 * exists", "the app must be unlocked to enter" and "features are guarded when
 * turned off", so every combination is pinned here.
 */
class AppLockPrefsStateTest {

    private fun state(
        lockType: LockType = LockType.PIN,
        storedHash: String = "00112233445566778899aabbccddeeff:deadbeef",
        entryLockEnabled: Boolean = true,
        disableProtectionEnabled: Boolean = true,
    ) = AppLockPrefsState(
        lockType = lockType,
        storedHash = storedHash,
        credentialLength = 4,
        entryLockEnabled = entryLockEnabled,
        disableProtectionEnabled = disableProtectionEnabled,
    )

    // ------------------------------------------------------- hasCredential

    @Test
    fun hasCredential_trueOnlyWhenTypeAndHashAreBothPresent() {
        assertTrue(state().hasCredential)
        assertFalse("type OFF ⇒ no credential", state(lockType = LockType.OFF).hasCredential)
        assertFalse("blank hash ⇒ no credential", state(storedHash = "").hasCredential)
        assertFalse("whitespace hash ⇒ no credential", state(storedHash = "   ").hasCredential)
        assertFalse(
            "OFF and blank ⇒ no credential",
            state(lockType = LockType.OFF, storedHash = "").hasCredential,
        )
    }

    @Test
    fun hasCredential_trueForEachLockMethod() {
        for (type in listOf(LockType.PIN, LockType.PASSWORD, LockType.PATTERN)) {
            assertTrue("$type with a stored hash is a credential", state(lockType = type).hasCredential)
        }
    }

    // ------------------------------------------------------- entryLockActive

    /**
     * The lockout guard: a gate armed without a credential would show the
     * unlock overlay's `LockType.OFF` branch, which renders no input at all.
     */
    @Test
    fun entryLockActive_falseWithoutACredentialEvenWhenEnabled() {
        assertFalse(
            state(lockType = LockType.OFF, entryLockEnabled = true).entryLockActive,
        )
        assertFalse(state(storedHash = "", entryLockEnabled = true).entryLockActive)
    }

    @Test
    fun entryLockActive_trueOnlyWhenBothEnabledAndCredentialPresent() {
        assertTrue(state(entryLockEnabled = true).entryLockActive)
        assertFalse(state(entryLockEnabled = false).entryLockActive)
    }

    // ----------------------------------------------- disableProtectionActive

    @Test
    fun disableProtectionActive_falseWithoutACredential() {
        assertFalse(
            state(lockType = LockType.OFF, disableProtectionEnabled = true)
                .disableProtectionActive,
        )
        assertFalse(state(storedHash = "", disableProtectionEnabled = true).disableProtectionActive)
    }

    @Test
    fun disableProtectionActive_trueOnlyWhenBothEnabledAndCredentialPresent() {
        assertTrue(state(disableProtectionEnabled = true).disableProtectionActive)
        assertFalse(state(disableProtectionEnabled = false).disableProtectionActive)
    }

    // --------------------------------------------------------- independence

    /** The core COD-79 requirement: neither flag may imply the other. */
    @Test
    fun theTwoProtectionsAreIndependent() {
        for (entry in listOf(true, false)) {
            for (disable in listOf(true, false)) {
                val s = state(entryLockEnabled = entry, disableProtectionEnabled = disable)
                assertEquals("entry flag must not be derived", entry, s.entryLockEnabled)
                assertEquals("disable flag must not be derived", disable, s.disableProtectionEnabled)
                assertEquals(entry, s.entryLockActive)
                assertEquals(disable, s.disableProtectionActive)
            }
        }
    }

    /** Card 1 off must leave the credential in place for card 2 to verify against. */
    @Test
    fun turningEntryLockOffRetainsTheCredential() {
        val credentialStored = state(entryLockEnabled = false)
        assertTrue(credentialStored.hasCredential)
        assertEquals("00112233445566778899aabbccddeeff:deadbeef", credentialStored.storedHash)
        assertEquals(4, credentialStored.credentialLength)
        assertTrue(credentialStored.disableProtectionActive)
    }

    // ------------------------------------------------------ upgrade default

    /**
     * A state built the way a pre-COD-79 build would have built it (named args,
     * no new fields) must behave exactly as that build did: entry gate armed.
     */
    @Test
    fun upgradeDefaultKeepsAnExistingLockGatingEntry() {
        val legacy = AppLockPrefsState(
            lockType = LockType.PASSWORD,
            storedHash = "salt:hash",
            credentialLength = 5,
            biometricEnabled = true,
        )
        assertTrue(legacy.entryLockEnabled)
        assertTrue(legacy.disableProtectionEnabled)
        assertTrue(legacy.entryLockActive)
        assertTrue(legacy.disableProtectionActive)
    }

    /** No credential at all (the fresh-install state) arms neither protection. */
    @Test
    fun freshInstallArmsNeitherProtection() {
        val fresh = AppLockPrefsState()
        assertEquals(LockType.OFF, fresh.lockType)
        assertFalse(fresh.hasCredential)
        assertFalse(fresh.entryLockActive)
        assertFalse(fresh.disableProtectionActive)
    }
}
