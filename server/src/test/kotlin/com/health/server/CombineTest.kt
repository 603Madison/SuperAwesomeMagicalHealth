package com.health.server

import com.health.api.CombinedValue
import com.health.api.DaySummary
import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.EnergyProfile
import com.health.api.Goals
import com.health.api.IngestBatch
import com.health.api.Metric
import com.health.api.Module
import com.health.api.Profile
import com.health.api.Sample
import com.health.api.SleepSession
import com.health.api.SleepStage
import com.health.api.SourceValue
import com.health.api.StageSpan
import com.health.api.Units
import com.health.server.care.SharedView
import com.health.server.health.StoredSample
import com.health.server.insights.Combine
import com.health.server.insights.DayStats
import com.health.server.insights.DeviceReading
import com.health.server.insights.DeviceRef
import com.health.server.mcp.McpServer
import io.ktor.client.request.delete
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt

/** A ring and a watch measuring the same things, and the app's own numbers from both. */
class CombineTest {
    // ---- The pure functions ----

    private val ring = DeviceRef("ring", "Colmi R02", DeviceKind.COLMI_RING)
    private val watch = DeviceRef("watch", "Pebble Time 2", DeviceKind.PEBBLE)
    private val devices = listOf(ring, watch).associateBy { it.id }
    private val dayStart = LocalDate.of(2026, 3, 9).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val dayEnd = dayStart + 24 * HOUR
    private var nextId = 1L

    private fun sample(device: DeviceRef, metric: Metric, start: Long, value: Double, end: Long? = null) =
        StoredSample(nextId++, device.id, device.name, false, metric, start, end, value)

    /** [value] steps each quarter hour from [fromHour] to [toHour]. */
    private fun quarters(device: DeviceRef, fromHour: Int, toHour: Int, value: Double) = (fromHour * 4 until toHour * 4).map { q ->
        val start = dayStart + q * QUARTER
        sample(device, Metric.STEPS, start, value, start + QUARTER)
    }

    private fun steps(samples: List<StoredSample>, chosen: String? = null) = Combine.counts(Metric.STEPS, samples, devices, chosen, dayStart, dayEnd)!!

    @Test
    fun `counts don't double, the same walk on two devices is counted once`() {
        val counted = steps(quarters(ring, 9, 10, 250.0) + quarters(watch, 9, 10, 240.0))
        assertEquals(1000.0, counted.total, 1e-6)
        val combined = counted.combined!!
        assertEquals(1000.0, combined.value, 1e-6)
        assertEquals(listOf(1.0, 0.0), combined.sources.map { it.weight })
        assertEquals(listOf(1000.0, 960.0), combined.sources.map { it.value })
        assertEquals("The ring's count: it counted more than the watch every 15 minutes", combined.how)
    }

    @Test
    fun `a device off the wrist doesn't lose the steps the other counted`() {
        // The ring came off at 10; the watch kept counting until 11.
        val counted = steps(quarters(ring, 9, 10, 200.0) + quarters(watch, 9, 11, 180.0))
        assertEquals(800.0 + 720.0, counted.total, 1e-6)
        assertEquals(800.0, (36 until 40).sumOf { counted.slots[it] }, 1e-6)
        assertEquals(720.0, (40 until 44).sumOf { counted.slots[it] }, 1e-6)
        val combined = counted.combined!!
        assertEquals("The ring's count each 15 minutes, or the watch's where it counted more", combined.how)
        assertEquals(800.0 / 1520, combined.sources.first { it.deviceId == "ring" }.weight, 1e-9)
    }

    @Test
    fun `a day's total from one device only tops up what the other counted`() {
        val phone = DeviceRef("hc", "Health Connect", DeviceKind.HEALTH_CONNECT)
        val all = devices + (phone.id to phone)
        val ringDay = quarters(ring, 9, 18, 250.0)
        fun total(value: Double) = Combine.counts(Metric.STEPS, ringDay + sample(phone, Metric.STEPS, dayStart, value, dayEnd), all, null, dayStart, dayEnd)!!
        // Fewer than the ring counted: nothing added.
        assertEquals(9000.0, total(8000.0).total, 1e-6)
        // More: the difference goes where the ring counted nothing.
        val more = total(12000.0)
        assertEquals(12000.0, more.total, 1e-6)
        assertEquals(250.0, more.slots[40], 1e-6)
        assertTrue(more.slots[0] > 0)
        // One device's long readings add up, even when they don't fall on the quarter hours.
        val twoHours = 2 * HOUR
        val alone = Combine.counts(
            Metric.STEPS,
            listOf(sample(phone, Metric.STEPS, dayStart + 9 * HOUR + 10 * MINUTE, 1000.0, dayStart + 9 * HOUR + 10 * MINUTE + twoHours),
                sample(phone, Metric.STEPS, dayStart + 11 * HOUR + 10 * MINUTE, 1000.0, dayStart + 11 * HOUR + 10 * MINUTE + twoHours)),
            all, null, dayStart, dayEnd,
        )!!
        assertEquals(2000.0, alone.total, 1e-6)
        assertNull(alone.combined)
    }

    @Test
    fun `heart rate is merged in time, so a device that samples more often doesn't outweigh the other`() {
        val ringHeart = (0 until 12).map { sample(ring, Metric.HEART_RATE, dayStart + 9 * HOUR + it * 5 * MINUTE, 60.0) }
        val watchHeart = (0 until 60).map { sample(watch, Metric.HEART_RATE, dayStart + 9 * HOUR + it * MINUTE, 70.0) }
        val line = Combine.line(ringHeart + watchHeart)
        assertEquals(12, line.size)
        assertTrue(line.all { abs(it.v - 65.0) < 1e-9 })
        val heart = Combine.heart(ringHeart + watchHeart, devices, null)
        // Mixing every reading would give 68.3, weighted to the watch's sixty a hour.
        assertEquals(65.0, heart.avg!!, 1e-9)
        assertEquals(65.0, heart.min!!, 1e-9)
        assertEquals(65.0, heart.max!!, 1e-9)
        assertEquals(listOf(0.5, 0.5), heart.combined!!.sources.map { it.weight })
        // One device's readings stay as they are.
        val alone = Combine.heart(watchHeart + sample(watch, Metric.HEART_RATE, dayStart + 10 * HOUR, 120.0), devices, null)
        assertEquals(120.0, alone.max!!, 1e-9)
        assertNull(alone.combined)
    }

    @Test
    fun `resting heart rate comes from the merged night line, each device's own shown`() {
        val night = (0 until 96).flatMap { i ->
            val t = dayStart + i * 5 * MINUTE
            val curve = abs(i / 12.0 - 3) * 1.5
            listOf(sample(ring, Metric.HEART_RATE, t, 52 + curve), sample(watch, Metric.HEART_RATE, t, 56 + curve))
        }
        val (resting, combined) = Combine.resting(emptyList(), night, devices, null)
        assertEquals(54.083, resting!!, 0.01)
        assertEquals(52.083, combined!!.sources.first { it.deviceId == "ring" }.value, 0.01)
        assertEquals(56.083, combined.sources.first { it.deviceId == "watch" }.value, 0.01)
        // Their own resting figures, where they give them: weighted to the ring at night.
        val stated = listOf(sample(ring, Metric.RESTING_HEART_RATE, dayStart, 52.0), sample(watch, Metric.RESTING_HEART_RATE, dayStart, 56.0))
        val (fromStated, how) = Combine.resting(stated, night, devices, null)
        assertEquals((52.0 * 1.0 + 56.0 * 0.8) / 1.8, fromStated!!, 1e-9)
        assertTrue(how!!.how.endsWith("weighted to the ring at night"))
    }

    /** Fourteen days with each device at its usual: the ring's HRV 50, the watch's 30. */
    private val usualDays = (1..14).map { d ->
        DayStats(
            "2026-02-%02d".format(d),
            readings = listOf(reading(ring, Metric.HRV, 50.0), reading(watch, Metric.HRV, 30.0), reading(ring, Metric.SPO2, 96.0), reading(watch, Metric.SPO2, 95.0)),
        )
    }

    private fun reading(device: DeviceRef, metric: Metric, value: Double, coverage: Double = 1.0) =
        DeviceReading(metric, device.id, device.name, device.kind, value, readings = 10, coverage = coverage)

    private fun hrv(ringValue: Double, watchValue: Double): Combine.Weighted {
        val today = listOf(reading(ring, Metric.HRV, ringValue), reading(watch, Metric.HRV, watchValue))
        return Combine.weighted(Metric.HRV, today, Combine.baselines(Metric.HRV, usualDays, listOf("ring", "watch")), null)!!
    }

    @Test
    fun `HRV is combined from each device's difference from its own usual, on the ring's scale`() {
        assertEquals(mapOf("ring" to 50.0, "watch" to 30.0), Combine.baselines(Metric.HRV, usualDays, listOf("ring", "watch")))
        // Both 10% above their usual: 10% above the ring's usual (a plain average would say 46.75).
        val up = hrv(55.0, 33.0)
        assertEquals(55.0, up.value, 1e-9)
        val combined = up.combined!!
        assertEquals("Each against its own usual, then averaged, weighted to the ring at night, on the ring's scale", combined.how)
        assertEquals(1.0 / 1.6, combined.sources.first { it.deviceId == "ring" }.weight, 1e-9)
        assertEquals(0.6 / 1.6, combined.sources.first { it.deviceId == "watch" }.weight, 1e-9)
        // The ring up 10%, the watch down 10%: weighted to the ring, a little up.
        assertEquals(50.0 * (1 + (0.1 - 0.06) / 1.6), hrv(55.0, 27.0).value, 1e-9)
        // Without a usual yet, the device that suits it best, as it reads.
        val early = Combine.weighted(Metric.HRV, listOf(reading(ring, Metric.HRV, 55.0), reading(watch, Metric.HRV, 33.0)), emptyMap(), null)!!
        assertEquals(55.0, early.value, 1e-9)
        assertEquals("still learning its usual", early.combined!!.sources.first { it.deviceId == "watch" }.note)
    }

    @Test
    fun `a device far from its usual while the other isn't counts for less`() {
        // The watch reads half its usual (a loose strap, say); the ring is where it usually is.
        val w = hrv(51.0, 15.0)
        val watchWeight = 0.6 * Combine.FAR_WEIGHT
        assertEquals(50.0 * (1 + (0.02 * 1.0 - 0.5 * watchWeight) / (1.0 + watchWeight)), w.value, 1e-9)
        val watchSource = w.combined!!.sources.first { it.deviceId == "watch" }
        assertEquals("far from its usual", watchSource.note)
        assertTrue(w.combined!!.how.endsWith("the watch was far from its usual, so counted for less"))
        // Blood oxygen, as read (no scale to set): the same.
        val spo2 = Combine.weighted(
            Metric.SPO2, listOf(reading(ring, Metric.SPO2, 96.0), reading(watch, Metric.SPO2, 88.0)),
            Combine.baselines(Metric.SPO2, usualDays, listOf("ring", "watch")), null,
        )!!
        assertEquals((96.0 + 88.0 * 0.5 * Combine.FAR_WEIGHT) / (1.0 + 0.5 * Combine.FAR_WEIGHT), spo2.value, 1e-9)
        // Both far from their usual in the same way: that's the body, and both count as usual.
        val both = hrv(30.0, 18.0)
        assertTrue(both.combined!!.sources.none { it.note == "far from its usual" })
        assertEquals(50.0 * 0.6, both.value, 1e-9)
    }

    @Test
    fun `a device that measured only part of the night counts for less`() {
        val spo2 = Combine.weighted(Metric.SPO2, listOf(reading(ring, Metric.SPO2, 96.0, coverage = 0.25), reading(watch, Metric.SPO2, 94.0)), emptyMap(), null)!!
        assertEquals((96.0 * 0.25 + 94.0 * 0.5) / 0.75, spo2.value, 1e-9)
        assertEquals("only part of the night", spo2.combined!!.sources.first { it.deviceId == "ring" }.note)
        // Stress is the wrist's: weighted to the watch, in the day.
        val stress = Combine.weighted(Metric.STRESS, listOf(reading(ring, Metric.STRESS, 40.0), reading(watch, Metric.STRESS, 30.0)), emptyMap(), null)!!
        assertEquals((40.0 * 0.6 + 30.0) / 1.6, stress.value, 1e-9)
        assertEquals("The average of both, weighted to the watch in the day", stress.combined!!.how)
    }

    private fun clock(daysAfter: Int, hour: Int, minute: Int = 0) = dayStart + daysAfter * 24 * HOUR + hour * HOUR + minute * MINUTE

    private val ringNight = run {
        val plan = listOf(SleepStage.LIGHT to 120, SleepStage.DEEP to 60, SleepStage.REM to 60, SleepStage.LIGHT to 210, SleepStage.AWAKE to 30)
        var t = clock(-1, 23)
        val stages = plan.map { (stage, minutes) -> StageSpan(stage, t, t + minutes * MINUTE).also { t += minutes * MINUTE } }
        SleepSession(clock(-1, 23), t, stages, id = "r1", deviceId = "ring", source = "Colmi R02")
    }

    @Test
    fun `a night two devices recorded has the timing they agree on, the ring's stages, time asleep from both`() {
        // The watch has no stages, and thinks the night began 20 minutes later and ended 30 later.
        val watchNight = SleepSession(clock(-1, 23, 20), clock(0, 7, 30), id = "w1", deviceId = "watch", source = "Pebble Time 2")
        val nights = Combine.nights(listOf(watchNight, ringNight), devices, null)
        val night = nights.single()
        assertEquals(clock(-1, 23, 10), night.session.start)
        assertEquals(clock(0, 7, 15), night.session.end)
        assertEquals("ring", night.combined!!.stagesFrom)
        assertEquals(ringNight.stages.size, night.session.stages.size)
        assertEquals(clock(-1, 23, 10), night.session.stages.first().start)
        // The ring: 450 minutes asleep; the watch: 490 in bed, counting half as it has no stages.
        assertEquals(((450 * 1.0 + 490 * 0.45) / 1.45).roundToInt(), night.asleepMinutes)
        assertEquals("Bedtime and waking averaged; stages from the ring; time asleep from both, weighted to the ring", night.combined!!.how)
        assertEquals(listOf(450, 490), night.combined!!.sources.sortedBy { it.deviceId }.map { it.asleepMinutes })

        // More than 45 minutes apart: the staged device's bedtime, though the watch's night is longer.
        val early = SleepSession(clock(-1, 21, 30), clock(0, 7), id = "w2", deviceId = "watch", source = "Pebble Time 2")
        val apart = Combine.nights(listOf(early, ringNight), devices, null).single()
        assertEquals(ringNight.start, apart.session.start)
        assertEquals(clock(0, 7), apart.session.end)
        assertTrue(apart.combined!!.how.startsWith("Waking averaged, bedtime from the ring"))

        // The ring's battery ran out at 3 am, mid-REM: the watch carried on asleep until 7:30, so the night
        // ends then, and the last stretch counts as asleep (without stages) for the ring too.
        val ringPlan = listOf(SleepStage.LIGHT to 120, SleepStage.DEEP to 60, SleepStage.REM to 60)
        var rt = clock(-1, 23)
        val ringStages = ringPlan.map { (stage, minutes) -> StageSpan(stage, rt, rt + minutes * MINUTE).also { rt += minutes * MINUTE } }
        val died = SleepSession(clock(-1, 23), rt, ringStages, id = "r2", deviceId = "ring", source = "Colmi R02")
        val carried = SleepSession(clock(-1, 23, 10), clock(0, 7, 30), id = "w4", deviceId = "watch", source = "Pebble Time 2")
        val whole = Combine.nights(listOf(carried, died), devices, null).single()
        assertEquals(clock(-1, 23, 5), whole.session.start)
        assertEquals(clock(0, 7, 30), whole.session.end)
        assertEquals(SleepStage.ASLEEP, whole.session.stages.last().stage)
        assertEquals(clock(0, 3), whole.session.stages.last().start)
        // The ring: 240 recorded + 270 carried on = 510; the watch: 500 in bed, counting less as it has no stages;
        // about 507 between them, but never more than the 505 minutes in bed.
        assertEquals(minOf(((510 * 1.0 + 500 * 0.45) / 1.45).roundToInt(), 505), whole.asleepMinutes)
        assertTrue(whole.combined!!.how.startsWith("Bedtime averaged, waking from the watch, as the ring stopped recording while you were still asleep"))
        // A ring that saw the waking keeps its own, even if the watch thinks the night went on.
        val lateWatch = SleepSession(clock(-1, 23, 10), clock(0, 9), id = "w5", deviceId = "watch", source = "Pebble Time 2")
        assertEquals(ringNight.end, Combine.nights(listOf(lateWatch, ringNight), devices, null).single().session.end)

        // A nap the watch alone recorded stays its own.
        val nap = SleepSession(clock(0, 14), clock(0, 14, 40), id = "w3", deviceId = "watch", source = "Pebble Time 2")
        val withNap = Combine.nights(listOf(watchNight, ringNight, nap), devices, null)
        assertEquals(2, withNap.size)
        assertNull(withNap.first { it.session.id == "w3" }.combined)

        // The person chose the watch for sleep: its night, as it recorded it.
        val chosen = Combine.nights(listOf(watchNight, ringNight), devices, "watch").single()
        assertEquals(watchNight.start, chosen.session.start)
        assertNull(chosen.asleepMinutes)
        assertEquals("Your choice: the watch's night", chosen.combined!!.how)
    }

    @Test
    fun `devices are placed by kind, or by name when they come through an app`() {
        assertEquals(Combine.Placement.WRIST, Combine.placement(DeviceKind.GADGETBRIDGE, "Pebble Time 2"))
        assertEquals(Combine.Placement.FINGER, Combine.placement(DeviceKind.HEALTH_CONNECT, "Oura"))
        assertEquals(Combine.Placement.FINGER, Combine.placement(DeviceKind.COLMI_RING, "R02_1A2B"))
        assertEquals(Combine.Placement.OTHER, Combine.placement(DeviceKind.HEALTH_CONNECT, "Health Connect"))
        // Two watches go by their own names.
        val names = Combine.shortNames(listOf(watch, DeviceRef("g", "Garmin Venu", DeviceKind.GARMIN), ring))
        assertEquals(mapOf("watch" to "Pebble Time 2", "g" to "Garmin Venu", "ring" to "ring"), names)
    }

    @Test
    fun `a share sees combined numbers for what's shared, and never the choices`() {
        assertEquals(SharedView.Need.Never, SharedView.requirement("/api/devices/choices", null))
        val heart = CombinedValue(Metric.HEART_RATE, 70.0, listOf(SourceValue("ring", "Colmi R02", value = 70.0, weight = 1.0)), "")
        val resting = CombinedValue(Metric.RESTING_HEART_RATE, 54.0, listOf(SourceValue("ring", "Colmi R02", value = 54.0, weight = 1.0)), "")
        val seen = SharedView.limit(DaySummary("2026-03-09", combined = listOf(heart, resting)), setOf(Module.ACTIVITY))
        assertEquals(listOf(Metric.HEART_RATE), seen.combined.map { it.metric })
    }

    // ---- Through ingest, the API, the scores and the connector ----

    private val zone = ZoneId.of("America/New_York")
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
    private val firstDay = LocalDate.of(2026, 3, 1)
    private val lastDay = LocalDate.of(2026, 3, 15)
    private val now = at(lastDay, 20, 30)
    private val ringInfo = DeviceInfo(DeviceKind.COLMI_RING, "AA:BB:CC:DD:EE:FF", "Colmi R02")
    private val watchInfo = DeviceInfo(DeviceKind.PEBBLE, "pebble-time-2", "Pebble Time 2")

    /**
     * Fifteen days of a ring and a Pebble: both measure the night's heart rate (the watch 4 bpm
     * higher) and HRV (the watch reads lower: 30 against the ring's 48); the ring counts steps
     * 9 to 6 and comes off; the watch counts a little more from noon to 8 (but not on the 14th).
     * On the last day both read HRV 10% above their usual.
     */
    private fun batches(): List<IngestBatch> {
        val ring = mutableListOf<Sample>()
        val watch = mutableListOf<Sample>()
        val ringNights = mutableListOf<SleepSession>()
        val watchNights = mutableListOf<SleepSession>()
        for (i in 0 until 15) {
            val date = firstDay.plusDays(i.toLong())
            val last = date == lastDay
            val bed = at(date.minusDays(1), 23)
            val plan = listOf(SleepStage.AWAKE to 10, SleepStage.LIGHT to 60, SleepStage.DEEP to 50, SleepStage.LIGHT to 60, SleepStage.REM to 40,
                SleepStage.LIGHT to 60, SleepStage.DEEP to 30, SleepStage.REM to 50, SleepStage.LIGHT to 60, SleepStage.REM to 40, SleepStage.AWAKE to 30)
            var t = bed
            val stages = plan.map { (stage, minutes) -> StageSpan(stage, t, t + minutes * MINUTE).also { t += minutes * MINUTE } }
            ringNights += SleepSession(bed, t, stages)
            watchNights += SleepSession(bed + 15 * MINUTE, t + 10 * MINUTE)
            var h = bed
            while (h < t + 10 * MINUTE) {
                val curve = abs((h - bed) / HOUR.toDouble() - 3) * 1.5
                if (h < t) ring += Sample(Metric.HEART_RATE, h, value = 52 + curve)
                if (h >= bed + 15 * MINUTE) watch += Sample(Metric.HEART_RATE, h, value = 56 + curve)
                h += 5 * MINUTE
            }
            ring += Sample(Metric.HRV, bed + 4 * HOUR, value = if (last) 52.8 else 48.0)
            watch += Sample(Metric.HRV, bed + 4 * HOUR, value = if (last) 33.0 else 30.0)
            for (q in 0 until 36) {
                val start = at(date, 9) + q * QUARTER
                ring += Sample(Metric.STEPS, start, start + QUARTER, 250.0)
                ring += Sample(Metric.HEART_RATE, start, value = 78.0)
            }
            if (date != lastDay.minusDays(1)) for (q in 0 until 32) {
                val start = at(date, 12) + q * QUARTER
                watch += Sample(Metric.STEPS, start, start + QUARTER, 260.0)
                watch += Sample(Metric.HEART_RATE, start, value = 80.0)
            }
        }
        return listOf(IngestBatch(ringInfo, ring, ringNights), IngestBatch(watchInfo, watch, watchNights))
    }

    private fun services() = testServices(clock = { now }).also { s ->
        runBlocking {
            s.bootstrap()
            val admin = s.users.byUsername("admin")!!
            s.profiles.put(admin.id, Profile(birthYear = 1990, heightCm = 168.0, energyProfile = EnergyProfile.AVERAGE, timezone = "America/New_York", goals = Goals(steps = 8000)))
            batches().forEach { s.ingest.ingest(admin.id, it) }
        }
    }

    private fun JsonObject.combined(metric: Metric) = this["combined"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["metric"]!!.jsonPrimitive.content == metric.name }

    @Test
    fun `a ring and a Pebble through ingest, the day shows the combined numbers, and the scores use them`() = serverTest(services()) { s ->
        val admin = adminBrowser()
        val user = s.users.byUsername("admin")!!
        val ringId = s.devices.list(user.id).first { it.kind == DeviceKind.COLMI_RING }.id
        val watchId = s.devices.list(user.id).first { it.kind == DeviceKind.PEBBLE }.id

        val response = admin.get("/api/day/$lastDay")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = response.obj()
        // Steps: the ring's 9 to noon, the watch's from noon (it counted more), and the watch's after the ring came off.
        assertEquals(3000 + 24 * 260 + 8 * 260, json["steps"]!!.jsonPrimitive.int)
        val steps = json.combined(Metric.STEPS)!!
        assertEquals(2, steps["sources"]!!.jsonArray.size)
        assertEquals(setOf(Metric.STEPS, Metric.HEART_RATE, Metric.RESTING_HEART_RATE, Metric.HRV).map { it.name }.toSet(),
            json["combined"]!!.jsonArray.map { it.jsonObject["metric"]!!.jsonPrimitive.content }.toSet())
        val hourly = json["hourlySteps"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertEquals(1000, hourly[9])
        assertEquals(1040, hourly[12])
        assertEquals(1040, hourly[19])

        val day = s.days.summary(user.id, lastDay.toString())
        // Resting: the merged night line's lowest, between the ring's 52 and the watch's 56.
        assertEquals(54.083, day.restingHeartRate!!, 0.01)
        assertEquals(day.restingHeartRate!!, day.combined.first { it.metric == Metric.RESTING_HEART_RATE }.value, 1e-9)
        // HRV: each 10% above its own usual, so 10% above the ring's 48.
        assertEquals(52.8, day.hrv!!, 0.01)
        val hrv = day.combined.first { it.metric == Metric.HRV }
        assertEquals(listOf(ringId, watchId), hrv.sources.map { it.deviceId })
        assertEquals(listOf(52.8, 33.0), hrv.sources.map { it.value })

        // The scores use the combined numbers.
        val rhr = day.readiness!!.contributors.first { it.key == "rhr" }
        assertEquals("54 bpm", rhr.value)
        assertEquals("Usually 54 bpm", rhr.detail)
        assertEquals("53 ms", day.readiness!!.contributors.first { it.key == "hrv" }.value)
        assertTrue(day.activity!!.contributors.first { it.key == "goal" }.detail!!.contains("11,320 steps"))

        // The night: both devices' versions, the ring's stages, the timing they agree on.
        val night = day.sleepSummary!!
        val combinedNight = night.combined!!
        assertEquals(2, combinedNight.sources.size)
        assertEquals(ringId, combinedNight.stagesFrom)
        assertEquals(at(lastDay.minusDays(1), 23) + 7 * MINUTE + 30_000, night.start)
        assertEquals(at(lastDay, 7, 15), night.end)
        assertEquals(((450 * 1.0 + 485 * 0.45) / 1.45).roundToInt(), night.asleepMinutes)
        assertEquals(night.asleepMinutes, night.deepMinutes + night.remMinutes + night.lightMinutes)
        assertNotNull(day.sleep)

        // Trends and the connector say the same.
        val trend = s.days.trends(user.id, lastDay, lastDay, null).days.single()
        assertEquals(day.restingHeartRate, trend.restingHeartRate)
        assertEquals(day.hrv, trend.hrv)
        assertEquals(11320, trend.steps)
        val text = McpServer(s).formatDay(day, Units.METRIC, emptySet())
        // The watch first: it had one more five minutes of the night than the ring.
        assertTrue(text, text.contains("Resting heart rate 54 (watch 56, ring 52): The lowest it stayed overnight, from both devices merged every 5 minutes"))
        assertTrue(text, text.contains("Steps 11,320 (watch 8,320, ring 9,000)"))
        assertTrue(text, text.contains("Recorded by Colmi R02 (7h 30m asleep, with stages) and Pebble Time 2"))
    }

    @Test
    fun `choosing one device uses only its readings, and falls back to combining when it has none`() = serverTest(services()) { s ->
        val admin = adminBrowser()
        val user = s.users.byUsername("admin")!!
        val ringId = s.devices.list(user.id).first { it.kind == DeviceKind.COLMI_RING }.id
        val watchId = s.devices.list(user.id).first { it.kind == DeviceKind.PEBBLE }.id
        assertEquals(11320, admin.get("/api/day/$lastDay").obj()["steps"]!!.jsonPrimitive.int)

        // Unknown devices, and readings that aren't chosen between, are refused.
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/devices/choices", """{"choices":[{"metric":"STEPS","deviceId":"nope"}]}""").status)
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/devices/choices", """{"choices":[{"metric":"WEIGHT","deviceId":"$ringId"}]}""").status)
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/devices/choices", """{"sleepDeviceId":"nope"}""").status)

        val saved = admin.putJson("/api/devices/choices", """{"choices":[{"metric":"STEPS","deviceId":"$watchId"},{"metric":"HRV"}],"sleepDeviceId":"$watchId"}""")
        assertEquals(HttpStatusCode.OK, saved.status)
        assertEquals(1, saved.obj()["choices"]!!.jsonArray.size)
        assertEquals(watchId, admin.get("/api/devices/choices").obj().str("sleepDeviceId"))

        // The watch's steps alone; it's said so.
        val chosen = admin.get("/api/day/$lastDay").obj()
        assertEquals(32 * 260, chosen["steps"]!!.jsonPrimitive.int)
        val steps = chosen.combined(Metric.STEPS)!!
        assertEquals(watchId, steps.str("chosenDeviceId"))
        assertEquals("Your choice: the watch's count", steps.str("how"))
        val sources = steps["sources"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1.0, sources.first { it.str("deviceId") == watchId }["weight"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.0, sources.first { it.str("deviceId") == ringId }["weight"]!!.jsonPrimitive.double, 0.0)
        // The night is the watch's, as it recorded it.
        val night = chosen["sleepSummary"]!!.jsonObject
        assertEquals(at(lastDay.minusDays(1), 23, 15), night["start"]!!.jsonPrimitive.content.toLong())
        assertEquals("Your choice: the watch's night", night["combined"]!!.jsonObject.str("how"))

        // On the 14th the watch counted nothing: back to the ring, and it says why.
        val fallback = admin.get("/api/day/${lastDay.minusDays(1)}").obj()
        assertEquals(9000, fallback["steps"]!!.jsonPrimitive.int)
        val why = fallback.combined(Metric.STEPS)!!
        assertEquals(watchId, why.str("chosenDeviceId"))
        assertEquals("You chose the watch, which had none that day. The ring's count", why.str("how"))

        // Back to combining.
        assertEquals(HttpStatusCode.OK, admin.putJson("/api/devices/choices", """{}""").status)
        assertEquals(11320, admin.get("/api/day/$lastDay").obj()["steps"]!!.jsonPrimitive.int)
        // A forgotten device's choices go with it.
        admin.putJson("/api/devices/choices", """{"choices":[{"metric":"STEPS","deviceId":"$watchId"}]}""")
        admin.delete("/api/devices/$watchId") { csrf() }
        assertTrue(admin.get("/api/devices/choices").obj()["choices"]!!.jsonArray.isEmpty())
        assertEquals(9000, admin.get("/api/day/$lastDay").obj()["steps"]!!.jsonPrimitive.int)
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 3_600_000L
        private const val QUARTER = 15 * MINUTE
    }
}
