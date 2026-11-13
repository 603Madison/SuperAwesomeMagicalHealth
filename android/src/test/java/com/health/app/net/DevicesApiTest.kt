package com.health.app.net

import com.health.api.ApiJson
import com.health.api.DeviceChoices
import com.health.api.Metric
import com.health.api.MetricChoice
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

class DevicesApiTest {
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
    fun `the person's choices are read and saved, without the view header`() = runBlocking {
        server.enqueue(json("""{"choices":[{"metric":"HRV","deviceId":"ring"}],"sleepDeviceId":null}"""))
        val choices = api.deviceChoices()
        assertEquals("ring", choices.chosenFor(Metric.HRV))
        assertNull(choices.chosenFor(Metric.STEPS))
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("/api/devices/choices", get.path)
        assertNull(get.getHeader(ServerApi.VIEW_HEADER))

        server.enqueue(MockResponse().setResponseCode(204))
        val next = choices.choosing(Metric.STEPS, "watch").choosingSleep("ring")
        assertEquals(next, api.saveDeviceChoices(next))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/devices/choices", put.path)
        assertNull(put.getHeader(ServerApi.VIEW_HEADER))
        assertEquals(next, ApiJson.decodeFromString(DeviceChoices.serializer(), put.body.readUtf8()))
    }

    @Test
    fun `choices are never saved from someone's share`() = runBlocking {
        val viewing = ServerApi(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, { "app-token" }, viewAs = { "owner" })
        try {
            viewing.saveDeviceChoices(DeviceChoices())
            fail()
        } catch (e: ServerException) {
            assertEquals(403, e.code)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `choosing replaces a reading's choice, and null combines again`() {
        val start = DeviceChoices(listOf(MetricChoice(Metric.HRV, "ring"), MetricChoice(Metric.STEPS, "watch")), sleepDeviceId = "ring")
        val watchHrv = start.choosing(Metric.HRV, "watch")
        assertEquals(listOf(MetricChoice(Metric.STEPS, "watch"), MetricChoice(Metric.HRV, "watch")), watchHrv.choices)
        val combined = watchHrv.choosing(Metric.HRV, null)
        assertEquals(listOf(MetricChoice(Metric.STEPS, "watch")), combined.choices)
        assertNull(combined.choosingSleep(null).sleepDeviceId)
        assertEquals("ring", combined.sleepDeviceId)
    }
}
