package com.health.server.platform

import com.health.api.DigestSection
import com.health.api.Module
import com.health.api.Point
import com.health.api.Profile
import com.health.api.ReminderAction
import com.health.api.ReportSectionKey
import com.health.api.SuggestedQuestion
import com.health.api.WebhookEvent
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * What a feature adds to the platform: the weekly digest, the doctor's report, the export,
 * reminders (sent by push and webhook) and questions for appointments. Each feature's service
 * implements the parts it has and is listed in [com.health.server.Services.hooks]; the platform
 * asks only the features the person has turned on ([module] null means always).
 */
interface FeatureHooks {
    val module: Module?

    /** Lines for the week [from]..[to] (Monday to Sunday), against the week before. */
    suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? = null

    /** This feature's part of the doctor's report. */
    suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection? = null

    /** CSV files for the export: file name ("blood-pressure.csv") to rows, the first being the header. */
    suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> = emptyMap()

    /** FHIR R4 resources (Observation, MedicationStatement…) for the export's bundle. */
    suspend fun fhir(userId: String, profile: Profile, from: LocalDate, to: LocalDate): List<JsonObject> = emptyList()

    /** Reminders due in [from, to) (epoch ms). The scheduler sends each [Reminder.key] once. */
    suspend fun reminders(userId: String, profile: Profile, from: Long, to: Long): List<Reminder> = emptyList()

    /** Questions worth asking at an appointment, from what was logged in [since]..[to]. */
    suspend fun questions(userId: String, profile: Profile, since: LocalDate, to: LocalDate): List<SuggestedQuestion> = emptyList()
}

/** A section of the report: a few plain sentences, tables and charts. */
data class ReportSection(
    val key: ReportSectionKey,
    val summary: List<String>,
    val tables: List<ReportTable> = emptyList(),
    val charts: List<ReportChart> = emptyList(),
)

data class ReportTable(val title: String, val header: List<String>, val rows: List<List<String>>)

/** A line chart: each series a name and its points; [band] shades a target range. */
data class ReportChart(
    val title: String,
    val unit: String,
    val series: List<Pair<String, List<Point>>>,
    val band: ClosedFloatingPointRange<Double>? = null,
)

/**
 * Something to tell the person at [at]: by browser push, by webhook (as [event]), and on the
 * watch. [title]/[body] may name things; [privateTitle]/[privateBody] don't, for locked screens
 * and for webhooks without details. [key] is unique for this reminder ("dose:<med>:<time>:again1").
 */
data class Reminder(
    val key: String,
    val at: Long,
    val event: WebhookEvent,
    val title: String,
    val body: String,
    val privateTitle: String,
    val privateBody: String,
    val url: String = "/",
    val actions: List<ReminderAction> = emptyList(),
    /** What a webhook gets as `data` (names only where the hook includes details). */
    val data: JsonObject = JsonObject(emptyMap()),
    val detailedData: JsonObject = data,
    /** Doses: which, so "taken" and "skip" work from the notification. */
    val medicationId: String? = null,
    val scheduledFor: Long? = null,
    /** Push it (doses, refills, appointments, habits, the digest); false for webhook-only events. */
    val push: Boolean = true,
)
