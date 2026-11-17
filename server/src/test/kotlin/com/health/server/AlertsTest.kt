package com.health.server

import com.health.api.AlertKind
import com.health.api.AlertLevel
import com.health.api.AlertSettings
import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.IngestBatch
import com.health.api.Metric
import com.health.api.Sample
import com.health.api.SleepSession
import com.health.api.SleepSummary
import com.health.server.health.StoredSample
import com.health.server.insights.AlertRules
import com.health.server.insights.DayStats
import com.health.server.insights.DeviceRef
import com.health.server.util.Days
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Health alerts: what's worth a heads-up, what isn't, and how it reaches the person. */
class AlertsTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = 1_794_470_400_000L // 2026-11-12 08:00 UTC
    private val zone = Days(ZoneOffset.UTC)

    private val ring = DeviceRef("ring", "Colmi R02", DeviceKind.COLMI_RING)
    private val strap = DeviceRef("strap", "Polar H10", DeviceKind.HEART_RATE_STRAP)
    private val devices = listOf(ring, strap).associateBy { it.id }
    private var nextId = 1L
    private fun s(device: DeviceRef, metric: Metric, at: Long, value: Double, end: Long? = null) = StoredSample(nextId++, device.id, device.name, false, metric, at, end, value)

    /** Every five minutes from [from] for [minutes]. */
    private fun every5(device: DeviceRef, metric: Metric, from: Long, minutes: Int, value: Double) = (0..minutes / 5).map { s(device, metric, from + it * 5 * minute, value) }

    private fun input(
        samples: List<StoredSample>,
        sleep: List<SleepSession> = emptyList(),
        workouts: List<LongRange> = emptyList(),
        days: List<DayStats> = emptyList(),
        luteal: Boolean = false,
        settings: AlertSettings = AlertSettings(),
    ) = AlertRules.Input(now, samples, sleep, workouts, devices, days, luteal, settings, zone)

    @Test
    fun `a heart rate that stays high at rest, and how serious it is`() {
        val spell = now - 3 * hour
        // Asleep, a ring at 130 for half an hour: worth telling a doctor.
        val night = SleepSession(spell - hour, spell + 2 * hour)
        val ringOnly = AlertRules.detect(input(every5(ring, Metric.HEART_RATE, spell, 30, 130.0), sleep = listOf(night)))
        val alert = ringOnly.single()
        assertEquals(AlertKind.HIGH_HEART_RATE, alert.kind)
        assertEquals(AlertLevel.DOCTOR, alert.level)
        assertTrue(alert.body, alert.body.contains("while you were asleep"))
        assertTrue(alert.advice.contains("emergency"))
        // A strap at 160 for 15 minutes: could need care now.
        val fast = (0..15).map { s(strap, Metric.HEART_RATE, spell + it * minute, 160.0) }
        assertEquals(AlertLevel.URGENT, AlertRules.detect(input(fast, sleep = listOf(night))).single().level)
        // A single odd reading doesn't break the spell, and two readings don't make one.
        val blip = every5(ring, Metric.HEART_RATE, spell, 30, 130.0).mapIndexed { i, x -> if (i == 3) x.copy(value = 90.0) else x }
        assertEquals(1, AlertRules.detect(input(blip, sleep = listOf(night))).size)
        assertTrue(AlertRules.detect(input(every5(ring, Metric.HEART_RATE, spell, 5, 130.0), sleep = listOf(night))).isEmpty())
    }

    @Test
    fun `not while walking, working out or getting your breath back`() {
        val spell = now - 3 * hour
        val heart = every5(ring, Metric.HEART_RATE, spell, 30, 130.0)
        val walking = (0..3).map { s(ring, Metric.STEPS, spell + it * 10 * minute, 1000.0, spell + (it + 1) * 10 * minute) }
        assertTrue(AlertRules.detect(input(heart + walking)).isEmpty())
        assertTrue(AlertRules.detect(input(heart, workouts = listOf(spell - 20 * minute..spell + 10 * minute))).isEmpty())
        val stairs = listOf(s(ring, Metric.STEPS, spell - 15 * minute, 900.0, spell - 2 * minute))
        assertTrue(AlertRules.detect(input(heart + stairs + listOf(s(ring, Metric.STEPS, spell, 20.0, spell + 30 * minute)))).isEmpty())
        // Sitting still with the steps counted: it counts, and says so.
        val still = listOf(s(ring, Metric.STEPS, spell - hour, 30.0, spell + hour))
        assertTrue(AlertRules.detect(input(heart + still)).single().body.contains("while you were still"))
        // Above the person's own line only.
        assertTrue(AlertRules.detect(input(heart + still, settings = AlertSettings(highHeartRate = 140))).isEmpty())
    }

    @Test
    fun `a low heart rate while awake, and only a very low one asleep`() {
        val spell = now - 5 * hour
        val low = every5(ring, Metric.HEART_RATE, spell, 20, 37.0)
        assertEquals(AlertLevel.DOCTOR, AlertRules.detect(input(low)).single().level)
        val night = SleepSession(spell - hour, spell + hour)
        assertTrue(AlertRules.detect(input(low, sleep = listOf(night))).isEmpty())
        val veryLow = every5(ring, Metric.HEART_RATE, spell, 20, 28.0)
        assertEquals(AlertLevel.WATCH, AlertRules.detect(input(veryLow, sleep = listOf(night))).single().level)
    }

    @Test
    fun `a strap's irregular rhythm, and fast with it`() {
        val start = now - 4 * hour
        fun windows(vararg shares: Double) = shares.mapIndexed { i, v -> s(strap, Metric.IRREGULAR_RHYTHM, start + i * 5 * minute, v, start + (i + 1) * 5 * minute) }
        val alert = AlertRules.detect(input(windows(80.0, 100.0, 0.0, 70.0, 90.0))).single()
        assertEquals(AlertKind.IRREGULAR_RHYTHM, alert.kind)
        assertEquals(AlertLevel.DOCTOR, alert.level)
        assertTrue(alert.body.contains("4 of 5"))
        // Two stretches aren't enough, nor three far apart.
        assertTrue(AlertRules.detect(input(windows(80.0, 100.0, 0.0, 0.0, 0.0))).isEmpty())
        assertTrue(AlertRules.detect(input(windows(80.0, 0.0, 0.0, 0.0, 90.0, 0.0, 0.0, 0.0, 70.0))).isEmpty())
        // With the heart racing: could need care now.
        val racing = (0..25).map { s(strap, Metric.HEART_RATE, start + it * minute, 140.0) }
        assertEquals(AlertLevel.URGENT, AlertRules.detect(input(windows(80.0, 100.0, 70.0, 90.0) + racing, settings = AlertSettings(highHeartRate = 180))).single().level)
        // Turned off: nothing.
        assertTrue(AlertRules.detect(input(windows(80.0, 100.0, 70.0, 90.0), settings = AlertSettings(rhythm = false))).isEmpty())
    }

    @Test
    fun `blood oxygen dips at night, and two nights make it worth a doctor's look`() {
        fun night(endHoursAgo: Int) = SleepSession(now - (endHoursAgo + 8) * hour, now - endHoursAgo * hour)
        fun readings(n: SleepSession, vararg values: Double) = values.mapIndexed { i, v -> s(ring, Metric.SPO2, n.start + (i + 1) * hour, v) }
        val last = night(3)
        val one = AlertRules.detect(input(readings(last, 96.0, 89.0, 88.0, 95.0, 87.0, 96.0), sleep = listOf(last)))
        assertEquals(AlertLevel.WATCH, one.single().level)
        assertEquals(AlertKind.OXYGEN_DIPS, one.single().kind)
        // An earlier night this week with dips of its own (told about at the time).
        val before = night(40)
        val two = AlertRules.detect(input(readings(last, 96.0, 89.0, 88.0, 87.0) + readings(before, 88.0, 89.0, 90.0, 97.0), sleep = listOf(before, last)))
        assertEquals(AlertLevel.DOCTOR, two.single().level)
        assertTrue(two.single().title.contains("2 nights"))
        // One dip in a night isn't a pattern.
        assertTrue(AlertRules.detect(input(readings(last, 96.0, 89.0, 95.0, 96.0, 97.0, 95.0), sleep = listOf(last))).isEmpty())
        // Awake, two low readings in a row.
        val awake = listOf(s(ring, Metric.SPO2, now - hour, 86.0), s(ring, Metric.SPO2, now - 50 * minute, 85.0))
        assertEquals(AlertKind.LOW_OXYGEN, AlertRules.detect(input(awake)).single().kind)
    }

    private fun day(date: LocalDate, rhr: Double, hrv: Double, breathing: Double, temp: Double? = null): DayStats {
        val end = zone.start(date) + 7 * hour
        return DayStats(
            date = date.toString(), restingHr = rhr, hrv = hrv, respiratoryRate = breathing, tempDeviation = temp,
            sleep = SleepSummary(end - 8 * hour, end, 450, 480, 94, 80, 90, 280, 30, true),
        )
    }

    @Test
    fun `the signs of coming down with something, against the person's own usual`() {
        val today = zone.today(now)
        val usual = (28 downTo 1).map { k -> day(today.minusDays(k.toLong()), 55.0 + k % 3, 50.0 + k % 4, 14.0 + (k % 2) * 0.2, 0.0) }
        assertTrue(AlertRules.detect(input(emptyList(), days = usual + day(today, 56.0, 51.0, 14.1, 0.1))).isEmpty())
        val sick = AlertRules.detect(input(emptyList(), days = usual + day(today, 63.0, 38.0, 15.6, 0.5))).single()
        assertEquals(AlertKind.GETTING_SICK, sick.kind)
        assertEquals(AlertLevel.WATCH, sick.level)
        assertEquals(4, sick.evidence.size)
        // In the second half of a cycle, a warmer, faster heart alone is expected.
        assertTrue(AlertRules.detect(input(emptyList(), days = usual + day(today, 61.0, 50.0, 14.1, 0.5), luteal = true)).isEmpty())
        // Not enough nights yet to know the usual: nothing.
        assertTrue(AlertRules.detect(input(emptyList(), days = usual.takeLast(4) + day(today, 63.0, 38.0, 15.6, 0.5))).none { it.kind == AlertKind.GETTING_SICK })
        // Turned off.
        assertTrue(AlertRules.detect(input(emptyList(), days = usual + day(today, 63.0, 38.0, 15.6, 0.5), settings = AlertSettings(illness = false))).isEmpty())
    }

    @Test
    fun `resting heart rate up three days running`() {
        val today = zone.today(now)
        val usual = (30 downTo 3).map { k -> day(today.minusDays(k.toLong()), 55.0, 50.0, 14.0) }
        val up = (2 downTo 0).map { k -> day(today.minusDays(k.toLong()), 67.0, 50.0, 14.0) }
        assertTrue(AlertRules.detect(input(emptyList(), days = usual + up)).any { it.kind == AlertKind.RESTING_HEART_RISE })
        val twoDays = usual + day(today.minusDays(2), 58.0, 50.0, 14.0) + up.drop(1)
        assertTrue(AlertRules.detect(input(emptyList(), days = twoDays)).none { it.kind == AlertKind.RESTING_HEART_RISE })
    }

    @Test
    fun `alerts through the API are kept once, shown on Today, put away, and never shared`() {
        var clock = now
        serverTest(testServices(clock = { clock })) { s ->
            val me = runBlocking { s.users.byUsername("admin")!!.id }
            val ringInfo = DeviceInfo(DeviceKind.COLMI_RING, "30:36:43:34:C3:01", "COLMI R02_C301")
            val spell = now - 2 * hour
            runBlocking {
                s.ingest.ingest(
                    me,
                    IngestBatch(
                        ringInfo,
                        samples = (0..8).map { Sample(Metric.HEART_RATE, spell + it * 5 * minute, value = 128.0) } + Sample(Metric.STEPS, spell - hour, spell + hour, 40.0),
                    ),
                )
            }
            val admin = adminBrowser()
            val first = admin.get("/api/alerts").obj()
            val alert = first["alerts"]!!.jsonArray.single().jsonObject
            assertEquals("HIGH_HEART_RATE", alert.str("kind"))
            assertEquals("DOCTOR", alert.str("level"))
            assertTrue(first["limits"]!!.jsonPrimitive.content.contains("not a diagnosis"))
            assertEquals("Heart rhythm", first["watching"]!!.jsonArray[1].jsonObject.str("what"))
            assertTrue(first["watching"]!!.jsonArray[1].jsonObject.str("how").contains("chest strap"))
            // The same spell, looked at again later, is still one alert.
            clock += 5 * minute
            assertEquals(1, admin.get("/api/alerts").obj()["alerts"]!!.jsonArray.size)

            // On Today, at the top, and as a notification once.
            val today = admin.get("/api/day/today").obj()
            assertEquals("Your heart rate stayed high at rest", today["insights"]!!.jsonArray.first().jsonObject.str("title"))
            val profile = runBlocking { s.profiles.get(me) }
            val due = runBlocking { s.alerts.reminders(me, profile, clock - 15 * minute, clock + minute) }
            assertEquals(listOf("alert:${alert.str("id")}:DOCTOR"), due.map { it.key })
            assertEquals("A health alert", due.single().privateTitle)
            // And worth asking about at an appointment.
            val questions = runBlocking { s.alerts.questions(me, profile, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)) }
            assertTrue(questions.single().text.contains("high heart rate at rest"))

            // Someone viewing a share doesn't see it.
            runBlocking { s.users.create("sam", "Sam", password = "sam-password-123") }
            admin.postJson("/api/shares", """{"username":"sam","modules":["ACTIVITY","VITALS"]}""")
            val viewer = browser().also { it.post("/api/auth/login") { json("""{"username":"sam","password":"sam-password-123"}""") } }
            val seen = viewer.get("/api/day/today") { header("X-SAMH-View", me) }.obj()
            assertTrue(seen["insights"]!!.jsonArray.none { it.jsonObject.str("title").contains("heart rate stayed high") })
            assertEquals(HttpStatusCode.Forbidden, viewer.get("/api/alerts") { header("X-SAMH-View", me) }.status)

            // Put away with a reason: off Today, kept in the list.
            val dismissed = admin.postJson("/api/alerts/${alert.str("id")}/dismiss", """{"feedback":"ACTIVE"}""").obj()
            assertEquals("ACTIVE", dismissed.str("feedback"))
            assertNotNull(dismissed["dismissedAt"])
            assertTrue(admin.get("/api/day/today").obj()["insights"]!!.jsonArray.none { it.jsonObject.str("title").contains("heart rate stayed high") })
            assertEquals(1, admin.get("/api/alerts").obj()["alerts"]!!.jsonArray.size)
            assertEquals(HttpStatusCode.NotFound, admin.post("/api/alerts/nope/seen") { csrf() }.status)

            // Settings stay within sense.
            val saved = admin.putJson("/api/alerts/settings", """{"highHeartRate":300,"lowHeartRate":10,"lowOxygen":99}""").obj()
            assertEquals(180, saved["highHeartRate"]!!.jsonPrimitive.content.toInt())
            assertEquals(30, saved["lowHeartRate"]!!.jsonPrimitive.content.toInt())
            assertEquals(94, saved["lowOxygen"]!!.jsonPrimitive.content.toInt())
        }
    }

    private fun kotlinx.serialization.json.JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
}
