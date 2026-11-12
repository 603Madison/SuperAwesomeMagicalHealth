package com.health.server

import com.health.api.BloodPressure
import com.health.api.BpCategory
import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.Drink
import com.health.api.DrinkKind
import com.health.api.GlucoseContext
import com.health.api.GlucoseReading
import com.health.api.Habit
import com.health.api.HabitSource
import com.health.api.HabitTick
import com.health.api.IngestBatch
import com.health.api.Metric
import com.health.api.Migraine
import com.health.api.Module
import com.health.api.Place
import com.health.api.Profile
import com.health.api.ResilienceLevel
import com.health.api.Sample
import com.health.api.StressDay
import com.health.api.StressState
import com.health.api.SymptomEntry
import com.health.api.VitalsTargets
import com.health.api.WebhookEvent
import com.health.server.insights.Stress
import com.health.server.insights.StressWindow
import com.health.server.tracking.DrinksService
import com.health.server.tracking.HabitsService
import com.health.server.tracking.SymptomsService
import com.health.server.tracking.VitalsService
import com.health.server.tracking.Weather
import com.health.server.util.Days
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class TrackingTest {
    private val zone = ZoneId.of("America/New_York")
    private val days = Days(zone)
    /** A Friday. */
    private val today = LocalDate.of(2026, 3, 20)
    private val now = at(today, 21)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun services(modules: Set<Module> = Module.entries.toSet(), place: Place? = null) = testServices(clock = { now }).also { s ->
        runBlocking {
            s.bootstrap()
            s.profiles.put(s.users.byUsername("admin")!!.id, Profile(timezone = zone.id, modules = modules, place = place))
        }
    }

    private fun Services.admin() = runBlocking { users.byUsername("admin")!!.id }

    // ---- Blood pressure and glucose ----

    @Test
    fun `blood pressure falls into the heart association's categories`() {
        assertEquals(BpCategory.NORMAL, VitalsService.category(118, 76))
        assertEquals(BpCategory.ELEVATED, VitalsService.category(125, 78))
        assertEquals(BpCategory.STAGE_1, VitalsService.category(132, 79))
        assertEquals(BpCategory.STAGE_1, VitalsService.category(128, 85))
        assertEquals(BpCategory.STAGE_2, VitalsService.category(145, 88))
        assertEquals(BpCategory.STAGE_2, VitalsService.category(150, 55))
        assertEquals(BpCategory.CRISIS, VitalsService.category(185, 100))
        assertEquals(BpCategory.CRISIS, VitalsService.category(170, 121))
        assertEquals(BpCategory.LOW, VitalsService.category(85, 55))
    }

    @Test
    fun `glucose time in range, and an A1c estimate only from two weeks of sensor data`() {
        val targets = VitalsTargets()
        // 14 days of sensor readings every hour: mostly 7.0, a few low and high.
        val readings = (0 until 14 * 24).map { i ->
            val value = when (i % 24) { 3 -> 3.5; 13, 14 -> 11.0; else -> 7.0 }
            GlucoseReading(at(today.minusDays(13), 0) + i * 3_600_000L, value, GlucoseContext.CGM)
        }
        val (below, inRange, above) = VitalsService.inRange(readings, targets)
        assertEquals(4, below)
        assertEquals(8, above)
        assertEquals(88, inRange)
        val gmi = VitalsService.gmi(readings, days)!!
        val mean = readings.map { it.mmolL }.average() * VitalsService.MG_PER_MMOL
        assertEquals(((3.31 + 0.02392 * mean) * 10).toInt() / 10.0, gmi, 0.11)
        assertNull(VitalsService.gmi(readings.take(10 * 24), days))
        assertEquals("126 mg/dL", VitalsService.glucoseText(7.0, com.health.api.GlucoseUnit.MG_DL))
    }

    @Test
    fun `readings entered, sent by a monitor and recorded as samples all count, and a very high one is flagged`() = runBlocking {
        val s = services()
        val id = s.admin()
        val vitals = s.tracking.vitals
        vitals.saveBloodPressure(id, BloodPressure(at(today, 8), 128, 82, pulse = 64, arm = "Left"))
        // A monitor's readings, sent twice: kept once.
        val monitor = IngestBatch(
            DeviceInfo(DeviceKind.BP_MONITOR, "AA:BB", "Omron"),
            bloodPressure = listOf(BloodPressure(at(today, 9), 135, 85, 70)),
            glucose = listOf(GlucoseReading(at(today, 7), 5.4, GlucoseContext.FASTING)),
        )
        assertEquals(2, s.ingest.ingest(id, monitor).readings)
        s.ingest.ingest(id, monitor)
        assertEquals(3L, s.db.tx { long("SELECT COUNT(*) FROM vitals") })
        // Health Connect-style samples, paired by time.
        s.ingest.ingest(
            id,
            IngestBatch(
                DeviceInfo(DeviceKind.HEALTH_CONNECT, "hc", "Health Connect"),
                listOf(Sample(Metric.BLOOD_PRESSURE_SYSTOLIC, at(today, 12), value = 122.0), Sample(Metric.BLOOD_PRESSURE_DIASTOLIC, at(today, 12), value = 78.0)),
            ),
        )
        val list = vitals.bloodPressure(id, 7)
        assertEquals(listOf(122, 135, 128), list.map { it.systolic })
        assertEquals("left", list.last().arm)
        assertEquals(listOf("Health Connect", "Omron", "Entered by hand"), list.map { it.source })
        val summary = vitals.summary(id, 7)
        assertEquals(128.3, summary.systolicAverage!!, 0.05)
        assertEquals(5.4, summary.fastingAverage!!, 0.01)
        assertEquals(100, summary.timeInRange)

        vitals.saveBloodPressure(id, BloodPressure(at(today, 20), 186, 118))
        val day = s.days.summary(id, today.toString())
        assertEquals(BpCategory.CRISIS, day.modules.vitals!!.category)
        val warning = day.insights.first()
        assertEquals("Very high blood pressure: 186/118", warning.title)
        assertTrue(warning.body.contains("get emergency help now"))
        assertEquals(Module.VITALS, warning.module)
        // Trends carry the day's averages.
        val trend = s.days.trends(id, today, today, null).days.single()
        assertEquals((128 + 135 + 122 + 186) / 4.0, trend.systolic!!, 0.01)
        assertEquals(5.4, trend.glucose!!, 0.01)
        // Only readings entered by hand can be deleted.
        val monitorReading = list.first { it.source == "Omron" }
        assertEquals(HttpStatusCode.NotFound, runCatching { vitals.delete(id, "BP", monitorReading.id!!) }.exceptionOrNull().let { (it as ApiError).status })
    }

    @Test
    fun `a low glucose is pushed, a high blood pressure goes to webhooks only`() = runBlocking {
        val s = services()
        val id = s.admin()
        val profile = s.profiles.get(id)
        s.tracking.vitals.saveGlucose(id, GlucoseReading(now - 60_000, 3.2, GlucoseContext.OTHER))
        s.tracking.vitals.saveBloodPressure(id, BloodPressure(now - 60_000, 150, 95))
        val hooks = s.hooks.first { it.module == Module.VITALS }
        val reminders = hooks.reminders(id, profile, now - 15 * 60_000, now + 60_000)
        val low = reminders.single { it.event == WebhookEvent.GLUCOSE_LOW }
        assertTrue(low.push)
        assertEquals("Check your glucose", low.privateTitle)
        assertFalse(reminders.single { it.event == WebhookEvent.BP_HIGH }.push)
        // Nothing new later on.
        assertTrue(hooks.reminders(id, profile, now + 60_000, now + 120_000).isEmpty())
        val fhir = hooks.fhir(id, profile, today, today)
        val bp = fhir.first { it["code"]!!.jsonObject["coding"]!!.jsonArray[0].jsonObject.str("code") == "85354-9" }
        assertEquals(listOf("8480-6", "8462-4"), bp["component"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonObject["coding"]!!.jsonArray[0].jsonObject.str("code") })
        assertTrue(hooks.export(id, profile, today, today).keys.containsAll(listOf("blood-pressure.csv", "glucose.csv")))
    }

    // ---- Drinks ----

    @Test
    fun `drinks other than alcohol count towards water, and the day is worked out again`() = runBlocking {
        val s = services()
        val id = s.admin()
        assertEquals(0, s.days.summary(id, today.toString()).waterMl)
        val coffee = s.tracking.drinks.save(id, Drink(at(today, 8), DrinkKind.COFFEE, 240, caffeineMg = 95.0))
        s.tracking.drinks.save(id, Drink(at(today, 19), DrinkKind.WINE, 150, alcoholG = 14.0))
        val day = s.days.summary(id, today.toString())
        assertEquals(240, day.waterMl)
        assertEquals(1.0, day.drinks!!.standardDrinks, 0.01)
        assertEquals(95.0, day.drinks!!.caffeineMg, 0.01)
        assertEquals(240, day.drinks!!.fluidMl)
        s.tracking.drinks.delete(id, coffee.id!!)
        assertEquals(0, s.days.summary(id, today.toString()).waterMl)
        val trend = s.days.trends(id, today, today, null).days.single()
        assertEquals(1.0, trend.standardDrinks!!, 0.01)
    }

    @Test
    fun `caffeine halves every five hours before bedtime`() {
        val start = days.start(today)
        val drinks = listOf(Drink(start + 13 * 3_600_000L, DrinkKind.COFFEE, 240, caffeineMg = 100.0), Drink(start + 18 * 3_600_000L, DrinkKind.TEA, 240, caffeineMg = 40.0))
        // Bed at 23:00: the coffee had ten hours (a quarter left), the tea five (half).
        assertEquals(45.0, DrinksService.atBedtime(drinks, 23 * 60, start), 0.6)
        val day = DrinksService.dayOf(today, drinks, 23 * 60, start)
        assertEquals(140.0, day.caffeineMg, 0.01)
        assertEquals(480, day.fluidMl)
    }

    // ---- Symptoms ----

    @Test
    fun `symptoms and migraines are encrypted at rest`() = runBlocking {
        val s = services()
        val id = s.admin()
        s.tracking.symptoms.save(id, SymptomEntry(at(today, 9), "Lower Back Pain", 6, note = "after the long drive"))
        s.tracking.symptoms.saveMigraine(id, Migraine(at(today, 14), severity = 8, aura = true, medications = listOf("Sumatriptan")))
        val raw = s.db.tx { query("SELECT data FROM entries") { it.string("data") } }
        assertEquals(2, raw.size)
        assertTrue(raw.none { "back pain" in it || "drive" in it || "Sumatriptan" in it })
        assertEquals("lower back pain", s.tracking.symptoms.entries(id, 7).single().symptom)
        val day = s.days.summary(id, today.toString()).modules.symptoms!!
        assertEquals(8, day.worst)
        assertTrue(s.days.summary(id, today.toString()).insights.any { it.title == "Migraine still going" })
    }

    @Test
    fun `symptom days are compared with short nights and falling air pressure`() = runBlocking {
        // Pressure drops by 8 hPa every fourth day; migraines come on those days.
        val start = today.minusDays(29)
        val pressure = (0L..31L).associate { i -> start.minusDays(1).plusDays(i) to if (i % 4 == 0L) 1004.0 else 1012.0 }
        val requests = mutableListOf<String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.path.orEmpty()
                    val url = request.requestUrl!!
                    val from = LocalDate.parse(url.queryParameter("start_date")!!)
                    val to = LocalDate.parse(url.queryParameter("end_date")!!)
                    val dates = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
                    val times = dates.flatMap { d -> (0 until 24).map { h -> "\"${d}T%02d:00\"".format(h) } }
                    val values = dates.flatMap { d -> List(24) { (pressure[d] ?: 1010.0).toString() } }
                    return MockResponse().setBody("""{"hourly":{"time":[${times.joinToString()}],"pressure_msl":[${values.joinToString()}]}}""")
                }
            }
            start()
        }
        try {
            val s = services(place = Place("Leeds", 53.7997, -1.5492))
            s.weather.urls = Weather.Urls(forecast = server.url("/forecast").toString(), archive = server.url("/archive").toString(), geocoding = server.url("/search").toString())
            val id = s.admin()
            for (i in 0L..29L) {
                val d = start.plusDays(i)
                if (pressure[d]!! < 1010 && pressure[d.minusDays(1)]!! > 1010) s.tracking.symptoms.saveMigraine(id, Migraine(at(d, 10), at(d, 16), severity = 7))
                else if (i % 7 == 3L) s.tracking.symptoms.save(id, SymptomEntry(at(d, 10), "headache", 4))
            }
            val summary = s.tracking.symptoms.summary(id, 30)
            assertTrue(summary.weather)
            val drop = summary.correlations.first { it.factor == "pressure_drop" }
            assertEquals(1.0, drop.withRate, 0.01)
            assertTrue(drop.withoutRate < 0.3)
            assertTrue(drop.text, drop.text.startsWith("Symptoms came on"))
            assertEquals(summary.days.count { it.migraines.isNotEmpty() }, summary.migraineDays)
            assertEquals("migraine", summary.counts.first().name)
            // Only a rough place went out, and each day was fetched once.
            assertTrue(requests.all { "latitude=53.8" in it && "longitude=-1.5" in it })
            val before = requests.size
            s.tracking.symptoms.summary(id, 30)
            assertEquals(before, requests.size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `the correlation needs a clear difference and enough days`() {
        val dates = (0L until 20L).map { today.minusDays(it) }
        val symptoms = dates.associateWith { it.dayOfMonth % 2 == 0 }
        // Same rate with and without: nothing.
        assertTrue(SymptomsService.correlate(symptoms, mapOf("short_sleep" to dates.associateWith { it.dayOfMonth % 4 < 2 })).isEmpty())
        // Too few days with the factor: nothing.
        assertTrue(SymptomsService.correlate(symptoms, mapOf("alcohol" to dates.associateWith { it.dayOfMonth == 2 })).isEmpty())
        val clear = SymptomsService.correlate(symptoms, mapOf("stress" to dates.associateWith { it.dayOfMonth % 2 == 0 }))
        assertEquals("stress", clear.single().factor)
        assertEquals(1.0, clear.single().withRate, 0.001)
    }

    // ---- Stress and resilience ----

    @Test
    fun `stress windows are judged against the person's own baseline`() {
        val base = Stress.Baseline(restingHr = 60.0, daytimeHrv = 50.0)
        fun w(hr: Double? = null, hrv: Double? = null, device: Double? = null) = StressWindow(0, hr, hrv, device)
        assertEquals(StressState.STRESSED, Stress.classify(w(85.0, 25.0), base))
        assertEquals(StressState.STRESSED, Stress.classify(w(82.0), base))
        assertEquals(StressState.ENGAGED, Stress.classify(w(72.0, 48.0), base))
        assertEquals(StressState.RELAXED, Stress.classify(w(66.0, 50.0), base))
        assertEquals(StressState.RESTORED, Stress.classify(w(62.0, 60.0), base))
        assertEquals(StressState.RESTORED, Stress.classify(w(62.0), base))
        // The device's own stress reading wins.
        assertEquals(StressState.STRESSED, Stress.classify(w(62.0, device = 80.0), base))
        assertEquals(StressState.RESTORED, Stress.classify(w(90.0, device = 10.0), base))
        assertNull(Stress.classify(w(), base))

        val start = days.start(today) + 9 * 3_600_000L
        val windows = (0 until 16).map { i -> StressWindow(start + i * Stress.WINDOW_MS, if (i < 10) 85.0 else 66.0) }
        val day = Stress.day(today.toString(), windows, Stress.Baseline(60.0, null))!!
        assertEquals(150, day.stressedMinutes)
        assertEquals(90, day.relaxedMinutes)
        assertEquals("Stressful", day.summary)
        assertEquals(2, day.spans.size)
        assertNull(Stress.day(today.toString(), windows.take(3), base))
    }

    @Test
    fun `resilience weighs stress against recovery over two weeks`() {
        fun d(stressed: Int, restored: Int) = StressDay("x", stressedMinutes = stressed, restoredMinutes = restored, summary = "Normal")
        val calm = Stress.resilience(List(14) { d(10, 120) }, List(14) { 88 })!!
        assertEquals(ResilienceLevel.EXCEPTIONAL, calm.level)
        assertEquals(3, calm.contributors.size)
        val strained = Stress.resilience(List(14) { d(260, 0) }, List(14) { 50 })!!
        assertEquals(ResilienceLevel.LIMITED, strained.level)
        assertNull(Stress.resilience(List(14) { if (it < 8) null else d(30, 60) }, List(14) { 80 }))
        val better = Stress.resilience(List(14) { d(60, 60) }, List(14) { 80 }, List(14) { d(200, 10) }, List(14) { 60 })!!
        assertEquals("up", better.trend)
    }

    @Test
    fun `a day of heart rate becomes stressed and calm time, leaving out a walk`() = runBlocking {
        val s = services()
        val id = s.admin()
        val samples = mutableListOf<Sample>()
        samples += Sample(Metric.RESTING_HEART_RATE, at(today, 6), value = 60.0)
        var t = at(today, 8)
        while (t < at(today, 20)) {
            val hour = days.hourOf(t)
            val hr = when (hour) { 10, 11 -> 90.0; 14 -> 115.0; else -> 65.0 }
            samples += Sample(Metric.HEART_RATE, t, value = hr)
            if (hour == 14) samples += Sample(Metric.STEPS, t, t + 300_000, 600.0)
            t += 5 * 60_000L
        }
        s.ingest.ingest(id, IngestBatch(DeviceInfo(DeviceKind.COLMI_RING, "ring", "Ring"), samples))
        val day = s.days.summary(id, today.toString()).stressDay!!
        assertEquals(120, day.stressedMinutes)
        assertEquals("Stressful", day.summary)
        // The walk at 2 pm isn't stress.
        assertTrue(day.spans.none { it.state == StressState.STRESSED && days.hourOf(it.start) == 14 })
        assertEquals(120, s.days.trends(id, today, today, null).days.single().stressedMinutes)
        assertTrue(s.days.summary(id, today.toString()).insights.any { it.title == "A stressful day" })
    }

    // ---- Habits ----

    @Test
    fun `day streaks forgive one missed day in seven, and today not done yet doesn't break them`() {
        val since = today.minusDays(40)
        val missed = setOf(today.minusDays(5), today.minusDays(15))
        fun done(d: LocalDate) = d != today && d !in missed
        // Misses ten days apart are both forgiven.
        assertEquals(40 - 2, HabitsService.streakDays(since, today, ::done))
        // Two misses within a week: the streak ends at the second.
        val close = setOf(today.minusDays(5), today.minusDays(8))
        assertEquals(6, HabitsService.streakDays(since, today) { it != today && it !in close })
    }

    @Test
    fun `week streaks count weeks that reach the target, across week boundaries`() {
        // Three days a week; this week (Monday 16 March) has one done by Friday, two days left.
        val monday = LocalDate.of(2026, 3, 16)
        val weeks = mapOf(
            monday to setOf(0L),
            monday.minusWeeks(1) to setOf(0L, 2L, 4L),
            monday.minusWeeks(2) to setOf(1L, 3L, 5L, 6L),
            monday.minusWeeks(3) to setOf(0L, 1L, 2L),
            monday.minusWeeks(4) to setOf(0L),
        )
        val done = weeks.flatMap { (start, ds) -> ds.map { start.plusDays(it) } }.toSet()
        val since = monday.minusWeeks(6)
        // Still reachable this week: the three before count.
        assertEquals(3, HabitsService.streakWeeks(3, since, today) { it in done })
        // Reached this week: it counts too.
        assertEquals(4, HabitsService.streakWeeks(3, since, today) { it in done || it == today || it == today.minusDays(1) })
        // No longer reachable (Sunday with one done): over.
        assertEquals(0, HabitsService.streakWeeks(3, since, monday.plusDays(6)) { it in done })
        // The week a habit began asks only for its share of the days it had: begun Thursday, 3 a week asks for 2.
        assertEquals(1, HabitsService.streakWeeks(3, LocalDate.of(2026, 3, 19), today) { it == today || it == today.minusDays(1) })
        assertEquals(0, HabitsService.streakWeeks(3, LocalDate.of(2026, 3, 19), today) { it == today })
    }

    @Test
    fun `habits tick by hand or from what's recorded, through the API`() = serverTest(services()) { s ->
        val admin = adminBrowser()
        val walk = admin.postJson("/api/habits", """{"name":"Walk","icon":"footprints","source":"STEPS_GOAL","timesPerWeek":5}""").obj()
        val stretch = admin.postJson("/api/habits", """{"name":"Stretch","perDay":2,"reminderTime":"20:30"}""").obj()
        assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/habits", """{"name":"Bad","reminderTime":"25:00"}""").status)
        s.ingest.ingest(s.admin(), IngestBatch(DeviceInfo(DeviceKind.PHONE, "p", "Phone"), listOf(Sample(Metric.STEPS, at(today, 12), at(today, 13), 9000.0))))
        admin.putJson("/api/habits/${stretch.str("id")}/ticks", """{"date":"$today","count":1}""")
        var statuses = admin.get("/api/habits/today").json().jsonArray.map { it.jsonObject }
        assertEquals(true, statuses.first { it["habit"]!!.jsonObject.str("name") == "Walk" }["done"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, statuses.first { it["habit"]!!.jsonObject.str("name") == "Stretch" }["done"]!!.jsonPrimitive.content.toBoolean())
        admin.putJson("/api/habits/${stretch.str("id")}/ticks", """{"date":"$today","count":2}""")
        statuses = admin.get("/api/habits/today").json().jsonArray.map { it.jsonObject }
        assertEquals(2, statuses.first { it["habit"]!!.jsonObject.str("name") == "Stretch" }["count"]!!.jsonPrimitive.int)
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/habits/${stretch.str("id")}/ticks", """{"date":"${today.plusDays(1)}","count":1}""").status)
        assertEquals(1, admin.get("/api/habits/${stretch.str("id")}/history").json().jsonArray.size)
        val day = s.days.summary(s.admin(), today.toString())
        assertEquals(2, day.modules.habits!!.count { it.done })
        assertEquals(2, s.days.trends(s.admin(), today, today, null).days.single().habitsDone)
        // A reminder at 20:30 only if it isn't done: it is.
        val hooks = s.hooks.first { it.module == Module.HABITS }
        val profile = s.profiles.get(s.admin())
        assertTrue(hooks.reminders(s.admin(), profile, at(today, 20, 25), at(today, 20, 40)).isEmpty())
        admin.putJson("/api/habits/${stretch.str("id")}/ticks", """{"date":"$today","count":0}""")
        val due = hooks.reminders(s.admin(), profile, at(today, 20, 25), at(today, 20, 40)).single()
        assertEquals(WebhookEvent.HABIT_DUE, due.event)
        assertEquals("habit:${stretch.str("id")}:$today", due.key)
        assertEquals("Stretch", due.title)
        assertNotNull(walk["id"])
    }

    @Test
    fun `days ticked before a habit was made count towards it`() = serverTest(services()) { s ->
        val admin = adminBrowser()
        val stretch = admin.postJson("/api/habits", """{"name":"Stretch","timesPerWeek":7}""").obj().str("id")
        for (back in 1L..9L) admin.putJson("/api/habits/$stretch/ticks", """{"date":"${today.minusDays(back)}","count":1}""")
        val status = admin.get("/api/habits/today").json().jsonArray.map { it.jsonObject }.single()
        assertEquals(9, status["streakDays"]!!.jsonPrimitive.int)
        val recent = status["recent"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull }
        assertEquals(listOf("null", "null", "null", "null", "true", "true", "true", "true", "true", "true", "true", "true", "true", "false"), recent.map { it ?: "null" })
    }

    // ---- The API and the AI connector ----

    @Test
    fun `the tracking API answers, and turned-off features stay off Today`() = serverTest(services(modules = Module.DEFAULT)) { s ->
        val admin = adminBrowser()
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/vitals/bp", """{"at":${at(today, 8)},"systolic":120,"diastolic":80}""").status)
        assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/vitals/bp", """{"at":${at(today, 8)},"systolic":80,"diastolic":120}""").status)
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/vitals/glucose", """{"at":${at(today, 8)},"mmolL":5.5,"context":"FASTING"}""").status)
        assertEquals(1, admin.get("/api/vitals/bp").json().jsonArray.size)
        assertEquals(1, admin.get("/api/vitals/summary").obj()["glucose"]!!.jsonArray.size)
        val drink = admin.postJson("/api/drinks", """{"at":${at(today, 9)},"kind":"COFFEE","volumeMl":240,"caffeineMg":95}""").obj()
        assertEquals(95.0, admin.get("/api/drinks/$today").obj()["caffeineMg"]!!.jsonPrimitive.content.toDouble(), 0.01)
        assertEquals(HttpStatusCode.OK, admin.putJson("/api/drinks/${drink.str("id")}", """{"at":${at(today, 9)},"kind":"COFFEE","volumeMl":300,"caffeineMg":120}""").status)
        assertEquals(30, admin.get("/api/drinks/summary").obj()["days"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/symptoms/entries", """{"at":${at(today, 10)},"symptom":"Headache","severity":4}""").status)
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/symptoms/migraines", """{"start":${at(today, 11)},"severity":6}""").status)
        assertEquals(1, admin.get("/api/symptoms/migraines").json().jsonArray.size)
        assertEquals(2, admin.get("/api/symptoms/summary").obj()["counts"]!!.jsonArray.size)
        // Vitals, habits and symptoms aren't on: Today leaves them out; drinks come with food.
        val day = s.days.summary(s.admin(), today.toString())
        assertNull(day.modules.vitals)
        assertNull(day.modules.symptoms)
        assertNotNull(day.drinks)
        assertTrue(day.insights.none { it.module == Module.SYMPTOMS || it.module == Module.VITALS })
        // Weather off: place search says so.
        s.settings.update { it.copy(weather = false) }
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/places?q=Leeds").status)
    }

    @Test
    fun `connector links see symptoms only when given them, and log the rest when allowed`() = serverTest(services()) { s ->
        val admin = adminBrowser()
        s.tracking.symptoms.save(s.admin(), SymptomEntry(at(today, 9), "headache", 5, triggers = listOf("screens")))
        suspend fun tools(url: String) = createClient { }.post(url) { json("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") }
            .obj()["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject.str("name") }
        suspend fun call(url: String, tool: String, args: String = "{}") = createClient { }.post(url) {
            json("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$args}}""")
        }.obj()["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject.str("text")
        suspend fun link(body: String) = admin.postJson("/api/me/connectors", body).obj().str("url").substringAfter("localhost")

        val plain = link("{}")
        val plainTools = tools(plain)
        assertTrue(plainTools.containsAll(listOf("vitals", "drinks", "stress", "habits")))
        assertFalse("symptoms" in plainTools)
        assertFalse("log_blood_pressure" in plainTools)

        val logging = link("""{"allowLogging":true,"modules":["SYMPTOMS"]}""")
        assertTrue(tools(logging).containsAll(listOf("symptoms", "log_symptom", "log_blood_pressure", "log_glucose", "log_drink", "tick_habit")))
        assertTrue(call(logging, "symptoms").contains("headache 5/10; triggers screens"))
        assertTrue(call(logging, "log_blood_pressure", """{"systolic":142,"diastolic":91,"pulse":70}""").startsWith("Logged 142/91, pulse 70 (high (stage 2))"))
        assertTrue(call(logging, "vitals").contains("142/91 (high (stage 2)), pulse 70"))
        assertTrue(call(logging, "log_glucose", """{"value":99,"unit":"mg/dL","context":"FASTING"}""").startsWith("Logged 5.5 mmol/L (fasting)"))
        assertTrue(call(logging, "log_drink", """{"kind":"WINE"}""").contains("1 drink"))
        assertTrue(call(logging, "log_symptom", """{"symptom":"migraine","severity":7,"aura":true}""").startsWith("Logged a migraine"))
        val day = call(logging, "day")
        assertTrue(day, day.contains("Vitals: blood pressure 142/91"))
        assertTrue(day, day.contains("Symptoms: headache 5/10"))
        assertTrue(call(plain, "day").let { "Symptoms:" !in it })
        assertTrue(call(logging, "trends", """{"days":3}""").lines().first().contains("systolic\tdiastolic"))
    }
}
