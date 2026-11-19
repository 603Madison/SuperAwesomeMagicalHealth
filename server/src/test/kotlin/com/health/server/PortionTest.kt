package com.health.server

import com.health.server.ai.Portion
import com.health.server.util.RecentLog
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
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
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import javax.imageio.ImageIO

/** "How much did I have?" against a fake Anthropic: the gates, what's sent, sane answers, and that the photos are never kept. */
class PortionTest {
    private val anthropic = MockWebServer()
    private val replies = ConcurrentLinkedQueue<String>()
    private val requests = ConcurrentLinkedQueue<Pair<RecordedRequest, JsonObject>>()
    private var n = 0

    @Before
    fun start() {
        anthropic.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val reply = replies.poll() ?: message("end_turn", text("""{"servings":1,"note":"One bar"}"""))
                return MockResponse().setHeader("Content-Type", "application/json").setBody(reply)
            }
        }
        anthropic.start()
    }

    @After
    fun stop() = anthropic.shutdown()

    private fun message(stop: String, vararg content: String) =
        """{"id":"msg_${n++}","type":"message","role":"assistant","model":"claude-opus-5-5","content":[${content.joinToString(",")}],""" +
            """"stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":1200,"output_tokens":40,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}"""

    private fun text(t: String) = """{"type":"text","text":${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(t))}}"""

    private fun Services.useFakeAnthropic() = ai.apply {
        baseUrl = anthropic.url("/").toString().trimEnd('/')
        maxRetries = 0
    }

    private suspend fun HttpClient.turnOn(dailyLimit: Int = 40) {
        assertEquals(HttpStatusCode.OK, putJson("/api/admin/ai/key", """{"apiKey":"sk-ant-test-key-0123456789"}""").status)
        assertEquals(HttpStatusCode.OK, putJson("/api/admin/ai", """{"enabled":true,"acknowledgedAt":1,"model":"claude-opus-5-5","effort":"medium","dailyLimit":$dailyLimit}""").status)
    }

    private suspend fun HttpClient.consent(modules: String) =
        putJson("/api/ai/consent", """{"modules":[$modules],"acknowledged":true,"sensitiveAcknowledged":false,"allowLogging":false}""")

    /** A 1600×1200 JPEG with an EXIF block saying "turn 90° clockwise" and a GPS block naming the place. */
    private fun phoneJpeg(): ByteArray {
        val img = BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(200, 120, 40); g.fillRect(0, 0, 800, 1200)
        g.color = Color(60, 160, 60); g.fillRect(800, 0, 800, 1200)
        g.dispose()
        val plain = ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray()
        var rest = plain.copyOfRange(2, plain.size)
        if (rest[1] == 0xE0.toByte()) {
            val len = ((rest[2].toInt() and 0xFF) shl 8) or (rest[3].toInt() and 0xFF)
            rest = rest.copyOfRange(2 + len, rest.size)
        }
        val tiff = ByteArrayOutputStream()
        fun u16(v: Int) { tiff.write(v shr 8); tiff.write(v and 0xFF) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        tiff.write("MM".toByteArray()); u16(42); u32(8)
        u16(2)
        u16(0x0112); u16(3); u32(1); u16(6); u16(0)
        u16(0x8825); u16(4); u32(1); u32(38)
        u32(0)
        u16(1)
        u16(0x0012); u16(2); u32(12); u32(56)
        u32(0)
        tiff.write("SECRETPLACE\u0000".toByteArray())
        val exif = "Exif\u0000\u0000".toByteArray() + tiff.toByteArray()
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((exif.size + 2) shr 8).toByte(), ((exif.size + 2) and 0xFF).toByte()) + exif
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 + rest
    }

    private val crisps = """{"name":"Sea salt crisps","serving":"1 bag (25 g)","servingGrams":25,"brand":"Crunchy Co","packageGrams":150}"""

    private suspend fun HttpClient.portion(
        bytes: ByteArray? = phoneJpeg(),
        pack: ByteArray? = null,
        food: String? = crisps,
        note: String? = null,
        viewing: String? = null,
    ): HttpResponse = submitFormWithBinaryData("/api/ai/portion", formData {
        food?.let { append("food", it) }
        note?.let { append("note", it) }
        fun image(name: String, b: ByteArray) = append(name, b, Headers.build {
            append(HttpHeaders.ContentType, "image/jpeg")
            append(HttpHeaders.ContentDisposition, "filename=\"$name.jpg\"")
        })
        bytes?.let { image("photo", it) }
        pack?.let { image("package", it) }
    }) {
        csrf()
        viewing?.let { header("X-SAMH-View", it) }
    }

    private fun ByteArray.has(text: String) = String(this, Charsets.ISO_8859_1).contains(text)

    @Test
    fun `it needs the assistant on, the person's consent with Food, and the assistant not paused`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        assertEquals(HttpStatusCode.Conflict, admin.portion().status)
        admin.turnOn()
        assertEquals(HttpStatusCode.Forbidden, admin.portion().status)
        admin.consent("\"SLEEP\"")
        val noFood = admin.portion()
        assertEquals(HttpStatusCode.Forbidden, noFood.status)
        assertTrue(noFood.bodyAsText().contains("Food"))
        admin.consent("\"NUTRITION\"")
        assertEquals(HttpStatusCode.BadRequest, admin.portion(bytes = null).status)
        assertEquals(HttpStatusCode.BadRequest, admin.portion(food = null).status)
        assertEquals(HttpStatusCode.BadRequest, admin.portion(food = "not json").status)
        assertEquals(HttpStatusCode.BadRequest, admin.portion(food = """{"name":"  "}""").status)
        assertEquals(HttpStatusCode.BadRequest, admin.portion(bytes = "not a picture".toByteArray()).status)
        // Paused in Where your data goes: nothing goes.
        assertEquals(HttpStatusCode.OK, admin.putJson("/api/privacy/AI_ASSISTANT", """{"paused":true}""").status)
        assertEquals(HttpStatusCode.Forbidden, admin.portion().status)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `someone viewing a share can't ask for the owner`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn()
        admin.consent("\"NUTRITION\"")
        s.users.create("sam", "Sam", password = "sam-password-123")
        val sam = browser().also { it.post("/api/auth/login") { json("""{"username":"sam","password":"sam-password-123"}""") } }
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/shares", """{"username":"sam","modules":["NUTRITION"]}""").status)
        assertEquals(HttpStatusCode.Forbidden, sam.portion(viewing = runBlocking { s.users.byUsername("admin")!!.id }).status)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `the photos go upright, shrunk and without their details, with the food's serving and package`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn()
        admin.consent("\"NUTRITION\"")
        replies += message("end_turn", text("""{"servings":1.5,"note":"About one and a half bags, from the open packets."}"""))
        val reply = admin.portion(pack = phoneJpeg(), note = "shared with a friend")
        assertEquals(reply.bodyAsText(), HttpStatusCode.OK, reply.status)
        assertEquals("no-store", reply.headers[HttpHeaders.CacheControl])
        val body = reply.obj()
        assertEquals(1.5, body["servings"]!!.jsonPrimitive.double, 0.001)
        assertEquals("About one and a half bags, from the open packets.", body.str("note"))
        assertFalse(body["refused"]!!.jsonPrimitive.boolean)
        assertEquals(1, body["usedToday"]!!.jsonPrimitive.int)

        val request = requests.single().second
        assertEquals(1500, request["max_tokens"]!!.jsonPrimitive.int)
        val schema = request["output_config"]!!.jsonObject["format"]!!.jsonObject["schema"]!!.jsonObject
        assertEquals(setOf("servings", "note"), schema["properties"]!!.jsonObject.keys)
        val content = request["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("image", "image", "text"), content.map { it.str("type") })
        content.take(2).forEach { c ->
            val jpeg = Base64.getDecoder().decode(c["source"]!!.jsonObject.str("data"))
            assertFalse("no EXIF", jpeg.has("Exif"))
            assertFalse("no place", jpeg.has("SECRETPLACE"))
            val image = ImageIO.read(ByteArrayInputStream(jpeg))
            assertEquals(1024, image.height)
            assertEquals(768, image.width)
        }
        val words = content[2].str("text")
        listOf("Sea salt crisps", "Crunchy Co", "1 bag (25 g) = 25 g", "whole package: 150 g", "second photo is the package", "shared with a friend").forEach {
            assertTrue("$it in: $words", words.contains(it))
        }

        // The log: a portion, Food, the model and tokens. No words.
        val audit = admin.get("/api/ai/audit").json().jsonArray.single().jsonObject
        assertEquals("portion", audit.str("kind"))
        assertEquals(listOf("NUTRITION"), audit["modules"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(audit.toString().contains("crisps") || audit.toString().contains("friend"))

        // Nothing logged (the food is in no table), and the photos kept nowhere.
        val everything = runBlocking {
            s.db.tx {
                query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.string("name") }.flatMap { table ->
                    val columns = query("PRAGMA table_info($table)") { it.string("name") }
                    query("SELECT * FROM $table") { row -> columns.mapNotNull { row.stringOrNull(it) } }.flatten()
                }
            }
        }.joinToString("\n")
        listOf("SECRETPLACE", "Sea salt crisps", "shared with a friend", "one and a half").forEach { assertFalse("the database has $it", everything.contains(it)) }
        s.config.dataDir.walkTopDown().filter { it.isFile }.forEach { f ->
            val bytes = f.readBytes()
            assertFalse("${f.name} starts like a JPEG", bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
        }
        val log = RecentLog.latest().joinToString("\n")
        listOf("SECRETPLACE", "Sea salt crisps", "shared with a friend").forEach { assertFalse("the log has $it", log.contains(it)) }
    }

    @Test
    fun `a recipe's yield goes with it`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn()
        admin.consent("\"NUTRITION\"")
        assertEquals(HttpStatusCode.OK, admin.portion(food = """{"name":"Lentil soup","serving":"1 serving","recipeServings":6}""").status)
        val words = requests.single().second["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.last().jsonObject.str("text")
        assertTrue(words, words.contains("recipe makes 6 servings"))
        assertFalse(words.contains("package"))
    }

    @Test
    fun `odd answers are made sane, and ones it couldn't tell say so`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn()
        admin.consent("\"NUTRITION\"")
        replies += message("end_turn", text("""{"servings":4000,"note":"   lots\n of   bags  "}"""))
        val many = admin.portion().obj()
        assertEquals(Portion.MAX_SERVINGS, many["servings"]!!.jsonPrimitive.double, 0.0)
        assertEquals("lots of bags", many.str("note"))
        replies += message("end_turn", text("""{"servings":0.3333333,"note":"A third"}"""))
        assertEquals(0.33, admin.portion().obj()["servings"]!!.jsonPrimitive.double, 0.0)
        replies += message("end_turn", text("""{"servings":0,"note":"No food in view"}"""))
        val none = admin.portion().obj()
        assertTrue(none["refused"]!!.jsonPrimitive.boolean)
        assertEquals(0.0, none["servings"]!!.jsonPrimitive.double, 0.0)
        assertEquals("No food in view", none.str("note"))
        replies += message("refusal")
        val refused = admin.portion().obj()
        assertTrue(refused["refused"]!!.jsonPrimitive.boolean)
        assertEquals(Portion.COULD_NOT, refused.str("note"))
        replies += message("max_tokens", text("""{"servings":1."""))
        assertTrue(admin.portion().obj()["refused"]!!.jsonPrimitive.boolean)
        assertEquals(5, admin.get("/api/ai/status").obj()["usedToday"]!!.jsonPrimitive.int)
        assertNull(Portion.servings(Portion.Reading(Double.NaN, "")))
    }

    @Test
    fun `it counts towards the day's limit`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn(dailyLimit = 2)
        admin.consent("\"NUTRITION\"")
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/ai/chat", """{"messages":[{"role":"user","text":"Hello"}]}""").status)
        assertEquals(HttpStatusCode.OK, admin.portion().status)
        assertEquals(HttpStatusCode.TooManyRequests, admin.portion().status)
        assertEquals(2, requests.size)
    }

    @Test
    fun `a package's size comes from Open Food Facts, and isn't kept with the person's foods`() {
        val crisps = com.health.server.food.OpenFoodFacts.toFood(
            Json.parseToJsonElement("""{"product_name":"Crisps","serving_quantity":25,"product_quantity":150,"product_quantity_unit":"g","nutriments":{"energy-kcal_100g":530}}""").jsonObject,
        )!!
        assertEquals(150.0, crisps.packageGrams!!, 0.0)
        assertNull(com.health.server.food.OpenFoodFacts.toFood(Json.parseToJsonElement("""{"product_name":"Crisps","product_quantity":6,"product_quantity_unit":"pieces","nutriments":{"energy-kcal_100g":530}}""").jsonObject)!!.packageGrams)
        assertNull(com.health.server.food.FoodStore.validated(crisps.copy(recipeServings = 4.0)).packageGrams)
        assertNull(com.health.server.food.FoodStore.validated(crisps.copy(recipeServings = 4.0)).recipeServings)
    }
}
