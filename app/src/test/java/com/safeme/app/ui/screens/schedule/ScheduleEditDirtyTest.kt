package com.safeme.app.ui.screens.schedule

import com.safeme.app.data.ScheduleMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** COD-81: net-diff dirty detection for the schedule editor's exit guard. */
class ScheduleEditDirtyTest {

    private val baseline = ScheduleEditUiState(
        loading = false,
        editId = "exams",
        name = "Exams",
        days = setOf(0, 1, 2),
        startMinute = 21 * 60,
        endMinute = 23 * 60,
        mode = ScheduleMode.BOTH,
        enabled = true,
        selectedApps = setOf("com.example.app"),
    )

    @Test
    fun dirty_identicalStateIsClean() {
        assertFalse(isScheduleEditDirty(baseline, baseline.copy()))
    }

    @Test
    fun dirty_nameChange() {
        assertTrue(isScheduleEditDirty(baseline, baseline.copy(name = "Work")))
    }

    @Test
    fun dirty_nameWhitespaceOnlyDriftIsClean() {
        // The save path stores name.trim(), so surrounding whitespace alone
        // is not an actual change.
        assertFalse(isScheduleEditDirty(baseline, baseline.copy(name = "  Exams\t")))
    }

    @Test
    fun dirty_daysChange() {
        assertTrue(isScheduleEditDirty(baseline, baseline.copy(days = setOf(0, 1))))
    }

    @Test
    fun dirty_startChange() {
        assertTrue(isScheduleEditDirty(baseline, baseline.copy(startMinute = 22 * 60)))
    }

    @Test
    fun dirty_endChange() {
        assertTrue(isScheduleEditDirty(baseline, baseline.copy(endMinute = 7 * 60)))
    }

    @Test
    fun dirty_modeChange() {
        assertTrue(isScheduleEditDirty(baseline, baseline.copy(mode = ScheduleMode.INTERNET)))
    }

    @Test
    fun dirty_enabledFlip() {
        assertTrue(isScheduleEditDirty(baseline, baseline.copy(enabled = false)))
    }

    @Test
    fun dirty_selectedAppsChange() {
        assertTrue(isScheduleEditDirty(baseline, baseline.copy(selectedApps = setOf("com.other"))))
    }

    @Test
    fun dirty_revertedEditsAreClean() {
        val edited = baseline.copy(name = "Work", enabled = false)
        assertFalse(isScheduleEditDirty(baseline, edited.copy(name = "Exams", enabled = true)))
    }

    @Test
    fun dirty_transientFieldsIgnored() {
        // loading / appsLoaded never participate in the dirty check.
        assertFalse(
            isScheduleEditDirty(
                baseline,
                baseline.copy(loading = true, appsLoaded = true),
            )
        )
    }
}
