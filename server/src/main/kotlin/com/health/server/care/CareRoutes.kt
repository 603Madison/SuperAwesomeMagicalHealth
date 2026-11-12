package com.health.server.care

import com.health.api.Appointment
import com.health.api.Contraction
import com.health.api.DoseChange
import com.health.api.Feed
import com.health.api.HormoneSettings
import com.health.api.HotFlash
import com.health.api.KickSession
import com.health.api.LifeStageInfo
import com.health.api.ShareChange
import com.health.api.ShareRequest
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.auth.requireUser
import com.health.server.modules.EntryKind
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Hormone therapy, life stages, appointments and sharing (docs/API.md). */
fun Route.careRoutes(services: Services) {
    fun ApplicationCall.days(default: Int) = request.queryParameters["days"]?.toIntOrNull() ?: default
    fun ApplicationCall.id() = parameters["id"] ?: throw ApiError.notFound()
    val ok = mapOf("ok" to true)
    val hormones = services.hormones
    val stages = services.lifeStages
    val appointments = services.appointments

    route("/api/hormones") {
        get { call.respond(hormones.overview(call.requireUser(services).id)) }
        put("/settings") { call.respond(hormones.saveSettings(call.requireUser(services).id, call.receive<HormoneSettings>())) }
        post("/changes") { call.respond(hormones.addChange(call.requireUser(services).id, call.receive<DoseChange>())) }
        delete("/changes/{id}") { hormones.deleteChange(call.requireUser(services).id, call.id()); call.respond(ok) }
    }

    route("/api/cycle") {
        get("/stage") { call.respond(stages.info(call.requireUser(services).id)) }
        put("/stage") { call.respond(stages.saveInfo(call.requireUser(services).id, call.receive<LifeStageInfo>())) }
        get("/stage/overview") { call.respond(stages.overview(call.requireUser(services).id, call.days(30))) }
        route("/kicks") {
            get { call.respond(stages.kicks(call.requireUser(services).id, call.days(30))) }
            post { call.respond(stages.saveKicks(call.requireUser(services).id, call.receive<KickSession>())) }
            put("/{id}") { call.respond(stages.saveKicks(call.requireUser(services).id, call.receive<KickSession>(), call.id())) }
            delete("/{id}") { stages.delete(call.requireUser(services).id, EntryKind.KICKS, call.id()); call.respond(ok) }
        }
        route("/contractions") {
            get { call.respond(stages.contractions(call.requireUser(services).id, call.days(2))) }
            post { call.respond(stages.saveContraction(call.requireUser(services).id, call.receive<Contraction>())) }
            put("/{id}") { call.respond(stages.saveContraction(call.requireUser(services).id, call.receive<Contraction>(), call.id())) }
            delete("/{id}") { stages.delete(call.requireUser(services).id, EntryKind.CONTRACTION, call.id()); call.respond(ok) }
        }
        route("/feeds") {
            get { call.respond(stages.feeds(call.requireUser(services).id, call.days(7))) }
            post { call.respond(stages.saveFeed(call.requireUser(services).id, call.receive<Feed>())) }
            put("/{id}") { call.respond(stages.saveFeed(call.requireUser(services).id, call.receive<Feed>(), call.id())) }
            delete("/{id}") { stages.delete(call.requireUser(services).id, EntryKind.FEED, call.id()); call.respond(ok) }
        }
        route("/hot-flashes") {
            get { call.respond(stages.hotFlashes(call.requireUser(services).id, call.days(30))) }
            post { call.respond(stages.saveHotFlash(call.requireUser(services).id, call.receive<HotFlash>())) }
            delete("/{id}") { stages.delete(call.requireUser(services).id, EntryKind.HOT_FLASH, call.id()); call.respond(ok) }
        }
    }

    route("/api/appointments") {
        get {
            val upcoming = call.request.queryParameters["upcoming"] == "true"
            call.respond(appointments.list(call.requireUser(services).id, call.days(365), upcoming))
        }
        post { call.respond(appointments.save(call.requireUser(services).id, call.receive<Appointment>())) }
        get("/{id}") { call.respond(appointments.get(call.requireUser(services).id, call.id())) }
        get("/{id}/suggestions") { call.respond(appointments.suggestions(call.requireUser(services).id, call.id())) }
        put("/{id}") { call.respond(appointments.save(call.requireUser(services).id, call.receive<Appointment>(), call.id())) }
        delete("/{id}") { appointments.delete(call.requireUser(services).id, call.id()); call.respond(ok) }
    }

    route("/api/shares") {
        get { call.respond(services.sharing.list(call.requireUser(services).id)) }
        post { call.respond(services.sharing.share(call.requireUser(services), call.receive<ShareRequest>())) }
        put("/{id}") { call.respond(services.sharing.change(call.requireUser(services).id, call.id(), call.receive<ShareChange>())) }
        delete("/{id}") { services.sharing.delete(call.requireUser(services).id, call.id()); call.respond(ok) }
    }
}
