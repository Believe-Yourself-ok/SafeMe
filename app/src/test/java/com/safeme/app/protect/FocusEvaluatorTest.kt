package com.safeme.app.protect

import com.safeme.app.data.FocusSession
import com.safeme.app.data.FocusMode
import com.safeme.app.data.focusDaysLabel
import com.safeme.app.data.focusModeLabel
import com.safeme.app.data.focusTimeLabel
import com.safeme.app.data.focusWindowLabel
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure FocusMe decision core. */
class FocusEvaluatorTest {

    // ------------------------------------------------------------ helpers

    private fun rule(
        name: String = "Test",
        days: List<Int> = listOf(dayIndexOf(2026, 7, 5)),
        startMinute: Int = 21 * 60,
        endMinute: Int = 23 * 60,
        mode: FocusMode = FocusMode.BOTH,
        apps: List<String> = listOf("com.tiktok"),
        enabled: Boolean = true,
    ) = FocusSession(
        id = name,
        name = name,
        days = days,
        startMinute = startMinute,
        endMinute = endMinute,
        mode = mode,
        appPackages = apps,
        enabled = enabled,
    )

    /** Prototype day index (0=Mon..6=Sun) of a calendar date. */
    private fun dayIndexOf(year: Int, month0: Int, day: Int): Int {
        val c = Calendar.getInstance().apply {
            clear()
            set(year, month0, day)
        }
        return (c.get(Calendar.DAY_OF_WEEK) - 1 + 6) % 7
    }

    private fun at(year: Int, month0: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month0, day, hour, minute, 0)
        }.timeInMillis

    /** A Wednesday in August 2026 — compute its day index dynamically. */
    private val wednesdayIndex = dayIndexOf(2026, 7, 5)
    private val mondayIndex = dayIndexOf(2026, 7, 3)
    private val sundayIndex = dayIndexOf(2026, 7, 9)

    /** Bedtime rule: 22:00 → 07:00 on the given days (overnight spill). */
    private fun overnightRule(
        days: List<Int> = listOf(mondayIndex),
        enabled: Boolean = true,
    ) = rule(days = days, startMinute = 22 * 60, endMinute = 7 * 60, enabled = enabled)

    // ---------------------------------------------------------- active window

    @Test
    fun evaluate_matchesDayAndWindow() {
        val r = rule(days = listOf(wednesdayIndex), startMinute = 21 * 60, endMinute = 23 * 60)
        val active = FocusEvaluator.evaluate(listOf(r), at(2026, 7, 5, 21, 30))
        assertEquals(setOf("com.tiktok"), active.launchBlockedPackages)
        assertEquals(setOf("com.tiktok"), active.internetBlockedPackages)
        assertFalse(active.launchBlockAll)
        assertFalse(active.internetBlockAll)
    }

    @Test
    fun evaluate_ignoresOtherDays() {
        val r = rule(days = listOf(wednesdayIndex))
        // Thursday 2026-08-06 at the same time — must be inactive.
        val active = FocusEvaluator.evaluate(listOf(r), at(2026, 7, 6, 21, 30))
        assertFalse(active.hasLaunchBlock)
        assertFalse(active.hasInternetBlock)
    }

    @Test
    fun evaluate_windowIsHalfOpenEndExclusive() {
        val r = rule(startMinute = 21 * 60, endMinute = 23 * 60)
        assertTrue(FocusEvaluator.evaluate(listOf(r), at(2026, 7, 5, 22, 59)).hasLaunchBlock)
        assertFalse(FocusEvaluator.evaluate(listOf(r), at(2026, 7, 5, 23, 0)).hasLaunchBlock)
    }

    @Test
    fun evaluate_disabledRulesAreSkipped() {
        val r = rule(enabled = false)
        val active = FocusEvaluator.evaluate(listOf(r), at(2026, 7, 5, 21, 30))
        assertFalse(active.hasLaunchBlock)
    }

    @Test
    fun evaluate_modeMapsCorrectly() {
        val internet = FocusEvaluator.evaluate(
            listOf(rule(mode = FocusMode.INTERNET)),
            at(2026, 7, 5, 21, 30),
        )
        assertEquals(setOf("com.tiktok"), internet.internetBlockedPackages)
        assertTrue(internet.launchBlockedPackages.isEmpty())

        val launch = FocusEvaluator.evaluate(
            listOf(rule(mode = FocusMode.LAUNCH)),
            at(2026, 7, 5, 21, 30),
        )
        assertEquals(setOf("com.tiktok"), launch.launchBlockedPackages)
        assertTrue(launch.internetBlockedPackages.isEmpty())
    }

    @Test
    fun evaluate_emptyAppsBlocksEverything() {
        val r = rule(apps = emptyList())
        val active = FocusEvaluator.evaluate(listOf(r), at(2026, 7, 5, 21, 30))
        assertTrue(active.launchBlockAll)
        assertTrue(active.internetBlockAll)
    }

    @Test
    fun evaluate_unionAcrossRules() {
        val r1 = rule(name = "A", apps = listOf("com.tiktok"), mode = FocusMode.LAUNCH)
        val r2 = rule(name = "B", apps = listOf("com.instagram"), mode = FocusMode.INTERNET)
        val active = FocusEvaluator.evaluate(listOf(r1, r2), at(2026, 7, 5, 21, 30))
        assertEquals(setOf("com.tiktok"), active.launchBlockedPackages)
        assertEquals(setOf("com.instagram"), active.internetBlockedPackages)
    }

    @Test
    fun evaluate_emptyRulesInactive() {
        val active = FocusEvaluator.evaluate(emptyList(), at(2026, 7, 5, 21, 30))
        assertFalse(active.hasLaunchBlock)
        assertFalse(active.hasInternetBlock)
    }

    @Test
    fun calendarDayOfWeek_mapsMonTo2SunTo1() {
        assertEquals(2, FocusEvaluator.calendarDayOfWeek(0)) // Mon
        assertEquals(3, FocusEvaluator.calendarDayOfWeek(1)) // Tue
        assertEquals(1, FocusEvaluator.calendarDayOfWeek(6)) // Sun
    }

    // ---------------------------------------------------------- next boundary

    @Test
    fun nextBoundary_returnsNextStart() {
        val r = rule(days = listOf(wednesdayIndex), startMinute = 21 * 60, endMinute = 23 * 60)
        // Tuesday evening — boundary is Wednesday 21:00.
        val tue = at(2026, 7, 4, 20, 0)
        val boundary = FocusEvaluator.nextBoundary(listOf(r), tue)
        assertEquals(at(2026, 7, 5, 21, 0), boundary)
    }

    @Test
    fun nextBoundary_returnsEndWhenInsideWindow() {
        val r = rule(days = listOf(wednesdayIndex), startMinute = 21 * 60, endMinute = 23 * 60)
        val inside = at(2026, 7, 5, 22, 0)
        val boundary = FocusEvaluator.nextBoundary(listOf(r), inside)
        assertEquals(at(2026, 7, 5, 23, 0), boundary)
    }

    @Test
    fun nextBoundary_wrapsToNextWeek() {
        val r = rule(days = listOf(wednesdayIndex), startMinute = 21 * 60, endMinute = 23 * 60)
        // Thursday right after the window — next boundary is next Wednesday.
        val thu = at(2026, 7, 6, 0, 0)
        val boundary = FocusEvaluator.nextBoundary(listOf(r), thu)
        assertEquals(at(2026, 7, 12, 21, 0), boundary)
    }

    @Test
    fun nextBoundary_noRulesReturnsMax() {
        assertEquals(Long.MAX_VALUE, FocusEvaluator.nextBoundary(emptyList()))
    }

    @Test
    fun nextBoundary_disabledRulesIgnored() {
        val r = rule(enabled = false)
        assertEquals(Long.MAX_VALUE, FocusEvaluator.nextBoundary(listOf(r)))
    }

    // ---------------------------------------------------------------- labels

    @Test
    fun daysLabel_dailyForAllSeven() {
        assertEquals("Daily", focusDaysLabel(listOf(0, 1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun daysLabel_joinsSortedWithDotSeparator() {
        assertEquals("Mon · Wed · Fri", focusDaysLabel(listOf(4, 0, 2)))
        assertEquals("Tue · Sat", focusDaysLabel(listOf(1, 5)))
    }

    @Test
    fun daysLabel_emptyAndDeduplicated() {
        assertEquals("", focusDaysLabel(emptyList()))
        assertEquals("Mon · Fri", focusDaysLabel(listOf(0, 4, 0)))
    }

    @Test
    fun modeLabel_matchesPrototype() {
        assertEquals("Internet + Launch", focusModeLabel(FocusMode.BOTH))
        assertEquals("Internet blocked", focusModeLabel(FocusMode.INTERNET))
        assertEquals("Launch blocked", focusModeLabel(FocusMode.LAUNCH))
    }

    @Test
    fun timeLabel_padsToHhmm() {
        assertEquals("21:00", focusTimeLabel(21 * 60))
        assertEquals("07:05", focusTimeLabel(7 * 60 + 5))
        assertEquals("00:00", focusTimeLabel(0))
        assertEquals("23:59", focusTimeLabel(23 * 60 + 59))
    }

    @Test
    fun windowLabel_usesEnDashWithSpaces() {
        assertEquals("21:00 – 23:00", focusWindowLabel(21 * 60, 23 * 60))
    }

    @Test
    fun windowLabel_wrapAppendsPlusOne() {
        assertEquals("22:00 – 07:00 (+1)", focusWindowLabel(22 * 60, 7 * 60))
    }

    // ------------------------------------------------------ overnight wrap

    @Test
    fun evaluate_wrapEveningActive() {
        val active = FocusEvaluator.evaluate(listOf(overnightRule()), at(2026, 7, 3, 22, 30))
        assertEquals(setOf("com.tiktok"), active.launchBlockedPackages)
        assertEquals(setOf("com.tiktok"), active.internetBlockedPackages)
    }

    @Test
    fun evaluate_wrapSpillsIntoNextMorning() {
        val active = FocusEvaluator.evaluate(listOf(overnightRule()), at(2026, 7, 4, 3, 0))
        assertTrue(active.hasLaunchBlock)
        assertTrue(active.hasInternetBlock)
    }

    @Test
    fun evaluate_wrapGapInactive() {
        val rules = listOf(overnightRule())
        assertFalse(FocusEvaluator.evaluate(rules, at(2026, 7, 3, 21, 59)).hasLaunchBlock)
        assertFalse(FocusEvaluator.evaluate(rules, at(2026, 7, 4, 12, 0)).hasLaunchBlock)
    }

    @Test
    fun evaluate_wrapBoundariesHalfOpen() {
        val rules = listOf(overnightRule())
        assertTrue(FocusEvaluator.evaluate(rules, at(2026, 7, 3, 22, 0)).hasLaunchBlock)
        assertFalse(FocusEvaluator.evaluate(rules, at(2026, 7, 4, 7, 0)).hasLaunchBlock)
    }

    @Test
    fun evaluate_wrapSundaySpillsIntoMonday() {
        val rules = listOf(overnightRule(days = listOf(sundayIndex)))
        // Monday 2026-08-10 just after midnight — Sunday's spill is active.
        assertTrue(FocusEvaluator.evaluate(rules, at(2026, 7, 10, 0, 30)).hasLaunchBlock)
        assertFalse(FocusEvaluator.evaluate(rules, at(2026, 7, 10, 8, 0)).hasLaunchBlock)
    }

    @Test
    fun evaluate_wrapDisabledInactive() {
        val rules = listOf(overnightRule(enabled = false))
        assertFalse(FocusEvaluator.evaluate(rules, at(2026, 7, 3, 22, 30)).hasLaunchBlock)
        assertFalse(FocusEvaluator.evaluate(rules, at(2026, 7, 4, 3, 0)).hasLaunchBlock)
    }

    @Test
    fun evaluate_equalStartEndNeverActive() {
        val rules = listOf(rule(startMinute = 22 * 60, endMinute = 22 * 60))
        assertFalse(FocusEvaluator.evaluate(rules, at(2026, 7, 5, 22, 0)).hasLaunchBlock)
        assertFalse(FocusEvaluator.evaluate(rules, at(2026, 7, 5, 22, 30)).hasLaunchBlock)
    }

    @Test
    fun evaluate_wrapUnionWithNormalRule() {
        val r1 = overnightRule()
        val r2 = rule(
            name = "B",
            days = listOf(mondayIndex),
            apps = listOf("com.instagram"),
            mode = FocusMode.INTERNET,
        )
        val active = FocusEvaluator.evaluate(listOf(r1, r2), at(2026, 7, 3, 22, 30))
        assertEquals(setOf("com.tiktok"), active.launchBlockedPackages)
        assertEquals(setOf("com.tiktok", "com.instagram"), active.internetBlockedPackages)
    }

    @Test
    fun nextBoundary_wrapBeforeStart() {
        val boundary = FocusEvaluator.nextBoundary(listOf(overnightRule()), at(2026, 7, 3, 21, 0))
        assertEquals(at(2026, 7, 3, 22, 0), boundary)
    }

    @Test
    fun nextBoundary_wrapEveningReturnsSpillEnd() {
        val boundary = FocusEvaluator.nextBoundary(listOf(overnightRule()), at(2026, 7, 3, 23, 0))
        assertEquals(at(2026, 7, 4, 7, 0), boundary)
    }

    @Test
    fun nextBoundary_wrapSpillReturnsSpillEnd() {
        val boundary = FocusEvaluator.nextBoundary(listOf(overnightRule()), at(2026, 7, 4, 3, 0))
        assertEquals(at(2026, 7, 4, 7, 0), boundary)
    }

    @Test
    fun nextBoundary_wrapAfterSpillReturnsNextWeek() {
        val boundary = FocusEvaluator.nextBoundary(listOf(overnightRule()), at(2026, 7, 4, 8, 0))
        assertEquals(at(2026, 7, 10, 22, 0), boundary)
    }

    @Test
    fun nextBoundary_wrapAtEndInstantReturnsNextWeek() {
        val boundary = FocusEvaluator.nextBoundary(listOf(overnightRule()), at(2026, 7, 4, 7, 0))
        assertEquals(at(2026, 7, 10, 22, 0), boundary)
    }

    @Test
    fun nextBoundary_equalStartEndSkipped() {
        val rules = listOf(rule(startMinute = 22 * 60, endMinute = 22 * 60))
        assertEquals(Long.MAX_VALUE, FocusEvaluator.nextBoundary(rules, at(2026, 7, 5, 12, 0)))
    }
}
