package com.health.server.modules

import com.health.api.Cycle
import com.health.api.CycleDay
import com.health.api.CycleOverview
import com.health.api.CyclePhase
import com.health.api.CyclePrediction
import com.health.api.CycleToday
import com.health.api.DatedValue
import com.health.api.Flow
import com.health.server.ApiError
import com.health.server.health.ProfileStore
import com.health.server.insights.DayService
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Periods and symptoms, and what they say about the cycle and the next period. */
class CycleService(
    private val entries: EntryStore,
    private val profiles: ProfileStore,
    private val days: DayService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The person's life stage, when it isn't simply tracking a cycle (care/LifeStages): predictions pause or widen. */
    var stageOf: (suspend (userId: String) -> com.health.api.LifeStageInfo?)? = null

    suspend fun put(userId: String, day: CycleDay): CycleDay {
        val date = runCatching { LocalDate.parse(day.date) }.getOrElse { throw ApiError.badRequest("Dates look like 2026-01-31") }
        day.pain?.let { if (it !in 0..10) throw ApiError.badRequest("Pain is from 0 to 10") }
        day.temperature?.let { if (it !in 34.0..42.0) throw ApiError.badRequest("Basal temperature is in °C, between 34 and 42") }
        val clean = day.copy(
            date = date.toString(),
            symptoms = day.symptoms.map { it.trim().lowercase().take(40) }.filter { it.isNotEmpty() }.distinct().take(30),
            note = day.note?.trim()?.take(2000)?.ifBlank { null },
        )
        entries.deleteDay(userId, EntryKind.CYCLE_DAY, date)
        val empty = clean.flow == null && clean.symptoms.isEmpty() && clean.pain == null && clean.mucus == null &&
            clean.ovulationTest == null && clean.temperature == null && clean.note == null
        if (!empty) {
            val noon = profiles.days(userId).let { it.start(date) + 12 * 3_600_000L }
            entries.put(userId, EntryKind.CYCLE_DAY, null, date, noon, clean, CycleDay.serializer())
        }
        return clean
    }

    suspend fun delete(userId: String, date: LocalDate) {
        entries.deleteDay(userId, EntryKind.CYCLE_DAY, date)
    }

    suspend fun logged(userId: String, from: LocalDate, to: LocalDate): List<CycleDay> =
        entries.list(userId, EntryKind.CYCLE_DAY, from, to, CycleDay.serializer()).map { it.value }

    /** Cycles over the last [months], and where things stand on [asOf] (today unless given). */
    suspend fun overview(userId: String, months: Int, asOf: LocalDate? = null): CycleOverview {
        val zone = profiles.days(userId)
        val today = asOf ?: zone.today(clock())
        val from = today.minusMonths(months.coerceIn(1, 60).toLong())
        val logged = logged(userId, from, today.plusDays(60))
        // Nightly temperatures from a ring or watch, for the last four months.
        val tempFrom = maxOf(from, today.minusDays(120))
        val nightly = days.stats(userId, tempFrom, today, zone).mapNotNull { s ->
            (s.tempDeviation ?: s.skinTemp)?.let { DatedValue(s.date, it) }
        }
        val overview = CycleMath.analyze(logged, nightly, today)
        return stageOf?.invoke(userId)?.let { com.health.server.care.LifeStages.adjust(overview, it) } ?: overview
    }

    suspend fun today(userId: String, date: LocalDate): CycleToday {
        val o = overview(userId, 6, date)
        return CycleToday(o.cycleDay, o.phase, o.daysUntilPeriod, o.days.firstOrNull { it.date == date.toString() })
    }
}

/** The arithmetic of cycles: pure, so it can be tested on its own. */
object CycleMath {
    /** A gap of up to this many days still counts as the same period (a day without bleeding mid-period). */
    private const val PERIOD_GAP = 2
    private const val SHORTEST = 15
    private const val LONGEST = 90

    fun analyze(days: List<CycleDay>, nightly: List<DatedValue>, today: LocalDate): CycleOverview {
        val sorted = days.sortedBy { it.date }
        val bleeding = sorted.filter { it.flow != null && it.flow != Flow.SPOTTING && !LocalDate.parse(it.date).isAfter(today) }
            .map { LocalDate.parse(it.date) }

        // Periods: runs of bleeding days.
        val periods = mutableListOf<MutableList<LocalDate>>()
        bleeding.forEach { d ->
            val last = periods.lastOrNull()?.last()
            if (last != null && ChronoUnit.DAYS.between(last, d) <= PERIOD_GAP) periods.last() += d else periods += mutableListOf(d)
        }

        // Temperatures: basal temperatures where there are any, else the device's nightly figure.
        val bbt = sorted.mapNotNull { d -> d.temperature?.let { DatedValue(d.date, it) } }
        val temps = if (bbt.size >= 6) bbt else nightly

        val cycles = periods.mapIndexed { i, p ->
            val start = p.first()
            val next = periods.getOrNull(i + 1)?.first()
            val end = next?.minusDays(1) ?: today
            Cycle(
                start = start.toString(),
                length = next?.let { ChronoUnit.DAYS.between(start, it).toInt() },
                periodLength = ChronoUnit.DAYS.between(start, p.last()).toInt() + 1,
                ovulation = ovulation(temps, start, end)?.toString(),
            )
        }
        val lengths = cycles.mapNotNull { it.length }.filter { it in SHORTEST..LONGEST }.takeLast(6)
        val average = lengths.takeIf { it.isNotEmpty() }?.average()
        val variation = lengths.takeIf { it.size >= 2 }?.let { l ->
            val mean = l.average()
            sqrt(l.sumOf { (it - mean) * (it - mean) } / (l.size - 1))
        }
        val averagePeriod = cycles.takeLast(6).map { it.periodLength }.takeIf { it.isNotEmpty() }?.average()

        val lastStart = periods.lastOrNull()?.first()
        val current = lastStart?.takeIf { ChronoUnit.DAYS.between(it, today) <= LONGEST }
        var prediction: CyclePrediction? = null
        var phase = CyclePhase.UNKNOWN
        var cycleDay: Int? = null
        var until: Int? = null
        if (current != null) {
            val length = (average ?: 28.0).roundToInt()
            val next = current.plusDays(length.toLong())
            val spread = when {
                lengths.size < 3 -> 4
                else -> maxOf(2, (variation ?: 2.0).roundToInt())
            }
            val confidence = when {
                lengths.size < 3 -> "low"
                (variation ?: 99.0) <= 3.0 -> "high"
                else -> "medium"
            }
            val detected = cycles.last().ovulation?.let(LocalDate::parse)
            val ovulation = detected ?: next.minusDays(14)
            prediction = CyclePrediction(
                nextPeriod = next.toString(),
                earliest = next.minusDays(spread.toLong()).toString(),
                latest = next.plusDays(spread.toLong()).toString(),
                fertileStart = ovulation.minusDays(5).toString(),
                fertileEnd = ovulation.plusDays(1).toString(),
                ovulation = ovulation.toString(),
                confidence = confidence,
            )
            cycleDay = ChronoUnit.DAYS.between(current, today).toInt() + 1
            until = ChronoUnit.DAYS.between(today, next).toInt()
            val periodEnd = periods.last().last()
            val bleedingToday = sorted.any { it.date == today.toString() && it.flow != null && it.flow != Flow.SPOTTING }
            phase = when {
                bleedingToday || (!today.isAfter(periodEnd)) -> CyclePhase.PERIOD
                !today.isBefore(ovulation.minusDays(5)) && !today.isAfter(ovulation.plusDays(1)) -> CyclePhase.FERTILE
                today.isBefore(ovulation) -> CyclePhase.FOLLICULAR
                else -> CyclePhase.LUTEAL
            }
        }
        val shown = temps.filter { !LocalDate.parse(it.date).isBefore(today.minusDays(120)) }
        return CycleOverview(
            days = sorted,
            cycles = cycles,
            averageCycle = average?.let { (it * 10).roundToInt() / 10.0 },
            averagePeriod = averagePeriod?.let { (it * 10).roundToInt() / 10.0 },
            variation = variation?.let { (it * 10).roundToInt() / 10.0 },
            prediction = prediction,
            cycleDay = cycleDay,
            phase = phase,
            daysUntilPeriod = until,
            temperatures = shown,
        )
    }

    /**
     * Ovulation from temperatures, by the "three over six" rule: the first three days running that
     * are all at least 0.1 °C above the highest of the six days before them; ovulation was the
     * day before the rise. Null when the data doesn't show one.
     */
    fun ovulation(temps: List<DatedValue>, start: LocalDate, end: LocalDate): LocalDate? {
        val inCycle = temps.filter { val d = LocalDate.parse(it.date); !d.isBefore(start) && !d.isAfter(end) }.sortedBy { it.date }
        for (i in 6..inCycle.size - 3) {
            val base = inCycle.subList(i - 6, i).maxOf { it.value }
            if ((i until i + 3).all { inCycle[it].value >= base + 0.1 }) return LocalDate.parse(inCycle[i].date).minusDays(1)
        }
        return null
    }
}
