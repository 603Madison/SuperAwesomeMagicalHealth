package com.health.server.ai

import com.health.api.AiBreakdownRequest
import com.health.api.AiChatRequest
import com.health.api.AiConsent
import com.health.api.AiKey
import com.health.api.AiSettings
import com.health.server.Services
import com.health.server.auth.requireAdmin
import com.health.server.auth.requireUser
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** The AI assistant: the admin's settings and key, and each person's status, consent, questions and log. */
fun Route.aiRoutes(services: Services) {
    route("/api/admin/ai") {
        get { call.requireAdmin(services); call.respond(services.ai.settings()) }
        put {
            call.requireAdmin(services)
            call.respond(services.ai.saveSettings(call.receive<AiSettings>()))
        }
        put("/key") {
            call.requireAdmin(services)
            services.ai.setKey(call.receive<AiKey>().apiKey)
            call.respond(services.ai.settings())
        }
        delete("/key") {
            call.requireAdmin(services)
            services.ai.setKey("")
            call.respond(services.ai.settings())
        }
        post("/test") {
            call.requireAdmin(services)
            call.respond(services.ai.test())
        }
    }

    route("/api/ai") {
        get("/status") { call.respond(services.ai.status(call.requireUser(services))) }
        put("/consent") {
            val user = call.requireUser(services)
            call.respond(services.ai.saveConsent(user, call.receive<AiConsent>()))
        }
        delete("/consent") {
            services.ai.deleteConsent(call.requireUser(services))
            call.respond(HttpStatusCode.NoContent)
        }
        post("/chat") {
            val user = call.requireUser(services)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(services.ai.chat(user, call.receive<AiChatRequest>()))
        }
        post("/breakdown") {
            val user = call.requireUser(services)
            call.respond(services.ai.breakdown(user, call.receive<AiBreakdownRequest>()))
        }
        get("/audit") {
            val user = call.requireUser(services)
            val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 365) ?: 30
            call.respond(services.ai.audit(user, days))
        }
    }
}
