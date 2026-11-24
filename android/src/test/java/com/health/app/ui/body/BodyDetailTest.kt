package com.health.app.ui.body

import com.health.api.Flow
import com.health.api.Goals
import com.health.api.Metric
import com.health.api.RecentPoint
import com.health.api.RecentReadings
import com.health.api.Sample
import com.health.api.SleepStage
import com.health.api.SleepSummary
import com.health.api.StageSpan
import com.health.api.TrendDay
import com.health.api.Trends
import com.health.api.Units
import com.health.api.Workout
import com.health.api.WorkoutType
import com.health.app.ui.recent.Recent
import com.health.app.ui.recent.RecentFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** A card on Body, opened: its spans, title, the latest, the stats with when, the list newest first, and nothing in the span. */
class BodyDetailTest {
    init {
        Locale.setDefault(Locale.US)
    }

    private val f = RecentFixtures
    private val ctx = BodyDetail.Ctx(Units.METRIC, f.zone, f.today, Goals(steps = 8000, sleepMinutes = 450, targetWeightKg = 65.0))

    /** A week of days ending today, each as [edit] makes it (given its index, oldest first). */
    private fun week(edit: (Int, TrendDay) -> TrendDay = { _, d -> d }): Trends {
        val days = (0 until 7).map { i -> edit(i, TrendDay(f.today.minusDays(6L - i).toString())) }
        return Trends(days.first().date, days.last().date, days)
    }

    private fun t(at: Long) = com.health.app.ui.Format.timeOfDay(at, f.zone)

    private fun day(date: LocalDate, block: TrendDay.() -> TrendDay = { this }) = TrendDay(date.toString()).block()

    @Test
    fun `routes carry the card, Body's span and the device`() {
        assertEquals("body/detail/heart?span=DAY", BodyDetail.route("heart", BodySpan.DAY))
        assertEquals("body/detail/heart?span=DAY&device=dev%20ring", BodyDetail.route("heart", BodySpan.DAY, "dev ring"))
        assertEquals("body/detail/nutrient.FIBER?span=MONTH", BodyDetail.route(BodyDetail.nutrient("FIBER"), BodySpan.MONTH))
        assertEquals(BodySpan.YEAR, BodyDetail.spanNamed("YEAR"))
        assertNull(BodyDetail.spanNamed("fortnight"))
    }

    @Test
    fun `24 hours only for what the devices recorded, and never while viewing a share`() {
        assertEquals(BodySpan.entries, BodyDetail.spans("heart", readOnly = false))
        assertEquals(listOf(BodySpan.WEEK, BodySpan.MONTH, BodySpan.YEAR), BodyDetail.spans("heart", readOnly = true))
        assertEquals(listOf(BodySpan.WEEK, BodySpan.MONTH, BodySpan.YEAR), BodyDetail.spans("weight", readOnly = false))
        // It starts where Body was, where it can.
        assertEquals(BodySpan.DAY, BodyDetail.startSpan("heart", BodySpan.DAY, false))
        assertEquals(BodySpan.MONTH, BodyDetail.startSpan("heart", BodySpan.MONTH, false))
        assertEquals(BodySpan.WEEK, BodyDetail.startSpan("heart", BodySpan.DAY, true))
        assertEquals(BodySpan.WEEK, BodyDetail.startSpan("weight", BodySpan.DAY, false))
        assertEquals(BodySpan.YEAR, BodyDetail.startSpan("weight", BodySpan.YEAR, false))
    }

    @Test
    fun `the title is the card's name at that span`() {
        assertEquals("Heart rate", BodyDetail.title("heart", BodySpan.DAY))
        assertEquals("Resting heart rate", BodyDetail.title("heart", BodySpan.WEEK))
        assertEquals("Stress", BodyDetail.title("stress", BodySpan.DAY))
        assertEquals("Stress and recovery", BodyDetail.title("stress", BodySpan.MONTH))
        assertEquals("Blood pressure", BodyDetail.title("bp", BodySpan.WEEK))
        assertEquals("Fiber", BodyDetail.title(BodyDetail.nutrient("FIBER"), BodySpan.WEEK).replace("Fibre", "Fiber"))
    }

    @Test
    fun `the arrangement's ids stay as they were`() {
        assertEquals(listOf("headline", "chart", "stats", "list"), BodyDetail.SECTIONS.map { it.id })
        assertEquals("android.bodyDetail", com.health.app.ui.modules.FeatureScreens.BODY_DETAIL)
    }

    @Test
    fun `a week of resting heart rate, the latest, average, lowest and highest with when, against the usual, newest first`() {
        val values = listOf(55.0, 53.0, null, 51.0, 54.0, 52.0, 50.0)
        val trends = week { i, d -> d.copy(restingHeartRate = values[i]) }
        val usual = Trends("", "", (1..10).map { i -> day(f.today.minusDays(i.toLong())) { copy(restingHeartRate = 52.0) } })
        val page = BodyDetail.page("heart", BodyDetailState(BodySpan.WEEK, trends = trends, usual = usual), ctx)
        assertEquals("Resting heart rate", page.title)
        assertEquals("50 bpm", page.headline)
        assertEquals("Today", page.headlineWhen)
        assertEquals(listOf("Average", "Lowest", "Highest"), page.stats.map { it.label })
        assertEquals("53 bpm", page.stats[0].value)
        assertEquals("50 bpm", page.stats[1].value)
        assertEquals("12 Nov", page.stats[1].note)
        assertEquals("55 bpm", page.stats[2].value)
        assertEquals("6 Nov", page.stats[2].note)
        assertEquals("About your usual 52 bpm", page.against)
        // Each day with a value, newest first; the day without isn't a row.
        assertEquals(6, page.rows.size)
        assertEquals(listOf("Today", "Yesterday"), page.rows.take(2).map { it.label })
        assertEquals("50 bpm", page.rows.first().value)
        assertTrue(page.rows.zipWithNext().all { (a, b) -> a.at > b.at })
        val chart = page.chart!!
        assertEquals(52.0, chart.reference!!, 0.01)
        assertEquals("Dashed line: your usual, 52 bpm", chart.referenceText)
        assertFalse(chart.bars)
        assertEquals(6, chart.lines.single().size)
    }

    @Test
    fun `steps a day as bars, with the goal where there's no usual yet`() {
        val trends = week { i, d -> d.copy(steps = 6000 + i * 500) }
        val page = BodyDetail.page("steps", BodyDetailState(BodySpan.WEEK, trends = trends), ctx)
        val chart = page.chart!!
        assertTrue(chart.bars)
        assertEquals(8000.0, chart.reference!!, 0.01)
        assertEquals("Dashed line: your goal, 8,000 steps", chart.referenceText)
        assertEquals(0.0, chart.scale.low, 0.0)
        assertEquals("9,000 steps", page.headline)
        assertEquals("7,500 steps", page.stats.first().value)
        assertNull(page.against)
    }

    @Test
    fun `a year of bars is drawn a week to a bar`() {
        val days = (0 until 365).map { i -> day(f.today.minusDays(364L - i)) { copy(steps = 7000) } }
        val page = BodyDetail.page("steps", BodyDetailState(BodySpan.YEAR, trends = Trends(days.first().date, days.last().date, days)), ctx)
        assertEquals(53, page.chart!!.lines.single().size)
        // Lines too, so a year reads as a trend.
        val heart = BodyDetail.page("heart", BodyDetailState(BodySpan.YEAR, trends = Trends(days.first().date, days.last().date, days.map { it.copy(restingHeartRate = 52.0) })), ctx)
        assertEquals(53, heart.chart!!.lines.single().size)
        // A tighter scale than the 24 hours' for a day's resting heart rate.
        assertTrue(heart.chart!!.scale.high - heart.chart!!.scale.low <= 12.0)
        assertEquals(7 * 86_400_000L, page.chart!!.bucket)
        // Each month marked once on its axis.
        assertTrue(page.chart!!.axis.ticks.size in 12..13)
    }

    @Test
    fun `scores are three lines, each with its average`() {
        val trends = week { i, d -> d.copy(readiness = 70 + i, sleepScore = 80, activityScore = 90 - i) }
        val page = BodyDetail.page("scores", BodyDetailState(BodySpan.WEEK, trends = trends), ctx)
        assertEquals(3, page.chart!!.lines.size)
        assertEquals(listOf("Readiness", "Sleep", "Activity"), page.chart!!.names)
        assertEquals(listOf("Readiness" to "73", "Sleep" to "80", "Activity" to "87"), page.stats.map { it.label to it.value })
        assertEquals("Readiness 76", page.rows.first().value)
        assertEquals("Sleep 80 · Activity 84", page.rows.first().note)
    }

    @Test
    fun `doses as a percentage over the span`() {
        val trends = week { i, d -> when (i) { 0 -> d.copy(dosesDue = 2, dosesTaken = 2); 1 -> d.copy(dosesDue = 2, dosesTaken = 1); else -> d } }
        val page = BodyDetail.page("doses", BodyDetailState(BodySpan.WEEK, trends = trends), ctx)
        assertEquals("In all", page.stats.first().label)
        assertEquals("75%", page.stats.first().value)
        assertEquals("3 of 4", page.stats.first().note)
        assertEquals("1 of 2", page.rows.first().value)
    }

    @Test
    fun `period days are counted, each named by its flow`() {
        val trends = week { i, d -> when (i) { 0 -> d.copy(flow = Flow.LIGHT); 1 -> d.copy(flow = Flow.HEAVY); else -> d } }
        val page = BodyDetail.page("period", BodyDetailState(BodySpan.WEEK, trends = trends), ctx)
        assertEquals(BodyDetail.Stat("Days", "2", "of 7"), page.stats.single())
        assertEquals(listOf("Heavy", "Light"), page.rows.map { it.value })
    }

    @Test
    fun `nothing in the span is an empty page`() {
        val page = BodyDetail.page("hrv", BodyDetailState(BodySpan.WEEK, trends = week()), ctx)
        assertTrue(page.empty)
        assertTrue(BodyDetail.page("weight", BodyDetailState(BodySpan.MONTH), ctx).empty)
        // Not loaded yet is empty too (the page shows it loading).
        assertTrue(BodyDetail.page("heart", BodyDetailState(BodySpan.DAY), ctx).empty)
        assertTrue(BodyDetail.page("heart", BodyDetailState(BodySpan.DAY, readings = f.empty), ctx).empty)
    }

    @Test
    fun `24 hours of heart rate, the latest, lowest and highest at their times, each hour newest first`() {
        val page = BodyDetail.page("heart", BodyDetailState(BodySpan.DAY, readings = f.all), ctx)
        assertEquals("Heart rate", page.title)
        assertNotNull(page.headline)
        assertTrue(page.headlineWhen!!.startsWith("latest at"))
        assertEquals(listOf("Average", "Lowest", "Highest"), page.stats.map { it.label })
        val low = f.heart.minByOrNull { it.value }!!
        assertEquals(com.health.app.ui.Format.timeOfDay(low.at, f.zone), page.stats[1].note)
        assertEquals("Each hour", page.listTitle)
        assertTrue(page.rows.size in 20..25)
        assertTrue(page.rows.zipWithNext().all { (a, b) -> a.at > b.at })
        // The hours before midnight say so.
        assertTrue(page.rows.last().label.endsWith("yesterday"))
        assertTrue(page.rows.first().note!!.startsWith("from "))
    }

    @Test
    fun `24 hours of steps, the total, the busiest hour, and only the hours with steps`() {
        val page = BodyDetail.page("steps", BodyDetailState(BodySpan.DAY, readings = f.all), ctx)
        val total = f.steps.sumOf { it.value }
        assertEquals("${com.health.app.ui.Format.number(total)} steps", page.headline)
        assertEquals("in the past 24 hours", page.headlineWhen)
        assertEquals(listOf("In all", "Busiest hour"), page.stats.map { it.label })
        assertTrue(page.chart!!.bars)
        assertEquals(f.steps.count { it.value > 0 }, page.rows.size)
    }

    @Test
    fun `hours are labelled by the clock, yesterday's saying so`() {
        val points = listOf(RecentPoint(f.at(f.today, 9, 10), 60.0), RecentPoint(f.at(f.today, 9, 40), 70.0), RecentPoint(f.at(f.today.minusDays(1), 21, 5), 55.0))
        val rows = BodyDetail.hours(points, Metric.HEART_RATE, false, ctx)
        assertEquals(listOf("9 AM", "9 PM yesterday"), rows.map { it.label })
        assertEquals("65 bpm", rows[0].value)
        assertEquals("from 60 to 70 bpm", rows[0].note)
        assertNull(rows[1].note)
    }

    @Test
    fun `the 24 hours' night, how long, its stages, against the usual, each sleep`() {
        val usual = Trends("", "", (1..5).map { i -> day(f.today.minusDays(i.toLong())) { copy(sleepMinutes = 420) } })
        val page = BodyDetail.page("sleep", BodyDetailState(BodySpan.DAY, readings = f.all, usual = usual), ctx)
        assertEquals("7h 7m", page.headline)
        assertNotNull(page.night)
        assertEquals(listOf("Deep", "REM", "Light", "Awake"), page.stats.map { it.label })
        assertEquals("About your usual 7h", page.against)
        assertEquals("Each sleep", page.listTitle)
        assertNotNull(page.rows.first().night)
        assertTrue(page.rows.first().note!!.startsWith("deep "))
    }

    @Test
    fun `the week's nights each with their times and stages`() {
        val trends = week { i, d -> d.copy(sleepMinutes = 400 + i * 5) }
        val end = f.at(f.today, 6, 30)
        val start = f.at(f.today.minusDays(1), 23, 0)
        val night = SleepSummary(
            start, end, asleepMinutes = 430, inBedMinutes = 450, efficiency = 95, deepMinutes = 80, remMinutes = 100, lightMinutes = 250, awakeMinutes = 20, staged = true,
            stages = listOf(StageSpan(SleepStage.LIGHT, start, end)),
        )
        val page = BodyDetail.page("sleep", BodyDetailState(BodySpan.WEEK, trends = trends, nights = listOf(night)), ctx)
        assertEquals("Each night", page.listTitle)
        assertEquals("7h 10m", page.headline)
        assertEquals("asleep, Today", page.headlineWhen)
        val row = page.rows.single()
        assertEquals("Today", row.label)
        assertEquals("${t(start)} – ${t(end)} · deep 1h 20m · REM 1h 40m · light 4h 10m · awake 20m", row.note)
        assertEquals(start, row.night!!.start)
        assertTrue(page.chart!!.bars)
        assertEquals(7.5, page.chart!!.reference!!, 0.01)
    }

    @Test
    fun `weight, each weigh-in newest first, the change since the first, and only the hand's to delete`() {
        val weights = listOf(
            Sample(Metric.WEIGHT, f.at(f.today.minusDays(20), 7), value = 68.0, id = 1, source = "Entered by hand"),
            Sample(Metric.WEIGHT, f.at(f.today.minusDays(10), 7), value = 67.0, id = 2, source = "Withings"),
            Sample(Metric.WEIGHT, f.at(f.today, 7), value = 67.4, id = 3),
            // Before the span: left out.
            Sample(Metric.WEIGHT, f.at(f.today.minusDays(40), 7), value = 70.0, id = 4),
        )
        val page = BodyDetail.page("weight", BodyDetailState(BodySpan.MONTH, weights = weights), ctx)
        assertEquals("67.4 kg", page.headline)
        assertEquals("Today", page.headlineWhen)
        assertEquals(BodyDetail.Stat("Change", "−0.6 kg", "since 23 Oct"), page.stats[0])
        assertEquals(BodyDetail.Stat("Lowest", "67.0 kg", "2 Nov"), page.stats[1])
        assertEquals(listOf(3L, null, 1L), page.rows.map { it.id })
        assertEquals("Each weigh-in", page.listTitle)
        assertEquals("Dashed line: your goal, 65.0 kg", page.chart!!.referenceText)
        // In pounds with imperial units.
        val imperial = BodyDetail.page("weight", BodyDetailState(BodySpan.MONTH, weights = weights), ctx.copy(units = Units.IMPERIAL))
        assertEquals("148.6 lb", imperial.headline)
    }

    @Test
    fun `workouts, each one with its details, the count, time and longest`() {
        val trends = week { i, d -> when (i) { 0 -> d.copy(workouts = 1, workoutMinutes = 40); 2 -> d.copy(workouts = 1, workoutMinutes = 65); else -> d } }
        val start = f.at(f.today.minusDays(4), 7, 5)
        val run = Workout(WorkoutType.RUN, start, start + 65 * 60_000L, distanceM = 10_200.0, activeCalories = 640.0, avgHeartRate = 148.0, effort = 7)
        val walk = Workout(WorkoutType.WALK, f.at(f.today.minusDays(6), 18, 0), f.at(f.today.minusDays(6), 18, 40), name = "Evening walk")
        val page = BodyDetail.page("workouts", BodyDetailState(BodySpan.WEEK, trends = trends, workouts = listOf(walk, run)), ctx)
        assertEquals("Run", page.headline)
        assertEquals("1h 5m · Sun, 8 Nov", page.headlineWhen)
        assertEquals(listOf("Workouts", "Time", "Longest"), page.stats.map { it.label })
        assertEquals("1h 45m", page.stats[1].value)
        assertEquals(listOf("Run", "Evening walk"), page.rows.map { it.label })
        assertEquals("Sun, 8 Nov, ${t(start)} · 10.2 km · 640 kcal · average 148 bpm · effort 7 of 10", page.rows.first().note)
        assertEquals("Each workout", page.listTitle)
    }

    @Test
    fun `the usual is the 30 days before the span, and none for one device alone`() {
        assertEquals(LocalDate.of(2026, 10, 7) to LocalDate.of(2026, 11, 5), BodyDetail.usualWindow(BodySpan.WEEK, f.today))
        assertEquals(LocalDate.of(2026, 10, 13) to LocalDate.of(2026, 11, 11), BodyDetail.usualWindow(BodySpan.DAY, f.today))
        // Days in the span don't count towards it.
        val trends = week { _, d -> d.copy(hrv = 30.0) }
        val usual = Trends("", "", (1..10).map { i -> day(f.today.minusDays(i.toLong())) { copy(hrv = if (i < 7) 30.0 else 50.0) } })
        assertEquals("Dashed line: your usual, 50 ms", BodyDetail.page("hrv", BodyDetailState(BodySpan.WEEK, trends = trends, usual = usual), ctx).chart!!.referenceText)
        val days = Trends("", "", (1..5).map { i -> day(f.today.minusDays(i.toLong())) { copy(hrv = 50.0) } })
        assertNotNull(BodyDetail.page("hrv", BodyDetailState(BodySpan.DAY, readings = f.all, usual = days), ctx).against)
        assertNull(BodyDetail.page("hrv", BodyDetailState(BodySpan.DAY, readings = f.ringAlone, usual = days), ctx).against)
        assertFalse(BodyDetail.usesUsual("temperature"))
    }

    @Test
    fun `against the usual, in words`() {
        val one = { v: Double -> "${v.toInt()} ms" }
        assertEquals("About your usual 50 ms", BodyDetail.against(51.0, 50.0, one))
        assertEquals("20% below your usual 50 ms", BodyDetail.against(40.0, 50.0, one))
        assertEquals("10% above your usual 50 ms", BodyDetail.against(55.0, 50.0, one))
    }

    @Test
    fun `a nutrient against its target, and an unknown card shows nothing`() {
        val trends = week { i, d -> when (i) { 0 -> d.copy(fiber = 20.0); 1 -> d.copy(fiber = 30.0); else -> d } }
        val page = BodyDetail.page(BodyDetail.nutrient("FIBER"), BodyDetailState(BodySpan.WEEK, trends = trends), ctx)
        assertEquals(2, page.rows.size)
        assertTrue(BodyDetail.page("nonsense", BodyDetailState(BodySpan.WEEK, trends = trends), ctx).empty)
    }

    @Test
    fun `breathing over the week from its series, a day at a time`() {
        val points = listOf(
            com.health.api.Point(f.at(f.today, 2), 14.0), com.health.api.Point(f.at(f.today, 4), 15.0),
            com.health.api.Point(f.at(f.today.minusDays(1), 3), 13.0),
        )
        val page = BodyDetail.page("breathing", BodyDetailState(BodySpan.WEEK, trends = week(), breathing = points), ctx)
        assertEquals(listOf("14.5 per min", "13 per min"), page.rows.map { it.value })
    }

    @Test
    fun `24 hours of one device's own`() {
        val ring: RecentReadings = f.ringAlone
        assertTrue(BodyDetail.page("steps", BodyDetailState(BodySpan.DAY, readings = ring), ctx).empty)
        assertEquals(Metric.HEART_RATE, BodyDetail.dayMetric(Recent.HEART, ring))
    }
}
