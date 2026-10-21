package com.safeme.app.ui.screens.focus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B10: window validation — only coinciding start/end is rejected. */
class FocusEditValidationTest {

    @Test
    fun window_equalIsInvalid() {
        assertFalse(isValidFocusWindow(22 * 60, 22 * 60))
        assertFalse(isValidFocusWindow(0, 0))
    }

    @Test
    fun window_normalIsValid() {
        assertTrue(isValidFocusWindow(21 * 60, 23 * 60))
    }

    @Test
    fun window_wrapIsValid() {
        assertTrue(isValidFocusWindow(22 * 60, 7 * 60))
    }
}
