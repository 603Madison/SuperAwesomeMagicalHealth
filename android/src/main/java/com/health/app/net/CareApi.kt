package com.health.app.net

import com.health.api.ApiJson
import com.health.api.Appointment
import com.health.api.Contraction
import com.health.api.Digest
import com.health.api.DoseChange
import com.health.api.Feed
import com.health.api.HabitStatus
import com.health.api.HormoneOverview
import com.health.api.HormoneSettings
import com.health.api.HotFlash
import com.health.api.KickSession
import com.health.api.LifeStageInfo
import com.health.api.LifeStageOverview
import com.health.api.ReportSectionKey
import com.health.api.Share
import com.health.api.ShareChange
import com.health.api.ShareRequest
import com.health.api.Shares
import com.health.api.SuggestedQuestion
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate

/*
 * Hormone therapy, life stages, appointments, sharing, the weekly digest, reports and exports:
 * the server's endpoints for them (docs/API.md), as extensions on [ServerApi].
 */

// ---- Hormone therapy ----

suspend fun ServerApi.hormones(): HormoneOverview = get("${base()}/api/hormones", HormoneOverview.serializer())

suspend fun ServerApi.saveHormoneSettings(settings: HormoneSettings): HormoneSettings =
    send("PUT", "${base()}/api/hormones/settings", ApiJson.encodeToString(HormoneSettings.serializer(), settings), HormoneSettings.serializer())

suspend fun ServerApi.addDoseChange(change: DoseChange): DoseChange = post("/api/hormones/changes", change, DoseChange.serializer())

suspend fun ServerApi.deleteDoseChange(id: String) = delete("/api/hormones/changes/${enc(id)}")

// ---- Life stages ----

suspend fun ServerApi.lifeStage(): LifeStageInfo = get("${base()}/api/cycle/stage", LifeStageInfo.serializer())

suspend fun ServerApi.saveLifeStage(info: LifeStageInfo): LifeStageInfo =
    send("PUT", "${base()}/api/cycle/stage", ApiJson.encodeToString(LifeStageInfo.serializer(), info), LifeStageInfo.serializer())

suspend fun ServerApi.lifeStageOverview(days: Int = 30): LifeStageOverview =
    get("${base()}/api/cycle/stage/overview?days=$days", LifeStageOverview.serializer())

suspend fun ServerApi.saveKicks(session: KickSession): KickSession = session.id?.let { id ->
    send("PUT", "${base()}/api/cycle/kicks/${enc(id)}", ApiJson.encodeToString(KickSession.serializer(), session), KickSession.serializer())
} ?: post("/api/cycle/kicks", session, KickSession.serializer())

suspend fun ServerApi.deleteKicks(id: String) = delete("/api/cycle/kicks/${enc(id)}")

suspend fun ServerApi.saveContraction(contraction: Contraction): Contraction = contraction.id?.let { id ->
    send("PUT", "${base()}/api/cycle/contractions/${enc(id)}", ApiJson.encodeToString(Contraction.serializer(), contraction), Contraction.serializer())
} ?: post("/api/cycle/contractions", contraction, Contraction.serializer())

suspend fun ServerApi.deleteContraction(id: String) = delete("/api/cycle/contractions/${enc(id)}")

suspend fun ServerApi.saveFeed(feed: Feed): Feed = feed.id?.let { id ->
    send("PUT", "${base()}/api/cycle/feeds/${enc(id)}", ApiJson.encodeToString(Feed.serializer(), feed), Feed.serializer())
} ?: post("/api/cycle/feeds", feed, Feed.serializer())

suspend fun ServerApi.deleteFeed(id: String) = delete("/api/cycle/feeds/${enc(id)}")

suspend fun ServerApi.addHotFlash(flash: HotFlash): HotFlash = post("/api/cycle/hot-flashes", flash, HotFlash.serializer())

suspend fun ServerApi.deleteHotFlash(id: String) = delete("/api/cycle/hot-flashes/${enc(id)}")

// ---- Appointments ----

suspend fun ServerApi.appointments(days: Int = 365, upcoming: Boolean = false): List<Appointment> =
    get("${base()}/api/appointments?days=$days" + (if (upcoming) "&upcoming=true" else ""), ListSerializer(Appointment.serializer()))

suspend fun ServerApi.saveAppointment(appointment: Appointment): Appointment = appointment.id?.let { id ->
    send("PUT", "${base()}/api/appointments/${enc(id)}", ApiJson.encodeToString(Appointment.serializer(), appointment), Appointment.serializer())
} ?: post("/api/appointments", appointment, Appointment.serializer())

suspend fun ServerApi.deleteAppointment(id: String) = delete("/api/appointments/${enc(id)}")

suspend fun ServerApi.appointmentSuggestions(id: String): List<SuggestedQuestion> =
    get("${base()}/api/appointments/${enc(id)}/suggestions", ListSerializer(SuggestedQuestion.serializer()))

// ---- Sharing ----

suspend fun ServerApi.shares(): Shares = get("${base()}/api/shares", Shares.serializer())

suspend fun ServerApi.share(request: ShareRequest): Share =
    send("POST", "${base()}/api/shares", ApiJson.encodeToString(ShareRequest.serializer(), request), Share.serializer())

suspend fun ServerApi.changeShare(id: String, change: ShareChange): Share =
    send("PUT", "${base()}/api/shares/${enc(id)}", ApiJson.encodeToString(ShareChange.serializer(), change), Share.serializer())

suspend fun ServerApi.deleteShare(id: String) = delete("/api/shares/${enc(id)}")

// ---- Weekly digest ----

suspend fun ServerApi.latestDigest(): Digest = get("${base()}/api/digest/latest", Digest.serializer())

suspend fun ServerApi.digest(weekStart: LocalDate): Digest = get("${base()}/api/digest?week=$weekStart", Digest.serializer())

suspend fun ServerApi.emailDigestTest() = send("POST", "${base()}/api/digest/email-test", "{}", Unit.serializer())

// ---- Reports and export ----

/** The doctor's report for [from]..[to], as a PDF; [sections] null means every feature that's on. */
suspend fun ServerApi.reportPdf(from: LocalDate, to: LocalDate, sections: Set<ReportSectionKey>?): ByteArray =
    bytes("/api/report.pdf?from=$from&to=$to" + (sections?.let { s -> "&sections=" + s.joinToString(",") { it.name } } ?: ""))

/** Everything in [from]..[to] as CSV files and a FHIR bundle, zipped. */
suspend fun ServerApi.exportZip(from: LocalDate, to: LocalDate): ByteArray = bytes("/api/export.zip?from=$from&to=$to")

// ---- Reminders ----

/** Today's habits with whether each is done, for habit reminders. */
suspend fun ServerApi.habitsForReminders(date: LocalDate): List<HabitStatus> =
    get("${base()}/api/habits/today?date=$date", ListSerializer(HabitStatus.serializer()))
