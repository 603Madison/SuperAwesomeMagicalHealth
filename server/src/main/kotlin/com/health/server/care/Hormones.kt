package com.health.server.care

import com.health.api.DigestSection
import com.health.api.DoseChange
import com.health.api.HormoneMed
import com.health.api.HormoneOverview
import com.health.api.HormoneSettings
import com.health.api.HormonesToday
import com.health.api.Insight
import com.health.api.LabResult
import com.health.api.LabSeries
import com.health.api.LabTarget
import com.health.api.Medication
import com.health.api.Module
import com.health.api.Point
import com.health.api.Profile
import com.health.api.ReportSectionKey
import com.health.api.ScheduleKind
import com.health.api.SuggestedQuestion
import com.health.server.ApiError
import com.health.server.health.ProfileStore
import com.health.server.modules.EntryKind
import com.health.server.modules.EntryStore
import com.health.server.modules.MedsService
import com.health.server.platform.FeatureHooks
import com.health.server.platform.ReportChart
import com.health.server.platform.ReportSection
import com.health.server.platform.ReportTable
import com.health.server.util.Days
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Hormone therapy of any kind (gender-affirming, for menopause, testosterone, thyroid…): the
 * medications marked as hormones, every change to their dose or schedule, lab results against the
 * person's own target ranges, and when labs are next due. It never assumes a direction: targets
 * are whatever the person (and their clinician) set.
 */
class HormonesService(
    private val meds: MedsService,
    private val entries: EntryStore,
    private val profiles: ProfileStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : FeatureHooks {
    override val module = Module.HORMONES

    // ---- Settings ----

    suspend fun settings(userId: String): HormoneSettings =
        Singletons.get(entries, userId, EntryKind.HORMONE_SETTINGS, HormoneSettings.serializer()) ?: HormoneSettings()

    suspend fun saveSettings(userId: String, settings: HormoneSettings): HormoneSettings {
        settings.labsEveryWeeks?.let { if (it !in 1..104) throw ApiError.badRequest("Labs every 1 to 104 weeks") }
        if (settings.targets.size > 40) throw ApiError.badRequest("Up to 40 target ranges")
        val targets = settings.targets.map { t ->
            val name = t.name.trim().take(80).ifBlank { throw ApiError.badRequest("A target range needs the test's name") }
            if (t.low != null && t.high != null && t.low!! > t.high!!) throw ApiError.badRequest("The low end of $name's range is above the high end")
            if (listOfNotNull(t.low, t.high).any { !it.isFinite() }) throw ApiError.badRequest("Ranges are numbers")
            t.copy(name = name, unit = t.unit.trim().take(30), note = t.note?.trim()?.take(500)?.ifBlank { null })
        }.distinctBy { it.name.lowercase() }
        val clean = settings.copy(targets = targets)
        Singletons.put(entries, userId, EntryKind.HORMONE_SETTINGS, clean, HormoneSettings.serializer())
        return clean
    }

    // ---- Dose changes ----

    suspend fun changes(userId: String): List<DoseChange> =
        entries.list(userId, EntryKind.DOSE_CHANGE, LocalDate.of(1900, 1, 1), LocalDate.of(9999, 12, 31), DoseChange.serializer())
            .map { it.value.copy(id = it.id) }.sortedBy { it.at }

    suspend fun addChange(userId: String, change: DoseChange): DoseChange {
        meds.get(userId, change.medicationId) // must be the person's own
        val to = change.to.trim().take(200).ifBlank { throw ApiError.badRequest("What did the dose change to?") }
        val clean = change.copy(from = change.from?.trim()?.take(200)?.ifBlank { null }, to = to, note = change.note?.trim()?.take(1000)?.ifBlank { null }, id = null)
        val date = profiles.days(userId).dateOf(clean.at)
        val id = entries.put(userId, EntryKind.DOSE_CHANGE, null, date, clean.at, clean, DoseChange.serializer())
        return clean.copy(id = id)
    }

    suspend fun deleteChange(userId: String, id: String) {
        entries.delete(userId, EntryKind.DOSE_CHANGE, id)
    }

    /**
     * Called whenever a medication is saved ([before] is null for a new one): a hormone
     * medication's first dose, and every change to its dose or schedule, go in its history.
     */
    suspend fun medicationChanged(userId: String, before: Medication?, after: Medication) {
        if (!after.hormone || after.id == null) return
        val now = describe(after)
        val start = after.startedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        when {
            before == null || !before.hormone -> {
                if (changes(userId).none { it.medicationId == after.id }) {
                    val at = start?.let { profiles.days(userId).start(it) + 12 * 3_600_000L }?.takeIf { it <= clock() } ?: clock()
                    addChange(userId, DoseChange(after.id!!, at, null, now, "Started"))
                }
            }
            describe(before) != now -> addChange(userId, DoseChange(after.id!!, clock(), describe(before), now))
        }
    }

    // ---- The overview ----

    suspend fun overview(userId: String): HormoneOverview {
        val settings = settings(userId)
        val list = meds.list(userId, all = true).filter { it.hormone }.sortedByDescending { it.active }
        val changes = changes(userId)
        val labs = meds.labs(userId, 36500)
        val doseDays = if (list.any { it.startedOn == null }) firstDoses(userId, list) else emptyMap()
        val hormoneMeds = list.map { m ->
            val mine = changes.filter { it.medicationId == m.id }
            val since = m.startedOn ?: listOfNotNull(doseDays[m.id], mine.firstOrNull()?.let { profiles.days(userId).dateOf(it.at).toString() }).minOrNull()
            HormoneMed(m, since, mine)
        }
        val today = profiles.days(userId).today(clock())
        return HormoneOverview(
            medications = hormoneMeds,
            labs = series(labs, settings.targets),
            settings = settings,
            lastLabs = labs.maxOfOrNull { it.date ?: "" }?.ifEmpty { null },
            nextLabsDue = nextLabsDue(labs, settings, today)?.toString(),
        )
    }

    private suspend fun firstDoses(userId: String, list: List<Medication>): Map<String, String> {
        val today = profiles.days(userId).today(clock())
        val ids = list.mapNotNull { it.id }.toSet()
        return meds.doses(userId, today.minusYears(10), today.plusDays(1)).filter { it.medicationId in ids && it.date != null }
            .groupBy { it.medicationId }.mapValues { (_, l) -> l.minOf { it.date!! } }
    }

    suspend fun today(userId: String, date: LocalDate): Pair<HormonesToday?, List<Pair<Int, Insight>>> {
        val list = meds.list(userId).filter { it.hormone }
        val settings = settings(userId)
        val labs = meds.labs(userId, 36500)
        if (list.isEmpty() && labs.isEmpty()) return null to emptyList()
        val series = series(labs, settings.targets)
        val out = outOfRange(series)
        val due = nextLabsDue(labs, settings, date)
        val insights = mutableListOf<Pair<Int, Insight>>()
        out.firstOrNull()?.let { (s, latest) ->
            val t = s.target!!
            val side = if (t.low != null && latest.value < t.low!!) "below" else "above"
            insights += 62 to Insight(
                "info", "${s.name} is $side your target range",
                "Your ${shortDate(latest.date)} result was ${num(latest.value)} ${latest.unit}; your range is ${rangeText(t)}. Worth mentioning to your clinician.",
                "activity", Module.HORMONES,
            )
        }
        if (due != null && list.isNotEmpty()) {
            val until = ChronoUnit.DAYS.between(date, due)
            when {
                until < 0 -> insights += 57 to Insight("info", "Labs are due", "It's been a while since your last blood test: your next one was due around ${shortDate(due.toString())}.", "calendar", Module.HORMONES)
                until <= 14 -> insights += 45 to Insight("info", "Labs due soon", "Your next blood test is due around ${shortDate(due.toString())}.", "calendar", Module.HORMONES)
            }
        }
        return HormonesToday(list.size, due?.toString(), out.map { it.first.name }) to insights
    }

    // ---- Hooks ----

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val o = overview(userId)
        if (o.medications.isEmpty()) return null
        val days = Days(Days.zoneOf(profile.timezone))
        val lines = mutableListOf<String>()
        o.medications.flatMap { m -> m.changes.map { m.medication to it } }
            .filter { (_, c) -> days.dateOf(c.at) in from..to && c.from != null }
            .forEach { (m, c) -> lines += "${m.name} changed to ${c.to}." }
        o.labs.forEach { s -> s.results.filter { LocalDate.parse(it.date) in from..to }.forEach { r -> lines += "${s.name}: ${num(r.value)} ${r.unit}${s.target?.let { t -> " (your range ${rangeText(t)})" } ?: ""}." } }
        o.nextLabsDue?.let { lines += "Next labs due around ${shortDate(it)}." }
        if (lines.isEmpty()) lines += "No changes this week."
        val out = outOfRange(o.labs).filter { (_, r) -> LocalDate.parse(r.date) in from..to }
        return DigestSection("Hormone therapy", Module.HORMONES, if (out.isNotEmpty()) "warn" else "info", lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection? {
        val o = overview(userId)
        if (o.medications.isEmpty() && o.labs.isEmpty()) return null
        val days = Days(Days.zoneOf(profile.timezone))
        val summary = mutableListOf<String>()
        o.medications.forEach { m -> summary += "${m.medication.name}: ${describe(m.medication)}${m.since?.let { ", since ${longDate(it)}" } ?: ""}." }
        o.lastLabs?.let { summary += "Last labs ${longDate(it)}${o.nextLabsDue?.let { d -> "; next due around ${longDate(d)}" } ?: ""}." }
        val changes = o.medications.flatMap { m -> m.changes.map { m.medication.name to it } }.filter { (_, c) -> days.dateOf(c.at) <= to }
        val labsInRange = o.labs.map { s -> s.copy(results = s.results.filter { LocalDate.parse(it.date) in from..to }) }.filter { it.results.isNotEmpty() }
        return ReportSection(
            key = ReportSectionKey.HORMONES,
            summary = summary,
            tables = listOfNotNull(
                ReportTable("Dose history", listOf("Date", "Medication", "From", "To"), changes.map { (name, c) -> listOf(days.dateOf(c.at).toString(), name, c.from ?: "–", c.to) }).takeIf { it.rows.isNotEmpty() },
                ReportTable(
                    "Lab results", listOf("Date", "Test", "Result", "Target range"),
                    labsInRange.flatMap { s -> s.results.map { r -> listOf(r.date.orEmpty(), s.name, "${num(r.value)} ${r.unit}", s.target?.let(::rangeText) ?: "–") } }.sortedBy { it[0] },
                ).takeIf { it.rows.isNotEmpty() },
            ),
            charts = labsInRange.filter { it.results.size >= 2 }.map { s ->
                ReportChart(s.name, s.unit, listOf(s.name to s.results.map { Point(it.at, it.value) }), s.target?.let { t -> if (t.low != null && t.high != null) t.low!!..t.high!! else null })
            },
        )
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val o = overview(userId)
        val days = Days(Days.zoneOf(profile.timezone))
        val rows = o.medications.flatMap { m -> m.changes.map { m.medication.name to it } }.filter { (_, c) -> days.dateOf(c.at) in from..to }
            .map { (name, c) -> listOf(days.dateOf(c.at).toString(), name, c.from.orEmpty(), c.to, c.note.orEmpty()) }
        val targets = o.settings.targets.map { listOf(it.name, it.unit, it.low?.let(::num).orEmpty(), it.high?.let(::num).orEmpty(), it.note.orEmpty()) }
        return buildMap {
            if (rows.isNotEmpty()) put("hormone-dose-changes.csv", listOf(listOf("date", "medication", "from", "to", "note")) + rows)
            if (targets.isNotEmpty()) put("lab-targets.csv", listOf(listOf("test", "unit", "low", "high", "note")) + targets)
        }
    }

    override suspend fun questions(userId: String, profile: Profile, since: LocalDate, to: LocalDate): List<SuggestedQuestion> {
        val o = overview(userId)
        val out = mutableListOf<SuggestedQuestion>()
        outOfRange(o.labs).forEach { (s, r) ->
            out += SuggestedQuestion("My ${s.name} was ${num(r.value)} ${r.unit}, outside my range of ${rangeText(s.target!!)}. Should anything change?", "Your ${shortDate(r.date)} result", Module.HORMONES)
        }
        val days = Days(Days.zoneOf(profile.timezone))
        o.medications.forEach { m ->
            m.changes.lastOrNull { it.from != null && days.dateOf(it.at) in since..to }?.let { c ->
                out += SuggestedQuestion("How is the change to ${m.medication.name} (${c.to}) going, and when should we check levels?", "Changed ${shortDate(days.dateOf(c.at).toString())}", Module.HORMONES)
            }
        }
        o.nextLabsDue?.let { due ->
            if (!LocalDate.parse(due).isAfter(to.plusDays(30))) out += SuggestedQuestion("Can I get a form for my next blood test?", "Labs due around ${shortDate(due)}", Module.HORMONES)
        }
        return out
    }

    companion object {
        /** Lab results by test name, oldest first, each with the person's target range for it. */
        fun series(labs: List<LabResult>, targets: List<LabTarget>): List<LabSeries> =
            labs.groupBy { it.name.trim().lowercase() }.map { (_, list) ->
                val sorted = list.sortedBy { it.at }
                val name = sorted.last().name.trim()
                LabSeries(name, sorted.last().unit, targets.firstOrNull { it.name.equals(name, ignoreCase = true) }, sorted)
            }.sortedBy { it.name.lowercase() }

        /** Tests whose latest result sits outside the person's range, with that result. */
        fun outOfRange(series: List<LabSeries>): List<Pair<LabSeries, LabResult>> = series.mapNotNull { s ->
            val t = s.target ?: return@mapNotNull null
            val latest = s.results.lastOrNull() ?: return@mapNotNull null
            val low = t.low != null && latest.value < t.low!!
            val high = t.high != null && latest.value > t.high!!
            if (low || high) s to latest else null
        }

        /** When the next labs are due: the last result's date plus the interval. */
        fun nextLabsDue(labs: List<LabResult>, settings: HormoneSettings, today: LocalDate): LocalDate? {
            val every = settings.labsEveryWeeks ?: return null
            val last = labs.mapNotNull { it.date?.let(LocalDate::parse) }.filter { !it.isAfter(today) }.maxOrNull() ?: return null
            return last.plusWeeks(every.toLong())
        }

        /** A medication's dose and schedule, as a line: "2 mg · daily at 08:00, 20:00". */
        fun describe(med: Medication): String {
            val s = med.schedule
            val times = s.times.joinToString(", ")
            val schedule = when (s.kind) {
                ScheduleKind.DAILY -> "daily at $times"
                ScheduleKind.WEEKLY -> "on ${s.weekdays.ifEmpty { listOf(1) }.joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }} at $times"
                ScheduleKind.EVERY_N_DAYS -> "every ${s.everyDays} days at $times"
                ScheduleKind.AS_NEEDED -> "as needed"
            }
            return listOfNotNull(med.dose, schedule).joinToString(" · ")
        }

        fun rangeText(t: LabTarget): String = when {
            t.low != null && t.high != null -> "${num(t.low!!)}–${num(t.high!!)} ${t.unit}".trim()
            t.low != null -> "above ${num(t.low!!)} ${t.unit}".trim()
            t.high != null -> "below ${num(t.high!!)} ${t.unit}".trim()
            else -> "not set"
        }
    }
}
