package com.health.server.platform

import com.health.api.Point
import com.health.api.Units
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** How the digest, report and export say numbers: plainly, the same everywhere. */
object Say {
    fun minutes(total: Number?): String {
        val m = total?.toDouble()?.roundToInt() ?: return "–"
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    fun count(n: Number?): String = n?.let { String.format(Locale.US, "%,d", it.toDouble().roundToLong()) } ?: "–"

    fun number(n: Double?, decimals: Int = 1): String = n?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "–"

    fun weight(kg: Double?, units: Units): String = when {
        kg == null -> "–"
        units == Units.IMPERIAL -> "${number(kg * 2.20462)} lb"
        else -> "${number(kg)} kg"
    }

    fun weightValue(kg: Double, units: Units): Double = if (units == Units.IMPERIAL) kg * 2.20462 else kg

    fun weightUnit(units: Units) = if (units == Units.IMPERIAL) "lb" else "kg"

    /** "up 18m", "down 3", "about the same", against last week; null when either side is missing. */
    fun change(now: Double?, before: Double?, format: (Double) -> String, sameWithin: Double): String? {
        if (now == null || before == null) return null
        val delta = now - before
        return when {
            abs(delta) <= sameWithin -> "about the same as the week before"
            delta > 0 -> "up ${format(delta)} on the week before"
            else -> "down ${format(-delta)} on the week before"
        }
    }

    fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    fun date(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK))

    fun shortDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))

    fun time(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))

    fun dateTime(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    fun range(from: LocalDate, to: LocalDate) = "${date(from)} to ${date(to)}"
}

/** One CSV file: a header and rows, written with RFC 4180 quoting. */
object Csv {
    fun write(rows: List<List<String>>): String = buildString {
        rows.forEach { row -> append(row.joinToString(",") { cell(it) }).append("\r\n") }
    }

    private fun cell(value: String): String {
        // Cells that a spreadsheet would run as a formula are kept as text.
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r" && value.toDoubleOrNull() == null) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    fun num(v: Double?, decimals: Int = 2): String = v?.let { String.format(Locale.US, "%.${decimals}f", it).trimEnd('0').trimEnd('.') }.orEmpty()

    fun iso(ms: Long?, zone: ZoneId): String = ms?.let { Instant.ofEpochMilli(it).atZone(zone).toOffsetDateTime().toString() }.orEmpty()
}

/** FHIR R4 resources, small and plain, for the export's bundle. */
object Fhir {
    const val LOINC = "http://loinc.org"
    const val UCUM = "http://unitsofmeasure.org"
    private const val CATEGORY = "http://terminology.hl7.org/CodeSystem/observation-category"

    data class Code(val code: String?, val display: String, val system: String = LOINC)

    fun patientRef(userId: String) = "Patient/$userId"

    fun patient(userId: String, name: String): JsonObject = buildJsonObject {
        put("resourceType", "Patient")
        put("id", userId)
        put("name", buildJsonArray { add(buildJsonObject { put("text", name) }) })
    }

    private fun codeable(code: Code): JsonObject = buildJsonObject {
        if (code.code != null) {
            put("coding", buildJsonArray { add(buildJsonObject { put("system", code.system); put("code", code.code); put("display", code.display) }) })
        }
        put("text", code.display)
    }

    /**
     * An Observation: at an instant ([at]), or for a whole day ([date]), with a quantity, or a
     * string when [text] is given instead.
     */
    fun observation(
        id: String,
        userId: String,
        code: Code,
        category: String,
        value: Double? = null,
        unit: String? = null,
        ucum: String? = unit,
        at: Long? = null,
        date: LocalDate? = null,
        zone: ZoneId = ZoneId.of("UTC"),
        text: String? = null,
        low: Double? = null,
        high: Double? = null,
        components: List<JsonObject> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("resourceType", "Observation")
        put("id", fhirId(id))
        put("status", "final")
        put("category", buildJsonArray {
            add(buildJsonObject { put("coding", buildJsonArray { add(buildJsonObject { put("system", CATEGORY); put("code", category) }) }) })
        })
        put("code", codeable(code))
        put("subject", buildJsonObject { put("reference", patientRef(userId)) })
        when {
            at != null -> put("effectiveDateTime", Instant.ofEpochMilli(at).atZone(zone).toOffsetDateTime().toString())
            date != null -> put("effectiveDateTime", date.toString())
        }
        if (value != null) put("valueQuantity", quantity(value, unit, ucum))
        else if (text != null) put("valueString", text)
        if (low != null || high != null) {
            put("referenceRange", buildJsonArray {
                add(buildJsonObject {
                    low?.let { put("low", quantity(it, unit, ucum)) }
                    high?.let { put("high", quantity(it, unit, ucum)) }
                })
            })
        }
        if (components.isNotEmpty()) put("component", JsonArray(components))
    }

    fun quantity(value: Double, unit: String?, ucum: String?): JsonObject = buildJsonObject {
        put("value", (value * 1000).roundToLong() / 1000.0)
        if (unit != null) put("unit", unit)
        if (ucum != null) { put("system", UCUM); put("code", ucum) }
    }

    fun component(code: Code, value: Double, unit: String, ucum: String = unit): JsonObject = buildJsonObject {
        put("code", codeable(code))
        put("valueQuantity", quantity(value, unit, ucum))
    }

    /** A medication the person takes, as they wrote it. */
    fun medicationStatement(id: String, userId: String, name: String, dosage: String?, active: Boolean, startedOn: String?): JsonObject = buildJsonObject {
        put("resourceType", "MedicationStatement")
        put("id", fhirId(id))
        put("status", if (active) "active" else "stopped")
        put("medicationCodeableConcept", buildJsonObject { put("text", name) })
        put("subject", buildJsonObject { put("reference", patientRef(userId)) })
        startedOn?.let { put("effectivePeriod", buildJsonObject { put("start", it) }) }
        if (!dosage.isNullOrBlank()) put("dosage", buildJsonArray { add(buildJsonObject { put("text", dosage) }) })
    }

    fun bundle(resources: List<JsonObject>, now: Long): JsonObject = buildJsonObject {
        put("resourceType", "Bundle")
        put("type", "collection")
        put("timestamp", Instant.ofEpochMilli(now).toString())
        put("entry", buildJsonArray {
            resources.forEach { resource ->
                add(buildJsonObject {
                    val type = (resource["resourceType"] as? JsonPrimitive)?.content ?: "Resource"
                    val id = (resource["id"] as? JsonPrimitive)?.content ?: ""
                    put("fullUrl", "urn:samh:$type/$id")
                    put("resource", resource)
                })
            }
        })
    }

    /** FHIR ids: letters, digits, '-' and '.', at most 64. */
    fun fhirId(raw: String): String = raw.map { if (it.isLetterOrDigit() || it == '-' || it == '.') it else '-' }.joinToString("").take(64)
}

internal fun points(values: List<Pair<LocalDate, Double?>>, zone: ZoneId): List<Point> =
    values.mapNotNull { (d, v) -> v?.let { Point(d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), it) } }

internal fun JsonObject.with(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(this + pairs.mapNotNull { (k, v) -> v?.let { k to jsonOf(it) } })

internal fun jsonOf(v: Any?): JsonElement = when (v) {
    null -> kotlinx.serialization.json.JsonNull
    is JsonElement -> v
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    else -> JsonPrimitive(v.toString())
}

internal fun json(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.mapNotNull { (k, v) -> v?.let { k to jsonOf(it) } }.toMap())
