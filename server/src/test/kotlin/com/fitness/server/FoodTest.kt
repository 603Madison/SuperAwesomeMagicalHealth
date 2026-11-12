package com.fitness.server

import com.fitness.server.food.OpenFoodFacts
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodTest {
    private val offRequests = mutableListOf<String>()
    private val off = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                offRequests += request.path.orEmpty()
                return when {
                    request.path!!.startsWith("/search") -> MockResponse().setBody(
                        """{"hits":[
                            {"code":"0894700010137","brands":["Chobani"],"product_name":"Nonfat Greek Yogurt","serving_size":"1 cup (170 g)","serving_quantity":170,
                             "nutriments":{"energy-kcal_100g":52.9,"proteins_100g":9.41,"carbohydrates_100g":3.53,"fat_100g":0,"sodium_100g":0.0382}},
                            {"code":"1","product_name":"No nutrition facts"}]}""",
                    )
                    request.path!!.startsWith("/product/3017624010701") -> MockResponse().setBody(
                        """{"product":{"code":"3017624010701","brands":"Ferrero, Nutella","product_name":"Nutella","nutriments":{"energy_100g":2228,"proteins_100g":6.3,"carbohydrates_100g":57.5,"fat_100g":30.9}}}""",
                    )
                    else -> MockResponse().setResponseCode(404).setBody("""{"status":0}""")
                }
            }
        }
        start()
    }

    @After
    fun stop() = off.shutdown()

    private fun services() = testServices(foodUrls = OpenFoodFacts.Urls(off.url("/search").toString(), off.url("/product/").toString()))

    @Test
    fun `products become foods per serving, and kilojoules turn into kilocalories`() {
        val yogurt = OpenFoodFacts.toFood(Json.parseToJsonElement("""{"code":"0894700010137","brands":["Chobani"],"product_name":"Nonfat Greek Yogurt","serving_size":"1 cup (170 g)","serving_quantity":170,"nutriments":{"energy-kcal_100g":52.9,"proteins_100g":9.41}}""").jsonObject)!!
        assertEquals("1 cup (170 g)", yogurt.serving)
        assertEquals(89.9, yogurt.nutrients.calories, 0.05)
        assertEquals(16.0, yogurt.nutrients.protein, 0.05)
        assertEquals("Chobani", yogurt.brand)
        val nutella = OpenFoodFacts.toFood(Json.parseToJsonElement("""{"product_name":"Nutella","brands":"Ferrero, Nutella","nutriments":{"energy_100g":2228}}""").jsonObject)!!
        assertEquals("100 g", nutella.serving)
        assertEquals(532.5, nutella.nutrients.calories, 0.1)
        assertEquals("Ferrero", nutella.brand)
        assertNull(OpenFoodFacts.toFood(Json.parseToJsonElement("""{"product_name":"Water"}""").jsonObject))
    }

    @Test
    fun `search, log, and the day adds up against its target`() = serverTest(services()) {
        val browser = adminBrowser()
        val found = browser.get("/api/foods/search?q=greek%20yogurt").obj()
        val online = found["online"]!!.jsonArray
        assertEquals(1, online.size)
        val yogurt = online[0].jsonObject

        val logged = browser.postJson("/api/food/entries", """{"date":"2026-02-01","meal":"BREAKFAST","servings":2,"food":$yogurt}""")
        assertEquals(HttpStatusCode.OK, logged.status)
        assertEquals(179.9, logged.obj()["nutrients"]!!.jsonObject["calories"]!!.jsonPrimitive.content.toDouble(), 0.1)
        browser.postJson("/api/food/entries", """{"date":"2026-02-01","meal":"SNACK","name":"Apple","nutrients":{"calories":95,"carbs":25}}""")
        browser.postJson("/api/water", """{"date":"2026-02-01","ml":500}""")

        val day = browser.get("/api/food/2026-02-01").obj()
        assertEquals(2, day["entries"]!!.jsonArray.size)
        assertEquals(274.9, day["totals"]!!.jsonObject["calories"]!!.jsonPrimitive.content.toDouble(), 0.1)
        assertEquals(500, day["waterMl"]!!.jsonPrimitive.content.toInt())
        assertEquals(2000, day["target"]!!.jsonObject["calories"]!!.jsonPrimitive.content.toInt())

        // Logged once, the yogurt is now the person's own: found first, without asking the database again.
        val recent = browser.get("/api/foods/recent").json().jsonArray
        assertEquals("Nonfat Greek Yogurt", recent[0].jsonObject.str("name"))
        val asked = offRequests.size
        val again = browser.get("/api/foods/search?q=greek%20yogurt").obj()
        assertEquals(1, again["mine"]!!.jsonArray.size)
        assertEquals(0, again["online"]!!.jsonArray.size)
        assertEquals(asked, offRequests.size) // the database's answer was kept

        // Copy breakfast to the next day, then delete an entry.
        val copied = browser.postJson("/api/food/copy", """{"from":"2026-02-01","to":"2026-02-02","meal":"BREAKFAST"}""").obj()
        assertEquals("1", copied.str("copied"))
        val next = browser.get("/api/food/2026-02-02").obj()["entries"]!!.jsonArray
        assertEquals(1, next.size)
        assertEquals(HttpStatusCode.OK, browser.delete("/api/food/entries/${next[0].jsonObject.str("id")}") { csrf() }.status)
        assertEquals(0, browser.get("/api/food/2026-02-02").obj()["entries"]!!.jsonArray.size)
    }

    @Test
    fun `barcodes are looked up, and unknown ones say so`() = serverTest(services()) {
        val browser = adminBrowser()
        val nutella = browser.get("/api/foods/barcode/3017624010701")
        assertEquals(HttpStatusCode.OK, nutella.status)
        assertEquals("Nutella", nutella.obj().str("name"))
        assertEquals(HttpStatusCode.NotFound, browser.get("/api/foods/barcode/1234567890123").status)
    }

    @Test
    fun `turning the food database off keeps searches on the server`() = serverTest(services()) { s ->
        s.settings.update { it.copy(foodDatabase = false) }
        val browser = adminBrowser()
        val found = browser.get("/api/foods/search?q=yogurt").obj()
        assertEquals(0, found["online"]!!.jsonArray.size)
        assertTrue(offRequests.isEmpty())
    }
}
