package com.health.server.modules

import com.health.api.Cycle
import com.health.api.CycleDay
import com.health.api.CycleOverview
import com.health.api.CyclePhase
import com.health.api.CyclePrediction
import com.health.api.CycleSettings
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

    /** Period products used come off their supplies' counts. Set at start-up. */
    var supplies: SuppliesService? = null

    /** The person's own settings. Set at start-up. */
    var settings: UserSettingsStore? = null

    suspend fun put(userId: String, day: CycleDay): CycleDay {
        val date = runCatching { LocalDate.parse(day.date) }.getOrElse { throw ApiError.badRequest("Dates look like 2026-01-31") }
        fun check(ok: Boolean, message: String) { if (!ok) throw ApiError.badRequest(message) }
        day.pain?.let { check(it in 0..10, "Pain is from 0 to 10") }
        day.temperature?.let { check(it in 34.0..42.0, "Basal temperature is in °C, between 34 and 42") }
        day.energy?.let { check(it in 1..5, "Energy is from 1 to 5") }
        day.libido?.let { check(it in 1..5, "Sex drive is from 1 to 5") }
        day.lh?.let { check(it in 0.0..500.0, "An LH reading is 0 to 500") }
        check(day.symptomSeverity.values.all { it in 1..3 }, "How bad is 1 (mild) to 3 (severe)")
        check(day.products.size <= 10 && day.products.all { it.count in 1..20 }, "Up to 10 products, 1 to 20 of each")
        check(day.details.size <= 30, "Up to 30 of your own fields")
        fun words(list: List<String>, max: Int = 30) = list.map { it.trim().lowercase().take(40) }.filter { it.isNotEmpty() }.distinct().take(max)
        day.products.mapNotNull { it.supplyId }.forEach { id -> check(supplies == null || supplies!!.get(userId, id) != null, "No such supply") }
        val clean = day.copy(
            date = date.toString(),
            symptoms = words(day.symptoms),
            note = day.note?.trim()?.take(2000)?.ifBlank { null },
            painAreas = words(day.painAreas, 20),
            symptomSeverity = day.symptomSeverity.entries.associate { (k, v) -> k.trim().lowercase().take(40) to v }.filterKeys { it.isNotEmpty() }.entries.take(30).associate { it.toPair() },
            mood = words(day.mood, 20),
            tags = words(day.tags, 20),
            color = day.color?.trim()?.lowercase()?.take(30)?.ifBlank { null },
            discharge = day.discharge?.trim()?.take(60)?.ifBlank { null },
            sex = day.sex?.let { it.copy(note = it.note?.trim()?.take(500)?.ifBlank { null }) },
            details = day.details.entries.associate { (k, v) -> k.trim().take(40) to v.trim().take(500) }.filterKeys { it.isNotEmpty() },
        )
        entries.deleteDay(userId, EntryKind.CYCLE_DAY, date)
        val empty = clean == CycleDay(date = clean.date)
        val noon = profiles.days(userId).let { it.start(date) + 12 * 3_600_000L }
        if (!empty) {
            entries.put(userId, EntryKind.CYCLE_DAY, null, date, noon, clean, CycleDay.serializer())
        }
        supplies?.syncUse(userId, source(date), usesOf(clean), noon)
        return clean
    }

    suspend fun delete(userId: String, date: LocalDate) {
        entries.deleteDay(userId, EntryKind.CYCLE_DAY, date)
        supplies?.syncUse(userId, source(date), emptyMap(), clock())
    }

    suspend fun settings(userId: String): CycleSettings = settings?.get(userId, SETTINGS_KEY, CycleSettings.serializer()) ?: CycleSettings()

    suspend fun putSettings(userId: String, input: CycleSettings): CycleSettings {
        fun check(ok: Boolean, message: String) { if (!ok) throw ApiError.badRequest(message) }
        input.cycleLength?.let { check(it in 15..90, "A cycle is 15 to 90 days") }
        input.periodLength?.let { check(it in 1..15, "A period is 1 to 15 days") }
        input.lutealLength?.let { check(it in 7..20, "The time after ovulation is 7 to 20 days") }
        input.remindBeforePeriod?.let { check(it in 0..14, "Remind 0 to 14 days before") }
        input.remindLateAfter?.let { check(it in 1..30, "Remind 1 to 30 days late") }
        input.dailyReminder?.let { check(runCatching { java.time.LocalTime.parse(it) }.isSuccess, "The daily reminder looks like 20:30") }
        check(input.customFields.size <= 30, "Up to 30 of your own fields")
        fun words(list: List<String>) = list.map { it.trim().lowercase().take(40) }.filter { it.isNotEmpty() }.distinct().take(60)
        val clean = input.copy(
            symptoms = words(input.symptoms), hiddenSymptoms = words(input.hiddenSymptoms), moods = words(input.moods), painAreas = words(input.painAreas),
            customFields = input.customFields.distinctBy { it.key }.map { it.copy(key = it.key.trim().take(40), label = it.label.trim().take(60)) },
        )
        return settings?.put(userId, SETTINGS_KEY, clean, CycleSettings.serializer()) ?: clean
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
        val own = runCatching { settings(userId) }.getOrNull()
        val overview = CycleMath.analyze(logged, nightly, today, own?.cycleLength, own?.periodLength, own?.lutealLength)
        return stageOf?.invoke(userId)?.let { com.health.server.care.LifeStages.adjust(overview, it) } ?: overview
    }

    suspend fun today(userId: String, date: LocalDate): CycleToday {
        val o = overview(userId, 6, date)
        return CycleToday(o.cycleDay, o.phase, o.daysUntilPeriod, o.days.firstOrNull { it.date == date.toString() })
    }

    companion object {
        const val SETTINGS_KEY = "cycle"

        fun source(date: LocalDate) = "cycle:$date"

        fun usesOf(day: CycleDay): Map<String, Int> =
            day.products.filter { it.supplyId != null }.groupBy { it.supplyId!! }.mapValues { (_, l) -> l.sumOf { it.count } }
    }
}

/** The arithmetic of cycles: pure, so it can be tested on its own. */
object CycleMath {
    /** A gap of up to this many days still counts as the same period (a day without bleeding mid-period). */
    private const val PERIOD_GAP = 2
    private const val SHORTEST = 15
    private const val LONGEST = 90

    /**
     * [cycleLength], [periodLength] and [lutealLength] are the person's own figures, used instead of
     * the averages (and the usual 14 days from ovulation to a period) when given.
     */
    fun analyze(
        days: List<CycleDay>,
        nightly: List<DatedValue>,
        today: LocalDate,
        cycleLength: Int? = null,
        periodLength: Int? = null,
        lutealLength: Int? = null,
    ): CycleOverview {
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
            val length = cycleLength ?: (average ?: 28.0).roundToInt()
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
            val ovulation = detected ?: next.minusDays((lutealLength ?: 14).toLong())
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
            averagePeriod = (averagePeriod ?: periodLength?.toDouble())?.let { (it * 10).roundToInt() / 10.0 },
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
