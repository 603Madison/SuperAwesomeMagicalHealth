package com.fitness.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysTest {
    private val batch = """{"device":{"kind":"OTHER","identifier":"script-1","name":"My script"},
        "samples":[{"metric":"HEART_RATE","start":1767261600000,"value":61}]}"""

    @Test
    fun `an upload key sends data in and opens nothing else`() = serverTest {
        val admin = adminBrowser()
        val key = admin.postJson("/api/devices/upload-keys", """{"name":"Pebble watchapp"}""").obj()
        assertTrue(key.str("url").endsWith("/api/ingest"))
        val token = key.str("token")
        val client = createClient { }

        val sent = client.post("/api/ingest") { bearerAuth(token); json(batch) }
        assertEquals(HttpStatusCode.OK, sent.status)
        assertEquals("1", sent.obj().str("samples"))

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/day/today") { bearerAuth(token) }.status)

        // It's listed with the account's sign-ins, and revoking it stops it.
        val sessions = admin.get("/api/me/sessions").json().jsonArray.map { it.jsonObject }
        val listed = sessions.first { it.str("kind") == "UPLOAD" }
        assertEquals("Pebble watchapp", listed.str("name"))
        admin.delete("/api/me/sessions/${listed.str("id")}") { csrf() }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/ingest") { bearerAuth(token); json(batch) }.status)
    }

    @Test
    fun `connector links open only the MCP endpoint`() = serverTest {
        val admin = adminBrowser()
        val link = admin.postJson("/api/me/connectors", "{}").obj()
        val token = link.str("token")
        assertTrue(link.str("url").endsWith("/mcp/$token"))
        val client = createClient { }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/ingest") { bearerAuth(token); json(batch) }.status)
        val ping = client.post("/mcp/$token") { json("""{"jsonrpc":"2.0","id":1,"method":"ping"}""") }
        assertEquals(HttpStatusCode.OK, ping.status)
        // Anything else at that address is refused.
        assertEquals(HttpStatusCode.NotFound, client.post("/mcp/not-a-real-token-at-all-really") { json("""{"jsonrpc":"2.0","id":1,"method":"ping"}""") }.status)
    }

    @Test
    fun `only admins make every-account links`() = serverTest { services ->
        val admin = adminBrowser()
        admin.postJson("/api/admin/users", """{"username":"sam","password":"sam-password-1"}""")
        val sam = browser()
        sam.post("/api/auth/login") { json("""{"username":"sam","password":"sam-password-1"}""") }
        assertEquals(HttpStatusCode.Forbidden, sam.postJson("/api/me/connectors", """{"allAccounts":true}""").status)
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/me/connectors", """{"allAccounts":true}""").status)
        assertEquals(2L, services.users.count())
    }
}
