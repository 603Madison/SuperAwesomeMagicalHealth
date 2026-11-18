package com.health.app.ui.care

import com.health.api.Appointment
import com.health.api.AppointmentQuestion
import com.health.api.ContinenceDay
import com.health.api.ContinenceSummary
import com.health.api.ContinenceWear
import com.health.api.ContinenceWearing
import com.health.api.DaySummary
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.DueDose
import com.health.api.Fields
import com.health.api.HormoneOverview
import com.health.api.HormoneSettings
import com.health.api.HormonesToday
import com.health.api.LabTarget
import com.health.api.Meal
import com.health.api.MealiePlan
import com.health.api.MealiePlanEntry
import com.health.api.MeasurementSet
import com.health.api.MedForm
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.MedsDay
import com.health.api.Metric
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.NeuroToday
import com.health.api.Nutrients
import com.health.api.NutritionTarget
import com.health.api.Routine
import com.health.api.Supply
import com.health.api.SupplyKind
import com.health.api.SupplyStatus
import com.health.api.TransitionOverview
import com.health.api.TransitionProfile
import com.health.api.TransitionToday
import com.health.api.VoicePlan
import com.health.api.VoiceProgress
import com.health.api.VoiceSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The Care tab's cards, order and words, without a phone. */
class CareTabLogicTest {
    init {
        Locale.setDefault(Locale.US)
    }

    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 11, 17)
    private fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(today, 13, 40)
    private val clock: (Long) -> String = { DateTimeFormatter.ofPattern("H:mm").format(Instant.ofEpochMilli(it).atZone(zone)) }
    private val fields = Fields.DEFAULTS + setOf(Fields.CONTINENCE_PROTECTION, Fields.CONTINENCE_SUPPLIES, Fields.TRANSITION_VOICE, Fields.TRANSITION_MEASUREMENTS)

    private fun ctx(
        modules: Set<Module> = Module.entries.toSet(),
        at: Long = now,
        readOnly: Boolean = false,
        foodNumbers: Boolean = true,
        photosLocked: Boolean = false,
        viewing: Boolean = false,
        fields: Set<String> = this.fields,
    ) = CareContext(at, zone, today, modules, fields, foodNumbers, readOnly, photosLocked = photosLocked, viewing = viewing, clock = clock)

    // ---- Sample data

    private val spiro = Medication("Spironolactone", "100 mg", schedule = MedSchedule(times = listOf("09:00", "21:00")), stock = 36, id = "sp", hormone = true)
    private fun dose(h: Int, taken: Boolean) = DueDose(
        "sp", "Spironolactone", "100 mg", MedForm.PILL, at(today, h),
        log = if (taken) DoseLog("sp", at(today, h, 5), DoseStatus.TAKEN, at(today, h)) else null,
    )

    private val wearing = ContinenceWearing(
        supplyId = "nb", name = "Night briefs", kind = SupplyKind.BRIEF, since = at(today, 6, 40), changeDueAt = at(today, 14, 40),
        wear = ContinenceWear("nb", "Night briefs", SupplyKind.BRIEF, current = true),
    )

    private fun contDay(date: LocalDate, voids: Int, leaks: Int = 0) = ContinenceDay(date.toString(), voids = voids, leaks = leaks)

    /** Two weeks: 4 leaks the week before, 1 this week (none today), and 5 trips today. */
    private val summary = ContinenceSummary(
        (13 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            contDay(d, voids = if (back == 0) 5 else 6 + back % 3, leaks = if (back in 7..10) 1 else if (back == 3) 1 else 0)
        },
    )

    private val day = DaySummary(
        today.toString(),
        nutrition = Nutrients(calories = 1240.0, protein = 62.0),
        nutritionTarget = NutritionTarget(1900, 90, 200, 60, 2000, "Your goal"),
        modules = ModulesToday(
            meds = MedsDay(today.toString(), listOf(dose(9, taken = true), dose(21, taken = false))),
            continence = contDay(today, 5).copy(wearing = wearing),
            hormones = HormonesToday(1, "2027-01-12"),
            neuro = NeuroToday(energyLeft = 6, routinesDue = listOf("Evening routine")),
            transition = TransitionToday("1 year, 3 months since you began", milestonesDue = listOf("Name change")),
        ),
    )

    private val hormones = HormoneOverview(emptyList(), emptyList(), HormoneSettings(12, listOf(LabTarget("Estradiol", "pg/mL", 100.0, 200.0))), lastLabs = "2026-10-12", nextLabsDue = "2027-01-12")

    private val voice = VoiceProgress(VoicePlan(targetLowHz = 165.0, targetHighHz = 220.0, reminder = "19:00"), nextLessonId = "l3", streakDays = 4)
    private val transition = TransitionOverview(
        TransitionProfile(startedOn = "2025-08-04"),
        sinceStart = "1 year, 3 months",
        voice = listOf(VoiceSession(at(today, 8), 10)),
        measurements = listOf(MeasurementSet(at(LocalDate.of(2026, 11, 3), 9), mapOf(Metric.WAIST to 81.0))),
    )

    private val data = CareData(
        day = day,
        meds = listOf(spiro),
        hormones = hormones,
        wearing = wearing,
        continence = summary,
        supplies = listOf(SupplyStatus(Supply("Night briefs", SupplyKind.BRIEF, onHand = 14, id = "nb"), perDay = 2.0, daysLeft = 7)),
        appointments = listOf(
            Appointment(at(LocalDate.of(2026, 11, 19), 14, 30), "Check-up", 30, "Dr Patel", questions = listOf(AppointmentQuestion("Dose?"), AppointmentQuestion("Sleep?"), AppointmentQuestion("Done", done = true)), id = "a1"),
            Appointment(at(LocalDate.of(2026, 12, 2), 9, 0), "Physio", id = "a2"),
        ),
        plan = MealiePlan(today.toString(), listOf(MealiePlanEntry(Meal.LUNCH, "Soup", logged = true), MealiePlanEntry(Meal.DINNER, "Chili"))),
        transition = transition,
        voice = voice,
        photos = 12,
        routines = listOf(Routine("Evening routine", emptyList(), reminder = "21:00", id = "r1")),
    )

    private fun card(area: CareArea, d: CareData = data, c: CareContext = ctx()) = CareCards.card(area, d, c)

    // ---- Which areas, and in what order

    @Test
    fun `areas follow the features that are on, with hormones, supplies and photos inside their own areas`() {
        assertEquals(listOf(CareArea.FOOD), CareArea.shown(Module.DEFAULT))
        val all = CareArea.shown(Module.entries.toSet())
        assertFalse(CareArea.HORMONES in all)
        assertFalse(CareArea.PHOTOS in all)
        assertEquals(
            listOf("FOOD", "MIND", "NEURO", "CYCLE", "CONTINENCE", "MEDICATIONS", "VITALS", "HABITS", "SYMPTOMS", "TRANSITION", "APPOINTMENTS"),
            all.map { it.navId },
        )
        // Without the area they belong to, hormone therapy and photos have cards of their own.
        assertEquals(listOf(CareArea.HORMONES, CareArea.PHOTOS), CareArea.shown(setOf(Module.HORMONES, Module.PHOTOS)))
        assertEquals("Bladder & bowel", CareArea.CONTINENCE.title)
        assertEquals("meds", CareArea.MEDICATIONS.route)
        assertTrue(CareArea.shown(emptySet()).isEmpty())
    }

    @Test
    fun `anything due in the next few hours comes first, soonest first, then the person's order`() {
        val cards = listOf(
            CareCard(CareArea.FOOD),
            CareCard(CareArea.CONTINENCE, dueAt = now + 60 * 60_000L),
            CareCard(CareArea.MIND),
            CareCard(CareArea.MEDICATIONS, dueAt = now - 30 * 60_000L),
            CareCard(CareArea.APPOINTMENTS, dueAt = now + 5 * 60 * 60_000L),
        )
        assertEquals(
            listOf(CareArea.MEDICATIONS, CareArea.CONTINENCE, CareArea.FOOD, CareArea.MIND, CareArea.APPOINTMENTS),
            CareOrder.dueFirst(cards, now).map { it.area },
        )
        // Nothing due: the order as it was.
        assertEquals(listOf(CareArea.FOOD, CareArea.MIND), CareOrder.dueFirst(listOf(CareCard(CareArea.FOOD), CareCard(CareArea.MIND)), now).map { it.area })
    }

    @Test
    fun `what each area loads, and never Mealie through a share or photos while locked`() {
        val parts = CareParts.needed(ctx())
        assertTrue(listOf(CarePart.DAY, CarePart.MEDS, CarePart.HORMONES, CarePart.WEARING, CarePart.SUPPLIES, CarePart.PLAN, CarePart.VOICE, CarePart.PHOTOS, CarePart.ROUTINES).all { it in parts })
        assertFalse(CarePart.LABS in parts)
        assertFalse(CarePart.MEALS in parts)
        assertFalse(CarePart.PLAN in CareParts.needed(ctx(viewing = true)))
        assertFalse(CarePart.PHOTOS in CareParts.needed(ctx(photosLocked = true)))
        assertTrue(CarePart.MEALS in CareParts.needed(ctx(foodNumbers = false)))
        assertTrue(CarePart.LABS in CareParts.needed(ctx(modules = setOf(Module.MEDICATIONS))))
        assertEquals(emptySet<CarePart>(), CareParts.needed(ctx(modules = emptySet())))
    }

    // ---- Medications, with hormone therapy

    @Test
    fun `medications show the next dose with Take, what's taken, supply left and hormone labs`() {
        val c = card(CareArea.MEDICATIONS)
        val row = c.now!!
        assertEquals("Spironolactone · 21:00", row.title)
        assertEquals("1 of 2 taken today · 18 days left", row.detail)
        assertEquals(CareActionKind.TAKE, row.action?.kind)
        assertEquals("Take Spironolactone", row.action?.description)
        assertEquals(at(today, 21), row.action?.dose?.scheduledFor)
        assertEquals(listOf("Hormone levels: last lab 12 Oct, in your range · next due 12 Jan"), c.lines)
        // 21:00 is more than three hours off at 13:40; at 19:00 it's due.
        assertNull(c.dueAt)
        assertEquals(at(today, 21), card(CareArea.MEDICATIONS, c = ctx(at = at(today, 19))).dueAt)
    }

    @Test
    fun `viewing a share, nothing can be done from a card`() {
        assertNull(card(CareArea.MEDICATIONS, c = ctx(readOnly = true)).now!!.action)
        assertNull(card(CareArea.CONTINENCE, c = ctx(readOnly = true)).now!!.action)
        assertNull(card(CareArea.TRANSITION, c = ctx(readOnly = true)).now!!.action)
    }

    @Test
    fun `every dose marked, a level outside the range, or no hormone therapy`() {
        val done = data.copy(
            day = day.copy(modules = day.modules.copy(meds = MedsDay(today.toString(), listOf(dose(9, true), dose(21, true))), hormones = HormonesToday(1, "2026-11-10", listOf("Estradiol")))),
            hormones = hormones.copy(nextLabsDue = "2026-11-10"),
        )
        val c = card(CareArea.MEDICATIONS, done)
        assertNull(c.now)
        assertEquals(listOf("All 2 doses taken today", "Hormone levels: last lab 12 Oct, Estradiol outside your range · labs were due 10 Nov"), c.lines)
        // Without hormone therapy, the last lab of any kind.
        val labs = data.copy(labs = listOf(com.health.api.LabResult("Ferritin", 40.0, "ng/mL", at(LocalDate.of(2026, 11, 3), 9))))
        assertEquals("Last lab: Ferritin, 3 Nov", card(CareArea.MEDICATIONS, labs, ctx(modules = setOf(Module.MEDICATIONS))).lines.last())
        assertEquals("Nothing scheduled today", CareWords.dosesDone(0, 0))
        assertEquals("Today's dose is taken", CareWords.dosesDone(1, 1))
    }

    // ---- Bladder & bowel, with supplies

    @Test
    fun `bladder and bowel shows what's on with Change, the week, today's counts and supplies`() {
        val c = card(CareArea.CONTINENCE)
        assertEquals("Wearing Night briefs", c.now!!.title)
        assertEquals("Dry so far · on since 6:40 (7 h)", c.now!!.detail)
        assertEquals(CareActionKind.CHANGE, c.now!!.action?.kind)
        assertEquals(7, c.week!!.size)
        assertTrue(c.week!!.last().today)
        assertEquals(5, c.week!!.last().trips)
        assertEquals(1, c.week!!.sumOf { it.leaks })
        assertEquals(listOf("5 trips today, no leaks · 1 leak this week, down from 4", "Night briefs: 14 left · about a week"), c.lines)
        // The change reminder is within the next few hours, so the card comes first.
        assertEquals(at(today, 14, 40), c.dueAt)
        val due = card(CareArea.CONTINENCE, c = ctx(at = at(today, 15)))
        assertEquals("Time to change: Night briefs", due.now!!.title)
    }

    @Test
    fun `without products there's no wearing row, and with nothing on it's Put on`() {
        val plain = card(CareArea.CONTINENCE, data.copy(wearing = null, day = day.copy(modules = day.modules.copy(continence = contDay(today, 5)))), ctx(fields = Fields.DEFAULTS))
        assertNull(plain.now)
        assertEquals(1, plain.lines.size)
        val off = card(CareArea.CONTINENCE, data.copy(wearing = ContinenceWearing(since = at(today, 12), nothingOn = true)))
        assertEquals("Nothing on for now", off.now!!.title)
        assertEquals(CareActionKind.PUT_ON, off.now!!.action?.kind)
        assertNull(off.dueAt)
    }

    @Test
    fun `the week's chart reads out each day and the leaks`() {
        val week = CareWords.week(summary.days, null, today)
        val said = CareWords.weekDescription(week)
        assertTrue(said, said.startsWith("Toilet trips over the last 7 days: Wednesday "))
        assertTrue(said, said.contains("today 5"))
        assertTrue(said, said.endsWith("Leaks: Saturday 1."))
    }

    @Test
    fun `trip and leak words`() {
        assertEquals("1 trip today, 2 leaks", CareWords.trips(null, contDay(today, 1, 2), today))
        assertEquals("1 leak today", CareWords.trips(null, contDay(today, 0, 1), today))
        assertNull(CareWords.trips(null, contDay(today, 0), today))
        // Only this week in the diary: no comparison.
        assertEquals("2 trips today, no leaks · no leaks this week", CareWords.trips(listOf(contDay(today, 2)), null, today))
        assertEquals("about a day", CareWords.lasts(1))
        assertEquals("about 4 days", CareWords.lasts(4))
        assertEquals("about 2 weeks", CareWords.lasts(14))
        assertEquals("a month or more", CareWords.lasts(40))
    }

    // ---- Appointments, Food, Transition, Autism & ADHD

    @Test
    fun `the next appointment, when, and questions ready`() {
        val c = card(CareArea.APPOINTMENTS)
        assertEquals("Next appointment", c.title)
        assertEquals("Thursday 19 November, 14:30", c.subtitle)
        assertEquals(listOf("Check-up with Dr Patel · 2 questions ready to ask"), c.lines)
        assertNull(c.dueAt)
        assertEquals(listOf("Nothing coming up"), card(CareArea.APPOINTMENTS, data.copy(appointments = emptyList())).lines)
        val soon = card(CareArea.APPOINTMENTS, c = ctx(at = at(LocalDate.of(2026, 11, 19), 13)))
        assertEquals(at(LocalDate.of(2026, 11, 19), 14, 30), soon.dueAt)
    }

    @Test
    fun `food shows progress, protein and tonight's plan, or only the meals when amounts are hidden`() {
        val c = card(CareArea.FOOD)
        assertEquals(1240f / 1900f, c.bar!!, 0.001f)
        assertEquals(listOf("1,240 of 1,900 kcal · 62 of 90 g protein · tonight: Chili"), c.lines)
        val hidden = card(CareArea.FOOD, data.copy(meals = listOf(Meal.LUNCH, Meal.BREAKFAST)), ctx(foodNumbers = false))
        assertNull(hidden.bar)
        assertEquals(listOf("Breakfast, lunch logged · tonight: Chili"), hidden.lines)
        assertFalse(hidden.lines.single().contains("kcal"))
    }

    @Test
    fun `transition shows voice practice with Start, then measurements, photos and what's coming`() {
        val c = card(CareArea.TRANSITION)
        assertEquals("Voice practice", c.now!!.title)
        assertEquals("4 days in a row · 10 minutes today", c.now!!.detail)
        assertEquals("transition/voice-lesson?id=l3", c.now!!.action?.route)
        assertEquals(listOf("Measurements last taken 3 Nov · 12 photos · coming up: Name change"), c.lines)
        // Practised today: the reminder isn't due.
        assertNull(c.dueAt)
        // Locked photos don't even say how many.
        assertEquals("Measurements last taken 3 Nov · photos locked · coming up: Name change", card(CareArea.TRANSITION, c = ctx(photosLocked = true)).lines.single())
    }

    @Test
    fun `transition shows only the parts the person has on`() {
        val bare = card(CareArea.TRANSITION, c = ctx(modules = setOf(Module.TRANSITION), fields = Fields.DEFAULTS))
        assertNull(bare.now)
        assertEquals(listOf("Coming up: Name change"), bare.lines)
        val since = card(CareArea.TRANSITION, data.copy(day = day.copy(modules = day.modules.copy(transition = TransitionToday("1 year, 3 months since you began")))), ctx(modules = setOf(Module.TRANSITION), fields = Fields.DEFAULTS))
        assertEquals(listOf("1 year, 3 months since you began"), since.lines)
        // Not practised today, and the reminder is in a couple of hours.
        val due = card(CareArea.TRANSITION, data.copy(transition = transition.copy(voice = emptyList())), ctx(at = at(today, 17)))
        assertEquals("4 days in a row", due.now!!.detail)
        assertEquals(at(today, 19), due.dueAt)
    }

    @Test
    fun `autism and ADHD says energy and the next routine, gently`() {
        val c = card(CareArea.NEURO, c = ctx(at = at(today, 19, 30)))
        assertEquals(listOf("Energy: 6 left · Evening routine at 21:00"), c.lines)
        assertEquals(at(today, 21), c.dueAt)
        val alot = card(CareArea.NEURO, data.copy(day = day.copy(modules = day.modules.copy(neuro = NeuroToday(overloadsToday = 2)))))
        assertEquals(listOf("Today's been a lot 2 times. Go gently."), alot.lines)
    }

    // ---- Waiting, failing, and nothing yet

    @Test
    fun `a card says it's loading, that it couldn't load, or that there's nothing yet`() {
        val empty = CareData()
        assertTrue(CareCards.card(CareArea.MIND, empty, ctx(), pending = setOf(CarePart.DAY)).loading)
        assertEquals(listOf(CareCards.COULD_NOT_LOAD), CareCards.card(CareArea.MIND, empty, ctx(), failed = setOf(CarePart.DAY)).lines)
        // The day came, with nothing for the mind: plainly so.
        assertEquals(listOf("No check-in yet today"), CareCards.card(CareArea.MIND, CareData(day = DaySummary(today.toString())), ctx()).lines)
        assertEquals(listOf("Nothing logged today"), CareCards.card(CareArea.SYMPTOMS, CareData(day = DaySummary(today.toString())), ctx()).lines)
        // One part failing never blanks the others' lines.
        val partly = CareCards.card(CareArea.MEDICATIONS, data.copy(hormones = null), ctx(), failed = setOf(CarePart.HORMONES))
        assertEquals("Spironolactone · 21:00", partly.now!!.title)
    }

    @Test
    fun `supply left, by schedule or by count`() {
        assertEquals("18 days left", CareWords.supplyLeft(spiro, zone, today))
        assertEquals("5 left", CareWords.supplyLeft(spiro.copy(stock = 5, schedule = MedSchedule(com.health.api.ScheduleKind.AS_NEEDED)), zone, today))
        assertEquals("less than a day left", CareWords.supplyLeft(spiro.copy(stock = 1), zone, today))
        assertNull(CareWords.supplyLeft(spiro.copy(stock = null), zone, today))
    }
}
