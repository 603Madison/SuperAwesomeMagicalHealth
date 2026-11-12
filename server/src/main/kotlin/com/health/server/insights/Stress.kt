package com.health.server.insights

import com.health.api.Contributor
import com.health.api.Resilience
import com.health.api.ResilienceLevel
import com.health.api.StressDay
import com.health.api.StressSpan
import com.health.api.StressState
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * A quarter of an hour awake and fairly still, as recorded: the average heart rate, HRV and the
 * device's own stress reading (0–100) where it gives one. Windows with a workout or a lot of
 * steps are left out, since a racing heart from a walk isn't stress.
 */
@Serializable
data class StressWindow(val start: Long, val hr: Double? = null, val hrv: Double? = null, val device: Double? = null)

/**
 * Daytime stress and resilience, as pure functions. Each window is compared with the person's own
 * baseline: their resting heart rate and their usual daytime HRV. The device's stress reading,
 * where there is one, is used as it is (Garmin's bands: 0–25 rest, 26–50 low, 51–75 medium,
 * 76–100 high).
 */
object Stress {
    const val WINDOW_MS = 15 * 60_000L
    /** Steps in a window above which it counts as activity rather than stress. */
    const val ACTIVE_STEPS = 500.0
    private const val WINDOW_MINUTES = 15

    data class Baseline(val restingHr: Double?, val daytimeHrv: Double?)

    /** The person's baseline from their recent days (oldest first) and today's resting heart rate. */
    fun baseline(today: DayStats, history: List<DayStats>): Baseline {
        val recent = history.takeLast(30)
        val resting = Scoring.median(recent.mapNotNull { it.restingHr } + listOfNotNull(today.restingHr)) ?: today.restingHr
        val hrv = Scoring.median((recent + today).flatMap { d -> d.stressWindows.mapNotNull { it.hrv } })
        return Baseline(resting, hrv)
    }

    /** One window's state, or null when there's too little to go on. */
    fun classify(w: StressWindow, base: Baseline): StressState? {
        w.device?.let { s ->
            return when {
                s <= 25 -> StressState.RESTORED
                s <= 50 -> StressState.RELAXED
                s <= 75 -> StressState.ENGAGED
                else -> StressState.STRESSED
            }
        }
        val hr = w.hr ?: return null
        val resting = base.restingHr ?: return null
        val delta = hr - resting
        val ratio = if (w.hrv != null && base.daytimeHrv != null && base.daytimeHrv > 0) w.hrv / base.daytimeHrv else null
        return when {
            ratio != null && ratio < 0.6 -> StressState.STRESSED
            delta >= 20 && (ratio == null || ratio < 0.85) -> StressState.STRESSED
            delta >= 10 || (ratio != null && ratio < 0.85) -> StressState.ENGAGED
            ratio != null && ratio >= 1.1 && delta <= 5 -> StressState.RESTORED
            ratio == null && delta <= 3 -> StressState.RESTORED
            else -> StressState.RELAXED
        }
    }

    /** The day's stress, or null with less than an hour of windows to go on. */
    fun day(date: String, windows: List<StressWindow>, base: Baseline): StressDay? {
        val states = windows.sortedBy { it.start }.mapNotNull { w -> classify(w, base)?.let { w.start to it } }
        if (states.size < 4) return null
        val minutes = StressState.entries.associateWith { s -> states.count { it.second == s } * WINDOW_MINUTES }
        val spans = mutableListOf<StressSpan>()
        states.forEach { (start, state) ->
            val last = spans.lastOrNull()
            if (last != null && last.state == state && last.end == start) spans[spans.lastIndex] = last.copy(end = start + WINDOW_MS)
            else spans += StressSpan(start, start + WINDOW_MS, state)
        }
        val stressed = minutes.getValue(StressState.STRESSED)
        val restored = minutes.getValue(StressState.RESTORED)
        val summary = when {
            stressed >= 120 && stressed > restored -> "Stressful"
            restored >= 60 && restored >= stressed -> "Restored"
            else -> "Normal"
        }
        return StressDay(
            date = date,
            stressedMinutes = stressed,
            engagedMinutes = minutes.getValue(StressState.ENGAGED),
            relaxedMinutes = minutes.getValue(StressState.RELAXED),
            restoredMinutes = restored,
            summary = summary,
            spans = spans,
        )
    }

    /**
     * Resilience over the last 14 days: sleep recovery (sleep scores), daytime recovery (restored
     * minutes) and stress load (stressed minutes). Needs a week of days with stress worked out.
     * [previous] are the 14 days before, for the trend.
     */
    fun resilience(days: List<StressDay?>, sleepScores: List<Int?>, previous: List<StressDay?> = emptyList(), previousSleep: List<Int?> = emptyList()): Resilience? {
        val score = overall(days, sleepScores) ?: return null
        val (total, contributors) = score
        val before = overall(previous, previousSleep)?.first
        val trend = when {
            before == null -> "steady"
            total - before >= 5 -> "up"
            before - total >= 5 -> "down"
            else -> "steady"
        }
        val level = when {
            total < 35 -> ResilienceLevel.LIMITED
            total < 50 -> ResilienceLevel.ADEQUATE
            total < 65 -> ResilienceLevel.SOLID
            total < 80 -> ResilienceLevel.STRONG
            else -> ResilienceLevel.EXCEPTIONAL
        }
        return Resilience(level, contributors, trend)
    }

    private fun overall(days: List<StressDay?>, sleepScores: List<Int?>): Pair<Int, List<Contributor>>? {
        val known = days.filterNotNull()
        if (known.size < 7) return null
        val stressed = known.map { it.stressedMinutes }.average()
        val restored = known.map { it.restoredMinutes }.average()
        val sleep = sleepScores.filterNotNull().takeIf { it.size >= 4 }?.average()
        val daytime = (restored / 90.0 * 100).coerceIn(0.0, 100.0)
        val load = (100 - stressed / 240.0 * 100).coerceIn(0.0, 100.0)
        val contributors = listOfNotNull(
            sleep?.let { Contributor("sleep_recovery", "Sleep recovery", it.roundToInt(), "${it.roundToInt()} average sleep score", "Over the last two weeks") },
            Contributor("daytime_recovery", "Daytime recovery", daytime.roundToInt(), "${Scoring.hm(restored.roundToInt())} restored a day", "Calm, still time while awake"),
            Contributor("stress_load", "Stress load", load.roundToInt(), "${Scoring.hm(stressed.roundToInt())} stressed a day", "Less is better"),
        )
        val total = if (sleep != null) sleep * 0.4 + daytime * 0.3 + load * 0.3 else daytime * 0.5 + load * 0.5
        return total.roundToInt() to contributors
    }
}
