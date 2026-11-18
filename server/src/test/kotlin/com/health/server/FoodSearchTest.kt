package com.health.server

import com.health.server.food.OpenFoodFacts
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Food search through the server: USDA first (everyday, then packaged), Open Food Facts only to fill in, each food once. */
class FoodSearchTest {
    private fun resource(name: String) = FoodSearchTest::class.java.getResource("/usda/$name")!!.readText()

    /** What USDA was asked: the query, the kinds of food, and the key it was sent. */
    data class Asked(val query: String, val dataType: String, val key: String?, val path: String)

    private val usdaAsked = CopyOnWriteArrayList<Asked>()
    private val offAsked = CopyOnWriteArrayList<String>()

    /** How USDA answers: by default the real answers saved for "banana" (everyday) and "greek yogurt" (packaged, also for "banana"). */
    @Volatile
    private var usdaAnswer: (Asked) -> MockResponse = { asked ->
        when {
            asked.dataType == "Branded" && asked.query in setOf("greek yogurt", "banana", "894700010038") -> MockResponse().setBody(resource("branded-greek-yogurt.json"))
            asked.dataType != "Branded" && asked.query == "banana" -> MockResponse().setBody(resource("everyday-banana.json"))
            else -> MockResponse().setBody("""{"foods":[]}""")
        }
    }

    private val usda = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val asked = Asked(url.queryParameter("query").orEmpty(), url.queryParameter("dataType").orEmpty(), request.getHeader("X-Api-Key"), request.path.orEmpty())
                usdaAsked += asked
                return usdaAnswer(asked)
            }
        }
        start()
    }

    private val off = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                offAsked += request.path.orEmpty()
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/search") -> MockResponse().setBody(
                        """{"hits":[
                            {"code":"0894700010038","lang":"en","brands":["Chobani"],"product_name":"Plain Greek Yogurt","nutriments":{"energy-kcal_100g":53}},
                            {"code":"5000000000001","lang":"ru","product_name":"Греческий йогурт","nutriments":{"energy-kcal_100g":60}},
                            {"code":"5000000000002","lang":"de","product_name":"Griechischer Joghurt","product_name_en":"Greek yoghurt","brands":"Milbona","nutriments":{"energy-kcal_100g":110}},
                            {"code":"5000000000003","lang":"en","product_name":"banana, RAW","nutriments":{"energy-kcal_100g":89}}]}""",
                    )
                    path.startsWith("/product/3017624010701") -> MockResponse().setBody(
                        """{"product":{"code":"3017624010701","product_name":"Nutella","nutriments":{"energy_100g":2228,"proteins_100g":6.3}}}""",
                    )
                    else -> MockResponse().setResponseCode(404).setBody("""{"status":0}""")
                }
            }
        }
        start()
    }

    @After
    fun stop() {
        usda.shutdown()
        off.shutdown()
    }

    private var now = 1_800_000_000_000L

    private fun services(key: String? = "test-key", fallback: Boolean = true) = testServices(
        clock = { now },
        foodUrls = OpenFoodFacts.Urls(off.url("/search").toString(), off.url("/product/").toString()),
        usdaUrl = usda.url("/fdc/v1/foods/search").toString(),
    ).also { s ->
        runBlocking {
            s.settings.update { it.copy(foodDatabase = fallback) }
            key?.let { s.settings.setUsdaApiKey(it) }
        }
    }

    private fun JsonObject.online() = this["online"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.names() = online().map { it.str("name") }
    private fun JsonObject.problem() = this["onlineError"]?.takeIf { it.toString() != "null" }?.jsonPrimitive?.content

    @Test
    fun `everyday foods come first, then packaged ones, each once, and Open Food Facts isn't needed`() = serverTest(services()) {
        val browser = adminBrowser()
        val banana = browser.get("/api/foods/search?q=banana").obj()
        // Four everyday foods, then five packaged ones, two of them the same Chobani yogurt.
        assertEquals(listOf("Bananas, dehydrated, or banana powder", "Banana, baked", "Banana, raw", "Banana chips"), banana.names().take(4))
        assertEquals(listOf("Ocean Spray", "Trader Joe's", "Chobani", "Olympiana"), banana.online().drop(4).map { it.str("brand") })
        assertTrue(banana.online().all { it.str("source") == "USDA" })
        assertNull(banana.problem())
        // Both kinds asked, with the key in a header and never in the address.
        assertEquals(setOf("Foundation,SR Legacy,Survey (FNDDS)", "Branded"), usdaAsked.map { it.dataType }.toSet())
        assertTrue(usdaAsked.all { it.key == "test-key" && "api_key" !in it.path && "test-key" !in it.path })
        assertTrue(usdaAsked.first { it.dataType == "Branded" }.path.contains("pageSize=15"))
        assertTrue(usdaAsked.first { it.dataType != "Branded" }.path.contains("pageSize=8"))
        // Enough found: Open Food Facts wasn't asked.
        assertTrue(offAsked.isEmpty())

        // Kept a week: asked again, USDA isn't.
        val asked = usdaAsked.size
        browser.get("/api/foods/search?q=Banana")
        assertEquals(asked, usdaAsked.size)
    }

    @Test
    fun `Open Food Facts fills in, in English, without what USDA already has`() = serverTest(services()) {
        val found = adminBrowser().get("/api/foods/search?q=greek%20yogurt").obj()
        // Four packaged yogurts from USDA are fewer than five: Open Food Facts fills in with its German
        // yoghurt (by its English name) and its banana; its Chobani (the same barcode, written with a
        // leading zero) and the Russian one (no English name) are left out.
        assertEquals(listOf("Greek Yogurt", "Greek Yogurt", "Greek Yogurt", "Greek Yogurt", "Greek yoghurt", "banana, RAW"), found.names())
        assertEquals(listOf("USDA", "USDA", "USDA", "USDA", "OPEN_FOOD_FACTS", "OPEN_FOOD_FACTS"), found.online().map { it.str("source") })
        assertTrue(offAsked.single().contains("langs=en"))
    }

    @Test
    fun `when USDA says no, Open Food Facts answers quietly, and nothing is kept`() = serverTest(services()) {
        usdaAnswer = { MockResponse().setResponseCode(429).setBody("""{"error":{"code":"OVER_RATE_LIMIT"}}""") }
        val browser = adminBrowser()
        val found = browser.get("/api/foods/search?q=greek%20yogurt").obj()
        assertEquals(listOf("Plain Greek Yogurt", "Greek yoghurt", "banana, RAW"), found.names())
        assertNull(found.problem())
        assertEquals(2, usdaAsked.size)
        browser.get("/api/foods/search?q=greek%20yogurt")
        assertEquals(4, usdaAsked.size)
    }

    @Test
    fun `when USDA fails and there's no fallback, search says so`() = serverTest(services(fallback = false)) {
        usdaAnswer = { MockResponse().setResponseCode(403) }
        val found = adminBrowser().get("/api/foods/search?q=greek%20yogurt").obj()
        assertEquals(0, found.online().size)
        assertEquals("The food database didn't answer; try again in a moment", found.problem())
        assertTrue(offAsked.isEmpty())
    }

    @Test
    fun `with no key USDA isn't asked, and without the fallback search says what it needs`() {
        serverTest(services(key = null, fallback = false)) {
            val browser = adminBrowser()
            val found = browser.get("/api/foods/search?q=banana").obj()
            assertEquals(0, found.online().size)
            assertEquals("Food search needs a free USDA key from your server's admin. Your own foods and Mealie still work.", found.problem())
            val scan = browser.get("/api/foods/barcode/894700010038")
            assertEquals(HttpStatusCode.Conflict, scan.status)
            assertTrue(scan.bodyAsText().contains("Scanning barcodes needs a free USDA key"))
        }
        serverTest(services(key = null, fallback = true)) {
            assertEquals("Plain Greek Yogurt", adminBrowser().get("/api/foods/search?q=yogurt").obj().names().first())
        }
        assertTrue(usdaAsked.isEmpty())
    }

    @Test
    fun `paused by the person or off on the server, USDA isn't asked`() = serverTest(services(fallback = false)) { s ->
        val browser = adminBrowser()
        browser.putJson("/api/privacy/USDA_FOODS", """{"paused":true}""")
        assertTrue(browser.get("/api/foods/search?q=banana").obj().problem()!!.startsWith("USDA food search is paused"))
        browser.putJson("/api/privacy/USDA_FOODS", """{"paused":false}""")
        s.settings.update { it.copy(usdaFoods = false) }
        assertTrue(browser.get("/api/foods/search?q=banana").obj().problem()!!.startsWith("Food search is turned off"))
        assertTrue(usdaAsked.isEmpty())
        s.settings.update { it.copy(usdaFoods = true) }
        assertEquals(8, browser.get("/api/foods/search?q=banana").obj().online().size)
        assertFalse(usdaAsked.isEmpty())
    }

    @Test
    fun `barcodes go to USDA for an exact match first, then Open Food Facts, and a miss is kept a day`() = serverTest(services()) {
        val browser = adminBrowser()
        // Scanned as EAN-13, it's found in its 12-digit form; only the exact match counts.
        val chobani = browser.get("/api/foods/barcode/0894700010038").obj()
        assertEquals("Chobani", chobani.str("brand"))
        assertEquals("894700010038", chobani.str("barcode"))
        assertEquals("USDA", chobani.str("source"))
        assertEquals(listOf("0894700010038", "894700010038"), usdaAsked.map { it.query })
        assertTrue(offAsked.isEmpty())

        // USDA doesn't know it: Open Food Facts does.
        usdaAsked.clear()
        assertEquals("Nutella", browser.get("/api/foods/barcode/3017624010701").obj().str("name"))
        assertEquals(listOf("3017624010701", "03017624010701"), usdaAsked.map { it.query })

        // Nobody knows it: asked again only after a day.
        usdaAsked.clear()
        assertEquals(HttpStatusCode.NotFound, browser.get("/api/foods/barcode/1234567890123").status)
        val asked = usdaAsked.size
        now += 12 * 3600_000L
        assertEquals(HttpStatusCode.NotFound, browser.get("/api/foods/barcode/1234567890123").status)
        assertEquals(asked, usdaAsked.size)
        now += 13 * 3600_000L
        browser.get("/api/foods/barcode/1234567890123")
        assertEquals(asked * 2, usdaAsked.size)
    }
}
