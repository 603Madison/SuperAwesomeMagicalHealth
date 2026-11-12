package com.health.server.insights

import com.health.api.Contributor
import com.health.api.Goals
import com.health.api.Score
import com.health.api.SleepSummary
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Readiness, sleep and activity scores, in the spirit of Oura's: each is 0–100, made of
 * contributors that each score 0–100 and say what they're based on. Contributors without data are
 * left out and the rest weighed up to make the whole. Baselines are the person's own: their
 * usual over recent weeks, not a population average.
 */
object Scoring {
    /** A score's word, as Oura uses them. */
    fun label(score: Int): String = when {
        score >= 85 -> "Optimal"
        score >= 70 -> "Good"
        score >= 60 -> "Fair"
        else -> "Pay attention"
    }

    // ---- Sleep ----

    fun sleep(night: SleepSummary?, goals: Goals, zone: java.time.ZoneId): Score? {
        night ?: return null
        val parts = mutableListOf<Weighted>()
        val need = goals.sleepMinutes.toDouble()
        parts += Weighted(
            35.0,
            Contributor(
                "total", "Total sleep",
                curve(night.asleepMinutes / need, 0.5 to 20.0, 0.75 to 60.0, 0.875 to 80.0, 1.0 to 100.0),
                hm(night.asleepMinutes), "Your goal is ${hm(goals.sleepMinutes)}",
            ),
        )
        val knowsAwake = night.staged || night.awakeMinutes > 0
        if (knowsAwake) {
            parts += Weighted(
                10.0,
                Contributor("efficiency", "Efficiency", curve(night.efficiency.toDouble(), 65.0 to 30.0, 75.0 to 60.0, 85.0 to 85.0, 92.0 to 100.0), "${night.efficiency}%", "Time asleep while in bed"),
            )
            val waso = (night.awakeMinutes - (night.latencyMinutes ?: 0)).coerceAtLeast(0)
            parts += Weighted(
                10.0,
                Contributor("restfulness", "Restfulness", curve(waso.toDouble(), 5.0 to 100.0, 15.0 to 85.0, 30.0 to 65.0, 60.0 to 30.0, 90.0 to 10.0), "${waso}m awake", "Time awake after first falling asleep"),
            )
        }
        if (night.staged) {
            parts += Weighted(15.0, Contributor("deep", "Deep sleep", curve(night.deepMinutes.toDouble(), 0.0 to 0.0, 30.0 to 45.0, 60.0 to 90.0, 75.0 to 100.0), hm(night.deepMinutes), "Restores the body; most comes early in the night"))
            parts += Weighted(15.0, Contributor("rem", "REM sleep", curve(night.remMinutes.toDouble(), 0.0 to 0.0, 45.0 to 50.0, 80.0 to 90.0, 95.0 to 100.0), hm(night.remMinutes), "Dreaming sleep, for memory and mood; most comes late in the night"))
            night.latencyMinutes?.let { latency ->
                parts += Weighted(
                    5.0,
                    Contributor(
                        "latency", "Latency",
                        curve(latency.toDouble(), 0.0 to 70.0, 5.0 to 100.0, 20.0 to 100.0, 45.0 to 50.0, 90.0 to 20.0),
                        "${latency}m", "Time to fall asleep: 5 to 20 minutes is ideal",
                    ),
                )
            }
        }
        // Timing: the middle of the night between about 1 and 4 am suits the body clock best.
        val mid = java.time.Instant.ofEpochMilli((night.start + night.end) / 2).atZone(zone)
        val midHours = (mid.hour + mid.minute / 60.0).let { if (it > 15) it - 24 else it }
        val off = when {
            midHours < 1.0 -> 1.0 - midHours
            midHours > 4.0 -> midHours - 4.0
            else -> 0.0
        }
        parts += Weighted(10.0, Contributor("timing", "Timing", curve(off, 0.0 to 100.0, 1.0 to 85.0, 2.0 to 65.0, 4.0 to 30.0), "Midpoint ${clock(mid.hour, mid.minute)}", "A midpoint between 1 and 4 am fits the body clock"))
        return combine(parts)
    }

    // ---- Readiness ----

    /**
     * How ready the body is for the day: last night's heart and sleep against the person's usual,
     * and how hard recent days were. [history] is the days before [today], oldest first.
     */
    fun readiness(today: DayStats, history: List<DayStats>, goals: Goals, sleepScore: Int?): Score? {
        val parts = mutableListOf<Weighted>()
        val recent14 = history.takeLast(14)
        val recent30 = history.takeLast(30)

        val rhr = today.restingHr
        val rhrBase = median(recent14.mapNotNull { it.restingHr })
        if (rhr != null && rhrBase != null && recent14.count { it.restingHr != null } >= 3) {
            val diff = rhr - rhrBase
            parts += Weighted(
                20.0,
                Contributor(
                    "rhr", "Resting heart rate", curve(diff, -2.0 to 100.0, 0.0 to 92.0, 2.0 to 80.0, 4.0 to 65.0, 7.0 to 45.0, 12.0 to 15.0),
                    "${rhr.roundToInt()} bpm", "Usually ${rhrBase.roundToInt()} bpm",
                ),
            )
        }

        val hrvBase = median(history.dropLast(2).takeLast(28).mapNotNull { it.hrv })
        val hrvRecent = (history.takeLast(2) + today).mapNotNull { it.hrv }.takeIf { it.isNotEmpty() }?.average()
        if (today.hrv != null && hrvRecent != null && hrvBase != null && history.count { it.hrv != null } >= 5) {
            val ratio = hrvRecent / hrvBase
            parts += Weighted(
                20.0,
                Contributor(
                    "hrv", "HRV balance", curve(ratio, 0.5 to 10.0, 0.7 to 45.0, 0.8 to 65.0, 0.9 to 85.0, 1.0 to 100.0),
                    "${today.hrv.roundToInt()} ms", "Last 3 days average ${hrvRecent.roundToInt()} ms, usually ${hrvBase.roundToInt()} ms",
                ),
            )
        }

        temperatureDeviation(today, recent30)?.let { dev ->
            parts += Weighted(
                10.0,
                Contributor("temperature", "Body temperature", curve(abs(dev), 0.0 to 100.0, 0.3 to 100.0, 0.5 to 85.0, 1.0 to 50.0, 1.5 to 20.0), signed(dev) + " °C", "Against your usual"),
            )
        }

        if (sleepScore != null) {
            parts += Weighted(20.0, Contributor("sleep", "Previous night", sleepScore, "$sleepScore", "Last night's sleep score"))
        }

        val slept = (recent14.takeLast(13) + today).mapNotNull { it.sleep?.asleepMinutes }
        if (slept.size >= 3) {
            val avg = slept.average()
            parts += Weighted(
                10.0,
                Contributor(
                    "balance", "Sleep balance", curve(avg / goals.sleepMinutes, 0.7 to 30.0, 0.8 to 55.0, 0.9 to 80.0, 0.97 to 100.0),
                    hm(avg.roundToInt()), "Average over ${slept.size} nights; your goal is ${hm(goals.sleepMinutes)}",
                ),
            )
        }

        val yesterday = history.lastOrNull()
        if (yesterday?.activeCalories != null && goals.activeCalories > 0) {
            val ratio = yesterday.activeCalories / goals.activeCalories
            parts += Weighted(
                10.0,
                Contributor(
                    "previous", "Previous day activity", curve(ratio, 0.0 to 75.0, 0.3 to 100.0, 1.6 to 100.0, 2.5 to 60.0, 4.0 to 30.0),
                    "${yesterday.activeCalories.roundToInt()} kcal", "Active calories yesterday",
                ),
            )
        }

        val week = history.takeLast(7).mapNotNull { it.activeCalories }
        val month = recent30.mapNotNull { it.activeCalories }
        if (week.size >= 4 && month.size >= 14 && month.average() > 0) {
            val ratio = week.average() / month.average()
            parts += Weighted(
                5.0,
                Contributor(
                    "load", "Activity balance", curve(ratio, 0.4 to 50.0, 0.8 to 100.0, 1.3 to 100.0, 1.8 to 50.0, 2.5 to 20.0),
                    "${(ratio * 100).roundToInt()}%", "Last week's activity against your month",
                ),
            )
        }

        today.lowestHrPosition?.let { position ->
            parts += Weighted(
                5.0,
                Contributor(
                    "recovery", "Recovery index", curve(position, 0.0 to 100.0, 0.5 to 100.0, 0.75 to 70.0, 1.0 to 40.0),
                    "${(position * 100).roundToInt()}% through the night", "When your heart rate was lowest: the earlier, the better rested",
                ),
            )
        }

        // Readiness needs either the night or the heart to say something.
        if (parts.none { it.contributor.key in setOf("rhr", "hrv", "sleep") }) return null
        return combine(parts)
    }

    /** Today's temperature against the person's usual: the device's own figure, or skin temperature against the last month. */
    fun temperatureDeviation(today: DayStats, recent30: List<DayStats>): Double? {
        today.tempDeviation?.let { return it }
        val skin = today.skinTemp ?: return null
        val base = median(recent30.mapNotNull { it.skinTemp })?.takeIf { recent30.count { it.skinTemp != null } >= 5 } ?: return null
        return skin - base
    }

    // ---- Activity ----

    /**
     * How active the day was, and the week: [hoursSoFar] is how many of the day's waking hours
     * (8 am to 10 pm) have passed (14 for a day that's over). [history] is the days before, oldest first.
     */
    fun activity(today: DayStats, history: List<DayStats>, goals: Goals, hoursSoFar: Int): Score? {
        if (today.steps == null && today.activeCalories == null && today.workouts == 0) return null
        val parts = mutableListOf<Weighted>()
        val week = history.takeLast(6) + today

        fun goalRatio(day: DayStats): Double {
            val steps = if (goals.steps > 0) (day.steps ?: 0) / goals.steps.toDouble() else 0.0
            val kcal = if (goals.activeCalories > 0) (day.activeCalories ?: 0.0) / goals.activeCalories else 0.0
            return maxOf(steps, kcal)
        }

        val ratio = goalRatio(today)
        parts += Weighted(
            25.0,
            Contributor(
                "goal", "Meet daily goals", curve(ratio, 0.0 to 0.0, 0.5 to 50.0, 1.0 to 100.0),
                "${(ratio * 100).roundToInt().coerceAtMost(999)}%",
                listOfNotNull(today.steps?.let { "%,d steps".format(it) }, today.activeCalories?.let { "${it.roundToInt()} active kcal" }).joinToString(" · "),
            ),
        )

        if (today.hourlySteps.isNotEmpty() && hoursSoFar > 0) {
            val active = today.hourlySteps.subList(8, (8 + hoursSoFar).coerceAtMost(22)).count { it >= 250 }
            parts += Weighted(
                15.0,
                Contributor(
                    "hourly", "Move every hour", curve(active / (hoursSoFar * 0.7), 0.0 to 0.0, 0.5 to 50.0, 1.0 to 100.0),
                    "$active of $hoursSoFar hours", "Hours with at least 250 steps, 8 am to 10 pm",
                ),
            )
        }

        if (history.size >= 3) {
            val met = week.count { goalRatio(it) >= 1.0 }
            parts += Weighted(15.0, Contributor("weekly", "Weekly goals", curve(met.toDouble(), 0.0 to 20.0, 3.0 to 70.0, 5.0 to 100.0), "$met of ${week.size} days", "Days you met your step or calorie goal"))
        }

        val sessions = week.sumOf { it.workouts }
        if (goals.workoutsPerWeek > 0) {
            parts += Weighted(15.0, Contributor("frequency", "Training frequency", curve(sessions / goals.workoutsPerWeek.toDouble(), 0.0 to 20.0, 1.0 to 100.0), "$sessions this week", "Your goal is ${goals.workoutsPerWeek} a week"))
        }

        val kcalWeek = week.mapNotNull { it.activeCalories }
        if (kcalWeek.size >= 3 && goals.activeCalories > 0) {
            val volume = kcalWeek.sum() / (goals.activeCalories * week.size.toDouble())
            parts += Weighted(15.0, Contributor("volume", "Training volume", curve(volume, 0.0 to 0.0, 0.6 to 60.0, 1.0 to 100.0), "%,d kcal".format(kcalWeek.sum().roundToInt()), "Active calories over the last ${week.size} days"))
        }

        if (history.size >= 3) {
            val easy = week.dropLast(1).count { goalRatio(it) < 0.8 && it.workouts == 0 }
            parts += Weighted(15.0, Contributor("recovery", "Recovery time", if (easy >= 1) 100 else 60, if (easy == 1) "1 easy day" else "$easy easy days", "Easier days help the body adapt"))
        }
        return combine(parts)
    }

    // ---- Helpers ----

    private class Weighted(val weight: Double, val contributor: Contributor)

    private fun combine(parts: List<Weighted>): Score? {
        if (parts.isEmpty()) return null
        val total = parts.sumOf { it.weight }
        val score = (parts.sumOf { it.weight * it.contributor.score } / total).roundToInt().coerceIn(0, 100)
        return Score(score, label(score), parts.map { it.contributor })
    }

    /** A score from [x], drawn straight between the given (x, score) points and level beyond them. */
    fun curve(x: Double, vararg points: Pair<Double, Double>): Int {
        if (x.isNaN()) return 0
        val sorted = points.sortedBy { it.first }
        if (x <= sorted.first().first) return sorted.first().second.roundToInt()
        if (x >= sorted.last().first) return sorted.last().second.roundToInt()
        val (a, b) = sorted.zipWithNext().first { (a, b) -> x >= a.first && x <= b.first }
        val t = (x - a.first) / (b.first - a.first)
        return (a.second + t * (b.second - a.second)).roundToInt().coerceIn(0, 100)
    }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** "7h 32m", "45m". */
    fun hm(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

    fun signed(value: Double): String = (if (value >= 0) "+" else "−") + String.format(java.util.Locale.ROOT, "%.1f", abs(value))

    private fun clock(hour: Int, minute: Int): String {
        val h = if (hour % 12 == 0) 12 else hour % 12
        return "%d:%02d %s".format(h, minute, if (hour < 12) "am" else "pm")
    }
}
