package com.health.server.neuro

import com.health.api.CheckTag
import com.health.api.EnergyCheck
import com.health.api.EnergyFactor
import com.health.api.FactorKind
import com.health.api.TimeBlock
import com.health.server.util.Days
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Energy and focus patterns from quick check-ins, as pure functions.
 *
 * The check-ins are ecological momentary assessment: ratings in the moment, a few times a day,
 * the way research measures how ADHD symptoms and energy really vary (rather than how a day is
 * remembered). Patterns compare the person's own check-ins with each other: by time of day, by
 * hours since their ADHD medication, and with or without enough sleep, recent movement, a recent
 * meal, or stress. They're associations in one person's data, never claims of cause, and only
 * shown with enough check-ins on both sides and a difference worth noticing.
 */
object EnergyPatterns {
    /** Patterns start to show from this many check-ins over this many days. */
    const val MIN_CHECKS = 12
    const val MIN_DAYS = 5
    /** A block of hours needs this many check-ins to say anything. */
    const val BLOCK_MIN = 3
    /** Day-level comparisons need this many days on each side; check-level ones this many check-ins. */
    const val SIDE_DAYS = 4
    const val SIDE_CHECKS = 6
    /** A difference this big on the 1–5 scales is worth showing. */
    const val DIFFERENCE = 0.5

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /** The day in three-hour blocks (the small hours as one). */
    val DAY_BLOCKS = listOf(0 to 6, 6 to 9, 9 to 12, 12 to 15, 15 to 18, 18 to 21, 21 to 24)

    /** Hours since the day's first dose. */
    val DOSE_BLOCKS = listOf(0 to 1, 1 to 3, 3 to 5, 5 to 7, 7 to 9, 9 to 12)

    class Input(
        val checks: List<EnergyCheck>,
        val zone: Days,
        /** When the ADHD medication was taken. */
        val doses: List<Long> = emptyList(),
        val medication: String? = null,
        /** Minutes asleep in the night that ended on each date. */
        val sleep: Map<LocalDate, Int> = emptyMap(),
        /** When food was logged as eaten. */
        val meals: List<Long> = emptyList(),
        val workouts: List<LongRange> = emptyList(),
        /** Step counts over their spans. */
        val steps: List<Pair<LongRange, Double>> = emptyList(),
    )

    class Result(
        val enough: Boolean,
        val needed: String?,
        val byHour: List<TimeBlock>,
        val sinceDose: List<TimeBlock>,
        val bestFocus: TimeBlock?,
        val factors: List<EnergyFactor>,
        val summary: List<String>,
        val days: Int,
    )

    fun analyse(i: Input): Result {
        val rated = i.checks.filter { it.energy != null || it.focus != null }
        val days = rated.map { i.zone.dateOf(it.at) }.distinct().size
        val enough = rated.size >= MIN_CHECKS && days >= MIN_DAYS
        if (!enough) {
            val more = (MIN_CHECKS - rated.size).coerceAtLeast(0)
            val moreDays = (MIN_DAYS - days).coerceAtLeast(0)
            val needed = listOfNotNull(
                more.takeIf { it > 0 }?.let { "$it more check-in${if (it == 1) "" else "s"}" },
                moreDays.takeIf { it > 0 }?.let { "over $it more day${if (it == 1) "" else "s"}" },
            ).joinToString(" ").ifBlank { null }?.let { "$it and your patterns start to show." }
            return Result(false, needed, emptyList(), emptyList(), null, emptyList(), emptyList(), days)
        }
        val byHour = blocks(rated, DAY_BLOCKS) { c -> i.zone.hourOf(c.at).toDouble() + (i.zone.minuteOfDay(c.at) % 60) / 60.0 }
        val overallEnergy = mean(rated.mapNotNull { it.energy })
        val overallFocus = mean(rated.mapNotNull { it.focus })
        val best = byHour.filter { it.focus != null && it.count >= BLOCK_MIN }.maxByOrNull { it.focus!! }
            ?.takeIf { overallFocus != null && it.focus!! >= overallFocus + DIFFERENCE }
        val dip = byHour.filter { it.energy != null && it.count >= BLOCK_MIN }.minByOrNull { it.energy!! }
            ?.takeIf { overallEnergy != null && it.energy!! <= overallEnergy - DIFFERENCE }
        val sinceDose = sinceDose(rated, i)
        val factors = factors(rated, i).sortedByDescending { abs(it.with - it.without) }

        val summary = buildList {
            best?.let { add("Your focus is usually best ${range(it.from, it.to)}: a good time for whatever needs it most.") }
            dip?.let { add("Your energy tends to dip ${range(it.from, it.to)}.") }
            doseSentence(sinceDose, i.medication)?.let(::add)
            factors.forEach { add(it.sentence) }
        }
        return Result(true, null, byHour, sinceDose, best, factors, summary, days)
    }

    /** Averages per block of hours, for blocks with any check-ins. */
    private fun blocks(checks: List<EnergyCheck>, spans: List<Pair<Int, Int>>, hourOf: (EnergyCheck) -> Double?): List<TimeBlock> =
        spans.mapNotNull { (from, to) ->
            val inBlock = checks.filter { c -> hourOf(c)?.let { it >= from && it < to } == true }
            if (inBlock.isEmpty()) null
            else TimeBlock(from, to, mean(inBlock.mapNotNull { it.energy })?.let(::round), mean(inBlock.mapNotNull { it.focus })?.let(::round), inBlock.size)
        }

    /** Focus and energy by hours since the day's first dose, for check-ins after one. */
    fun sinceDose(checks: List<EnergyCheck>, i: Input): List<TimeBlock> {
        if (i.doses.isEmpty()) return emptyList()
        val firstByDay = i.doses.groupBy { i.zone.dateOf(it) }.mapValues { (_, l) -> l.min() }
        return blocks(checks, DOSE_BLOCKS) { c ->
            firstByDay[i.zone.dateOf(c.at)]?.takeIf { it <= c.at }?.let { (c.at - it).toDouble() / HOUR }
        }.filter { it.count >= BLOCK_MIN }
    }

    private fun doseSentence(blocks: List<TimeBlock>, medication: String?): String? {
        val withFocus = blocks.filter { it.focus != null }
        if (withFocus.size < 3) return null
        val peak = withFocus.maxBy { it.focus!! }
        val after = withFocus.filter { it.from >= peak.to }.firstOrNull { it.focus!! <= peak.focus!! - 1.0 }
        val what = medication?.let { "your $it" } ?: "your ADHD medication"
        return "Your focus is usually highest ${peak.from}–${peak.to} hours after $what" +
            (after?.let { ", and lower again from about ${it.from} hours." } ?: ".")
    }

    /** What goes with better or worse energy or focus, where both sides have enough and the gap is worth noticing. */
    fun factors(checks: List<EnergyCheck>, i: Input): List<EnergyFactor> {
        val out = mutableListOf<EnergyFactor>()
        val byDay = checks.groupBy { i.zone.dateOf(it.at) }

        fun dayMeans(dates: Collection<LocalDate>, measure: (EnergyCheck) -> Int?): List<Double> =
            dates.mapNotNull { d -> mean(byDay[d].orEmpty().mapNotNull(measure)) }

        // Sleep: the night before, from the ring or by hand.
        val rested = byDay.keys.filter { (i.sleep[it] ?: -1) >= 7 * 60 }
        val short = byDay.keys.filter { i.sleep[it]?.let { m -> m in 1 until 6 * 60 } == true }
        compare(dayMeans(rested) { it.energy }, dayMeans(short) { it.energy }, SIDE_DAYS)?.let { (a, b) ->
            out += EnergyFactor(
                FactorKind.SLEEP, "Sleep",
                "After 7 hours' sleep or more, your energy averaged ${f(a.mean)}, against ${f(b.mean)} after less than 6 (${a.n} and ${b.n} days).",
                "energy", a.mean, b.mean, a.n, b.n,
            )
        }

        // The ADHD medication: days taken and days not.
        if (i.doses.isNotEmpty()) {
            val dosed = i.doses.map { i.zone.dateOf(it) }.toSet()
            compare(dayMeans(byDay.keys.filter { it in dosed }) { it.focus }, dayMeans(byDay.keys.filter { it !in dosed }) { it.focus }, SIDE_DAYS)?.let { (a, b) ->
                val what = i.medication ?: "your ADHD medication"
                out += EnergyFactor(
                    FactorKind.MEDICATION, "Your medication",
                    "On days you took $what, your focus averaged ${f(a.mean)}, against ${f(b.mean)} on days you didn't (${a.n} and ${b.n} days).",
                    "focus", a.mean, b.mean, a.n, b.n,
                )
            }
        }

        // Moving: a workout in the three hours before, or a brisk hour of steps.
        fun moved(c: EnergyCheck): Boolean {
            val from = c.at - 3 * HOUR
            if (i.workouts.any { it.first <= c.at && it.last >= from }) return true
            val hour = c.at - HOUR..c.at
            val steps = i.steps.sumOf { (span, n) ->
                val overlap = (minOf(span.last, hour.last) - maxOf(span.first, hour.first)).coerceAtLeast(0)
                val length = (span.last - span.first).coerceAtLeast(1)
                n * overlap / length
            }
            return steps >= 1500
        }
        checkCompare(checks, ::moved) { it.focus }?.let { (a, b) ->
            out += EnergyFactor(
                FactorKind.MOVEMENT, "Moving",
                "Within a few hours of moving, your focus averaged ${f(a.mean)}, against ${f(b.mean)} otherwise (${a.n} and ${b.n} check-ins).",
                "focus", a.mean, b.mean, a.n, b.n,
            )
        }

        // Eating in the three hours before.
        if (i.meals.isNotEmpty()) {
            checkCompare(checks, { c -> i.meals.any { it in (c.at - 3 * HOUR)..c.at } }) { it.energy }?.let { (a, b) ->
                out += EnergyFactor(
                    FactorKind.MEAL, "Eating",
                    "Within 3 hours of eating, your energy averaged ${f(a.mean)}, against ${f(b.mean)} when it had been longer (${a.n} and ${b.n} check-ins).",
                    "energy", a.mean, b.mean, a.n, b.n,
                )
            }
        }

        // Stress, as the person tagged it.
        checkCompare(checks, { CheckTag.STRESSED in it.tags }) { it.focus }?.let { (a, b) ->
            out += EnergyFactor(
                FactorKind.STRESS, "Stress",
                "When you felt stressed, your focus averaged ${f(a.mean)}, against ${f(b.mean)} otherwise (${a.n} and ${b.n} check-ins).",
                "focus", a.mean, b.mean, a.n, b.n,
            )
        }
        return out
    }

    data class Side(val mean: Double, val n: Int)

    private fun compare(with: List<Double>, without: List<Double>, min: Int): Pair<Side, Side>? {
        if (with.size < min || without.size < min) return null
        val a = Side(round(with.average()), with.size)
        val b = Side(round(without.average()), without.size)
        return (a to b).takeIf { abs(a.mean - b.mean) >= DIFFERENCE }
    }

    private fun checkCompare(checks: List<EnergyCheck>, test: (EnergyCheck) -> Boolean, measure: (EnergyCheck) -> Int?): Pair<Side, Side>? {
        val (yes, no) = checks.filter { measure(it) != null }.partition(test)
        return compare(yes.map { measure(it)!!.toDouble() }, no.map { measure(it)!!.toDouble() }, SIDE_CHECKS)
    }

    // ---- Words ----

    /** "9 AM – noon", "3 – 6 PM", "midnight – 6 AM". */
    fun range(from: Int, to: Int): String {
        fun clock(h: Int) = when (h % 24) {
            0 -> "midnight"
            12 -> "noon"
            in 1..11 -> "${h % 24} AM"
            else -> "${h % 24 - 12} PM"
        }
        val a = clock(from)
        val b = clock(to)
        val samePart = from in 13..23 && to in 13..24 && to != 24 || from in 1..11 && to in 1..11
        return if (samePart) "${a.substringBefore(' ')} – $b" else "$a – $b"
    }

    private fun f(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    private fun mean(values: List<Int>): Double? = values.takeIf { it.isNotEmpty() }?.average()
    private fun round(v: Double) = (v * 10).roundToInt() / 10.0

    const val ABOUT = "Quick check-ins through the day show how your energy and focus actually move, rather than how a day is remembered: " +
        "it's how researchers measure it too (ecological momentary assessment). Your patterns compare your own check-ins with each other, " +
        "and with your sleep, doses, meals and movement. They're patterns in your data, not proof of what causes what, and they get more " +
        "reliable the more you check in. Putting the hardest things into your best hours is part of the therapies for adult ADHD with the " +
        "best evidence. Sleep and ADHD are closely linked both ways, and exercise's short-term boost to attention has mostly been studied " +
        "in children and young people. Take patterns about your medication to your prescriber rather than changing a dose yourself."

    val SOURCES = listOf(
        "Shiffman, Stone & Hufford (2008). Ecological momentary assessment. Annual Review of Clinical Psychology, 4, 1–32.",
        "Safren et al. (2010). Cognitive behavioral therapy vs relaxation with educational support for medication-treated adults with ADHD and persistent symptoms. JAMA, 304(8), 875–880.",
        "Solanto et al. (2010). Efficacy of meta-cognitive therapy for adult ADHD. American Journal of Psychiatry, 167(8), 958–968.",
        "Hvolby (2015). Associations of sleep disturbance with ADHD: implications for treatment. ADHD Attention Deficit and Hyperactivity Disorders, 7(1), 1–18.",
        "Cerrillo-Urbina et al. (2015). The effects of physical exercise in children with ADHD: a systematic review and meta-analysis of randomized controlled trials. Child: Care, Health and Development, 41(6), 779–788.",
    )
}
