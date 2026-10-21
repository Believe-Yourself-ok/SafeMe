package com.safeme.app.ui.screens.focus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusEditEnabledTest {

    @Test
    fun newFocusSessionDefaultsEnabled() {
        assertTrue(resolveEditedEnabled(editId = null, storedEnabled = null))
    }

    @Test
    fun newFocusSessionIgnoresAnyStoredValue() {
        assertTrue(resolveEditedEnabled(editId = null, storedEnabled = false))
    }

    @Test
    fun editPreservesDisabled() {
        assertFalse(resolveEditedEnabled(editId = "exams", storedEnabled = false))
    }

    @Test
    fun editPreservesEnabled() {
        assertTrue(resolveEditedEnabled(editId = "exams", storedEnabled = true))
    }

    @Test
    fun editWithMissingFocusSessionFailsSafeToEnabled() {
        // Schedule deleted elsewhere while editing: updateFocusSession() no-ops,
        // and the flag must not strand a phantom disabled state.
        assertTrue(resolveEditedEnabled(editId = "exams", storedEnabled = null))
    }
}
