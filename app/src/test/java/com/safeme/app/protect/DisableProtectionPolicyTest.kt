package com.safeme.app.protect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COD-79: the whole truth table for the two independent protections.
 *
 * The issue asks to "test all combinations of the two settings to verify that
 * the behavior is consistent and no security regressions are introduced", so
 * every combination is asserted exhaustively rather than by example.
 */
class DisableProtectionPolicyTest {

    // ---------------------------------------------------------- card 1 gate

    @Test
    fun entryLockActive_neverArmedWithoutACredential() {
        // The unlock overlay renders NO input for LockType.OFF, so an armed
        // gate without a credential is an unrecoverable full-screen dead end.
        assertFalse(DisableProtectionPolicy.entryLockActive(entryLockEnabled = true, hasCredential = false))
        assertFalse(DisableProtectionPolicy.entryLockActive(entryLockEnabled = false, hasCredential = false))
    }

    @Test
    fun entryLockActive_followsTheFlagWhenACredentialExists() {
        assertTrue(DisableProtectionPolicy.entryLockActive(entryLockEnabled = true, hasCredential = true))
        assertFalse(DisableProtectionPolicy.entryLockActive(entryLockEnabled = false, hasCredential = true))
    }

    // ---------------------------------------------------------- card 2 gate

    @Test
    fun passwordRequired_isFalseForEveryActionWhenCard2IsOff() {
        for (action in ProtectedAction.entries) {
            assertFalse(
                "card 2 off must never prompt ($action)",
                DisableProtectionPolicy.passwordRequired(
                    disableProtectionEnabled = false,
                    hasCredential = true,
                    action = action,
                ),
            )
        }
    }

    @Test
    fun passwordRequired_isFalseForEveryActionWithoutACredential() {
        // Fail-open: demanding a password the user never set would trap them
        // inside their own settings with no way to satisfy the prompt.
        for (action in ProtectedAction.entries) {
            assertFalse(
                "no credential must never prompt ($action)",
                DisableProtectionPolicy.passwordRequired(
                    disableProtectionEnabled = true,
                    hasCredential = false,
                    action = action,
                ),
            )
        }
    }

    @Test
    fun passwordRequired_isTrueForEveryProtectedActionWhenArmed() {
        for (action in ProtectedAction.entries) {
            assertEquals(
                "armed card 2 must gate exactly the protected actions ($action)",
                action.protected,
                DisableProtectionPolicy.passwordRequired(
                    disableProtectionEnabled = true,
                    hasCredential = true,
                    action = action,
                ),
            )
        }
    }

    // --------------------------------------------------------- independence

    /**
     * The core COD-79 requirement: card 1 cannot see card 2 and vice versa.
     * Asserted as a full 4-way matrix over a representative action.
     */
    @Test
    fun theTwoProtectionsAreIndependent() {
        val action = ProtectedAction.MASTER_BLOCKING
        for (entry in listOf(true, false)) {
            for (disable in listOf(true, false)) {
                val gate = DisableProtectionPolicy.entryLockActive(entry, hasCredential = true)
                val prompt = DisableProtectionPolicy.passwordRequired(disable, hasCredential = true, action = action)
                assertEquals("entry gate must track only card 1", entry, gate)
                assertEquals("disable prompt must track only card 2", disable, prompt)
            }
        }
    }

    /** "Open the app without the app lock" — the headline scenario. */
    @Test
    fun entryOffWithCard2On_opensFreelyButStillGuardsDisables() {
        assertFalse(DisableProtectionPolicy.entryLockActive(entryLockEnabled = false, hasCredential = true))
        assertTrue(
            DisableProtectionPolicy.passwordRequired(
                disableProtectionEnabled = true,
                hasCredential = true,
                action = ProtectedAction.ANTI_TAMPER,
            ),
        )
    }

    // ------------------------------------------------------ enum invariants

    /**
     * The enum is the implementation checklist: one value per gate site.
     * Pinning the size makes "added a protected surface but forgot to gate it"
     * (or a value deleted by accident) a test failure instead of a silent
     * security hole. Bump this number only in the same commit that wires the
     * new site.
     */
    @Test
    fun protectedAction_hasExactlyTheWiredSurfaces() {
        assertEquals(19, ProtectedAction.entries.size)
    }

    @Test
    fun protectedAction_idsAreNonBlankAndUnique() {
        val ids = ProtectedAction.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.isNotBlank() })
    }

    /**
     * Every current surface is genuinely protected. `protected = false` exists
     * so a non-gated surface can be listed without a second enum — if one is
     * ever added, this test must be updated deliberately, not by accident.
     */
    @Test
    fun protectedAction_allEntriesAreProtectedToday() {
        assertTrue(ProtectedAction.entries.all { it.protected })
    }

    /**
     * There must be no value for card 1 itself: gating the entry toggle would
     * mean the user has to be past a gate in order to configure the gate.
     */
    @Test
    fun protectedAction_hasNoEntryLockToggleValue() {
        assertTrue(ProtectedAction.entries.none { it.id.contains("entry") })
    }
}
