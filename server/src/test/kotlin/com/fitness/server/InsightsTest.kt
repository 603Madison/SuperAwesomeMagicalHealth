package com.fitness.server

import com.fitness.api.DeviceInfo
import com.fitness.api.DeviceKind
import com.fitness.api.EnergyProfile
import com.fitness.api.Goals
import com.fitness.api.IngestBatch
import com.fitness.api.Metric
import com.fitness.api.Profile
import com.fitness.api.Sample
import com.fitness.api.SleepSession
import com.fitness.api.SleepStage
import com.fitness.api.StageSpan
import com.fitness.server.insights.DayStats
import com.fitness.server.insights.Energy
import com.fitness.server.insights.Scoring
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

class InsightsTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    /** Ten days of a ring's data, 1 to 10 March 2026 (the clocks go forward on the 8th). */
    private val firstDay = LocalDate.of(2026, 3, 1)
    private val now = at(LocalDate.of(2026, 3, 10), 20)
    private val ring = DeviceInfo(DeviceKind.COLMI_RING, "AA:BB:CC:DD:EE:FF", "Colmi R02", battery = 80)

    private fun ringBatch(restingBump: Map<LocalDate, Double> = emptyMap()): IngestBatch {
        val samples = mutableListOf<Sample>()
        val nights = mutableListOf<SleepSession>()
        for (i in 0 until 10) {
            val date = firstDay.plusDays(i.toLong())
            val bump = restingBump[date] ?: 0.0
            // The night before: 23:00 to 07:10, in stages.
            val bed = at(date.minusDays(1), 23)
            val plan = listOf(SleepStage.AWAKE to 10, SleepStage.LIGHT to 60, SleepStage.DEEP to 50, SleepStage.LIGHT to 60, SleepStage.REM to 40,
                SleepStage.LIGHT to 60, SleepStage.DEEP to 30, SleepStage.REM to 50, SleepStage.LIGHT to 60, SleepStage.REM to 40, SleepStage.AWAKE to 30)
            var t = bed
            val stages = plan.map { (stage, minutes) -> StageSpan(stage, t, t + minutes * 60_000L).also { t += minutes * 60_000L } }
            nights += SleepSession(bed, t, stages)
            // Heart rate every 5 minutes through the night, lowest around 2 am.
            var h = bed
            while (h < t) {
                val hoursIn = (h - bed) / 3_600_000.0
                samples += Sample(Metric.HEART_RATE, h, value = 52 + bump + kotlin.math.abs(hoursIn - 3) * 1.5)
                h += 5 * 60_000L
            }
            samples += Sample(Metric.HRV, bed + 4 * 3_600_000L, value = 48.0 - bump)
            // Days: 250 steps and 12 kcal each quarter hour from 9 to 6, and a daytime heart rate.
            for (q in 0 until 36) {
                val start = at(date, 9) + q * 15 * 60_000L
                samples += Sample(Metric.STEPS, start, start + 15 * 60_000L, 250.0)
                samples += Sample(Metric.ACTIVE_CALORIES, start, start + 15 * 60_000L, 12.0)
                samples += Sample(Metric.HEART_RATE, start, value = 78.0)
            }
        }
        return IngestBatch(ring, samples, nights)
    }

    private fun services() = testServices(clock = { now }).also { s ->
        runBlocking {
            s.bootstrap()
            val admin = s.users.byUsername("admin")!!
            s.profiles.put(admin.id, Profile(birthYear = 1990, heightCm = 168.0, energyProfile = EnergyProfile.FEMALE, timezone = "America/New_York", goals = Goals(steps = 8000, activeCalories = 400)))
        }
    }

    @Test
    fun `a ring's data becomes days with scores, and sending it again changes nothing`() = runBlocking {
        val s = services()
        val user = s.users.byUsername("admin")!!
        val first = s.ingest.ingest(user.id, ringBatch())
        assertEquals(10, first.sleep)
        val before = s.devices.list(user.id).single()
        assertEquals("Colmi R02", before.name)
        assertEquals(80, before.battery)

        s.ingest.ingest(user.id, ringBatch())
        assertEquals(before.samples, s.devices.list(user.id).single().samples)

        val day = s.days.summary(user.id, "2026-03-09")
        assertEquals(9000, day.steps)
        assertEquals(432.0, day.activeCalories, 0.01)
        val night = day.sleepSummary!!
        assertTrue(night.staged)
        assertEquals(80, night.deepMinutes)
        assertEquals(130, night.remMinutes)
        assertEquals(40, night.awakeMinutes)
        assertEquals(10, night.latencyMinutes)
        assertEquals(52.0, day.restingHeartRate!!, 1.0)
        assertEquals(48.0, day.hrv!!, 0.01)
        assertTrue(day.sleep!!.score >= 75)
        assertNotNull(day.readiness)
        assertNotNull(day.activity)
        assertEquals(24, day.hourlySteps.size)
        assertEquals(1000, day.hourlySteps[9])
        assertTrue(day.heartRate.isNotEmpty())
        // Resting energy for a 1990-born, 168 cm woman needs a weight: without one there's no estimate.
        assertNull(day.restingCalories)
        assertEquals(2000, day.nutritionTarget!!.calories)
    }

    @Test
    fun `the day the clocks change still adds up`() = runBlocking {
        val s = services()
        val user = s.users.byUsername("admin")!!
        s.ingest.ingest(user.id, ringBatch())
        // 8 March 2026 is 23 hours long in New York; its steps all still count.
        assertEquals(9000, s.days.summary(user.id, "2026-03-08").steps)
    }

    @Test
    fun `a raised resting heart rate lowers readiness and says so`() = runBlocking {
        val s = services()
        val user = s.users.byUsername("admin")!!
        s.ingest.ingest(user.id, ringBatch(mapOf(LocalDate.of(2026, 3, 10) to 9.0)))
        val calm = s.days.summary(user.id, "2026-03-09")
        val strained = s.days.summary(user.id, "2026-03-10")
        assertTrue(strained.readiness!!.score < calm.readiness!!.score)
        val rhr = strained.readiness!!.contributors.first { it.key == "rhr" }
        assertTrue(rhr.score < 50)
        assertTrue(strained.insights.any { it.title.startsWith("Resting heart rate is up") })
    }

    @Test
    fun `steps come from one device a day, so a phone and a ring don't both count`() = runBlocking {
        val s = services()
        val user = s.users.byUsername("admin")!!
        s.ingest.ingest(user.id, ringBatch())
        val date = LocalDate.of(2026, 3, 9)
        val phone = IngestBatch(DeviceInfo(DeviceKind.PHONE, "pixel", "Pixel"), listOf(Sample(Metric.STEPS, at(date, 12), at(date, 13), 3000.0)))
        s.ingest.ingest(user.id, phone)
        assertEquals(9000, s.days.summary(user.id, "2026-03-09").steps)
        s.ingest.ingest(user.id, phone.copy(samples = listOf(Sample(Metric.STEPS, at(date, 12), at(date, 13), 12000.0))))
        assertEquals(12000, s.days.summary(user.id, "2026-03-09").steps)
    }

    @Test
    fun `readings that can't be right are dropped`() = runBlocking {
        val s = services()
        val user = s.users.byUsername("admin")!!
        val result = s.ingest.ingest(
            user.id,
            IngestBatch(
                ring,
                listOf(
                    Sample(Metric.HEART_RATE, now - 60_000, value = 0.0),
                    Sample(Metric.HEART_RATE, now - 120_000, value = 255.0),
                    Sample(Metric.HEART_RATE, 0, value = 60.0),
                    Sample(Metric.HEART_RATE, now + 86_400_000L, value = 60.0),
                    Sample(Metric.HEART_RATE, now - 180_000, value = 61.0),
                ),
            ),
        )
        assertEquals(1, result.samples)
    }

    @Test
    fun `weights entered by hand feed the calorie target, and can be deleted`() = runBlocking {
        val s = services()
        val user = s.users.byUsername("admin")!!
        s.ingest.ingest(user.id, ringBatch())
        val weight = s.ingest.manualSample(user.id, Sample(Metric.WEIGHT, at(LocalDate.of(2026, 3, 9), 7), value = 64.0))
        val day = s.days.summary(user.id, "2026-03-09")
        assertEquals(64.0, day.weightKg!!, 0.0)
        // 10×64 + 6.25×168 − 5×36 − 161 = 1349
        assertEquals(1349.0, day.restingCalories!!, 0.5)
        assertTrue(day.nutritionTarget!!.basis.startsWith("About"))
        s.ingest.deleteSample(user.id, weight.id!!)
        assertNull(s.days.summary(user.id, "2026-03-09").weightKg)
    }

    @Test
    fun `trends give a row a day over the API`() = serverTest(services()) {
        val admin = adminBrowser()
        val user = it.users.byUsername("admin")!!
        it.ingest.ingest(user.id, ringBatch())
        val response = admin.get("/api/trends?days=7")
        assertEquals(HttpStatusCode.OK, response.status)
        val rows = response.obj()["days"]!!.jsonArray.map { r -> r.jsonObject }
        assertEquals(7, rows.size)
        assertEquals("2026-03-10", rows.last()["date"]!!.jsonPrimitive.content)
        assertEquals(9000, rows.last()["steps"]!!.jsonPrimitive.content.toInt())
        assertNotNull(rows.last()["readiness"])
    }

    @Test
    fun `scores draw straight lines between their points`() {
        assertEquals(50, Scoring.curve(0.5, 0.0 to 0.0, 1.0 to 100.0))
        assertEquals(100, Scoring.curve(2.0, 0.0 to 0.0, 1.0 to 100.0))
        assertEquals(0, Scoring.curve(-1.0, 0.0 to 0.0, 1.0 to 100.0))
        assertEquals("Optimal", Scoring.label(90))
        assertEquals("Pay attention", Scoring.label(40))
    }

    @Test
    fun `resting energy follows the chosen formula, and targets the weight change aimed for`() {
        val date = LocalDate.of(2026, 1, 1)
        val base = Profile(birthYear = 1996, heightCm = 170.0, goals = Goals(weeklyChangeKg = -0.5))
        val female = Energy.restingCalories(base.copy(energyProfile = EnergyProfile.FEMALE), 70.0, date)!!
        val male = Energy.restingCalories(base.copy(energyProfile = EnergyProfile.MALE), 70.0, date)!!
        val average = Energy.restingCalories(base, 70.0, date)!!
        assertEquals(1451.5, female, 0.01)
        assertEquals(1617.5, male, 0.01)
        assertEquals((female + male) / 2, average, 0.01)
        val target = Energy.target(base, 70.0, female, 400.0)
        // Keeping weight: 1451.5 × 1.1 + 400 ≈ 1997; losing half a kilo a week: 550 less.
        assertEquals(1450, target.calories)
        assertEquals(98, target.protein)
        assertTrue(target.basis.contains("lose 0.5 kg a week"))
    }

    @Test
    fun `readiness waits for a baseline`() {
        val day = DayStats("2026-01-10", restingHr = 55.0)
        assertNull(Scoring.readiness(day, emptyList(), Goals(), null))
        val history = (1..9).map { DayStats("2026-01-0$it", restingHr = 55.0) }
        val score = Scoring.readiness(day, history, Goals(), 80)!!
        assertEquals(listOf("rhr", "sleep"), score.contributors.map { it.key })
        assertEquals((92 * 20 + 80 * 20) / 40, score.score)
        assertEquals(86, ((92 * 20 + 80 * 20) / 40.0).roundToInt())
    }
}
