package com.health.server

import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.CycleDay
import com.health.api.CyclePhase
import com.health.api.DatedValue
import com.health.api.DoseLog
import com.health.api.Flow
import com.health.api.JournalEntry
import com.health.api.MedForm
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.Module
import com.health.api.MoodEntry
import com.health.api.Profile
import com.health.api.ScheduleKind
import com.health.api.Screener
import com.health.api.ScreenerResult
import com.health.api.SleepSession
import com.health.server.modules.CycleMath
import com.health.server.modules.MindService
import com.health.server.modules.Schedules
import com.health.server.util.Days
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ModulesTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 3, 20)
    private val now = at(today, 21)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun services(modules: Set<Module> = Module.entries.toSet()) = testServices(clock = { now }).also { s ->
        runBlocking {
            s.bootstrap()
            s.profiles.put(s.users.byUsername("admin")!!.id, Profile(timezone = zone.id, modules = modules))
        }
    }

    private fun Services.admin() = runBlocking { users.byUsername("admin")!!.id }

    // ---- Encryption ----

    @Test
    fun `entries are encrypted in the database`() = runBlocking {
        val s = services()
        val id = s.admin()
        s.modules.mind.saveJournal(id, JournalEntry(now, text = "Nobody should read this but me"))
        s.modules.meds.save(id, Medication("Estradiol", "2 mg"))
        val raw = s.db.tx { query("SELECT data FROM entries") { it.string("data") } + query("SELECT data FROM medications") { it.string("data") } }
        assertEquals(2, raw.size)
        assertTrue(raw.none { "read this" in it || "Estradiol" in it })
        assertEquals("Nobody should read this but me", s.modules.mind.journal(id, 7).single().text)
        assertEquals("Estradiol", s.modules.meds.list(id).single().name)
    }

    // ---- Mind ----

    @Test
    fun `screeners are scored, banded, and PHQ-9's last question flags support`() {
        val mild = MindService.score(ScreenerResult(Screener.PHQ9, 0, listOf(1, 1, 1, 1, 1, 1, 0, 0, 0)))
        assertEquals(6, mild.score)
        assertEquals("Mild", mild.severity)
        assertFalse(mild.flagged)
        val flagged = MindService.score(ScreenerResult(Screener.PHQ9, 0, listOf(3, 3, 3, 2, 2, 2, 2, 2, 1)))
        assertEquals(20, flagged.score)
        assertEquals("Severe", flagged.severity)
        assertTrue(flagged.flagged)
        assertEquals("Moderate", MindService.score(ScreenerResult(Screener.GAD7, 0, listOf(2, 2, 2, 2, 1, 1, 0))).severity)
    }

    @Test
    fun `mood comes with patterns, and the summary counts feelings`() = runBlocking {
        val s = services()
        val id = s.admin()
        // Ten days: good nights come with better moods.
        for (back in 1..10) {
            val date = today.minusDays(back.toLong())
            val longNight = back % 2 == 0
            val bed = at(date.minusDays(1), 23)
            s.ingest.manualSleep(id, SleepSession(bed, bed + (if (longNight) 8 else 5) * 3_600_000L))
            s.modules.mind.saveMood(id, MoodEntry(at(date, 12), if (longNight) 4 else 2, feelings = listOf(if (longNight) "calm" else "tired")))
        }
        val summary = s.modules.mind.summary(id, 14)
        assertEquals(3.0, summary.averageMood!!, 0.01)
        assertEquals(setOf("calm", "tired"), summary.feelings.map { it.name }.toSet())
        val sleepPattern = summary.patterns.firstOrNull { it.title.contains("7 hours") }
        assertNotNull(sleepPattern)
        assertTrue(sleepPattern!!.body.startsWith("Averaging 4.0, against 2.0"))
    }

    // ---- Cycle ----

    private fun periods(vararg starts: LocalDate, length: Int = 5) = starts.flatMap { start ->
        (0 until length).map { CycleDay(start.plusDays(it.toLong()).toString(), flow = if (it < 2) Flow.HEAVY else Flow.LIGHT) }
    }

    @Test
    fun `regular cycles predict the next period and know the phase`() {
        val starts = (4 downTo 0).map { LocalDate.of(2026, 3, 1).minusDays(28L * it) }.toTypedArray()
        val o = CycleMath.analyze(periods(*starts) + CycleDay("2026-02-10", flow = Flow.SPOTTING), emptyList(), today)
        assertEquals(5, o.cycles.size)
        assertEquals(28.0, o.averageCycle!!, 0.01)
        assertEquals(5.0, o.averagePeriod!!, 0.01)
        val p = o.prediction!!
        assertEquals("2026-03-29", p.nextPeriod)
        assertEquals("high", p.confidence)
        assertEquals("2026-03-15", p.ovulation)
        assertEquals(20, o.cycleDay)
        assertEquals(9, o.daysUntilPeriod)
        assertEquals(CyclePhase.LUTEAL, o.phase)
        // Spotting alone doesn't start a period.
        assertTrue(o.cycles.none { it.start == "2026-02-10" })
    }

    @Test
    fun `a sustained temperature rise marks ovulation`() {
        val start = LocalDate.of(2026, 3, 1)
        val temps = (0 until 20).map { d -> DatedValue(start.plusDays(d.toLong()).toString(), if (d < 13) 36.4 + (d % 2) * 0.05 else 36.75) }
        assertEquals(LocalDate.of(2026, 3, 13), CycleMath.ovulation(temps, start, start.plusDays(27)))
        assertNull(CycleMath.ovulation(temps.take(12), start, start.plusDays(27)))
    }

    @Test
    fun `a late period is said so, with low confidence from little history`() {
        val o = CycleMath.analyze(periods(LocalDate.of(2026, 2, 10)), emptyList(), today)
        assertEquals("low", o.prediction!!.confidence)
        assertEquals(-10, o.daysUntilPeriod)
    }

    @Test
    fun `cycle days are logged over the API and shape the overview`() = serverTest(services()) { s ->
        val browser = adminBrowser()
        for (start in listOf("2026-01-24", "2026-02-21")) {
            for (d in 0..3) browser.putJson("/api/cycle/days/${LocalDate.parse(start).plusDays(d.toLong())}", """{"date":"x","flow":"MEDIUM","symptoms":["Cramps"]}""")
        }
        val overview = browser.get("/api/cycle").obj()
        assertEquals("28.0", overview.str("averageCycle"))
        assertEquals("2026-03-21", overview["prediction"]!!.jsonObject.str("nextPeriod"))
        // Clearing a day's details deletes it.
        browser.putJson("/api/cycle/days/2026-02-24", """{"date":"2026-02-24"}""")
        assertEquals(7, browser.get("/api/cycle").obj()["days"]!!.jsonArray.size)
        // Today shows the cycle, and the period due tomorrow.
        val day = browser.get("/api/day/today").obj()
        assertEquals("28", day["modules"]!!.jsonObject["cycle"]!!.jsonObject.str("cycleDay"))
        assertTrue(day["insights"]!!.jsonArray.any { it.jsonObject.str("title") == "Period likely tomorrow" })
        assertEquals(HttpStatusCode.OK, s.let { browser.get("/api/cycle?months=3").status })
    }

    // ---- Bladder and bowel ----

    @Test
    fun `the diary counts trips, night trips and leaks, and spots patterns`() = runBlocking {
        val s = services()
        val id = s.admin()
        for (back in 0..2) {
            val date = today.minusDays(back.toLong())
            val bed = at(date.minusDays(1), 23)
            s.ingest.manualSleep(id, SleepSession(bed, bed + 8 * 3_600_000L))
            s.modules.continence.save(id, ContinenceEntry(bed + 3 * 3_600_000L, ContinenceKind.URINATION))
            s.modules.continence.save(id, ContinenceEntry(bed + 5 * 3_600_000L, ContinenceKind.URINATION))
            listOf(9, 12, 15, 18).forEach { h -> s.modules.continence.save(id, ContinenceEntry(at(date, h), ContinenceKind.URINATION, urgency = 1)) }
            s.modules.continence.save(id, ContinenceEntry(at(date, 10), ContinenceKind.LEAK, triggers = listOf("Cough")))
        }
        val summary = s.modules.continence.summary(id, 3)
        assertEquals(6.0, summary.averageVoids!!, 0.01)
        assertEquals(2.0, summary.averageNightVoids!!, 0.01)
        assertEquals(1.0, summary.averageLeaks!!, 0.01)
        assertEquals("cough", summary.triggers.single().name)
        assertEquals(180, summary.averageInterval)
        assertTrue(summary.patterns.any { it.title.contains("coughs") })
        assertTrue(summary.patterns.any { it.title.startsWith("Up 2.0 times a night") })
        assertTrue(s.modules.continence.list(id, 1).filter { it.kind == ContinenceKind.URINATION }.count { it.night } == 2)
    }

    // ---- Medications ----

    @Test
    fun `schedules fall on the right days`() {
        val days = Days(zone)
        val daily = MedSchedule(ScheduleKind.DAILY, listOf("08:00", "20:00"))
        assertEquals(2, Schedules.times(daily, today, days).size)
        val every5 = MedSchedule(ScheduleKind.EVERY_N_DAYS, listOf("09:00"), everyDays = 5, startDate = "2026-03-10")
        assertEquals(1, Schedules.times(every5, LocalDate.of(2026, 3, 20), days).size)
        assertEquals(0, Schedules.times(every5, LocalDate.of(2026, 3, 21), days).size)
        assertEquals(0, Schedules.times(every5, LocalDate.of(2026, 3, 5), days).size)
        val weekly = MedSchedule(ScheduleKind.WEEKLY, listOf("10:00"), weekdays = listOf(5)) // Fridays
        assertEquals(1, Schedules.times(weekly, LocalDate.of(2026, 3, 20), days).size)
        assertEquals(0, Schedules.times(weekly, LocalDate.of(2026, 3, 19), days).size)
        assertEquals(0, Schedules.times(MedSchedule(ScheduleKind.AS_NEEDED, emptyList()), today, days).size)
    }

    @Test
    fun `doses are marked against the schedule, stock counts down and sites rotate`() = runBlocking {
        val s = services()
        val id = s.admin()
        val pill = s.modules.meds.save(id, Medication("Spironolactone", "50 mg", schedule = MedSchedule(times = listOf("08:00", "20:00")), stock = 10))
        val shot = s.modules.meds.save(
            id,
            Medication(
                "Estradiol valerate", "5 mg", MedForm.INJECTION,
                MedSchedule(ScheduleKind.EVERY_N_DAYS, listOf("09:00"), everyDays = 7, startDate = "2026-03-06"),
                sites = listOf("left thigh", "right thigh"),
            ),
        )
        // Last week's shot went in the left thigh.
        s.modules.meds.logDose(id, DoseLog(shot.id!!, at(LocalDate.of(2026, 3, 13), 9), site = "left thigh", scheduledFor = at(LocalDate.of(2026, 3, 13), 9)))
        val day = s.modules.meds.day(id, today)
        assertEquals(3, day.due.size)
        assertEquals("right thigh", day.due.first { it.medicationId == shot.id }.nextSite)

        val morning = day.due.first { it.medicationId == pill.id }
        s.modules.meds.logDose(id, DoseLog(pill.id!!, morning.scheduledFor + 600_000, scheduledFor = morning.scheduledFor))
        // Marking the same dose again replaces it rather than counting twice.
        s.modules.meds.logDose(id, DoseLog(pill.id!!, morning.scheduledFor + 900_000, scheduledFor = morning.scheduledFor))
        assertEquals(9, s.modules.meds.get(id, pill.id!!).stock)
        val after = s.modules.meds.day(id, today)
        assertNotNull(after.due.first { it.scheduledFor == morning.scheduledFor }.log)
        assertTrue(after.adherence7d != null)
        // The evening dose hasn't been taken: Today says so.
        val summary = s.days.summary(id, today.toString())
        val todo = summary.insights.firstOrNull { it.title.endsWith("still to take") }
        assertNotNull(todo)
        assertEquals(Module.MEDICATIONS, todo!!.module)
        // It opens Medications, and is left out while Today's Medications card shows the doses.
        assertEquals(com.health.api.AppPage.MEDS, todo.link)
        assertEquals(com.health.api.TodaySection.MEDS, todo.shownBy)
    }

    // ---- Profile, today and the AI connector ----

    @Test
    fun `modules that are off add nothing to the day`() = runBlocking {
        val s = services(Module.DEFAULT)
        val id = s.admin()
        s.modules.mind.saveMood(id, MoodEntry(now - 3_600_000, 1))
        val day = s.days.summary(id, today.toString())
        assertNull(day.modules.mind)
        assertTrue(day.insights.none { it.module != null })
        assertEquals(Module.DEFAULT, s.profiles.get(id).modules)
    }

    @Test
    fun `connector links read sensitive modules only when given them`() = serverTest(services()) { s ->
        val admin = adminBrowser()
        s.modules.cycle.put(s.admin(), CycleDay("2026-03-19", Flow.MEDIUM))
        suspend fun tools(url: String) = createClient { }.post(url) { json("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") }
            .obj()["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject.str("name") }
        suspend fun link(body: String) = admin.postJson("/api/me/connectors", body).obj().str("url").substringAfter("localhost")

        val plain = link("{}")
        assertFalse("cycle" in tools(plain))
        assertFalse("mind" in tools(plain))
        val refused = createClient { }.post(plain) { json("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"cycle","arguments":{}}}""") }.obj()
        assertTrue(refused["error"]!!.jsonObject.str("message").contains("wasn't given Cycle"))

        val cycleOnly = link("""{"modules":["CYCLE"]}""")
        assertTrue("cycle" in tools(cycleOnly))
        assertFalse("journal" in tools(cycleOnly))
        assertFalse("log_cycle_day" in tools(cycleOnly))
        val text = createClient { }.post(cycleOnly) { json("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"cycle","arguments":{}}}""") }
            .obj()["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject.str("text")
        assertTrue(text, text.contains("2026-03-19: flow medium"))

        val logging = link("""{"allowLogging":true,"modules":["MIND","MEDICATIONS"]}""")
        assertTrue(tools(logging).containsAll(listOf("mind", "journal", "log_mood", "medications", "log_dose")))
        assertFalse("log_cycle_day" in tools(logging))
        val sessions = admin.get("/api/me/sessions").json().jsonArray.map { it.jsonObject }
        assertTrue(sessions.any { it.str("name") == "AI connector (can log) + Mind, Medications" })

        // Every-account links never see them, whatever is asked for.
        val everyone = link("""{"allAccounts":true,"modules":["MIND","CYCLE"]}""")
        assertFalse(tools(everyone).any { it in setOf("mind", "journal", "cycle", "continence", "medications") })
    }

    @Test
    fun `admins set the support lines people see`() = serverTest(services()) {
        val admin = adminBrowser()
        assertTrue(admin.get("/api/mind/resources").json().jsonArray.any { it.jsonObject.str("contact") == "Call or text 988" })
        val settings = admin.get("/api/admin/settings").obj()["settings"]!!.jsonObject
        val changed = settings.toString().replace(Regex("\"crisisResources\":\\[.*?]\\s*(?=,\"oidc\")"), "\"crisisResources\":[{\"name\":\"Local line\",\"contact\":\"Call 0800 123\"}]")
        assertEquals(HttpStatusCode.OK, admin.putJson("/api/admin/settings", """{"settings":$changed}""").status)
        assertEquals("Local line", admin.get("/api/mind/resources").json().jsonArray.single().jsonObject.str("name"))
    }
}
