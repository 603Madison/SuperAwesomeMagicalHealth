package com.health.server.records

import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.RecordFlag
import com.health.api.RecordItem
import com.health.api.RecordKind
import com.health.api.ScheduleKind
import com.health.server.util.Days
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * A record's FHIR R4 resources in plain words: pure functions, from the JSON as the portal sends it
 * (Epic's and athenahealth's shapes) to [RecordItem]s, and the numbers of lab results for Labs.
 */
object Fhir {
    /** One resource, read: [key] is its own "Type/id" there; [lab] its number, for a lab with one. */
    data class Mapped(val key: String, val item: RecordItem, val lab: LabValue? = null)

    /** A lab result's number, unit and usual range, to keep in Labs. */
    data class LabValue(val value: Double, val unit: String, val at: Long, val low: Double?, val high: Double?)

    /** What each kind is read with: the resource type and its search, besides `patient=`. */
    data class Search(val kind: RecordKind, val type: String, val params: List<Pair<String, String>> = emptyList())

    val SEARCHES = listOf(
        Search(RecordKind.LAB, "Observation", listOf("category" to "laboratory")),
        Search(RecordKind.VITAL, "Observation", listOf("category" to "vital-signs")),
        Search(RecordKind.MEDICATION, "MedicationRequest"),
        Search(RecordKind.CONDITION, "Condition", listOf("category" to "problem-list-item")),
        Search(RecordKind.ALLERGY, "AllergyIntolerance"),
        Search(RecordKind.IMMUNIZATION, "Immunization"),
        Search(RecordKind.PROCEDURE, "Procedure"),
    )

    private const val TITLE = 200
    private const val DETAIL = 300

    /**
     * [resource] as a record item of [kind], or null when it isn't one to show: entered in error,
     * nothing to call it, or (with [patient]) someone else's.
     */
    fun map(
        resource: JsonObject,
        kind: RecordKind,
        organization: String,
        zone: Days,
        patient: String?,
        included: Map<String, JsonObject> = emptyMap(),
    ): Mapped? {
        val type = resource.str("resourceType") ?: return null
        val id = resource.str("id")?.takeIf { it.isNotBlank() } ?: return null
        if (type != SEARCHES.first { it.kind == kind }.type) return null
        val status = resource.str("status")
        if (status in setOf("entered-in-error", "cancelled", "draft")) return null
        if (patient != null && !belongsTo(resource, patient)) return null
        val item = when (kind) {
            RecordKind.LAB, RecordKind.VITAL -> return observation(resource, kind, organization, zone, "$type/$id")
            RecordKind.MEDICATION -> medication(resource, included, zone)
            RecordKind.CONDITION -> condition(resource, zone)
            RecordKind.ALLERGY -> allergy(resource, zone)
            RecordKind.IMMUNIZATION -> immunization(resource, zone)
            RecordKind.PROCEDURE -> procedure(resource, zone)
        } ?: return null
        return Mapped("$type/$id", item.copy(organization = organization))
    }

    /** Whether [resource] is about [patient] (its subject or patient), when it says. */
    fun belongsTo(resource: JsonObject, patient: String): Boolean {
        val ref = (resource.obj("subject") ?: resource.obj("patient"))?.str("reference") ?: return true
        return ref == "Patient/$patient" || ref.endsWith("/Patient/$patient")
    }

    // ---- Kinds

    private fun observation(o: JsonObject, kind: RecordKind, organization: String, zone: Days, key: String): Mapped? {
        val title = concept(o.obj("code")) ?: return null
        val (date, at) = time(o.str("effectiveDateTime") ?: o.obj("effectivePeriod")?.str("start") ?: o.str("issued"), zone)
        val quantity = o.obj("valueQuantity")
        val value = quantity?.num("value")
        val unit = quantity?.let { it.str("unit") ?: it.str("code") }?.trim().orEmpty()
        val comparator = quantity?.str("comparator").orEmpty()
        val range = o.arr("referenceRange")?.firstObj()
        val low = range?.obj("low")?.num("value")
        val high = range?.obj("high")?.num("value")
        val usual = when {
            low != null && high != null -> "usual ${number(low)}–${number(high)}"
            low != null -> "usual ${number(low)} or more"
            high != null -> "usual up to ${number(high)}"
            else -> range?.str("text")?.trim()?.ifBlank { null }?.let { "usual $it" }
        }
        val shown = when {
            value != null -> listOf("$comparator${number(value)}", unit).filter { it.isNotEmpty() }.joinToString(" ")
            o.arr("component") != null -> components(o.arr("component")!!)
            else -> o.obj("valueCodeableConcept")?.let(::concept) ?: o.str("valueString")?.trim()
        }?.ifBlank { null }
        val detail = listOfNotNull(shown, usual?.takeIf { shown != null }?.let { "($it)" }).joinToString(" ").ifBlank { null }
        val flag = if (kind == RecordKind.LAB) flagOf(o, value.takeIf { comparator.isEmpty() }, low, high) else null
        val item = RecordItem(
            id = "", connectionId = "", kind = kind, title = title.take(TITLE), detail = detail?.take(DETAIL),
            date = date, at = at, status = o.str("status"), flag = flag, organization = organization,
        )
        val lab = if (kind == RecordKind.LAB && value != null && value.isFinite() && comparator.isEmpty()) {
            (at ?: date?.let { noon(it, zone) })?.let { LabValue(value, unit, it, low, high) }
        } else null
        return Mapped(key, item, lab)
    }

    /** Blood pressure and the like: "120/80 mmHg". */
    private fun components(list: JsonArray): String? {
        val values = list.mapNotNull { (it as? JsonObject)?.obj("valueQuantity") }.mapNotNull { q -> q.num("value")?.let { it to (q.str("unit") ?: q.str("code")) } }
        if (values.isEmpty()) return null
        val units = values.mapNotNull { it.second }.distinct()
        val numbers = values.joinToString("/") { number(it.first) }
        return if (units.size == 1) "$numbers ${units[0]}" else values.joinToString(", ") { listOfNotNull(number(it.first), it.second).joinToString(" ") }
    }

    private fun flagOf(o: JsonObject, value: Double?, low: Double?, high: Double?): RecordFlag? {
        val codes = o.arr("interpretation").orEmpty().mapNotNull { it as? JsonObject }.flatMap { c ->
            c.arr("coding").orEmpty().mapNotNull { (it as? JsonObject)?.str("code")?.uppercase() } + listOfNotNull(c.str("text")?.uppercase())
        }
        if (codes.any { it in setOf("H", "HH", "HU", ">", "HIGH", "CRITICAL HIGH") }) return RecordFlag.HIGH
        if (codes.any { it in setOf("L", "LL", "LU", "<", "LOW", "CRITICAL LOW") }) return RecordFlag.LOW
        if (codes.any { it in setOf("A", "AA", "ABN", "ABNORMAL", "POS", "DET", "R", "CRITICAL") }) return RecordFlag.ABNORMAL
        if (codes.any { it in setOf("N", "NORMAL", "NEG", "ND", "S") }) return null
        return when {
            value == null -> null
            high != null && value > high -> RecordFlag.HIGH
            low != null && value < low -> RecordFlag.LOW
            else -> null
        }
    }

    private fun medication(m: JsonObject, included: Map<String, JsonObject>, zone: Days): RecordItem? {
        val reference = m.obj("medicationReference")
        val ref = reference?.str("reference")
        val contained = ref?.takeIf { it.startsWith("#") }?.let { local ->
            m.arr("contained").orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { it.str("id") == local.removePrefix("#") }
        }
        val name = m.obj("medicationCodeableConcept")?.let(::concept)
            ?: contained?.obj("code")?.let(::concept)
            ?: reference?.str("display")?.trim()?.ifBlank { null }
            ?: ref?.let { included[it] ?: included["Medication/" + it.substringAfterLast("/")] }?.obj("code")?.let(::concept)
            ?: return null
        val dosage = m.arr("dosageInstruction")?.firstObj()
        val instruction = dosage?.str("text")?.trim()?.ifBlank { null }
        val amount = dosage?.arr("doseAndRate")?.firstObj()?.obj("doseQuantity")?.let { q -> q.num("value")?.let { listOfNotNull(number(it), q.str("unit")).joinToString(" ") } }
        val status = m.str("status")
        val (date, at) = time(m.str("authoredOn"), zone)
        val medication = Medication(
            name = name.take(120),
            dose = (amount ?: instruction)?.take(120),
            schedule = MedSchedule(kind = ScheduleKind.AS_NEEDED, times = emptyList()),
            notes = instruction?.takeIf { amount != null }?.take(1000),
            reminders = false,
            active = status == "active",
            startedOn = date,
        )
        return RecordItem("", "", RecordKind.MEDICATION, name.take(TITLE), (instruction ?: amount)?.take(DETAIL), date, at, status, organization = "", medication = medication)
    }

    private fun condition(c: JsonObject, zone: Days): RecordItem? {
        val title = concept(c.obj("code")) ?: return null
        val status = clinical(c)
        val onset = c.str("onsetDateTime") ?: c.obj("onsetPeriod")?.str("start")
        val abated = c.str("abatementDateTime") ?: c.obj("abatementPeriod")?.str("start")
        val detail = when {
            status in setOf("resolved", "inactive", "remission") && abated != null -> "Until ${year(abated)}"
            onset != null -> "Since ${year(onset)}"
            else -> c.str("onsetString")?.trim()?.ifBlank { null }
        }
        val (date, at) = time(c.str("recordedDate") ?: onset, zone)
        return RecordItem("", "", RecordKind.CONDITION, title.take(TITLE), detail?.take(DETAIL), date, at, status, organization = "")
    }

    private fun allergy(a: JsonObject, zone: Days): RecordItem? {
        val title = concept(a.obj("code")) ?: return null
        val reaction = a.arr("reaction")?.firstObj()
        val signs = reaction?.arr("manifestation").orEmpty().mapNotNull { (it as? JsonObject)?.let(::concept) }.distinct().joinToString(", ").ifBlank { null }
        val severity = reaction?.str("severity") ?: a.str("criticality")?.takeIf { it == "high" }?.let { "high risk" }
        val detail = listOfNotNull(signs, severity).joinToString(" · ").ifBlank { null }
        val (date, at) = time(a.str("recordedDate") ?: a.str("onsetDateTime"), zone)
        return RecordItem("", "", RecordKind.ALLERGY, title.take(TITLE), detail?.take(DETAIL), date, at, clinical(a), organization = "")
    }

    private fun immunization(i: JsonObject, zone: Days): RecordItem? {
        val title = concept(i.obj("vaccineCode")) ?: return null
        val status = i.str("status")
        val (date, at) = time(i.str("occurrenceDateTime") ?: i.str("recorded"), zone)
        val detail = if (status == "not-done") "Not given" else null
        return RecordItem("", "", RecordKind.IMMUNIZATION, title.take(TITLE), detail, date, at, status, organization = "")
    }

    private fun procedure(p: JsonObject, zone: Days): RecordItem? {
        val title = concept(p.obj("code")) ?: return null
        val (date, at) = time(p.str("performedDateTime") ?: p.obj("performedPeriod")?.str("start"), zone)
        return RecordItem("", "", RecordKind.PROCEDURE, title.take(TITLE), null, date, at, p.str("status"), organization = "")
    }

    // ---- Pieces

    /** A CodeableConcept's words: its text, else its first coding's display. */
    fun concept(c: JsonObject?): String? {
        c ?: return null
        return c.str("text")?.trim()?.ifBlank { null }
            ?: c.arr("coding").orEmpty().firstNotNullOfOrNull { (it as? JsonObject)?.str("display")?.trim()?.ifBlank { null } }
    }

    private fun clinical(o: JsonObject): String? =
        o.obj("clinicalStatus")?.let { cs -> cs.arr("coding").orEmpty().firstNotNullOfOrNull { (it as? JsonObject)?.str("code") } ?: cs.str("text") }

    /**
     * A FHIR date or dateTime: the day ("YYYY-MM-DD", in the person's own time zone for a time),
     * and the moment when there's a time. Partial dates ("2019", "2019-03") give neither.
     */
    fun time(text: String?, zone: Days): Pair<String?, Long?> {
        val t = text?.trim().orEmpty()
        if (t.length == 10) return (runCatching { LocalDate.parse(t).toString() }.getOrNull()) to null
        if (t.length > 10) {
            val at = try {
                OffsetDateTime.parse(t).toInstant().toEpochMilli()
            } catch (e: DateTimeParseException) {
                return runCatching { LocalDate.parse(t.take(10)).toString() }.getOrNull() to null
            }
            return zone.dateOf(at).toString() to at
        }
        return null to null
    }

    private fun year(text: String): String = text.trim().take(4)

    /** Midday on [date], for a lab result that only has its day. */
    fun noon(date: String, zone: Days): Long? = runCatching { zone.start(LocalDate.parse(date)) + 12 * 3_600_000L }.getOrNull()

    /** 142.0 is "142", 4.50 is "4.5". */
    fun number(v: Double): String = runCatching { BigDecimal.valueOf(v).stripTrailingZeros().toPlainString() }.getOrDefault(v.toString())

    // ---- JSON

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull
    fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }?.takeIf { it.isFinite() }
    private fun JsonArray.firstObj(): JsonObject? = firstOrNull() as? JsonObject
}
