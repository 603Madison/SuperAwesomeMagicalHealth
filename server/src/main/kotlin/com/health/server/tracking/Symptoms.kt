package com.health.server.tracking

import com.health.api.Correlation
import com.health.api.Count
import com.health.api.Insight
import com.health.api.Migraine
import com.health.api.Module
import com.health.api.Profile
import com.health.api.SymptomEntry
import com.health.api.SymptomsDay
import com.health.api.SymptomsSummary
import com.health.server.ApiError
import com.health.server.health.ProfileStore
import com.health.server.insights.DayService
import com.health.server.insights.Stress
import com.health.server.modules.CycleMath
import com.health.server.modules.CycleService
import com.health.server.modules.EntryKind
import com.health.server.modules.EntryStore
import com.health.server.util.Days
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Symptoms, pain and migraines: sealed entries, like the other sensitive features. The summary
 * looks for what went with symptom days in the person's own data: short nights, alcohol, caffeine,
 * stressful days, the days around a period, and falling air pressure.
 */
class SymptomsService(
    private val entries: EntryStore,
    private val profiles: ProfileStore,
    private val days: DayService,
    private val drinks: DrinksService,
    private val cycle: CycleService,
    private val weather: Weather,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // ---- Symptoms ----

    suspend fun entries(userId: String, from: LocalDate, to: LocalDate): List<SymptomEntry> =
        entries.list(userId, EntryKind.SYMPTOM, from, to, SymptomEntry.serializer()).map { it.value.copy(id = it.id, date = it.date.toString()) }

    suspend fun entries(userId: String, count: Int): List<SymptomEntry> {
        val (from, to) = range(userId, count)
        return entries(userId, from, to).reversed()
    }

    suspend fun save(userId: String, input: SymptomEntry, id: String? = null): SymptomEntry {
        val entry = validated(input)
        val date = profiles.days(userId).dateOf(entry.at)
        val saved = entries.put(userId, EntryKind.SYMPTOM, id, date, entry.at, entry, SymptomEntry.serializer())
        return entry.copy(id = saved, date = date.toString())
    }

    // ---- Migraines ----

    suspend fun migraines(userId: String, from: LocalDate, to: LocalDate): List<Migraine> =
        entries.list(userId, EntryKind.MIGRAINE, from, to, Migraine.serializer()).map { it.value.copy(id = it.id, date = it.date.toString()) }

    suspend fun migraines(userId: String, count: Int): List<Migraine> {
        val (from, to) = range(userId, count)
        return migraines(userId, from, to).reversed()
    }

    suspend fun saveMigraine(userId: String, input: Migraine, id: String? = null): Migraine {
        val m = validated(input, clock())
        val date = profiles.days(userId).dateOf(m.start)
        val saved = entries.put(userId, EntryKind.MIGRAINE, id, date, m.start, m, Migraine.serializer())
        return m.copy(id = saved, date = date.toString())
    }

    suspend fun delete(userId: String, kind: EntryKind, id: String) {
        entries.delete(userId, kind, id)
    }

    // ---- Days and the summary ----

    suspend fun daysOf(userId: String, from: LocalDate, to: LocalDate): Map<LocalDate, SymptomsDay> {
        val s = entries(userId, from, to).groupBy { LocalDate.parse(it.date!!) }
        val m = migraines(userId, from, to).groupBy { LocalDate.parse(it.date!!) }
        return (s.keys + m.keys).associateWith { date ->
            val list = s[date].orEmpty()
            val migraines = m[date].orEmpty()
            SymptomsDay(date.toString(), list, migraines, (list.map { it.severity } + migraines.map { it.severity }).maxOrNull())
        }
    }

    suspend fun summary(userId: String, count: Int): SymptomsSummary {
        val profile = profiles.get(userId)
        val zone = Days(Days.zoneOf(profile.timezone))
        val to = zone.today(clock())
        val from = to.minusDays((count.coerceIn(7, 730) - 1).toLong())
        val byDay = daysOf(userId, from, to)
        val list = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { byDay[it] ?: SymptomsDay(it.toString()) }.toList()
        val all = list.flatMap { it.entries }
        val migraines = list.flatMap { it.migraines }
        val counts = (all.map { it.symptom } + migraines.map { "migraine" }).groupingBy { it }.eachCount()
            .map { (name, n) -> Count(name, n) }.sortedByDescending { it.count }
        val triggers = (all.flatMap { it.triggers } + migraines.flatMap { it.triggers }).groupingBy { it }.eachCount()
            .map { (name, n) -> Count(name, n) }.sortedByDescending { it.count }.take(12)
        val factors = factors(userId, profile, zone, from, to)
        return SymptomsSummary(
            days = list,
            counts = counts,
            migraineDays = list.count { it.migraines.isNotEmpty() },
            triggers = triggers,
            correlations = correlate(list.associate { LocalDate.parse(it.date) to isSymptomDay(it) }, factors.days),
            weather = factors.weather,
        )
    }

    /** Today's part: the day's entries, and a few kind words where they help. */
    suspend fun insights(userId: String, date: LocalDate, day: SymptomsDay?, water: Boolean = true): List<Pair<Int, Insight>> = buildList {
        val open = migraines(userId, date.minusDays(2), date).firstOrNull { it.end == null }
        if (open != null) {
            // Someone who's turned water off may be keeping to a fluid limit: no "sip water", then.
            add(74 to Insight("info", "Migraine still going", "Rest somewhere dark and quiet if you can${if (water) ", and sip water" else ""}. Log when it ends, and what helped.", "zap", Module.SYMPTOMS))
        } else if (day != null && (day.worst ?: 0) >= 7) {
            add(60 to Insight("info", "A painful day", "Go gently. If the pain is new, severe or comes with other worrying symptoms, contact a doctor.", "heart", Module.SYMPTOMS))
        }
        val month = migraines(userId, date.minusDays(29), date).map { it.date }.distinct().size
        if (month >= 4) {
            add(38 to Insight("info", "$month migraine days this month", "That's worth talking to a doctor about: there are treatments that can make them less frequent.", "zap", Module.SYMPTOMS))
        }
    }

    // ---- What went with symptoms ----

    /** Each factor's days: day to whether it applied (missing where unknown). */
    class Factors(val days: Map<String, Map<LocalDate, Boolean>>, val weather: Boolean)

    private suspend fun factors(userId: String, profile: Profile, zone: Days, from: LocalDate, to: LocalDate): Factors {
        val out = linkedMapOf<String, Map<LocalDate, Boolean>>()
        val range = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        val stats = days.stats(userId, from.minusDays(30), to, zone)
        val byDate = stats.associateBy { LocalDate.parse(it.date) }
        // A short night before: under six hours asleep.
        out["short_sleep"] = range.mapNotNull { d -> byDate[d]?.sleep?.let { d to (it.asleepMinutes < 360) } }.toMap()
        // A stressful day before.
        val stress = stats.indices.associate { i -> LocalDate.parse(stats[i].date) to Stress.day(stats[i].date, stats[i].stressWindows, Stress.baseline(stats[i], stats.subList(0, i))) }
        out["stress"] = range.mapNotNull { d -> stress[d.minusDays(1)]?.let { d to (it.stressedMinutes >= 120) } }.toMap()
        // Drinks, where the person logs them.
        val drinkDays = drinks.daysOf(userId, from.minusDays(1), to)
        if (drinkDays.isNotEmpty()) {
            out["alcohol"] = range.associateWith { d -> (drinkDays[d.minusDays(1)]?.standardDrinks ?: 0.0) >= 2 }
            val caffeine = range.map { drinkDays[it]?.caffeineMg ?: 0.0 }
            val usual = caffeine.filter { it > 0 }.takeIf { it.size >= 5 }?.average()
            if (usual != null && usual >= 100) {
                out["more_caffeine"] = range.associateWith { d -> (drinkDays[d]?.caffeineMg ?: 0.0) >= usual * 1.5 }
                out["less_caffeine"] = range.associateWith { d -> (drinkDays[d]?.caffeineMg ?: 0.0) <= usual * 0.5 }
            }
        }
        // The days around a period, where the cycle is tracked.
        if (Module.CYCLE in profile.modules) {
            val starts = CycleMath.analyze(cycle.logged(userId, from.minusDays(60), to), emptyList(), to).cycles.map { LocalDate.parse(it.start) }
            if (starts.isNotEmpty()) out["period"] = range.associateWith { d -> starts.any { s -> !d.isBefore(s.minusDays(2)) && !d.isAfter(s.plusDays(2)) } }
        }
        // Falling air pressure, where the server may ask the weather.
        var usedWeather = false
        profile.place?.let { place ->
            val pressure = runCatching { weather.pressure(place, from.minusDays(1), to) }.getOrDefault(emptyMap())
            if (pressure.isNotEmpty()) {
                usedWeather = true
                out["pressure_drop"] = range.mapNotNull { d ->
                    val today = pressure[d]
                    val before = pressure[d.minusDays(1)]
                    if (today != null && before != null) d to (today - before <= -5.0) else null
                }.toMap()
            }
        }
        return Factors(out, usedWeather)
    }

    companion object {
        private val LABELS = mapOf(
            "short_sleep" to "after a night under six hours",
            "stress" to "after a stressful day",
            "alcohol" to "after two or more drinks the day before",
            "more_caffeine" to "with more caffeine than usual",
            "less_caffeine" to "with much less caffeine than usual",
            "period" to "in the days around a period",
            "pressure_drop" to "when the air pressure dropped",
        )

        /** A day counts when a symptom was at least moderate (3 of 10) or there was a migraine. */
        fun isSymptomDay(d: SymptomsDay) = d.migraines.isNotEmpty() || d.entries.any { it.severity >= 3 }

        /**
         * What went with symptom days: for each factor, how often symptoms came on days with it
         * against days without. Only clear differences, with enough days on each side.
         */
        fun correlate(symptomDays: Map<LocalDate, Boolean>, factors: Map<String, Map<LocalDate, Boolean>>): List<Correlation> {
            if (symptomDays.values.count { it } < 3) return emptyList()
            return factors.mapNotNull { (key, days) ->
                val known = days.filterKeys { it in symptomDays }
                val with = known.filterValues { it }.keys
                val without = known.filterValues { !it }.keys
                if (with.size < 3 || without.size < 3) return@mapNotNull null
                val hits = with.count { symptomDays[it] == true }
                val withRate = hits.toDouble() / with.size
                val withoutRate = without.count { symptomDays[it] == true }.toDouble() / without.size
                val clear = withRate - withoutRate >= 0.15 && (withRate >= withoutRate * 1.5 || (withoutRate == 0.0 && withRate >= 0.3))
                if (!clear) return@mapNotNull null
                Correlation(
                    factor = key,
                    text = "Symptoms came on $hits of ${with.size} days ${LABELS[key] ?: key} (${(withRate * 100).roundToInt()}%), against ${(withoutRate * 100).roundToInt()}% of other days.",
                    withRate = (withRate * 100).roundToInt() / 100.0,
                    withoutRate = (withoutRate * 100).roundToInt() / 100.0,
                    days = with.size,
                )
            }.sortedByDescending { it.withRate - it.withoutRate }
        }

        private fun tags(list: List<String>, max: Int) = list.map { it.trim().lowercase().take(60) }.filter { it.isNotEmpty() }.distinct().take(max)

        fun validated(s: SymptomEntry): SymptomEntry {
            val name = s.symptom.trim().lowercase().take(60)
            if (name.isEmpty()) throw ApiError.badRequest("Which symptom?")
            if (s.severity !in 0..10) throw ApiError.badRequest("Severity is from 0 to 10")
            s.reliefHelped?.let { if (it !in 0..3) throw ApiError.badRequest("How much it helped is from 0 to 3") }
            s.durationMinutes?.let { if (it !in 0..10_080) throw ApiError.badRequest("A symptom lasts up to a week; log it again after that") }
            return s.copy(
                symptom = name, bodyAreas = tags(s.bodyAreas, 12), triggers = tags(s.triggers, 20), relief = tags(s.relief, 20),
                note = s.note?.trim()?.take(4000)?.ifBlank { null }, id = null, date = null,
            )
        }

        fun validated(m: Migraine, now: Long): Migraine {
            if (m.severity !in 0..10) throw ApiError.badRequest("Severity is from 0 to 10")
            m.end?.let { end -> if (end < m.start || end - m.start > 7 * 86_400_000L) throw ApiError.badRequest("A migraine ends after it starts, within a week") }
            m.reliefHelped?.let { if (it !in 0..3) throw ApiError.badRequest("How much it helped is from 0 to 3") }
            if (m.start > now + 3_600_000 || m.start < 946_684_800_000L) throw ApiError.badRequest("That time doesn't look right")
            return m.copy(
                symptoms = tags(m.symptoms, 20), triggers = tags(m.triggers, 20), medications = m.medications.map { it.trim().take(80) }.filter { it.isNotEmpty() }.distinct().take(10),
                note = m.note?.trim()?.take(4000)?.ifBlank { null }, id = null, date = null,
            )
        }
    }

    private suspend fun range(userId: String, count: Int): Pair<LocalDate, LocalDate> {
        val to = profiles.days(userId).today(clock())
        return to.minusDays((count.coerceIn(1, 3650) - 1).toLong()) to to
    }
}
