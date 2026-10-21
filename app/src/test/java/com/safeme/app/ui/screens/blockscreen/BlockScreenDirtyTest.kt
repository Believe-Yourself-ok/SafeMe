package com.safeme.app.ui.screens.blockscreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** COD-81: net-diff dirty detection for the block-screen editor's exit guard. */
class BlockScreenDirtyTest {

    private val defaultMsg = "Stay safe, Alex. You chose this."

    private val baseline = BlockScreenSnapshot(
        dwell = 10,
        message = "Stay focused",
        img = "sunset",
        whyOn = true,
    )

    @Test
    fun dirty_identicalStateIsClean() {
        assertFalse(isBlockScreenDirty(baseline, baseline.copy(), defaultMsg))
    }

    @Test
    fun dirty_dwellStep() {
        assertTrue(isBlockScreenDirty(baseline, baseline.copy(dwell = 11), defaultMsg))
    }

    @Test
    fun dirty_messageEdited() {
        assertTrue(isBlockScreenDirty(baseline, baseline.copy(message = "You chose this"), defaultMsg))
    }

    @Test
    fun dirty_messageBlankVsDefaultBaselineIsClean() {
        // The screen resolves message.ifEmpty { default }, so blank and the
        // default preview message are the same content in both directions.
        val defaultBaseline = baseline.copy(message = defaultMsg)
        assertFalse(isBlockScreenDirty(defaultBaseline, defaultBaseline.copy(message = ""), defaultMsg))
        assertFalse(isBlockScreenDirty(defaultBaseline.copy(message = ""), defaultBaseline, defaultMsg))
    }

    @Test
    fun dirty_messageClearedVsCustomBaselineIsDirty() {
        assertTrue(isBlockScreenDirty(baseline, baseline.copy(message = ""), defaultMsg))
    }

    @Test
    fun dirty_imgChanged() {
        assertTrue(isBlockScreenDirty(baseline, baseline.copy(img = "ocean"), defaultMsg))
    }

    @Test
    fun dirty_imgCleared() {
        assertTrue(isBlockScreenDirty(baseline, baseline.copy(img = ""), defaultMsg))
    }

    @Test
    fun dirty_whyToggle() {
        assertTrue(isBlockScreenDirty(baseline, baseline.copy(whyOn = false), defaultMsg))
    }

    @Test
    fun dirty_revertedEditsAreClean() {
        val edited = baseline.copy(dwell = 60, message = "x", img = "", whyOn = false)
        val reverted = edited.copy(dwell = 10, message = "Stay focused", img = "sunset", whyOn = true)
        assertFalse(isBlockScreenDirty(baseline, reverted, defaultMsg))
    }
}
