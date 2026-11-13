package com.health.server

import com.health.api.Drink
import com.health.api.DrinkKind
import com.health.api.Fields
import com.health.api.FoodEntryInput
import com.health.api.Goals
import com.health.api.Habit
import com.health.api.HabitSource
import com.health.api.HabitStatus
import com.health.api.Meal
import com.health.api.Module
import com.health.api.NutritionTarget
import com.health.api.Nutrients
import com.health.api.Profile
import com.health.api.Score
import com.health.api.WaterEntry
import com.health.server.insights.DayStats
import com.health.server.insights.InsightRules
import com.health.server.tracking.HabitsHooks
import com.health.server.tracking.HabitsService
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipInputStream

/**
 * Water turned off (`nutrition.water` hidden): what's logged stays stored, but nothing the server
 * says talks about it; turned back on, it all returns.
 */
class WaterOffTest {
    // A fake Anthropic, for the assistant.
    private val anthropic = MockWebServer()
    private val replies = ConcurrentLinkedQueue<String>()
    private val requests = ConcurrentLinkedQueue<JsonObject>()
    private var n = 0

    @Before
    fun start() {
        anthropic.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                return MockResponse().setHeader("Content-Type", "application/json").setBody(replies.poll() ?: message("end_turn", """{"type":"text","text":"Done."}"""))
            }
        }
        anthropic.start()
    }

    @After
    fun stop() = anthropic.shutdown()

    private fun message(stop: String, vararg content: String) =
        """{"id":"msg_${n++}","type":"message","role":"assistant","model":"claude-test-model","content":[${content.joinToString(",")}],""" +
            """"stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}"""

    /** Thursday 12 November 2026, 18:30 UTC: late enough for the water nudge and the evening's habits. */
    private val now = Instant.parse("2026-11-12T18:30:00Z").toEpochMilli()
    private val monday = LocalDate.parse("2026-11-09")
    private val today = LocalDate.parse("2026-11-12")

    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    private suspend fun HttpClient.rpc(url: String, method: String, params: String = "{}"): JsonObject =
        post(url) { json("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""") }.obj()

    private suspend fun HttpClient.call(url: String, tool: String, arguments: String = "{}"): JsonObject =
        rpc(url, "tools/call", """{"name":"$tool","arguments":$arguments}""")

    private fun JsonObject.text() = this["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject.str("text")
    private fun JsonObject.failed() = this["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean

    private fun says(text: String) = text.lowercase().contains("water")

    @Test
    fun `everything about water goes when it's turned off, and comes back when it's on`() = serverTest(testServices(clock = { now })) { s ->
        val userId = s.users.byUsername("admin")!!.id
        // A 10 litre goal, so today stays under half of it (and the nudge stays due) whatever the checks log.
        s.profiles.put(userId, Profile(timezone = "UTC", modules = Module.DEFAULT + Module.HABITS, goals = Goals(waterMl = 10_000)))
        // The whole goal on Monday, some on Tuesday and today, lunch on Tuesday, a coffee this morning.
        for ((day, ml) in listOf("2026-11-09" to 5000, "2026-11-09" to 5000, "2026-11-10" to 1800, "2026-11-12" to 300)) {
            s.foods.addWater(userId, WaterEntry(day, ml, at = at("${day}T09:00:00Z")))
            s.days.changedDate(userId, LocalDate.parse(day))
        }
        s.foods.log(userId, FoodEntryInput("2026-11-10", Meal.LUNCH, name = "Soup", nutrients = Nutrients(calories = 400.0, protein = 20.0)))
        s.days.changedDate(userId, LocalDate.parse("2026-11-10"))
        s.tracking.drinks.save(userId, Drink(at("2026-11-12T08:00:00Z"), DrinkKind.COFFEE, 240, caffeineMg = 96.0))

        val admin = adminBrowser()
        val client = createClient { }
        // A water-goal habit with a reminder at six, made while water is on.
        val habit = admin.postJson("/api/habits", """{"name":"Fill my bottle","source":"WATER_GOAL","reminderTime":"18:00"}""")
        assertEquals(HttpStatusCode.OK, habit.status)
        val habitId = habit.obj().str("id")
        val watch = admin.postJson("/api/watch/keys", "{}").obj().str("token")
        val ha = admin.postJson("/api/integrations/keys", """{"name":"Home Assistant"}""").obj().str("token")
        val mcp = admin.postJson("/api/me/connectors", """{"allowLogging":true}""").obj().str("url").substringAfter("localhost")
        // A digest kept from this week while water was on.
        s.platform.digests.store(userId, s.platform.digests.build(userId, s.profiles.get(userId), monday))
        // The assistant, allowed food and logging.
        s.ai.baseUrl = anthropic.url("/").toString().trimEnd('/')
        s.ai.maxRetries = 0
        assertEquals(HttpStatusCode.OK, admin.putJson("/api/admin/ai/key", """{"apiKey":"sk-ant-test-key-0123456789"}""").status)
        assertEquals(HttpStatusCode.OK, admin.putJson("/api/admin/ai", """{"enabled":true,"acknowledgedAt":1,"model":"claude-test-model","effort":"medium","dailyLimit":40,"allowLogging":true}""").status)
        assertEquals(HttpStatusCode.OK, admin.putJson("/api/ai/consent", """{"modules":["NUTRITION"],"acknowledged":true,"sensitiveAcknowledged":false,"allowLogging":true}""").status)

        suspend fun turnWater(on: Boolean) {
            val layout = admin.putJson("/api/fields", if (on) """{"hide":[]}""" else """{"hide":["${Fields.NUTRITION_WATER}"]}""").obj()
            assertEquals(on, layout["effective"]!!.jsonArray.any { it.jsonPrimitive.content == Fields.NUTRITION_WATER })
            assertEquals(on, s.days.showsWater(userId))
        }

        suspend fun check(on: Boolean) {
            val what = if (on) "with water on" else "with water off"

            // Today: the stored amount stays (the apps decide), but no insight mentions water.
            val day = s.days.summary(userId, "today")
            assertTrue(what, day.waterMl >= 300)
            assertEquals(what, on, day.insights.any { it.title == "Time for some water" })
            assertEquals(what, on, day.insights.any { says(it.title + " " + it.body) })
            // The water-goal habit isn't "left today" while water is off.
            assertEquals(what, on, day.insights.any { it.body.contains("Fill my bottle") })
            assertEquals(what, habitId, day.modules.habits!!.single().habit.id)

            // The digest, worked out now and kept from before (drinks keep their own section, about caffeine).
            for (digest in listOf(s.platform.digests.build(userId, s.profiles.get(userId), monday), s.platform.digests.latest(userId)!!)) {
                val food = digest.sections.single { it.module == Module.NUTRITION && it.title.startsWith("Food") }
                assertEquals(what, if (on) "Food and water" else "Food", food.title)
                assertTrue(what, food.lines.any { it.startsWith("Food logged on 1 of 7 days") })
                assertEquals(what, on, digest.sections.any { section -> says(section.title) || section.lines.any { says(it) } })
                assertTrue(what, digest.sections.any { it.title == "Drinks" })
            }

            // The report: no water line or column (food and drinks still there).
            val report = s.hooks.filter { it.module == Module.NUTRITION }.mapNotNull { it.report(userId, s.profiles.get(userId), monday, today) }
            assertEquals(what, 2, report.size)
            val words = report.flatMap { r -> r.summary + r.tables.flatMap { t -> listOf(t.title) + t.header } }
            assertEquals(what, on, words.any { says(it) || it == "Other drinks (ml)" })
            assertTrue(what, words.any { it == "Caffeine (mg)" })
            report.flatMap { it.tables }.forEach { t -> assertTrue(what, t.rows.all { it.size == t.header.size }) }

            // The export: water's column and file only while it's on.
            val zip = admin.get("/api/export.zip?from=$monday&to=$today").bodyAsBytes()
            val files = mutableMapOf<String, String>()
            ZipInputStream(ByteArrayInputStream(zip)).use { z -> generateSequence { z.nextEntry }.forEach { files[it.name] = String(z.readBytes()) } }
            assertEquals(what, on, files["daily.csv"]!!.lines().first().split(",").contains("water_ml"))
            assertEquals(what, on, "water.csv" in files)
            assertTrue(what, files["food.csv"]!!.contains("Soup"))
            val daily = files["daily.csv"]!!.lines().filter { it.isNotBlank() }.map { it.split(",").size }
            assertEquals(what, 1, daily.distinct().size)

            // The watch: no water figures, no water tap.
            val w = client.get("/api/watch/today") { bearerAuth(watch) }.obj()
            assertEquals(what, on, w["water"]!!.jsonPrimitive.boolean)
            assertEquals(what, if (on) day.waterMl else 0, w["waterMl"]!!.jsonPrimitive.int)
            assertEquals(what, if (on) 10_000 else 0, w["waterGoal"]!!.jsonPrimitive.int)
            val tap = client.post("/api/watch/log") { bearerAuth(watch); json("""{"kind":"WATER","value":250}""") }
            assertEquals(what, if (on) HttpStatusCode.OK else HttpStatusCode.BadRequest, tap.status)
            if (!on) assertTrue(tap.obj().str("error").contains("turned off"))
            assertEquals(what, HttpStatusCode.OK, client.post("/api/watch/log") { bearerAuth(watch); json("""{"kind":"COFFEE"}""") }.status)

            // Habits: a new water goal is offered only while water is on; the kept one can still be edited, and only nags while it's on.
            val another = admin.postJson("/api/habits", """{"name":"Two litres","source":"WATER_GOAL"}""")
            assertEquals(what, if (on) HttpStatusCode.OK else HttpStatusCode.BadRequest, another.status)
            if (on) admin.delete("/api/habits/${another.obj().str("id")}") { csrf() } else assertEquals(HabitsService.WATER_OFF, another.obj().str("error"))
            assertEquals(what, HttpStatusCode.OK, admin.putJson("/api/habits/$habitId", """{"name":"Fill my bottle","source":"WATER_GOAL","reminderTime":"18:00"}""").status)
            val hooks = s.hooks.filterIsInstance<HabitsHooks>().single()
            val reminders = hooks.reminders(userId, s.profiles.get(userId), now - 3_600_000L, now + 60_000L)
            assertEquals(what, if (on) listOf("Fill my bottle") else emptyList<String>(), reminders.map { it.title })
            // It still counts from what's logged: Monday reached the goal.
            val kept = s.tracking.habits.kept(userId)
            assertTrue(what, s.tracking.habits.doneDays(userId, s.profiles.get(userId), s.profiles.days(userId), kept, monday, today)[habitId]!![monday]!!.second)

            // Home Assistant: no water keys.
            val state = client.get("/api/integrations/state") { bearerAuth(ha) }.obj()
            assertEquals(what, on, "water_ml" in state)
            assertEquals(what, on, "water_goal" in state)
            assertTrue(what, "steps" in state)

            // The AI connector: log_water only while it's on, a WATER drink refused kindly, and its text leaves water out.
            val tools = client.rpc(mcp, "tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject.str("name") }
            assertEquals(what, on, "log_water" in tools)
            assertTrue(what, "log_drink" in tools)
            val hello = client.rpc(mcp, "initialize", """{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""")
            assertEquals(what, on, hello["result"]!!.jsonObject.str("instructions").contains("log food, water, weight"))
            val logWater = client.call(mcp, "log_water", """{"ml":250}""")
            if (on) {
                assertFalse(what, logWater.failed())
            } else {
                assertTrue(what, logWater["error"]!!.jsonObject.str("message").contains("turned water off"))
            }
            val waterDrink = client.call(mcp, "log_drink", """{"kind":"WATER","ml":250}""")
            assertEquals(what, !on, waterDrink.failed())
            if (!on) assertTrue(waterDrink.text().contains("turned water off"))
            assertFalse(what, client.call(mcp, "log_drink", """{"kind":"TEA"}""").failed())
            for (tool in listOf("day", "food_log", "trends", "profile", "drinks")) {
                val text = client.call(mcp, tool, if (tool == "food_log") """{"date":"2026-11-10"}""" else "{}").text()
                assertEquals("$tool $what: $text", on, says(text))
            }

            // The assistant: log_water offered only while it's on, and its look at the day leaves water out.
            requests.clear()
            replies += message("tool_use", """{"type":"tool_use","id":"toolu_1","name":"day","input":{}}""")
            replies += message("end_turn", """{"type":"text","text":"Here's your day."}""")
            assertEquals(what, HttpStatusCode.OK, admin.postJson("/api/ai/chat", """{"messages":[{"role":"user","text":"How's my day?"}]}""").status)
            val asked = requests.toList()
            assertEquals(what, on, asked.first()["tools"]!!.jsonArray.any { it.jsonObject.str("name") == "log_water" })
            val result = asked[1]["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray.single().jsonObject
            assertEquals(what, on, says(result["content"].toString()))
        }

        check(on = true)
        turnWater(on = false)
        check(on = false)
        turnWater(on = true)
        check(on = true)
        // What was logged is still there.
        assertTrue(s.foods.day(userId, today).second.isNotEmpty())
    }

    @Test
    fun `insight wording leaves water out when it's off`() {
        val weights = (1..10).map { DayStats(date = "2026-11-%02d".format(it), weightKg = 70.0 + it * 0.2) }
        fun context(water: Boolean) = InsightRules.Context(
            today = DayStats(date = "2026-11-12", weightKg = 72.5, waterMl = 200, hasData = true),
            history = weights, profile = Profile(timezone = "UTC"),
            target = NutritionTarget(2000, 100, 250, 60, 2000, "Your goal"),
            readiness = Score(52, "Pay attention", emptyList()), sleep = null,
            isToday = true, hourNow = 17, restingCalories = null, water = water,
        )
        val on = InsightRules.insights(context(water = true))
        assertTrue(on.any { it.title == "Take it easy today" && it.body.contains("water") })
        assertTrue(on.any { it.title == "Time for some water" })
        assertTrue(on.any { it.icon == "scale" && it.body.contains("water") })
        val off = InsightRules.insights(context(water = false))
        assertTrue(off.any { it.title == "Take it easy today" })
        assertTrue(off.any { it.icon == "scale" })
        assertTrue(off.none { says(it.title + " " + it.body) })
    }

    @Test
    fun `a water goal left undone isn't counted as left while water is off`() {
        fun status(name: String, source: HabitSource) = HabitStatus(Habit(name, source = source, id = name), "2026-11-12", 0, false, 0, 0, 0)
        val statuses = listOf(status("Stretch", HabitSource.MANUAL), status("Fill my bottle", HabitSource.WATER_GOAL))
        val service = testServices().tracking.habits
        assertEquals("Stretch, Fill my bottle", service.insights(statuses, isToday = true, hourNow = 19).single().second.body)
        assertEquals("Stretch", service.insights(statuses, isToday = true, hourNow = 19, water = false).single().second.body)
        assertEquals("1 habit left today", service.insights(statuses, isToday = true, hourNow = 19, water = false).single().second.title)
    }
}
