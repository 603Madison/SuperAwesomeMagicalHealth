package com.health.server

import com.health.api.Module
import com.health.server.ai.AiTools
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/** The AI assistant, against a fake Anthropic: it never reaches the real one. */
class AiTest {
    private val anthropic = MockWebServer()
    private val replies = ConcurrentLinkedQueue<String>()
    private val requests = ConcurrentLinkedQueue<Pair<RecordedRequest, JsonObject>>()
    private var n = 0

    @Before
    fun start() {
        anthropic.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val reply = replies.poll() ?: message("end_turn", text("(nothing more was scripted)"))
                return MockResponse().setHeader("Content-Type", "application/json").setBody(reply)
            }
        }
        anthropic.start()
    }

    @After
    fun stop() = anthropic.shutdown()

    private fun message(stop: String, vararg content: String) =
        """{"id":"msg_${n++}","type":"message","role":"assistant","model":"claude-opus-5-5","content":[${content.joinToString(",")}],""" +
            """"stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":120,"output_tokens":30,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}"""

    private fun quoted(t: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(t))
    private fun text(t: String) = """{"type":"text","text":${quoted(t)}}"""
    private fun toolUse(id: String, name: String, input: String) = """{"type":"tool_use","id":"$id","name":"$name","input":$input}"""

    private fun Services.useFakeAnthropic() = ai.apply {
        baseUrl = anthropic.url("/").toString().trimEnd('/')
        maxRetries = 0
    }

    private suspend fun HttpClient.turnOn(allowLogging: Boolean = false, dailyLimit: Int = 40) {
        assertEquals(HttpStatusCode.OK, putJson("/api/admin/ai/key", """{"apiKey":"sk-ant-test-key-0123456789"}""").status)
        val on = putJson("/api/admin/ai", """{"enabled":true,"acknowledgedAt":1,"model":"claude-opus-5-5","effort":"medium","dailyLimit":$dailyLimit,"allowLogging":$allowLogging}""")
        assertEquals(HttpStatusCode.OK, on.status)
    }

    private suspend fun HttpClient.consent(modules: String, sensitive: Boolean = false, logging: Boolean = false) =
        putJson("/api/ai/consent", """{"modules":[$modules],"acknowledged":true,"sensitiveAcknowledged":$sensitive,"allowLogging":$logging}""")

    private suspend fun HttpClient.ask(question: String) = postJson("/api/ai/chat", """{"messages":[{"role":"user","text":${quoted(question)}}]}""")

    private fun toolNames(request: JsonObject) = request["tools"]?.jsonArray.orEmpty().map { it.jsonObject["name"]!!.jsonPrimitive.content }

    @Test
    fun `the admin turns it on only after acknowledging where data goes, and the key never comes back`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        val off = admin.putJson("/api/admin/ai", """{"enabled":true,"model":"claude-opus-5-5","effort":"medium","dailyLimit":40}""")
        assertEquals(HttpStatusCode.BadRequest, off.status)
        assertTrue(off.bodyAsText().contains("Anthropic"))
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/admin/ai", """{"enabled":false,"model":"gpt-4","effort":"medium"}""").status)
        admin.turnOn()
        val settings = admin.get("/api/admin/ai").bodyAsText()
        assertFalse(settings.contains("sk-ant-test-key"))
        assertTrue(Json.parseToJsonElement(settings).jsonObject["hasKey"]!!.jsonPrimitive.boolean)
        assertEquals("sk-ant-test-key-0123456789", s.ai.store.key())
        // Kept sealed, not as it was typed.
        val raw = runBlocking { s.db.tx { one("SELECT value FROM settings WHERE key = 'ai.key'") { it.string("value") } } }!!
        assertFalse(raw.contains("sk-ant"))
        // Nobody else can see or change it.
        s.users.create("sam", "Sam", password = "sam-password-123")
        val sam = browser().also { it.post("/api/auth/login") { json("""{"username":"sam","password":"sam-password-123"}""") } }
        assertEquals(HttpStatusCode.Forbidden, sam.get("/api/admin/ai").status)
        assertEquals(HttpStatusCode.Forbidden, sam.putJson("/api/admin/ai/key", """{"apiKey":"sk-ant-someone-elses-key-123"}""").status)
        // The test call reaches Anthropic, with the key.
        replies += message("end_turn", text("ready"))
        assertTrue(admin.postJson("/api/admin/ai/test", "{}").obj()["ok"]!!.jsonPrimitive.boolean)
        val sentHeaders = requests.last().first.headers
        assertTrue(sentHeaders.toString(), sentHeaders.toMultimap().values.flatten().any { "sk-ant-test-key-0123456789" in it })
    }

    @Test
    fun `consent needs reading the warning, private features their own tick, and photos are never included`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        assertFalse(admin.get("/api/ai/status").obj()["available"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.Conflict, admin.ask("Hello").status)
        admin.turnOn()
        val status = admin.get("/api/ai/status").obj()
        assertTrue(status["available"]!!.jsonPrimitive.boolean)
        assertTrue(status["dataNotice"]!!.jsonPrimitive.content.contains("Anthropic"))
        assertNull(status["consent"])
        assertEquals(HttpStatusCode.Forbidden, admin.ask("How did I sleep?").status)
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/ai/consent", """{"modules":["SLEEP"],"acknowledged":false}""").status)
        assertEquals(HttpStatusCode.BadRequest, admin.consent("\"SLEEP\",\"MIND\"").status)
        assertEquals(HttpStatusCode.BadRequest, admin.consent("\"SLEEP\"", logging = true).status)
        val saved = admin.consent("\"SLEEP\",\"MIND\",\"PHOTOS\"", sensitive = true).obj()
        assertEquals(setOf("SLEEP", "MIND"), saved["modules"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertTrue(Module.PHOTOS !in AiTools.MODULES.values)
        assertTrue(AiTools.MODULES.keys.none { "photo" in it || it in setOf("server_log", "recent_warnings", "webhook_deliveries", "notifications_status") })
        // Kept sealed.
        val raw = runBlocking { s.db.tx { one("SELECT data FROM user_settings WHERE key = 'ai.consent'") { it.string("data") } } }!!
        assertFalse(raw.contains("SLEEP"))
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/ai/consent") { csrf() }.status)
        assertEquals(HttpStatusCode.Forbidden, admin.ask("How did I sleep?").status)
    }

    @Test
    fun `it looks with only the tools for what was allowed, and the log keeps no words`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn()
        admin.consent("\"SLEEP\",\"ACTIVITY\"")
        replies += message(
            "tool_use",
            text("Let me look."),
            toolUse("toolu_1", "sleep", """{"days":7,"account":"someone-else"}"""),
            toolUse("toolu_2", "mind", "{}"),
        )
        replies += message("end_turn", text("You slept well this week."))
        val question = "How did I sleep this week? I've been worried about my mood too."
        val reply = admin.ask(question)
        assertEquals(HttpStatusCode.OK, reply.status)
        val body = reply.obj()
        assertEquals("You slept well this week.", body["text"]!!.jsonPrimitive.content)
        val looked = body["looked"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("sleep"), looked.map { it["tool"]!!.jsonPrimitive.content })
        assertEquals("SLEEP", looked.single()["module"]!!.jsonPrimitive.content)

        val (first, firstBody) = requests.first()
        assertTrue(first.path!!.startsWith("/v1/messages"))
        val tools = toolNames(firstBody)
        assertEquals(tools.sorted(), tools)
        assertTrue("day" in tools && "sleep" in tools && "workouts" in tools)
        assertTrue(tools.none { it in setOf("mind", "journal", "cycle", "medications", "food_log", "server_log", "log_food", "log_weight") })
        assertTrue(firstBody["tools"]!!.jsonArray.none { "account" in it.jsonObject["input_schema"]!!.jsonObject["properties"]!!.jsonObject })
        // The model, effort, the stable system prompt cached, the refusal fallback; no forced tool or thinking settings.
        assertEquals("claude-opus-5-5", firstBody["model"]!!.jsonPrimitive.content)
        assertEquals("medium", firstBody["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue(firstBody["system"]!!.jsonArray.first().jsonObject.containsKey("cache_control"))
        assertEquals("default", firstBody["fallbacks"]!!.jsonPrimitive.content)
        assertTrue(first.getHeader("anthropic-beta")!!.contains("server-side-fallback-2026-07-01"))
        assertFalse(firstBody.containsKey("tool_choice"))
        assertFalse(firstBody.containsKey("thinking"))

        // Both results went back in one message; the tool it wasn't given came back as an error.
        val second = requests.toList()[1].second
        val results = second["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("toolu_1", "toolu_2"), results.map { it["tool_use_id"]!!.jsonPrimitive.content })
        assertTrue(results[1]["is_error"]!!.jsonPrimitive.boolean)

        // The log: what kind, which features and tools, the model, the tokens. Never the words.
        val audit = admin.get("/api/ai/audit").json().jsonArray.single().jsonObject
        assertEquals(listOf("sleep"), audit["tools"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(240, audit["inputTokens"]!!.jsonPrimitive.int)
        assertEquals(60, audit["outputTokens"]!!.jsonPrimitive.int)
        assertFalse(audit.toString().contains("sleep this week") || audit.toString().contains("slept well"))
        val rows = runBlocking { s.db.tx { query("SELECT data FROM ai_audit") { it.string("data") } } }
        assertTrue(rows.none { it.contains("sleep") })
        assertEquals(1, admin.get("/api/ai/status").obj()["usedToday"]!!.jsonPrimitive.int)
    }

    @Test
    fun `it sees a condition only when every feature it turns on was allowed`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn()
        val saved = admin.putJson(
            "/api/conditions",
            """{"conditions":[{"id":"sleep-better","name":"Sleep better","kind":"GOAL"},{"id":"hypertension","name":"High blood pressure"}]}""",
        )
        assertEquals(HttpStatusCode.OK, saved.status)
        admin.consent("\"SLEEP\",\"HABITS\",\"VITALS\"")
        replies += message("tool_use", toolUse("toolu_1", "conditions", "{}"))
        replies += message("end_turn", text("You're working on sleeping better."))
        assertEquals(HttpStatusCode.OK, admin.ask("What am I working on?").status)

        assertTrue("conditions" in toolNames(requests.first().second))
        val result = requests.toList()[1].second["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray.single().jsonObject.toString()
        assertTrue(result.contains("Sleep better"))
        // High blood pressure also turns on medications, which the person didn't allow.
        assertFalse(result.contains("blood pressure"))
    }

    @Test
    fun `it may log entries only when both the admin and the person allow it`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn(allowLogging = false)
        assertFalse(admin.get("/api/ai/status").obj()["loggingAllowed"]!!.jsonPrimitive.boolean)
        admin.consent("\"NUTRITION\"")
        admin.ask("What did I eat?")
        assertTrue(toolNames(requests.last().second).none { it.startsWith("log_") })

        admin.turnOn(allowLogging = true)
        assertTrue(admin.get("/api/ai/status").obj()["loggingAllowed"]!!.jsonPrimitive.boolean)
        admin.consent("\"NUTRITION\"", logging = false)
        admin.ask("What did I eat?")
        assertTrue(toolNames(requests.last().second).none { it.startsWith("log_") })

        admin.consent("\"NUTRITION\"", logging = true)
        admin.ask("Log a glass of water")
        val tools = toolNames(requests.last().second)
        assertTrue("log_water" in tools && "log_food" in tools)
        assertTrue("log_weight" !in tools)
    }

    @Test
    fun `a day's questions are limited`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn(dailyLimit = 1)
        admin.consent("\"SLEEP\"")
        assertEquals(HttpStatusCode.OK, admin.ask("One").status)
        val second = admin.ask("Two")
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
        assertEquals(1, requests.size)
    }

    @Test
    fun `refusals and long answers are said plainly`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn()
        admin.consent("\"SLEEP\"")
        replies += message("refusal")
        val refused = admin.ask("Something it won't help with").obj()["text"]!!.jsonPrimitive.content
        assertTrue(refused.startsWith("I can't help with that one"))
        replies += message("max_tokens", text("Here's the first part"))
        val cut = admin.ask("Tell me everything").obj()["text"]!!.jsonPrimitive.content
        assertTrue(cut.startsWith("Here's the first part") && cut.contains("had to stop"))
        // Anthropic turning the key down reads as the admin's to fix.
        replies += """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        anthropic.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        }
        val bad = admin.ask("Hello")
        assertEquals(HttpStatusCode.BadGateway, bad.status)
        assertTrue(bad.bodyAsText().contains("API key"))
    }

    @Test
    fun `a task is broken into steps from its words alone`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn()
        admin.consent("")
        replies += message("end_turn", text("""{"steps":[{"text":"Open the laptop","minutes":1},{"text":"Write the first line","minutes":5}]}"""))
        val steps = admin.postJson("/api/ai/breakdown", """{"task":"Write the report"}""").obj()["steps"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Open the laptop", "Write the first line"), steps.map { it["text"]!!.jsonPrimitive.content })
        val sent = requests.last().second
        assertFalse(sent.containsKey("tools"))
        assertEquals("Task: Write the report", sent["messages"]!!.jsonArray.single().jsonObject["content"]!!.let {
            (it as? JsonPrimitive)?.content ?: it.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        })
        assertEquals("json_schema", sent["output_config"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
}
