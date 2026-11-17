package com.health.server

import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.IngestBatch
import com.health.api.Metric
import com.health.api.Sample
import com.health.api.SleepSession
import com.health.api.SleepStage
import com.health.api.StageSpan
import com.health.server.health.RecentService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The last 24 hours as recorded: a ring's night and readings, a phone's steps, one device or both. */
class RecentTest {
    private val hour = 3_600_000L
    private var now = 1_794_470_400_000L

    private val ring = DeviceInfo(DeviceKind.COLMI_RING, "30:36:43:34:C3:01", "COLMI R02_C301")
    private val phone = DeviceInfo(DeviceKind.HEALTH_CONNECT, "com.android.healthconnect.phone", "Pixel")

    private fun s(metric: Metric, at: Long, value: Double, end: Long? = null) = Sample(metric, at, end, value)

    @Test
    fun `the last day as recorded, from every device or one`() = serverTest(testServices(clock = { now })) { s ->
        val me = runBlocking { s.users.byUsername("admin")!!.id }
        val bed = now - 20 * hour
        val night = SleepSession(
            bed, bed + 7 * hour,
            listOf(StageSpan(SleepStage.LIGHT, bed, bed + hour), StageSpan(SleepStage.DEEP, bed + hour, bed + 2 * hour), StageSpan(SleepStage.REM, bed + 2 * hour, bed + 3 * hour), StageSpan(SleepStage.LIGHT, bed + 3 * hour, bed + 7 * hour)),
        )
        runBlocking {
            s.ingest.ingest(
                me,
                IngestBatch(
                    ring,
                    samples = (0 until 24 * 12).map { i -> s(Metric.HEART_RATE, now - 24 * hour + 60_000 + i * 5 * 60_000L, 60.0 + i % 20) } +
                        listOf(s(Metric.SPO2, now - 15 * hour, 96.0), s(Metric.SPO2, now - 14 * hour, 94.0), s(Metric.STRESS, now - 2 * hour, 46.0)) +
                        // The ring counted the same quarter hour's walk as the phone.
                        listOf(s(Metric.STEPS, now - 3 * hour, 800.0, now - 3 * hour + 10 * 60_000)) +
                        // From before the window: left out.
                        listOf(s(Metric.HEART_RATE, now - 30 * hour, 150.0)),
                    sleep = listOf(night),
                ),
            )
            s.ingest.ingest(me, IngestBatch(phone, samples = listOf(s(Metric.STEPS, now - 3 * hour, 1000.0, now - 3 * hour + 10 * 60_000), s(Metric.STEPS, now - hour, 500.0, now - hour + 5 * 60_000))))
        }
        val admin = adminBrowser()
        val all = admin.get("/api/recent").obj()
        assertEquals(24, all["hours"]!!.jsonPrimitive.content.toInt())
        assertEquals(setOf("COLMI R02_C301", "Pixel"), all["devices"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())
        val sleep = all["sleep"]!!.jsonArray.single().jsonObject
        assertEquals(4, sleep["stages"]!!.jsonArray.size)
        val series = all["series"]!!.jsonArray.map { it.jsonObject }.associateBy { it["metric"]!!.jsonPrimitive.content }
        assertEquals(listOf("HEART_RATE", "SPO2", "STRESS", "STEPS"), all["series"]!!.jsonArray.map { it.jsonObject["metric"]!!.jsonPrimitive.content })
        val heart = series["HEART_RATE"]!!
        assertEquals(24 * 12, heart["points"]!!.jsonArray.size)
        assertEquals(79.0, heart["max"]!!.jsonPrimitive.double, 0.0)
        assertEquals(94.0, series["SPO2"]!!["min"]!!.jsonPrimitive.double, 0.0)
        // Steps: the shared quarter hour counted once (the phone's 1,000), then 500.
        val steps = series["STEPS"]!!
        assertEquals(1500.0, steps["total"]!!.jsonPrimitive.double, 0.0)
        assertEquals(60, steps["bucketMinutes"]!!.jsonPrimitive.content.toInt())

        // Just the ring: its night and readings, its own steps.
        val ringId = all["devices"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content.startsWith("COLMI") }["id"]!!.jsonPrimitive.content
        val ringOnly = admin.get("/api/recent?device=$ringId").obj()
        assertEquals(ringId, ringOnly["device"]!!.jsonPrimitive.content)
        assertEquals(1, ringOnly["sleep"]!!.jsonArray.size)
        assertEquals(800.0, ringOnly.series("STEPS")!!["total"]!!.jsonPrimitive.double, 0.0)
        // Just the phone: steps, and no night.
        val phoneId = all["devices"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "Pixel" }["id"]!!.jsonPrimitive.content
        val phoneOnly = admin.get("/api/recent?device=$phoneId").obj()
        assertTrue(phoneOnly["sleep"]!!.jsonArray.isEmpty())
        assertNull(phoneOnly.series("HEART_RATE"))
        // Someone else's device, or none such: not found. A share never reaches it.
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/recent?device=nope").status)
        s.users.create("sam", "Sam", password = "sam-password-123")
        admin.postJson("/api/shares", """{"username":"sam","modules":["SLEEP"]}""")
        val sam = browser().also { it.postJson("/api/auth/login", """{"username":"sam","password":"sam-password-123"}""") }
        assertEquals(HttpStatusCode.Forbidden, sam.get("/api/recent") { header("X-SAMH-View", me) }.status)
    }

    @Test
    fun `a sensor that reads every second is thinned to a few minutes`() {
        val points = (0 until 86_400).map { com.health.api.RecentPoint(it * 1000L, 60.0) }
        val thinned = RecentService.thinned(points)
        assertTrue(thinned.size in 600..RecentService.MAX_POINTS + 1)
    }

    private fun JsonObject.series(metric: String) = this["series"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["metric"]!!.jsonPrimitive.content == metric }
}
