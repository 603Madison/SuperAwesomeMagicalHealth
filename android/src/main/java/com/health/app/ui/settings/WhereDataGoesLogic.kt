package com.health.app.ui.settings

import com.health.api.DataDestination
import com.health.api.DataMap
import com.health.api.DataPath
import com.health.api.DataPathState
import com.health.app.ui.components.ArrangeItem

/*
 * You › Where your data goes (GET /api/privacy): every way something about the person can
 * leave their server, each paused or resumed with a switch, then what happens on this phone.
 * Worked out without drawing anything, so it can be tested; WhereDataGoes.kt draws it.
 */
object WhereDataGoes {
    const val TITLE = "Where your data goes"
    const val ROUTE = "settings/privacy"

    /** Said wherever something set up is paused here (Mealie, the assistant), with a way here. */
    const val PAUSED = "Paused in Where your data goes"

    /** "The assistant is paused in Where your data goes". */
    fun paused(what: String) = "$what is paused in Where your data goes"

    const val INTRO = "Every way something about you can leave your server. Pausing one keeps how it's set up, and nothing goes until you turn it back on."

    // ---- Sections (android.privacy): their ids are saved with the person's arrangement, so never rename one.

    const val SERVER = "server"
    const val PHONE = "phone"

    val SECTIONS = listOf(ArrangeItem(SERVER, "From your server"), ArrangeItem(PHONE, "On this phone"))

    // ---- Each way out

    /** Whether it has a switch: one the person can pause, that's set up (on or paused). Signing in never has. */
    fun switchable(d: DataDestination): Boolean = d.pausable && (d.state == DataPathState.ON || d.state == DataPathState.PAUSED)

    /** "2 links", "1 webhook", "3 browsers": how many are set up, where that's a thing. */
    fun countText(d: DataDestination): String? {
        val n = d.count?.takeIf { it > 0 } ?: return null
        val (one, many) = when (d.path) {
            DataPath.AI_CONNECTORS -> "link" to "links"
            DataPath.WEBHOOKS -> "webhook" to "webhooks"
            DataPath.BROWSER_PUSH -> "browser" to "browsers"
            DataPath.SHARES -> "share" to "shares"
            DataPath.HOME_ASSISTANT -> "key" to "keys"
            DataPath.HEALTH_RECORDS -> "portal" to "portals"
            else -> return "$n set up"
        }
        return "$n ${if (n == 1) one else many}"
    }

    /** The line under it: "On · 2 links", "Paused · 1 webhook kept", "Not set up", "Turned off on this server". */
    fun stateLine(d: DataDestination): String = when (d.state) {
        DataPathState.ON -> if (!d.pausable) "Always on: it's how you sign in" else listOfNotNull("On", countText(d)).joinToString(" · ")
        DataPathState.PAUSED -> listOfNotNull("Paused: nothing goes", countText(d)?.let { "$it kept" }).joinToString(" · ")
        DataPathState.NOT_SET_UP -> "Not set up"
        DataPathState.OFF_ON_SERVER -> "Turned off on this server"
    }

    /** Where something not set up is set up in this app: a page and the words for the way there. */
    data class Link(val route: String, val label: String)

    /**
     * Each [DataDestination.setUp] key as this app's page for it, or null where the app has none
     * (AI connector links and browser notifications are made on the server's web page).
     */
    fun link(setUp: String?): Link? = when (setUp) {
        "settings.connections" -> Link("settings/mealie", "Set up Mealie")
        "settings.ai" -> Link("settings/ai", "Set up the assistant")
        "settings.integrations" -> Link("settings/integrations", "Set up in $INTEGRATIONS")
        "settings.digest" -> Link(DIGEST_SETTINGS, "Set up the weekly digest")
        "settings.sharing" -> Link("settings/sharing", "Set up sharing")
        // The weather follows the place chosen in Symptoms.
        "settings.profile" -> Link("symptoms", "Choose a place in Symptoms")
        "records" -> Link(RECORDS_ROUTE, "Connect in Medical records")
        else -> null
    }

    /** A way out with a page of its own, linked there once it's set up (on or paused): Medical records. */
    fun openLink(setUp: String?): Link? = when (setUp) {
        "records" -> Link(RECORDS_ROUTE, "Open Medical records")
        else -> null
    }

    /** You › Medical records (ui/records). */
    const val RECORDS_ROUTE = "records"

    /** The map with [path] paused (or resumed) at once, before the server's answer comes. */
    fun pausing(map: DataMap, path: DataPath, paused: Boolean): DataMap = map.copy(
        destinations = map.destinations.map { d ->
            if (d.path != path || !switchable(d)) d else d.copy(state = if (paused) DataPathState.PAUSED else DataPathState.ON)
        },
    )

    /** You's one line about it: "Nothing leaves your server", or "2 ways out on · 1 paused". */
    fun youLine(map: DataMap): String {
        val on = map.destinations.count { it.state == DataPathState.ON }
        val paused = map.destinations.count { it.state == DataPathState.PAUSED }
        if (on == 0 && paused == 0) return "Nothing leaves your server"
        val ways = when (on) {
            0 -> "Nothing goes out now"
            1 -> "1 way out on"
            else -> "$on ways out on"
        }
        return if (paused > 0) "$ways · $paused paused" else ways
    }

    /** "On now: Open Food Facts, Open-Meteo and Signing in.", or that nothing is. */
    fun summary(map: DataMap): String {
        // "The assistant" reads as "the assistant" after the first.
        val on = map.destinations.filter { it.state == DataPathState.ON }.mapIndexed { i, d -> if (i > 0 && d.name.startsWith("The ")) "the " + d.name.removePrefix("The ") else d.name }
        return when (on.size) {
            0 -> "Nothing is on now: nothing about you leaves your server."
            1 -> "On now: ${on[0]}."
            else -> "On now: ${on.dropLast(1).joinToString(", ")} and ${on.last()}."
        }
    }

    // ---- On this phone (the app's own part, worked out here on the phone)

    /**
     * What this phone does: the server it talks to, the paired ring, whether Health Connect is read,
     * what the person chose to write back to it (Send to other apps), and Gadgetbridge's export file.
     */
    data class Phone(
        val server: String,
        val ring: String? = null,
        val healthConnect: Boolean = false,
        val writes: List<String> = emptyList(),
        val gadgetbridgeFile: String? = null,
    )

    const val BLUETOOTH = "Your ring and a chest strap talk only to this phone, over Bluetooth, never to the internet. The app passes what they record to your server and nowhere else. Blood pressure monitors and glucose meters work the same way."

    fun healthConnectRead(on: Boolean): String =
        if (on) "Read on this phone: what your other apps share with it. The app sends it only to your server."
        else "Not connected. When it is, it's read on this phone and sent only to your server."

    /** What the app writes back to Health Connect: only the kinds chosen in Export, else nothing. */
    fun healthConnectWrites(writes: List<String>): String = when {
        writes.isEmpty() -> "Nothing is written back to it."
        else -> "Written back to it, as you chose in $SEND_TO_APPS: ${list(writes.map { it.lowercase() })}. Other apps you let read Health Connect can see these, and some send them on to their own servers."
    }

    fun gadgetbridge(file: String?): String =
        if (file != null) "Read on this phone, from its export file ($file). Nothing is written to it."
        else "Not set up. When it is, its export file is read on this phone and nothing is written to it."

    const val ONLY_SERVER = "This app only talks to your server"
    const val ONLY_SERVER_MORE = "It loads nothing from anywhere else: no fonts, trackers or adverts. Links you tap, and signing in, open in your browser. Nothing from the app goes into your phone's cloud backup."

    /** "weight", "weight and workouts", "weight, water and workouts". */
    fun list(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
}
