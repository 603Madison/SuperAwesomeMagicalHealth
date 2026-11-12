package com.fitness.server

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AI connector: links from Settings, the MCP handshake, its tools, and what each link may do. */
class McpTest {
    private suspend fun HttpClient.rpc(url: String, method: String, params: String = "{}"): HttpResponse =
        post(url) { json("""{"jsonrpc":"2.0","id":7,"method":"$method","params":$params}""") }

    private suspend fun HttpClient.call(url: String, tool: String, arguments: String = "{}"): JsonObject =
        rpc(url, "tools/call", """{"name":"$tool","arguments":$arguments}""").obj()["result"]!!.jsonObject

    private fun JsonObject.text() = this["content"]!!.jsonArray[0].jsonObject.str("text")

    private fun JsonObject.failed() = this["isError"]!!.jsonPrimitive.boolean

    @Test
    fun `the handshake and a read-only link's tools`() = serverTest {
        val admin = adminBrowser()
        val url = admin.postJson("/api/me/connectors", "{}").obj().str("url").substringAfter("localhost")
        val client = createClient { }

        val hello = client.rpc(url, "initialize", """{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""").obj()["result"]!!.jsonObject
        assertEquals("2025-03-26", hello.str("protocolVersion"))
        assertEquals("superawesomemagicalfitness", hello["serverInfo"]!!.jsonObject.str("name"))
        assertTrue(hello.str("instructions").contains("status"))

        // A notification gets no answer.
        assertEquals(HttpStatusCode.Accepted, client.post(url) { json("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") }.status)

        val tools = client.rpc(url, "tools/list").obj()["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject.str("name") }
        assertTrue(tools.containsAll(listOf("status", "day", "trends", "sleep", "workouts", "food_log", "readings", "devices", "sync_check", "profile")))
        assertFalse("log_food" in tools)

        val status = client.call(url, "status")
        assertFalse(status.failed())
        assertTrue(status.text(), status.text().contains("Account: admin"))
        assertTrue(status.text().contains("missing birth year"))

        val refused = client.rpc(url, "tools/call", """{"name":"log_food","arguments":{"meal":"LUNCH","calories":500}}""").obj()
        assertTrue(refused["error"]!!.jsonObject.str("message").contains("can only read"))
    }

    @Test
    fun `a link that may log, logs`() = serverTest { services ->
        val admin = adminBrowser()
        val url = admin.postJson("/api/me/connectors", """{"allowLogging":true}""").obj().str("url").substringAfter("localhost")
        val client = createClient { }
        val logged = client.call(url, "log_food", """{"meal":"LUNCH","name":"Burrito","calories":650,"protein":32,"date":"2026-02-03"}""")
        assertFalse(logged.text(), logged.failed())
        assertTrue(logged.text().contains("Burrito"))
        client.call(url, "log_water", """{"ml":500,"date":"2026-02-03"}""")
        val weight = client.call(url, "log_weight", """{"lb":150,"at":"2026-02-03T07:30"}""")
        assertTrue(weight.text(), weight.text().contains("68.0 kg"))
        val workout = client.call(url, "log_workout", """{"type":"STRENGTH","start":"2026-02-03T18:00","minutes":45,"effort":7}""")
        assertFalse(workout.text(), workout.failed())
        client.call(url, "check_in", """{"date":"2026-02-03","mood":4,"tags":["late meal"]}""")

        val day = client.call(url, "day", """{"date":"2026-02-03"}""").text()
        assertTrue(day, day.contains("Food: 650 of"))
        assertTrue(day.contains("water 500/"))
        assertTrue(day.contains("Weight: 68.0 kg"))
        assertTrue(day.contains("Workout: Strength"))
        assertTrue(day.contains("mood 4/5"))
        val food = client.call(url, "food_log", """{"date":"2026-02-03"}""").text()
        assertTrue(food, food.contains("Lunch:") && food.contains("Burrito"))
        val user = services.users.byUsername("admin")!!
        runBlocking { assertEquals(1, services.foods.day(user.id, java.time.LocalDate.parse("2026-02-03")).first.size) }
    }

    @Test
    fun `the log is for admins, and other accounts only through an admin's every-account link`() = serverTest {
        val admin = adminBrowser()
        admin.postJson("/api/admin/users", """{"username":"robin","password":"robin-password"}""")
        val robin = browser()
        robin.post("/api/auth/login") { json("""{"username":"robin","password":"robin-password"}""") }
        val robinUrl = robin.postJson("/api/me/connectors", "{}").obj().str("url").substringAfter("localhost")
        val client = createClient { }

        assertTrue(client.call(robinUrl, "server_log").failed())
        assertTrue(client.call(robinUrl, "status", """{"account":"admin"}""").failed())

        val everyone = admin.postJson("/api/me/connectors", """{"allAccounts":true}""").obj().str("url").substringAfter("localhost")
        val status = client.call(everyone, "status", """{"account":"robin"}""")
        assertFalse(status.text(), status.failed())
        assertTrue(status.text().contains("Account: robin"))
        assertTrue(status.text().contains("Accounts:"))
        val log = client.call(everyone, "server_log", """{"contains":"AI connector"}""")
        assertFalse(log.failed())
    }

    @Test
    fun `sync check flags quiet days`() = serverTest { services ->
        val admin = adminBrowser()
        val now = System.currentTimeMillis()
        admin.postJson(
            "/api/ingest",
            """{"device":{"kind":"COLMI_RING","identifier":"AA","name":"Ring"},"samples":[{"metric":"HEART_RATE","start":${now - 60_000},"value":64}]}""",
        )
        val url = admin.postJson("/api/me/connectors", "{}").obj().str("url").substringAfter("localhost")
        val check = createClient { }.call(url, "sync_check", """{"days":3}""").text()
        assertTrue(check, check.contains("Ring [Colmi ring]"))
        assertTrue(check.contains("← nothing"))
        assertEquals(1L, services.users.count())
    }
}
