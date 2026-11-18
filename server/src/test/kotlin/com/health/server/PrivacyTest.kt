package com.health.server

import com.health.api.DataPath
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where data can go, and each way paused by the person: nothing goes until it's resumed. */
class PrivacyTest {
    private fun JsonObject.destinations() = this["destinations"]!!.jsonArray.map { it.jsonObject }.associateBy { it["path"]!!.jsonPrimitive.content }
    private fun JsonObject.state(path: DataPath) = destinations().getValue(path.name)["state"]!!.jsonPrimitive.content

    @Test
    fun `every way out is listed, and pausing one stops it`() = serverTest { s ->
        val me = runBlocking { s.users.byUsername("admin")!!.id }
        val admin = adminBrowser()
        val map = admin.get("/api/privacy").obj()
        assertEquals(
            listOf(
                "FOOD_DATABASE", "USDA_FOODS", "HEALTH_RECORDS", "WEATHER", "MEALIE", "AI_ASSISTANT", "AI_CONNECTORS", "WEBHOOKS", "BROWSER_PUSH",
                "EMAIL", "SHARES", "HOME_ASSISTANT",
            ),
            map["destinations"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content },
        )
        assertEquals("ON", map.state(DataPath.FOOD_DATABASE))
        // USDA needs a key, and medical records an app registered with Epic or athenahealth: neither is set up here.
        assertEquals("NOT_SET_UP", map.state(DataPath.USDA_FOODS))
        assertEquals("OFF_ON_SERVER", map.state(DataPath.HEALTH_RECORDS))
        assertEquals("NOT_SET_UP", map.state(DataPath.MEALIE))
        assertEquals("OFF_ON_SERVER", map.state(DataPath.AI_ASSISTANT))
        assertTrue(map["note"]!!.jsonPrimitive.content.startsWith("Everything else stays on this server"))

        // The food database: asked (and, in tests, unreachable) until paused, then never asked.
        assertNotNull(admin.get("/api/foods/search?q=banana").obj()["onlineError"]?.takeIf { it.toString() != "null" })
        val paused = admin.putJson("/api/privacy/FOOD_DATABASE", """{"paused":true}""").obj()
        assertEquals("PAUSED", paused.state(DataPath.FOOD_DATABASE))
        assertNull(admin.get("/api/foods/search?q=banana").obj()["onlineError"]?.takeIf { it.toString() != "null" })
        assertFalse(runBlocking { s.foodDatabaseFor(me) })
        assertEquals("ON", admin.putJson("/api/privacy/FOOD_DATABASE", """{"paused":false}""").obj().state(DataPath.FOOD_DATABASE))
        assertTrue(runBlocking { s.foodDatabaseFor(me) })

        // Signing in can't be paused; a way out that doesn't exist isn't one.
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/privacy/SIGN_IN", """{"paused":true}""").status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/privacy/CARRIER_PIGEON", """{"paused":true}""").status)

        // AI connector links: refused while paused.
        val link = admin.postJson("/api/me/connectors", "{}").obj()["url"]!!.jsonPrimitive.content.substringAfter("localhost")
        val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
        assertEquals(HttpStatusCode.OK, client.post(link) { json(init) }.status)
        admin.putJson("/api/privacy/AI_CONNECTORS", """{"paused":true}""")
        assertEquals(HttpStatusCode.Forbidden, client.post(link) { json(init) }.status)
        assertEquals("1", admin.get("/api/privacy").obj().destinations().getValue("AI_CONNECTORS")["count"]!!.jsonPrimitive.content)

        // Webhooks: nothing is sent while paused.
        val hook = admin.postJson("/api/webhooks", """{"name":"Home","url":"http://127.0.0.1:9/hook","events":["MORNING"]}""").obj()["id"]!!.jsonPrimitive.content
        admin.putJson("/api/privacy/WEBHOOKS", """{"paused":true}""")
        val delivery = admin.post("/api/webhooks/$hook/test") { csrf() }.obj()
        assertTrue(delivery["error"]!!.jsonPrimitive.content.contains("paused"))
        assertEquals(listOf("127.0.0.1"), admin.get("/api/privacy").obj().destinations().getValue("WEBHOOKS")["hosts"]!!.jsonArray.map { it.jsonPrimitive.content })

        // Shares: the viewer sees nothing, and isn't offered the account.
        runBlocking { s.users.create("sam", "Sam", password = "sam-password-123") }
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/shares", """{"username":"sam","modules":["ACTIVITY"]}""").status)
        val viewer = browser().also { it.post("/api/auth/login") { json("""{"username":"sam","password":"sam-password-123"}""") } }
        assertEquals(HttpStatusCode.OK, viewer.get("/api/day/today") { header("X-SAMH-View", me) }.status)
        admin.putJson("/api/privacy/SHARES", """{"paused":true}""")
        assertEquals(HttpStatusCode.Forbidden, viewer.get("/api/day/today") { header("X-SAMH-View", me) }.status)
        assertTrue(viewer.get("/api/shares").obj()["received"]!!.jsonArray.isEmpty())

        // The map itself is never shared.
        admin.putJson("/api/privacy/SHARES", """{"paused":false}""")
        assertEquals(HttpStatusCode.Forbidden, viewer.get("/api/privacy") { header("X-SAMH-View", me) }.status)

        // Pauses are the person's own, kept sealed.
        assertEquals(setOf(DataPath.AI_CONNECTORS, DataPath.WEBHOOKS), runBlocking { s.privacy.paused(me) })
    }
}
