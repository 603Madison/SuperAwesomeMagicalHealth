package com.health.server.tracking

import com.health.api.BloodPressure
import com.health.api.Drink
import com.health.api.GlucoseReading
import com.health.api.Habit
import com.health.api.HabitTick
import com.health.api.Migraine
import com.health.api.SymptomEntry
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

/** Blood pressure and glucose, drinks, symptoms, habits and place search (docs/API.md). */
fun Route.trackingRoutes(services: Services) {
    val t = services.tracking
    fun ApplicationCall.days(default: Int) = request.queryParameters["days"]?.toIntOrNull() ?: default
    fun ApplicationCall.id() = parameters["id"] ?: throw ApiError.notFound()
    val ok = mapOf("ok" to true)

    route("/api/vitals") {
        route("/bp") {
            get { call.respond(t.vitals.bloodPressure(call.requireUser(services).id, call.days(30))) }
            post { call.respond(t.vitals.saveBloodPressure(call.requireUser(services).id, call.receive<BloodPressure>())) }
            delete("/{id}") { t.vitals.delete(call.requireUser(services).id, "BP", call.id()); call.respond(ok) }
        }
        route("/glucose") {
            get { call.respond(t.vitals.glucose(call.requireUser(services).id, call.days(14))) }
            post { call.respond(t.vitals.saveGlucose(call.requireUser(services).id, call.receive<GlucoseReading>())) }
            delete("/{id}") { t.vitals.delete(call.requireUser(services).id, "GLUCOSE", call.id()); call.respond(ok) }
        }
        get("/summary") { call.respond(t.vitals.summary(call.requireUser(services).id, call.days(30))) }
    }

    route("/api/drinks") {
        get("/summary") { call.respond(t.drinks.summary(call.requireUser(services).id, call.days(30))) }
        get("/{date}") {
            val user = call.requireUser(services)
            call.respond(t.drinks.day(user.id, services.profiles.days(user.id).parse(call.parameters["date"].orEmpty(), services.clock())))
        }
        post { call.respond(t.drinks.save(call.requireUser(services).id, call.receive<Drink>())) }
        put("/{id}") { call.respond(t.drinks.save(call.requireUser(services).id, call.receive<Drink>(), call.id())) }
        delete("/{id}") { t.drinks.delete(call.requireUser(services).id, call.id()); call.respond(ok) }
    }

    route("/api/symptoms") {
        route("/entries") {
            get { call.respond(t.symptoms.entries(call.requireUser(services).id, call.days(30))) }
            post { call.respond(t.symptoms.save(call.requireUser(services).id, call.receive<SymptomEntry>())) }
            put("/{id}") { call.respond(t.symptoms.save(call.requireUser(services).id, call.receive<SymptomEntry>(), call.id())) }
            delete("/{id}") { t.symptoms.delete(call.requireUser(services).id, EntryKind.SYMPTOM, call.id()); call.respond(ok) }
        }
        route("/migraines") {
            get { call.respond(t.symptoms.migraines(call.requireUser(services).id, call.days(90))) }
            post { call.respond(t.symptoms.saveMigraine(call.requireUser(services).id, call.receive<Migraine>())) }
            put("/{id}") { call.respond(t.symptoms.saveMigraine(call.requireUser(services).id, call.receive<Migraine>(), call.id())) }
            delete("/{id}") { t.symptoms.delete(call.requireUser(services).id, EntryKind.MIGRAINE, call.id()); call.respond(ok) }
        }
        get("/summary") { call.respond(t.symptoms.summary(call.requireUser(services).id, call.days(90))) }
    }

    route("/api/habits") {
        get { call.respond(t.habits.list(call.requireUser(services).id, call.request.queryParameters["all"] == "true")) }
        post { call.respond(t.habits.save(call.requireUser(services).id, call.receive<Habit>())) }
        get("/today") {
            val user = call.requireUser(services)
            val date = call.request.queryParameters["date"]?.takeIf { it.isNotBlank() }?.let { services.profiles.days(user.id).parse(it, services.clock()) }
            call.respond(t.habits.statuses(user.id, date))
        }
        put("/{id}") { call.respond(t.habits.save(call.requireUser(services).id, call.receive<Habit>(), call.id())) }
        delete("/{id}") { t.habits.delete(call.requireUser(services).id, call.id()); call.respond(ok) }
        put("/{id}/ticks") { call.respond(t.habits.tick(call.requireUser(services).id, call.id(), call.receive<HabitTick>())) }
        get("/{id}/history") { call.respond(t.habits.history(call.requireUser(services).id, call.id(), call.days(90))) }
    }

    get("/api/places") {
        call.requireUser(services)
        call.respond(t.weather.places(call.request.queryParameters["q"].orEmpty()))
    }
}
