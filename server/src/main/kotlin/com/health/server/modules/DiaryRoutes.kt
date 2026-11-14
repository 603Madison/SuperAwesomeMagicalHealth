package com.health.server.modules

import com.health.api.ContinenceCheckIn
import com.health.api.ContinenceSettings
import com.health.api.CycleSettings
import com.health.api.FieldChoices
import com.health.api.MyConditions
import com.health.api.Supply
import com.health.api.SupplyAdjustment
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.auth.requireUser
import com.health.server.care.SharedView
import com.health.server.care.viewScope
import com.health.server.conditions.ConditionCatalog
import com.health.api.Fields
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Conditions and goals, which fields show, the deeper bladder and bowel diary, supplies, cycle settings (docs/API.md). */
fun Route.diaryRoutes(services: Services) {
    val m = services.modules
    val supplies = services.supplies
    val conditions = services.conditions
    fun ApplicationCall.days(default: Int) = request.queryParameters["days"]?.toIntOrNull() ?: default
    fun ApplicationCall.id() = parameters["id"] ?: throw ApiError.notFound()
    val ok = mapOf("ok" to true)

    get("/api/conditions/catalog") { call.requireUser(services); call.respond(ConditionCatalog.ALL) }
    route("/api/conditions") {
        get { call.respond(conditions.get(call.requireUser(services).id)) }
        put { call.respond(conditions.put(call.requireUser(services).id, call.receive<MyConditions>())) }
    }
    route("/api/fields") {
        get {
            val user = call.requireUser(services)
            val layout = conditions.layout(user.id)
            // Someone viewing a share sees the fields of what's shared, and not which conditions brought them.
            val view = call.viewScope
            call.respond(
                if (view == null) layout
                else layout.copy(
                    effective = layout.effective.filter { Fields.ALL[it]?.first in view.modules }.toSet(),
                    show = emptySet(), hide = emptySet(), because = emptyMap(),
                ),
            )
        }
        put { call.respond(conditions.putChoices(call.requireUser(services).id, call.receive<FieldChoices>())) }
    }

    route("/api/continence") {
        get("/settings") { call.respond(m.continence.settings(call.requireUser(services).id)) }
        put("/settings") { call.respond(m.continence.putSettings(call.requireUser(services).id, call.receive<ContinenceSettings>())) }
        get("/progress") {
            val weeks = call.request.queryParameters["weeks"]?.toIntOrNull() ?: 12
            call.respond(m.continence.progress(call.requireUser(services).id, weeks))
        }
        route("/check-ins") {
            get { call.respond(m.continence.checkIns(call.requireUser(services).id, call.days(180))) }
            post { call.respond(m.continence.saveCheckIn(call.requireUser(services).id, call.receive<ContinenceCheckIn>())) }
            delete("/{id}") { m.continence.deleteCheckIn(call.requireUser(services).id, call.id()); call.respond(ok) }
        }
    }

    route("/api/supplies") {
        // Through a share: only the supplies of the features shared (bladder and bowel, or periods).
        get {
            val list = supplies.list(call.requireUser(services).id, call.request.queryParameters["all"] == "true")
            val scope = call.viewScope
            call.respond(if (scope == null) list else list.filter { SharedView.showsSupply(it.kind, scope.modules) })
        }
        post { call.respond(supplies.save(call.requireUser(services).id, call.receive<Supply>())) }
        get("/status") {
            val list = supplies.status(call.requireUser(services).id)
            val scope = call.viewScope
            call.respond(if (scope == null) list else list.filter { SharedView.showsSupply(it.supply.kind, scope.modules) })
        }
        put("/{id}") { call.respond(supplies.save(call.requireUser(services).id, call.receive<Supply>(), call.id())) }
        delete("/{id}") { supplies.delete(call.requireUser(services).id, call.id()); call.respond(ok) }
        post("/{id}/adjust") { call.respond(supplies.adjust(call.requireUser(services).id, call.id(), call.receive<SupplyAdjustment>())) }
        get("/{id}/log") {
            val user = call.requireUser(services)
            call.viewScope?.let { scope ->
                val supply = supplies.get(user.id, call.id())
                if (supply == null || !SharedView.showsSupply(supply.kind, scope.modules)) throw ApiError.notFound("No such supply")
            }
            call.respond(supplies.log(user.id, call.id(), call.days(90)))
        }
    }

    route("/api/cycle/settings") {
        get { call.respond(m.cycle.settings(call.requireUser(services).id)) }
        put { call.respond(m.cycle.putSettings(call.requireUser(services).id, call.receive<CycleSettings>())) }
    }
}
