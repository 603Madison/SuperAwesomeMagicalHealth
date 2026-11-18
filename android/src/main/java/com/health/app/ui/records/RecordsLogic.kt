package com.health.app.ui.records

import com.health.api.HealthRecords
import com.health.api.LabResult
import com.health.api.Medication
import com.health.api.RecordConnect
import com.health.api.RecordConnection
import com.health.api.RecordFlag
import com.health.api.RecordItem
import com.health.api.RecordKind
import com.health.api.ScheduleKind
import com.health.app.ui.components.ArrangeItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * You › Medical records (/api/records): a hospital, clinic or practice's own record of the person,
 * from its patient portal. Worked out without drawing anything, so it can be tested; RecordsScreen.kt
 * draws it. The same words and sections as the web's page (web/src/lib/records.ts).
 */
object RecordsLogic {
    const val TITLE = "Medical records"
    const val ROUTE = "records"
    const val PROMISE = "You'll sign in on your portal's own page. Your record comes straight to this server; nothing else sees it."
    const val SEARCH = "Your hospital or health system"
    const val SEARCH_HINT = "Search by its name, like Mass General Brigham."

    // ---- Sections (android.records): the same ids as the web's, saved with the person's arrangement, so never rename one.

    const val CONNECTIONS = "connections"

    fun sectionId(kind: RecordKind): String = when (kind) {
        RecordKind.LAB -> "labs"
        RecordKind.VITAL -> "vitals"
        RecordKind.MEDICATION -> "medications"
        RecordKind.CONDITION -> "conditions"
        RecordKind.ALLERGY -> "allergies"
        RecordKind.IMMUNIZATION -> "immunizations"
        RecordKind.PROCEDURE -> "procedures"
    }

    fun kindOf(sectionId: String): RecordKind? = RecordKind.entries.firstOrNull { sectionId(it) == sectionId }

    /** The page's sections in the default order: the portals, then each kind once something's connected. */
    fun sections(connected: Boolean): List<ArrangeItem> =
        listOf(ArrangeItem(CONNECTIONS, "Your portals")) + if (connected) RecordKind.entries.map { ArrangeItem(sectionId(it), it.label) } else emptyList()

    // ---- Words

    /** "just now", "5 min ago", "2 h ago", "yesterday", "3 days ago", or "on 4 Nov" (with the year when it isn't this one). */
    fun ago(ms: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val seconds = ((now - ms) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3600} h ago"
            seconds < 2 * 86_400 -> "yesterday"
            seconds < 7 * 86_400 -> "${seconds / 86_400} days ago"
            else -> {
                val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
                val sameYear = day.year == Instant.ofEpochMilli(now).atZone(zone).year
                "on " + DateTimeFormatter.ofPattern(if (sameYear) "d MMM" else "d MMM yyyy", Locale.ENGLISH).format(day)
            }
        }
    }

    /** "4 Nov 2026" from "2026-11-04"; the text as it is when it isn't a date. */
    fun dayText(date: String): String =
        parseDay(date)?.let { DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(it) } ?: date

    private fun parseDay(date: String): LocalDate? =
        Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(date)?.let { m ->
            val (y, mo, d) = m.destructured
            if (mo.toInt() in 1..12 && d.toInt() in 1..31) LocalDate.of(y.toInt(), mo.toInt(), d.toInt().coerceAtMost(LocalDate.of(y.toInt(), mo.toInt(), 1).lengthOfMonth())) else null
        }

    /** "Updated 2 h ago", or "Not updated yet". */
    fun updatedText(c: RecordConnection, now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        c.lastSyncAt?.let { "Updated ${ago(it, now, zone)}" } ?: "Not updated yet"

    /** "Updates itself daily", or "Sign in again to update". */
    fun keepsText(c: RecordConnection): String = if (c.keepsUpdating) "Updates itself daily" else "Sign in again to update"

    private fun noun(kind: RecordKind, n: Int): String {
        val (one, many) = when (kind) {
            RecordKind.LAB -> "lab result" to "lab results"
            RecordKind.VITAL -> "vital sign" to "vital signs"
            RecordKind.MEDICATION -> "medication" to "medications"
            RecordKind.CONDITION -> "condition" to "conditions"
            RecordKind.ALLERGY -> "allergy" to "allergies"
            RecordKind.IMMUNIZATION -> "immunization" to "immunizations"
            RecordKind.PROCEDURE -> "procedure" to "procedures"
        }
        return "$n ${if (n == 1) one else many}"
    }

    /** "12 lab results · 3 medications · 1 allergy", in the page's order; null when nothing came in. */
    fun countLine(counts: Map<RecordKind, Int>): String? =
        RecordKind.entries.mapNotNull { k -> counts[k]?.takeIf { it > 0 }?.let { noun(k, it) } }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** A lab flag in words, never colour alone. */
    fun flagWord(flag: RecordFlag): String = when (flag) {
        RecordFlag.HIGH -> "High"
        RecordFlag.LOW -> "Low"
        RecordFlag.ABNORMAL -> "Outside range"
    }

    private fun whenOf(item: RecordItem): Long =
        item.at ?: item.date?.let { parseDay(it) }?.atTime(12, 0)?.toInstant(java.time.ZoneOffset.UTC)?.toEpochMilli() ?: Long.MIN_VALUE

    /** The record a kind at a time, each newest first (undated last, by title); kinds with nothing left out. */
    fun byKind(items: List<RecordItem>): Map<RecordKind, List<RecordItem>> =
        items.groupBy { it.kind }.mapValues { (_, list) -> list.sortedWith(compareByDescending<RecordItem> { whenOf(it) }.thenBy { it.title }) }

    /** The line under an item's title: its detail, its day, and its status when it isn't the usual one. */
    fun itemLine(item: RecordItem): String {
        val status = item.status?.takeIf { it.lowercase() !in setOf("active", "final", "completed") }?.replaceFirstChar { it.uppercase() }
        return listOfNotNull(item.detail, item.date?.let(::dayText), status).joinToString(" · ")
    }

    /** What to send to connect to the same portal again ("Sign in again"), from the phone app. */
    fun reconnect(c: RecordConnection): RecordConnect = RecordConnect(c.organization, c.fhirBaseUrl, c.provider, app = true)

    /** You's line, true now: where it stands, or the portal and when it was last brought up to date. */
    fun youLine(records: HealthRecords?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (records == null) return "Your hospital or clinic's record"
        val all = records.connections
        if (all.isEmpty()) return if (records.providers.isEmpty()) "Not set up on this server yet" else "Connect your patient portal"
        // Disconnected ones (kept, without access) only count when nothing else is connected.
        val live = all.filterNot(::isDisconnected)
        val list = live.ifEmpty { all }
        val who = if (list.size == 1) list[0].organization else "${list[0].organization} and ${list.size - 1} more"
        if (live.isEmpty()) return "$who · disconnected"
        if (records.paused) return "$who · paused"
        if (list.any { it.problem != null }) return "$who · needs you to sign in again"
        val latest = list.mapNotNull { it.lastSyncAt }.maxOrNull()
        return if (latest != null) "$who · updated ${ago(latest, now, zone)}" else "$who · not updated yet"
    }

    /**
     * Disconnected but kept: its access is gone and what it brought in stays (the server says so in
     * its problem, "Disconnected: …"). It can be connected again, or removed with all it brought.
     */
    fun isDisconnected(c: RecordConnection): Boolean = c.problem?.startsWith("Disconnected") == true

    /** How the portal sent the app back to samhealth://records: ?connected=, ?error=, or ?confirm= to confirm with the server. */
    data class Return(val connected: String? = null, val error: String? = null, val confirm: String? = null)

    /** A lab result from a connected record: the server brings it back, so it isn't deleted here. */
    fun fromRecord(lab: LabResult): Boolean = lab.source != null || lab.ref?.startsWith("record:") == true

    /** "From Mass General Brigham" for a lab from a record; null for one entered here. */
    fun labSource(lab: LabResult): String? = lab.source?.let { "From $it" }

    /** A medication from a record as a new one to add here: never an edit of one, and with a time when it's scheduled. */
    fun draft(m: Medication): Medication {
        val s = m.schedule
        val schedule = if (s.kind != ScheduleKind.AS_NEEDED && s.times.isEmpty()) s.copy(times = listOf("09:00")) else s
        return m.copy(id = null, schedule = schedule)
    }

    /** Whether [m] looks like one the person already has (by name). */
    fun alreadyHas(m: Medication, mine: Collection<String>): Boolean = m.name.trim().lowercase() in mine.map { it.trim().lowercase() }
}
