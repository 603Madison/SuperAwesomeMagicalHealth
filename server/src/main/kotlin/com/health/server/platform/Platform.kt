package com.health.server.platform

import com.health.api.DoseStatus
import com.health.api.Module
import com.health.api.SessionKind
import com.health.api.User
import com.health.server.Services
import com.health.server.util.Days
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import kotlinx.serialization.json.JsonObject

/** Reminders, push, webhooks, digests, reports, exports and the watch: built once, on first use. */
class Platform(private val services: Services) {
    val push = PushService(services.db, services.box, services.http, { pushSubject() }, services.clock)
    val webhooks = WebhookService(services.db, services.box, services.http, { services.settings.get().webhooksPrivateNetworks }, services.clock)
    val smtpPassword = SmtpPassword(services.db, services.box)

    /** Sends email; tests swap in one that only remembers. */
    var mailer: Mailer = SmtpMailer({ services.settings.get().smtp }, { smtpPassword.get() })

    val digests = DigestService(services)
    val reports = ReportService(services)
    val exports = ExportService(services)
    val watch = WatchService(services)
    val scheduler = Scheduler(services)

    /** Who push services may contact about this server (RFC 8292 asks for one). */
    private suspend fun pushSubject(): String {
        val url = services.settings.get().publicUrl
        return if (url.startsWith("https://")) url else "mailto:admin@localhost"
    }

    /**
     * Today's numbers for Home Assistant, flat. Sensitive features give counts and times only,
     * never names.
     */
    suspend fun state(userId: String): JsonObject {
        val profile = services.profiles.get(userId)
        val zone = Days(Days.zoneOf(profile.timezone))
        val day = services.days.summary(userId, "today")
        val on = profile.modules
        val devices = services.devices.list(userId).filter { it.battery != null }
        val meds = if (Module.MEDICATIONS in on) services.modules.meds.day(userId, zone.today(services.clock())) else null
        val now = services.clock()
        val open = meds?.due?.filter { it.log == null }.orEmpty()
        // Water turned off: its keys are left out (a null drops the key).
        val water = services.days.showsWater(userId)
        return json(
            "date" to day.date,
            "readiness" to day.readiness?.score,
            "readiness_label" to day.readiness?.label,
            "sleep_score" to day.sleep?.score,
            "activity_score" to day.activity?.score,
            "sleep_minutes" to day.sleepSummary?.asleepMinutes,
            "steps" to day.steps,
            "step_goal" to day.goals.steps,
            "active_calories" to day.activeCalories.toInt(),
            "resting_hr" to day.restingHeartRate?.let { Math.round(it) },
            "hrv" to day.hrv?.let { Math.round(it) },
            "stress_state" to day.stressDay?.spans?.lastOrNull()?.state?.name?.lowercase(),
            "water_ml" to day.waterMl.takeIf { water },
            "water_goal" to day.goals.waterMl.takeIf { water },
            "weight_kg" to day.weightKg,
            "doses_left_today" to meds?.let { open.size },
            "doses_taken_today" to meds?.let { it.due.count { d -> d.log?.status == DoseStatus.TAKEN } },
            "next_dose_at" to open.firstOrNull { it.scheduledFor >= now - 2 * 3_600_000L }?.let { Csv.iso(it.scheduledFor, zone.zone) },
            "device_battery_min" to devices.minOfOrNull { it.battery!! },
            "devices" to JsonObject(devices.associate { it.name to jsonOf(it.battery) }),
            "updated_at" to Csv.iso(now, zone.zone),
        )
    }
}

/** The account behind an `Authorization: Bearer` key of [kind] (a Home Assistant or watch key). */
suspend fun ApplicationCall.keyUser(services: Services, kind: SessionKind): User? {
    val token = request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim() ?: return null
    val (user, session) = services.sessions.resolve(token) ?: return null
    return user.takeIf { session.kind == kind }
}
