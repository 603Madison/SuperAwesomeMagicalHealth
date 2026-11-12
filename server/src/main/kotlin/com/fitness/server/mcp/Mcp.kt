package com.fitness.server.mcp

import com.fitness.api.ApiJson
import com.fitness.api.CheckIn
import com.fitness.api.DaySummary
import com.fitness.api.ErrorBody
import com.fitness.api.Food
import com.fitness.api.FoodEntryInput
import com.fitness.api.Meal
import com.fitness.api.Metric
import com.fitness.api.Nutrients
import com.fitness.api.Sample
import com.fitness.api.Score
import com.fitness.api.SessionKind
import com.fitness.api.SleepSummary
import com.fitness.api.Units
import com.fitness.api.User
import com.fitness.api.WaterEntry
import com.fitness.api.Workout
import com.fitness.api.WorkoutType
import com.fitness.server.ServerConfig
import com.fitness.server.Services
import com.fitness.server.food.foodDay
import com.fitness.server.insights.DayService
import com.fitness.server.insights.Energy
import com.fitness.server.insights.Scoring
import com.fitness.server.util.Days
import com.fitness.server.util.RecentLog
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The server as a Model Context Protocol server (Streamable HTTP, answering each request with
 * plain JSON), so people can bring their fitness data to the AI of their choice (Claude, or any
 * MCP client) and get help troubleshooting their devices and the server.
 *
 * A link from Settings is the key: `/mcp/<token>`, or `/mcp` with the token as a bearer token.
 * Links are sign-ins of their own kind, listed and revoked with the account's devices, and open
 * nothing but this. A plain link only reads the account's own data; one made with logging
 * allowed ([SessionKind.MCP_WRITE]) can also log food, water, weight, workouts and check-ins; an
 * admin's every-account link ([SessionKind.MCP_ALL]) reads every account, for troubleshooting.
 * The server's own log is for admins.
 */
fun Route.mcpRoutes(services: Services) {
    val mcp = McpServer(services)

    suspend fun ApplicationCall.serve(token: String?) {
        val (user, session) = token?.let { services.sessions.resolve(it) }?.takeIf { it.second.kind.isConnector }
            ?: return respond(HttpStatusCode.NotFound, ErrorBody("That connector link isn't valid (any more): make a new one in Settings"))
        val caller = McpServer.Caller(
            user,
            everyone = session.kind == SessionKind.MCP_ALL && user.isAdmin,
            canLog = session.kind == SessionKind.MCP_WRITE,
        )
        val body = runCatching { ApiJson.parseToJsonElement(receiveText()) }.getOrNull()
            ?: return respondJson(McpServer.error(JsonNull, -32700, "That isn't JSON"))
        when (body) {
            is JsonObject -> mcp.handle(caller, body)?.let { respondJson(it) } ?: respond(HttpStatusCode.Accepted, "")
            is JsonArray -> {
                val replies = body.mapNotNull { (it as? JsonObject)?.let { message -> mcp.handle(caller, message) } }
                if (replies.isEmpty()) respond(HttpStatusCode.Accepted, "") else respondJson(JsonArray(replies))
            }
            else -> respondJson(McpServer.error(JsonNull, -32600, "Not a JSON-RPC request"))
        }
    }

    post("/mcp/{token}") { call.serve(call.parameters["token"]) }
    post("/mcp") {
        call.serve(call.request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim())
    }
    // No event stream to open, and no session to end: each request stands alone.
    for (path in listOf("/mcp", "/mcp/{token}")) {
        get(path) { call.notAllowed() }
        delete(path) { call.notAllowed() }
    }
    // No OAuth: the link is the key. Saying so plainly (not with the web app's page, which
    // answers every other address) keeps clients from trying to sign in.
    get("/.well-known/{...}") { call.respond(HttpStatusCode.NotFound, ErrorBody("Not here")) }
}

private suspend fun ApplicationCall.notAllowed() {
    response.header(HttpHeaders.Allow, "POST")
    respond(HttpStatusCode.MethodNotAllowed, ErrorBody("Send JSON-RPC requests with POST"))
}

private suspend fun ApplicationCall.respondJson(element: JsonElement) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(ApiJson.encodeToString(JsonElement.serializer(), element), ContentType.Application.Json)
}

/** The protocol: initialize, ping, and the tools. */
class McpServer(private val services: Services) {
    /** Who's asking: the link's account, whether it sees every account, and whether it may log entries. */
    class Caller(val user: User, val everyone: Boolean, val canLog: Boolean)

    private class Tool(
        val name: String,
        val title: String,
        val description: String,
        val inputSchema: JsonObject,
        val writes: Boolean = false,
        val run: suspend (Caller, JsonObject) -> String,
    )

    /** One JSON-RPC message from the client; null for a notification (nothing to answer). */
    suspend fun handle(caller: Caller, message: JsonObject): JsonObject? {
        val id = message["id"]?.takeUnless { it is JsonNull }
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        if (id == null) return null // A notification ("initialized", "cancelled"), or a reply.
        if (method == null) return error(id, -32600, "No method")
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            when (method) {
                "initialize" -> result(id, initialize(params, caller))
                "ping" -> result(id, JsonObject(emptyMap()))
                "tools/list" -> result(id, buildJsonObject { put("tools", JsonArray(toolsFor(caller).map(::describe))) })
                "tools/call" -> callTool(id, caller, params)
                "resources/list" -> result(id, buildJsonObject { put("resources", JsonArray(emptyList())) })
                "resources/templates/list" -> result(id, buildJsonObject { put("resourceTemplates", JsonArray(emptyList())) })
                "prompts/list" -> result(id, buildJsonObject { put("prompts", JsonArray(emptyList())) })
                else -> error(id, -32601, "No method $method")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error(id, -32603, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun toolsFor(caller: Caller) = tools.filter { !it.writes || caller.canLog }

    private fun initialize(params: JsonObject, caller: Caller): JsonObject {
        val asked = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", asked?.takeIf { it in VERSIONS } ?: VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", "superawesomemagicalfitness")
                put("title", "SuperAwesomeMagicalFitness")
                put("version", Services.version)
            }
            put(
                "instructions",
                "A self-hosted fitness and health server (SuperAwesomeMagicalFitness): wearables (Colmi rings, Pebble, Garmin, " +
                    "anything in Health Connect or Gadgetbridge) send heart rate, HRV, sleep, steps and workouts through a phone " +
                    "app, and people log food, water, weight and check-ins. It works out Oura-style readiness, sleep and activity " +
                    "scores against each person's own baseline. Start with status (what's connected, what's missing), then day " +
                    "for a day in full, trends for weeks and months, and sleep, workouts and food_log for detail. For device " +
                    "troubleshooting use devices and sync_check (gaps, duplicates, stale syncs) and readings for raw data" +
                    (if (caller.user.isAdmin) "; recent_warnings and server_log show the server's own log" else "") + ". " +
                    (if (caller.canLog) "This link may also log entries (log_food, log_water, log_weight, log_workout, check_in): confirm amounts with the person before logging. " else "") +
                    "This is personal health data: it's for the person's own understanding, not medical advice.",
            )
        }
    }

    private suspend fun callTool(id: JsonElement, caller: Caller, params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
        val tool = toolsFor(caller).firstOrNull { it.name == name }
            ?: return error(id, -32602, if (tools.any { it.name == name }) "This link can only read; make one that may log entries in Settings" else "No tool named $name")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        log.info("AI connector of {}{}: {} {}", caller.user.username, if (caller.everyone) " (every account)" else "", name, arguments)
        val (text, failed) = try {
            tool.run(caller, arguments) to false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            (e.message ?: e.toString()) to true
        }
        return result(id, buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text.take(MAX_TEXT).ifBlank { "(nothing)" })
                })
            })
            put("isError", failed)
        })
    }

    private fun describe(tool: Tool) = buildJsonObject {
        put("name", tool.name)
        put("title", tool.title)
        put("description", tool.description)
        put("inputSchema", tool.inputSchema)
        putJsonObject("annotations") {
            put("title", tool.title)
            put("readOnlyHint", !tool.writes)
            if (tool.writes) put("destructiveHint", false)
            put("openWorldHint", false)
        }
    }

    private val account = "account" to stringProperty(
        "Whose: an account's username (default the link's own; an admin's every-account link can name any account)",
    )
    private val dateProperty = "date" to stringProperty("A day: YYYY-MM-DD, today or yesterday (default today, in the person's time zone)")
    private val daysProperty = { default: Int, max: Int -> "days" to intProperty("How many days back from today (default $default, at most $max)") }

    private val tools = listOf(
        Tool(
            "status", "Status",
            "Start here. The server's version and uptime; the account's profile (and what's missing for scores and calorie " +
                "targets), its devices with when each last synced and its battery, and which kinds of data have arrived lately. " +
                "An every-account link lists every account.",
            schema(account),
        ) { caller, args -> status(caller, accountOf(caller, args)) },
        Tool(
            "day", "Day summary",
            "One day in full: readiness, sleep and activity scores with each contributor; steps, calories, heart rate, HRV, " +
                "SpO2, temperature; the night's sleep with its stages; food eaten against the day's targets; workouts; the " +
                "person's check-in (mood, energy, tags); and the insights the app showed.",
            schema(dateProperty, account),
        ) { caller, args ->
            val user = accountOf(caller, args)
            formatDay(services.days.summary(user.id, args.string("date") ?: "today"), services.profiles.get(user.id).units)
        },
        Tool(
            "trends", "Trends",
            "A row per day (tab-separated): scores, steps, active calories, resting heart rate, HRV, sleep (total, deep, REM), " +
                "SpO2, temperature deviation, weight, food (calories, protein, carbs, fat), water, workouts. Good for spotting " +
                "patterns over weeks and months.",
            schema(daysProperty(30, 365), "to" to stringProperty("The last day (default today)"), account),
        ) { caller, args ->
            val user = accountOf(caller, args)
            val days = services.profiles.days(user.id)
            val to = args.string("to")?.let { days.parse(it, services.clock()) }
            trendsTable(services.days.trends(user.id, null, to, args.int("days")?.coerceIn(1, 365) ?: 30).days)
        },
        Tool(
            "sleep", "Sleep",
            "Nights (and naps), newest first: bedtime, wake time, time asleep, efficiency, deep/REM/light/awake, time to fall " +
                "asleep, lowest and average heart rate, HRV, and which device recorded it.",
            schema(daysProperty(14, 365), "stages" to boolProperty("Also each stage with its times (long)"), account),
        ) { caller, args -> sleep(accountOf(caller, args), args.int("days")?.coerceIn(1, 365) ?: 14, args.bool("stages") ?: false) },
        Tool(
            "workouts", "Workouts",
            "Workouts, newest first: type, when, how long, distance, calories, average and highest heart rate, effort, and where they came from.",
            schema(daysProperty(30, 3650), account),
        ) { caller, args -> workouts(accountOf(caller, args), args.int("days")?.coerceIn(1, 3650) ?: 30) },
        Tool(
            "food_log", "Food log",
            "What was eaten each day, by meal, with calories and protein, carbs and fat, totals against the day's target, and water.",
            schema(dateProperty, "days" to intProperty("Several days ending on date (default 1, at most 31)"), account),
        ) { caller, args -> foodLog(accountOf(caller, args), args.string("date") ?: "today", args.int("days")?.coerceIn(1, 31) ?: 1) },
        Tool(
            "readings", "Raw readings",
            "Raw readings of one metric as devices sent them, with the device each came from: for checking what a device " +
                "actually recorded (e.g. heart rate through a night). Metrics: ${Metric.entries.joinToString { it.name }}.",
            schema(
                "metric" to stringProperty("Which metric (e.g. HEART_RATE, STEPS, HRV, SPO2, WEIGHT)"),
                "from" to stringProperty("Start: YYYY-MM-DD, YYYY-MM-DDTHH:MM (local time) or today/yesterday (default 24 hours ago)"),
                "to" to stringProperty("End, as from (default now)"),
                "limit" to intProperty("At most this many, newest last (default 300, at most 3000)"),
                account,
                required = listOf("metric"),
            ),
        ) { caller, args -> readings(accountOf(caller, args), args) },
        Tool(
            "devices", "Devices",
            "Every device data has come from: kind, model, firmware, battery, when it last synced, how many readings it has " +
                "sent, and its latest reading of each kind. Upload keys and phone sign-ins are listed too.",
            schema(account),
        ) { caller, args -> devices(accountOf(caller, args)) },
        Tool(
            "sync_check", "Sync check",
            "Troubleshooting device syncs: for each device and day, how many heart rate, step and other readings arrived, " +
                "nights and workouts, with gaps flagged; and nights recorded by two devices at once (duplicates the scores " +
                "pick between).",
            schema(daysProperty(7, 60), account),
        ) { caller, args -> syncCheck(accountOf(caller, args), args.int("days")?.coerceIn(1, 60) ?: 7) },
        Tool(
            "profile", "Profile and goals",
            "The person's profile (birth year, height, which calorie formula, units, time zone), goals, and how today's " +
                "calorie and macro targets were worked out (resting energy, active calories, weight change aimed for).",
            schema(account),
        ) { caller, args -> profile(accountOf(caller, args)) },
        Tool(
            "search_foods", "Search foods",
            "Searches the person's own foods and the Open Food Facts database; each result gives calories and macros per serving and its barcode (for log_food).",
            schema("query" to stringProperty("What to search for"), account, required = listOf("query")),
        ) { caller, args -> searchFoods(accountOf(caller, args), args.string("query").orEmpty()) },
        Tool(
            "recent_warnings", "Recent warnings",
            "The server's latest warnings and errors (newest last): failed sign-ins, device uploads refused, food database trouble. Admins only.",
            schema(
                "limit" to intProperty("How many lines, newest last (default 60, at most 300)"),
                "contains" to stringProperty("Only lines containing this text (any case)"),
            ),
        ) { caller, args ->
            requireAdmin(caller)
            val contains = args.string("contains")?.lowercase()
            RecentLog.latest(warningsOnly = true).filter { contains == null || contains in it.lowercase() }
                .takeLast(args.int("limit")?.coerceIn(1, 300) ?: 60).joinToString("\n").ifBlank { "No warnings since the server started." }
        },
        Tool(
            "server_log", "Server log",
            "Everything the server logged lately (newest last): each API request with its status and time (uploads from " +
                "phones and watches, imports, sign-ins), what the AI connector was asked, warnings. Admins only.",
            schema(
                "limit" to intProperty("How many lines, newest last (default 200, at most 2000)"),
                "contains" to stringProperty("Only lines containing this text (any case), e.g. a path or a username"),
                "warnings_only" to boolProperty("Only warnings and errors"),
            ),
        ) { caller, args ->
            requireAdmin(caller)
            val contains = args.string("contains")?.lowercase()
            RecentLog.latest(warningsOnly = args.bool("warnings_only") ?: false).filter { contains == null || contains in it.lowercase() }
                .takeLast(args.int("limit")?.coerceIn(1, 2000) ?: 200).joinToString("\n").takeLast(MAX_TEXT).ifBlank { "Nothing logged that matches." }
        },

        // ---- Logging (links made with logging allowed) ----
        Tool(
            "log_food", "Log food",
            "Logs something eaten. Give a barcode (from search_foods) and servings, or a name with its calories (and protein, " +
                "carbs and fat in grams where known). Confirm with the person first.",
            schema(
                "meal" to enumProperty("Which meal", Meal.entries.map { it.name }),
                "name" to stringProperty("What it was"),
                "calories" to numberProperty("Kilocalories, for the amount eaten (or per serving with servings)"),
                "protein" to numberProperty("Grams of protein"),
                "carbs" to numberProperty("Grams of carbohydrate"),
                "fat" to numberProperty("Grams of fat"),
                "barcode" to stringProperty("A product's barcode, to log it from the food database"),
                "servings" to numberProperty("How many servings (default 1)"),
                dateProperty,
                required = listOf("meal"),
            ),
            writes = true,
        ) { caller, args -> logFood(caller.user, args) },
        Tool(
            "log_water", "Log water",
            "Logs water drunk, in millilitres.",
            schema("ml" to intProperty("Millilitres (a glass is about 250)"), dateProperty, required = listOf("ml")),
            writes = true,
        ) { caller, args ->
            val user = caller.user
            val date = services.profiles.days(user.id).parse(args.string("date") ?: "today", services.clock())
            val entry = services.foods.addWater(user.id, WaterEntry(date.toString(), args.int("ml") ?: error("How much water, in ml?")))
            services.days.changedDate(user.id, date)
            "Logged ${entry.ml} ml of water on ${entry.date}."
        },
        Tool(
            "log_weight", "Log weight",
            "Logs a weight, in kilograms or pounds.",
            schema("kg" to numberProperty("Kilograms"), "lb" to numberProperty("Pounds (if not kg)"), "at" to stringProperty("When: YYYY-MM-DD or YYYY-MM-DDTHH:MM (default now)")),
            writes = true,
        ) { caller, args ->
            val user = caller.user
            val kg = args.number("kg") ?: args.number("lb")?.let { it * 0.45359237 } ?: error("Give kg or lb")
            val at = args.string("at")?.let { instantOf(user, it) } ?: services.clock()
            val sample = services.ingest.manualSample(user.id, Sample(Metric.WEIGHT, at, value = (kg * 100).roundToInt() / 100.0))
            "Logged ${weight(sample.value, services.profiles.get(user.id).units)} at ${services.profiles.days(user.id).let { formatTime(it, at) }}."
        },
        Tool(
            "log_workout", "Log workout",
            "Logs a workout done without a device recording it.",
            schema(
                "type" to enumProperty("What kind", WorkoutType.entries.map { it.name }),
                "start" to stringProperty("When it started: YYYY-MM-DDTHH:MM (local) or HH:MM today"),
                "minutes" to intProperty("How long, in minutes"),
                "distance_km" to numberProperty("Distance in kilometres"),
                "calories" to numberProperty("Active calories burned"),
                "effort" to intProperty("How hard it felt, 1 to 10"),
                "name" to stringProperty("A name, e.g. Leg day"),
                "note" to stringProperty("A note"),
                required = listOf("type", "start", "minutes"),
            ),
            writes = true,
        ) { caller, args ->
            val user = caller.user
            val type = args.string("type")?.let { runCatching { WorkoutType.valueOf(it.uppercase()) }.getOrNull() } ?: WorkoutType.OTHER
            val start = instantOf(user, args.string("start") ?: error("When did it start?"))
            val minutes = args.int("minutes")?.takeIf { it in 1..1440 } ?: error("How many minutes?")
            val saved = services.ingest.manualWorkout(
                user.id,
                Workout(
                    type = type, start = start, end = start + minutes * 60_000L, name = args.string("name"),
                    distanceM = args.number("distance_km")?.let { it * 1000 }, activeCalories = args.number("calories"),
                    effort = args.int("effort"), note = args.string("note"),
                ),
            )
            "Logged ${saved.name ?: saved.type.label} for $minutes minutes starting ${formatTime(services.profiles.days(user.id), saved.start)}."
        },
        Tool(
            "check_in", "Check in",
            "Records how the person feels on a day: mood, energy, stress and soreness (each 1 to 5), tags (e.g. alcohol, " +
                "late meal, travel, illness, period) and a note. Only the parts given change.",
            schema(
                dateProperty,
                "mood" to intProperty("1 (low) to 5 (great)"),
                "energy" to intProperty("1 (drained) to 5 (full)"),
                "stress" to intProperty("1 (calm) to 5 (very stressed)"),
                "soreness" to intProperty("1 (none) to 5 (very sore)"),
                "tags" to buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }); put("description", "Tags to add") },
                "note" to stringProperty("A note"),
            ),
            writes = true,
        ) { caller, args ->
            val user = caller.user
            val date = services.profiles.days(user.id).parse(args.string("date") ?: "today", services.clock())
            val before = services.checkIns.get(user.id, date)
            val tags = (args["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val saved = services.checkIns.put(
                user.id, date,
                before.copy(
                    mood = args.int("mood") ?: before.mood, energy = args.int("energy") ?: before.energy,
                    stress = args.int("stress") ?: before.stress, soreness = args.int("soreness") ?: before.soreness,
                    tags = (before.tags + tags).distinct(), note = args.string("note") ?: before.note,
                ),
            )
            "Checked in for ${saved.date}: ${checkInText(saved)}."
        },
    )

    // ---- Tools ----

    private suspend fun status(caller: Caller, user: User): String = buildString {
        val uptime = services.clock() - startedAt
        appendLine("SuperAwesomeMagicalFitness ${Services.version}, up ${span(uptime)}.")
        val settings = services.settings.get()
        if (caller.user.isAdmin) {
            appendLine("Public address: ${settings.publicUrl.ifBlank { "(not set)" }}. Single sign-on: ${if (settings.oidc.enabled) "on (${settings.oidc.issuer})" else "off"}. Food database: ${if (settings.foodDatabase) "Open Food Facts" else "off"}.")
            val fixed = services.config.overrides.keys.filter { it in ServerConfig.OVERRIDES }.sorted()
            if (fixed.isNotEmpty()) appendLine("Set by environment: ${fixed.joinToString()}.")
        }
        if (caller.everyone) {
            appendLine()
            appendLine("Accounts:")
            services.users.list().forEach { u ->
                appendLine("- ${u.username} (${u.displayName})${if (u.isAdmin) " admin" else ""}${if (u.disabled) " DISABLED" else ""}${if (u.oidc) " SSO" else ""}, last sign-in ${u.lastLoginAt?.let { ago(it) } ?: "never"}")
            }
        }
        appendLine()
        appendLine("Account: ${user.username} (${user.displayName}).")
        val profile = services.profiles.get(user.id)
        val days = Days(Days.zoneOf(profile.timezone))
        val weight = services.records.latest(user.id, Metric.WEIGHT, services.clock() + 1)
        val missing = listOfNotNull(
            "birth year".takeIf { profile.birthYear == null },
            "height".takeIf { profile.heightCm == null },
            "a weight".takeIf { weight == null },
            "time zone (using the server's, ${days.zone})".takeIf { profile.timezone.isBlank() },
        )
        appendLine("Profile: " + if (missing.isEmpty()) "complete (time zone ${profile.timezone})." else "missing ${missing.joinToString(", ")}: resting-energy and calorie targets need birth year, height and weight.")
        val devices = services.devices.list(user.id)
        appendLine()
        appendLine(if (devices.isEmpty()) "No devices have sent data yet." else "Devices:")
        devices.forEach { d ->
            appendLine("- ${d.name} [${d.kind.label}${d.model?.let { ", $it" } ?: ""}]: last sync ${d.lastSyncAt?.let { ago(it) } ?: "never"}${d.battery?.let { ", battery $it%" } ?: ""}, %,d readings".format(d.samples))
        }
        val now = services.clock()
        val week = services.records.samples(user.id, now - 7 * DAY_MS, now + DAY_MS)
        appendLine()
        if (week.isEmpty()) {
            appendLine("No readings in the last 7 days.")
        } else {
            appendLine("Readings in the last 7 days:")
            week.groupBy { it.metric }.forEach { (metric, list) ->
                appendLine("- ${metric.label}: %,d, latest ${ago(list.maxOf { it.start })}".format(list.size))
            }
        }
        val sleep = services.records.sleep(user.id, now - 7 * DAY_MS, now + DAY_MS)
        val workouts = services.records.workouts(user.id, now - 7 * DAY_MS, now + DAY_MS)
        appendLine("Nights recorded in the last 7 days: ${sleep.size}. Workouts: ${workouts.size}.")
    }

    private fun formatDay(d: DaySummary, units: Units): String = buildString {
        appendLine("${d.date}${if (!d.hasData) " (nothing recorded)" else ""}")
        fun score(name: String, s: Score?) {
            if (s == null) {
                appendLine("$name: not enough data")
                return
            }
            appendLine("$name: ${s.score} (${s.label})")
            s.contributors.forEach { c -> appendLine("  - ${c.label}: ${c.score} (${c.value}${c.detail?.let { "; $it" } ?: ""})") }
        }
        score("Readiness", d.readiness)
        score("Sleep score", d.sleep)
        score("Activity score", d.activity)
        appendLine()
        appendLine("Activity: %,d steps (goal %,d), %s, %d active kcal (goal %d)%s".format(d.steps, d.goals.steps, distance(d.distanceM, units), d.activeCalories.roundToInt(), d.goals.activeCalories, d.restingCalories?.let { ", resting energy so far ${it.roundToInt()} kcal" } ?: ""))
        val heart = listOfNotNull(
            d.restingHeartRate?.let { "resting ${it.roundToInt()} bpm" }, d.avgHeartRate?.let { "average ${it.roundToInt()}" },
            d.minHeartRate?.let { "min ${it.roundToInt()}" }, d.maxHeartRate?.let { "max ${it.roundToInt()}" },
            d.hrv?.let { "HRV ${it.roundToInt()} ms" }, d.spo2?.let { "SpO2 ${it.roundToInt()}%" },
            d.respiratoryRate?.let { "breathing %.1f/min".format(it) }, d.temperatureDeviation?.let { "temperature ${Scoring.signed(it)} °C" },
            d.stress?.let { "stress ${it.roundToInt()}" },
        )
        if (heart.isNotEmpty()) appendLine("Heart and body: ${heart.joinToString(", ")}")
        d.sleepSummary?.let { appendLine("Sleep: ${nightText(it)}") }
        d.naps.forEach { appendLine("Nap: ${nightText(it)}") }
        d.workouts.forEach { appendLine("Workout: ${workoutText(it, units)}") }
        val n = d.nutrition
        d.nutritionTarget?.let { t ->
            appendLine("Food: %d of %d kcal; protein %d/%d g, carbs %d/%d g, fat %d/%d g; water %d/%d ml".format(n.calories.roundToInt(), t.calories, n.protein.roundToInt(), t.protein, n.carbs.roundToInt(), t.carbs, n.fat.roundToInt(), t.fat, d.waterMl, t.waterMl))
            appendLine("  Target: ${t.basis}")
        }
        d.weightKg?.let { appendLine("Weight: ${weight(it, units)}${d.bodyFat?.let { f -> ", body fat %.1f%%".format(f) } ?: ""}") }
        d.checkIn?.takeIf { it.mood != null || it.energy != null || it.stress != null || it.soreness != null || it.tags.isNotEmpty() || it.note != null }
            ?.let { appendLine("Check-in: ${checkInText(it)}") }
        if (d.insights.isNotEmpty()) {
            appendLine("Insights shown:")
            d.insights.forEach { appendLine("  - [${it.tone}] ${it.title}: ${it.body}") }
        }
    }

    private fun trendsTable(rows: List<com.fitness.api.TrendDay>): String = buildString {
        appendLine("date\treadiness\tsleep_score\tactivity_score\tsteps\tactive_kcal\tresting_hr\thrv_ms\tsleep_min\tdeep_min\trem_min\tspo2\ttemp_dev\tweight_kg\tkcal_eaten\tprotein_g\tcarbs_g\tfat_g\twater_ml\tworkouts")
        rows.forEach { r ->
            fun d(v: Double?, digits: Int = 0) = v?.let { if (digits == 0) it.roundToInt().toString() else String.format(Locale.ROOT, "%.${digits}f", it) } ?: ""
            appendLine(
                listOf(
                    r.date, r.readiness?.toString().orEmpty(), r.sleepScore?.toString().orEmpty(), r.activityScore?.toString().orEmpty(),
                    r.steps?.toString().orEmpty(), d(r.activeCalories), d(r.restingHeartRate, 1), d(r.hrv), r.sleepMinutes?.toString().orEmpty(),
                    r.deepMinutes?.toString().orEmpty(), r.remMinutes?.toString().orEmpty(), d(r.spo2, 1), d(r.temperatureDeviation, 2),
                    d(r.weightKg, 2), d(r.calories), d(r.protein), d(r.carbs), d(r.fat), r.waterMl?.toString().orEmpty(), r.workouts.toString(),
                ).joinToString("\t"),
            )
        }
    }

    private suspend fun sleep(user: User, days: Int, stages: Boolean): String {
        val now = services.clock()
        val from = now - days * DAY_MS
        val sessions = services.records.sleep(user.id, from, now + DAY_MS)
        if (sessions.isEmpty()) return "No nights recorded in the last $days days."
        val samples = services.records.samples(user.id, from - DAY_MS, now + DAY_MS, listOf(Metric.HEART_RATE, Metric.HRV))
        val dayClock = services.profiles.days(user.id)
        return sessions.reversed().joinToString("\n") { s ->
            val night = DayService.summarize(s, samples)
            "${formatTime(dayClock, night.start)} to ${formatTime(dayClock, night.end)}: ${nightText(night)}" +
                if (stages && night.stages.isNotEmpty()) "\n" + night.stages.joinToString("\n") { "    ${it.stage} ${formatTime(dayClock, it.start).substringAfter(' ')}–${formatTime(dayClock, it.end).substringAfter(' ')}" } else ""
        }
    }

    private suspend fun workouts(user: User, days: Int): String {
        val now = services.clock()
        val list = services.records.workouts(user.id, now - days * DAY_MS, now + DAY_MS)
        if (list.isEmpty()) return "No workouts in the last $days days."
        val units = services.profiles.get(user.id).units
        val dayClock = services.profiles.days(user.id)
        return list.joinToString("\n") { "${formatTime(dayClock, it.start)}: ${workoutText(it, units)}" }
    }

    private suspend fun foodLog(user: User, dateText: String, count: Int): String {
        val days = services.profiles.days(user.id)
        val last = days.parse(dateText, services.clock())
        val parts = mutableListOf<String>()
        for (back in count - 1 downTo 0) parts += foodDayText(user, last.minusDays(back.toLong()))
        return parts.joinToString("\n\n")
    }

    private suspend fun foodDayText(user: User, date: LocalDate): String = buildString {
        val day = foodDay(services, user.id, date)
        appendLine("$date: %d of %d kcal, protein %d/%d g, carbs %d/%d g, fat %d/%d g, water %d/%d ml".format(
            day.totals.calories.roundToInt(), day.target.calories, day.totals.protein.roundToInt(), day.target.protein,
            day.totals.carbs.roundToInt(), day.target.carbs, day.totals.fat.roundToInt(), day.target.fat, day.waterMl, day.target.waterMl,
        ))
        day.burned?.let { appendLine("  Burned so far: ${it.roundToInt()} kcal") }
        if (day.entries.isEmpty()) appendLine("  Nothing logged.")
        Meal.entries.forEach { meal ->
            val entries = day.entries.filter { it.meal == meal }
            if (entries.isNotEmpty()) {
                appendLine("  ${meal.label}:")
                entries.forEach { e ->
                    appendLine("    - ${e.name}${e.brand?.let { " ($it)" } ?: ""}, ${num(e.servings)} × ${e.serving}: ${nutrientText(e.nutrients)}")
                }
            }
        }
    }.trimEnd()

    private suspend fun readings(user: User, args: JsonObject): String {
        val metric = args.string("metric")?.uppercase()?.let { runCatching { Metric.valueOf(it) }.getOrNull() }
            ?: error("Which metric? One of ${Metric.entries.joinToString { it.name }}")
        val days = services.profiles.days(user.id)
        val now = services.clock()
        val from = args.string("from")?.let { instantOf(user, it) } ?: (now - DAY_MS)
        val to = args.string("to")?.let { text -> if (text.length == 10 || text == "today" || text == "yesterday") days.end(days.parse(text, now)) else instantOf(user, text) } ?: (now + 60_000)
        if (to <= from) error("The end has to come after the start")
        val list = services.records.samples(user.id, from, to, listOf(metric))
        if (list.isEmpty()) return "No ${metric.label.lowercase()} readings between ${formatTime(days, from)} and ${formatTime(days, to)}."
        val limit = args.int("limit")?.coerceIn(1, 3000) ?: 300
        val shown = list.takeLast(limit)
        return buildString {
            appendLine("%,d ${metric.label.lowercase()} readings (%s)%s:".format(list.size, metric.unit.ifBlank { "score" }, if (shown.size < list.size) ", the latest ${shown.size} shown" else ""))
            shown.forEach { s ->
                appendLine("${formatTime(days, s.start)}${s.end?.let { "–" + formatTime(days, it).substringAfter(' ') } ?: ""}\t${num(s.value)}\t${s.deviceName}")
            }
        }
    }

    private suspend fun devices(user: User): String = buildString {
        val list = services.devices.list(user.id)
        val now = services.clock()
        if (list.isEmpty()) appendLine("No devices have sent data yet.")
        list.forEach { d ->
            appendLine("${d.name} [${d.kind.label}] id ${d.id}")
            appendLine("  identifier ${d.identifier}${d.model?.let { ", model $it" } ?: ""}${d.firmware?.let { ", firmware $it" } ?: ""}${d.battery?.let { ", battery $it%" } ?: ""}")
            appendLine("  added ${ago(d.createdAt)}, last sync ${d.lastSyncAt?.let { ago(it) } ?: "never"}, %,d readings in all".format(d.samples))
            val recent = services.records.samples(user.id, now - 30 * DAY_MS, now + DAY_MS).filter { it.deviceId == d.id }
            recent.groupBy { it.metric }.forEach { (metric, samples) ->
                val last = samples.maxBy { it.start }
                appendLine("  ${metric.label}: %,d in 30 days, latest ${num(last.value)} ${metric.unit} ${ago(last.start)}".format(samples.size))
            }
        }
        val sessions = services.sessions.list(user.id).filter { it.kind != SessionKind.WEB }
        if (sessions.isNotEmpty()) {
            appendLine()
            appendLine("Apps, upload keys and connector links:")
            sessions.forEach { appendLine("- ${it.name} [${it.kind}], made ${ago(it.createdAt)}, last used ${ago(it.lastSeenAt)}") }
        }
    }

    private suspend fun syncCheck(user: User, dayCount: Int): String = buildString {
        val days = services.profiles.days(user.id)
        val today = days.today(services.clock())
        val first = today.minusDays(dayCount - 1L)
        val from = days.start(first)
        val to = days.end(today)
        val samples = services.records.samples(user.id, from, to)
        val sleep = services.records.sleep(user.id, from, to)
        val workouts = services.records.workouts(user.id, from, to)
        val devices = services.devices.list(user.id).filter { d -> samples.any { it.deviceId == d.id } || sleep.any { it.deviceId == d.id } || workouts.any { it.deviceId == d.id } }
        if (devices.isEmpty()) {
            appendLine("Nothing arrived from any device from $first to $today.")
            return@buildString
        }
        devices.forEach { d ->
            appendLine("${d.name} [${d.kind.label}], last sync ${d.lastSyncAt?.let { ago(it) } ?: "never"}:")
            var date = first
            while (!date.isAfter(today)) {
                val s = days.start(date)
                val e = days.end(date)
                val mine = samples.filter { it.deviceId == d.id && it.start in s until e }
                val counts = mine.groupingBy { it.metric }.eachCount()
                val nights = sleep.count { it.deviceId == d.id && it.end in s until e }
                val sessions = workouts.count { it.deviceId == d.id && it.start in s until e }
                val heartHours = mine.filter { it.metric == Metric.HEART_RATE }.map { days.hourOf(it.start) }.toSet().size
                val parts = counts.entries.sortedBy { it.key.ordinal }.map { (m, n) -> "${m.name.lowercase()} $n" } +
                    listOfNotNull(nights.takeIf { it > 0 }?.let { "nights $it" }, sessions.takeIf { it > 0 }?.let { "workouts $it" })
                val flag = when {
                    parts.isEmpty() -> "  ← nothing"
                    counts.containsKey(Metric.HEART_RATE) && heartHours < 18 && date != today -> "  ← heart rate in only $heartHours hours"
                    else -> ""
                }
                appendLine("  $date: ${parts.joinToString(", ").ifEmpty { "-" }}$flag")
                date = date.plusDays(1)
            }
        }
        val doubles = sleep.flatMap { a -> sleep.filter { b -> b.deviceId != a.deviceId && a.deviceId!! < b.deviceId!! && minOf(a.end, b.end) - maxOf(a.start, b.start) > 30 * 60_000 }.map { a to it } }
        if (doubles.isNotEmpty()) {
            appendLine()
            appendLine("Nights recorded by two devices (scores use the one with sleep stages, else the longer):")
            doubles.forEach { (a, b) -> appendLine("- ${formatTime(days, a.start)}: ${a.source} and ${b.source}") }
        }
    }

    private suspend fun profile(user: User): String = buildString {
        val p = services.profiles.get(user.id)
        val days = Days(Days.zoneOf(p.timezone))
        val today = days.today(services.clock())
        val weight = services.records.latest(user.id, Metric.WEIGHT, services.clock() + 1)
        appendLine("Birth year: ${p.birthYear ?: "not set"}; height: ${p.heightCm?.let { "${num(it)} cm" } ?: "not set"}; calorie formula: ${p.energyProfile.label}; units: ${p.units.name.lowercase()}; time zone: ${p.timezone.ifBlank { "not set (server's: ${days.zone})" }}")
        appendLine("Latest weight: ${weight?.let { "${weight(it.value, p.units)} on ${days.dateOf(it.start)}" } ?: "none"}")
        val g = p.goals
        appendLine("Goals: %,d steps, %d active kcal, %s sleep, %d workouts a week, water %d ml%s%s".format(
            g.steps, g.activeCalories, Scoring.hm(g.sleepMinutes), g.workoutsPerWeek, g.waterMl,
            g.targetWeightKg?.let { ", target weight ${weight(it, p.units)}" } ?: "",
            if (g.weeklyChangeKg != 0.0) ", aiming to ${if (g.weeklyChangeKg < 0) "lose" else "gain"} ${num(kotlin.math.abs(g.weeklyChangeKg))} kg a week" else "",
        ))
        val resting = Energy.restingCalories(p, weight?.value, today)
        appendLine("Resting energy: ${resting?.let { "${it.roundToInt()} kcal a day (Mifflin–St Jeor, ${p.energyProfile.label.lowercase()})" } ?: "unknown (needs birth year, height and a weight)"}")
        val (target, burned) = services.days.nutritionFor(user.id, today)
        appendLine("Today's targets: ${target.calories} kcal, protein ${target.protein} g, carbs ${target.carbs} g, fat ${target.fat} g, water ${target.waterMl} ml")
        appendLine("How: ${target.basis}")
        burned?.let { appendLine("Burned so far today: ${it.roundToInt()} kcal") }
    }

    private suspend fun searchFoods(user: User, query: String): String {
        if (query.length < 2) error("Search for at least two letters")
        val mine = services.foods.search(user.id, query)
        val online = if (services.settings.get().foodDatabase) runCatching { services.foodDatabase.search(query) }.getOrDefault(emptyList()) else emptyList()
        fun line(f: Food) = "- ${f.name}${f.brand?.let { " ($it)" } ?: ""}, per ${f.serving}: ${nutrientText(f.nutrients)}${f.barcode?.let { ", barcode $it" } ?: ""}"
        return buildString {
            if (mine.isNotEmpty()) { appendLine("The person's foods:"); mine.forEach { appendLine(line(it)) } }
            if (online.isNotEmpty()) { appendLine("Open Food Facts:"); online.take(15).forEach { appendLine(line(it)) } }
        }.ifBlank { "Nothing found for \"$query\"." }
    }

    private suspend fun logFood(user: User, args: JsonObject): String {
        val days = services.profiles.days(user.id)
        val date = days.parse(args.string("date") ?: "today", services.clock())
        val meal = args.string("meal")?.uppercase()?.let { runCatching { Meal.valueOf(it) }.getOrNull() } ?: error("Which meal: BREAKFAST, LUNCH, DINNER or SNACK?")
        val servings = args.number("servings") ?: 1.0
        val barcode = args.string("barcode")
        val input = if (barcode != null) {
            val food = services.foods.byBarcode(user.id, barcode) ?: services.foodDatabase.barcode(barcode) ?: error("No food with barcode $barcode; give its name and calories instead")
            FoodEntryInput(date.toString(), meal, servings, food = food)
        } else {
            val calories = args.number("calories") ?: error("Give the calories (or a barcode)")
            FoodEntryInput(
                date.toString(), meal, servings, name = args.string("name") ?: "Quick add",
                nutrients = Nutrients(calories, args.number("protein") ?: 0.0, args.number("carbs") ?: 0.0, args.number("fat") ?: 0.0),
            )
        }
        val entry = services.foods.log(user.id, input)
        services.days.changedDate(user.id, date)
        return "Logged ${entry.name} to ${meal.label.lowercase()} on ${entry.date}: ${nutrientText(entry.nutrients)}."
    }

    // ---- Helpers ----

    private val startedAt = services.clock()

    private fun requireAdmin(caller: Caller) {
        if (!caller.user.isAdmin) error("The server's log is for admins; this connector belongs to ${caller.user.username}")
    }

    /** The account a tool is asked about: the link's own, or (every-account links) any named one. */
    private suspend fun accountOf(caller: Caller, args: JsonObject): User {
        val name = args.string("account") ?: return caller.user
        if (name.equals(caller.user.username, ignoreCase = true)) return caller.user
        if (!caller.everyone) error("This link sees only ${caller.user.username}'s account (an admin's every-account link sees others)")
        return services.users.byUsername(name) ?: error("No account named $name")
    }

    /** "2026-01-31T07:30" or "07:30" (today), in the person's time zone; or a date (its start). */
    private suspend fun instantOf(user: User, text: String): Long {
        val days = services.profiles.days(user.id)
        val t = text.trim()
        return runCatching { LocalDateTime.parse(t.replace(' ', 'T').take(16)).atZone(days.zone).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalTime.parse(t).atDate(days.today(services.clock())).atZone(days.zone).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { days.start(days.parse(t, services.clock())) }.getOrNull()
            ?: error("Times look like 2026-01-31T07:30, 07:30 or 2026-01-31")
    }

    private fun nightText(n: SleepSummary): String = buildString {
        append("${Scoring.hm(n.asleepMinutes)} asleep of ${Scoring.hm(n.inBedMinutes)} in bed (${n.efficiency}% efficiency)")
        if (n.staged) append("; deep ${Scoring.hm(n.deepMinutes)}, REM ${Scoring.hm(n.remMinutes)}, light ${Scoring.hm(n.lightMinutes)}, awake ${Scoring.hm(n.awakeMinutes)}")
        n.latencyMinutes?.let { append("; fell asleep in ${it}m") }
        n.lowestHeartRate?.let { append("; lowest HR ${it.roundToInt()}") }
        n.avgHeartRate?.let { append(", average ${it.roundToInt()}") }
        n.avgHrv?.let { append("; HRV ${it.roundToInt()} ms") }
        n.source?.let { append(" [$it]") }
    }

    private fun workoutText(w: Workout, units: Units): String = buildString {
        append("${w.name ?: w.type.label} (${w.type.label}), ${Scoring.hm(((w.end - w.start) / 60_000).toInt())}")
        w.distanceM?.let { append(", ${distance(it, units)}") }
        w.activeCalories?.let { append(", ${it.roundToInt()} kcal") }
        w.avgHeartRate?.let { append(", average HR ${it.roundToInt()}") }
        w.maxHeartRate?.let { append(", max ${it.roundToInt()}") }
        w.steps?.let { append(", %,d steps".format(it)) }
        w.effort?.let { append(", effort $it/10") }
        w.note?.let { append(" — $it") }
        w.source?.let { append(" [$it]") }
    }

    private fun checkInText(c: CheckIn): String = listOfNotNull(
        c.mood?.let { "mood $it/5" }, c.energy?.let { "energy $it/5" }, c.stress?.let { "stress $it/5" }, c.soreness?.let { "soreness $it/5" },
        c.tags.takeIf { it.isNotEmpty() }?.joinToString(", ", "tags: "), c.note?.let { "note: $it" },
    ).joinToString("; ").ifEmpty { "nothing recorded" }

    private fun nutrientText(n: Nutrients) = "%d kcal, protein %s g, carbs %s g, fat %s g".format(n.calories.roundToInt(), num(n.protein), num(n.carbs), num(n.fat))

    private fun distance(m: Double, units: Units) =
        if (units == Units.IMPERIAL) String.format(Locale.ROOT, "%.2f mi", m / 1609.344) else String.format(Locale.ROOT, "%.2f km", m / 1000)

    private fun weight(kg: Double, units: Units) =
        if (units == Units.IMPERIAL) String.format(Locale.ROOT, "%.1f lb (%.1f kg)", kg / 0.45359237, kg) else String.format(Locale.ROOT, "%.1f kg", kg)

    private fun num(v: Double) = if (v % 1.0 == 0.0) v.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", v)

    private fun formatTime(days: Days, ms: Long) = MINUTE.format(Instant.ofEpochMilli(ms).atZone(days.zone))

    private fun ago(ms: Long): String {
        val d = services.clock() - ms
        return if (d < 0) "in ${span(-d)}" else if (d < 60_000) "just now" else "${span(d)} ago"
    }

    companion object {
        private val log = LoggerFactory.getLogger("Connector")

        /** Newest first; an older one is answered when a client asks for it. */
        val VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        private const val MAX_TEXT = 80_000
        private const val DAY_MS = 24L * 3600 * 1000
        private val MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

        fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }

        private fun schema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { properties.forEach { (name, schema) -> put(name, schema) } }
            if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            put("additionalProperties", false)
        }

        private fun stringProperty(description: String) = buildJsonObject { put("type", "string"); put("description", description) }
        private fun intProperty(description: String) = buildJsonObject { put("type", "integer"); put("description", description) }
        private fun numberProperty(description: String) = buildJsonObject { put("type", "number"); put("description", description) }
        private fun boolProperty(description: String) = buildJsonObject { put("type", "boolean"); put("description", description) }
        private fun enumProperty(description: String, values: List<String>) = buildJsonObject {
            put("type", "string")
            put("description", description)
            put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
        }

        private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        private fun JsonObject.int(name: String) = (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.roundToInt() }
        private fun JsonObject.number(name: String) = (this[name] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }?.takeIf { it.isFinite() }
        private fun JsonObject.bool(name: String) = (this[name] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

        /** "3h 12m", "2d 4h", "45m". */
        fun span(ms: Long): String {
            val minutes = (ms / 60_000).coerceAtLeast(0)
            return when {
                minutes >= 1440 -> "${minutes / 1440}d ${(minutes % 1440) / 60}h"
                minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
                else -> "${minutes}m"
            }
        }
    }
}
