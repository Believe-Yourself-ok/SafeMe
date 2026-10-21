package com.safeme.app.protect

import com.safeme.app.data.FocusSession
import com.safeme.app.data.FocusMode
import java.util.Calendar

/**
 * Pure decision core for FocusMe.
 *
 * No Android, I/O or side effects — trivially unit-testable (mirrors the
 * reference `ScheduleEvaluator` in the Protect-Yourself project).
 *
 * Day convention: schedules store 0=Mon … 6=Sun (prototype order); this file
 * converts to [Calendar.DAY_OF_WEEK] (1=Sun … 7=Sat) internally.
 *
 * An empty [FocusSession.appPackages] list means "no apps picked" → the rule
 * blocks every app ([FocusSession.blocksAllApps]), per the prototype's
 * "No apps — schedule blocks everything" editor copy.
 */
object FocusEvaluator {

    /** Which apps are restricted at a given instant. */
    data class ActiveRules(
        val internetBlockedPackages: Set<String>,
        val internetBlockAll: Boolean,
        val launchBlockedPackages: Set<String>,
        val launchBlockAll: Boolean,
    ) {
        val hasInternetBlock: Boolean get() = internetBlockAll || internetBlockedPackages.isNotEmpty()
        val hasLaunchBlock: Boolean get() = launchBlockAll || launchBlockedPackages.isNotEmpty()
    }

    /** Prototype day index (0=Mon) → [Calendar.DAY_OF_WEEK] (1=Sun). */
    fun calendarDayOfWeek(dayIndex: Int): Int = ((dayIndex + 1) % 7) + 1

    /** True when [dayIndex] (0=Mon..6=Sun) is part of the rule's repeat set. */
    fun matchesDay(rule: FocusSession, dayIndex: Int): Boolean =
        dayIndex in 0..6 && dayIndex in rule.days

    /**
     * True when [minuteOfDay] falls inside the rule's window. Normal windows
     * (`end > start`) match `start until end` on selected days; overnight
     * windows (`end < start`) match `[start, 24:00)` on selected days plus
     * `[00:00, end)` spilling into the following day. Degenerate windows
     * (`end == start`) never match.
     */
    fun isActiveAt(rule: FocusSession, dayIndex: Int, minuteOfDay: Int): Boolean {
        if (!rule.enabled || dayIndex !in 0..6) return false
        val start = rule.startMinute
        val end = rule.endMinute
        if (start !in 0..1439 || end !in 0..1439 || end == start) return false
        if (end > start) {
            return matchesDay(rule, dayIndex) && minuteOfDay in start until end
        }
        // Overnight: [start, 24:00) on each selected day plus [00:00, end)
        // spilling into the following day. (minuteOfDay >= start is safely
        // bounded: callers derive it from Calendar fields, always 0..1439.)
        val evening = dayIndex in rule.days && minuteOfDay >= start
        val morning = prevDayIndex(dayIndex) in rule.days && minuteOfDay < end
        return evening || morning
    }

    /** Day before [dayIndex] in prototype order (Mon=0..Sun=6); Monday's is Sunday. */
    private fun prevDayIndex(dayIndex: Int): Int = (dayIndex + 6) % 7

    /**
     * Evaluate all rules at [nowMillis]. Union semantics: if any active rule
     * targets a package, that package is blocked.
     */
    fun evaluate(
        rules: List<FocusSession>,
        nowMillis: Long = System.currentTimeMillis(),
    ): ActiveRules {
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val dow = calendar.get(Calendar.DAY_OF_WEEK)
        // Map the current Calendar DOW back to a prototype day index (0=Mon).
        val dayIndex = ((dow - 1 + 6) % 7)
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        val internetBlocked = LinkedHashSet<String>()
        val launchBlocked = LinkedHashSet<String>()
        var internetBlockAll = false
        var launchBlockAll = false

        for (rule in rules) {
            if (!isActiveAt(rule, dayIndex, minuteOfDay)) continue
            when (rule.mode) {
                FocusMode.INTERNET -> {
                    if (rule.blocksAllApps) internetBlockAll = true else internetBlocked.addAll(rule.appPackages)
                }
                FocusMode.LAUNCH -> {
                    if (rule.blocksAllApps) launchBlockAll = true else launchBlocked.addAll(rule.appPackages)
                }
                FocusMode.BOTH -> {
                    if (rule.blocksAllApps) {
                        internetBlockAll = true
                        launchBlockAll = true
                    } else {
                        internetBlocked.addAll(rule.appPackages)
                        launchBlocked.addAll(rule.appPackages)
                    }
                }
            }
        }

        return ActiveRules(internetBlocked, internetBlockAll, launchBlocked, launchBlockAll)
    }

    /**
     * Earliest future instant (start or end) of any enabled rule, or
     * [Long.MAX_VALUE] when no boundary exists (no rules). An overnight
     * rule's end is emitted on the day it spills into.
     */
    fun nextBoundary(
        rules: List<FocusSession>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        val enabled = rules.filter { it.enabled && it.days.isNotEmpty() }
        if (enabled.isEmpty()) return Long.MAX_VALUE

        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        var earliest = Long.MAX_VALUE

        // dayOffset 0..7 covers every day-of-week combination exactly once.
        for (dayOffset in 0..7) {
            val day = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dayIndex = ((day.get(Calendar.DAY_OF_WEEK) - 1 + 6) % 7)
            for (rule in enabled) {
                val start = rule.startMinute
                val end = rule.endMinute
                if (start !in 0..1439 || end !in 0..1439 || end == start) continue
                if (dayIndex in rule.days) {
                    val startAt = day.timeInMillis + start * 60_000L
                    if (startAt > nowMillis && startAt < earliest) earliest = startAt
                    if (end > start) {
                        val endAt = day.timeInMillis + end * 60_000L
                        if (endAt > nowMillis && endAt < earliest) earliest = endAt
                    }
                }
                // A wrapped rule's end is emitted while scanning the day it
                // spills into (independent if: consecutive selected days emit
                // both their own start and the previous day's spill end).
                // Every candidate stays before the next midnight, so the
                // early-break below remains correct.
                if (end < start && prevDayIndex(dayIndex) in rule.days) {
                    val spillAt = day.timeInMillis + end * 60_000L
                    if (spillAt > nowMillis && spillAt < earliest) earliest = spillAt
                }
            }
            if (earliest != Long.MAX_VALUE) break
        }
        return earliest
    }
}
