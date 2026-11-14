package com.health.app.e2e

import com.health.api.FoodEntryInput
import com.health.api.FoodSource
import com.health.api.Meal
import com.health.api.MealieConnect
import com.health.api.Profile
import com.health.app.net.ServerApi
import com.health.app.net.ServerException
import com.health.app.net.connectMealie
import com.health.app.net.disconnectMealie
import com.health.app.net.mealiePlan
import com.health.app.net.mealieRecipe
import com.health.app.net.mealieRecipes
import com.health.app.net.mealieStatus
import com.health.app.ui.food.MealieText
import com.health.app.ui.food.PlanAction
import com.health.server.ServerConfig
import com.health.server.Services
import com.health.server.db.Database
import com.health.server.db.Schema
import com.health.server.module
import com.health.server.util.Passwords
import com.health.server.util.SecretBox
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

/**
 * Mealie through the app's own calls against the real server, in-process, with a stand-in Mealie:
 * connecting (a wrong token says why), recipes as foods, the day's plan as the Food tab shows it,
 * logging from it, and disconnecting.
 */
class MealieEndToEndTest {
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var base: String
    private var token: String? = null
    private val api by lazy { ServerApi(OkHttpClient(), { base }, { token }) }
    private val zone = ZoneId.of("Europe/London")
    private val mealieToken = "mealie-token-123"

    private val mealie = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer $mealieToken") return MockResponse().setResponseCode(401).setBody("""{"detail":"Unauthorized"}""")
                val path = request.requestUrl!!.encodedPath
                val json = when (path) {
                    "/api/users/self" -> """{"id":"u1","username":"sam","fullName":"Sam"}"""
                    "/api/app/about" -> """{"version":"v3.1.0"}"""
                    "/api/groups/self" -> """{"id":"g1","slug":"home"}"""
                    "/api/recipes" -> {
                        val q = request.requestUrl!!.queryParameter("search").orEmpty()
                        val all = listOf("lentil-soup" to "Lentil soup", "plain-toast" to "Plain toast")
                        val found = all.filter { it.second.contains(q, ignoreCase = true) }
                        """{"items":[${found.joinToString(",") { """{"id":"id-${it.first}","slug":"${it.first}","name":"${it.second}"}""" }}]}"""
                    }
                    "/api/recipes/lentil-soup" -> """{"id":"id-lentil-soup","slug":"lentil-soup","name":"Lentil soup","recipeServings":4,
                        "nutrition":{"calories":"320 kcal","proteinContent":"18 g","carbohydrateContent":"40 g","fatContent":"6.5 g"}}"""
                    "/api/recipes/plain-toast" -> """{"id":"id-plain-toast","slug":"plain-toast","name":"Plain toast","nutrition":{}}"""
                    "/api/households/mealplans" -> {
                        val day = request.requestUrl!!.queryParameter("start_date")
                        """{"items":[
                            {"date":"$day","entryType":"dinner","title":"","text":"","recipe":{"slug":"lentil-soup","name":"Lentil soup"}},
                            {"date":"$day","entryType":"breakfast","title":"","text":"","recipe":{"slug":"plain-toast","name":"Plain toast"}},
                            {"date":"$day","entryType":"snack","title":"Fruit","text":"Whatever's in the bowl","recipe":null}
                        ]}"""
                    }
                    else -> return MockResponse().setResponseCode(404).setBody("""{"detail":"Not found"}""")
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(json)
            }
        }
    }

    private val site get() = mealie.url("/").toString().trimEnd('/')

    @Before
    fun start() {
        mealie.start()
        val config = ServerConfig(dataDir = Files.createTempDirectory("samh-mealie-e2e").toFile(), adminPassword = "e2e-password")
        val db = Database.inMemory()
        Schema.migrate(db)
        val services = Services(config, db, SecretBox(ByteArray(32) { it.toByte() }), passwords = Passwords(iterations = 1_000))
        runBlocking { services.bootstrap() }
        server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(services) }.start()
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        base = "http://127.0.0.1:$port"
    }

    @After
    fun stop() {
        server.stop(100, 500)
        runCatching { mealie.shutdown() }
    }

    @Test
    fun `connect Mealie, find a recipe, log the day's plan from it, and disconnect`() = runBlocking {
        token = api.login(base, "admin", "e2e-password", "Test phone").token
        api.saveProfile(Profile(timezone = zone.id))
        assertFalse(api.mealieStatus().connected)

        // A wrong token: the server's own sentence, to show as it is.
        try {
            api.connectMealie(MealieConnect(site, "nope"))
            fail()
        } catch (e: ServerException) {
            assertEquals(400, e.code)
            assertTrue(e.message!!, e.message!!.contains("token"))
        }
        assertFalse(api.mealieStatus().connected)

        val status = api.connectMealie(MealieConnect("$site/g/home", mealieToken))
        assertEquals("Connected to $site as Sam", MealieText.connected(status))
        assertEquals("Mealie 3.1.0", MealieText.version(status))
        // A new address alone keeps the token.
        assertTrue(api.connectMealie(MealieConnect(site)).connected)

        // Recipes: one with nutrition (a food, one serving), one without.
        val soup = api.mealieRecipes("soup").items.single()
        val food = checkNotNull(soup.food)
        assertEquals(FoodSource.MEALIE, food.source)
        assertEquals("mealie:id-lentil-soup", food.ref)
        assertEquals(320.0, food.nutrients.calories, 0.0)
        assertEquals("1 serving (the recipe makes 4)", MealieText.servingLine(food, MealieText.makes(food, listOf(soup))))
        assertNull(api.mealieRecipes("toast").items.single().food)
        assertEquals("Lentil soup", api.mealieRecipe("lentil-soup").name)

        // The day's plan, as the card shows it.
        val today = LocalDate.now(zone).toString()
        var rows = MealieText.rows(api.mealiePlan(today))
        assertEquals(listOf(Meal.BREAKFAST, Meal.DINNER, Meal.SNACK), rows.map { it.meal })
        assertEquals(listOf(PlanAction.NONE, PlanAction.ADD, PlanAction.NONE), rows.map { it.action })
        assertEquals("Breakfast · No nutrition in Mealie yet", rows[0].detail)
        assertEquals("Snacks · Whatever's in the bowl", rows[2].detail)

        // Add, as the card does: one serving to its meal. Then it's logged, and one of the person's foods.
        val added = api.addFood(FoodEntryInput(today, rows[1].meal, 1.0, food = rows[1].food))
        assertEquals(Meal.DINNER, added.meal)
        assertEquals(320.0, added.nutrients.calories, 0.0)
        rows = MealieText.rows(api.mealiePlan(today))
        assertEquals(PlanAction.LOGGED, rows[1].action)
        val mine = api.searchFoods("lentil").mine
        assertEquals("mealie:id-lentil-soup", mine.single { it.source == FoodSource.MEALIE }.ref)
        assertTrue(api.recentFoods().any { it.ref == "mealie:id-lentil-soup" })
        // In the search, the copy goes under Mealie's group, not twice.
        val group = MealieText.group(true, "lentil", api.mealieRecipes("lentil"), false)
        assertTrue(MealieText.mine(mine, group).none { it.ref == "mealie:id-lentil-soup" })

        api.disconnectMealie()
        assertFalse(api.mealieStatus().connected)
    }
}
