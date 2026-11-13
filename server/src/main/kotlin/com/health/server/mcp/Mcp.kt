package com.health.server.mcp

import com.health.api.ApiJson
import com.health.api.BloodPressure
import com.health.api.Drink
import com.health.api.DrinkKind
import com.health.api.GlucoseContext
import com.health.api.GlucoseReading
import com.health.api.GlucoseUnit
import com.health.api.HabitTick
import com.health.api.Migraine
import com.health.api.SymptomEntry
import com.health.server.tracking.DrinksService
import com.health.server.tracking.VitalsService
import com.health.api.CheckIn
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.CycleDay
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.Flow
import com.health.api.LeakSize
import com.health.api.Module
import com.health.api.MoodEntry
import com.health.api.Screener
import com.health.api.DaySummary
import com.health.api.ErrorBody
import com.health.api.Food
import com.health.api.FoodEntryInput
import com.health.api.Meal
import com.health.api.Metric
import com.health.api.Nutrients
import com.health.api.Sample
import com.health.api.Score
import com.health.api.SessionKind
import com.health.api.SleepSummary
import com.health.api.Units
import com.health.api.User
import com.health.api.WaterEntry
import com.health.api.Workout
import com.health.api.WorkoutType
import com.health.server.ServerConfig
import com.health.server.Services
import com.health.server.food.foodDay
import com.health.server.insights.DayService
import com.health.server.insights.Energy
import com.health.server.insights.Scoring
import com.health.server.modules.Wear
import com.health.server.util.Days
import com.health.server.util.RecentLog
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
 * plain JSON), so people can bring their health data to the AI of their choice (Claude, or any
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
        val everyone = session.kind == SessionKind.MCP_ALL && user.isAdmin
        val caller = McpServer.Caller(
            user,
            everyone = everyone,
            canLog = session.kind == SessionKind.MCP_WRITE,
            // An every-account link never reads the sensitive modules.
            scope = if (session.kind == SessionKind.MCP_ALL) emptySet() else session.scope.filter { it.sensitive }.toSet(),
            water = services.days.showsWater(user.id),
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
    /**
     * Who's asking: the link's account, whether it sees every account, whether it may log entries,
     * and whether the account keeps track of water (off, water isn't offered).
     */
    class Caller(val user: User, val everyone: Boolean, val canLog: Boolean, val scope: Set<Module> = emptySet(), val water: Boolean = true)

    private class Tool(
        val name: String,
        val title: String,
        val description: String,
        val inputSchema: JsonObject,
        val writes: Boolean = false,
        /** A sensitive module the link must have been given. */
        val module: Module? = null,
        /** Only for someone who keeps track of water ([com.health.api.Fields.NUTRITION_WATER]). */
        val water: Boolean = false,
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

    private fun toolsFor(caller: Caller) = tools.filter { (!it.writes || caller.canLog) && (it.module == null || it.module in caller.scope) && (!it.water || caller.water) }

    /** Whether [user] keeps track of water: the caller's own answer, or asked for another account. */
    private suspend fun waterOf(caller: Caller, user: User) = if (user.id == caller.user.id) caller.water else services.days.showsWater(user.id)

    // ---- The in-app assistant (ai/) uses these tools too, through its own allowlist (ai/AiTools.kt) ----

    /** A tool as the in-app assistant sees it. */
    class AssistantTool(val name: String, val description: String, val inputSchema: JsonObject, val writes: Boolean, val module: Module?)

    /** The tools [caller] may use, for the in-app assistant (which narrows them further). */
    fun assistantTools(caller: Caller): List<AssistantTool> =
        toolsFor(caller).map { AssistantTool(it.name, it.description, it.inputSchema, it.writes, it.module) }

    /** Runs one for the in-app assistant: its text (failures throw, and become error results). */
    suspend fun runAssistantTool(caller: Caller, name: String, arguments: JsonObject): String {
        val tool = toolsFor(caller).firstOrNull { it.name == name } ?: throw IllegalArgumentException("No tool named $name")
        log.info("Assistant of {}: {}", caller.user.username, name)
        return tool.run(caller, arguments).take(MAX_TEXT)
    }

    private fun initialize(params: JsonObject, caller: Caller): JsonObject {
        val asked = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", asked?.takeIf { it in VERSIONS } ?: VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", "superawesomemagicalhealth")
                put("title", "SuperAwesomeMagicalHealth")
                put("version", Services.version)
            }
            put(
                "instructions",
                "A self-hosted health server (SuperAwesomeMagicalHealth): wearables (Colmi rings, Pebble, Garmin, " +
                    "anything in Health Connect or Gadgetbridge) send heart rate, HRV, sleep, steps and workouts through a phone " +
                    "app, and people log food, ${if (caller.water) "water, " else ""}weight and check-ins. It works out Oura-style readiness, sleep and activity " +
                    "scores against each person's own baseline. Start with status (what's connected, what's missing), then day " +
                    "for a day in full, trends for weeks and months, and sleep, workouts and food_log for detail. For device " +
                    "troubleshooting use devices and sync_check (gaps, duplicates, stale syncs) and readings for raw data" +
                    (if (caller.user.isAdmin) "; recent_warnings and server_log show the server's own log" else "") + ". " +
                    (if (caller.scope.isNotEmpty()) "It may also read ${caller.scope.joinToString { it.label }} (${toolsFor(caller).filter { it.module != null && !it.writes }.joinToString { it.name }}): treat these with particular care and kindness. " else "") +
                    (if (caller.canLog) "This link may also log entries (${toolsFor(caller).filter { it.writes }.joinToString { it.name }}): confirm what to log with the person first. " else "") +
                    (if (Module.MIND in caller.scope) "If the person mentions thoughts of harming themselves, gently share the support lines from the mind tool and encourage reaching out. " else "") +
                    "This is personal health data: it's for the person's own understanding, not medical advice.",
            )
        }
    }

    private suspend fun callTool(id: JsonElement, caller: Caller, params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
        val tool = toolsFor(caller).firstOrNull { it.name == name }
            ?: return error(
                id, -32602,
                when {
                    tools.any { it.name == name && it.module != null && it.module !in caller.scope } ->
                        "This link wasn't given ${tools.first { it.name == name }.module!!.label}; make one that includes it in Settings"
                    tools.any { it.name == name && it.water } && !caller.water ->
                        "This person has turned water off, so there's no water to log. They can turn it back on in Settings › Features, under Food"
                    tools.any { it.name == name } -> "This link can only read; make one that may log entries in Settings"
                    else -> "No tool named $name"
                },
            )
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
                "person's check-in (mood, energy, tags); and the insights the app showed. Where more than one device measured " +
                "something, a line gives the app's combined number, each device's own, and how it was worked out.",
            schema(dateProperty, account),
        ) { caller, args ->
            val user = accountOf(caller, args)
            formatDay(
                services.days.summary(user.id, args.string("date") ?: "today"), services.profiles.get(user.id).units,
                caller.scope.takeIf { user.id == caller.user.id }.orEmpty(), waterOf(caller, user),
            )
        },
        Tool(
            "trends", "Trends",
            "A row per day (tab-separated): scores, steps, active calories, resting heart rate, HRV, sleep (total, deep, REM), " +
                "SpO2, temperature deviation, weight, food (calories, protein, carbs, fat), water (where it's tracked), workouts. " +
                "Good for spotting patterns over weeks and months.",
            schema(daysProperty(30, 365), "to" to stringProperty("The last day (default today)"), account),
        ) { caller, args ->
            val user = accountOf(caller, args)
            val days = services.profiles.days(user.id)
            val to = args.string("to")?.let { days.parse(it, services.clock()) }
            trendsTable(
                services.days.trends(user.id, null, to, args.int("days")?.coerceIn(1, 365) ?: 30).days,
                caller.scope.takeIf { user.id == caller.user.id }.orEmpty(), waterOf(caller, user),
            )
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
            "What was eaten each day, by meal, with calories and protein, carbs and fat, totals against the day's target, and water (where it's tracked).",
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
        Tool(
            "webhook_deliveries", "Webhook deliveries",
            "The account's webhooks (where each points, what it sends) and their latest calls, newest first: when, which " +
                "event, whether it worked, and the HTTP status or error. For when Home Assistant or another hook isn't firing.",
            schema("limit" to intProperty("How many calls (default 30, at most 200)")),
        ) { caller, args -> com.health.server.platform.PlatformMcp.deliveries(services, caller.user, args.int("limit")?.coerceIn(1, 200) ?: 30) },
        Tool(
            "notifications_status", "Notifications status",
            "Reminders for the account: browsers signed up for notifications (and when each last took one), webhooks, when " +
                "the reminder round last ran, reminders due in the next day, whether email is set up, and the latest weekly digest.",
            schema(),
        ) { caller, _ -> com.health.server.platform.PlatformMcp.status(services, caller.user) },

        // ---- Health modules (links given them) ----
        Tool(
            "mind", "Mood and mind",
            "Mood over recent days (1 low to 5 great, with energy and anxiety), the feelings and situations named most, " +
                "what seems to go with better days, recent mood notes, PHQ-9 and GAD-7 check-in results, calm practice, " +
                "and the support lines set on this server.",
            schema(daysProperty(30, 365)),
            module = Module.MIND,
        ) { caller, args -> mindText(caller.user, args.int("days")?.coerceIn(1, 365) ?: 30) },
        Tool(
            "journal", "Journal",
            "The person's journal entries (free writing, gratitude, thought records), newest first. Private: only read when they ask about it.",
            schema(daysProperty(14, 365)),
            module = Module.MIND,
        ) { caller, args -> journalText(caller.user, args.int("days")?.coerceIn(1, 365) ?: 14) },
        Tool(
            "cycle", "Cycle",
            "Periods and cycle: logged days (flow, symptoms, pain, temperature), past cycles with their lengths, averages, " +
                "today's cycle day and phase, and the prediction for the next period and fertile window (estimates only).",
            schema("months" to intProperty("How many months back (default 6, at most 36)")),
            module = Module.CYCLE,
        ) { caller, args -> cycleText(caller.user, args.int("months")?.coerceIn(1, 36) ?: 6) },
        Tool(
            "continence", "Bladder and bowel",
            "The bladder and bowel diary: each toilet trip, leak, bowel movement, change of pad or brief (what came off, what went on, " +
                "how long it was worn) and pelvic floor session, with daily counts, night-time trips, fluid logged, triggers for leaks and " +
                "patterns, what's being worn now, and how each product goes (usual wear time, how full, leaks through).",
            schema(daysProperty(7, 90)),
            module = Module.CONTINENCE,
        ) { caller, args -> continenceText(caller.user, args.int("days")?.coerceIn(1, 90) ?: 7) },
        Tool(
            "medications", "Medications",
            "Medications and supplements with their schedules, today's doses (taken, skipped, due), adherence over a week, " +
                "injection and patch site rotation, low stock, and lab results over time.",
            schema(dateProperty),
            module = Module.MEDICATIONS,
        ) { caller, args -> medsText(caller.user, args.string("date") ?: "today") },

        // ---- Blood pressure and glucose, drinks, symptoms, stress, habits (tracking/) ----
        Tool(
            "vitals", "Blood pressure and glucose",
            "Blood pressure and glucose readings (newest first) with each one's category, averages, time in range, an A1c " +
                "estimate from a sensor, the person's own targets, and patterns (morning against evening, after meals, lows).",
            schema(daysProperty(30, 365), account),
        ) { caller, args -> vitalsText(accountOf(caller, args), args.int("days")?.coerceIn(1, 365) ?: 30) },
        Tool(
            "drinks", "Drinks",
            "Drinks by day: caffeine (mg, and when the last was), alcohol (standard drinks of 14 g), other drinks (which count " +
                "towards water, where it's tracked); averages, and how caffeine and alcohol went with sleep and HRV.",
            schema(daysProperty(14, 365), account),
        ) { caller, args -> drinksText(accountOf(caller, args), args.int("days")?.coerceIn(1, 365) ?: 14) },
        Tool(
            "stress", "Stress and resilience",
            "Daytime stress by day: minutes stressed, engaged, relaxed and restored while awake and still (from heart rate and " +
                "HRV against the person's baseline, or the device's own stress readings), and resilience over two weeks with what it's made of.",
            schema(daysProperty(14, 90), account),
        ) { caller, args -> stressText(accountOf(caller, args), args.int("days")?.coerceIn(1, 90) ?: 14) },
        Tool(
            "habits", "Habits",
            "Habits with their status on a day: done or not, days done this week against the aim, week streaks and day streaks " +
                "(which forgive one missed day in seven), and the last two weeks.",
            schema(dateProperty, account),
        ) { caller, args -> habitsText(accountOf(caller, args), args.string("date") ?: "today") },
        Tool(
            "symptoms", "Symptoms and migraines",
            "Symptoms, pain and migraines: each entry with its severity (0–10), triggers and what helped; counts; and what went " +
                "with symptom days in the person's own data (short nights, stress, alcohol, caffeine, the cycle, falling air pressure).",
            schema(daysProperty(30, 365)),
            module = Module.SYMPTOMS,
        ) { caller, args -> symptomsText(caller.user, args.int("days")?.coerceIn(1, 365) ?: 30) },

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
            water = true,
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
            "log_mood", "Log mood",
            "Logs how the person feels now: mood 1 (very low) to 5 (great), with optional energy and anxiety (1–5), feelings, context and a note.",
            schema(
                "mood" to intProperty("1 very low … 5 great"),
                "energy" to intProperty("1–5"),
                "anxiety" to intProperty("1 calm … 5 very anxious"),
                "feelings" to stringArray("Feelings, e.g. calm, anxious, proud"),
                "context" to stringArray("What it's about, e.g. work, family, sleep"),
                "note" to stringProperty("A note"),
                required = listOf("mood"),
            ),
            writes = true,
            module = Module.MIND,
        ) { caller, args ->
            val saved = services.modules.mind.saveMood(
                caller.user.id,
                MoodEntry(
                    at = services.clock(), mood = args.int("mood") ?: error("How's the mood, 1 to 5?"), energy = args.int("energy"), anxiety = args.int("anxiety"),
                    feelings = args.strings("feelings"), context = args.strings("context"), note = args.string("note"),
                ),
            )
            "Logged mood ${saved.mood}/5 for ${saved.date}."
        },
        Tool(
            "log_cycle_day", "Log cycle day",
            "Logs a day of the cycle: flow (SPOTTING, LIGHT, MEDIUM, HEAVY, or NONE to clear it), symptoms, pain 0–10, basal temperature (°C) and a note. Adds to what's already logged that day.",
            schema(
                dateProperty,
                "flow" to enumProperty("Bleeding", Flow.entries.map { it.name } + "NONE"),
                "symptoms" to stringArray("e.g. cramps, headache, bloating"),
                "pain" to intProperty("0–10"),
                "temperature" to numberProperty("Basal body temperature, °C"),
                "note" to stringProperty("A note"),
            ),
            writes = true,
            module = Module.CYCLE,
        ) { caller, args ->
            val user = caller.user
            val date = services.profiles.days(user.id).parse(args.string("date") ?: "today", services.clock())
            val before = services.modules.cycle.logged(user.id, date, date).firstOrNull() ?: CycleDay(date.toString())
            val flowArg = args.string("flow")?.uppercase()
            val saved = services.modules.cycle.put(
                user.id,
                before.copy(
                    flow = when (flowArg) { null -> before.flow; "NONE" -> null; else -> runCatching { Flow.valueOf(flowArg) }.getOrElse { error("Flow is SPOTTING, LIGHT, MEDIUM, HEAVY or NONE") } },
                    symptoms = (before.symptoms + args.strings("symptoms")).distinct(),
                    pain = args.int("pain") ?: before.pain,
                    temperature = args.number("temperature") ?: before.temperature,
                    note = args.string("note") ?: before.note,
                ),
            )
            "Logged ${saved.date}: ${cycleDayText(saved)}."
        },
        Tool(
            "log_continence", "Log bladder or bowel",
            "Logs a diary entry: kind URINATION (toilet trip), LEAK, BOWEL, PAD_CHANGE or PELVIC_FLOOR, with optional urgency 0–3, leak size, triggers, Bristol type 1–7, volume and time.",
            schema(
                "kind" to enumProperty("What happened", ContinenceKind.entries.map { it.name }),
                "at" to stringProperty("When: YYYY-MM-DDTHH:MM or HH:MM today (default now)"),
                "urgency" to intProperty("0 none … 3 couldn't wait"),
                "leak" to enumProperty("Leak size", LeakSize.entries.map { it.name }),
                "triggers" to stringArray("e.g. cough, sneeze, urge"),
                "bristol" to intProperty("Bristol stool type, 1–7"),
                "volume_ml" to intProperty("Measured volume, ml"),
                "reps" to intProperty("Pelvic floor repetitions"),
                required = listOf("kind"),
            ),
            writes = true,
            module = Module.CONTINENCE,
        ) { caller, args ->
            val kind = args.string("kind")?.uppercase()?.let { runCatching { ContinenceKind.valueOf(it) }.getOrNull() } ?: error("Which kind?")
            val saved = services.modules.continence.save(
                caller.user.id,
                ContinenceEntry(
                    at = args.string("at")?.let { instantOf(caller.user, it) } ?: services.clock(), kind = kind, urgency = args.int("urgency"),
                    leak = args.string("leak")?.uppercase()?.let { runCatching { LeakSize.valueOf(it) }.getOrNull() },
                    triggers = args.strings("triggers"), bristol = args.int("bristol"), volumeMl = args.int("volume_ml"), reps = args.int("reps"),
                ),
            )
            "Logged ${saved.kind.label.lowercase()} at ${formatTime(services.profiles.days(caller.user.id), saved.at)}."
        },
        Tool(
            "log_dose", "Log a dose",
            "Marks a medication dose as taken (or skipped): give the medication's name; it matches the nearest scheduled dose today if there is one.",
            schema(
                "medication" to stringProperty("The medication's name"),
                "status" to enumProperty("Taken or skipped", listOf("TAKEN", "SKIPPED")),
                "at" to stringProperty("When: YYYY-MM-DDTHH:MM or HH:MM today (default now)"),
                "site" to stringProperty("For injections and patches: where"),
                required = listOf("medication"),
            ),
            writes = true,
            module = Module.MEDICATIONS,
        ) { caller, args ->
            val user = caller.user
            val name = args.string("medication") ?: error("Which medication?")
            val med = services.modules.meds.list(user.id).firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: services.modules.meds.list(user.id).firstOrNull { it.name.contains(name, ignoreCase = true) }
                ?: error("No medication called $name")
            val at = args.string("at")?.let { instantOf(user, it) } ?: services.clock()
            val days = services.profiles.days(user.id)
            val slot = services.modules.meds.day(user.id, days.dateOf(at)).due
                .filter { it.medicationId == med.id && it.log == null }.minByOrNull { kotlin.math.abs(it.scheduledFor - at) }
            val status = if (args.string("status")?.uppercase() == "SKIPPED") DoseStatus.SKIPPED else DoseStatus.TAKEN
            val saved = services.modules.meds.logDose(user.id, DoseLog(med.id!!, at, status, slot?.scheduledFor, site = args.string("site") ?: slot?.nextSite))
            "Logged ${med.name} as ${saved.status.name.lowercase()} at ${formatTime(days, saved.at)}${saved.site?.let { ", $it" } ?: ""}${slot?.let { " (the ${formatTime(days, it.scheduledFor).substringAfter(' ')} dose)" } ?: ""}."
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

        // ---- Logging blood pressure, glucose, drinks, symptoms and habits (tracking/) ----
        Tool(
            "log_blood_pressure", "Log blood pressure",
            "Logs a blood pressure reading: systolic and diastolic in mmHg, with the pulse if known.",
            schema(
                "systolic" to intProperty("The top number, mmHg"),
                "diastolic" to intProperty("The bottom number, mmHg"),
                "pulse" to intProperty("Beats a minute"),
                "arm" to enumProperty("Which arm", listOf("left", "right")),
                "at" to stringProperty("When: YYYY-MM-DDTHH:MM or HH:MM today (default now)"),
                "note" to stringProperty("A note"),
                required = listOf("systolic", "diastolic"),
            ),
            writes = true,
        ) { caller, args ->
            val user = caller.user
            val saved = services.tracking.vitals.saveBloodPressure(
                user.id,
                BloodPressure(
                    at = args.string("at")?.let { instantOf(user, it) } ?: services.clock(), systolic = args.int("systolic") ?: error("The top number?"),
                    diastolic = args.int("diastolic") ?: error("The bottom number?"), pulse = args.int("pulse"), arm = args.string("arm"), note = args.string("note"),
                ),
            )
            "Logged ${saved.systolic}/${saved.diastolic}${saved.pulse?.let { ", pulse $it" } ?: ""} (${VitalsService.category(saved.systolic, saved.diastolic).label.lowercase()}) at ${formatTime(services.profiles.days(user.id), saved.at)}."
        },
        Tool(
            "log_glucose", "Log glucose",
            "Logs a blood glucose reading, in mmol/L or mg/dL, with when it was taken (fasting, before or after a meal, bedtime).",
            schema(
                "value" to numberProperty("The reading"),
                "unit" to enumProperty("Its unit (default the person's own)", listOf("mmol/L", "mg/dL")),
                "context" to enumProperty("When it was taken", GlucoseContext.entries.filter { it != GlucoseContext.CGM }.map { it.name }),
                "at" to stringProperty("When: YYYY-MM-DDTHH:MM or HH:MM today (default now)"),
                "note" to stringProperty("A note"),
                required = listOf("value"),
            ),
            writes = true,
        ) { caller, args ->
            val user = caller.user
            val targets = services.profiles.get(user.id).vitalsTargets
            val value = args.number("value") ?: error("What was the reading?")
            val mgdl = when (args.string("unit")?.lowercase()) { "mg/dl" -> true; "mmol/l" -> false; else -> targets.glucoseUnit == GlucoseUnit.MG_DL || value > 40 }
            val saved = services.tracking.vitals.saveGlucose(
                user.id,
                GlucoseReading(
                    at = args.string("at")?.let { instantOf(user, it) } ?: services.clock(), mmolL = if (mgdl) value / VitalsService.MG_PER_MMOL else value,
                    context = args.string("context")?.uppercase()?.let { runCatching { GlucoseContext.valueOf(it) }.getOrNull() } ?: GlucoseContext.OTHER,
                    note = args.string("note"),
                ),
            )
            "Logged ${VitalsService.glucoseText(saved.mmolL, targets.glucoseUnit)} (${saved.context.label.lowercase()}) at ${formatTime(services.profiles.days(user.id), saved.at)}."
        },
        Tool(
            "log_drink", "Log a drink",
            "Logs a drink: coffee, tea, alcohol and the rest. Caffeine and alcohol are filled in from the kind and size unless given.",
            schema(
                "kind" to enumProperty("What kind", DrinkKind.entries.map { it.name }),
                "ml" to intProperty("How much, in ml (default a usual serving)"),
                "caffeine_mg" to numberProperty("Caffeine in mg, if known"),
                "alcohol_g" to numberProperty("Alcohol in grams, if known (a standard drink is 14 g)"),
                "name" to stringProperty("A name, e.g. Flat white"),
                "at" to stringProperty("When: YYYY-MM-DDTHH:MM or HH:MM today (default now)"),
                required = listOf("kind"),
            ),
            writes = true,
        ) { caller, args ->
            val user = caller.user
            val kind = args.string("kind")?.uppercase()?.let { runCatching { DrinkKind.valueOf(it) }.getOrNull() } ?: error("Which kind of drink?")
            if (kind == DrinkKind.WATER && !caller.water) error("This person has turned water off, so water isn't logged. Coffee, tea, alcohol and the rest still are.")
            val ml = args.int("ml") ?: kind.defaultMl
            val saved = services.tracking.drinks.save(
                user.id,
                Drink(
                    at = args.string("at")?.let { instantOf(user, it) } ?: services.clock(), kind = kind, volumeMl = ml,
                    caffeineMg = args.number("caffeine_mg") ?: (ml * kind.caffeineMgPer100Ml / 100).roundToInt().toDouble(),
                    alcoholG = args.number("alcohol_g") ?: (ml * kind.alcoholPercent / 100 * 0.789 * 10).roundToInt() / 10.0,
                    name = args.string("name"),
                ),
            )
            "Logged ${saved.name ?: saved.kind.label.lowercase()}, ${saved.volumeMl} ml${if (saved.caffeineMg > 0) ", ${saved.caffeineMg.roundToInt()} mg caffeine" else ""}${if (saved.alcoholG > 0) ", ${DrinksService.drinksText((saved.alcoholG / DrinksService.STANDARD_DRINK_G * 10).roundToInt() / 10.0)}" else ""}."
        },
        Tool(
            "log_symptom", "Log a symptom",
            "Logs a symptom with its severity 0–10, triggers and what helped. A symptom called migraine logs a migraine (with aura if said).",
            schema(
                "symptom" to stringProperty("e.g. headache, back pain, fatigue, migraine"),
                "severity" to intProperty("0 none … 10 worst imaginable"),
                "triggers" to stringArray("What seemed to bring it on"),
                "relief" to stringArray("What was tried, e.g. ibuprofen, rest"),
                "aura" to boolProperty("For a migraine: with aura"),
                "at" to stringProperty("When it started: YYYY-MM-DDTHH:MM or HH:MM today (default now)"),
                "note" to stringProperty("A note"),
                required = listOf("symptom", "severity"),
            ),
            writes = true,
            module = Module.SYMPTOMS,
        ) { caller, args ->
            val user = caller.user
            val at = args.string("at")?.let { instantOf(user, it) } ?: services.clock()
            val name = args.string("symptom") ?: error("Which symptom?")
            val severity = args.int("severity") ?: error("How bad, 0 to 10?")
            if (name.equals("migraine", ignoreCase = true)) {
                val saved = services.tracking.symptoms.saveMigraine(user.id, Migraine(at, severity = severity, aura = args.bool("aura") ?: false, triggers = args.strings("triggers"), medications = args.strings("relief"), note = args.string("note")))
                "Logged a migraine starting ${formatTime(services.profiles.days(user.id), saved.start)}, ${saved.severity}/10."
            } else {
                val saved = services.tracking.symptoms.save(user.id, SymptomEntry(at, name, severity, triggers = args.strings("triggers"), relief = args.strings("relief"), note = args.string("note")))
                "Logged ${saved.symptom}, ${saved.severity}/10, at ${formatTime(services.profiles.days(user.id), saved.at)}."
            }
        },
        Tool(
            "tick_habit", "Tick a habit",
            "Marks a habit done on a day (by its name), or sets how many times it was done.",
            schema(
                "habit" to stringProperty("The habit's name"),
                dateProperty,
                "count" to intProperty("Times done that day (default enough for done; 0 clears it)"),
                required = listOf("habit"),
            ),
            writes = true,
        ) { caller, args ->
            val user = caller.user
            val name = args.string("habit") ?: error("Which habit?")
            val all = services.tracking.habits.list(user.id)
            val habit = all.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: all.firstOrNull { it.name.contains(name, ignoreCase = true) } ?: error("No habit called $name")
            val date = services.profiles.days(user.id).parse(args.string("date") ?: "today", services.clock())
            val tick = services.tracking.habits.tick(user.id, habit.id!!, HabitTick(date.toString(), args.int("count") ?: habit.perDay))
            if (tick.count == 0) "Cleared ${habit.name} on ${tick.date}." else "Ticked ${habit.name} on ${tick.date}${if (habit.perDay > 1) " (${tick.count} of ${habit.perDay})" else ""}."
        },
        *careTools().toTypedArray(),
        *neuroTransitionTools().toTypedArray(),
        *diaryTools().toTypedArray(),
    )

    // ---- Autism & ADHD, and transition (neuro/, transition/). Photos are never offered. ----

    private fun neuroTransitionTools(): List<Tool> = listOf(
        Tool(
            "neuro", "Autism & ADHD",
            "The person's Autism & ADHD log, in their own words: about them (what helps, how they communicate, senses), times things got too much " +
                "(overwhelm, meltdowns, shutdowns) with triggers and what helped, energy and social battery, masking, focus time, and patterns. " +
                "Speak about it in neurodiversity-affirming terms.",
            schema(daysProperty(30, 365)),
            module = Module.NEURO,
        ) { caller, args -> com.health.server.neuro.NeuroText.neuro(services, caller.user.id, args.int("days")?.coerceIn(7, 365) ?: 30) },
        Tool(
            "transition", "Transition",
            "The person's transition, whatever it means to them: pronouns, how long since they began and on hormones, milestones (social, legal, " +
                "medical, voice) with checklists, changes they've noticed, voice practice and pitch, the voice programme (their own goal and range, " +
                "lessons done, this week's practice, and how it compares with where they started), hair removal, and moments of euphoria and dysphoria.",
            schema(),
            module = Module.TRANSITION,
        ) { caller, _ -> com.health.server.neuro.NeuroText.transition(services, caller.user.id) },
        Tool(
            "log_overload", "Log a time things got too much",
            "Logs overwhelm, sensory overload, a meltdown, a shutdown or panic: when, how intense (1–5), triggers and what helped.",
            schema(
                "kind" to enumProperty("What it was", com.health.api.OverloadKind.entries.map { it.name }),
                "intensity" to intProperty("1 mild … 5 intense (default 3)"),
                "triggers" to stringArray("What seemed to set it off"),
                "helped" to stringArray("What helped"),
                "at" to stringProperty("When it started: YYYY-MM-DDTHH:MM or HH:MM today (default now)"),
                "note" to stringProperty("A note"),
                required = listOf("kind"),
            ),
            writes = true,
            module = Module.NEURO,
        ) { caller, args ->
            val kind = args.string("kind")?.uppercase()?.let { runCatching { com.health.api.OverloadKind.valueOf(it) }.getOrNull() } ?: error("Which kind?")
            val at = args.string("at")?.let { instantOf(caller.user, it) } ?: services.clock()
            val saved = services.neuro.saveEvent(
                caller.user.id,
                com.health.api.OverloadEvent(at, kind = kind, intensity = args.int("intensity") ?: 3, triggers = args.strings("triggers"), whatHelped = args.strings("helped"), note = args.string("note")),
            )
            "Logged ${saved.kind.label.lowercase()} (${saved.intensity} of 5) at ${formatTime(services.profiles.days(caller.user.id), saved.start)}."
        },
        Tool(
            "log_energy", "Log energy",
            "Sets a day's energy budget (spoons or any unit) and adds what used or gave energy back, and the social battery (0–10).",
            schema(
                dateProperty,
                "start_with" to intProperty("Energy to start the day with"),
                "spend_what" to stringProperty("Something that used energy (or gave it back)"),
                "spend_amount" to intProperty("How much it used (negative for gave back)"),
                "social_battery" to intProperty("0 empty … 10 full"),
            ),
            writes = true,
            module = Module.NEURO,
        ) { caller, args ->
            val userId = caller.user.id
            val date = services.profiles.days(userId).parse(args.string("date") ?: "today", services.clock())
            val day = services.neuro.energyOn(userId, date) ?: com.health.api.EnergyDay(date.toString())
            val spend = args.string("spend_what")?.let { com.health.api.EnergySpend(it, args.int("spend_amount") ?: 1, services.clock()) }
            val saved = services.neuro.saveEnergy(
                userId, date.toString(),
                day.copy(startWith = args.int("start_with") ?: day.startWith, spends = day.spends + listOfNotNull(spend), socialBattery = args.int("social_battery") ?: day.socialBattery),
            )
            "Energy for ${saved.date}: " + (saved.startWith?.let { "${it - saved.spends.sumOf { s -> s.amount }} of $it left" } ?: "${saved.spends.size} things logged") +
                (saved.socialBattery?.let { ", social battery $it of 10" } ?: "") + "."
        },
        Tool(
            "log_moment", "Log a moment of euphoria or dysphoria",
            "Logs a moment of gender euphoria or dysphoria: how strong (1–5), what it was about, and a note.",
            schema(
                "kind" to enumProperty("Euphoria or dysphoria", com.health.api.MomentKind.entries.map { it.name }),
                "intensity" to intProperty("1–5 (default 3)"),
                "context" to stringArray("What it was about: mirror, voice, being gendered right, clothes…"),
                "at" to stringProperty("When: YYYY-MM-DDTHH:MM or HH:MM today (default now)"),
                "note" to stringProperty("A note"),
                required = listOf("kind"),
            ),
            writes = true,
            module = Module.TRANSITION,
        ) { caller, args ->
            val kind = args.string("kind")?.uppercase()?.let { runCatching { com.health.api.MomentKind.valueOf(it) }.getOrNull() } ?: error("Euphoria or dysphoria?")
            val at = args.string("at")?.let { instantOf(caller.user, it) } ?: services.clock()
            val saved = services.transition.saveMoment(caller.user.id, com.health.api.GenderMoment(at, kind, args.int("intensity") ?: 3, args.strings("context"), args.string("note")))
            "Logged a moment of ${saved.kind.label.lowercase()} (${saved.intensity} of 5) at ${formatTime(services.profiles.days(caller.user.id), saved.at)}."
        },
    )

    // ---- Supplies, bladder and bowel progress, conditions (modules/Supplies.kt, Continence.kt, conditions/) ----

    private fun diaryTools(): List<Tool> = listOf(
        Tool(
            "supplies", "Supplies",
            "Pads, briefs, bed pads and other bladder and bowel supplies on hand (and period products, if the link may see the cycle): " +
                "how many are left, how fast they're used, and which are running low.",
            schema(),
            module = Module.CONTINENCE,
        ) { caller, _ ->
            val categories = buildSet {
                add(com.health.api.SupplyCategory.CONTINENCE); add(com.health.api.SupplyCategory.OTHER)
                if (Module.CYCLE in caller.scope) add(com.health.api.SupplyCategory.PERIOD)
            }
            val list = services.supplies.status(caller.user.id).filter { it.supply.kind.category in categories }
            if (list.isEmpty()) "No supplies added." else buildString {
                list.forEach { s ->
                    val x = s.supply
                    appendLine(
                        "${x.name} (${x.kind.label.lowercase()}${x.absorbency?.let { ", $it" } ?: ""}): ${x.onHand} on hand" +
                            (s.perDay?.let { ", about %.1f used a day".format(it) } ?: "") + (s.daysLeft?.let { ", about $it days left" } ?: "") +
                            (if (s.low) " — running low" else "") + (if (x.reusable) " (reusable)" else ""),
                    )
                }
            }
        },
        Tool(
            "continence_progress", "Bladder and bowel progress",
            "Whether bladder and bowel symptoms are getting better or worse: the last weeks against the ones before (leaks, bowel " +
                "accidents, urgency, night-time trips, products used, how full they were, weighed loss, and how much it bothers the person), " +
                "week by week, with what went along with the change.",
            schema("weeks" to intProperty("How many weeks (default 12, at most 52)")),
            module = Module.CONTINENCE,
        ) { caller, args ->
            val p = services.modules.continence.progress(caller.user.id, args.int("weeks")?.coerceIn(2, 52) ?: 12)
            buildString {
                appendLine("${p.verdict.label}. ${p.headline}")
                p.metrics.forEach { m -> appendLine("${m.label}: ${m.before ?: "–"} before, ${m.after ?: "–"} in the last ${p.recentWeeks} weeks (${m.verdict.label.lowercase()}).") }
                p.notes.forEach { appendLine("Note: $it") }
                appendLine("week	days	trips	night	leaks	bowel_leaks	urgency	products	fullness	loss_g	bother	pelvic_floor_sets")
                p.weeks.forEach { w ->
                    appendLine(listOf(w.weekStart, w.days, w.voidsPerDay, w.nightVoidsPerDay, w.leaksPerDay, w.bowelLeaksPerDay, w.urgency, w.productsPerDay, w.fullness, w.lossGPerDay, w.bother, w.pelvicFloorSets).joinToString("\t") { it?.toString() ?: "" })
                }
            }
        },
        Tool(
            "conditions", "Conditions and goals",
            "The conditions and goals the person keeps track of (only those whose private features this link may see), with how long and any notes.",
            schema(),
        ) { caller, _ ->
            if (caller.everyone || caller.scope.isEmpty()) return@Tool "Conditions are private: this link wasn't given any private features."
            val visible = services.conditions.get(caller.user.id).conditions.filter { c ->
                com.health.server.conditions.ConditionsService.modulesOf(c).filter { it.sensitive }.all { it in caller.scope }
            }
            if (visible.isEmpty()) "None that this link may see." else visible.joinToString("\n") { c ->
                "${c.name}${c.status?.let { " (${it.label.lowercase()})" } ?: ""}${c.since?.let { ", since $it" } ?: ""}${c.notes?.let { ": $it" } ?: ""}"
            }
        },
    )

    // ---- Hormone therapy, life stages and appointments (care/) ----

    private fun careTools(): List<Tool> = listOf(
        Tool(
            "hormones", "Hormone therapy",
            "Hormone therapy of any kind: the medications marked as hormones, every change to their dose or schedule, " +
                "lab results against the person's own target ranges (from their clinician), and when labs are next due.",
            schema(),
            module = Module.HORMONES,
        ) { caller, _ -> com.health.server.care.CareText.hormones(services, caller.user) },
        Tool(
            "life_stage", "Life stage",
            "The person's life stage with their cycle (tracking a cycle, trying to conceive, pregnant, after a birth, perimenopause, " +
                "after menopause), with pregnancy week and due date, movement counts, contractions, feeds, months since the last period, and hot flashes.",
            schema(daysProperty(30, 365)),
            module = Module.CYCLE,
        ) { caller, args -> com.health.server.care.CareText.lifeStage(services, caller.user, args.int("days")?.coerceIn(1, 365) ?: 30) },
        Tool(
            "appointments", "Appointments",
            "Appointments coming up and before: when, with whom, where, the questions the person wants to ask (ticked when asked) and notes afterwards.",
            schema(daysProperty(90, 730)),
            module = Module.APPOINTMENTS,
        ) { caller, args -> com.health.server.care.CareText.appointments(services, caller.user, args.int("days")?.coerceIn(1, 730) ?: 90) },
        Tool(
            "log_hot_flash", "Log a hot flash",
            "Logs a hot flash: when (default now), how strong (1 mild, 2 moderate, 3 strong) and whether it was at night (a night sweat).",
            schema(
                "at" to stringProperty("When: an ISO time like 2026-03-20T14:30, or leave out for now (the person's time zone)"),
                "intensity" to intProperty("1 mild, 2 moderate, 3 strong (default 2)"),
                "night" to boolProperty("At night (a night sweat)"),
            ),
            writes = true,
            module = Module.CYCLE,
        ) { caller, args ->
            val days = services.profiles.days(caller.user.id)
            val at = args.string("at")?.let { text ->
                runCatching { java.time.LocalDateTime.parse(text).atZone(days.zone).toInstant().toEpochMilli() }.getOrElse { error("Times look like 2026-03-20T14:30") }
            } ?: services.clock()
            val saved = services.lifeStages.saveHotFlash(caller.user.id, com.health.api.HotFlash(at, args.int("intensity") ?: 2, args.bool("night") ?: (days.hourOf(at) !in 7..21)))
            "Logged a ${listOf("mild", "moderate", "strong")[saved.intensity - 1]} hot flash at ${formatTime(days, saved.at)}${if (saved.night) " (at night)" else ""}."
        },
    )

    // ---- Tools ----

    private suspend fun status(caller: Caller, user: User): String = buildString {
        val uptime = services.clock() - startedAt
        appendLine("SuperAwesomeMagicalHealth ${Services.version}, up ${span(uptime)}.")
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
            appendLine("- ${d.name} [${d.kind.label}${d.model?.let { ", $it" } ?: ""}]: last sync ${d.lastSyncAt?.let { ago(it) } ?: "never"}${d.battery?.let { ", battery $it%" } ?: ""}, ${count(d.samples)} readings")
        }
        val now = services.clock()
        val week = services.records.samples(user.id, now - 7 * DAY_MS, now + DAY_MS)
        appendLine()
        if (week.isEmpty()) {
            appendLine("No readings in the last 7 days.")
        } else {
            appendLine("Readings in the last 7 days:")
            week.groupBy { it.metric }.forEach { (metric, list) ->
                appendLine("- ${metric.label}: ${count(list.size.toLong())}, latest ${ago(list.maxOf { it.start })}")
            }
        }
        val sleep = services.records.sleep(user.id, now - 7 * DAY_MS, now + DAY_MS)
        val workouts = services.records.workouts(user.id, now - 7 * DAY_MS, now + DAY_MS)
        appendLine("Nights recorded in the last 7 days: ${sleep.size}. Workouts: ${workouts.size}.")
    }

    /** A day as text; without [water], nothing about water or how much was drunk. */
    internal fun formatDay(d: DaySummary, units: Units, scope: Set<Module>, water: Boolean = true): String = buildString {
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
        appendLine("Activity: %,d steps (goal %,d), %s, %d active kcal (goal %d)%s".format(d.steps, d.goals.steps, distance(d.distanceM, units), d.activeCalories.roundToInt(), d.goals.activeCalories, d.restingCalories?.let { ", resting energy ${it.roundToInt()} kcal" } ?: ""))
        val heart = listOfNotNull(
            d.restingHeartRate?.let { "resting ${it.roundToInt()} bpm" }, d.avgHeartRate?.let { "average ${it.roundToInt()}" },
            d.minHeartRate?.let { "min ${it.roundToInt()}" }, d.maxHeartRate?.let { "max ${it.roundToInt()}" },
            d.hrv?.let { "HRV ${it.roundToInt()} ms" }, d.spo2?.let { "SpO2 ${it.roundToInt()}%" },
            d.respiratoryRate?.let { "breathing %.1f/min".format(it) }, d.temperatureDeviation?.let { "temperature ${Scoring.signed(it)} °C" },
            d.stress?.let { "stress ${it.roundToInt()}" },
        )
        if (heart.isNotEmpty()) appendLine("Heart and body: ${heart.joinToString(", ")}")
        // Readings more than one device measured: the app's own number, each device's, and how.
        d.combined.forEach { c ->
            val names = com.health.server.insights.Combine.shortNamesOf(c.sources)
            val each = c.sources.joinToString(", ") { s -> "${names[s.deviceId]} ${combinedValue(c.metric, s.value, units, unit = false)}${if (s.weight == 0.0) " (left out)" else ""}" }
            appendLine("${c.metric.label} ${combinedValue(c.metric, c.value, units, unit = true)} ($each): ${c.how}")
        }
        d.sleepSummary?.let { appendLine("Sleep: ${nightText(it)}") }
        d.sleepSummary?.combined?.let { c ->
            appendLine("  Recorded by ${c.sources.joinToString(" and ") { "${it.device} (${Scoring.hm(it.asleepMinutes)} asleep${if (it.staged) ", with stages" else ""})" }}: ${c.how}")
        }
        d.naps.forEach { appendLine("Nap: ${nightText(it)}") }
        d.workouts.forEach { appendLine("Workout: ${workoutText(it, units)}") }
        val n = d.nutrition
        d.nutritionTarget?.let { t ->
            appendLine(
                "Food: %d of %d kcal; protein %d/%d g, carbs %d/%d g, fat %d/%d g".format(n.calories.roundToInt(), t.calories, n.protein.roundToInt(), t.protein, n.carbs.roundToInt(), t.carbs, n.fat.roundToInt(), t.fat) +
                    if (water) "; water %d/%d ml".format(d.waterMl, t.waterMl) else "",
            )
            appendLine("  Target: ${t.basis}")
        }
        d.weightKg?.let { appendLine("Weight: ${weight(it, units)}${d.bodyFat?.let { f -> ", body fat %.1f%%".format(f) } ?: ""}") }
        d.checkIn?.takeIf { it.mood != null || it.energy != null || it.stress != null || it.soreness != null || it.tags.isNotEmpty() || it.note != null }
            ?.let { appendLine("Check-in: ${checkInText(it)}") }
        val m = d.modules
        if (Module.MIND in scope) m.mind?.let { appendLine("Mood: ${it.mood?.let { v -> "%.1f/5".format(v) } ?: "not logged"} (${it.entries} entries)${it.anxiety?.let { a -> ", anxiety %.1f/5".format(a) } ?: ""}${if (it.mindfulMinutes > 0) ", ${num(it.mindfulMinutes)} min calm practice" else ""}") }
        if (Module.CYCLE in scope) m.cycle?.let { c ->
            appendLine("Cycle: ${c.cycleDay?.let { "day $it" } ?: "no recent period logged"}, ${c.phase.label.lowercase()}${c.daysUntilPeriod?.let { u -> if (u >= 0) ", period in about $u days" else ", period ${-u} days later than expected" } ?: ""}${c.logged?.let { l -> "; logged: " + cycleDayText(l) } ?: ""}")
        }
        if (Module.CONTINENCE in scope) m.continence?.let {
            val w = it.wearing
            val wearing = when {
                w?.supplyId != null -> "; wearing ${w.name ?: "a product"} for ${Wear.say(w.minutes ?: 0)}"
                w?.nothingOn == true -> "; nothing on for now"
                else -> ""
            }
            appendLine("Bladder and bowel: ${it.voids} toilet trips (${it.nightVoids} at night), ${it.leaks} leaks, ${it.bowel} bowel movements, ${it.pelvicFloor} pelvic floor sessions$wearing")
        }
        m.lowSupplies?.filter { if (it.supply.kind.category == com.health.api.SupplyCategory.PERIOD) Module.CYCLE in scope else Module.CONTINENCE in scope }
            ?.takeIf { it.isNotEmpty() }
            ?.let { low -> appendLine("Supplies running low: " + low.joinToString { "${it.supply.name} (${it.supply.onHand} left)" }) }
        if (Module.MEDICATIONS in scope) m.meds?.let { md ->
            appendLine("Medications: ${md.due.count { it.log?.status == DoseStatus.TAKEN }} of ${md.due.size} scheduled doses taken${md.adherence7d?.let { a -> " ($a% over 7 days)" } ?: ""}")
        }
        // Blood pressure and glucose, drinks, stress, habits, symptoms (tracking/).
        m.vitals?.let { v ->
            val parts = listOfNotNull(
                v.bloodPressure?.let { "blood pressure ${it.systolic}/${it.diastolic}${v.category?.let { c -> " (${c.label.lowercase()})" } ?: ""}" },
                v.glucose?.let { "glucose ${VitalsService.glucoseText(it.mmolL, v.targets.glucoseUnit)}" },
                v.timeInRange?.let { "$it% in range" },
            )
            if (parts.isNotEmpty()) appendLine("Vitals: ${parts.joinToString(", ")}")
        }
        d.drinks?.let { dr -> appendLine("Drinks: caffeine ${dr.caffeineMg.roundToInt()} mg${dr.caffeineAtBedtimeMg?.let { b -> " (about ${b.roundToInt()} mg left at bedtime)" } ?: ""}, ${DrinksService.drinksText(dr.standardDrinks)} of alcohol${if (water) ", ${dr.fluidMl} ml other drinks" else ""}") }
        d.stressDay?.let { s -> appendLine("Stress: ${s.summary.lowercase()} (stressed ${Scoring.hm(s.stressedMinutes)}, engaged ${Scoring.hm(s.engagedMinutes)}, relaxed ${Scoring.hm(s.relaxedMinutes)}, restored ${Scoring.hm(s.restoredMinutes)})") }
        d.resilience?.let { r -> appendLine("Resilience: ${r.level.label.lowercase()} (${r.trend}); ${r.contributors.joinToString("; ") { "${it.label} ${it.score} (${it.value})" }}") }
        m.habits?.let { list -> appendLine("Habits: ${list.joinToString("; ") { "${it.habit.name} ${if (it.done) "done" else "not yet"}, ${it.weekDone}/${it.habit.timesPerWeek} this week, ${it.streakWeeks}-week streak" }}") }
        if (Module.SYMPTOMS in scope) m.symptoms?.let { s ->
            appendLine("Symptoms: ${(s.entries.map { "${it.symptom} ${it.severity}/10" } + s.migraines.map { "migraine ${it.severity}/10${if (it.end == null) " (ongoing)" else ""}" }).joinToString(", ")}")
        }
        val shown = d.insights.filter { it.module == null || it.module in scope }
        if (shown.isNotEmpty()) {
            appendLine("Insights shown:")
            shown.forEach { appendLine("  - [${it.tone}] ${it.title}: ${it.body}") }
        }
    }

    /** Trend rows as a table; without [water], no water column. */
    internal fun trendsTable(rows: List<com.health.api.TrendDay>, scope: Set<Module>, water: Boolean = true): String = buildString {
        val extra = buildList {
            if (Module.MIND in scope) addAll(listOf("mood", "anxiety", "calm_min"))
            if (Module.CYCLE in scope) add("flow")
            if (Module.CONTINENCE in scope) addAll(listOf("voids", "night_voids", "leaks"))
            if (Module.MEDICATIONS in scope) addAll(listOf("doses_taken", "doses_due"))
        }
        // Blood pressure and glucose, drinks, stress and habits where there are any; symptoms where the link may see them.
        fun d1(v: Double?) = v?.let { String.format(Locale.ROOT, "%.1f", it) }.orEmpty()
        val tracked = listOf<Pair<String, (com.health.api.TrendDay) -> String>>(
            "systolic" to { r -> r.systolic?.roundToInt()?.toString().orEmpty() }, "diastolic" to { r -> r.diastolic?.roundToInt()?.toString().orEmpty() },
            "glucose_mmol" to { r -> d1(r.glucose) }, "caffeine_mg" to { r -> r.caffeineMg?.roundToInt()?.toString().orEmpty() },
            "alcohol_drinks" to { r -> d1(r.standardDrinks) }, "stressed_min" to { r -> r.stressedMinutes?.toString().orEmpty() },
            "restored_min" to { r -> r.restoredMinutes?.toString().orEmpty() }, "habits_done" to { r -> r.habitsDone?.toString().orEmpty() },
            "habits_due" to { r -> r.habitsDue?.toString().orEmpty() },
        ).filter { (_, value) -> rows.any { value(it).isNotEmpty() } } + if (Module.SYMPTOMS in scope) listOf<Pair<String, (com.health.api.TrendDay) -> String>>(
            "symptom_worst" to { r -> r.symptomWorst?.toString().orEmpty() }, "migraine" to { r -> if (r.migraine == true) "yes" else "" },
        ) else emptyList()
        val trackedHeader = tracked.joinToString("") { "\t${it.first}" }
        appendLine("date\treadiness\tsleep_score\tactivity_score\tsteps\tactive_kcal\tresting_hr\thrv_ms\tsleep_min\tdeep_min\trem_min\tspo2\ttemp_dev\tweight_kg\tkcal_eaten\tprotein_g\tcarbs_g\tfat_g\t${if (water) "water_ml\t" else ""}workouts" + extra.joinToString("") { "\t$it" } + trackedHeader)
        rows.forEach { r ->
            fun d(v: Double?, digits: Int = 0) = v?.let { if (digits == 0) it.roundToInt().toString() else String.format(Locale.ROOT, "%.${digits}f", it) } ?: ""
            appendLine(
                listOf(
                    r.date, r.readiness?.toString().orEmpty(), r.sleepScore?.toString().orEmpty(), r.activityScore?.toString().orEmpty(),
                    r.steps?.toString().orEmpty(), d(r.activeCalories), d(r.restingHeartRate, 1), d(r.hrv), r.sleepMinutes?.toString().orEmpty(),
                    r.deepMinutes?.toString().orEmpty(), r.remMinutes?.toString().orEmpty(), d(r.spo2, 1), d(r.temperatureDeviation, 2),
                    d(r.weightKg, 2), d(r.calories), d(r.protein), d(r.carbs), d(r.fat),
                ).plus(listOfNotNull(r.waterMl?.toString().orEmpty().takeIf { water })).plus(r.workouts.toString()).plus(
                    buildList {
                        if (Module.MIND in scope) addAll(listOf(d(r.mood, 1), d(r.anxiety, 1), d(r.mindfulMinutes)))
                        if (Module.CYCLE in scope) add(r.flow?.name?.lowercase().orEmpty())
                        if (Module.CONTINENCE in scope) addAll(listOf(r.voids?.toString().orEmpty(), r.nightVoids?.toString().orEmpty(), r.leaks?.toString().orEmpty()))
                        if (Module.MEDICATIONS in scope) addAll(listOf(r.dosesTaken?.toString().orEmpty(), r.dosesDue?.toString().orEmpty()))
                    },
                ).plus(tracked.map { (_, value) -> value(r) }).joinToString("\t"),
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
        val water = services.days.showsWater(user.id)
        val parts = mutableListOf<String>()
        for (back in count - 1 downTo 0) parts += foodDayText(user, last.minusDays(back.toLong()), water)
        return parts.joinToString("\n\n")
    }

    private suspend fun foodDayText(user: User, date: LocalDate, water: Boolean): String = buildString {
        val day = foodDay(services, user.id, date)
        appendLine("$date: %d of %d kcal, protein %d/%d g, carbs %d/%d g, fat %d/%d g".format(
            day.totals.calories.roundToInt(), day.target.calories, day.totals.protein.roundToInt(), day.target.protein,
            day.totals.carbs.roundToInt(), day.target.carbs, day.totals.fat.roundToInt(), day.target.fat,
        ) + if (water) ", water %d/%d ml".format(day.waterMl, day.target.waterMl) else "")
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
            appendLine("${count(list.size.toLong())} ${metric.label.lowercase()} readings (${metric.unit.ifBlank { "score" }})${if (shown.size < list.size) ", the latest ${shown.size} shown" else ""}:")
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
            appendLine("  added ${ago(d.createdAt)}, last sync ${d.lastSyncAt?.let { ago(it) } ?: "never"}, ${count(d.samples)} readings in all")
            val recent = services.records.samples(user.id, now - 30 * DAY_MS, now + DAY_MS).filter { it.deviceId == d.id }
            recent.groupBy { it.metric }.forEach { (metric, samples) ->
                val last = samples.maxBy { it.start }
                appendLine("  ${metric.label}: ${count(samples.size.toLong())} in 30 days, latest ${num(last.value)} ${metric.unit} ${ago(last.start)}")
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
        val water = services.days.showsWater(user.id)
        appendLine("Goals: %,d steps, %d active kcal, %s sleep, %d workouts a week%s%s%s".format(
            g.steps, g.activeCalories, Scoring.hm(g.sleepMinutes), g.workoutsPerWeek, if (water) ", water ${g.waterMl} ml" else "",
            g.targetWeightKg?.let { ", target weight ${weight(it, p.units)}" } ?: "",
            if (g.weeklyChangeKg != 0.0) ", aiming to ${if (g.weeklyChangeKg < 0) "lose" else "gain"} ${num(kotlin.math.abs(g.weeklyChangeKg))} kg a week" else "",
        ))
        val resting = Energy.restingCalories(p, weight?.value, today)
        appendLine("Resting energy: ${resting?.let { "${it.roundToInt()} kcal a day (Mifflin–St Jeor, ${p.energyProfile.label.lowercase()})" } ?: "unknown (needs birth year, height and a weight)"}")
        val (target, burned) = services.days.nutritionFor(user.id, today)
        appendLine("Today's targets: ${target.calories} kcal, protein ${target.protein} g, carbs ${target.carbs} g, fat ${target.fat} g${if (water) ", water ${target.waterMl} ml" else ""}")
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

    // ---- Health modules ----

    private suspend fun mindText(user: User, count: Int): String = buildString {
        val m = services.modules.mind
        val summary = m.summary(user.id, count)
        appendLine("Mood over the last $count days: average ${summary.averageMood?.let { "%.1f".format(it) } ?: "not logged"} of 5; calm practice ${num(summary.mindfulMinutes)} minutes.")
        summary.days.filter { it.mood != null }.takeLast(31).forEach { d ->
            appendLine("  ${d.date}: mood %.1f${d.anxiety?.let { a -> ", anxiety %.1f".format(a) } ?: ""}${d.energy?.let { e -> ", energy %.1f".format(e) } ?: ""}".format(d.mood))
        }
        if (summary.feelings.isNotEmpty()) appendLine("Feelings named most: ${summary.feelings.joinToString { "${it.name} (${it.count})" }}")
        if (summary.context.isNotEmpty()) appendLine("About: ${summary.context.joinToString { "${it.name} (${it.count})" }}")
        summary.patterns.forEach { appendLine("Pattern: ${it.title}. ${it.body}") }
        val notes = m.moods(user.id, count).filter { it.note != null }.take(15)
        if (notes.isNotEmpty()) {
            appendLine("Mood notes:")
            val days = services.profiles.days(user.id)
            notes.forEach { appendLine("  ${formatTime(days, it.at)} (${it.mood}/5): ${it.note}") }
        }
        val results = m.screenerResults(user.id, 365)
        if (results.isNotEmpty()) {
            appendLine("Check-ins:")
            results.take(10).forEach { appendLine("  ${it.date}: ${when (it.screener) { Screener.PHQ9 -> "PHQ-9"; Screener.GAD7 -> "GAD-7"; Screener.EPDS -> "EPDS (Edinburgh)" }} ${it.score} (${it.severity})${if (it.flagged) "; answered the self-harm question above its lowest answer" else ""}") }
        }
        val sessions = m.sessions(user.id, count)
        if (sessions.isNotEmpty()) appendLine("Calm practice: ${sessions.groupBy { it.kind }.entries.joinToString { (k, v) -> "$k ×${v.size}" }}")
        appendLine("Support lines on this server: ${m.resources().joinToString("; ") { "${it.name} (${it.region ?: "any"}): ${it.contact}" }}")
    }

    private suspend fun journalText(user: User, count: Int): String {
        val entries = services.modules.mind.journal(user.id, count)
        if (entries.isEmpty()) return "No journal entries in the last $count days."
        val days = services.profiles.days(user.id)
        return entries.joinToString("\n\n") { e ->
            buildString {
                append("${formatTime(days, e.at)} [${e.kind.label}]${e.title?.let { " $it" } ?: ""}")
                e.prompt?.let { append("\nPrompt: $it") }
                if (e.text.isNotBlank()) append("\n${e.text}")
                if (e.gratitude.isNotEmpty()) append("\nGrateful for: ${e.gratitude.joinToString("; ")}")
                e.thought?.let { t ->
                    append("\nSituation: ${t.situation}\nThought: ${t.thought}${t.beliefBefore?.let { b -> " ($b%)" } ?: ""}")
                    t.feelings?.let { append("\nFeelings: $it") }
                    t.evidenceFor?.let { append("\nEvidence for: $it") }
                    t.evidenceAgainst?.let { append("\nEvidence against: $it") }
                    t.balancedThought?.let { append("\nBalanced thought: $it${t.beliefAfter?.let { b -> " (belief in the first thought now $b%)" } ?: ""}") }
                }
            }
        }
    }

    private fun cycleDayText(d: CycleDay): String = listOfNotNull(
        d.flow?.label?.lowercase()?.let { "flow $it" }, d.symptoms.takeIf { it.isNotEmpty() }?.joinToString(", "),
        d.pain?.let { "pain $it/10" }, d.mucus?.label?.lowercase()?.let { "mucus $it" }, d.ovulationTest?.label?.lowercase()?.let { "ovulation test $it" },
        d.temperature?.let { "BBT %.2f °C".format(it) },
        d.products.takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.count} ${it.kind.label.lowercase()}${it.fullness?.let { f -> " (${f.label.lowercase()})" } ?: ""}" },
        d.clots?.takeIf { it != com.health.api.Clots.NONE }?.let { "clots ${it.label.lowercase()}" }, d.color?.let { "colour $it" },
        d.painAreas.takeIf { it.isNotEmpty() }?.let { "pain in ${it.joinToString(", ")}" },
        d.symptomSeverity.takeIf { it.isNotEmpty() }?.entries?.joinToString(", ") { "${it.key} ${listOf("", "mild", "moderate", "severe")[it.value.coerceIn(1, 3)]}" },
        d.mood.takeIf { it.isNotEmpty() }?.let { "mood ${it.joinToString(", ")}" }, d.energy?.let { "energy $it/5" }, d.libido?.let { "sex drive $it/5" },
        d.lh?.let { "LH $it" }, d.pregnancyTest?.label?.lowercase()?.let { "pregnancy test $it" }, d.discharge?.let { "discharge $it" },
        d.tags.takeIf { it.isNotEmpty() }?.joinToString(", "), d.note,
    ).joinToString("; ").ifEmpty { "nothing" }

    private suspend fun cycleText(user: User, months: Int): String = buildString {
        val o = services.modules.cycle.overview(user.id, months)
        appendLine(
            "Today: ${o.cycleDay?.let { "cycle day $it" } ?: "no recent period logged"}, phase ${o.phase.label.lowercase()}" +
                (o.daysUntilPeriod?.let { if (it >= 0) ", next period in about $it days" else ", period ${-it} days later than expected" } ?: "") + ".",
        )
        appendLine("Averages: cycle ${o.averageCycle?.let { "%.1f days".format(it) } ?: "unknown"}, period ${o.averagePeriod?.let { "%.1f days".format(it) } ?: "unknown"}${o.variation?.let { ", cycles vary by ±%.1f days".format(it) } ?: ""}.")
        val plain = services.modules.plainCycle?.invoke(user.id) == true
        o.prediction?.let { p ->
            appendLine(
                "Prediction (${p.confidence} confidence): next period ${p.nextPeriod} (between ${p.earliest} and ${p.latest})" +
                    (if (plain) "." else "; estimated fertile window ${p.fertileStart} to ${p.fertileEnd}, ovulation about ${p.ovulation}."),
            )
        }
        if (plain) appendLine("The person prefers cycle wording without the fertile window or ovulation: leave those out.")
        appendLine(o.caveat)
        if (o.cycles.isNotEmpty()) {
            appendLine("Cycles:")
            o.cycles.reversed().take(12).forEach { c -> appendLine("  from ${c.start}: ${c.length?.let { "$it days" } ?: "current"}, period ${c.periodLength} days${c.ovulation?.let { ", temperature rise suggests ovulation around $it" } ?: ""}") }
        }
        val logged = o.days.takeLast(60)
        if (logged.isNotEmpty()) {
            appendLine("Logged days:")
            logged.forEach { appendLine("  ${it.date}: ${cycleDayText(it)}") }
        }
    }

    private suspend fun continenceText(user: User, count: Int): String = buildString {
        val c = services.modules.continence
        val s = c.summary(user.id, count)
        appendLine("Over $count days: ${s.averageVoids?.let { "%.1f toilet trips a day".format(it) } ?: "no trips logged"}, ${s.averageNightVoids?.let { "%.1f at night".format(it) } ?: "-"}, ${s.averageLeaks?.let { "%.1f leaks a day".format(it) } ?: "-"}${s.averageInterval?.let { "; longest daytime gap averages ${Scoring.hm(it)}" } ?: ""}.")
        val days = services.profiles.days(user.id)
        val w = c.wearing(user.id)
        when {
            w.supplyId != null -> appendLine(
                "Wearing now: ${w.name ?: "a product"}${w.kind?.let { " (${Wear.kindWord(it)})" } ?: ""}, on since ${formatTime(days, w.since!!)} " +
                    "(${Wear.say(w.minutes ?: 0)})" + (w.typicalMinutes?.let { "; usually changed after about ${Wear.say(Wear.rough(it))}" } ?: "") + ".",
            )
            w.nothingOn -> appendLine("Wearing now: nothing, since ${formatTime(days, w.since!!)} (${Wear.say(w.minutes ?: 0)}).")
        }
        val products = c.products(user.id, 90)
        if (products.isNotEmpty()) {
            appendLine("Products over 90 days:")
            products.forEach { p ->
                appendLine("  ${Wear.describe(p)} (${p.changes} ${if (p.changes == 1) "change" else "changes"}${p.avgWornMinutes?.let { ", worn ${Wear.say(it)} on average" } ?: ""}, ${(p.nightShare * 100).roundToInt()}% at night)")
            }
        }
        if (s.triggers.isNotEmpty()) appendLine("Leak triggers: ${s.triggers.joinToString { "${it.name} (${it.count})" }}")
        s.patterns.forEach { appendLine("Pattern: ${it.title}. ${it.body}") }
        // What was drunk comes from water tracking: no column while it's turned off.
        val water = services.days.showsWater(user.id)
        appendLine("date\ttrips\tnight\tleaks\tbowel\tpads\tpelvic_floor\tvoided_ml\t${if (water) "fluid_ml\t" else ""}urgent\tbowel_leaks\twet_nights\tproducts\tleaked_through\tcatheters\tloss_g")
        s.days.forEach { d -> appendLine("${d.date}\t${d.voids}\t${d.nightVoids}\t${d.leaks}\t${d.bowel}\t${d.pads}\t${d.pelvicFloor}\t${d.voidedMl ?: ""}\t${if (water) "${d.fluidMl}\t" else ""}${d.urgentTrips}\t${d.bowelLeaks}\t${d.wetNights}\t${d.products}\t${d.leakedThrough}\t${d.catheters}\t${d.lossG ?: ""}") }
        val entries = c.list(user.id, count).reversed()
        val names = if (entries.any { it.supplyId != null || it.putOnSupplyId != null }) services.supplies.list(user.id, all = true).associate { it.id to it.name } else emptyMap()
        if (entries.isNotEmpty()) {
            appendLine("Diary:")
            entries.forEach { e ->
                appendLine("  ${formatTime(days, e.at)}${if (e.night) " (night)" else ""}: ${e.kind.label}" + listOfNotNull(
                    e.volumeMl?.let { "$it ml" }, e.urgency?.let { "urgency $it/3" }, e.leak?.label?.lowercase()?.let { "leak: $it" },
                    e.triggers.takeIf { it.isNotEmpty() }?.joinToString(", "), e.bristol?.let { "Bristol type $it" },
                    "pain".takeIf { e.pain }, "blood".takeIf { e.blood }, e.reps?.let { "$it reps" }, e.holdSeconds?.let { "${it}s holds" },
                    e.contents?.label?.lowercase(), e.activity.takeIf { it.isNotEmpty() }?.let { "while ${it.joinToString(", ")}" },
                    e.awareness?.label?.lowercase(), e.reachedToilet?.label?.lowercase(), e.warningSeconds?.let { "${it}s warning" },
                    e.location?.let { "at $it" },
                    e.supplyId?.takeIf { e.kind == ContinenceKind.PAD_CHANGE }?.let { "took off ${names[it] ?: "a product"}" },
                    e.putOnSupplyId?.let { "put on ${names[it] ?: "a product"}" }, "nothing on for now".takeIf { e.nothingOn },
                    e.wornMinutes?.let { "worn ${Wear.say(it)}" },
                    e.fullness?.label?.lowercase()?.let { "product $it" }, e.lossG?.let { "lost ${it} g" },
                    e.count?.let { "$it used" }, "leaked through".takeIf { e.leakedThrough }, "changed clothes".takeIf { e.changedClothes },
                    "changed bedding".takeIf { e.changedBedding }, e.skin?.takeIf { it > 0 }?.let { "skin soreness $it/3" },
                    e.utiSigns.takeIf { it.isNotEmpty() }?.let { "infection signs: ${it.joinToString(", ")}" },
                    e.tags.takeIf { it.isNotEmpty() }?.joinToString(", "), e.note,
                ).joinToString(", ", ", ").ifEmpty { "" }.let { if (it == ", ") "" else it })
            }
        }
    }

    private suspend fun medsText(user: User, dateText: String): String = buildString {
        val meds = services.modules.meds
        val days = services.profiles.days(user.id)
        val date = days.parse(dateText, services.clock())
        val list = meds.list(user.id, all = true)
        if (list.isEmpty()) appendLine("No medications added.")
        list.forEach { m ->
            val s = m.schedule
            val schedule = when (s.kind) {
                com.health.api.ScheduleKind.DAILY -> "daily at ${s.times.joinToString()}"
                com.health.api.ScheduleKind.WEEKLY -> "weekly on ${s.weekdays.joinToString { java.time.DayOfWeek.of(it).name.lowercase().replaceFirstChar(Char::uppercase) }} at ${s.times.joinToString()}"
                com.health.api.ScheduleKind.EVERY_N_DAYS -> "every ${s.everyDays} days from ${s.startDate ?: "the start"} at ${s.times.joinToString()}"
                com.health.api.ScheduleKind.AS_NEEDED -> "as needed"
            }
            appendLine("- ${m.name}${m.dose?.let { " $it" } ?: ""} (${m.form.label.lowercase()}), $schedule${m.purpose?.let { ", for $it" } ?: ""}${if (!m.active) " [stopped]" else ""}${m.stock?.let { ", $it left" } ?: ""}${m.sites.takeIf { it.isNotEmpty() }?.let { "; sites: ${it.joinToString()}" } ?: ""}")
        }
        val day = meds.day(user.id, date)
        if (day.due.isNotEmpty()) {
            appendLine("Doses on $date:")
            day.due.forEach { d ->
                val state = d.log?.let { "${it.status.name.lowercase()} at ${formatTime(days, it.at).substringAfter(' ')}${it.site?.let { s -> ", $s" } ?: ""}" }
                    ?: if (d.scheduledFor < services.clock()) "not marked yet" else "due"
                appendLine("  ${formatTime(days, d.scheduledFor).substringAfter(' ')} ${d.name}: $state${if (d.log == null) d.nextSite?.let { ", next site $it" } ?: "" else ""}")
            }
        }
        day.extra.forEach { e -> appendLine("  extra: ${list.firstOrNull { it.id == e.medicationId }?.name ?: "?"} at ${formatTime(days, e.at)}") }
        day.adherence7d?.let { appendLine("Taken $it% of scheduled doses over the last 7 days.") }
        day.lowStock.forEach { appendLine("Running low: ${it.name} (${it.stock} left)") }
        val labs = meds.labs(user.id, 3650)
        if (labs.isNotEmpty()) {
            appendLine("Lab results:")
            labs.groupBy { it.name }.forEach { (name, results) ->
                appendLine("  $name: " + results.take(8).joinToString("; ") { r -> "${r.date} ${num(r.value)} ${r.unit}${if (r.low != null || r.high != null) " (range ${r.low?.let(::num) ?: "?"}–${r.high?.let(::num) ?: "?"})" else ""}" })
            }
        }
    }

    // ---- Helpers ----

    private val startedAt = services.clock()

    // ---- Blood pressure and glucose, drinks, stress, habits, symptoms (tracking/) ----

    private suspend fun vitalsText(user: User, count: Int): String = buildString {
        val s = services.tracking.vitals.summary(user.id, count)
        val days = services.profiles.days(user.id)
        val unit = s.targets.glucoseUnit
        if (s.bloodPressure.isEmpty() && s.glucose.isEmpty()) return "No blood pressure or glucose readings in the last $count days."
        appendLine("Targets: blood pressure under ${s.targets.systolicHigh}/${s.targets.diastolicHigh}; glucose ${VitalsService.glucoseText(s.targets.glucoseLow, unit)} to ${VitalsService.glucoseText(s.targets.glucoseHigh, unit)}.")
        if (s.bloodPressure.isNotEmpty()) {
            appendLine("Blood pressure: ${s.bloodPressure.size} readings, average ${s.systolicAverage?.roundToInt()}/${s.diastolicAverage?.roundToInt()} (${s.category?.label?.lowercase()}).")
            s.bloodPressure.take(60).forEach { r ->
                appendLine("- ${formatTime(days, r.at)}: ${r.systolic}/${r.diastolic} (${VitalsService.category(r.systolic, r.diastolic).label.lowercase()})${r.pulse?.let { ", pulse $it" } ?: ""}${r.arm?.let { ", $it arm" } ?: ""}${r.note?.let { "; $it" } ?: ""} [${r.source}]")
            }
        }
        if (s.glucose.isNotEmpty()) {
            appendLine("Glucose: ${s.glucose.size} readings, average ${VitalsService.glucoseText(s.glucoseAverage!!, unit)}; in range ${s.timeInRange}%, below ${s.timeBelow}%, above ${s.timeAbove}%" +
                (s.fastingAverage?.let { "; fasting average ${VitalsService.glucoseText(it, unit)}" } ?: "") + (s.gmi?.let { "; GMI $it%" } ?: "") + ".")
            s.glucose.take(80).forEach { r -> appendLine("- ${formatTime(days, r.at)}: ${VitalsService.glucoseText(r.mmolL, unit)} (${r.context.label.lowercase()})${r.note?.let { "; $it" } ?: ""} [${r.source}]") }
        }
        s.patterns.forEach { appendLine("Pattern: ${it.title}: ${it.body}") }
    }

    private suspend fun drinksText(user: User, count: Int): String = buildString {
        val s = services.tracking.drinks.summary(user.id, count)
        val days = services.profiles.days(user.id)
        // Water turned off: no glasses of water, and nothing about how much was drunk.
        val water = services.days.showsWater(user.id)
        val logged = s.days.map { d -> if (water) d else d.copy(drinks = d.drinks.filter { it.kind != DrinkKind.WATER }) }.filter { it.drinks.isNotEmpty() }
        if (logged.isEmpty()) return "No drinks logged in the last $count days."
        appendLine("Averages on days with drinks logged: caffeine ${s.averageCaffeineMg?.roundToInt() ?: 0} mg, alcohol ${s.averageStandardDrinks ?: 0.0} standard drinks (14 g).")
        logged.reversed().forEach { d ->
            appendLine("- ${d.date}: caffeine ${d.caffeineMg.roundToInt()} mg${d.lastCaffeineAt?.let { " (last at ${formatTime(days, it).substringAfter(' ')})" } ?: ""}, ${DrinksService.drinksText(d.standardDrinks)} of alcohol${if (water) ", ${d.fluidMl} ml other drinks" else ""}: " +
                d.drinks.joinToString(", ") { "${it.name ?: it.kind.label.lowercase()} ${it.volumeMl} ml" })
        }
        s.patterns.forEach { appendLine("Pattern: ${it.title}: ${it.body}") }
    }

    private suspend fun stressText(user: User, count: Int): String = buildString {
        val rows = services.days.trends(user.id, null, null, count).days.filter { it.stressedMinutes != null }
        val today = services.days.summary(user.id, "today")
        if (rows.isEmpty()) return "Not enough daytime heart rate (or stress readings) to work out stress in the last $count days. It needs a ring or watch worn during the day."
        appendLine("date\tstressed_min\trestored_min")
        rows.forEach { appendLine("${it.date}\t${it.stressedMinutes}\t${it.restoredMinutes}") }
        today.stressDay?.let { s -> appendLine("Today: ${s.summary.lowercase()}; stressed ${Scoring.hm(s.stressedMinutes)}, engaged ${Scoring.hm(s.engagedMinutes)}, relaxed ${Scoring.hm(s.relaxedMinutes)}, restored ${Scoring.hm(s.restoredMinutes)}.") }
        today.resilience?.let { r -> appendLine("Resilience: ${r.level.label.lowercase()} (${r.trend}). ${r.contributors.joinToString("; ") { "${it.label}: ${it.score} (${it.value})" }}.") }
            ?: appendLine("Resilience needs a week or more of daytime stress first.")
    }

    private suspend fun habitsText(user: User, dateText: String): String = buildString {
        val date = services.profiles.days(user.id).parse(dateText, services.clock())
        val list = services.tracking.habits.statuses(user.id, date)
        if (list.isEmpty()) return "No habits set up."
        appendLine("Habits on $date (the last 14 days oldest first: x done, . missed):")
        list.forEach { s ->
            val h = s.habit
            appendLine(
                "- ${h.name} (${h.source.label.lowercase()}, aim ${h.timesPerWeek} days a week${if (h.perDay > 1) ", ${h.perDay} times a day" else ""}): " +
                    "${if (s.done) "done" else "not yet"}${if (h.perDay > 1) " (${s.count}/${h.perDay})" else ""}; ${s.weekDone} this week; " +
                    "${s.streakWeeks}-week streak, ${s.streakDays}-day streak; ${s.recent.joinToString("") { when (it) { true -> "x"; false -> "."; null -> " " } }}",
            )
        }
    }

    private suspend fun symptomsText(user: User, count: Int): String = buildString {
        val s = services.tracking.symptoms.summary(user.id, count)
        val days = services.profiles.days(user.id)
        val logged = s.days.filter { it.entries.isNotEmpty() || it.migraines.isNotEmpty() }
        if (logged.isEmpty()) return "No symptoms or migraines logged in the last $count days."
        appendLine("Symptom days: ${logged.size} of $count; migraine days: ${s.migraineDays}.")
        if (s.counts.isNotEmpty()) appendLine("Most logged: ${s.counts.take(8).joinToString { "${it.name} (${it.count})" }}.")
        if (s.triggers.isNotEmpty()) appendLine("Triggers noted: ${s.triggers.take(8).joinToString { "${it.name} (${it.count})" }}.")
        if (s.correlations.isNotEmpty()) {
            appendLine("What went with symptom days${if (s.weather) " (air pressure included)" else ""}:")
            s.correlations.forEach { appendLine("- ${it.text}") }
        }
        logged.reversed().forEach { d ->
            d.migraines.forEach { m ->
                appendLine("- Migraine ${formatTime(days, m.start)}${m.end?.let { " to ${formatTime(days, it)}" } ?: " (ongoing)"}: ${m.severity}/10${if (m.aura) ", aura" else ""}" +
                    (if (m.symptoms.isNotEmpty()) ", ${m.symptoms.joinToString()}" else "") + (if (m.triggers.isNotEmpty()) "; triggers ${m.triggers.joinToString()}" else "") +
                    (if (m.medications.isNotEmpty()) "; took ${m.medications.joinToString()}" else "") + (m.reliefHelped?.let { "; helped $it/3" } ?: "") + (m.note?.let { "; $it" } ?: ""))
            }
            d.entries.reversed().forEach { e ->
                appendLine("- ${formatTime(days, e.at)}: ${e.symptom} ${e.severity}/10" + (if (e.bodyAreas.isNotEmpty()) " (${e.bodyAreas.joinToString()})" else "") +
                    (if (e.triggers.isNotEmpty()) "; triggers ${e.triggers.joinToString()}" else "") + (if (e.relief.isNotEmpty()) "; tried ${e.relief.joinToString()}" else "") +
                    (e.reliefHelped?.let { "; helped $it/3" } ?: "") + (e.note?.let { "; $it" } ?: ""))
            }
        }
    }

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

    /** A combined reading's value as text: "53", "48 ms", "7,912". */
    private fun combinedValue(metric: Metric, v: Double, units: Units, unit: Boolean): String = when (metric) {
        Metric.STEPS, Metric.FLOORS -> String.format(Locale.ROOT, "%,d", v.roundToInt())
        Metric.DISTANCE -> distance(v, units).let { if (unit) it else it.substringBefore(' ') }
        Metric.RESPIRATORY_RATE, Metric.SKIN_TEMPERATURE -> String.format(Locale.ROOT, "%.1f", v) + if (unit) " ${metric.unit}" else ""
        Metric.TEMPERATURE_DEVIATION -> Scoring.signed(v) + if (unit) " °C" else ""
        Metric.HEART_RATE, Metric.RESTING_HEART_RATE, Metric.STRESS -> v.roundToInt().toString()
        else -> v.roundToInt().toString() + if (unit && metric.unit.isNotEmpty()) (if (metric.unit == "%") "%" else " ${metric.unit}") else ""
    }

    private fun distance(m: Double, units: Units) =
        if (units == Units.IMPERIAL) String.format(Locale.ROOT, "%.2f mi", m / 1609.344) else String.format(Locale.ROOT, "%.2f km", m / 1000)

    private fun weight(kg: Double, units: Units) =
        if (units == Units.IMPERIAL) String.format(Locale.ROOT, "%.1f lb (%.1f kg)", kg / 0.45359237, kg) else String.format(Locale.ROOT, "%.1f kg", kg)

    private fun count(n: Long) = String.format(Locale.ROOT, "%,d", n)

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
        private fun stringArray(description: String) = buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
            put("description", description)
        }
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
        private fun JsonObject.strings(name: String) = (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()
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
