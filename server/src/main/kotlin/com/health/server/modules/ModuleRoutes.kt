package com.health.server.modules

import com.health.api.ContinenceEntry
import com.health.api.CycleDay
import com.health.api.DoseLog
import com.health.api.JournalEntry
import com.health.api.LabResult
import com.health.api.Medication
import com.health.api.MindfulnessSession
import com.health.api.MoodEntry
import com.health.api.ScreenerResult
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.auth.requireUser
import com.health.server.util.parseDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** The health modules' API: mind, cycle, bladder and bowel, medications and labs (docs/API.md). */
fun Route.moduleRoutes(services: Services) {
    val m = services.modules
    fun ApplicationCall.days(default: Int) = request.queryParameters["days"]?.toIntOrNull() ?: default
    fun ApplicationCall.id() = parameters["id"] ?: throw ApiError.notFound()
    val ok = mapOf("ok" to true)

    route("/api/mind") {
        route("/moods") {
            get { call.respond(m.mind.moods(call.requireUser(services).id, call.days(30))) }
            post { call.respond(m.mind.saveMood(call.requireUser(services).id, call.receive<MoodEntry>())) }
            put("/{id}") { call.respond(m.mind.saveMood(call.requireUser(services).id, call.receive<MoodEntry>(), call.id())) }
            delete("/{id}") { m.mind.delete(call.requireUser(services).id, EntryKind.MOOD, call.id()); call.respond(ok) }
        }
        route("/journal") {
            get { call.respond(m.mind.journal(call.requireUser(services).id, call.days(90))) }
            post { call.respond(m.mind.saveJournal(call.requireUser(services).id, call.receive<JournalEntry>())) }
            put("/{id}") { call.respond(m.mind.saveJournal(call.requireUser(services).id, call.receive<JournalEntry>(), call.id())) }
            delete("/{id}") { m.mind.delete(call.requireUser(services).id, EntryKind.JOURNAL, call.id()); call.respond(ok) }
        }
        route("/screeners") {
            get { call.requireUser(services); call.respond(MindService.SCREENERS) }
            get("/results") { call.respond(m.mind.screenerResults(call.requireUser(services).id, call.days(365))) }
            post("/results") { call.respond(m.mind.submit(call.requireUser(services).id, call.receive<ScreenerResult>())) }
            delete("/results/{id}") { m.mind.delete(call.requireUser(services).id, EntryKind.SCREENER, call.id()); call.respond(ok) }
        }
        route("/sessions") {
            get { call.respond(m.mind.sessions(call.requireUser(services).id, call.days(30))) }
            post { call.respond(m.mind.saveSession(call.requireUser(services).id, call.receive<MindfulnessSession>())) }
            delete("/{id}") { m.mind.delete(call.requireUser(services).id, EntryKind.MINDFUL, call.id()); call.respond(ok) }
        }
        get("/summary") { call.respond(m.mind.summary(call.requireUser(services).id, call.days(30))) }
        get("/resources") { call.requireUser(services); call.respond(m.mind.resources()) }
    }

    route("/api/cycle") {
        get {
            val months = call.request.queryParameters["months"]?.toIntOrNull() ?: 12
            call.respond(m.cycle.overview(call.requireUser(services).id, months))
        }
        put("/days/{date}") {
            val user = call.requireUser(services)
            val date = parseDate(call.parameters["date"].orEmpty())
            call.respond(m.cycle.put(user.id, call.receive<CycleDay>().copy(date = date.toString())))
        }
        delete("/days/{date}") {
            m.cycle.delete(call.requireUser(services).id, parseDate(call.parameters["date"].orEmpty()))
            call.respond(ok)
        }
    }

    route("/api/continence") {
        route("/entries") {
            get { call.respond(m.continence.list(call.requireUser(services).id, call.days(7))) }
            post { call.respond(m.continence.save(call.requireUser(services).id, call.receive<ContinenceEntry>())) }
            put("/{id}") { call.respond(m.continence.save(call.requireUser(services).id, call.receive<ContinenceEntry>(), call.id())) }
            delete("/{id}") { m.continence.delete(call.requireUser(services).id, call.id()); call.respond(ok) }
        }
        get("/summary") { call.respond(m.continence.summary(call.requireUser(services).id, call.days(14))) }
        get("/wearing") { call.respond(m.continence.wearing(call.requireUser(services).id)) }
        get("/diary") {
            val user = call.requireUser(services)
            val zone = services.profiles.days(user.id)
            val date = call.request.queryParameters["date"]?.let { zone.parse(it, services.clock()) } ?: zone.today(services.clock())
            call.respond(m.continence.diary(user.id, date))
        }
        get("/products") { call.respond(m.continence.products(call.requireUser(services).id, call.days(90))) }
    }

    route("/api/meds") {
        get { call.respond(m.meds.list(call.requireUser(services).id, call.request.queryParameters["all"] == "true")) }
        post { call.respond(m.meds.save(call.requireUser(services).id, call.receive<Medication>())) }
        get("/day/{date}") {
            val user = call.requireUser(services)
            call.respond(m.meds.day(user.id, services.profiles.days(user.id).parse(call.parameters["date"].orEmpty(), services.clock())))
        }
        route("/doses") {
            get {
                val user = call.requireUser(services)
                val zone = services.profiles.days(user.id)
                val to = zone.today(services.clock()).plusDays(1)
                call.respond(m.meds.doses(user.id, to.minusDays(call.days(30).coerceIn(1, 3650).toLong()), to).reversed())
            }
            post { call.respond(m.meds.logDose(call.requireUser(services).id, call.receive<DoseLog>())) }
            delete("/{id}") { m.meds.deleteDose(call.requireUser(services).id, call.id()); call.respond(ok) }
        }
        put("/{id}") { call.respond(m.meds.save(call.requireUser(services).id, call.receive<Medication>(), call.id())) }
        delete("/{id}") { m.meds.delete(call.requireUser(services).id, call.id()); call.respond(ok) }
    }

    route("/api/labs") {
        get { call.respond(m.meds.labs(call.requireUser(services).id, call.days(3650))) }
        post { call.respond(m.meds.saveLab(call.requireUser(services).id, call.receive<LabResult>())) }
        delete("/{id}") { m.meds.deleteLab(call.requireUser(services).id, call.id()); call.respond(ok) }
    }
}
