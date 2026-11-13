package com.health.app.ui.today

import com.health.api.Appointment
import com.health.api.AppointmentQuestion
import com.health.api.BladderTraining
import com.health.api.ContinenceDay
import com.health.api.CycleToday
import com.health.api.DaySummary
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.DueDose
import com.health.api.Meal
import com.health.api.MedForm
import com.health.api.MedsDay
import com.health.api.MindDay
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.NeuroToday
import com.health.api.Nutrients
import com.health.api.QuickAction
import com.health.api.ScreenLayout
import com.health.api.Supply
import com.health.api.SupplyKind
import com.health.api.SupplyStatus
import com.health.api.TodaySection
import com.health.api.TransitionToday
import com.health.app.ui.food.FoodSections
import com.health.app.ui.today.TodayPlan.Kind
import com.health.app.ui.today.TodayPlan.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** What goes in Up next, what collapses into Also today, the Quick log row and the + Log sheet, and food without numbers. */
class TodayPlanTest {
    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val today: LocalDate = LocalDate.of(2026, 11, 13)
    private fun at(h: Int, m: Int = 0, day: LocalDate = today): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(14, 10)

    /** Times as newer Java writes them ("3:30 PM" with a narrow space), with an ordinary space. */
    private fun String.plain() = replace(' ', ' ')

    private val modules = Module.DEFAULT + setOf(Module.MEDICATIONS, Module.MIND, Module.CYCLE, Module.CONTINENCE, Module.NEURO, Module.TRANSITION, Module.APPOINTMENTS, Module.HABITS, Module.SYMPTOMS)
    private val all = TodayLayout.sections(modules)

    private fun dose(id: String, name: String, h: Int, log: DoseLog? = null) = DueDose(id, name, "1 mg", MedForm.PILL, at(h), log)

    private val busy = DaySummary(
        date = today.toString(),
        nutrition = Nutrients(1180.0, 54.0),
        modules = ModulesToday(
            meds = MedsDay(
                today.toString(),
                due = listOf(
                    dose("p", "Progesterone", 8),
                    dose("e", "Estradiol", 13),
                    dose("s", "Spironolactone", 15),
                    dose("n", "Night estradiol", 22),
                    dose("t", "Taken one", 9, DoseLog("t", at(9), DoseStatus.TAKEN, at(9))),
                ),
            ),
            appointments = listOf(
                Appointment(at(15, 30), "Voice therapy", with = "Sam", questions = listOf(AppointmentQuestion("Pitch range?")), id = "a1"),
                Appointment(at(10), "Physio, this morning", durationMinutes = 45, id = "a0"),
                Appointment(at(11, day = today.plusDays(2)), "Blood test", id = "a2"),
            ),
            neuro = NeuroToday(routinesDue = listOf("Evening wind-down", "Tidy up")),
            lowSupplies = listOf(SupplyStatus(Supply("Pads", SupplyKind.PAD, onHand = 4, id = "pads"), daysLeft = 2, low = true)),
            continence = ContinenceDay(today.toString(), voids = 3),
            mind = MindDay(today.toString()),
            habits = emptyList(),
            transition = TransitionToday(headline = "1 year, 3 months since you began"),
        ),
    )

    private val training = BladderTraining(enabled = true, intervalMinutes = 90, from = "08:00", to = "21:00", startedOn = today.toString())

    @Test
    fun `up next has overdue doses first, then what's timed, then a routine and restocks`() {
        val items = TodayPlan.upNext(busy, modules, all, now, zone, isToday = true, bladder = training)
        assertEquals(
            listOf(Kind.DOSE, Kind.DOSE, Kind.DOSE, Kind.APPOINTMENT, Kind.BLADDER, Kind.ROUTINE, Kind.RESTOCK),
            items.map { it.kind },
        )
        // Overdue first, by time; the one due at 3 pm is within two hours; the one at night and the one taken aren't.
        assertEquals(listOf("Progesterone", "Estradiol", "Spironolactone"), items.take(3).map { it.title })
        assertTrue(items[0].late && items[1].late)
        assertFalse(items[2].late)
        assertTrue(items[0].detail.plain().startsWith("Due 8:00 AM"))
        assertEquals("At 3:00 PM · 1 mg", items[2].detail.plain())
        assertEquals("Voice therapy with Sam", items[3].title)
        // Trips every 90 minutes from 08:00: the next after 14:10 is at 15:30.
        assertEquals("Toilet trip at 3:30 PM", items[4].title.plain())
        assertEquals("Evening wind-down", items[5].title)
        assertEquals("Still to do today, and 1 more", items[5].detail)
        assertEquals("Pads running low", items[6].title)
        assertEquals("4 left · about 2 days", items[6].detail)
        assertEquals("supplies/continence", items[6].route)
        // The appointment that's over isn't there; the one later this week is for the Appointments card.
        assertFalse(items.any { it.title.startsWith("Physio") })
        assertEquals("1 question to ask", items[3].detail.substringAfterLast(" · "))
        assertEquals("appointments/edit?id=a1", items[3].route)
    }

    @Test
    fun `while Up next shows the doses and today's appointment, For you leaves out the insights that repeat them`() {
        val doses = com.health.api.Insight("info", "2 doses still to take", "Progesterone, Estradiol", "pill", Module.MEDICATIONS, shownBy = TodaySection.MEDS)
        val visit = com.health.api.Insight("info", "Voice therapy today at 15:30", "1 question to ask.", "calendar", Module.APPOINTMENTS, shownBy = TodaySection.APPOINTMENTS)
        val other = com.health.api.Insight("info", "Time for some water", "A glass now would help.", "droplet")
        val insights = listOf(doses, visit, other)
        val items = TodayPlan.upNext(busy, modules, all, now, zone, isToday = true)
        assertEquals(setOf(TodaySection.MEDS, TodaySection.APPOINTMENTS), TodayPlan.showing(items))
        assertEquals(listOf(other), InsightLinks.shown(insights, modules, TodayPlan.showing(items)))
        // Appointments hidden on Today: not in Up next, so its insight shows (and the doses' still doesn't).
        val noVisits = TodayPlan.upNext(busy, modules, all - TodaySection.APPOINTMENTS, now, zone, isToday = true)
        assertEquals(setOf(TodaySection.MEDS), TodayPlan.showing(noVisits))
        assertEquals(listOf(visit, other), InsightLinks.shown(insights, modules, TodayPlan.showing(noVisits)))
        // Hiding Autism & ADHD or Bladder & bowel takes their routine and trip out of Up next too.
        val fewer = TodayPlan.upNext(busy, modules, all - TodaySection.NEURO - TodaySection.CONTINENCE, now, zone, isToday = true, bladder = training)
        assertFalse(fewer.any { it.kind == Kind.ROUTINE || it.kind == Kind.BLADDER })
        assertTrue(TodayPlan.showing(emptyList()).isEmpty())
    }

    @Test
    fun `a dose shows from two hours before it's due`() {
        val at1230 = at(12, 30)
        val titles = TodayPlan.upNext(busy, setOf(Module.MEDICATIONS), all, at1230, zone, isToday = true).map { it.title }
        assertEquals(listOf("Progesterone", "Estradiol"), titles)
        assertEquals(listOf("Progesterone", "Estradiol", "Spironolactone"), TodayPlan.upNext(busy, setOf(Module.MEDICATIONS), all, at(13, 0), zone, isToday = true).map { it.title })
    }

    @Test
    fun `up next is only for today, and a hidden section's things stay out`() {
        assertTrue(TodayPlan.upNext(busy, modules, all, now, zone, isToday = false, bladder = training).isEmpty())
        val withoutMeds = TodayPlan.upNext(busy, modules, all - TodaySection.MEDS - TodaySection.SUPPLIES, now, zone, isToday = true, bladder = training)
        assertFalse(withoutMeds.any { it.kind == Kind.DOSE || it.kind == Kind.RESTOCK })
        // Bladder training only with its settings (never while viewing a share, when there are none).
        assertFalse(TodayPlan.upNext(busy, modules, all, now, zone, isToday = true, bladder = null).any { it.kind == Kind.BLADDER })
        // Nothing to do: nothing at all.
        assertTrue(TodayPlan.upNext(DaySummary(today.toString()), modules, all, now, zone, isToday = true).isEmpty())
    }

    @Test
    fun `features with nothing today collapse into Also today, in the person's order`() {
        val upNext = TodayPlan.upNext(busy, modules, all, now, zone, isToday = true, bladder = training)
        val day = TodayPlan.Day(busy, modules, upNext, now, zone)
        // Doses are in Up next, supplies too: no card and no row.
        assertEquals(Place.NONE, TodayPlan.place(TodaySection.MEDS, day))
        assertEquals(Place.NONE, TodayPlan.place(TodaySection.SUPPLIES, day))
        // Something today: a card.
        assertEquals(Place.CARD, TodayPlan.place(TodaySection.CONTINENCE, day))
        assertEquals(Place.CARD, TodayPlan.place(TodaySection.TRANSITION, day))
        assertEquals(Place.CARD, TodayPlan.place(TodaySection.FOOD, day))
        assertEquals(Place.CARD, TodayPlan.place(TodaySection.APPOINTMENTS, day))
        // Readings only devices give: nowhere when there are none.
        assertEquals(Place.NONE, TodayPlan.place(TodaySection.HEART, day))
        assertEquals(Place.NONE, TodayPlan.place(TodaySection.WORKOUTS, day))
        val also = TodayPlan.also(all, day)
        assertEquals(
            listOf(
                TodaySection.MIND, TodaySection.CYCLE, TodaySection.NEURO, TodaySection.SYMPTOMS, TodaySection.HABITS,
                TodaySection.ACTIVITY, TodaySection.SLEEP, TodaySection.WATER, TodaySection.BODY, TodaySection.DRINKS,
            ).sortedBy { all.indexOf(it) },
            also,
        )
        assertEquals("None set up yet", TodayPlan.alsoStatus(TodaySection.HABITS, day, isToday = true))
        assertEquals("Nothing yet", TodayPlan.alsoStatus(TodaySection.MIND, day, isToday = true))
        assertEquals("Nothing logged", TodayPlan.alsoStatus(TodaySection.MIND, day, isToday = false))
        // Moved to the top, Body leads Also today too.
        assertEquals(TodaySection.BODY, TodayPlan.also(listOf(TodaySection.BODY) + (all - TodaySection.BODY), day).first())
    }

    @Test
    fun `logging something moves a feature from Also today to its card`() {
        val logged = busy.copy(modules = busy.modules.copy(mind = MindDay(today.toString(), mood = 4.0, entries = 1), cycle = CycleToday(cycleDay = 12)))
        val day = TodayPlan.Day(logged, modules, emptyList(), now, zone)
        assertEquals(Place.CARD, TodayPlan.place(TodaySection.MIND, day))
        assertEquals(Place.CARD, TodayPlan.place(TodaySection.CYCLE, day))
        // Medications: with no doses in Up next and none marked, a quiet row that says when the next one is.
        assertEquals(Place.ALSO, TodayPlan.place(TodaySection.MEDS, day.copy(summary = logged.copy(modules = logged.modules.copy(meds = MedsDay(today.toString(), listOf(dose("n", "Night", 22))))))))
        val later = day.copy(summary = logged.copy(modules = logged.modules.copy(meds = MedsDay(today.toString(), listOf(dose("n", "Night", 22))))))
        assertEquals("Next at 10:00 PM", TodayPlan.alsoStatus(TodaySection.MEDS, later, isToday = true).plain())
        // All marked: nothing at all ("All medications taken" is For you's).
        val done = logged.copy(modules = logged.modules.copy(meds = MedsDay(today.toString(), listOf(dose("t", "Taken", 9, DoseLog("t", at(9)))))))
        assertEquals(Place.NONE, TodayPlan.place(TodaySection.MEDS, day.copy(summary = done)))
        // Only Mind on: its energy budget has no row of its own when empty (Mind's stands for it).
        assertEquals(Place.NONE, TodayPlan.place(TodaySection.NEURO, TodayPlan.Day(DaySummary(today.toString()), setOf(Module.MIND))))
    }

    @Test
    fun `quick log is the first five for the features on, then More, and the + Log sheet has every way to log, by feature`() {
        val on = Module.DEFAULT + setOf(Module.MIND, Module.CYCLE, Module.CONTINENCE, Module.MEDICATIONS)
        val available = TodayLayout.quickActions(on)
        val row = TodayLayout.quickRow(available, null)
        assertEquals(listOf(QuickAction.FOOD, QuickAction.WATER, QuickAction.MOOD, QuickAction.PERIOD, QuickAction.BLADDER), row.shown)
        assertEquals(available.size - 5, row.more.size)
        val groups = TodayLayout.logGroups(available, null)
        // Every action once, grouped by feature in the order they first come.
        assertEquals(available.toSet(), groups.flatMap { it.second }.toSet())
        assertEquals(available.size, groups.sumOf { it.second.size })
        assertEquals(listOf(Module.NUTRITION, Module.MIND, Module.CYCLE, Module.CONTINENCE, Module.MEDICATIONS), groups.take(5).map { it.first })
        assertEquals(listOf(QuickAction.FOOD, QuickAction.WATER, QuickAction.COFFEE, QuickAction.DRINK), groups.first().second)
        assertTrue(groups.any { it.first == null && it.second == listOf(QuickAction.CHECK_IN) })
        // Arranged: the person's order leads the groups too.
        assertEquals(Module.MIND, TodayLayout.logGroups(available, ScreenLayout(listOf("CALM", "FOOD"))).first().first)
        // Short words under the round buttons, and a name for screen readers.
        assertTrue(available.all { QuickActions.short(it, com.health.api.Units.METRIC).length <= 8 })
        assertEquals("Add 250 ml of water", QuickActions.spoken(QuickAction.WATER, com.health.api.Units.METRIC))
    }

    @Test
    fun `without food numbers, Food says which meals are logged and the Food tab has no totals`() {
        assertEquals("Breakfast, lunch logged", TodayPlan.mealsLogged(listOf(Meal.LUNCH, Meal.BREAKFAST, Meal.LUNCH)))
        assertEquals("Snacks logged", TodayPlan.mealsLogged(listOf(Meal.SNACK)))
        assertNull(TodayPlan.mealsLogged(emptyList()))
        assertNull(TodayPlan.mealsLogged(null))
        // Food logged with no amounts known still has its card.
        val day = TodayPlan.Day(DaySummary(today.toString()), Module.DEFAULT, meals = listOf(Meal.BREAKFAST))
        assertEquals(Place.CARD, TodayPlan.place(TodaySection.FOOD, day))
        assertEquals(Place.ALSO, TodayPlan.place(TodaySection.FOOD, day.copy(meals = emptyList())))
        assertFalse(FoodSections.items(water = true, numbers = false).any { it.id == FoodSections.TOTALS })
        assertTrue(FoodSections.items(water = true, numbers = true).any { it.id == FoodSections.TOTALS })
    }
}
