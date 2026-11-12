package com.health.server.insights

import com.health.api.Insight
import com.health.api.NutritionTarget
import com.health.api.Profile
import com.health.api.Score
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The day's few things worth knowing, in plain words: what stands out against the person's own
 * usual, and what would help. At most four, most useful first.
 */
object InsightRules {
    class Context(
        val today: DayStats,
        /** The days before, oldest first. */
        val history: List<DayStats>,
        val profile: Profile,
        val target: NutritionTarget,
        val readiness: Score?,
        val sleep: Score?,
        val isToday: Boolean,
        val hourNow: Int,
        val restingCalories: Double?,
        /** Where the person is in their cycle, if they track it: a warmer, faster heart after ovulation is expected. */
        val cyclePhase: com.health.api.CyclePhase? = null,
        /** The health modules' insights, ranked with these. */
        val extra: List<Pair<Int, Insight>> = emptyList(),
    )

    private class Ranked(val priority: Int, val insight: Insight)

    fun insights(c: Context): List<Insight> {
        val out = mutableListOf<Ranked>()
        fun add(priority: Int, tone: String, icon: String, title: String, body: String) { out += Ranked(priority, Insight(tone, title, body, icon)) }
        val today = c.today
        val goals = c.profile.goals
        val recent = c.history.takeLast(14)

        // Readiness overall.
        c.readiness?.let { r ->
            when {
                r.score >= 85 -> add(60, "good", "zap", "Ready for a challenge", "Your body has recovered well. A good day to push a little harder.")
                r.score < 60 -> add(90, "warn", "battery-low", "Take it easy today", "Your readiness is low. Gentle movement, water and an early night will help you bounce back.")
            }
        }

        // Resting heart rate against usual.
        val rhrBase = Scoring.median(recent.mapNotNull { it.restingHr })
        if (today.restingHr != null && rhrBase != null && recent.count { it.restingHr != null } >= 3) {
            val diff = today.restingHr - rhrBase
            val luteal = c.cyclePhase == com.health.api.CyclePhase.LUTEAL
            if (diff >= 4 && luteal && diff < 7) {
                out += Ranked(40, Insight("info", "Resting heart rate is up ${diff.roundToInt()} bpm", "A few beats higher is common in the second half of a cycle.", "heart", com.health.api.Module.CYCLE))
            } else if (diff >= 4) {
                add(85, "warn", "heart", "Resting heart rate is up ${diff.roundToInt()} bpm", "Alcohol, a late meal, stress, illness or a hard workout can all raise it. Listen to your body today.")
            } else if (diff <= -3) {
                add(50, "good", "heart", "Resting heart rate ${(-diff).roundToInt()} bpm below your usual", "A sign your body is well recovered.")
            }
        }

        // HRV against usual.
        val hrvBase = Scoring.median(c.history.takeLast(28).mapNotNull { it.hrv })
        if (today.hrv != null && hrvBase != null && c.history.count { it.hrv != null } >= 5) {
            val ratio = today.hrv / hrvBase
            if (ratio <= 0.8) add(80, "warn", "activity", "HRV is lower than usual", "${today.hrv.roundToInt()} ms against your usual ${hrvBase.roundToInt()} ms: your body may be under strain. Rest and recovery help it climb back.")
            else if (ratio >= 1.15) add(45, "good", "activity", "HRV is higher than usual", "${today.hrv.roundToInt()} ms against your usual ${hrvBase.roundToInt()} ms: your nervous system is well balanced.")
        }

        // Temperature.
        Scoring.temperatureDeviation(today, c.history.takeLast(30))?.let { dev ->
            if (c.cyclePhase == com.health.api.CyclePhase.LUTEAL && dev in 0.2..0.8) {
                out += Ranked(38, Insight("info", "Temperature ${Scoring.signed(dev)} °C from your usual", "As expected in the second half of a cycle: it usually falls again around your period.", "thermometer", com.health.api.Module.CYCLE))
            } else if (abs(dev) >= 0.6) {
                add(88, "warn", "thermometer", "Temperature ${Scoring.signed(dev)} °C from your usual", if (dev > 0) "A raised temperature can mean you're coming down with something, or your cycle, alcohol or a warm room. Worth keeping an eye on." else "A lower temperature than usual; often just a cool room.")
            }
        }

        // Sleep.
        today.sleep?.let { night ->
            val usual = Scoring.median(recent.mapNotNull { it.sleep?.asleepMinutes?.toDouble() })
            if (usual != null && recent.count { it.sleep != null } >= 4 && usual - night.asleepMinutes >= 45) {
                add(70, "info", "moon", "${Scoring.hm((usual - night.asleepMinutes).roundToInt())} less sleep than usual", "Try winding down a little earlier tonight.")
            }
            if (night.staged && night.deepMinutes < 30 && night.asleepMinutes >= 300) {
                add(40, "info", "moon", "Not much deep sleep", "Regular bedtimes, a cool dark room and no alcohol late in the evening all help deep sleep.")
            }
        }
        val slept = recent.mapNotNull { it.sleep?.asleepMinutes } + listOfNotNull(today.sleep?.asleepMinutes)
        if (slept.size >= 5) {
            val debt = goals.sleepMinutes - slept.average()
            if (debt >= 45) add(75, "warn", "moon", "You're short on sleep", "You've averaged ${Scoring.hm(slept.average().roundToInt())} a night lately, ${Scoring.hm(debt.roundToInt())} under your goal.")
        }

        // Activity.
        val steps = today.steps ?: 0
        if (goals.steps > 0 && steps >= goals.steps) {
            add(55, "good", "footprints", "Step goal reached", "%,d steps%s.".format(steps, if (steps >= goals.steps * 1.5) ": well beyond your goal" else ""))
        } else if (c.isToday && c.hourNow >= 17 && goals.steps > 0 && steps in 1 until goals.steps / 2) {
            add(52, "info", "footprints", "%,d steps to go".format(goals.steps - steps), "An evening walk would get you there.")
        }
        val week = c.history.takeLast(6) + today
        val sessions = week.sumOf { it.workouts }
        if (goals.workoutsPerWeek > 0 && sessions >= goals.workoutsPerWeek && today.workouts > 0) {
            add(50, "good", "dumbbell", "Weekly workouts done", "$sessions workouts in the last 7 days: your goal is ${goals.workoutsPerWeek}.")
        }

        // Food and water.
        val eaten = today.nutrition
        val late = !c.isToday || c.hourNow >= 19
        if (eaten != null && late) {
            if (eaten.protein < c.target.protein * 0.7) {
                add(58, "info", "egg", "Protein ${(c.target.protein - eaten.protein).roundToInt()} g under target", "Protein helps you recover and keeps you full: eggs, yoghurt, beans, tofu, fish or chicken.")
            }
            if (eaten.calories > c.target.calories * 1.2) {
                add(56, "info", "flame", "%,d kcal over your target".format((eaten.calories - c.target.calories).roundToInt()), "One day doesn't change much; your trend over a week is what counts.")
            }
        }
        if (c.isToday && c.hourNow >= 16 && today.waterMl < c.target.waterMl * 0.5) {
            add(48, "info", "droplet", "Time for some water", "${today.waterMl} ml so far of your ${c.target.waterMl} ml.")
        }

        // Weight trend over two weeks.
        val weights = (c.history.takeLast(21) + today).mapNotNull { day -> day.weightKg?.let { day.date to it } }
        if (weights.size >= 3) {
            val change = weights.last().second - weights.first().second
            val aim = goals.weeklyChangeKg
            if (abs(change) >= 0.5) {
                val towards = (aim < 0 && change < 0) || (aim > 0 && change > 0)
                add(
                    if (towards) 47 else 35, if (towards) "good" else "info", "scale",
                    "%s %.1f kg in three weeks".format(if (change < 0) "Down" else "Up", abs(change)),
                    if (towards) "Right on track for your goal." else "Weight changes day to day with water and food; the trend is what matters.",
                )
            }
        }

        if (!today.hasData && c.history.none { it.hasData }) {
            add(100, "info", "sparkles", "Welcome!", "Connect a ring or watch with the phone app, or start logging meals, weight and workouts. Your scores appear as your data comes in.")
        } else if (c.readiness == null && c.history.count { it.restingHr != null || it.hrv != null } < 3 && today.sleep != null) {
            add(30, "info", "sparkles", "Learning your baseline", "Readiness compares your heart to your own usual; it needs a few nights of data first.")
        }

        c.extra.forEach { (priority, insight) -> out += Ranked(priority, insight) }
        return out.sortedByDescending { it.priority }.take(5).map { it.insight }
    }
}
