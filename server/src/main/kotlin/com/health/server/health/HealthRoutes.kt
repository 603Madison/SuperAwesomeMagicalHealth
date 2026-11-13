package com.health.server.health

import com.health.api.CheckIn
import com.health.api.DeviceChoices
import com.health.api.FitImportResult
import com.health.api.IngestBatch
import com.health.api.MeasurementInput
import com.health.api.Metric
import com.health.api.Profile
import com.health.api.Sample
import com.health.api.SessionKind
import com.health.api.SleepSession
import com.health.api.UploadKey
import com.health.api.UploadKeyRequest
import com.health.api.Workout
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.auth.principal
import com.health.server.auth.publicBase
import com.health.server.auth.requireUser
import com.health.server.care.viewScope
import com.health.server.insights.DayService
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.Serializable

@Serializable
data class DeviceRename(val name: String)

/** Profile, days and trends, and the records people enter by hand. */
fun Route.healthRoutes(services: Services) {
    route("/api/profile") {
        get {
            val user = call.requireUser(services)
            val profile = services.profiles.get(user.id)
            call.respond(call.viewScope?.let { com.health.server.care.SharedView.limit(profile, it.modules) } ?: profile)
        }
        put {
            val user = call.requireUser(services)
            val before = services.profiles.get(user.id)
            val saved = services.profiles.put(user.id, call.receive<Profile>())
            // A new time zone moves every day's edges; anything else changes only scores and targets.
            if (saved.timezone != before.timezone) services.days.changedAll(user.id)
            call.respond(saved)
        }
    }

    get("/api/day/{date}") {
        val user = call.requireUser(services)
        val summary = services.days.summary(user.id, call.parameters["date"].orEmpty())
        // Someone viewing shared data sees only the features shared with them.
        call.respond(call.viewScope?.let { com.health.server.care.SharedView.limit(summary, it.modules) } ?: summary)
    }

    get("/api/trends") {
        val user = call.requireUser(services)
        val q = call.request.queryParameters
        val days = services.profiles.days(user.id)
        val trends = services.days.trends(
            user.id,
            q["from"]?.let { days.parse(it, services.clock()) },
            q["to"]?.let { days.parse(it, services.clock()) },
            q["days"]?.toIntOrNull(),
        )
        call.respond(call.viewScope?.let { v -> trends.copy(days = trends.days.map { com.health.server.care.SharedView.limit(it, v.modules) }) } ?: trends)
    }

    get("/api/series") {
        val user = call.requireUser(services)
        val q = call.request.queryParameters
        val metric = q["metric"]?.let { runCatching { Metric.valueOf(it) }.getOrNull() } ?: throw ApiError.badRequest("Which metric?")
        val to = q["to"]?.toLongOrNull() ?: services.clock()
        val from = q["from"]?.toLongOrNull() ?: (to - DAY_MS)
        if (to <= from || to - from > 400 * DAY_MS) throw ApiError.badRequest("A series covers up to 400 days")
        call.respond(services.days.series(user.id, metric, from, to, q["points"]?.toIntOrNull()?.coerceIn(10, 5000) ?: 400))
    }

    route("/api/measurements") {
        get {
            val user = call.requireUser(services)
            val q = call.request.queryParameters
            val metric = q["metric"]?.let { runCatching { Metric.valueOf(it) }.getOrNull() } ?: Metric.WEIGHT
            val days = (q["days"]?.toIntOrNull() ?: 365).coerceIn(1, 3650)
            val now = services.clock()
            call.respond(services.records.samples(user.id, now - days * DAY_MS, now + DAY_MS, listOf(metric)).map { it.toApi() }.reversed())
        }
        post {
            val user = call.requireUser(services)
            val input = call.receive<MeasurementInput>()
            if (input.metric.aggregation == com.health.api.Aggregation.SUM) throw ApiError.badRequest("Steps and calories come from devices or workouts")
            call.respond(services.ingest.manualSample(user.id, Sample(input.metric, input.at ?: services.clock(), value = input.value)))
        }
        delete("/{id}") {
            val user = call.requireUser(services)
            services.ingest.deleteSample(user.id, call.parameters["id"]?.toLongOrNull() ?: throw ApiError.notFound())
            call.respond(mapOf("ok" to true))
        }
    }

    route("/api/workouts") {
        get {
            val user = call.requireUser(services)
            val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 30).coerceIn(1, 3650)
            val now = services.clock()
            call.respond(services.records.workouts(user.id, now - days * DAY_MS, now + DAY_MS))
        }
        post {
            val user = call.requireUser(services)
            call.respond(services.ingest.manualWorkout(user.id, call.receive<Workout>().copy(id = null)))
        }
        put("/{id}") {
            val user = call.requireUser(services)
            call.respond(services.ingest.manualWorkout(user.id, call.receive<Workout>().copy(id = call.parameters["id"])))
        }
        delete("/{id}") {
            val user = call.requireUser(services)
            services.ingest.deleteWorkout(user.id, call.parameters["id"].orEmpty())
            call.respond(mapOf("ok" to true))
        }
    }

    route("/api/sleep") {
        get {
            val user = call.requireUser(services)
            val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 30).coerceIn(1, 3650)
            val now = services.clock()
            val from = now - days * DAY_MS
            val sessions = services.records.sleep(user.id, from, now + DAY_MS)
            val samples = services.records.samples(user.id, from - DAY_MS, now + DAY_MS, listOf(Metric.HEART_RATE, Metric.HRV))
            call.respond(sessions.reversed().map { DayService.summarize(it, samples) })
        }
        post {
            val user = call.requireUser(services)
            call.respond(services.ingest.manualSleep(user.id, call.receive<SleepSession>()))
        }
        delete("/{id}") {
            val user = call.requireUser(services)
            services.ingest.deleteSleep(user.id, call.parameters["id"].orEmpty())
            call.respond(mapOf("ok" to true))
        }
    }

    route("/api/checkins/{date}") {
        get {
            val user = call.requireUser(services)
            val date = services.profiles.days(user.id).parse(call.parameters["date"].orEmpty(), services.clock())
            call.respond(services.checkIns.get(user.id, date))
        }
        put {
            val user = call.requireUser(services)
            val date = services.profiles.days(user.id).parse(call.parameters["date"].orEmpty(), services.clock())
            call.respond(services.checkIns.put(user.id, date, call.receive<CheckIn>()))
        }
    }
}

/** Devices, upload keys, and data coming in from them. */
fun Route.deviceRoutes(services: Services) {
    route("/api/devices") {
        get {
            val user = call.requireUser(services)
            call.respond(services.devices.list(user.id))
        }
        // Which device to use for a reading (none: combine them). Never shared (see SharedView).
        get("/choices") {
            val user = call.requireUser(services)
            call.respond(services.deviceChoices.get(user.id))
        }
        put("/choices") {
            val user = call.requireUser(services)
            call.respond(services.deviceChoices.put(user.id, call.receive<DeviceChoices>()))
        }
        patch("/{id}") {
            val user = call.requireUser(services)
            val renamed = services.devices.rename(user.id, call.parameters["id"].orEmpty(), call.receive<DeviceRename>().name)
            // Days name the devices behind their combined numbers (and a name can say it's a ring): work them out again.
            services.days.changedAll(user.id)
            call.respond(renamed)
        }
        delete("/{id}") {
            val user = call.requireUser(services)
            val id = call.parameters["id"].orEmpty()
            services.devices.delete(user.id, id)
            services.deviceChoices.deviceDeleted(user.id, id)
            services.ingest.deviceDeleted(user.id)
            call.respond(mapOf("ok" to true))
        }
        post("/upload-keys") {
            val user = call.requireUser(services)
            val name = runCatching { call.receive<UploadKeyRequest>() }.getOrDefault(UploadKeyRequest()).name.trim().take(80).ifBlank { "Upload key" }
            val token = services.sessions.create(user.id, SessionKind.UPLOAD, name)
            val id = com.health.server.auth.SessionStore.idOf(token)
            call.respond(UploadKey(id, name, token, "${publicBase(services, call)}/api/ingest"))
        }
    }

    post("/api/ingest") {
        val user = call.principal(services, allowUpload = true)?.user ?: throw ApiError.unauthorized()
        call.respond(services.ingest.ingest(user.id, call.receive<IngestBatch>()))
    }

    post("/api/import/fit") {
        val user = call.principal(services, allowUpload = true)?.user ?: throw ApiError.unauthorized()
        val files = mutableListOf<Pair<String, ByteArray>>()
        var total = 0L
        call.receiveMultipart(formFieldLimit = MAX_FIT_BYTES).forEachPart { part ->
            try {
                if (part is PartData.FileItem) {
                    val bytes = part.provider().toByteArray()
                    total += bytes.size
                    if (bytes.size > MAX_FIT_BYTES || total > MAX_UPLOAD_BYTES) throw ApiError.badRequest("Upload at most 100 MB of FIT files at a time")
                    if (files.size >= 500) throw ApiError.badRequest("Upload at most 500 files at a time")
                    files += (part.originalFileName ?: "file.fit") to bytes
                }
            } finally {
                part.dispose()
            }
        }
        val result: FitImportResult = services.fit.import(user.id, files)
        call.respond(result)
    }
}

private const val DAY_MS = 24L * 3600 * 1000
private const val MAX_FIT_BYTES = 25L * 1024 * 1024
private const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024
