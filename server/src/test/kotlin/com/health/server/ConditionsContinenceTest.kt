package com.health.server

import com.health.api.BladderTraining
import com.health.api.ContinenceCheckIn
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceSettings
import com.health.api.CycleDay
import com.health.api.CycleSettings
import com.health.api.FieldChoices
import com.health.api.Fields
import com.health.api.Flow
import com.health.api.Fullness
import com.health.api.Module
import com.health.api.MyCondition
import com.health.api.MyConditions
import com.health.api.ProductUse
import com.health.api.Profile
import com.health.api.Supply
import com.health.api.SupplyAdjustment
import com.health.api.SupplyKind
import com.health.api.SupplyReason
import com.health.api.Verdict
import com.health.server.conditions.ConditionCatalog
import com.health.server.conditions.ConditionsService
import com.health.server.modules.BladderTrainingPlan
import com.health.server.modules.CycleHooks
import com.health.server.modules.CycleMath
import com.health.server.modules.Progress
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Conditions and goals, the deeper bladder and bowel diary, supplies, and cycle settings. */
class ConditionsContinenceTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 3, 20)
    private val now = at(today, 15)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun services(modules: Set<Module> = Module.DEFAULT) = testServices(clock = { now }).also { s ->
        runBlocking {
            s.bootstrap()
            s.profiles.put(s.users.byUsername("admin")!!.id, Profile(timezone = zone.id, modules = modules))
        }
    }

    private fun Services.admin() = runBlocking { users.byUsername("admin")!!.id }

    // ---- Conditions and fields ----

    @Test
    fun `the catalog only names real fields, and every entry is complete`() {
        val ids = ConditionCatalog.ALL.map { it.id }
        assertEquals("ids are unique", ids.size, ids.toSet().size)
        assertTrue("about 40 or more", ConditionCatalog.ALL.size >= 40)
        ConditionCatalog.ALL.forEach { c ->
            assertTrue(c.id, c.name.isNotBlank() && c.about.isNotBlank() && c.group.isNotBlank())
            c.fields.forEach { f -> assertTrue("${c.id}: $f", f in Fields.ALL) }
            c.modules.forEach { m -> assertTrue("${c.id}: $m", m in Module.entries) }
            // A field only makes sense with its feature: every field's feature is turned on too.
            c.fields.forEach { f -> assertTrue("${c.id}: $f needs ${Fields.ALL[f]!!.first}", Fields.ALL[f]!!.first in c.modules || Fields.ALL[f]!!.first == Module.BODY) }
        }
        // Recovery from an eating disorder never brings calorie targets.
        assertFalse(Module.NUTRITION in ConditionCatalog.byId("eating-disorder")!!.modules)
        Fields.DEFAULTS.forEach { assertTrue(it in Fields.ALL) }
    }

    @Test
    fun `the fields shown are the defaults, plus conditions, plus the person's own, less what's hidden`() {
        val layout = ConditionsService.layout(
            MyConditions(listOf(MyCondition("overactive-bladder", "Overactive bladder"))),
            FieldChoices(show = setOf(Fields.CYCLE_LH), hide = setOf(Fields.CYCLE_FERTILITY, Fields.CONTINENCE_SUPPLIES)),
        )
        assertTrue(Fields.CONTINENCE_URGENCY in layout.effective) // a default
        assertTrue(Fields.CONTINENCE_BLADDER_TRAINING in layout.effective) // from the condition
        assertEquals("Overactive bladder", layout.because[Fields.CONTINENCE_BLADDER_TRAINING])
        assertEquals("default", layout.because[Fields.CONTINENCE_URGENCY])
        assertEquals("you", layout.because[Fields.CYCLE_LH])
        assertFalse(Fields.CYCLE_FERTILITY in layout.effective)
        assertFalse("hidden wins over a condition", Fields.CONTINENCE_SUPPLIES in layout.effective)
    }

    @Test
    fun `picking conditions turns their features on, never off, and fields follow`() = serverTest(services()) { s ->
        val admin = adminBrowser()
        assertEquals(HttpStatusCode.OK, admin.get("/api/conditions/catalog").status)
        val put = admin.putJson("/api/conditions", """{"conditions":[{"id":"stress-incontinence","name":"x"},{"id":"custom:tinnitus","name":"Tinnitus","modules":["SYMPTOMS"]}]}""")
        assertEquals(HttpStatusCode.OK, put.status)
        val modules = s.profiles.get(s.admin()).modules
        assertTrue(Module.CONTINENCE in modules && Module.SYMPTOMS in modules && Module.ACTIVITY in modules)
        // The catalog's own name is kept.
        assertEquals("Stress incontinence", admin.get("/api/conditions").obj()["conditions"]!!.jsonArray[0].jsonObject.str("name"))
        val fields = admin.get("/api/fields").obj()
        assertTrue(fields["effective"]!!.jsonArray.any { it.jsonPrimitive.content == Fields.CONTINENCE_PAD_WEIGHT })
        // Taking a condition off doesn't turn features off.
        admin.putJson("/api/conditions", """{"conditions":[]}""")
        assertTrue(Module.CONTINENCE in s.profiles.get(s.admin()).modules)
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/conditions", """{"conditions":[{"id":"not-a-thing","name":"x"}]}""").status)
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/fields", """{"show":["continence.nope"]}""").status)
        val hidden = admin.putJson("/api/fields", """{"hide":["continence.urgency"]}""").obj()
        assertFalse(hidden["effective"]!!.jsonArray.any { it.jsonPrimitive.content == Fields.CONTINENCE_URGENCY })
    }

    // ---- Supplies ----

    @Test
    fun `supplies are counted down by the diary, and back up when entries change or go`() = runBlocking {
        val s = services(Module.DEFAULT + Module.CONTINENCE + Module.CYCLE)
        val me = s.admin()
        val pads = s.supplies.save(me, Supply("Night pads", SupplyKind.PAD, onHand = 20, dryWeightG = 30.0))
        val change = s.modules.continence.save(me, ContinenceEntry(now - 3_600_000, ContinenceKind.PAD_CHANGE, supplyId = pads.id, count = 2))
        assertEquals(18, s.supplies.get(me, pads.id!!)!!.onHand)
        s.modules.continence.save(me, change.copy(count = 1), change.id)
        assertEquals(19, s.supplies.get(me, pads.id!!)!!.onHand)
        s.modules.continence.delete(me, change.id!!)
        assertEquals(20, s.supplies.get(me, pads.id!!)!!.onHand)

        // Weighed: the loss is the wet weight less the dry weight.
        val leak = s.modules.continence.save(me, ContinenceEntry(now - 1_800_000, ContinenceKind.LEAK, supplyId = pads.id, padWeightG = 95.5, fullness = Fullness.WET))
        assertEquals(65.5, leak.lossG!!, 0.01)
        assertEquals(20, s.supplies.get(me, pads.id!!)!!.onHand) // wearing it isn't using it up

        // Period products from the cycle diary.
        val tampons = s.supplies.save(me, Supply("Tampons", SupplyKind.TAMPON, onHand = 10))
        s.modules.cycle.put(me, CycleDay(today.toString(), flow = Flow.HEAVY, products = listOf(ProductUse(SupplyKind.TAMPON, 3, supplyId = tampons.id))))
        assertEquals(7, s.supplies.get(me, tampons.id!!)!!.onHand)
        s.modules.cycle.put(me, CycleDay(today.toString(), flow = Flow.HEAVY, products = listOf(ProductUse(SupplyKind.TAMPON, 2, supplyId = tampons.id))))
        assertEquals(8, s.supplies.get(me, tampons.id!!)!!.onHand)
        s.modules.cycle.delete(me, today)
        assertEquals(10, s.supplies.get(me, tampons.id!!)!!.onHand)

        // By hand: restocked, then counted.
        assertEquals(30, s.supplies.adjust(me, pads.id!!, SupplyAdjustment(now, 10, SupplyReason.RESTOCKED)).onHand)
        assertEquals(5, s.supplies.adjust(me, pads.id!!, SupplyAdjustment(now, 5, SupplyReason.COUNTED)).onHand)
        assertEquals(listOf(SupplyReason.COUNTED, SupplyReason.RESTOCKED), s.supplies.log(me, pads.id!!, 30).map { it.reason })
    }

    @Test
    fun `a supply running low shows on Today and reminds once at refill time`() = runBlocking {
        val s = services(Module.DEFAULT + Module.CONTINENCE)
        val me = s.admin()
        val briefs = s.supplies.save(me, Supply("Briefs", SupplyKind.BRIEF, onHand = 20))
        // 3 a day for a week: about a week left, so low.
        for (d in 0L until 7L) s.modules.continence.save(me, ContinenceEntry(at(today.minusDays(d), 9), ContinenceKind.PAD_CHANGE, supplyId = briefs.id, count = 2))
        val status = s.supplies.status(me).single()
        assertEquals(6, status.supply.onHand)
        assertTrue(status.low)
        val day = s.days.summary(me, today.toString())
        assertEquals("Briefs", day.modules.lowSupplies!!.single().supply.name)
        assertTrue(day.insights.any { it.title == "Running low on Briefs" })
        val hooks = s.hooks.filterIsInstance<com.health.server.modules.SuppliesHooks>().single()
        val profile = s.profiles.get(me)
        val refill = at(today, 10)
        assertEquals("supplies:$today", hooks.reminders(me, profile, refill - 60_000, refill + 60_000).single().key)
        assertTrue(hooks.reminders(me, profile, at(today, 12), at(today, 12, 15)).isEmpty())
    }

    // ---- Bladder and bowel ----

    @Test
    fun `the detail is kept, checked, and counted`() = runBlocking {
        val s = services(Module.DEFAULT + Module.CONTINENCE)
        val me = s.admin()
        val c = s.modules.continence
        try {
            c.save(me, ContinenceEntry(now, ContinenceKind.LEAK, skin = 7))
            error("A skin score of 7 was taken")
        } catch (e: ApiError) {
            assertEquals(HttpStatusCode.BadRequest, e.status)
        }
        c.save(me, ContinenceEntry(at(today, 2), ContinenceKind.BEDWETTING, changedBedding = true))
        c.save(me, ContinenceEntry(at(today, 9), ContinenceKind.BOWEL_LEAK, leakedThrough = true))
        c.save(me, ContinenceEntry(at(today, 10), ContinenceKind.CATHETER, volumeMl = 350))
        c.save(me, ContinenceEntry(at(today, 11), ContinenceKind.URINATION, utiSigns = listOf("Burning ")))
        val d = c.daysOf(me, today, today)[today]!!
        assertEquals(1, d.wetNights)
        assertEquals(1, d.leaks) // the wet night
        assertEquals(1, d.bowelLeaks)
        assertEquals(1, d.leakedThrough)
        assertEquals(350, d.catheterMl)
        assertEquals(listOf("burning"), c.entriesOn(me, today).first { it.kind == ContinenceKind.URINATION }.utiSigns)
        val day = s.days.summary(me, today.toString())
        assertTrue(day.insights.any { it.title == "Signs that can go with a urine infection" && it.module == Module.CONTINENCE })
    }

    private fun entry(date: LocalDate, kind: ContinenceKind, hour: Int = 10) =
        ContinenceEntry(at(date, hour), kind, date = date.toString())

    @Test
    fun `progress says better, worse, or not enough yet`() {
        val firstWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(7)
        fun diary(earlierLeaks: Int, recentLeaks: Int): List<ContinenceEntry> = (0L until 56L).flatMap { d ->
            val date = firstWeek.plusDays(d)
            if (date.isAfter(today)) return@flatMap emptyList()
            val recent = !date.isBefore(firstWeek.plusWeeks(4))
            List(if (recent) recentLeaks else earlierLeaks) { entry(date, ContinenceKind.LEAK, 9 + it) } + entry(date, ContinenceKind.URINATION, 8)
        }
        val better = Progress.analyze(diary(3, 1), emptyList(), emptyMap(), firstWeek, 8, today)
        assertEquals(Verdict.IMPROVING, better.verdict)
        assertTrue(better.headline, better.headline.startsWith("Getting better"))
        assertEquals(8, better.weeks.size)
        val worse = Progress.analyze(diary(1, 3), emptyList(), emptyMap(), firstWeek, 8, today)
        assertEquals(Verdict.WORSENING, worse.verdict)
        val steady = Progress.analyze(diary(2, 2), emptyList(), emptyMap(), firstWeek, 8, today)
        assertEquals(Verdict.STEADY, steady.verdict)
        val few = listOf(entry(today, ContinenceKind.LEAK), entry(today.minusDays(1), ContinenceKind.LEAK))
        assertEquals(Verdict.NOT_ENOUGH, Progress.analyze(few, emptyList(), emptyMap(), firstWeek, 8, today).verdict)
        // Bother counts too, and twice.
        val checks = (0L until 8L).map { w ->
            ContinenceCheckIn(firstWeek.plusWeeks(w).plusDays(2).toString(), bother = if (w < 4) 8 else 3)
        }
        assertEquals(Verdict.IMPROVING, Progress.analyze(diary(2, 2), checks, emptyMap(), firstWeek, 8, today).verdict)
    }

    @Test
    fun `bladder training reminds at each interval while awake, stretching as planned`() {
        val days = Days(zone)
        val training = BladderTraining(enabled = true, intervalMinutes = 60, from = "08:00", to = "12:00", stepMinutes = 15, stepEveryDays = 7, goalMinutes = 90, startedOn = today.toString())
        val times = BladderTrainingPlan.times(training, days, days.start(today), days.end(today)).map { (at, _) -> LocalTime.ofSecondOfDay(days.minuteOfDay(at) * 60L) }
        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(10, 0), LocalTime.of(11, 0), LocalTime.of(12, 0)), times)
        assertEquals(75, BladderTrainingPlan.interval(training, today.plusDays(7)))
        assertEquals(90, BladderTrainingPlan.interval(training, today.plusDays(70))) // never past the goal
        val s = services(Module.DEFAULT + Module.CONTINENCE)
        runBlocking {
            s.modules.continence.putSettings(s.admin(), ContinenceSettings(bladderTraining = training))
            val hooks = s.hooks.filterIsInstance<com.health.server.modules.ContinenceHooks>().single()
            val due = hooks.reminders(s.admin(), s.profiles.get(s.admin()), at(today, 8, 55), at(today, 9, 5)).single()
            assertEquals("Reminder", due.privateTitle)
            assertFalse(due.privateBody.contains("toilet"))
        }
    }

    // ---- Cycle ----

    @Test
    fun `the person's own cycle and luteal lengths are used for predictions`() {
        val days = listOf(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 29), LocalDate.of(2026, 2, 26)).flatMap { start ->
            (0L until 4L).map { CycleDay(start.plusDays(it).toString(), flow = Flow.MEDIUM) }
        }
        val usual = CycleMath.analyze(days, emptyList(), LocalDate.of(2026, 3, 5))
        assertEquals("2026-03-26", usual.prediction!!.nextPeriod)
        val own = CycleMath.analyze(days, emptyList(), LocalDate.of(2026, 3, 5), cycleLength = 32, lutealLength = 12)
        assertEquals("2026-03-30", own.prediction!!.nextPeriod)
        assertEquals("2026-03-18", own.prediction!!.ovulation)
        assertEquals(4.0, CycleMath.analyze(emptyList(), emptyList(), LocalDate.of(2026, 3, 5), periodLength = 4).averagePeriod!!, 0.01)
    }

    @Test
    fun `cycle details are kept and checked, reminders come before a period, without fertility words`() = runBlocking {
        val s = services(Module.DEFAULT + Module.CYCLE)
        val me = s.admin()
        val c = s.modules.cycle
        try {
            c.put(me, CycleDay(today.toString(), symptomSeverity = mapOf("cramps" to 5)))
            error("A severity of 5 was taken")
        } catch (e: ApiError) {
            assertEquals(HttpStatusCode.BadRequest, e.status)
        }
        val saved = c.put(me, CycleDay(today.toString(), painAreas = listOf("Lower Back"), symptomSeverity = mapOf("Cramps" to 3), energy = 2))
        assertEquals(listOf("lower back"), saved.painAreas)
        assertEquals(mapOf("cramps" to 3), saved.symptomSeverity)
        c.delete(me, today)
        // Periods 28 days apart, the last on 2026-03-01: the next is due on the 29th.
        listOf(LocalDate.of(2026, 1, 4), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1)).forEach { start ->
            (0L until 4L).forEach { c.put(me, CycleDay(start.plusDays(it).toString(), flow = Flow.MEDIUM)) }
        }
        c.putSettings(me, CycleSettings(remindBeforePeriod = 9, neutralWording = true))
        val hooks = s.hooks.filterIsInstance<CycleHooks>().single()
        val reminders = hooks.reminders(me, s.profiles.get(me), at(today, 8, 55), at(today, 9, 5))
        assertEquals("Period likely in 9 days", reminders.single().title)
        reminders.forEach { r -> listOf("fertile", "ovulat").forEach { w -> assertFalse(r.title + r.body, (r.title + r.body).lowercase().contains(w)) } }
        assertEquals(HttpStatusCode.BadRequest.value, runCatching { c.putSettings(me, CycleSettings(cycleLength = 5)) }.exceptionOrNull().let { (it as ApiError).status.value })
    }

    @Test
    fun `neutral wording, or a hidden fertile window, keeps fertility words off Today`() = runBlocking {
        val s = services(Module.DEFAULT + Module.CYCLE)
        val me = s.admin()
        listOf(LocalDate.of(2026, 2, 5), LocalDate.of(2026, 3, 5)).forEach { start ->
            (0L until 4L).forEach { s.modules.cycle.put(me, CycleDay(start.plusDays(it).toString(), flow = Flow.MEDIUM)) }
        }
        s.lifeStages.saveInfo(me, com.health.api.LifeStageInfo(com.health.api.LifeStage.TRYING))
        fun words(d: com.health.api.DaySummary) = (d.insights.map { it.title + " " + it.body } + listOfNotNull(d.modules.lifeStage?.headline)).joinToString(" ").lowercase()
        assertTrue("fertility copy is there by default", words(s.days.summary(me, today.toString())).contains("fertile"))
        s.modules.cycle.putSettings(me, CycleSettings(neutralWording = true))
        assertFalse(words(s.days.summary(me, today.toString())).let { it.contains("fertile") || it.contains("ovulat") })
        s.modules.cycle.putSettings(me, CycleSettings())
        s.conditions.putChoices(me, FieldChoices(hide = setOf(Fields.CYCLE_FERTILITY)))
        assertFalse(words(s.days.summary(me, today.toString())).contains("fertile"))
    }

    // ---- Privacy ----

    @Test
    fun `supplies, settings and conditions are encrypted at rest`() = runBlocking {
        val s = services(Module.DEFAULT + Module.CONTINENCE)
        val me = s.admin()
        s.supplies.save(me, Supply("Tena Lady Maxi", SupplyKind.PAD, onHand = 3))
        s.conditions.put(me, MyConditions(listOf(MyCondition("overactive-bladder", "x", notes = "since my second child"))))
        s.modules.continence.putSettings(me, ContinenceSettings(triggers = listOf("running water at the sink")))
        val raw = s.db.tx {
            query("SELECT data FROM supplies") { it.string("data") } + query("SELECT data FROM user_settings") { it.string("data") }
        }.joinToString("\n")
        listOf("Tena", "overactive", "second child", "running water").forEach { assertFalse(it, raw.contains(it, ignoreCase = true)) }
        assertEquals("Tena Lady Maxi", s.supplies.list(me).single().name)
    }

    @Test
    fun `someone viewing a share sees shared supplies and fields, never conditions`() = serverTest(services(Module.DEFAULT + Module.CONTINENCE)) { s ->
        val ownerId = s.admin()
        s.supplies.save(ownerId, Supply("Pads", SupplyKind.PAD, onHand = 3))
        s.conditions.put(ownerId, MyConditions(listOf(MyCondition("overactive-bladder", "x"), MyCondition("autism", "x"))))
        s.users.create("sam", "Sam", password = "sam-password-123")
        assertEquals(HttpStatusCode.OK, adminBrowser().postJson("/api/shares", """{"username":"sam","modules":["CONTINENCE"]}""").status)
        val viewer = browser().also { it.post("/api/auth/login") { json("""{"username":"sam","password":"sam-password-123"}""") } }
        suspend fun look(path: String) = viewer.get(path) { header("X-SAMH-View", ownerId) }
        assertEquals("Pads", look("/api/supplies").json().jsonArray.single().jsonObject.str("name"))
        assertEquals(HttpStatusCode.Forbidden, look("/api/conditions").status)
        val fields = look("/api/fields").obj()
        val effective = fields["effective"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(Fields.CONTINENCE_BLADDER_TRAINING in effective)
        assertFalse("only shared features' fields", effective.any { it.startsWith("neuro.") })
        assertTrue("never why", fields["because"]!!.jsonObject.isEmpty())
        assertNull(look("/api/fields").obj()["because"]!!.jsonObject["continence.bladderTraining"])
    }
}
