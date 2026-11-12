package com.health.server.neuro

import com.health.api.ApiJson
import com.health.api.BodyChange
import com.health.api.CommunicationCard
import com.health.api.EnergyDay
import com.health.api.FocusSession
import com.health.api.GenderMoment
import com.health.api.HairRemovalSession
import com.health.api.Metric
import com.health.api.Milestone
import com.health.api.NeuroCheckIn
import com.health.api.NeuroProfile
import com.health.api.OverloadEvent
import com.health.api.Photo
import com.health.api.Routine
import com.health.api.RoutineRun
import com.health.api.TransitionProfile
import com.health.api.VoiceSession
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.auth.requireUser
import com.health.server.health.FitExport
import com.health.server.modules.EntryKind
import com.health.server.photos.Images
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Autism & ADHD, transition, progress photos and the FIT export (docs/API.md). */
fun Route.neuroTransitionRoutes(services: Services) {
    fun ApplicationCall.days(default: Int) = request.queryParameters["days"]?.toIntOrNull() ?: default
    fun ApplicationCall.id() = parameters["id"] ?: throw ApiError.notFound()
    val ok = mapOf("ok" to true)
    val neuro = services.neuro
    val transition = services.transition
    val photos = services.photos

    route("/api/neuro") {
        get("/profile") { call.respond(neuro.profile(call.requireUser(services).id)) }
        put("/profile") { call.respond(neuro.saveProfile(call.requireUser(services).id, call.receive<NeuroProfile>())) }
        route("/events") {
            get { call.respond(neuro.events(call.requireUser(services).id, call.days(90))) }
            post { call.respond(neuro.saveEvent(call.requireUser(services).id, call.receive<OverloadEvent>())) }
            put("/{id}") { call.respond(neuro.saveEvent(call.requireUser(services).id, call.receive<OverloadEvent>(), call.id())) }
            delete("/{id}") { services.entries.delete(call.requireUser(services).id, EntryKind.NEURO_EVENT, call.id()); call.respond(ok) }
        }
        get("/energy") { call.respond(neuro.energy(call.requireUser(services).id, call.days(30))) }
        put("/energy/{date}") { call.respond(neuro.saveEnergy(call.requireUser(services).id, call.parameters["date"].orEmpty(), call.receive<EnergyDay>())) }
        route("/check-ins") {
            get { call.respond(neuro.checkIns(call.requireUser(services).id, call.days(30))) }
            post { call.respond(neuro.saveCheckIn(call.requireUser(services).id, call.receive<NeuroCheckIn>())) }
            delete("/{id}") { services.entries.delete(call.requireUser(services).id, EntryKind.NEURO_CHECKIN, call.id()); call.respond(ok) }
        }
        route("/focus") {
            get { call.respond(neuro.focus(call.requireUser(services).id, call.days(30))) }
            post { call.respond(neuro.saveFocus(call.requireUser(services).id, call.receive<FocusSession>())) }
            put("/{id}") { call.respond(neuro.saveFocus(call.requireUser(services).id, call.receive<FocusSession>(), call.id())) }
            delete("/{id}") { services.entries.delete(call.requireUser(services).id, EntryKind.NEURO_FOCUS, call.id()); call.respond(ok) }
        }
        route("/routines") {
            get { call.respond(neuro.routines(call.requireUser(services).id)) }
            post { call.respond(neuro.saveRoutine(call.requireUser(services).id, call.receive<Routine>())) }
            get("/runs") { call.respond(neuro.runs(call.requireUser(services).id, call.days(14))) }
            put("/{id}") { call.respond(neuro.saveRoutine(call.requireUser(services).id, call.receive<Routine>(), call.id())) }
            delete("/{id}") { neuro.deleteRoutine(call.requireUser(services).id, call.id()); call.respond(ok) }
            put("/{id}/runs/{date}") {
                call.respond(neuro.saveRun(call.requireUser(services).id, call.id(), call.parameters["date"].orEmpty(), call.receive<RoutineRun>()))
            }
        }
        get("/cards") { call.respond(neuro.cards(call.requireUser(services).id)) }
        put("/cards") { call.respond(neuro.saveCards(call.requireUser(services).id, call.receive<List<CommunicationCard>>())) }
        get("/summary") { call.respond(neuro.summary(call.requireUser(services).id, call.days(30))) }
    }

    route("/api/transition") {
        get { call.respond(transition.overview(call.requireUser(services).id)) }
        get("/profile") { call.respond(transition.profile(call.requireUser(services).id)) }
        put("/profile") { call.respond(transition.saveProfile(call.requireUser(services).id, call.receive<TransitionProfile>())) }
        get("/templates") { call.requireUser(services); call.respond(transition.templates()) }
        route("/milestones") {
            get { call.respond(transition.milestones(call.requireUser(services).id)) }
            post { call.respond(transition.saveMilestone(call.requireUser(services).id, call.receive<Milestone>())) }
            put("/{id}") { call.respond(transition.saveMilestone(call.requireUser(services).id, call.receive<Milestone>(), call.id())) }
            delete("/{id}") { transition.delete(call.requireUser(services).id, EntryKind.TRANSITION_MILESTONE, call.id()); call.respond(ok) }
        }
        route("/changes") {
            get { call.respond(transition.changes(call.requireUser(services).id)) }
            post { call.respond(transition.saveChange(call.requireUser(services).id, call.receive<BodyChange>())) }
            put("/{id}") { call.respond(transition.saveChange(call.requireUser(services).id, call.receive<BodyChange>(), call.id())) }
            delete("/{id}") { transition.delete(call.requireUser(services).id, EntryKind.TRANSITION_CHANGE, call.id()); call.respond(ok) }
        }
        route("/voice") {
            get { call.respond(transition.voice(call.requireUser(services).id, call.days(180))) }
            post { call.respond(transition.saveVoice(call.requireUser(services).id, call.receive<VoiceSession>())) }
            delete("/{id}") { transition.delete(call.requireUser(services).id, EntryKind.TRANSITION_VOICE, call.id()); call.respond(ok) }
        }
        route("/hair-removal") {
            get { call.respond(transition.hairRemoval(call.requireUser(services).id, call.days(365))) }
            post { call.respond(transition.saveHairRemoval(call.requireUser(services).id, call.receive<HairRemovalSession>())) }
            delete("/{id}") { transition.delete(call.requireUser(services).id, EntryKind.TRANSITION_HAIR, call.id()); call.respond(ok) }
        }
        route("/moments") {
            get { call.respond(transition.moments(call.requireUser(services).id, call.days(90))) }
            post { call.respond(transition.saveMoment(call.requireUser(services).id, call.receive<GenderMoment>())) }
            delete("/{id}") { transition.delete(call.requireUser(services).id, EntryKind.TRANSITION_MOMENT, call.id()); call.respond(ok) }
        }
    }

    route("/api/photos") {
        get {
            val user = call.requireUser(services)
            call.respond(photos.list(user.id, call.request.queryParameters["album"]?.takeIf { it.isNotBlank() }, call.request.queryParameters["days"]?.toIntOrNull()))
        }
        get("/albums") { call.respond(photos.albums(call.requireUser(services).id)) }
        post {
            val user = call.requireUser(services)
            call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let {
                if (it > Images.MAX_BYTES + 256 * 1024) throw ApiError(HttpStatusCode.PayloadTooLarge, "Photos can be up to 20 MB")
            }
            var bytes: ByteArray? = null
            var meta: Photo? = null
            call.receiveMultipart(formFieldLimit = 256 * 1024).forEachPart { part ->
                try {
                    when {
                        part is PartData.FileItem && part.name == "file" -> {
                            val read = part.provider().readRemaining(Images.MAX_BYTES + 1L).readByteArray()
                            if (read.size > Images.MAX_BYTES) throw ApiError(HttpStatusCode.PayloadTooLarge, "Photos can be up to 20 MB")
                            bytes = read
                        }
                        part is PartData.FormItem && part.name == "photo" ->
                            meta = runCatching { ApiJson.decodeFromString(Photo.serializer(), part.value) }.getOrElse { throw ApiError.badRequest("The photo's details aren't right") }
                    }
                } finally {
                    part.dispose()
                }
            }
            val file = bytes ?: throw ApiError.badRequest("Send the picture as `file`")
            call.respond(photos.upload(user.id, file, meta ?: Photo(album = "Progress", at = services.clock())))
        }
        get("/{id}/image") { call.respondPhoto(photos.image(call.requireUser(services).id, call.id(), thumb = false)) }
        get("/{id}/thumb") { call.respondPhoto(photos.image(call.requireUser(services).id, call.id(), thumb = true)) }
        put("/{id}") { call.respond(photos.update(call.requireUser(services).id, call.id(), call.receive<Photo>())) }
        delete("/{id}") { photos.delete(call.requireUser(services).id, call.id()); call.respond(ok) }
    }

    get("/api/export/fit") {
        val user = call.requireUser(services)
        val zone = services.profiles.days(user.id)
        val today = zone.today(services.clock())
        fun date(name: String) = call.request.queryParameters[name]?.takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDate.parse(it) }.getOrNull() ?: throw ApiError.badRequest("Days look like 2026-03-20")
        }
        val to = date("to") ?: today
        val from = date("from") ?: to.minusDays(364)
        if (from.isAfter(to)) throw ApiError.badRequest("The start comes after the end")
        if (ChronoUnit.DAYS.between(from, to) > 3 * 366) throw ApiError.badRequest("An export covers three years at most")
        val workouts = services.records.workouts(user.id, zone.start(from), zone.end(to))
        val weights = services.records.samples(user.id, zone.start(from), zone.end(to), listOf(Metric.WEIGHT, Metric.BODY_FAT))
        val zip = FitExport.zip(workouts, weights)
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "superawesomemagicalhealth-fit-$from-to-$to.zip").toString())
        call.response.header(HttpHeaders.CacheControl, "private, no-store")
        call.respondBytes(zip, ContentType.Application.Zip)
    }
}

private suspend fun ApplicationCall.respondPhoto(bytes: ByteArray) {
    response.header(HttpHeaders.CacheControl, "private, no-store")
    response.header(HttpHeaders.ContentDisposition, ContentDisposition.Inline.toString())
    respondBytes(bytes, ContentType.Image.JPEG)
}
