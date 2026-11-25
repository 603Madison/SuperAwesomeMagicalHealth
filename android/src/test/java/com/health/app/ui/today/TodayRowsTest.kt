package com.health.app.ui.today

import com.health.api.AlertKind
import com.health.api.AlertLevel
import com.health.api.AppPage
import com.health.api.DaySummary
import com.health.api.HealthAlert
import com.health.api.Insight
import com.health.api.Module
import com.health.api.NowAction
import com.health.api.NowItem
import com.health.api.NowKind
import com.health.api.Point
import com.health.api.Score
import com.health.api.SleepStage
import com.health.api.SleepSummary
import com.health.api.StageSpan
import com.health.api.TimelineItem
import com.health.api.TimelineKind
import com.health.api.TodaySection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Today's Right now and Your day from the day feed: what each action does, where a tap goes, and the words around them. */
class TodayRowsTest {
    init {
        Locale.setDefault(Locale.US)
    }

    private val modules = Module.DEFAULT + setOf(Module.MEDICATIONS, Module.CONTINENCE, Module.NEURO, Module.APPOINTMENTS, Module.TRANSITION)

    private fun now(kind: NowKind, action: String?, ids: Map<String, String> = emptyMap(), priority: Int = 50, page: AppPage? = null, title: String = "Thing") =
        NowItem("$kind", kind, title, action = action?.let { NowAction("Go", it, ids) }, priority = priority, page = page)

    @Test
    fun `the greeting follows the time of day`() {
        assertEquals("Good morning", TodayRows.greeting(7))
        assertEquals("Good afternoon", TodayRows.greeting(13))
        assertEquals("Good evening", TodayRows.greeting(20))
        assertEquals("Good evening", TodayRows.greeting(2))
    }

    @Test
    fun `right now is most important first, without the alert that has the card`() {
        val alert = HealthAlert("a1", AlertKind.HIGH_HEART_RATE, AlertLevel.URGENT, "High heart rate", "", "Call 911 if…", start = 1, createdAt = 1)
        val items = listOf(
            now(NowKind.FOOD_TODAY, NowAction.FOOD_ADD, priority = 30),
            now(NowKind.HEALTH_ALERT, NowAction.ALERT_OPEN, mapOf("alertId" to "a1"), priority = 100),
            now(NowKind.DOSE_DUE, NowAction.DOSE_TAKE, priority = 80),
            now(NowKind.HEALTH_ALERT, NowAction.ALERT_OPEN, mapOf("alertId" to "a2"), priority = 60),
        )
        val shown = TodayRows.now(items, alert)
        assertEquals(listOf(NowKind.DOSE_DUE, NowKind.HEALTH_ALERT, NowKind.FOOD_TODAY), shown.map { it.kind })
        assertEquals("a2", shown[1].action?.ids?.get("alertId"))
        // Without a card, every alert is there, an urgent one in the danger colours.
        assertEquals(NowKind.HEALTH_ALERT, TodayRows.now(items).first().kind)
        assertTrue(TodayRows.urgent(items[1]))
        assertTrue(!TodayRows.urgent(items[3]))
        // A dose in Right now leaves its insight out of For you.
        assertEquals(setOf(TodaySection.MEDS), TodayRows.showing(shown))
    }

    @Test
    fun `the + sheet offers what can be logged there and then`() {
        val items = listOf(
            now(NowKind.WEARING, NowAction.CONTINENCE_CHANGE, priority = 70),
            now(NowKind.FOOD_TODAY, NowAction.FOOD_ADD, priority = 30),
            now(NowKind.DOSE_OVERDUE, NowAction.DOSE_TAKE, priority = 85),
            now(NowKind.APPOINTMENT_SOON, NowAction.APPOINTMENT_OPEN, priority = 50),
            now(NowKind.VOICE_PRACTICE_DUE, NowAction.VOICE_START, priority = 40),
        )
        assertEquals(listOf(NowKind.DOSE_OVERDUE, NowKind.WEARING, NowKind.VOICE_PRACTICE_DUE), TodayRows.logNow(items).map { it.kind })
    }

    @Test
    fun `each action goes to the app's own flow`() {
        val today = "2026-11-17"
        fun route(action: String, ids: Map<String, String> = emptyMap(), page: AppPage? = null, m: Set<Module> = modules) =
            TodayRows.route(now(NowKind.OTHER, action, ids, page = page), m, today)
        assertEquals("food/add/2026-11-17/DINNER", route(NowAction.FOOD_ADD, mapOf("meal" to "DINNER", "date" to today)))
        assertEquals("food/add/2026-11-17/SNACK", route(NowAction.FOOD_ADD))
        // Energy: Autism & ADHD's page (its quick check-in is a sheet, below); without it, Mind's own budget page.
        assertEquals("neuro", route(NowAction.ENERGY_ADD))
        assertEquals("neuro/energy", route(NowAction.ENERGY_ADD, m = setOf(Module.MIND)))
        assertEquals("transition/voice-lesson?id=resonance-1", route(NowAction.VOICE_START, mapOf("lessonId" to "resonance-1")))
        assertEquals("transition/voice-training", route(NowAction.VOICE_START))
        assertEquals("appointments/edit?id=ap1", route(NowAction.APPOINTMENT_OPEN, mapOf("appointmentId" to "ap1")))
        assertEquals("appointments/edit?id=ap2", route(NowAction.APPOINTMENT_OPEN, mapOf("id" to "ap2")))
        assertEquals("alerts", route(NowAction.ALERT_OPEN, mapOf("alertId" to "a1")))
        // The evening's wind-down: the player opens on that meditation.
        assertEquals("meditation?play=wind-down-10", route(NowAction.MEDITATION_OPEN, mapOf("meditationId" to "wind-down-10")))
        assertEquals("meditation", route(NowAction.MEDITATION_OPEN))
        // Done in place: the change sheet, taking the dose.
        assertNull(route(NowAction.CONTINENCE_CHANGE))
        assertNull(route(NowAction.DOSE_TAKE))
        // An action from a newer server opens the item's page.
        assertEquals("meds", route("something.new", page = AppPage.MEDS))
        assertEquals("Log food", TodayRows.spoken(now(NowKind.FOOD_TODAY, NowAction.FOOD_ADD)))
        assertEquals("Go Estradiol", TodayRows.spoken(now(NowKind.DOSE_DUE, NowAction.DOSE_TAKE, title = "Estradiol")))
    }

    @Test
    fun `energy is the quick check-in with autism and ADHD, and mind's budget page without`() {
        val check = NowItem("energy-check:x", NowKind.ENERGY_TODAY, "How are your energy and focus?", module = Module.NEURO, action = NowAction("Check in", NowAction.ENERGY_ADD))
        assertTrue(TodayRows.checksIn(check, modules, checkIns = true))
        // Check-ins off, or Autism & ADHD off (Mind's budget): a page instead.
        assertFalse(TodayRows.checksIn(check, modules, checkIns = false))
        val budget = NowItem("energy:x", NowKind.ENERGY_TODAY, "Energy today", module = Module.MIND, action = NowAction("Add", NowAction.ENERGY_ADD))
        assertFalse(TodayRows.checksIn(budget, setOf(Module.MIND), checkIns = false))
        assertEquals("neuro/energy", TodayRows.route(budget, setOf(Module.MIND), "2026-11-17"))
        // Nothing else is a check-in.
        assertFalse(TodayRows.checksIn(now(NowKind.FOOD_TODAY, NowAction.FOOD_ADD), modules, checkIns = true))
        // Said as what it does.
        assertEquals("Check in on your energy and focus", TodayRows.spoken(check))
        assertEquals("Log your energy", TodayRows.spoken(budget))
        // The + sheet offers it there and then.
        assertEquals(listOf(NowKind.ENERGY_TODAY), TodayRows.logNow(listOf(check, now(NowKind.FOOD_TODAY, NowAction.FOOD_ADD))).map { it.kind })
    }

    @Test
    fun `a tap on your day opens the entry where the app can`() {
        fun item(kind: TimelineKind, page: AppPage?, ref: String? = null) = TimelineItem("$kind", kind, 0, title = "x", page = page, ref = ref)
        assertEquals("recent", TodayRows.route(item(TimelineKind.SLEEP, AppPage.SLEEP), modules))
        assertEquals("appointments/edit?id=ap1", TodayRows.route(item(TimelineKind.APPOINTMENT, AppPage.APPOINTMENTS, "ap1"), modules))
        assertEquals("cycle?log=2026-11-17", TodayRows.route(item(TimelineKind.CYCLE_DAY, AppPage.CYCLE, "2026-11-17"), modules))
        assertEquals("cycle", TodayRows.route(item(TimelineKind.CYCLE_DAY, AppPage.CYCLE, "abc"), modules))
        assertEquals("food", TodayRows.route(item(TimelineKind.MEAL, AppPage.FOOD), modules))
        assertEquals("meds", TodayRows.route(item(TimelineKind.DOSE_TAKEN, AppPage.MEDS, "d1"), modules))
        assertEquals("alerts", TodayRows.route(item(TimelineKind.HEALTH_ALERT, AppPage.ALERTS, "a1"), modules))
        // A guided meditation done: the Meditation page.
        assertEquals("meditation", TodayRows.route(item(TimelineKind.CALM, AppPage.MEDITATION, "s1"), modules))
        assertEquals("measurements", TodayRows.route(item(TimelineKind.MEASUREMENT, AppPage.BODY), modules, measurements = true))
        assertEquals("trends", TodayRows.route(item(TimelineKind.WEIGHT, AppPage.BODY), modules))
        assertNull(TodayRows.route(item(TimelineKind.OTHER, null), modules))
    }

    @Test
    fun `the night carries its stages, and the now line says what's live`() {
        val stages = listOf(StageSpan(SleepStage.LIGHT, 100, 200), StageSpan(SleepStage.DEEP, 200, 300))
        val night = SleepSummary(100, 300, 180, 200, 90, 60, 30, 90, 20, true, stages = stages)
        val summary = DaySummary("2026-11-17", sleepSummary = night, steps = 6240, heartRate = listOf(Point(9_000_000, 72.0)))
        assertEquals(stages, TodayRows.stages(TimelineItem("s", TimelineKind.SLEEP, 100, end = 300, title = "Sleep"), summary))
        assertTrue(TodayRows.stages(TimelineItem("n", TimelineKind.NAP, 100, end = 300, title = "Nap"), summary).isEmpty())
        assertTrue(TodayRows.stages(TimelineItem("o", TimelineKind.SLEEP, 1_000, end = 2_000, title = "Sleep"), summary).isEmpty())
        assertEquals("Heart rate 72 · 6,240 steps so far", TodayRows.nowLine(summary, 9_000_000 + 60_000))
        assertEquals("6,240 steps so far", TodayRows.nowLine(summary, 9_000_000 + 2 * 3_600_000))
        assertNull(TodayRows.nowLine(DaySummary("2026-11-17"), 0))
    }

    @Test
    fun `one gentle sentence under the scores, and For you is two insights at most, never an alert`() {
        val readinessInsight = Insight("good", "Ready for a challenge", "Your body has recovered well.", link = AppPage.TODAY)
        val alert = Insight("warn", "High heart rate", "It stayed up.", link = AppPage.ALERTS)
        val dose = Insight("info", "2 doses still to take", "", module = Module.MEDICATIONS, shownBy = TodaySection.MEDS)
        val a = Insight("info", "A", "")
        val b = Insight("info", "B", "")
        val summary = DaySummary("2026-11-17", readiness = Score(90, "Optimal", emptyList()), insights = listOf(alert, readinessInsight, dose, a, b))
        assertEquals("Your body has recovered well.", TodayRows.scoresLine(summary))
        assertEquals(listOf(a, b), TodayRows.forYou(summary, modules, setOf(TodaySection.MEDS), scores = true))
        assertEquals(listOf(readinessInsight, dose), TodayRows.forYou(summary, modules, emptySet(), scores = false))
        // Without the insight, a word from the score itself; without scores, nothing.
        assertEquals("Your body's asking for an easier day. Go gently.", TodayRows.scoresLine(DaySummary("d", readiness = Score(40, "Pay attention", emptyList()))))
        assertNull(TodayRows.scoresLine(DaySummary("d")))
        assertEquals("Afternoon snack", TodayRows.mealTitle(15))
    }
}
