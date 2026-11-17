package com.health.app.net

import com.health.api.ApiJson
import com.health.api.CompareKey
import com.health.api.DataPath
import com.health.api.DataPathChange
import com.health.api.DataPathState
import com.health.api.DeviceKind
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Comparing two devices and Where your data goes: always the person's own, never with the view header. */
class CompareAndPrivacyApiTest {
    private val server = MockWebServer()
    private lateinit var api: ServerApi

    @Before
    fun start() {
        server.start()
        api = ServerApi(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, { "app-token" })
    }

    @After
    fun stop() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `two devices are compared by their ids, over the nights asked for`() = runBlocking {
        server.enqueue(
            json(
                """{"days":7,"from":"2026-11-06","to":"2026-11-12",
                   "a":{"id":"strap","name":"Polar H10","kind":"HEART_RATE_STRAP"},"b":{"id":"ring","name":"COLMI R02","kind":"COLMI_RING"},
                   "devices":[{"id":"strap","name":"Polar H10","kind":"HEART_RATE_STRAP"},{"id":"ring","name":"COLMI R02","kind":"COLMI_RING"},{"id":"x","name":"New","kind":"SOMETHING_NEW"}],
                   "metrics":[{"key":"NIGHT_HEART_RATE","count":4,"a":55.8,"b":58.9,"difference":3.1,"apart":3.4,"lowest":-0.6,"highest":6.8}],
                   "nights":[{"date":"2026-11-12","values":[{"key":"HRV","a":54.0,"b":47.0,"pairs":12}]}]}""",
            ),
        )
        val c = api.compareDevices("strap", "ring one", 7)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/devices/compare?a=strap&b=ring%20one&days=7", request.path)
        assertNull(request.getHeader(ServerApi.VIEW_HEADER))
        assertEquals(DeviceKind.HEART_RATE_STRAP, c.a?.kind)
        assertEquals(CompareKey.NIGHT_HEART_RATE, c.metrics.single().key)
        assertEquals(3.1, c.metrics.single().difference, 0.0)
        assertEquals(54.0, c.nights.single().values.single().a!!, 0.0)
        // A kind this app doesn't know yet reads as none, not as a failure.
        assertNull(c.devices.last().kind)

        server.enqueue(json("""{"days":14,"from":"2026-10-30","to":"2026-11-12"}"""))
        assertNull(api.compareDevices().a)
        assertEquals("/api/devices/compare?days=14", server.takeRequest().path)
    }

    @Test
    fun `where data goes is read, and each way out paused or resumed`() = runBlocking {
        val map = """{"destinations":[
            {"path":"MEALIE","name":"Mealie","who":"Your own Mealie","hosts":["mealie.home"],"what":"Recipe searches","whenSent":"When you search","state":"PAUSED","setUp":"settings.connections"},
            {"path":"SIGN_IN","name":"Signing in","who":"The sign-in service","what":"That you're signing in","whenSent":"When you sign in","state":"ON","pausable":false}
            ],"note":"Everything else stays on this server."}"""
        server.enqueue(json(map))
        val read = api.dataMap()
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("/api/privacy", get.path)
        assertNull(get.getHeader(ServerApi.VIEW_HEADER))
        assertEquals(DataPathState.PAUSED, read.destinations.first().state)
        assertEquals(listOf("mealie.home"), read.destinations.first().hosts)
        assertEquals(false, read.destinations.last().pausable)

        server.enqueue(json(map.replace("\"PAUSED\"", "\"ON\"")))
        val after = api.pauseDataPath(DataPath.MEALIE, paused = false)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/privacy/MEALIE", put.path)
        assertEquals(DataPathChange(false), ApiJson.decodeFromString(DataPathChange.serializer(), put.body.readUtf8()))
        assertEquals(DataPathState.ON, after.destinations.first().state)
    }

    @Test
    fun `an older server says it needs updating, plainly`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("""{"error":"Not found"}"""))
        try {
            api.dataMap()
            fail()
        } catch (e: ServerException) {
            assertEquals("Your server needs updating for Where your data goes.", e.message)
        }
    }

    @Test
    fun `nothing is paused from someone's share`() = runBlocking {
        val viewing = ServerApi(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, { "app-token" }, viewAs = { "owner" })
        try {
            viewing.pauseDataPath(DataPath.MEALIE, paused = true)
            fail()
        } catch (e: ServerException) {
            assertEquals(403, e.code)
        }
        assertEquals(0, server.requestCount)
    }
}
