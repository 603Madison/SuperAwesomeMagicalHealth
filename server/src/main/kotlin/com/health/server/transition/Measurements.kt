package com.health.server.transition

import com.health.api.DigestSection
import com.health.api.Metric
import com.health.api.Module
import com.health.api.Point
import com.health.api.Profile
import com.health.api.ReportSectionKey
import com.health.server.health.Records
import com.health.server.platform.Csv
import com.health.server.platform.FeatureHooks
import com.health.server.platform.Fhir
import com.health.server.platform.ReportChart
import com.health.server.platform.ReportSection
import com.health.server.platform.ReportTable
import com.health.server.platform.Say
import com.health.server.util.Days
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.util.Locale

/**
 * Body measurements (waist, hips, chest…, in cm) with the rest of the body: in the digest, the
 * report's Body section, the export and FHIR. They're entered through /api/measurements like weight.
 */
class MeasurementHooks(private val records: Records) : FeatureHooks {
    override val module = Module.BODY

    private suspend fun samples(userId: String, profile: Profile, from: LocalDate, to: LocalDate) =
        Days(Days.zoneOf(profile.timezone)).let { zone -> records.samples(userId, zone.start(from), zone.end(to), TransitionService.MEASUREMENTS) }

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val week = samples(userId, profile, from, to)
        if (week.isEmpty()) return null
        val lines = week.groupBy { it.metric }.map { (metric, list) ->
            val latest = list.maxBy { it.start }
            val before = samples(userId, profile, from.minusDays(60), from.minusDays(1)).filter { it.metric == metric }.maxByOrNull { it.start }
            val change = before?.let { b -> latest.value - b.value }?.takeIf { kotlin.math.abs(it) >= 0.5 }
            "${metric.label} ${cm(latest.value)}${change?.let { c -> " (${if (c > 0) "up" else "down"} ${cm(kotlin.math.abs(c))})" } ?: ""}."
        }
        return DigestSection("Measurements", Module.BODY, "info", lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection? {
        val all = samples(userId, profile, from, to)
        if (all.isEmpty()) return null
        val by = all.groupBy { it.metric }
        val rows = TransitionService.MEASUREMENTS.mapNotNull { metric ->
            by[metric]?.sortedBy { it.start }?.let { list ->
                listOf(metric.label, cm(list.first().value), cm(list.last().value), "${list.size}")
            }
        }
        val charts = listOf(Metric.WAIST, Metric.HIPS, Metric.CHEST, Metric.BUST).mapNotNull { m ->
            by[m]?.takeIf { it.size >= 2 }?.let { list -> m.label to list.sortedBy { it.start }.map { Point(it.start, it.value) } }
        }.takeIf { it.isNotEmpty() }?.let { listOf(ReportChart("Measurements", "cm", it)) } ?: emptyList()
        return ReportSection(
            ReportSectionKey.BODY,
            listOf("${by.size} kinds of body measurement, ${all.size} in all."),
            listOf(ReportTable("Measurements (cm)", listOf("Measurement", "First", "Latest", "Times"), rows)),
            charts,
        )
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val zone = Days(Days.zoneOf(profile.timezone)).zone
        val list = samples(userId, profile, from, to).sortedBy { it.start }
        if (list.isEmpty()) return emptyMap()
        return mapOf("body-measurements.csv" to listOf(listOf("at", "measurement", "cm")) + list.map { listOf(Csv.iso(it.start, zone), it.metric.name.lowercase(Locale.ROOT), Csv.num(it.value)) })
    }

    override suspend fun fhir(userId: String, profile: Profile, from: LocalDate, to: LocalDate): List<JsonObject> {
        val zone = Days(Days.zoneOf(profile.timezone)).zone
        return samples(userId, profile, from, to).map { s ->
            val code = when (s.metric) {
                Metric.WAIST -> Fhir.Code("8280-0", "Waist Circumference at umbilicus by Tape measure")
                Metric.HIPS -> Fhir.Code("62409-8", "Hip circumference")
                else -> Fhir.Code(null, "${s.metric.label} circumference")
            }
            Fhir.observation("measure-${s.id}", userId, code, "vital-signs", s.value, "cm", "cm", at = s.start, zone = zone)
        }
    }

    private fun cm(v: Double) = "${Say.number(v, 1).removeSuffix(".0")} cm"
}
