package com.health.server.tracking

import com.health.api.BpCategory
import com.health.api.DigestSection
import com.health.api.GlucoseContext
import com.health.api.Module
import com.health.api.Point
import com.health.api.Profile
import com.health.api.ReminderAction
import com.health.api.ReportSectionKey
import com.health.api.SuggestedQuestion
import com.health.api.WebhookEvent
import com.health.server.insights.DayService
import com.health.server.insights.Scoring
import com.health.server.insights.Stress
import com.health.server.platform.FeatureHooks
import com.health.server.platform.Reminder
import com.health.server.platform.ReportChart
import com.health.server.platform.ReportSection
import com.health.server.platform.ReportTable
import com.health.server.util.Days
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Blood pressure and glucose, drinks, symptoms, habits and stress in the weekly digest, the
 * doctor's report, the export, reminders and appointment questions (see platform/FeatureHooks).
 */

private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
private fun stamp(days: Days, ms: Long) = STAMP.format(Instant.ofEpochMilli(ms).atZone(days.zone))
private fun zoneOf(profile: Profile) = Days(Days.zoneOf(profile.timezone))
private fun one(v: Double) = String.format(Locale.ROOT, "%.1f", v)
private fun weeks(since: LocalDate, to: LocalDate): String {
    val n = ((ChronoUnit.DAYS.between(since, to) + 1) / 7.0).roundToInt()
    return if (n <= 1) "the last week" else "the last $n weeks"
}

private fun observation(code: String, display: String, at: Long, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
    put("resourceType", "Observation")
    put("status", "final")
    putJsonArray("category") {
        add(buildJsonObject {
            putJsonArray("coding") {
                add(buildJsonObject {
                    put("system", "http://terminology.hl7.org/CodeSystem/observation-category")
                    put("code", if (code.isEmpty()) "survey" else "vital-signs")
                })
            }
        })
    }
    putJsonObject("code") {
        if (code.isNotEmpty()) putJsonArray("coding") { add(loinc(code, display)) }
        put("text", display)
    }
    put("effectiveDateTime", Instant.ofEpochMilli(at).toString())
    block()
}

private fun loinc(code: String, display: String) = buildJsonObject {
    put("system", "http://loinc.org")
    put("code", code)
    put("display", display)
}

private fun quantity(value: Double, unit: String, ucum: String) = buildJsonObject {
    put("value", value)
    put("unit", unit)
    put("system", "http://unitsofmeasure.org")
    put("code", ucum)
}

// ---------------------------------------------------------------- Blood pressure and glucose

class VitalsHooks(private val vitals: VitalsService, private val clock: () -> Long = System::currentTimeMillis) : FeatureHooks {
    override val module = Module.VITALS

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val zone = zoneOf(profile)
        val t = profile.vitalsTargets
        val week = vitals.bloodPressure(userId, zone.start(from), zone.end(to))
        val before = vitals.bloodPressure(userId, zone.start(from.minusDays(7)), zone.start(from))
        val glucose = vitals.glucose(userId, zone.start(from), zone.end(to))
        if (week.isEmpty() && glucose.isEmpty()) return null
        val lines = mutableListOf<String>()
        var tone = "info"
        if (week.isNotEmpty()) {
            val sys = week.map { it.systolic }.average().roundToInt()
            val dia = week.map { it.diastolic }.average().roundToInt()
            val last = before.takeIf { it.isNotEmpty() }?.let { "${it.map { b -> b.systolic }.average().roundToInt()}/${it.map { b -> b.diastolic }.average().roundToInt()}" }
            lines += "Blood pressure averaged $sys/$dia over ${week.size} ${if (week.size == 1) "reading" else "readings"}${last?.let { " (last week $it)" } ?: ""}."
            tone = if (sys < t.systolicHigh && dia < t.diastolicHigh) "good" else if (sys >= t.systolicHigh + 10 || dia >= t.diastolicHigh + 10) "warn" else "info"
        }
        if (glucose.isNotEmpty()) {
            val (below, tir, _) = VitalsService.inRange(glucose, t)
            lines += "Glucose was in range $tir% of the time${if (below > 0) ", below it $below%" else ""}."
            if (below >= 4) tone = "warn"
        }
        return DigestSection("Blood pressure and glucose", Module.VITALS, tone, lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection? {
        val zone = zoneOf(profile)
        val bp = vitals.bloodPressure(userId, zone.start(from), zone.end(to))
        val glucose = vitals.glucose(userId, zone.start(from), zone.end(to))
        if (bp.isEmpty() && glucose.isEmpty()) return null
        val s = VitalsService.summarize(ChronoUnit.DAYS.between(from, to).toInt() + 1, bp, glucose, profile.vitalsTargets, zone)
        val unit = profile.vitalsTargets.glucoseUnit
        val summary = buildList {
            if (bp.isNotEmpty()) add("Blood pressure averaged ${s.systolicAverage?.roundToInt()}/${s.diastolicAverage?.roundToInt()} mmHg over ${bp.size} readings (${s.category?.label?.lowercase()}).")
            if (glucose.isNotEmpty()) {
                add("Glucose averaged ${VitalsService.glucoseText(s.glucoseAverage!!, unit)} over ${glucose.size} readings; ${s.timeInRange}% in range, ${s.timeBelow}% below, ${s.timeAbove}% above.")
                s.fastingAverage?.let { add("Fasting glucose averaged ${VitalsService.glucoseText(it, unit)}.") }
                s.gmi?.let { add("Glucose management indicator (an A1c estimate from the sensor): $it%.") }
            }
            s.patterns.forEach { add("${it.title}: ${it.body}") }
        }
        val tables = buildList {
            if (bp.isNotEmpty()) add(ReportTable("Blood pressure", listOf("When", "mmHg", "Pulse", "Category", "From"), bp.take(300).map {
                listOf(stamp(zone, it.at), "${it.systolic}/${it.diastolic}", it.pulse?.toString().orEmpty(), VitalsService.category(it.systolic, it.diastolic).label, it.source.orEmpty())
            }))
            if (glucose.isNotEmpty() && glucose.none { it.context == GlucoseContext.CGM }) add(ReportTable("Glucose", listOf("When", unit.label, "When taken", "Note"), glucose.take(300).map {
                listOf(stamp(zone, it.at), VitalsService.glucoseText(it.mmolL, unit).substringBefore(' '), it.context.label, it.note.orEmpty())
            }))
        }
        val charts = buildList {
            if (bp.size >= 2) add(ReportChart("Blood pressure", "mmHg", listOf(
                "Systolic" to bp.reversed().map { Point(it.at, it.systolic.toDouble()) },
                "Diastolic" to bp.reversed().map { Point(it.at, it.diastolic.toDouble()) },
            )))
            if (glucose.size >= 2) {
                val factor = if (unit == com.health.api.GlucoseUnit.MG_DL) VitalsService.MG_PER_MMOL else 1.0
                add(ReportChart("Glucose", unit.label, listOf("Glucose" to glucose.reversed().map { Point(it.at, it.mmolL * factor) }), profile.vitalsTargets.let { it.glucoseLow * factor..it.glucoseHigh * factor }))
            }
        }
        return ReportSection(ReportSectionKey.VITALS, summary, tables, charts)
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val zone = zoneOf(profile)
        val bp = vitals.bloodPressure(userId, zone.start(from), zone.end(to)).reversed()
        val glucose = vitals.glucose(userId, zone.start(from), zone.end(to)).reversed()
        return buildMap {
            if (bp.isNotEmpty()) put("blood-pressure.csv", listOf(listOf("time", "systolic_mmhg", "diastolic_mmhg", "pulse_bpm", "arm", "note", "source")) +
                bp.map { listOf(stamp(zone, it.at), it.systolic.toString(), it.diastolic.toString(), it.pulse?.toString().orEmpty(), it.arm.orEmpty(), it.note.orEmpty(), it.source.orEmpty()) })
            if (glucose.isNotEmpty()) put("glucose.csv", listOf(listOf("time", "mmol_l", "mg_dl", "context", "note", "source")) +
                glucose.map { listOf(stamp(zone, it.at), one(it.mmolL), (it.mmolL * VitalsService.MG_PER_MMOL).roundToInt().toString(), it.context.name.lowercase(), it.note.orEmpty(), it.source.orEmpty()) })
        }
    }

    override suspend fun fhir(userId: String, profile: Profile, from: LocalDate, to: LocalDate): List<JsonObject> {
        val zone = zoneOf(profile)
        val bp = vitals.bloodPressure(userId, zone.start(from), zone.end(to)).map { r ->
            observation("85354-9", "Blood pressure panel with all children optional", r.at) {
                putJsonArray("component") {
                    add(buildJsonObject { put("code", buildJsonObject { putJsonArray("coding") { add(loinc("8480-6", "Systolic blood pressure")) } }); put("valueQuantity", quantity(r.systolic.toDouble(), "mmHg", "mm[Hg]")) })
                    add(buildJsonObject { put("code", buildJsonObject { putJsonArray("coding") { add(loinc("8462-4", "Diastolic blood pressure")) } }); put("valueQuantity", quantity(r.diastolic.toDouble(), "mmHg", "mm[Hg]")) })
                    r.pulse?.let { p -> add(buildJsonObject { put("code", buildJsonObject { putJsonArray("coding") { add(loinc("8867-4", "Heart rate")) } }); put("valueQuantity", quantity(p.toDouble(), "beats/minute", "/min")) }) }
                }
            }
        }
        val glucose = vitals.glucose(userId, zone.start(from), zone.end(to)).map { r ->
            observation("15074-8", "Glucose [Moles/volume] in Blood", r.at) {
                put("valueQuantity", quantity(r.mmolL, "mmol/L", "mmol/L"))
                if (r.context != GlucoseContext.OTHER) put("note", buildJsonArray { add(buildJsonObject { put("text", r.context.label) }) })
            }
        }
        return bp + glucose
    }

    override suspend fun reminders(userId: String, profile: Profile, from: Long, to: Long): List<Reminder> {
        val (bp, glucose) = vitals.arrived(userId, from, to)
        val t = profile.vitalsTargets
        val zone = zoneOf(profile)
        return buildList {
            bp.filter { VitalsService.category(it.systolic, it.diastolic) >= BpCategory.STAGE_2 }.forEach { r ->
                val category = VitalsService.category(r.systolic, r.diastolic)
                val data = buildJsonObject { put("systolic", r.systolic); put("diastolic", r.diastolic); put("category", category.name.lowercase()) }
                add(Reminder(
                    key = "bp:${r.id}", at = minOf(r.at, to - 1).coerceAtLeast(from), event = WebhookEvent.BP_HIGH,
                    title = "Blood pressure ${r.systolic}/${r.diastolic}", body = category.label,
                    privateTitle = "A blood pressure reading", privateBody = "Open the app to see it.", url = "/vitals",
                    data = data, push = false,
                ))
            }
            glucose.filter { it.mmolL < t.glucoseLow }.groupBy { it.at / (30 * 60_000L) }.forEach { (slot, list) ->
                val r = list.minBy { it.mmolL }
                add(Reminder(
                    key = "glucose-low:$slot", at = to - 1, event = WebhookEvent.GLUCOSE_LOW,
                    title = "Low glucose: ${VitalsService.glucoseText(r.mmolL, t.glucoseUnit)}",
                    body = "If you feel shaky or unwell, have something sugary and check again in 15 minutes.",
                    privateTitle = "Check your glucose", privateBody = "A reading was below your range.", url = "/vitals",
                    data = buildJsonObject { put("mmol_l", r.mmolL); put("time", stamp(zone, r.at)) }, push = true,
                ))
            }
            glucose.filter { it.mmolL > t.glucoseHigh }.groupBy { it.at / 3_600_000L }.forEach { (hour, list) ->
                val r = list.maxBy { it.mmolL }
                add(Reminder(
                    key = "glucose-high:$hour", at = to - 1, event = WebhookEvent.GLUCOSE_HIGH,
                    title = "Glucose above your range: ${VitalsService.glucoseText(r.mmolL, t.glucoseUnit)}", body = "At ${stamp(zone, r.at).substringAfter(' ')}.",
                    privateTitle = "A glucose reading", privateBody = "Open the app to see it.", url = "/vitals",
                    data = buildJsonObject { put("mmol_l", r.mmolL); put("time", stamp(zone, r.at)) }, push = false,
                ))
            }
        }
    }

    override suspend fun questions(userId: String, profile: Profile, since: LocalDate, to: LocalDate): List<SuggestedQuestion> {
        val zone = zoneOf(profile)
        val t = profile.vitalsTargets
        val bp = vitals.bloodPressure(userId, zone.start(since), zone.end(to))
        val glucose = vitals.glucose(userId, zone.start(since), zone.end(to))
        return buildList {
            if (bp.size >= 3) {
                val sys = bp.map { it.systolic }.average().roundToInt()
                val dia = bp.map { it.diastolic }.average().roundToInt()
                if (sys >= t.systolicHigh || dia >= t.diastolicHigh) {
                    add(SuggestedQuestion("My blood pressure has averaged $sys/$dia over ${weeks(since, to)}. Is that something to treat, or to keep an eye on?", "${bp.size} readings since $since", Module.VITALS))
                }
            }
            val lows = glucose.count { it.mmolL < t.glucoseLow }
            if (lows >= 2) add(SuggestedQuestion("I've had $lows low glucose readings over ${weeks(since, to)}. Should anything change?", "Below ${VitalsService.glucoseText(t.glucoseLow, t.glucoseUnit)}", Module.VITALS))
            if (glucose.count { it.context == GlucoseContext.CGM } >= 100) {
                val (_, tir, _) = VitalsService.inRange(glucose, t)
                if (tir < 70) add(SuggestedQuestion("My glucose was in range $tir% of the time. What would help bring that up?", "From your sensor since $since", Module.VITALS))
            }
        }
    }
}

// ---------------------------------------------------------------- Drinks

class DrinksHooks(private val drinks: DrinksService) : FeatureHooks {
    override val module = Module.NUTRITION

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val week = drinks.daysOf(userId, from, to).values
        if (week.isEmpty()) return null
        val before = drinks.daysOf(userId, from.minusDays(7), from.minusDays(1)).values
        val caffeine = week.filter { it.caffeineMg > 0 }
        val alcohol = week.sumOf { it.standardDrinks }
        val alcoholBefore = before.sumOf { it.standardDrinks }
        val lines = buildList {
            if (caffeine.isNotEmpty()) add("Caffeine averaged ${caffeine.map { it.caffeineMg }.average().roundToInt()} mg on the ${caffeine.size} days you had any.")
            if (alcohol > 0 || alcoholBefore > 0) add("${DrinksService.drinksText((alcohol * 10).roundToInt() / 10.0).replaceFirstChar { it.uppercase() }} of alcohol this week${if (before.isNotEmpty()) " (${DrinksService.drinksText((alcoholBefore * 10).roundToInt() / 10.0)} last week)" else ""}.")
        }
        if (lines.isEmpty()) return null
        return DigestSection("Drinks", Module.NUTRITION, if (alcohol < alcoholBefore) "good" else "info", lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection? {
        val list = drinks.daysOf(userId, from, to).values.sortedBy { it.date }
        if (list.isEmpty()) return null
        val weeks = (ChronoUnit.DAYS.between(from, to) + 1) / 7.0
        return ReportSection(
            ReportSectionKey.NUTRITION,
            listOf(
                "Drinks: caffeine averaged ${list.filter { it.caffeineMg > 0 }.map { it.caffeineMg }.ifEmpty { listOf(0.0) }.average().roundToInt()} mg a day on days with any; " +
                    "about ${one(list.sumOf { it.standardDrinks } / weeks.coerceAtLeast(1.0))} standard drinks (14 g of alcohol each) a week.",
            ),
            listOf(ReportTable("Caffeine and alcohol", listOf("Day", "Caffeine (mg)", "Standard drinks", "Other drinks (ml)"), list.map {
                listOf(it.date, it.caffeineMg.roundToInt().toString(), one(it.standardDrinks), it.fluidMl.toString())
            })),
        )
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val zone = zoneOf(profile)
        val list = drinks.list(userId, from, to)
        if (list.isEmpty()) return emptyMap()
        return mapOf("drinks.csv" to listOf(listOf("time", "kind", "name", "volume_ml", "caffeine_mg", "alcohol_g")) + list.map {
            listOf(stamp(zone, it.at), it.kind.name.lowercase(), it.name.orEmpty(), it.volumeMl.toString(), it.caffeineMg.roundToInt().toString(), one(it.alcoholG))
        })
    }

    override suspend fun questions(userId: String, profile: Profile, since: LocalDate, to: LocalDate): List<SuggestedQuestion> {
        val list = drinks.daysOf(userId, since, to).values
        val weeks = ((ChronoUnit.DAYS.between(since, to) + 1) / 7.0).coerceAtLeast(1.0)
        val perWeek = list.sumOf { it.standardDrinks } / weeks
        return if (perWeek >= 14) listOf(SuggestedQuestion("I've been having about ${perWeek.roundToInt()} drinks of alcohol a week. Could that be affecting my health?", "From the drinks you logged since $since", Module.NUTRITION)) else emptyList()
    }
}

// ---------------------------------------------------------------- Symptoms

class SymptomsHooks(private val symptoms: SymptomsService) : FeatureHooks {
    override val module = Module.SYMPTOMS

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val week = symptoms.daysOf(userId, from, to).values
        if (week.isEmpty()) return null
        val before = symptoms.daysOf(userId, from.minusDays(7), from.minusDays(1)).values
        val migraine = week.count { it.migraines.isNotEmpty() }
        val migraineBefore = before.count { it.migraines.isNotEmpty() }
        val top = week.flatMap { it.entries }.groupingBy { it.symptom }.eachCount().maxByOrNull { it.value }
        val lines = buildList {
            add("Symptoms on ${week.size} ${if (week.size == 1) "day" else "days"}${if (before.isNotEmpty()) " (${before.size} last week)" else ""}${top?.let { ", most often ${it.key}" } ?: ""}.")
            if (migraine > 0 || migraineBefore > 0) add("$migraine migraine ${if (migraine == 1) "day" else "days"} (last week $migraineBefore).")
        }
        return DigestSection("Symptoms", Module.SYMPTOMS, if (week.size < before.size) "good" else "info", lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection? {
        val zone = zoneOf(profile)
        val list = symptoms.daysOf(userId, from, to).values.sortedBy { it.date }
        if (list.isEmpty()) return null
        val entries = list.flatMap { it.entries }
        val migraines = list.flatMap { it.migraines }
        val count = ChronoUnit.DAYS.between(from, to).toInt() + 1
        val correlations = runCatching { symptoms.summary(userId, count).correlations }.getOrDefault(emptyList())
        val summary = buildList {
            add("Symptoms on ${list.size} of $count days.")
            entries.groupBy { it.symptom }.entries.sortedByDescending { it.value.size }.take(6).forEach { (name, l) ->
                add("${name.replaceFirstChar { it.uppercase() }}: ${l.size} ${if (l.size == 1) "time" else "times"}, usually around ${l.map { it.severity }.average().roundToInt()} of 10.")
            }
            if (migraines.isNotEmpty()) add("Migraines on ${migraines.map { it.date }.distinct().size} days${migraines.count { it.aura }.takeIf { it > 0 }?.let { ", $it with aura" } ?: ""}.")
            correlations.forEach { add(it.text) }
        }
        val tables = buildList {
            if (entries.isNotEmpty()) add(ReportTable("Symptoms", listOf("When", "Symptom", "Severity", "Triggers", "What helped"), entries.map {
                listOf(stamp(zone, it.at), it.symptom, "${it.severity}/10", it.triggers.joinToString(), it.relief.joinToString())
            }))
            if (migraines.isNotEmpty()) add(ReportTable("Migraines", listOf("Started", "Lasted", "Severity", "Aura", "Medication"), migraines.map {
                listOf(stamp(zone, it.start), it.end?.let { e -> Scoring.hm(((e - it.start) / 60_000).toInt()) } ?: "ongoing", "${it.severity}/10", if (it.aura) "yes" else "no", it.medications.joinToString())
            }))
        }
        val charts = listOf(ReportChart("Worst symptom each day", "of 10", listOf("Worst" to list.mapNotNull { d -> d.worst?.let { Point(zone.start(LocalDate.parse(d.date)), it.toDouble()) } })))
        return ReportSection(ReportSectionKey.SYMPTOMS, summary, tables, if (list.size >= 2) charts else emptyList())
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val zone = zoneOf(profile)
        val entries = symptoms.entries(userId, from, to)
        val migraines = symptoms.migraines(userId, from, to)
        return buildMap {
            if (entries.isNotEmpty()) put("symptoms.csv", listOf(listOf("time", "symptom", "severity", "body_areas", "duration_min", "triggers", "relief", "relief_helped", "note")) + entries.map {
                listOf(stamp(zone, it.at), it.symptom, it.severity.toString(), it.bodyAreas.joinToString("; "), it.durationMinutes?.toString().orEmpty(), it.triggers.joinToString("; "), it.relief.joinToString("; "), it.reliefHelped?.toString().orEmpty(), it.note.orEmpty())
            })
            if (migraines.isNotEmpty()) put("migraines.csv", listOf(listOf("start", "end", "severity", "aura", "symptoms", "triggers", "medications", "relief_helped", "missed_plans", "note")) + migraines.map {
                listOf(stamp(zone, it.start), it.end?.let { e -> stamp(zone, e) }.orEmpty(), it.severity.toString(), it.aura.toString(), it.symptoms.joinToString("; "), it.triggers.joinToString("; "), it.medications.joinToString("; "), it.reliefHelped?.toString().orEmpty(), it.missedPlans.toString(), it.note.orEmpty())
            })
        }
    }

    override suspend fun fhir(userId: String, profile: Profile, from: LocalDate, to: LocalDate): List<JsonObject> =
        symptoms.entries(userId, from, to).map { s ->
            observation("", s.symptom, s.at) {
                put("valueInteger", s.severity)
                s.note?.let { put("note", buildJsonArray { add(buildJsonObject { put("text", it) }) }) }
            }
        } + symptoms.migraines(userId, from, to).map { m ->
            observation("", "migraine", m.start) {
                put("valueInteger", m.severity)
                m.end?.let { end -> putJsonObject("effectivePeriod") { put("start", Instant.ofEpochMilli(m.start).toString()); put("end", Instant.ofEpochMilli(end).toString()) } }
            }.let { o -> if (m.end != null) JsonObject(o - "effectiveDateTime") else o }
        }

    override suspend fun reminders(userId: String, profile: Profile, from: Long, to: Long): List<Reminder> {
        val zone = zoneOf(profile)
        return symptoms.migraines(userId, zone.dateOf(from).minusDays(1), zone.dateOf(to)).filter { it.start in from until to }.map { m ->
            Reminder(
                key = "migraine:${m.id}", at = m.start, event = WebhookEvent.MIGRAINE,
                title = "A migraine started", body = "Severity ${m.severity} of 10${if (m.aura) ", with aura" else ""}.",
                privateTitle = "An entry was logged", privateBody = "", url = "/symptoms",
                data = buildJsonObject { put("severity", m.severity) },
                detailedData = buildJsonObject { put("severity", m.severity); put("aura", m.aura); put("symptoms", JsonArray(m.symptoms.map(::JsonPrimitive))) },
                push = false,
            )
        }
    }

    override suspend fun questions(userId: String, profile: Profile, since: LocalDate, to: LocalDate): List<SuggestedQuestion> {
        val list = symptoms.daysOf(userId, since, to).values
        if (list.isEmpty()) return emptyList()
        val span = ChronoUnit.DAYS.between(since, to) + 1
        return buildList {
            val migraineDays = list.count { it.migraines.isNotEmpty() }
            val perMonth = migraineDays * 30.0 / span
            if (migraineDays >= 3 && perMonth >= 4) add(SuggestedQuestion("I've had $migraineDays migraine days over ${weeks(since, to)}. Would a preventive treatment help?", "About ${perMonth.roundToInt()} a month", Module.SYMPTOMS))
            list.flatMap { it.entries }.groupBy { it.symptom }.filter { (_, l) -> l.map { it.date }.distinct().size >= 5 }.entries
                .sortedByDescending { it.value.size }.take(2).forEach { (name, l) ->
                    add(SuggestedQuestion("I've had $name on ${l.map { it.date }.distinct().size} days, usually around ${l.map { it.severity }.average().roundToInt()} of 10. What could be causing it?", "Since $since", Module.SYMPTOMS))
                }
            runCatching { symptoms.summary(userId, span.toInt()).correlations }.getOrDefault(emptyList()).take(2).forEach { c ->
                add(SuggestedQuestion("Could ${FACTOR_QUESTIONS[c.factor] ?: c.factor} be making my symptoms worse?", c.text, Module.SYMPTOMS))
            }
        }
    }

    private companion object {
        val FACTOR_QUESTIONS = mapOf(
            "short_sleep" to "short nights", "stress" to "stress", "alcohol" to "alcohol", "more_caffeine" to "caffeine",
            "less_caffeine" to "cutting back on caffeine", "period" to "my cycle", "pressure_drop" to "changes in the weather",
        )
    }
}

// ---------------------------------------------------------------- Habits

class HabitsHooks(private val habits: HabitsService) : FeatureHooks {
    override val module = Module.HABITS

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val kept = habits.kept(userId)
        if (kept.isEmpty()) return null
        val done = habits.doneDays(userId, profile, zoneOf(profile), kept, from, to)
        val lines = kept.map { k ->
            val n = done[k.habit.id].orEmpty().count { it.value.second }
            "${k.habit.name}: $n of ${k.habit.timesPerWeek} ${if (k.habit.timesPerWeek == 1) "day" else "days"}${if (n >= k.habit.timesPerWeek) " ✓" else ""}"
        }
        val met = kept.count { k -> done[k.habit.id].orEmpty().count { it.value.second } >= k.habit.timesPerWeek }
        return DigestSection("Habits", Module.HABITS, if (met == kept.size) "good" else "info", lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection? {
        val kept = habits.kept(userId)
        if (kept.isEmpty()) return null
        val done = habits.doneDays(userId, profile, zoneOf(profile), kept, from, to)
        return ReportSection(
            ReportSectionKey.HABITS,
            listOf("${kept.size} ${if (kept.size == 1) "habit" else "habits"}, from $from to $to."),
            listOf(ReportTable("Habits", listOf("Habit", "Aim", "Days done"), kept.map { k ->
                listOf(k.habit.name, "${k.habit.timesPerWeek} days a week", done[k.habit.id].orEmpty().count { it.value.second }.toString())
            })),
        )
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val kept = habits.kept(userId, all = true)
        if (kept.isEmpty()) return emptyMap()
        val done = habits.doneDays(userId, profile, zoneOf(profile), kept, from, to)
        return mapOf("habits.csv" to listOf(listOf("date", "habit", "count", "done")) + kept.flatMap { k ->
            done[k.habit.id].orEmpty().filter { (d, v) -> !d.isBefore(k.since) && (v.first > 0 || v.second) }.toSortedMap().map { (d, v) -> listOf(d.toString(), k.habit.name, v.first.toString(), v.second.toString()) }
        })
    }

    override suspend fun reminders(userId: String, profile: Profile, from: Long, to: Long): List<Reminder> {
        if (!profile.reminders.habits) return emptyList()
        val zone = zoneOf(profile)
        val kept = habits.kept(userId).filter { it.habit.reminderTime != null }
        if (kept.isEmpty()) return emptyList()
        val dates = listOf(zone.dateOf(from), zone.dateOf(to)).distinct()
        return dates.flatMap { date ->
            val due = kept.mapNotNull { k ->
                val (h, m) = k.habit.reminderTime!!.split(":").map { it.toInt() }
                val at = date.atTime(h, m).atZone(zone.zone).toInstant().toEpochMilli()
                if (at in from until to && !date.isBefore(k.since)) k to at else null
            }
            if (due.isEmpty()) return@flatMap emptyList()
            val statuses = habits.statuses(userId, date).associateBy { it.habit.id }
            due.mapNotNull { (k, at) ->
                val s = statuses[k.habit.id] ?: return@mapNotNull null
                if (s.done || s.weekDone >= k.habit.timesPerWeek) return@mapNotNull null
                Reminder(
                    key = "habit:${k.habit.id}:$date", at = at, event = WebhookEvent.HABIT_DUE,
                    title = k.habit.name, body = "Not done yet today.",
                    privateTitle = "A habit is waiting", privateBody = "Open the app to tick it off.", url = "/habits",
                    actions = if (k.habit.source == com.health.api.HabitSource.MANUAL) listOf(ReminderAction("done", "Done"), ReminderAction("open", "Open")) else listOf(ReminderAction("open", "Open")),
                    data = buildJsonObject { put("habit_id", k.habit.id) },
                    detailedData = buildJsonObject { put("habit_id", k.habit.id); put("habit", k.habit.name) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Stress and resilience

class StressHooks(private val days: DayService) : FeatureHooks {
    override val module = Module.ACTIVITY

    private suspend fun stressDays(userId: String, profile: Profile, from: LocalDate, to: LocalDate): List<com.health.api.StressDay> {
        val stats = days.stats(userId, from.minusDays(30), to, zoneOf(profile))
        return stats.indices.mapNotNull { i ->
            val d = LocalDate.parse(stats[i].date)
            if (d.isBefore(from)) null else Stress.day(stats[i].date, stats[i].stressWindows, Stress.baseline(stats[i], stats.subList(0, i)))
        }
    }

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val week = stressDays(userId, profile, from, to)
        if (week.size < 3) return null
        val stressed = week.map { it.stressedMinutes }.average().roundToInt()
        val restored = week.map { it.restoredMinutes }.average().roundToInt()
        val summary = days.summary(userId, to.toString())
        val lines = listOfNotNull(
            "Stressed ${Scoring.hm(stressed)} a day on average, restored ${Scoring.hm(restored)}.",
            summary.resilience?.let { "Resilience: ${it.level.label.lowercase()}${if (it.trend == "up") ", building" else if (it.trend == "down") ", dipping" else ""}." },
        )
        return DigestSection("Stress and resilience", Module.ACTIVITY, if (restored >= stressed) "good" else "info", lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection? {
        val list = stressDays(userId, profile, from, to)
        if (list.size < 3) return null
        val zone = zoneOf(profile)
        return ReportSection(
            ReportSectionKey.STRESS,
            listOf(
                "Daytime stress worked out from heart rate and HRV while awake and still (or the device's own stress readings), on ${list.size} days.",
                "On average ${Scoring.hm(list.map { it.stressedMinutes }.average().roundToInt())} stressed and ${Scoring.hm(list.map { it.restoredMinutes }.average().roundToInt())} restored a day; ${list.count { it.summary == "Stressful" }} stressful days.",
            ),
            listOf(ReportTable("Each day", listOf("Day", "Stressed", "Engaged", "Relaxed", "Restored", "Overall"), list.map {
                listOf(it.date, Scoring.hm(it.stressedMinutes), Scoring.hm(it.engagedMinutes), Scoring.hm(it.relaxedMinutes), Scoring.hm(it.restoredMinutes), it.summary)
            })),
            listOf(ReportChart("Stressed and restored minutes", "min", listOf(
                "Stressed" to list.map { Point(zone.start(LocalDate.parse(it.date)), it.stressedMinutes.toDouble()) },
                "Restored" to list.map { Point(zone.start(LocalDate.parse(it.date)), it.restoredMinutes.toDouble()) },
            ))),
        )
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val list = stressDays(userId, profile, from, to)
        if (list.isEmpty()) return emptyMap()
        return mapOf("stress.csv" to listOf(listOf("date", "stressed_min", "engaged_min", "relaxed_min", "restored_min", "overall")) + list.map {
            listOf(it.date, it.stressedMinutes.toString(), it.engagedMinutes.toString(), it.relaxedMinutes.toString(), it.restoredMinutes.toString(), it.summary.lowercase())
        })
    }

    override suspend fun questions(userId: String, profile: Profile, since: LocalDate, to: LocalDate): List<SuggestedQuestion> {
        val list = stressDays(userId, profile, since, to)
        if (list.size < 7) return emptyList()
        val stressed = list.map { it.stressedMinutes }.average()
        return if (stressed >= 180) listOf(SuggestedQuestion("My stress has been high on most days lately. What could help?", "About ${Scoring.hm(stressed.roundToInt())} stressed a day, from my ring or watch", Module.ACTIVITY)) else emptyList()
    }
}
