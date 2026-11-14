package com.health.server

import com.health.server.ai.FoodPhoto
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
import org.junit.Assert.assertNotNull
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

/** Food photo estimates, against a fake Anthropic: what's sent, what comes back, and that the photo is never kept. */
class FoodPhotoTest {
    private val anthropic = MockWebServer()
    private val replies = ConcurrentLinkedQueue<String>()
    private val requests = ConcurrentLinkedQueue<Pair<RecordedRequest, JsonObject>>()
    private var n = 0

    @Before
    fun start() {
        anthropic.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val reply = replies.poll() ?: message("end_turn", text("""{"items":[],"notes":""}"""))
                return MockResponse().setHeader("Content-Type", "application/json").setBody(reply)
            }
        }
        anthropic.start()
    }

    @After
    fun stop() = anthropic.shutdown()

    private fun message(stop: String, vararg content: String) =
        """{"id":"msg_${n++}","type":"message","role":"assistant","model":"claude-opus-5-5","content":[${content.joinToString(",")}],""" +
            """"stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":1500,"output_tokens":220,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}"""

    private fun quoted(t: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(t))
    private fun text(t: String) = """{"type":"text","text":${quoted(t)}}"""

    private val plate = """
        {"items":[
          {"name":"Spaghetti bolognese","amount":320,"unit":"GRAMS","calories":480,"protein":24,"carbs":62,"fat":14,"fiber":5,"confidence":"MEDIUM","assumption":"assumed cooked weight"},
          {"name":"Green salad","amount":60,"unit":"GRAMS","calories":20,"protein":1,"carbs":3,"fat":0.2,"fiber":1.5,"confidence":"LOW","assumption":"dressing not visible"},
          {"name":"Orange juice","amount":250,"unit":"MILLILITRES","calories":110,"protein":1.7,"carbs":26,"fat":0.5,"fiber":0.5,"confidence":"HIGH","assumption":"assumed a standard glass"}
        ],"notes":""}
    """.trimIndent()

    private fun Services.useFakeAnthropic() = ai.apply {
        baseUrl = anthropic.url("/").toString().trimEnd('/')
        maxRetries = 0
    }

    private suspend fun HttpClient.turnOn(dailyLimit: Int = 40, effort: String = "medium") {
        assertEquals(HttpStatusCode.OK, putJson("/api/admin/ai/key", """{"apiKey":"sk-ant-test-key-0123456789"}""").status)
        val on = putJson("/api/admin/ai", """{"enabled":true,"acknowledgedAt":1,"model":"claude-opus-5-5","effort":"$effort","dailyLimit":$dailyLimit}""")
        assertEquals(HttpStatusCode.OK, on.status)
    }

    private suspend fun HttpClient.consent(modules: String) =
        putJson("/api/ai/consent", """{"modules":[$modules],"acknowledged":true,"sensitiveAcknowledged":false,"allowLogging":false}""")

    /** A 1600×1200 JPEG, as a phone takes it: EXIF first, saying "turn 90° clockwise", with a GPS block naming the place. */
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

    private suspend fun HttpClient.snap(bytes: ByteArray = phoneJpeg(), meal: String? = "LUNCH", note: String? = null, viewing: String? = null): HttpResponse =
        submitFormWithBinaryData("/api/ai/food-photo", formData {
            meal?.let { append("meal", it) }
            note?.let { append("note", it) }
            append("photo", bytes, Headers.build {
                append(HttpHeaders.ContentType, "image/jpeg")
                append(HttpHeaders.ContentDisposition, "filename=\"meal.jpg\"")
            })
        }) {
            csrf()
            viewing?.let { header("X-SAMH-View", it) }
        }

    private fun ByteArray.has(text: String) = String(this, Charsets.ISO_8859_1).contains(text)

    private fun Services.admin() = runBlocking { users.byUsername("admin")!!.id }

    @Test
    fun `it needs the assistant on, the person's consent, and Food among what they allowed`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        assertEquals(HttpStatusCode.Conflict, admin.snap().status)
        admin.turnOn()
        val noConsent = admin.snap()
        assertEquals(HttpStatusCode.Forbidden, noConsent.status)
        assertTrue(noConsent.bodyAsText().contains("Settings › AI assistant"))
        admin.consent("\"SLEEP\"")
        val noFood = admin.snap()
        assertEquals(HttpStatusCode.Forbidden, noFood.status)
        assertTrue(noFood.bodyAsText().contains("Food"))
        admin.consent("\"NUTRITION\"")
        assertEquals(HttpStatusCode.BadRequest, admin.submitFormWithBinaryData("/api/ai/food-photo", formData { append("meal", "LUNCH") }) { csrf() }.status)
        assertEquals(HttpStatusCode.BadRequest, admin.snap(bytes = "not a picture".toByteArray()).status)
        // Nothing reached Anthropic.
        assertTrue(requests.isEmpty())
        // Off again on the server: no photos either.
        admin.putJson("/api/admin/ai", """{"enabled":false,"model":"claude-opus-5-5","effort":"medium","dailyLimit":40}""")
        assertEquals(HttpStatusCode.Conflict, admin.snap().status)
        assertTrue(requests.isEmpty())
    }

    /** A photo the size a phone sends: full of detail, so about a megabyte as JPEG. */
    private fun bigJpeg(): ByteArray {
        val random = java.util.Random(7)
        val img = BufferedImage(2000, 1500, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) img.setRGB(x, y, random.nextInt(0xFFFFFF))
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray()
    }

    @Test
    fun `a photo the size a phone sends is estimated`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn()
        admin.consent("\"NUTRITION\"")
        val big = bigJpeg()
        assertTrue("the test photo is phone-sized (${big.size} bytes)", big.size > 500_000)
        val answer = admin.snap(bytes = big)
        assertEquals(answer.bodyAsText(), HttpStatusCode.OK, answer.status)
    }

    @Test
    fun `someone viewing a share can't send a photo for the owner`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn()
        admin.consent("\"NUTRITION\"")
        s.users.create("sam", "Sam", password = "sam-password-123")
        val sam = browser().also { it.post("/api/auth/login") { json("""{"username":"sam","password":"sam-password-123"}""") } }
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/shares", """{"username":"sam","modules":["NUTRITION"]}""").status)
        val viewing = sam.snap(viewing = s.admin())
        assertEquals(HttpStatusCode.Forbidden, viewing.status)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `the photo goes upright, shrunk and without its details, with a typed answer, the model, effort and fallback`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn(effort = "high")
        admin.consent("\"NUTRITION\"")
        replies += message("end_turn", text(plate))
        val reply = admin.snap(note = "large plate")
        assertEquals(HttpStatusCode.OK, reply.status)
        assertEquals("no-store", reply.headers[HttpHeaders.CacheControl])
        val body = reply.obj()
        assertFalse(body["refused"]!!.jsonPrimitive.boolean)
        assertFalse(body["hideNumbers"]!!.jsonPrimitive.boolean)
        assertEquals(1, body["usedToday"]!!.jsonPrimitive.int)
        val items = body["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Spaghetti bolognese", "Green salad", "Orange juice"), items.map { it.str("name") })
        assertEquals(320.0, items[0]["grams"]!!.jsonPrimitive.double, 0.01)
        assertEquals(480.0, items[0]["nutrients"]!!.jsonObject["calories"]!!.jsonPrimitive.double, 0.01)
        assertEquals(5.0, items[0]["nutrients"]!!.jsonObject["fiber"]!!.jsonPrimitive.double, 0.01)
        assertEquals("MEDIUM", items[0].str("confidence"))
        assertEquals("assumed cooked weight", items[0].str("assumption"))
        assertEquals("LOW", items[1].str("confidence"))
        assertEquals("ml", items[2].str("unit"))
        assertEquals("g", items[0].str("unit"))

        val (sent, request) = requests.single()
        assertTrue(sent.path!!.startsWith("/v1/messages"))
        assertEquals("claude-opus-5-5", request.str("model"))
        assertEquals(4000, request["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("high", request["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("default", request["fallbacks"]!!.jsonPrimitive.content)
        assertTrue(sent.getHeader("anthropic-beta")!!.contains("server-side-fallback-2026-07-01"))
        assertFalse(request.containsKey("thinking"))
        assertFalse(request.containsKey("tools"))
        // Structured output: a JSON schema made from the answer's shape.
        val format = request["output_config"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format.str("type"))
        val schema = format["schema"]!!.jsonObject
        assertTrue(schema["properties"]!!.jsonObject.keys.containsAll(listOf("items", "notes")))
        val itemSchema = schema.toString()
        listOf("amount", "calories", "protein", "carbs", "fat", "fiber", "sugar", "saturatedFat", "sodiumMg", "confidence", "assumption", "MILLILITRES").forEach { assertTrue(it, itemSchema.contains(it)) }
        // Vitamins and minerals can't be judged from a photo: the schema never asks for them.
        com.health.api.Micronutrient.entries.forEach { m -> assertFalse(m.name, itemSchema.lowercase().contains(m.name.lowercase())) }
        assertFalse(itemSchema.lowercase().contains("vitamin"))
        assertTrue(request["system"]!!.jsonArray.first().jsonObject.str("text").contains("grams for food"))

        // One user message: the image first, then the words (the meal and the note).
        val content = request["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("image", "text"), content.map { it.str("type") })
        val source = content[0]["source"]!!.jsonObject
        assertEquals("base64", source.str("type"))
        assertEquals("image/jpeg", source.str("media_type"))
        val jpeg = Base64.getDecoder().decode(source.str("data"))
        assertTrue(jpeg.size > 1000)
        assertTrue(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
        assertFalse("no EXIF", jpeg.has("Exif"))
        assertFalse("no place", jpeg.has("SECRETPLACE"))
        val image = ImageIO.read(ByteArrayInputStream(jpeg))
        // Turned upright (portrait now) and shrunk to 1024 on the long side.
        assertEquals(1024, image.height)
        assertEquals(768, image.width)
        val words = content[1].str("text")
        assertTrue(words, words.contains("lunch") && words.contains("large plate"))

        // The log: a food photo, Food, the model and tokens. No words.
        val audit = admin.get("/api/ai/audit").json().jsonArray.single().jsonObject
        assertEquals("food_photo", audit.str("kind"))
        assertEquals(listOf("NUTRITION"), audit["modules"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("claude-opus-5-5", audit.str("model"))
        assertEquals(1500, audit["inputTokens"]!!.jsonPrimitive.int)
        assertEquals(220, audit["outputTokens"]!!.jsonPrimitive.int)
        assertFalse(audit.toString().contains("Spaghetti") || audit.toString().contains("large plate"))
        assertEquals(1, admin.get("/api/ai/status").obj()["usedToday"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the photo is never kept, not in the database, the data folder or the log`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn()
        admin.consent("\"NUTRITION\"")
        replies += message("end_turn", text(plate))
        val original = phoneJpeg()
        assertEquals(HttpStatusCode.OK, admin.snap(bytes = original, note = "half eaten").status)
        val sentData = requests.single().second["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.first().jsonObject["source"]!!.jsonObject.str("data")
        val sent = Base64.getDecoder().decode(sentData)
        val originalB64 = Base64.getEncoder().encodeToString(original)

        // Every text in every table.
        val everything = runBlocking {
            s.db.tx {
                query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.string("name") }.flatMap { table ->
                    val columns = query("PRAGMA table_info($table)") { it.string("name") }
                    query("SELECT * FROM $table") { row -> columns.mapNotNull { row.stringOrNull(it) } }.flatten()
                }
            }
        }.joinToString("\n")
        assertTrue(everything.isNotEmpty())
        listOf(sentData.take(64), originalB64.take(64), "SECRETPLACE", "Exif", "half eaten", "Spaghetti").forEach {
            assertFalse("the database has $it", everything.contains(it))
        }

        // No file under the data folder holds a picture.
        val files = s.config.dataDir.walkTopDown().filter { it.isFile }.toList()
        files.forEach { f ->
            val bytes = f.readBytes()
            assertFalse("${f.name} starts like a JPEG", bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
            assertFalse("${f.name} has the photo", bytes.has(sentData.take(64)) || bytes.has("SECRETPLACE"))
            assertNull("${f.name} reads as a picture", runCatching { ImageIO.read(f) }.getOrNull())
        }
        assertTrue(sent.isNotEmpty())

        // Nor the server's log.
        val log = RecentLog.latest().joinToString("\n")
        listOf(sentData.take(64), originalB64.take(64), "SECRETPLACE", "half eaten", "Spaghetti").forEach { assertFalse("the log has $it", log.contains(it)) }
    }

    @Test
    fun `refusals and cut-off answers are said plainly, and count towards the day`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn()
        admin.consent("\"NUTRITION\"")
        replies += message("refusal")
        val refused = admin.snap().obj()
        assertTrue(refused["refused"]!!.jsonPrimitive.boolean)
        assertEquals(FoodPhoto.COULD_NOT, refused.str("notes"))
        assertTrue(refused["items"]!!.jsonArray.isEmpty())
        replies += message("max_tokens", text("""{"items":[{"name":"Spag"""))
        val cut = admin.snap().obj()
        assertTrue(cut["refused"]!!.jsonPrimitive.boolean)
        assertEquals("Couldn't make that out; try another angle or add it by hand", cut.str("notes"))
        // Nothing to see: not refused, but said.
        replies += message("end_turn", text("""{"items":[],"notes":""}"""))
        val empty = admin.snap().obj()
        assertFalse(empty["refused"]!!.jsonPrimitive.boolean)
        assertEquals(FoodPhoto.NOTHING_SEEN, empty.str("notes"))
        assertEquals(3, empty["usedToday"]!!.jsonPrimitive.int)
        assertEquals(3, admin.get("/api/ai/audit").json().jsonArray.size)
    }

    @Test
    fun `photos count towards the day's limit`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn(dailyLimit = 2)
        admin.consent("\"NUTRITION\"")
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/ai/chat", """{"messages":[{"role":"user","text":"Hello"}]}""").status)
        replies += message("end_turn", text(plate))
        assertEquals(HttpStatusCode.OK, admin.snap().status)
        assertEquals(HttpStatusCode.TooManyRequests, admin.snap().status)
        assertEquals(2, requests.size)
    }

    @Test
    fun `items come with the person's own foods of a similar name`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn()
        admin.consent("\"NUTRITION\"")
        val mine = admin.postJson("/api/foods", """{"name":"Homemade spaghetti bolognese","serving":"1 bowl","servingGrams":350,"nutrients":{"calories":560,"protein":30,"carbs":70,"fat":16}}""").obj()
        admin.postJson("/api/foods", """{"name":"Caesar salad","serving":"1 bowl","nutrients":{"calories":300,"protein":10,"carbs":12,"fat":22}}""")
        admin.postJson("/api/foods", """{"name":"Orange and mango smoothie","serving":"1 bottle","nutrients":{"calories":180,"protein":2,"carbs":40,"fat":1}}""")
        replies += message("end_turn", text(plate))
        val items = admin.snap().obj()["items"]!!.jsonArray.map { it.jsonObject }
        val match = items[0]["match"]!!.jsonObject
        assertEquals(mine.str("id"), match.str("id"))
        assertEquals(350.0, match["servingGrams"]!!.jsonPrimitive.double, 0.01)
        // "Green salad" isn't "Caesar salad", and orange juice isn't a smoothie.
        assertNull(items[1]["match"])
        assertNull(items[2]["match"])

        // The words alone.
        assertTrue(FoodPhoto.similarity(FoodPhoto.words("Grilled chicken breasts"), FoodPhoto.words("Chicken breast")) >= 0.5)
        assertEquals(0.0, FoodPhoto.similarity(FoodPhoto.words("Banana"), FoodPhoto.words("Banana bread")), 0.0)
    }

    @Test
    fun `in eating-disorder recovery the estimate says to leave out the numbers, and still has them`() = serverTest { s ->
        s.useFakeAnthropic()
        val admin = adminBrowser()
        admin.turnOn()
        admin.consent("\"NUTRITION\"")
        assertEquals(HttpStatusCode.OK, admin.putJson("/api/conditions", """{"conditions":[{"id":"eating-disorder","name":"Eating disorder recovery"}]}""").status)
        replies += message("end_turn", text(plate))
        val body = admin.snap().obj()
        assertTrue(body["hideNumbers"]!!.jsonPrimitive.boolean)
        // Still filled in, for logging.
        assertEquals(480.0, body["items"]!!.jsonArray.first().jsonObject["nutrients"]!!.jsonObject["calories"]!!.jsonPrimitive.double, 0.01)
    }

    @Test
    fun `an estimate is logged with the food API, its portion as the serving`() = serverTest { s ->
        val admin = adminBrowser()
        val logged = admin.postJson(
            "/api/food/entries",
            """{"date":"2026-11-12","meal":"LUNCH","servings":1,"name":"Spaghetti bolognese","serving":"about 320 g","nutrients":{"calories":480,"protein":24,"carbs":62,"fat":14,"fiber":5}}""",
        ).obj()
        assertEquals("about 320 g", logged.str("serving"))
        assertNotNull(logged["id"])
        assertEquals(480.0, logged["nutrients"]!!.jsonObject["calories"]!!.jsonPrimitive.double, 0.01)
    }
}
