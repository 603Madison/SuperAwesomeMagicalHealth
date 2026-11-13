package com.health.server.platform

import com.health.api.LayoutPrefs
import com.health.api.Layouts
import com.health.api.ScreenLayout
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.auth.requireUser
import com.health.server.modules.UserSettingsStore
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * How each person has arranged the apps' screens (docs/API.md, "Arranging screens"). Only their own:
 * someone viewing a share keeps their own arrangement, so this isn't in SharedView's allowlist.
 */
class LayoutService(private val settings: UserSettingsStore) {
    suspend fun get(userId: String): LayoutPrefs = settings.get(userId, KEY, LayoutPrefs.serializer()) ?: LayoutPrefs()

    /** Replaces every screen's arrangement. */
    suspend fun put(userId: String, prefs: LayoutPrefs): LayoutPrefs {
        if (prefs.screens.size > Layouts.MAX_SCREENS) throw ApiError.badRequest("Up to ${Layouts.MAX_SCREENS} screens")
        val clean = prefs.screens.entries.associate { (key, layout) -> checkKey(key) to clean(layout) }.filterValues { it.order.isNotEmpty() || it.hidden.isNotEmpty() }
        return settings.put(userId, KEY, LayoutPrefs(clean), LayoutPrefs.serializer())
    }

    /** Sets one screen's arrangement; an empty one puts the screen back as it was. */
    suspend fun putScreen(userId: String, key: String, layout: ScreenLayout): LayoutPrefs {
        val now = get(userId)
        val screens = now.screens.toMutableMap()
        val c = clean(layout)
        if (c.order.isEmpty() && c.hidden.isEmpty()) screens.remove(checkKey(key)) else screens[checkKey(key)] = c
        if (screens.size > Layouts.MAX_SCREENS) throw ApiError.badRequest("Up to ${Layouts.MAX_SCREENS} screens")
        return settings.put(userId, KEY, LayoutPrefs(screens), LayoutPrefs.serializer())
    }

    private fun checkKey(key: String): String {
        if (!ID.matches(key)) throw ApiError.badRequest("Screens are named like web.food or today")
        return key
    }

    private fun clean(layout: ScreenLayout): ScreenLayout {
        val order = layout.order.distinct()
        if (order.size > Layouts.MAX_SECTIONS || layout.hidden.size > Layouts.MAX_SECTIONS) throw ApiError.badRequest("Up to ${Layouts.MAX_SECTIONS} sections a screen")
        if ((order + layout.hidden).any { !ID.matches(it) }) throw ApiError.badRequest("Section names are letters, numbers, dots, dashes and underscores")
        return ScreenLayout(order, layout.hidden)
    }

    companion object {
        const val KEY = "layout"
        private val ID = Regex("^[A-Za-z0-9._-]{1,${Layouts.MAX_ID}}$")
    }
}

fun Route.layoutRoutes(services: Services) {
    val layouts = services.layouts
    route("/api/layout") {
        get { call.respond(layouts.get(call.requireUser(services).id)) }
        put { call.respond(layouts.put(call.requireUser(services).id, call.receive<LayoutPrefs>())) }
        put("/{key}") {
            call.respond(layouts.putScreen(call.requireUser(services).id, call.parameters["key"].orEmpty(), call.receive<ScreenLayout>()))
        }
        delete("/{key}") {
            call.respond(layouts.putScreen(call.requireUser(services).id, call.parameters["key"].orEmpty(), ScreenLayout()))
        }
    }
}
