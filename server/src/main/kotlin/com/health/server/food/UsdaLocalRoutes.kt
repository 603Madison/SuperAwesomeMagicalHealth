package com.health.server.food

import com.health.server.ApiError
import com.health.server.Services
import com.health.server.auth.requireAdmin
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Admin › Food search: USDA's food list kept on this server (admins only). */
fun Route.usdaLocalRoutes(services: Services) {
    route("/api/admin/usda-local") {
        // Where the copy stands: ready or not, a download under way, the last check, what each choice takes.
        get {
            call.requireAdmin(services)
            call.respond(services.usdaLocal.status())
        }
        // Downloads the latest release (with packaged foods as Admin chose) and swaps it in, in the background.
        post("/download") {
            call.requireAdmin(services)
            if (!services.usdaLocal.download(services.settings.get().usdaLocal.packaged)) throw ApiError.conflict("A download is already under way")
            call.respond(services.usdaLocal.status())
        }
        // Looks for a newer release now.
        post("/check") {
            call.requireAdmin(services)
            services.usdaLocal.check(services.clock())
            call.respond(services.usdaLocal.status())
        }
        // Stops a download under way, and removes the copy.
        delete {
            call.requireAdmin(services)
            services.usdaLocal.remove()
            call.respond(services.usdaLocal.status())
        }
    }
}
