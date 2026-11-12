package com.fitness.app.net

import com.fitness.api.DeviceInfo
import com.fitness.api.DeviceKind
import com.fitness.api.IngestBatch
import com.fitness.api.Metric
import com.fitness.api.Sample
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ServerApiTest {
    private val server = MockWebServer()
    private var token: String? = "app-token"
    private var signedOut = 0
    private lateinit var api: ServerApi

    @Before
    fun start() {
        server.start()
        api = ServerApi(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, { token }, { signedOut++ })
    }

    @After
    fun stop() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `the sign-in settings are asked for without a token`() = runBlocking {
        server.enqueue(json("""{"serverName":"Home","version":"0.1.0","localLogin":true,"oidc":{"label":"Sign in with Authentik"}}"""))
        val config = api.authConfig()
        assertEquals("Sign in with Authentik", config.oidc?.label)
        val request = server.takeRequest()
        assertEquals("/api/auth/config", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `a password sign-in asks for an app token`() = runBlocking {
        server.enqueue(json("""{"user":{"id":"u1","username":"maddie","displayName":"Maddie","role":"USER","createdAt":1},"token":"t0k3n"}"""))
        val signedIn = api.login(server.url("/").toString().trimEnd('/'), "maddie", "pw", "Pixel 9")
        assertEquals("t0k3n", signedIn.token)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"app\":true"))
        assertTrue(body.contains("\"deviceName\":\"Pixel 9\""))
    }

    @Test
    fun `requests carry the app's token, and dates go in the path`() = runBlocking {
        server.enqueue(json("""{"date":"2026-11-12","steps":4200,"goals":{}}"""))
        val day = api.day("2026-11-12")
        assertEquals(4200, day.steps)
        val request = server.takeRequest()
        assertEquals("/api/day/2026-11-12", request.path)
        assertEquals("Bearer app-token", request.getHeader("Authorization"))
    }

    @Test
    fun `the server's own words come back as the error, and 401 signs out`() = runBlocking {
        server.enqueue(json("""{"error":"Sign in first"}""", 401))
        try {
            api.me()
            fail()
        } catch (e: ServerException) {
            assertEquals(401, e.code)
            assertEquals("Sign in first", e.message)
        }
        assertEquals(1, signedOut)
    }

    @Test
    fun `an unknown barcode is null, not an error`() = runBlocking {
        server.enqueue(json("""{"error":"Not found"}""", 404))
        assertNull(api.barcode("4006381333931"))
        assertEquals("/api/foods/barcode/4006381333931", server.takeRequest().path)
    }

    @Test
    fun `batches go to the ingest endpoint as the shared models`() = runBlocking {
        server.enqueue(json("""{"deviceId":"d1","samples":1,"sleep":0,"workouts":0}"""))
        val result = api.ingest(IngestBatch(DeviceInfo(DeviceKind.COLMI_RING, "AA:BB", "R02"), samples = listOf(Sample(Metric.HEART_RATE, 1000, value = 61.0))))
        assertEquals(1, result.samples)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/ingest", request.path)
        assertTrue(request.body.readUtf8().contains("\"COLMI_RING\""))
    }

    @Test
    fun `an unreachable server is code 0`() = runBlocking {
        server.shutdown()
        try {
            api.devices()
            fail()
        } catch (e: ServerException) {
            assertEquals(0, e.code)
        }
    }

    @Test
    fun `addresses are made whole`() {
        assertEquals("https://fitness.local", ServerApi.normalize("fitness.local/"))
        assertEquals("http://10.0.0.5:8080", ServerApi.normalize(" http://10.0.0.5:8080 "))
        assertNull(ServerApi.normalize(""))
        assertNull(ServerApi.normalize("not a server"))
    }

    @Test
    fun `the PKCE challenge is RFC 7636's`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", SignIn.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
}
