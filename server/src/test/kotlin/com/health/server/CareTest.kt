package com.health.server

import com.health.api.Appointment
import com.health.api.AppointmentQuestion
import com.health.api.Contraction
import com.health.api.CycleDay
import com.health.api.DaySummary
import com.health.api.Flow
import com.health.api.HormoneSettings
import com.health.api.Insight
import com.health.api.LabResult
import com.health.api.LabTarget
import com.health.api.LifeStage
import com.health.api.LifeStageInfo
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.MedsDay
import com.health.api.MindDay
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.MoodEntry
import com.health.api.Profile
import com.health.api.Score
import com.health.api.Screener
import com.health.api.ScreenerResult
import com.health.api.WebhookEvent
import com.health.server.care.LifeStages
import com.health.server.care.SharedView
import com.health.server.modules.MindService
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

class CareTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 3, 20)
    private val now = at(today, 15)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun services(modules: Set<Module> = Module.entries.toSet()) = testServices(clock = { now }).also { s ->
        runBlocking {
            s.bootstrap()
            s.profiles.put(s.users.byUsername("admin")!!.id, Profile(timezone = zone.id, modules = modules))
        }
    }

    private fun Services.admin() = runBlocking { users.byUsername("admin")!!.id }
    private fun Services.profile() = runBlocking { profiles.get(admin()) }

    // ---- Hormone therapy ----

    @Test
    fun `hormone medications keep a history of every dose change`() = runBlocking {
        val s = services()
        val id = s.admin()
        val med = s.modules.meds.save(id, Medication("Estradiol", "2 mg", schedule = MedSchedule(times = listOf("08:00", "20:00")), hormone = true, startedOn = "2025-06-01", stock = 60))
        s.modules.meds.save(id, med.copy(dose = "3 mg"), med.id)
        s.modules.meds.save(id, med.copy(dose = "3 mg", schedule = MedSchedule(times = listOf("09:00"))), med.id)
        // Counting a dose down changes neither dose nor schedule.
        s.modules.meds.logDose(id, com.health.api.DoseLog(med.id!!, now))
        // Other medications aren't part of it.
        val other = s.modules.meds.save(id, Medication("Vitamin D", "1000 IU"))
        s.modules.meds.save(id, other.copy(dose = "2000 IU"), other.id)

        val overview = s.hormones.overview(id)
        val estradiol = overview.medications.single()
        assertEquals("2025-06-01", estradiol.since)
        assertEquals(3, estradiol.changes.size)
        assertEquals(null, estradiol.changes[0].from)
        assertEquals("2 mg · daily at 08:00, 20:00", estradiol.changes[0].to)
        assertEquals(
            setOf("2 mg · daily at 08:00, 20:00" to "3 mg · daily at 08:00, 20:00", "3 mg · daily at 08:00, 20:00" to "3 mg · daily at 09:00"),
            estradiol.changes.drop(1).map { it.from to it.to }.toSet(),
        )
    }

    @Test
    fun `labs are due on time, and a result outside the person's range is said plainly`() = runBlocking {
        val s = services()
        val id = s.admin()
        s.modules.meds.save(id, Medication("Testosterone", "50 mg", hormone = true))
        s.hormones.saveSettings(id, HormoneSettings(labsEveryWeeks = 12, targets = listOf(LabTarget("Estradiol", "pmol/L", 100.0, 200.0))))
        s.modules.meds.saveLab(id, LabResult("Estradiol", 150.0, "pmol/L", at(today.minusWeeks(20), 9)))
        s.modules.meds.saveLab(id, LabResult("estradiol", 250.0, "pmol/L", at(today.minusWeeks(11), 9)))

        val overview = s.hormones.overview(id)
        val series = overview.labs.single()
        assertEquals(2, series.results.size)
        assertEquals(100.0, series.target!!.low!!, 0.0)
        assertEquals(today.plusWeeks(1).toString(), overview.nextLabsDue)
        val (card, insights) = s.hormones.today(id, today)
        assertEquals(listOf("estradiol"), card!!.outOfRange.map { it.lowercase() })
        val titles = insights.map { it.second.title }
        assertTrue(titles.toString(), titles.any { it.endsWith("is above your target range") })
        assertTrue(insights.first { it.second.title.endsWith("range") }.second.body.contains("Worth mentioning to your clinician"))
        assertTrue(titles.toString(), "Labs due soon" in titles)
        assertTrue(s.hormones.today(id, today.plusWeeks(2)).second.any { it.second.title == "Labs are due" })
        // And on Today, tagged as hormone therapy.
        val day = s.modules.today(id, s.profile(), today)
        assertEquals(1, day.modules.hormones!!.medications)
        assertTrue(day.insights.all { it.second.module != null })
    }

    // ---- Life stages ----

    @Test
    fun `pregnancy weeks come from the due date or the last period`() {
        val byDue = LifeStages.pregnancy(LifeStageInfo(LifeStage.PREGNANT, dueDate = today.plusDays(280 - 100).toString()), today)!!
        assertEquals(14, byDue.week)
        assertEquals(2, byDue.day)
        assertEquals(2, byDue.trimester)
        assertEquals(180, byDue.daysToGo)
        val byPeriod = LifeStages.pregnancy(LifeStageInfo(LifeStage.PREGNANT, lastPeriod = today.minusDays(30).toString()), today)!!
        assertEquals(4, byPeriod.week)
        assertEquals(1, byPeriod.trimester)
        assertEquals(today.minusDays(30).plusDays(280).toString(), byPeriod.dueDate)
    }

    @Test
    fun `contractions in the 5-1-1 pattern say it's time to call`() {
        val five = (0 until 14).map { i -> val start = now - (13 - i) * 5 * 60_000L - 60_000L; Contraction(start, start + 60_000L) }
        assertTrue(LifeStages.contractionsPattern(five, now)!!.startsWith("About every 5 minutes"))
        val apart = (0 until 4).map { i -> val start = now - (3 - i) * 12 * 60_000L - 50_000L; Contraction(start, start + 40_000L) }
        val text = LifeStages.contractionsPattern(apart, now)!!
        assertTrue(text, text.startsWith("Last hour: 4 contractions, about every 12 minutes"))
        assertNull(LifeStages.contractionsPattern(apart.take(2), now))
    }

    @Test
    fun `predictions pause while pregnant and widen in perimenopause`() = runBlocking {
        val s = services()
        val id = s.admin()
        for (start in listOf(today.minusDays(84), today.minusDays(56), today.minusDays(28))) {
            for (d in 0L until 4L) s.modules.cycle.put(id, CycleDay(start.plusDays(d).toString(), flow = Flow.MEDIUM))
        }
        val plain = s.modules.cycle.overview(id, 12)
        assertNotNull(plain.prediction)

        s.lifeStages.saveInfo(id, LifeStageInfo(LifeStage.PERIMENOPAUSE))
        val peri = s.modules.cycle.overview(id, 12)
        assertEquals("low", peri.prediction!!.confidence)
        assertTrue(LocalDate.parse(peri.prediction!!.earliest) <= LocalDate.parse(peri.prediction!!.nextPeriod).minusDays(7))

        try {
            s.lifeStages.saveInfo(id, LifeStageInfo(LifeStage.PREGNANT))
            error("A pregnancy needs a date")
        } catch (e: ApiError) {
            assertEquals(io.ktor.http.HttpStatusCode.BadRequest, e.status)
        }
        s.lifeStages.saveInfo(id, LifeStageInfo(LifeStage.PREGNANT, lastPeriod = today.minusDays(60).toString()))
        val pregnant = s.modules.cycle.overview(id, 12)
        assertNull(pregnant.prediction)
        assertNull(pregnant.daysUntilPeriod)
        assertTrue(pregnant.caveat.contains("paused"))
        val day = s.modules.today(id, s.profile(), today)
        assertEquals(LifeStage.PREGNANT, day.modules.lifeStage!!.stage)
        assertEquals("Week 8, day 4 · first trimester", day.modules.lifeStage!!.headline)
        // No "period likely" insights while pregnant.
        assertTrue(day.insights.none { it.second.title.contains("Period") })
    }

    @Test
    fun `high blood pressure in pregnancy says to contact the midwife today`() = runBlocking {
        val s = services()
        val id = s.admin()
        s.lifeStages.saveInfo(id, LifeStageInfo(LifeStage.PREGNANT, dueDate = today.plusDays(60).toString()))
        s.db.tx {
            update(
                "INSERT INTO vitals (id, user_id, kind, at, date, v1, v2, created_at) VALUES ('bp1', ?, 'BP', ?, ?, 145, 92, ?)",
                id, now - 3_600_000L, today.toString(), now,
            )
        }
        val day = s.modules.today(id, s.profile(), today)
        val warn = day.insights.map { it.second }.first { it.title == "Your blood pressure is high for pregnancy" }
        assertEquals("warn", warn.tone)
        assertTrue(warn.body.contains("145/92"))
        assertTrue(s.lifeStages.overview(id, 30).tips.any { it.title == "If movements slow down or stop" })
    }

    @Test
    fun `a year without a period is noticed in perimenopause, and bleeding after menopause is flagged`() = runBlocking {
        val s = services()
        val id = s.admin()
        s.modules.cycle.put(id, CycleDay(today.minusMonths(13).toString(), flow = Flow.LIGHT))
        s.lifeStages.saveInfo(id, LifeStageInfo(LifeStage.PERIMENOPAUSE))
        assertEquals(13, s.lifeStages.overview(id, 30).monthsSinceLastPeriod)
        assertTrue(s.modules.today(id, s.profile(), today).insights.any { it.second.title == "A year since your last period" })

        s.lifeStages.saveInfo(id, LifeStageInfo(LifeStage.MENOPAUSE, since = today.minusMonths(1).toString()))
        s.modules.cycle.put(id, CycleDay(today.minusDays(2).toString(), flow = Flow.SPOTTING))
        val warn = s.modules.today(id, s.profile(), today).insights.map { it.second }.first { it.title == "Bleeding after menopause is worth checking" }
        assertEquals("warn", warn.tone)
        assertNull(s.modules.cycle.overview(id, 12).prediction)
        // Hot flashes count on Today and in trends.
        s.lifeStages.saveHotFlash(id, com.health.api.HotFlash(now - 60_000, 3))
        assertEquals(1, s.modules.today(id, s.profile(), today).modules.lifeStage!!.hotFlashesToday)
        assertEquals(1, s.days.trends(id, today, today, null).days.single().hotFlashes)
    }

    @Test
    fun `the EPDS counts some questions in reverse and its last question brings up support`() {
        val epds = MindService.SCREENERS.first { it.id == Screener.EPDS }
        assertEquals(10, epds.items.size)
        assertEquals(10, epds.itemOptions!!.size)
        assertEquals(9, epds.flagItem)
        // The first answer to every question: 0 for 1, 2 and 4; 3 for the rest, the last among them.
        val first = MindService.score(ScreenerResult(Screener.EPDS, 0, List(10) { 0 }))
        assertEquals(21, first.score)
        assertEquals("High", first.severity)
        assertTrue(first.flagged)
        // The last answer to every question: 3 for 1, 2 and 4; 0 for the rest.
        val last = MindService.score(ScreenerResult(Screener.EPDS, 0, List(10) { 3 }))
        assertEquals(9, last.score)
        assertEquals("Low", last.severity)
        assertFalse(last.flagged)
        // PHQ-9 still flags on its last question.
        assertTrue(MindService.score(ScreenerResult(Screener.PHQ9, 0, listOf(0, 0, 0, 0, 0, 0, 0, 0, 1))).flagged)
        assertFalse(MindService.score(ScreenerResult(Screener.GAD7, 0, List(7) { 3 })).flagged)
    }

    // ---- Appointments ----

    @Test
    fun `appointments gather questions from every feature that's on, leaving out ones already asked`() = runBlocking {
        val s = services()
        val id = s.admin()
        for (d in 1L..6L) s.modules.mind.saveMood(id, MoodEntry(at(today.minusDays(d), 12), mood = 2))
        val vitaminD = s.modules.meds.save(id, Medication("Vitamin D", "1000 IU", schedule = MedSchedule(times = listOf("08:00"), startDate = today.minusDays(10).toString()), stock = 2))
        s.modules.meds.save(id, Medication("Estradiol", "2 mg", hormone = true))
        s.hormones.saveSettings(id, HormoneSettings(targets = listOf(LabTarget("Estradiol", "pmol/L", 100.0, 200.0))))
        s.modules.meds.saveLab(id, LabResult("Estradiol", 90.0, "pmol/L", at(today.minusDays(5), 9)))
        val appointment = s.appointments.save(
            id,
            Appointment(at(today.plusDays(3), 14, 30), "Check-up", with = "Dr Patel", questions = listOf(AppointmentQuestion("Can I have a new prescription for ${vitaminD.name}?"))),
        )
        val suggestions = s.appointments.suggestions(id, appointment.id!!)
        val modules = suggestions.map { it.module }.toSet()
        assertTrue(suggestions.toString(), Module.MIND in modules)
        assertTrue(suggestions.toString(), Module.MEDICATIONS in modules)
        assertTrue(suggestions.toString(), Module.HORMONES in modules)
        assertTrue(suggestions.any { it.text.startsWith("I've missed") && it.text.contains("Vitamin D") })
        assertTrue(suggestions.none { it.text == "Can I have a new prescription for Vitamin D?" })
        // Turned-off features don't add any.
        s.profiles.put(id, s.profile().copy(modules = Module.entries.toSet() - Module.MIND))
        assertTrue(s.appointments.suggestions(id, appointment.id!!).none { it.module == Module.MIND })
    }

    @Test
    fun `appointments remind before, without names on a locked screen, and show on Today`() = runBlocking {
        val s = services()
        val id = s.admin()
        val a = s.appointments.save(id, Appointment(at(today, 17), "Gender clinic follow-up", with = "Dr Okafor", remindBefore = listOf(1440, 60)))
        val reminders = s.appointments.reminders(id, s.profile(), at(today, 15, 55), at(today, 16, 5))
        val r = reminders.single()
        assertEquals(WebhookEvent.APPOINTMENT_SOON, r.event)
        assertEquals(at(today, 16), r.at)
        assertEquals("Appointment coming up", r.privateTitle)
        assertFalse(r.privateBody.contains("Gender"))
        assertTrue(r.body.contains("Dr Okafor"))
        assertEquals("appointment:${a.id}:${a.at}:60", r.key)
        val day = s.modules.today(id, s.profile(), today)
        assertEquals(1, day.modules.appointments!!.size)
        assertTrue(day.insights.any { it.second.title == "Gender clinic follow-up today at 5:00 PM" })
        // Sealed like every sensitive entry.
        s.lifeStages.saveInfo(id, LifeStageInfo(LifeStage.POSTPARTUM, birthDate = today.minusDays(20).toString()))
        val raw = s.db.tx { query("SELECT data FROM entries") { it.string("data") } }
        assertTrue(raw.isNotEmpty())
        assertTrue(raw.none { "Gender" in it || "Okafor" in it || "POSTPARTUM" in it })
    }

    // ---- The AI connector ----

    @Test
    fun `the connector's care tools need their features in the link's scope`() = runBlocking {
        val s = services()
        val user = s.users.byUsername("admin")!!
        s.modules.meds.save(user.id, Medication("Estradiol", "2 mg", hormone = true))
        val mcp = com.health.server.mcp.McpServer(s)
        suspend fun tools(scope: Set<Module>, canLog: Boolean = false): List<String> {
            val list = kotlinx.serialization.json.buildJsonObject {
                put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0")); put("id", kotlinx.serialization.json.JsonPrimitive(1)); put("method", kotlinx.serialization.json.JsonPrimitive("tools/list"))
            }
            return mcp.handle(com.health.server.mcp.McpServer.Caller(user, false, canLog, scope), list)!!["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject.str("name") }
        }
        assertTrue(tools(emptySet()).none { it in setOf("hormones", "life_stage", "appointments") })
        val all = tools(setOf(Module.HORMONES, Module.CYCLE, Module.APPOINTMENTS), canLog = true)
        assertTrue(all.toString(), all.containsAll(listOf("hormones", "life_stage", "appointments", "log_hot_flash")))
        assertFalse(tools(setOf(Module.CYCLE)).contains("log_hot_flash"))
        val call = kotlinx.serialization.json.buildJsonObject {
            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0")); put("id", kotlinx.serialization.json.JsonPrimitive(2)); put("method", kotlinx.serialization.json.JsonPrimitive("tools/call"))
            put("params", kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive("hormones")); put("arguments", kotlinx.serialization.json.JsonObject(emptyMap())) })
        }
        val text = mcp.handle(com.health.server.mcp.McpServer.Caller(user, false, false, setOf(Module.HORMONES)), call).toString()
        assertTrue(text, text.contains("Estradiol: 2 mg"))
    }

    // ---- Sharing ----

    @Test
    fun `a day shows a viewer only what's shared`() {
        val day = DaySummary(
            date = today.toString(),
            readiness = Score(80, "Good", emptyList()),
            steps = 5000,
            hrv = 50.0,
            weightKg = 70.0,
            modules = ModulesToday(mind = MindDay(today.toString(), mood = 4.0), meds = MedsDay(today.toString(), emptyList())),
            insights = listOf(
                Insight("info", "Short on sleep", "", "moon"),
                Insight("good", "Step goal reached", "", "footprints"),
                Insight("info", "Something new", "", "a-new-icon"),
                Insight("info", "All medications taken", "", "pill", Module.MEDICATIONS),
                Insight("info", "A hard day", "", "heart", Module.MIND),
            ),
        )
        val seen = SharedView.limit(day, setOf(Module.ACTIVITY, Module.MEDICATIONS))
        assertNull(seen.readiness)
        assertNull(seen.hrv)
        assertNull(seen.weightKg)
        assertEquals(5000, seen.steps)
        assertNull(seen.modules.mind)
        assertNotNull(seen.modules.meds)
        assertEquals(listOf("Step goal reached", "All medications taken"), seen.insights.map { it.title })
    }

    @Test
    fun `sharing lets another account read what's shared, and nothing else`() = serverTest(services()) { s ->
        val ownerId = s.admin()
        val sam = s.users.create("sam", "Sam", password = "sam-password-123")
        s.users.create("kim", "Kim", password = "kim-password-123")
        s.modules.mind.saveMood(ownerId, MoodEntry(now - 60_000, mood = 4))
        s.modules.meds.save(ownerId, Medication("Estradiol", "2 mg"))
        val owner = adminBrowser()

        assertEquals(HttpStatusCode.BadRequest, owner.postJson("/api/shares", """{"username":"admin","modules":["ACTIVITY"]}""").status)
        assertEquals(HttpStatusCode.BadRequest, owner.postJson("/api/shares", """{"username":"nobody","modules":["ACTIVITY"]}""").status)
        assertEquals(HttpStatusCode.BadRequest, owner.postJson("/api/shares", """{"username":"sam","modules":[]}""").status)
        val made = owner.postJson("/api/shares", """{"username":"sam","modules":["ACTIVITY","MEDICATIONS"]}""")
        assertEquals(HttpStatusCode.OK, made.status)
        val shareId = made.obj().str("id")

        val viewer = browser().also { it.post("/api/auth/login") { json("""{"username":"sam","password":"sam-password-123"}""") } }
        val received = viewer.get("/api/shares").obj()["received"]!!.jsonArray
        assertEquals("Administrator", received.single().jsonObject.str("ownerName"))
        suspend fun look(path: String) = viewer.get(path) { header("X-SAMH-View", ownerId) }

        val day = look("/api/day/today")
        assertEquals(HttpStatusCode.OK, day.status)
        val modules = day.obj()["modules"]!!.jsonObject
        assertNotNull(modules["meds"])
        assertNull(modules["mind"])
        assertEquals(HttpStatusCode.OK, look("/api/trends?days=7").status)
        assertEquals("Estradiol", look("/api/meds").json().jsonArray.single().jsonObject.str("name"))
        val profile = look("/api/profile").obj()
        assertEquals(setOf("ACTIVITY", "MEDICATIONS"), profile["modules"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertNull(profile["birthYear"])

        // Not shared, never shareable, or a change: refused.
        assertEquals(HttpStatusCode.Forbidden, look("/api/mind/moods").status)
        assertEquals(HttpStatusCode.Forbidden, look("/api/me").status)
        assertEquals(HttpStatusCode.Forbidden, look("/api/admin/users").status)
        assertEquals(HttpStatusCode.Forbidden, look("/api/devices").status)
        val write = viewer.post("/api/meds") { csrf(); header("X-SAMH-View", ownerId); json("""{"name":"Something"}""") }
        assertEquals(HttpStatusCode.Forbidden, write.status)
        assertTrue(write.bodyAsText().contains("can't change"))
        assertEquals(1, s.modules.meds.list(ownerId).size)
        // Someone it isn't shared with sees nothing.
        val kim = browser().also { it.post("/api/auth/login") { json("""{"username":"kim","password":"kim-password-123"}""") } }
        assertEquals(HttpStatusCode.Forbidden, kim.get("/api/day/today") { header("X-SAMH-View", ownerId) }.status)
        // The owner sees when it was last looked at; stopping sharing ends it.
        assertNotNull(owner.get("/api/shares").obj()["given"]!!.jsonArray.single().jsonObject["lastViewedAt"])
        assertEquals(HttpStatusCode.OK, owner.delete("/api/shares/$shareId") { csrf() }.status)
        assertEquals(HttpStatusCode.Forbidden, look("/api/day/today").status)
        assertEquals(sam.id, s.users.byUsername("sam")!!.id)
    }
}
