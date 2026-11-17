package com.health.api

import kotlinx.serialization.Serializable

/**
 * Every way something about a person can leave this server (GET /api/privacy). Each one the
 * person can pause for themselves; pausing keeps its setup (a Mealie token, a webhook, a share)
 * and only stops anything going until it's resumed. The server's admin can also turn the food
 * database and the weather off for everyone.
 */
@Serializable
enum class DataPath {
    FOOD_DATABASE, WEATHER, MEALIE, AI_ASSISTANT, AI_CONNECTORS, WEBHOOKS, BROWSER_PUSH, EMAIL, SHARES, HOME_ASSISTANT, SIGN_IN,
}

@Serializable
enum class DataPathState {
    /** Set up and in use. */
    ON,
    /** Set up, but paused by the person: nothing goes. */
    PAUSED,
    /** Nothing set up, so nothing goes. */
    NOT_SET_UP,
    /** Turned off for everyone by the server's admin. */
    OFF_ON_SERVER,
}

/** One way out, said plainly. */
@Serializable
data class DataDestination(
    val path: DataPath,
    /** "Open Food Facts". */
    val name: String,
    /** Who's at the other end: "A non-profit food database", "Anthropic, who make Claude". */
    val who: String,
    /** The addresses it goes to, where known. */
    val hosts: List<String> = emptyList(),
    /** What goes, and what never does. */
    val what: String,
    /** When it goes. */
    val whenSent: String,
    val state: DataPathState,
    /** How many are set up (links, webhooks, browsers, shares, keys), where that's a thing. */
    val count: Int? = null,
    /** Whether the person can pause it here (signing in can't be). */
    val pausable: Boolean = true,
    /** Where it's set up in the apps: settings.connections, settings.ai, settings.sharing… */
    val setUp: String? = null,
)

@Serializable
data class DataMap(
    val destinations: List<DataDestination>,
    /** What stays put: everything else. */
    val note: String,
)

/** Pausing or resuming one way out (PUT /api/privacy/{path}). */
@Serializable
data class DataPathChange(val paused: Boolean)
