package com.health.server.ai

import com.health.api.AiBreakdownRequest
import com.health.api.AiChatRequest
import com.health.api.AiConsent
import com.health.api.AiKey
import com.health.api.AiSettings
import com.health.api.ApiJson
import com.health.api.Meal
import com.health.api.PortionFood
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.auth.requireAdmin
import com.health.server.auth.requireUser
import com.health.server.photos.Images
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/** The AI assistant: the admin's settings and key, and each person's status, consent, questions, food photos and log. */
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
        // A photo of a meal, estimated. The picture is read into memory and passed on; nothing keeps it.
        post("/food-photo") {
            val user = call.requireUser(services)
            call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let {
                if (it > Images.MAX_BYTES + 256 * 1024) throw ApiError(HttpStatusCode.PayloadTooLarge, "Photos can be up to 20 MB")
            }
            var photo: ByteArray? = null
            var meal: Meal? = null
            var note: String? = null
            // Ktor's limit is for every part, the picture too: the picture's own limit is checked as it's read.
            call.receiveMultipart(formFieldLimit = Images.MAX_BYTES + 256 * 1024L).forEachPart { part ->
                try {
                    when {
                        part is PartData.FileItem && part.name == "photo" -> {
                            val read = part.provider().readRemaining(Images.MAX_BYTES + 1L).readByteArray()
                            if (read.size > Images.MAX_BYTES) throw ApiError(HttpStatusCode.PayloadTooLarge, "Photos can be up to 20 MB")
                            photo = read
                        }
                        part is PartData.FormItem && part.name == "meal" -> meal = Meal.entries.firstOrNull { it.name.equals(part.value.trim(), ignoreCase = true) }
                        part is PartData.FormItem && part.name == "note" -> note = part.value.trim().ifEmpty { null }
                    }
                } finally {
                    part.dispose()
                }
            }
            val picture = photo ?: throw ApiError.badRequest("Send the picture as `photo`")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(services.ai.foodPhoto(user, picture, meal, note))
        }
        // "How much did I have?": a photo (and the package's, if sent) with the food being added, as servings. Nothing keeps the photos.
        post("/portion") {
            val user = call.requireUser(services)
            call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let {
                if (it > 2L * Images.MAX_BYTES + 256 * 1024) throw ApiError(HttpStatusCode.PayloadTooLarge, "Photos can be up to 20 MB")
            }
            var photo: ByteArray? = null
            var pack: ByteArray? = null
            var food: PortionFood? = null
            var note: String? = null
            call.receiveMultipart(formFieldLimit = Images.MAX_BYTES + 256 * 1024L).forEachPart { part ->
                try {
                    when {
                        part is PartData.FileItem && (part.name == "photo" || part.name == "package") -> {
                            val read = part.provider().readRemaining(Images.MAX_BYTES + 1L).readByteArray()
                            if (read.size > Images.MAX_BYTES) throw ApiError(HttpStatusCode.PayloadTooLarge, "Photos can be up to 20 MB")
                            if (part.name == "photo") photo = read else pack = read
                        }
                        part is PartData.FormItem && part.name == "food" -> food = try {
                            ApiJson.decodeFromString(PortionFood.serializer(), part.value.take(4_000))
                        } catch (e: kotlinx.serialization.SerializationException) {
                            throw ApiError.badRequest("Send the food as `food`: its name and serving")
                        } catch (e: IllegalArgumentException) {
                            throw ApiError.badRequest("Send the food as `food`: its name and serving")
                        }
                        part is PartData.FormItem && part.name == "note" -> note = part.value.trim().ifEmpty { null }
                    }
                } finally {
                    part.dispose()
                }
            }
            val picture = photo ?: throw ApiError.badRequest("Send the picture as `photo`")
            val what = food ?: throw ApiError.badRequest("Send the food as `food`: its name and serving")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(services.ai.portion(user, picture, pack, what, note))
        }
        get("/audit") {
            val user = call.requireUser(services)
            val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 365) ?: 30
            call.respond(services.ai.audit(user, days))
        }
    }
}
