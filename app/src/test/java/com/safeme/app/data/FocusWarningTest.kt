package com.safeme.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the \"Accessibility Service required\" banner predicate. */
class FocusWarningTest {

    private fun block(
        mode: FocusMode,
        enabled: Boolean = true,
    ) = FocusSession(
        id = "id",
        name = "n",
        days = listOf(0),
        startMinute = 60,
        endMinute = 120,
        mode = mode,
        appPackages = emptyList(),
        enabled = enabled,
    )

    @Test
    fun requiresAccessibility_onlyLaunchAndBoth() {
        assertTrue(requiresAccessibility(FocusMode.LAUNCH))
        assertTrue(requiresAccessibility(FocusMode.BOTH))
        assertFalse(requiresAccessibility(FocusMode.INTERNET))
    }

    @Test
    fun banner_hiddenWhenNoEnabledFocusSessionNeedsA11y() {
        assertFalse(shouldShowA11yWarning(emptyList(), a11yEnabled = false, dismissed = false))
        assertFalse(
            shouldShowA11yWarning(
                listOf(block(FocusMode.INTERNET)),
                a11yEnabled = false,
                dismissed = false,
            )
        )
    }

    @Test
    fun banner_hiddenWhenOnlyPausedFocusSessionNeedsA11y() {
        assertFalse(
            shouldShowA11yWarning(
                listOf(block(FocusMode.LAUNCH, enabled = false)),
                a11yEnabled = false,
                dismissed = false,
            )
        )
    }

    @Test
    fun banner_visibleWhenEnabledLaunchOrBothFocusSessionAndA11yOff() {
        assertTrue(
            shouldShowA11yWarning(
                listOf(block(FocusMode.LAUNCH)),
                a11yEnabled = false,
                dismissed = false,
            )
        )
        assertTrue(
            shouldShowA11yWarning(
                listOf(block(FocusMode.BOTH)),
                a11yEnabled = false,
                dismissed = false,
            )
        )
        // One a11y-requiring schedule among internet-only ones is enough.
        assertTrue(
            shouldShowA11yWarning(
                listOf(block(FocusMode.INTERNET), block(FocusMode.LAUNCH)),
                a11yEnabled = false,
                dismissed = false,
            )
        )
    }

    @Test
    fun banner_hiddenWhenA11yEnabled() {
        assertFalse(
            shouldShowA11yWarning(
                listOf(block(FocusMode.BOTH)),
                a11yEnabled = true,
                dismissed = false,
            )
        )
    }

    @Test
    fun banner_hiddenWhenDismissed() {
        assertFalse(
            shouldShowA11yWarning(
                listOf(block(FocusMode.BOTH)),
                a11yEnabled = false,
                dismissed = true,
            )
        )
    }

    // -------------------------------------------- exact-alarm banner predicate

    @Test
    fun exactBanner_hiddenBelowS() {
        assertFalse(
            shouldShowExactAlarmWarning(
                listOf(block(FocusMode.BOTH)),
                granted = false,
                dismissed = false,
                sdkInt = 30,
            )
        )
    }

    @Test
    fun exactBanner_hiddenWhenGranted() {
        assertFalse(
            shouldShowExactAlarmWarning(
                listOf(block(FocusMode.BOTH)),
                granted = true,
                dismissed = false,
                sdkInt = 34,
            )
        )
    }

    @Test
    fun exactBanner_hiddenWhenDismissed() {
        assertFalse(
            shouldShowExactAlarmWarning(
                listOf(block(FocusMode.BOTH)),
                granted = false,
                dismissed = true,
                sdkInt = 34,
            )
        )
    }

    @Test
    fun exactBanner_hiddenWithNoEnabledFocusSessions() {
        assertFalse(
            shouldShowExactAlarmWarning(
                emptyList(),
                granted = false,
                dismissed = false,
                sdkInt = 34,
            )
        )
        assertFalse(
            shouldShowExactAlarmWarning(
                listOf(block(FocusMode.BOTH, enabled = false)),
                granted = false,
                dismissed = false,
                sdkInt = 34,
            )
        )
    }

    @Test
    fun exactBanner_visibleWhenDeniedWithEnabledFocusSession() {
        assertTrue(
            shouldShowExactAlarmWarning(
                listOf(block(FocusMode.BOTH)),
                granted = false,
                dismissed = false,
                sdkInt = 31,
            )
        )
        // One enabled schedule among paused ones is enough.
        assertTrue(
            shouldShowExactAlarmWarning(
                listOf(block(FocusMode.INTERNET, enabled = false), block(FocusMode.LAUNCH)),
                granted = false,
                dismissed = false,
                sdkInt = 34,
            )
        )
    }
}
