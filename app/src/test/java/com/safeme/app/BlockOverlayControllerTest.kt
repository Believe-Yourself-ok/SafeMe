package com.safeme.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure logic extracted from [BlockOverlayController] (and mirrored from
 * BlockGateActivity.addBlockActivity): the activity-feed title/sub mapping for
 * every gate type. Android-free, so it runs in the JVM test suite.
 */
class BlockOverlayControllerTest {

    // ---- blockActivityTitle ----

    @Test
    fun websiteGateTitle() {
        assertEquals("Website blocked", blockActivityTitle("website", "label", "site.com"))
    }

    @Test
    fun titleGateTitle() {
        assertEquals("Settings page blocked", blockActivityTitle("title", "label", "Settings"))
    }

    @Test
    fun scheduleGateTitleUsesLabel() {
        assertEquals("Blocked Game", blockActivityTitle("schedule", "Game", ""))
    }

    @Test
    fun puGateTitle() {
        assertEquals("Uninstall blocked", blockActivityTitle("pu", "label", ""))
    }

    @Test
    fun keywordGateTitleWithMatch() {
        assertEquals("Keyword blocked", blockActivityTitle("", "label", "badword"))
    }

    @Test
    fun keywordGateTitleWithoutMatchFallsBackToLabel() {
        assertEquals("Blocked label", blockActivityTitle("", "label", ""))
    }

    // ---- blockActivitySub ----

    @Test
    fun subUsesMatchedTextWhenPresent() {
        assertEquals("site.com", blockActivitySub("website", "site.com"))
    }

    @Test
    fun scheduleSubWhenNoMatch() {
        assertEquals("Launch blocked by focus session", blockActivitySub("schedule", ""))
    }

    @Test
    fun puSubWhenNoMatch() {
        assertEquals("Prevent Uninstall is on", blockActivitySub("pu", ""))
    }

    @Test
    fun defaultSubWhenNoMatch() {
        assertEquals("Blocked by SafeMe", blockActivitySub("", ""))
    }

    // ---- blockGateMessage ----

    @Test
    fun messageUsesCustomWhenPresent() {
        assertEquals("My message", blockGateMessage("My message", "default"))
    }

    @Test
    fun messageFallsBackToDefaultWhenCustomEmpty() {
        assertEquals("default", blockGateMessage("", "default"))
    }

    // ---- blockGateWhyReason ----

    @Test
    fun websiteWhyWithMatch() {
        assertEquals(
            "Why: website blocked by SafeMe (site.com)",
            blockGateWhyReason("website", "site.com", "pu", "sched")
        )
    }

    @Test
    fun websiteWhyWithoutMatch() {
        assertEquals(
            "Why: website blocked by SafeMe",
            blockGateWhyReason("website", "", "pu", "sched")
        )
    }

    @Test
    fun titleWhyWithMatch() {
        assertEquals(
            "Why: Settings page blocked by SafeMe (Settings)",
            blockGateWhyReason("title", "Settings", "pu", "sched")
        )
    }

    @Test
    fun puWhyUsesPuMessage() {
        assertEquals("pu", blockGateWhyReason("pu", "", "pu", "sched"))
    }

    @Test
    fun focusWhyUsesFocusMessage() {
        assertEquals("sched", blockGateWhyReason("schedule", "", "pu", "sched"))
    }

    @Test
    fun keywordWhyWithMatch() {
        assertEquals(
            "Why: badword blocked by SafeMe",
            blockGateWhyReason("", "badword", "pu", "sched")
        )
    }

    @Test
    fun unknownWhyWithoutMatchUsesNeutralFallback() {
        assertEquals(
            "Why: this content or action was blocked by an active SafeMe rule",
            blockGateWhyReason("", "", "pu", "sched")
        )
    }
}
