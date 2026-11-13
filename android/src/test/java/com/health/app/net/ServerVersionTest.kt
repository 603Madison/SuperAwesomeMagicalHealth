package com.health.app.net

import com.health.api.FieldChoices
import com.health.api.Meal
import com.health.api.ScreenLayout
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The server's version against the app's, and an older server meeting what's new. */
class ServerVersionTest {
    @Test
    fun `an older major or minor version is older, patches and newer servers aren't`() {
        assertTrue(ServerVersion.isOlder("0.2.0", "0.3.0"))
        assertTrue(ServerVersion.isOlder("0.2.9", "0.3.0"))
        assertTrue(ServerVersion.isOlder("v0.2.0", "0.3.0"))
        assertTrue(ServerVersion.isOlder("0.9.4", "1.0.0"))
        assertFalse(ServerVersion.isOlder("0.3.0", "0.3.0"))
        assertFalse(ServerVersion.isOlder("0.3.0", "0.3.2"))
        assertFalse(ServerVersion.isOlder("0.3.5", "0.3.0"))
        assertFalse(ServerVersion.isOlder("0.4.0", "0.3.0"))
        assertFalse(ServerVersion.isOlder("1.0.0", "0.9.0"))
        assertFalse(ServerVersion.isOlder("0.3.0-beta1", "0.3.0"))
        // Unknown versions never ask for an update.
        assertFalse(ServerVersion.isOlder(null, "0.3.0"))
        assertFalse(ServerVersion.isOlder("dev", "0.3.0"))
        assertFalse(ServerVersion.isOlder("0.2.0", "dev"))
        assertEquals(0 to 12, ServerVersion.majorMinor("0.12.3"))
        assertNull(ServerVersion.majorMinor(""))
    }

    @Test
    fun `the notice says which versions, plainly`() {
        assertEquals(
            "Your server is on 0.2.0 and this app is 0.3.0: some things (like turning water off and arranging screens) need the server updated.",
            ServerVersion.notice("0.2.0", "0.3.0"),
        )
        assertTrue(ServerVersion.HELP_URL.endsWith("#updating"))
        assertEquals("0.2.0>0.3.0", ServerVersion.key("0.2.0", "0.3.0"))
    }

    private val server = MockWebServer()
    private lateinit var api: ServerApi

    @Before
    fun start() {
        server.start()
        api = ServerApi(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, { "app-token" })
    }

    @After
    fun stop() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private suspend fun message(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: ServerException) {
            return e.message.orEmpty()
        }
        fail("Expected the server to refuse")
        return ""
    }

    @Test
    fun `the server's version comes from its health check, without signing in`() = runBlocking {
        server.enqueue(json("""{"ok":true,"version":"0.2.0"}"""))
        assertEquals("0.2.0", api.serverHealth().version)
        val request = server.takeRequest()
        assertEquals("/api/health", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `an older server's 404s and unknown fields read as needing an update`() = runBlocking {
        server.enqueue(json("""{"error":"Not found"}""", 404))
        assertEquals("Your server needs updating to arrange screens.", message { api.saveScreenLayout("today", ScreenLayout(listOf("FOOD"))) })
        server.enqueue(json("""{"error":"Not found"}""", 404))
        assertEquals("Your server needs updating to arrange screens.", message { api.layout() })
        server.enqueue(json("""{"error":"Unknown fields: nutrition.water"}""", 400))
        assertEquals("Your server needs updating for this setting.", message { api.saveFieldChoices(FieldChoices(hide = setOf("nutrition.water"))) })
        // An old server reads "suggestions" as a date.
        server.enqueue(json("""{"error":"Dates look like 2026-11-13"}""", 400))
        assertEquals("Your server needs updating for meal ideas.", message { api.foodSuggestions("2026-11-13", Meal.DINNER) })
        val paths = List(4) { server.takeRequest().path }
        assertEquals(listOf("/api/layout/today", "/api/layout", "/api/fields", "/api/food/suggestions?date=2026-11-13&meal=DINNER"), paths)
        // Anything else is as the server says.
        server.enqueue(json("""{"error":"Up to 80 sections"}""", 400))
        assertEquals("Up to 80 sections", message { api.saveScreenLayout("today", ScreenLayout(listOf("FOOD"))) })
        server.enqueue(json("""{"error":"Up to 80 sections"}""", 400))
        assertEquals("Up to 80 sections", message { api.saveFieldChoices(FieldChoices()) })
    }
}
