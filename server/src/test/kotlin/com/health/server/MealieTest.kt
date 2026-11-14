package com.health.server

import com.health.api.ApiJson
import com.health.api.Meal
import com.health.api.Micronutrient
import com.health.server.food.MealieService
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
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

/** Mealie: connecting with a token, recipes as foods with their nutrition, the day's plan, and who never sees it. */
class MealieTest {
    private val token = "mealie-token-123"
    private val asked = CopyOnWriteArrayList<RecordedRequest>()
    private var households = true

    private val mealie = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked += request
                if (request.getHeader("Authorization") != "Bearer $token") return MockResponse().setResponseCode(401).setBody("""{"detail":"Unauthorized"}""")
                val path = request.requestUrl!!.encodedPath
                val json = when {
                    path == "/api/users/self" -> """{"id":"u1","username":"madison","fullName":"Madison","email":"someone@example.com"}"""
                    path == "/api/app/about" -> """{"version":"v3.1.0","production":true}"""
                    path == "/api/groups/self" -> """{"id":"g1","name":"Home","slug":"home"}"""
                    path == "/api/recipes" -> {
                        val q = request.requestUrl!!.queryParameter("search").orEmpty()
                        val all = listOf("lentil-soup" to "Lentil soup", "chicken-wrap" to "Chicken wrap", "plain-toast" to "Plain toast")
                        val found = all.filter { it.second.contains(q, ignoreCase = true) }
                        """{"page":1,"per_page":10,"total":${found.size},"items":[${found.joinToString(",") { """{"id":"id-${it.first}","slug":"${it.first}","name":"${it.second}"}""" }}]}"""
                    }
                    path == "/api/recipes/lentil-soup" -> """{"id":"id-lentil-soup","slug":"lentil-soup","name":"Lentil soup","recipeServings":4,
                        "nutrition":{"calories":"320 kcal","proteinContent":"18 g","carbohydrateContent":"40g","fatContent":"6,5 g","fiberContent":"11 g",
                        "sodiumContent":"650 mg","saturatedFatContent":"1 g","cholesterolContent":"0","transFatContent":null,"sugarContent":""}}"""
                    path == "/api/recipes/chicken-wrap" -> """{"id":"id-chicken-wrap","slug":"chicken-wrap","name":"Chicken wrap","recipeServings":0,"recipeYieldQuantity":2,
                        "nutrition":{"proteinContent":"32","carbohydrateContent":"45","fatContent":"14","sodiumContent":"1.2 g"}}"""
                    path == "/api/recipes/plain-toast" -> """{"id":"id-plain-toast","slug":"plain-toast","name":"Plain toast","nutrition":{"calories":null}}"""
                    path == "/api/households/mealplans" && households -> plan(request)
                    path == "/api/groups/mealplans" && !households -> plan(request)
                    else -> return MockResponse().setResponseCode(404).setBody("""{"detail":"Not found"}""")
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(json)
            }

            private fun plan(request: RecordedRequest): String {
                val day = request.requestUrl!!.queryParameter("start_date")
                return """{"page":1,"per_page":50,"total":3,"items":[
                    {"id":1,"date":"$day","entryType":"dinner","title":"","text":"","recipeId":"id-lentil-soup","recipe":{"id":"id-lentil-soup","slug":"lentil-soup","name":"Lentil soup"}},
                    {"id":2,"date":"$day","entryType":"breakfast","title":"","text":"","recipeId":"id-plain-toast","recipe":{"id":"id-plain-toast","slug":"plain-toast","name":"Plain toast"}},
                    {"id":3,"date":"$day","entryType":"dessert","title":"Fruit","text":"Whatever's in the bowl","recipeId":null,"recipe":null}
                ]}"""
            }
        }
        start()
    }

    @After fun stop() { runCatching { mealie.shutdown() } }

    private val site get() = mealie.url("/").toString().trimEnd('/')

    @Test
    fun `nutrition comes from Mealie's text, per serving`() {
        val n = MealieService.nutrients(ApiJson.parseToJsonElement("""{"calories":"1,250 kcal","proteinContent":"1,5 g","fatContent":"2 g","sodiumContent":"0.4 g","cholesterolContent":"30 mg","transFatContent":"0.2 g"}""").jsonObject)!!
        assertEquals(1250.0, n.calories, 0.0)
        assertEquals(1.5, n.protein, 0.0)
        assertEquals(400.0, n.sodiumMg!!, 0.0)
        assertEquals(30.0, n.micros[Micronutrient.CHOLESTEROL]!!, 0.0)
        assertEquals(0.2, n.micros[Micronutrient.TRANS_FAT]!!, 0.0)
        // Kilojoules become kilocalories; only macronutrients, and calories come from them.
        assertEquals(239.0, MealieService.nutrients(ApiJson.parseToJsonElement("""{"calories":"1000 kJ"}""").jsonObject)!!.calories, 0.0)
        assertEquals(4 * 10 + 4 * 20 + 9 * 5.0, MealieService.nutrients(ApiJson.parseToJsonElement("""{"proteinContent":"10","carbohydrateContent":"20","fatContent":"5"}""").jsonObject)!!.calories, 0.0)
        assertNull(MealieService.nutrients(ApiJson.parseToJsonElement("""{"calories":null,"fiberContent":""}""").jsonObject))
        // Both units given: the kilocalories. European thousands, for calories and milligrams.
        assertEquals(250.0, MealieService.nutrients(ApiJson.parseToJsonElement("""{"calories":"1046 kJ / 250 kcal"}""").jsonObject)!!.calories, 0.0)
        assertEquals(1234.0, MealieService.nutrients(ApiJson.parseToJsonElement("""{"calories":"1.234 kcal","sodiumContent":"1.200 mg"}""").jsonObject)!!.calories, 0.0)
        assertEquals(1200.0, MealieService.nutrients(ApiJson.parseToJsonElement("""{"calories":"300","sodiumContent":"1.200 mg"}""").jsonObject)!!.sodiumMg!!, 0.0)
        // A decimal stays a decimal (calories are whole).
        assertEquals(13.0, MealieService.nutrients(ApiJson.parseToJsonElement("""{"calories":"12.5 kcal"}""").jsonObject)!!.calories, 0.0)
        // However it's pasted, it's the site.
        assertEquals("http://mealie.home:9000", MealieService.site("mealie.home:9000/"))
        assertEquals("https://food.example.org/mealie", MealieService.site("https://food.example.org/mealie/g/home/r/lentil-soup?x=1"))
        assertEquals("https://food.example.org", MealieService.site("https://food.example.org/api/"))
        assertEquals(Meal.DINNER, MealieService.mealOf("side"))
        assertEquals(Meal.SNACK, MealieService.mealOf("dessert"))
    }

    @Test
    fun `connect with a token, find recipes as foods, log one, and see it on the plan`() = serverTest { s ->
        val browser = adminBrowser()
        assertFalse(browser.get("/api/mealie").obj()["connected"]!!.jsonPrimitive.boolean)
        // A wrong token says so, and nothing's saved.
        val wrong = browser.putJson("/api/mealie", """{"url":"$site","token":"nope"}""")
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertTrue(wrong.obj().str("error").contains("token"))
        assertFalse(browser.get("/api/mealie").obj()["connected"]!!.jsonPrimitive.boolean)

        val connected = browser.putJson("/api/mealie", """{"url":"$site/g/home","token":"$token"}""").obj()
        assertTrue(connected["connected"]!!.jsonPrimitive.boolean)
        assertEquals(site, connected.str("url"))
        assertEquals("Madison", connected.str("user"))
        assertEquals("v3.1.0", connected.str("version"))
        // The token never comes back, and Mealie's other details about the person aren't kept.
        val status = browser.get("/api/mealie").obj().toString()
        assertFalse(status.contains(token))
        assertFalse(status.contains("example.com"))
        // Changing the address alone keeps the token, at the same Mealie.
        assertEquals(HttpStatusCode.OK, browser.putJson("/api/mealie", """{"url":"$site/g/home"}""").status)
        // A different Mealie needs the token again: the saved one never goes anywhere new.
        val elsewhere = browser.putJson("/api/mealie", """{"url":"http://localhost:${mealie.port + 1}"}""")
        assertEquals(HttpStatusCode.BadRequest, elsewhere.status)
        assertTrue(elsewhere.obj().str("error").contains("again"))

        val found = browser.get("/api/mealie/recipes?q=soup").obj()["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Lentil soup"), found.map { it.str("name") })
        val soup = found[0]
        assertEquals(4.0, soup["servings"]!!.jsonPrimitive.double, 0.0)
        assertEquals("$site/g/home/r/lentil-soup", soup.str("link"))
        val food = soup["food"]!!.jsonObject
        assertEquals("MEALIE", food.str("source"))
        assertEquals("mealie:id-lentil-soup", food.str("ref"))
        val n = food["nutrients"]!!.jsonObject
        assertEquals(320.0, n["calories"]!!.jsonPrimitive.double, 0.0)
        assertEquals(6.5, n["fat"]!!.jsonPrimitive.double, 0.0)
        assertEquals(650.0, n["sodiumMg"]!!.jsonPrimitive.double, 0.0)
        // Calories from the macronutrients; sodium in grams becomes milligrams.
        val wrap = browser.get("/api/mealie/recipes/chicken-wrap").obj()
        assertEquals(2.0, wrap["servings"]!!.jsonPrimitive.double, 0.0)
        assertEquals(434.0, wrap["food"]!!.jsonObject["nutrients"]!!.jsonObject["calories"]!!.jsonPrimitive.double, 0.0)
        assertEquals(1200.0, wrap["food"]!!.jsonObject["nutrients"]!!.jsonObject["sodiumMg"]!!.jsonPrimitive.double, 0.0)
        // Without nutrition in Mealie, there's nothing to log yet.
        val toast = browser.get("/api/mealie/recipes?q=toast").obj()["items"]!!.jsonArray[0].jsonObject
        assertTrue(toast["food"] == null || toast["food"] is kotlinx.serialization.json.JsonNull)
        // Short searches don't ask Mealie.
        val before = asked.size
        assertTrue(browser.get("/api/mealie/recipes?q=s").obj()["items"]!!.jsonArray.isEmpty())
        assertEquals(before, asked.size)

        val date = s.profiles.days(s.users.byUsername("admin")!!.id).today(s.clock()).toString()
        var plan = browser.get("/api/mealie/plan?date=$date").obj()["entries"]!!.jsonArray.map { it.jsonObject }
        // In the day's order: breakfast, dinner, then the dessert with snacks.
        assertEquals(listOf("BREAKFAST", "DINNER", "SNACK"), plan.map { it.str("meal") })
        assertEquals(listOf("Plain toast", "Lentil soup", "Fruit"), plan.map { it.str("title") })
        assertEquals("Whatever's in the bowl", plan[2].str("note"))
        assertTrue(plan.none { it["logged"]!!.jsonPrimitive.boolean })

        // Logging the soup: it joins the person's foods, known again next time.
        val logged = browser.postJson("/api/food/entries", """{"date":"$date","meal":"DINNER","servings":1,"food":$food}""")
        assertEquals(HttpStatusCode.OK, logged.status)
        assertEquals(320.0, logged.obj()["nutrients"]!!.jsonObject["calories"]!!.jsonPrimitive.double, 0.0)
        browser.postJson("/api/food/entries", """{"date":"$date","meal":"DINNER","servings":2,"food":$food}""")
        val mine = browser.get("/api/foods/recent").json().jsonArray.map { it.jsonObject }.filter { it.str("name") == "Lentil soup" }
        assertEquals(1, mine.size)
        assertEquals("mealie:id-lentil-soup", mine[0].str("ref"))
        assertEquals(2, mine[0]["uses"]!!.jsonPrimitive.content.toInt())
        plan = browser.get("/api/mealie/plan?date=$date").obj()["entries"]!!.jsonArray.map { it.jsonObject }
        assertTrue(plan.first { it.str("title") == "Lentil soup" }["logged"]!!.jsonPrimitive.boolean)
        assertFalse(plan.first { it.str("title") == "Plain toast" }["logged"]!!.jsonPrimitive.boolean)

        // An older Mealie (before households) has the group's plan.
        households = false
        assertEquals(3, browser.get("/api/mealie/plan?date=$date").obj()["entries"]!!.jsonArray.size)
        households = true

        // Disconnected: nothing more is asked of Mealie.
        assertEquals(HttpStatusCode.OK, browser.delete("/api/mealie") { csrf() }.status)
        val after = asked.size
        val gone = browser.get("/api/mealie/recipes?q=soup").obj()
        assertEquals(MealieService.NOT_CONNECTED, gone.str("problem"))
        assertEquals(after, asked.size)
    }

    @Test
    fun `a Mealie that's gone says so, and a share never reaches Mealie`() = serverTest { s ->
        val me = s.users.byUsername("admin")!!.id
        val browser = adminBrowser()
        browser.putJson("/api/mealie", """{"url":"$site","token":"$token"}""")
        s.users.create("sam", "Sam", password = "sam-password-123")
        assertEquals(HttpStatusCode.OK, browser.postJson("/api/shares", """{"username":"sam","modules":["NUTRITION"]}""").status)
        val sam = browser().also { it.postJson("/api/auth/login", """{"username":"sam","password":"sam-password-123"}""") }
        // Food is shared; Mealie (and its token) never is.
        assertEquals(HttpStatusCode.OK, sam.get("/api/food/today") { header("X-SAMH-View", me) }.status)
        for (path in listOf("/api/mealie", "/api/mealie/recipes?q=soup", "/api/mealie/plan")) {
            assertEquals(path, HttpStatusCode.Forbidden, sam.get(path) { header("X-SAMH-View", me) }.status)
        }
        // Sam's own Mealie isn't connected.
        assertFalse(sam.get("/api/mealie").obj()["connected"]!!.jsonPrimitive.boolean)

        mealie.shutdown()
        val plan = browser.get("/api/mealie/plan").obj()
        assertTrue(plan.str("problem").startsWith("Mealie didn't answer"))
        assertTrue(plan["entries"]!!.jsonArray.isEmpty())
        val search = browser.get("/api/mealie/recipes?q=wrap").obj()
        assertTrue(search.str("problem").startsWith("Mealie didn't answer"))
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
}
