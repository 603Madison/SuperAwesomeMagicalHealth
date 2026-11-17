package com.health.server

import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.IngestBatch
import com.health.api.Metric
import com.health.api.Sample
import com.health.api.SleepSession
import com.health.api.SleepStage
import com.health.api.StageSpan
import com.health.server.health.StoredSample
import com.health.server.insights.Combine
import com.health.server.insights.DeviceRef
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** A chest strap as the reference for heart rate and HRV, and two devices compared night by night. */
class StrapCompareTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    private val ring = DeviceRef("ring", "Colmi R02", DeviceKind.COLMI_RING)
    private val strap = DeviceRef("strap", "Polar H10 1A2B3C4D", DeviceKind.HEART_RATE_STRAP)
    private val devices = listOf(ring, strap).associateBy { it.id }
    private var nextId = 1L
    private fun sample(device: DeviceRef, metric: Metric, start: Long, value: Double) = StoredSample(nextId++, device.id, device.name, false, metric, start, null, value)

    @Test
    fun `a strap is worn on the chest, whatever app it comes through`() {
        assertEquals(Combine.Placement.CHEST, Combine.placement(DeviceKind.HEART_RATE_STRAP, "Anything"))
        assertEquals(Combine.Placement.CHEST, Combine.placement(DeviceKind.GADGETBRIDGE, "Polar H10 1A2B3C4D"))
        assertEquals(Combine.Placement.CHEST, Combine.placement(DeviceKind.HEALTH_CONNECT, "Garmin HRM-Dual"))
        assertEquals(Combine.Placement.CHEST, Combine.placement(DeviceKind.OTHER, "Wahoo TICKR X"))
        // Polar's watches are still watches.
        assertEquals(Combine.Placement.WRIST, Combine.placement(DeviceKind.HEALTH_CONNECT, "Polar Vantage V3"))
        assertEquals(mapOf("ring" to "ring", "strap" to "strap"), Combine.shortNames(devices.values))
        // It counts for twice a good fit for resting heart rate and HRV, and little for the rest.
        assertEquals(2.0, Combine.suits(Metric.HRV, DeviceKind.HEART_RATE_STRAP, "H10"), 0.0)
        assertEquals(2.0, Combine.suits(Metric.RESTING_HEART_RATE, DeviceKind.HEART_RATE_STRAP, "H10"), 0.0)
        assertTrue(Combine.suits(Metric.SPO2, DeviceKind.HEART_RATE_STRAP, "H10") < Combine.suits(Metric.SPO2, DeviceKind.COLMI_RING, "R02"))
    }

    @Test
    fun `where a strap measured, its heart rate is used alone`() {
        val start = 1_794_470_400_000L
        val ringHeart = (0 until 12).map { sample(ring, Metric.HEART_RATE, start + it * 5 * minute, 60.0) }
        // The strap is on for the first half hour only.
        val strapHeart = (0 until 30).map { sample(strap, Metric.HEART_RATE, start + it * minute, 66.0) }
        val line = Combine.line(ringHeart + strapHeart, prefer = Combine.straps(devices))
        assertEquals(12, line.size)
        assertTrue(line.take(6).all { it.v == 66.0 })
        assertTrue(line.drop(6).all { it.v == 60.0 })
        val heart = Combine.heart(ringHeart + strapHeart, devices, null)
        assertEquals(63.0, heart.avg!!, 1e-9)
        assertTrue(heart.combined!!.how, heart.combined!!.how.contains("the strap where it measured"))
        // Without a strap, the plain average as before.
        assertTrue(Combine.line(ringHeart + strapHeart).take(6).all { it.v == 63.0 })
    }

    @Test
    fun `two devices side by side, over only the time both measured`() {
        val now = 1_794_470_400_000L
        serverTest(testServices(clock = { now })) { s ->
            val me = runBlocking { s.users.byUsername("admin")!!.id }
            val d = runBlocking { s.profiles.days(me) }
            val today = d.today(now)
            val ringInfo = DeviceInfo(DeviceKind.COLMI_RING, "30:36:43:34:C3:01", "COLMI R02_C301")
            val strapInfo = DeviceInfo(DeviceKind.HEART_RATE_STRAP, "A0:9E:1A:11:22:33", "Polar H10 1A2B3C4D")
            runBlocking {
                for (k in 1..3) {
                    val date = today.minusDays(k.toLong())
                    val bed = d.at(date.minusDays(1), LocalTime.of(23, 0))
                    val wake = d.at(date, LocalTime.of(7, 0))
                    val beats = ((bed until wake step 5 * minute)).mapIndexed { i, t -> t to 52.0 + i % 6 }
                    val night = SleepSession(bed, wake, listOf(StageSpan(SleepStage.LIGHT, bed, bed + 2 * hour), StageSpan(SleepStage.DEEP, bed + 2 * hour, bed + 3 * hour), StageSpan(SleepStage.LIGHT, bed + 3 * hour, wake)))
                    // The ring reads 3 bpm high and HRV 10 ms low, a minute or two after the strap.
                    s.ingest.ingest(
                        me,
                        IngestBatch(
                            ringInfo,
                            samples = beats.map { (t, v) -> Sample(Metric.HEART_RATE, t + minute, value = v + 3) } +
                                (bed until wake step 30 * minute).map { Sample(Metric.HRV, it + 2 * minute, value = 40.0) } +
                                // Before the strap went on: not compared.
                                listOf(Sample(Metric.HEART_RATE, bed - hour, value = 90.0)),
                            sleep = listOf(night),
                        ),
                    )
                    s.ingest.ingest(
                        me,
                        IngestBatch(strapInfo, samples = beats.map { (t, v) -> Sample(Metric.HEART_RATE, t, value = v) } + (bed until wake step 30 * minute).map { Sample(Metric.HRV, it, value = 50.0) }),
                    )
                }
            }
            val admin = adminBrowser()
            // Without a choice: the strap is the one to compare against.
            val c = admin.get("/api/devices/compare?days=7").obj()
            assertEquals("Polar H10 1A2B3C4D", c["a"]!!.jsonObject.str("name"))
            assertEquals("COLMI R02_C301", c["b"]!!.jsonObject.str("name"))
            val metrics = c["metrics"]!!.jsonArray.map { it.jsonObject }.associateBy { it.str("key") }
            val heart = metrics.getValue("NIGHT_HEART_RATE")
            assertEquals(3, heart["count"]!!.jsonPrimitive.int)
            assertEquals(3.0, heart["difference"]!!.jsonPrimitive.double, 1e-9)
            assertEquals(3.0, heart["apart"]!!.jsonPrimitive.double, 1e-9)
            assertEquals(3.0, metrics.getValue("LOWEST_HEART_RATE")["difference"]!!.jsonPrimitive.double, 1e-9)
            assertEquals(-10.0, metrics.getValue("HRV")["difference"]!!.jsonPrimitive.double, 1e-9)
            // Only the ring tracked sleep: nothing to compare there.
            assertNull(metrics["ASLEEP"])
            val nights = c["nights"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf(today.minusDays(1), today.minusDays(2), today.minusDays(3)).map { it.toString() }, nights.map { it.str("date") })
            val first = nights.first()
            assertNull(first["a"])
            assertEquals(3, first["b"]!!.jsonObject["stages"]!!.jsonArray.size)
            val asleep = first["values"]!!.jsonArray.map { it.jsonObject }.first { it.str("key") == "ASLEEP" }
            assertNull(asleep["a"])
            assertEquals(480.0, asleep["b"]!!.jsonPrimitive.double, 1e-9)
            // Fell asleep at 11 pm: five hours after 6 pm.
            assertEquals(300.0, first["values"]!!.jsonArray.map { it.jsonObject }.first { it.str("key") == "BEDTIME" }["b"]!!.jsonPrimitive.double, 1e-9)
            val pairs = first["values"]!!.jsonArray.map { it.jsonObject }.first { it.str("key") == "NIGHT_HEART_RATE" }["pairs"]!!.jsonPrimitive.int
            assertEquals(96, pairs)

            // Chosen the other way round, the differences turn round too.
            val ids = c["devices"]!!.jsonArray.map { it.jsonObject }.associate { it.str("name") to it.str("id") }
            val turned = admin.get("/api/devices/compare?a=${ids["COLMI R02_C301"]}&b=${ids["Polar H10 1A2B3C4D"]}&days=7").obj()
            assertEquals(-3.0, turned["metrics"]!!.jsonArray.map { it.jsonObject }.first { it.str("key") == "NIGHT_HEART_RATE" }["difference"]!!.jsonPrimitive.double, 1e-9)
            assertEquals(HttpStatusCode.BadRequest, admin.get("/api/devices/compare?a=${ids["COLMI R02_C301"]}&b=${ids["COLMI R02_C301"]}").status)
            assertEquals(HttpStatusCode.NotFound, admin.get("/api/devices/compare?a=nope").status)
        }
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
}
