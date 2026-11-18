package com.health.server.records

import com.health.api.RecordConnect
import com.health.api.RecordKind
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.auth.principal
import com.health.server.auth.publicBase
import com.health.server.auth.requireUser
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Medical records (docs/API.md, "Medical records"). Everything needs the signed-in person, but the
 * portal's redirect back, which carries its own single-use `state`. Never shared: /api/records
 * isn't in SharedView's list, so a request viewing someone else's data is refused.
 */
fun Route.recordsRoutes(services: Services) {
    val records = services.medicalRecords
    fun ApplicationCall.id() = parameters["id"] ?: throw ApiError.notFound()

    route("/api/records") {
        get { call.respond(records.state(call.requireUser(services).id)) }
        get("/organizations") {
            call.respond(records.organizations(call.requireUser(services).id, call.request.queryParameters["q"]?.take(200)))
        }
        post("/connect") {
            val user = call.requireUser(services)
            val input = call.receive<RecordConnect>()
            call.respond(records.connect(user.id, input, "${publicBase(services, call)}${RecordsService.CALLBACK_PATH}"))
        }
        get("/callback") {
            // A top-level redirect from the portal: the `state` says whose it is. Never cached, never passed on.
            val params = call.request.queryParameters
            val session = try {
                call.principal(services)?.user?.id
            } catch (e: ApiError) {
                null
            }
            val target = records.callback(mapOf("code" to params["code"], "state" to params["state"], "error" to params["error"]), session)
            call.response.header("Cache-Control", "no-store")
            call.respondRedirect(target)
        }
        get("/items") {
            val user = call.requireUser(services)
            val kind = call.request.queryParameters["kind"]?.takeIf { it.isNotBlank() }?.let { k ->
                RecordKind.entries.firstOrNull { it.name.equals(k, ignoreCase = true) } ?: throw ApiError.badRequest("No such kind")
            }
            call.respond(records.items(user.id, kind, call.request.queryParameters["connection"]?.takeIf { it.isNotBlank() }))
        }
        post("/{id}/sync") { call.respond(records.sync(call.requireUser(services).id, call.id())) }
        delete("/{id}") {
            val user = call.requireUser(services)
            val keep = call.request.queryParameters["keep"]?.lowercase() in setOf("1", "true", "yes")
            records.disconnect(user.id, call.id(), keep)
            call.respond(mapOf("ok" to true))
        }
    }
}
