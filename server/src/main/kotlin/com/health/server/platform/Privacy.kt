package com.health.server.platform

import com.health.api.DataDestination
import com.health.api.DataMap
import com.health.api.DataPath
import com.health.api.DataPathState
import com.health.api.SessionKind
import com.health.server.ApiError
import com.health.server.Services
import com.health.api.User
import com.health.server.modules.UserSettingsStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Each person's pauses (sealed in their settings under `privacy.paused`): a paused way out sends
 * nothing about them until it's resumed, whatever is set up. Every path out of the server asks
 * [allows] first.
 */
class PrivacyStore(private val settings: UserSettingsStore) {
    suspend fun paused(userId: String): Set<DataPath> =
        settings.get(userId, KEY, ListSerializer(serializer<String>())).orEmpty().mapNotNull { name -> DataPath.entries.firstOrNull { it.name == name } }.toSet()

    suspend fun allows(userId: String, path: DataPath): Boolean = path !in paused(userId)

    suspend fun set(userId: String, path: DataPath, paused: Boolean) {
        if (path == DataPath.SIGN_IN) throw ApiError.badRequest("Signing in can't be paused: it's how you get in")
        val now = paused(userId).let { if (paused) it + path else it - path }
        if (now.isEmpty()) settings.delete(userId, KEY) else settings.put(userId, KEY, now.map { it.name }.sorted(), ListSerializer(serializer<String>()))
    }

    companion object {
        const val KEY = "privacy.paused"
        /** Said wherever something didn't go because it's paused. */
        fun pausedText(what: String) = "$what is paused in Settings › Where your data goes"
    }
}

/** The person's map of where their data can go (Settings › Where your data goes). */
class PrivacyService(private val services: Services) {
    private val store get() = services.privacy

    suspend fun map(user: User): DataMap {
        val paused = store.paused(user.id)
        val server = services.settings.get()
        fun state(path: DataPath, setUp: Boolean, onServer: Boolean = true) = when {
            !onServer -> DataPathState.OFF_ON_SERVER
            !setUp -> DataPathState.NOT_SET_UP
            path in paused -> DataPathState.PAUSED
            else -> DataPathState.ON
        }
        val sessions = services.sessions.list(user.id)
        val links = sessions.count { it.kind == SessionKind.MCP || it.kind == SessionKind.MCP_WRITE || (it.kind == SessionKind.MCP_ALL && user.isAdmin) }
        val integrationKeys = sessions.count { it.kind == SessionKind.INTEGRATION }
        val mealie = services.mealie.status(user.id)
        val ai = services.ai.status(user)
        val hooks = services.platform.webhooks.list(user.id)
        val pushHosts = services.platform.push.hosts(user.id)
        val profile = services.profiles.get(user.id)
        val shares = services.sharing.list(user.id).given
        val emails = profile.digest.emailEnabled && !profile.digest.email.isNullOrBlank()
        val mail = server.smtp
        val usdaKey = services.settings.usdaApiKey().isNotEmpty()
        val records = services.medicalRecords.connections(user.id)
        val destinations = listOfNotNull(
            DataDestination(
                DataPath.FOOD_DATABASE, "Open Food Facts", "A non-profit food database anyone can use",
                hosts = listOf("search.openfoodfacts.org", "world.openfoodfacts.org"),
                what = "The words you search for and the barcodes you scan. Never who you are or what you ate.",
                whenSent = "Only when USDA FoodData Central doesn't know a food you search for or scan, where this server has it switched on",
                state = state(DataPath.FOOD_DATABASE, true, server.foodDatabase), setUp = null,
            ),
            DataDestination(
                DataPath.USDA_FOODS, "USDA FoodData Central", "The US Department of Agriculture's food database",
                hosts = listOf("api.nal.usda.gov"),
                what = "The words you search for and the barcodes you scan, with this server's key. Never who you are or what you ate.",
                whenSent = "When you search for a food or scan a barcode",
                state = state(DataPath.USDA_FOODS, usdaKey, server.usdaFoods), setUp = null,
            ),
            DataDestination(
                DataPath.HEALTH_RECORDS, "Medical records", "Your hospital, clinic or practice's patient portal (Epic or athenahealth)",
                hosts = (services.medicalRecords.hosts(user.id) + listOfNotNull("open.epic.com".takeIf { com.health.api.RecordProvider.EPIC in server.records.providers })).distinct(),
                what = "You sign in on your portal's own page; then this server asks it for your record. Nothing of yours is sent to it. Finding your organization on Epic asks Epic's public list.",
                whenSent = "When you connect, and once a day after to bring your record up to date",
                state = state(DataPath.HEALTH_RECORDS, records.isNotEmpty(), server.records.providers.isNotEmpty()),
                count = records.size, setUp = "records",
            ),
            DataDestination(
                DataPath.WEATHER, "Open-Meteo", "A free weather service",
                hosts = listOf("api.open-meteo.com", "archive-api.open-meteo.com", "geocoding-api.open-meteo.com"),
                what = "A rough place (to about 10 km) and dates, to fetch the air pressure, and the words you type when you look for a place. Never your symptoms.",
                whenSent = "When your symptom patterns are worked out, and when you look for a place",
                state = state(DataPath.WEATHER, profile.place != null, server.weather), setUp = "settings.profile",
            ),
            DataDestination(
                DataPath.MEALIE, "Mealie", "Your own Mealie",
                hosts = listOfNotNull(mealie.url?.let(::hostOf)),
                what = "Your recipe searches and the days of your meal plan, with your Mealie token. Nothing is ever written to Mealie.",
                whenSent = "When you search for a food or open Food",
                state = state(DataPath.MEALIE, mealie.connected), setUp = "settings.connections",
            ),
            DataDestination(
                DataPath.AI_ASSISTANT, "The assistant", "Anthropic, who make Claude",
                hosts = listOf("api.anthropic.com"),
                what = "What you ask it and the readings it looks up to answer (only the features you let it read), and food photos you send it.",
                whenSent = "Only when you ask it something or snap a meal",
                state = state(DataPath.AI_ASSISTANT, ai.consent != null, ai.available), setUp = "settings.ai",
            ),
            DataDestination(
                DataPath.AI_CONNECTORS, "AI connector links", "The AI app each link was made for",
                what = "What that AI asks for through its link: only the features the link names. It comes to this server to ask; nothing is sent until it does.",
                whenSent = "When you ask that AI something about your health",
                state = state(DataPath.AI_CONNECTORS, links > 0), count = links, setUp = "settings.connector",
            ),
            DataDestination(
                DataPath.WEBHOOKS, "Webhooks", "Addresses you set up, like Home Assistant",
                hosts = hooks.filter { it.enabled }.mapNotNull { hostOf(it.url) }.distinct(),
                what = "The events you chose for each one: times and counts, and names only where you turned details on.",
                whenSent = "When one of those events happens",
                state = state(DataPath.WEBHOOKS, hooks.any { it.enabled }), count = hooks.count { it.enabled }, setUp = "settings.integrations",
            ),
            DataDestination(
                DataPath.BROWSER_PUSH, "Browser notifications", "Your browser's push service (Google's for Chrome, Mozilla's for Firefox, Apple's for Safari)",
                hosts = pushHosts,
                what = "Reminders and alerts, sealed so only your browser can open them. The push service sees when one is sent, not what it says. The phone app's own reminders never use this.",
                whenSent = "When a reminder or alert is due",
                state = state(DataPath.BROWSER_PUSH, pushHosts.isNotEmpty()), count = services.platform.push.count(user.id), setUp = "settings.notifications",
            ),
            DataDestination(
                DataPath.EMAIL, "Weekly email", "This server's mail provider, then your inbox's",
                hosts = listOfNotNull(mail.host.takeIf { it.isNotBlank() }),
                what = "Your weekly digest, leaving out private features you didn't list for email. Email isn't sealed: mail providers along the way can read it.",
                whenSent = "Once a week, if you asked for it by email",
                state = state(DataPath.EMAIL, emails, mail.enabled && mail.host.isNotBlank()), setUp = "settings.digest",
            ),
            DataDestination(
                DataPath.SHARES, "Shares", "People on this server you share with",
                what = "The features each share names, when they look. It stays on this server.",
                whenSent = "When they open what you share",
                state = state(DataPath.SHARES, shares.isNotEmpty()), count = shares.size, setUp = "settings.sharing",
            ),
            DataDestination(
                DataPath.HOME_ASSISTANT, "Home Assistant keys", "Your Home Assistant",
                what = "Today's numbers (sleep, readiness, steps and the like) when Home Assistant asks with its key.",
                whenSent = "Each time Home Assistant checks in",
                state = state(DataPath.HOME_ASSISTANT, integrationKeys > 0), count = integrationKeys, setUp = "settings.integrations",
            ),
            server.oidc.takeIf { it.enabled && user.oidc }?.let { oidc ->
                DataDestination(
                    DataPath.SIGN_IN, "Signing in", "The sign-in service this server uses",
                    hosts = listOfNotNull(hostOf(oidc.issuer)),
                    what = "That you're signing in. It sends this server your name and email; nothing about your health goes to it.",
                    whenSent = "When you sign in",
                    state = DataPathState.ON, pausable = false,
                )
            },
        )
        return DataMap(destinations, NOTE)
    }

    suspend fun pause(user: User, path: DataPath, paused: Boolean): DataMap {
        store.set(user.id, path, paused)
        return map(user)
    }

    companion object {
        const val NOTE = "Everything else stays on this server: your readings, diary and photos are never sent anywhere unless it's listed here. The apps load nothing from anywhere else (no fonts, trackers or adverts)."

        /** Hosts only, never whole addresses (a push address names the browser, a webhook's may carry a key). */
        fun hostOf(url: String): String? = url.trim().toHttpUrlOrNull()?.host
    }
}
