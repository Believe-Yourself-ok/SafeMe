package com.safeme.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for immediate schedule activation checks. */
class SafeMeAccessibilityServiceFocusTest {

    @Test
    fun forcedFocusRecheck_bypassesThrottle() {
        val now = 10_000L

        assertFalse(
            shouldThrottleFocusRecheck(
                lastRecheckMs = now - 1_000L,
                nowMs = now,
                force = true,
            ),
        )
    }

    @Test
    fun normalFocusRecheck_keepsThrottle() {
        val now = 10_000L

        assertTrue(
            shouldThrottleFocusRecheck(
                lastRecheckMs = now - 1_000L,
                nowMs = now,
                force = false,
            ),
        )
    }
}
