package com.health.app.ui.meditation

import com.health.api.BreathPacing
import com.health.api.Insight
import com.health.api.Meditation
import com.health.api.MeditationKind
import com.health.api.MeditationSession
import com.health.api.MeditationStep
import com.health.api.MeditationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/** How a meditation plays, which voice reads it, the chime, the reminder and the page's words. */
class MeditationLogicTest {
    init {
        Locale.setDefault(Locale.US)
    }

    private val pace = BreathPacing(inSeconds = 4, holdSeconds = 0, outSeconds = 6, rounds = 3)
    private val steps = listOf(
        MeditationStep("Welcome. This is some quiet time for you.", pauseSeconds = 5),
        MeditationStep("Breathe in for four, and out for six.", pauseSeconds = 3, breath = pace),
        MeditationStep("", pauseSeconds = 10),
        MeditationStep("Rest here as long as you like.", pauseSeconds = 0),
    )

    @Test
    fun `each step plays its words, then its breaths, then its quiet`() {
        val t = MeditationPlan.timeline(steps)
        assertEquals(
            listOf(Segment.Speak::class, Segment.Pause::class, Segment.Speak::class, Segment.Breathe::class, Segment.Pause::class, Segment.Pause::class, Segment.Speak::class),
            t.map { it::class },
        )
        assertEquals(listOf(0, 0, 1, 1, 1, 2, 3), t.map { it.step })
        // 8 words at 2.5 a second; the pacer's three breaths of 10 seconds.
        assertEquals(3.2, t[0].seconds, 1e-9)
        assertEquals(30.0, t[3].seconds, 1e-9)
        val total = MeditationPlan.total(t)
        assertEquals(total, MeditationPlan.secondsLeft(t, 0, 0.0), 1e-9)
        assertEquals(total - 3.2 - 2.0, MeditationPlan.secondsLeft(t, 1, 2.0), 1e-9)
        // Words that ran long don't make time go backwards.
        assertEquals(total - 3.2, MeditationPlan.secondsLeft(t, 0, 10.0), 1e-9)
        assertEquals(0.0, MeditationPlan.secondsLeft(t, t.size, 0.0), 1e-9)
    }

    @Test
    fun `a step without words still shows the last words said`() {
        assertEquals("Breathe in for four, and out for six.", MeditationPlan.sayAt(steps, 2))
        assertEquals("Rest here as long as you like.", MeditationPlan.sayAt(steps, 3))
        assertEquals(1.0, MeditationPlan.speakSeconds("Rest."), 1e-9)
    }

    @Test
    fun `the time left is in whole minutes, rounded up`() {
        assertEquals("10 min left", MeditationPlan.leftText(600.0))
        assertEquals("10 min left", MeditationPlan.leftText(541.0))
        assertEquals("1 min left", MeditationPlan.leftText(60.0))
        assertEquals("Less than a minute left", MeditationPlan.leftText(59.0))
        assertEquals("Less than a minute left", MeditationPlan.leftText(0.0))
    }

    @Test
    fun `the pacer breathes in, rests when asked, and breathes out, round after round`() {
        assertEquals(30, Pacer.seconds(pace))
        assertEquals(PacerPoint(1, BreathPhase.IN, 0f, 4), Pacer.at(pace, 0.0))
        assertEquals(PacerPoint(1, BreathPhase.IN, 0.5f, 4), Pacer.at(pace, 2.0))
        assertEquals(PacerPoint(1, BreathPhase.OUT, 0f, 6), Pacer.at(pace, 4.0))
        assertEquals(PacerPoint(2, BreathPhase.IN, 0.25f, 4), Pacer.at(pace, 11.0))
        assertEquals(3, Pacer.at(pace, 29.9)?.round)
        assertNull(Pacer.at(pace, 30.0))
        assertNull(Pacer.at(pace, -1.0))

        val held = BreathPacing(4, 2, 6, 1)
        assertEquals(listOf(BreathPhase.IN, BreathPhase.HOLD, BreathPhase.OUT), Pacer.parts(held).map { it.first })
        assertEquals(BreathPhase.HOLD, Pacer.at(held, 5.0)?.phase)
        assertEquals("Breathe in", BreathPhase.IN.words)
        assertEquals("Breathe out", BreathPhase.OUT.words)
        assertEquals(0, Pacer.seconds(BreathPacing(0, 0, 0, 5)))
    }

    @Test
    fun `the circle grows breathing in and shrinks breathing out, smoothly`() {
        assertEquals(Pacer.SMALL, Pacer.scale(Pacer.at(pace, 0.0)), 1e-6f)
        assertEquals(1f, Pacer.scale(PacerPoint(1, BreathPhase.IN, 1f, 4)), 1e-6f)
        assertEquals(1f, Pacer.scale(Pacer.at(pace, 4.0)), 1e-6f)
        assertEquals(Pacer.SMALL, Pacer.scale(PacerPoint(1, BreathPhase.OUT, 1f, 6)), 1e-6f)
        assertEquals(1f, Pacer.scale(PacerPoint(1, BreathPhase.HOLD, 0.5f, 2)), 1e-6f)
        // Halfway in, halfway there; and it never jumps between two moments a tenth of a second apart.
        assertEquals((1 + Pacer.SMALL) / 2, Pacer.scale(PacerPoint(1, BreathPhase.IN, 0.5f, 4)), 1e-4f)
        var last = Pacer.scale(Pacer.at(pace, 0.0))
        var t = 0.1
        while (t < 30) {
            val now = Pacer.scale(Pacer.at(pace, t))
            assertTrue("at $t", abs(now - last) < 0.03f)
            last = now
            t += 0.1
        }
    }

    @Test
    fun `only an offline voice that's on the phone reads, never one that needs the network`() {
        val uk = Locale.UK
        val voices = listOf(
            VoiceOption("en-us-x-network", Locale.US, network = true, quality = 500),
            VoiceOption("en-gb-x-not-downloaded", uk, network = false, installed = false, quality = 500),
            VoiceOption("en-us-x-local", Locale.US, network = false, quality = 400),
            VoiceOption("en-gb-x-local", uk, network = false, quality = 300),
            VoiceOption("fr-fr-x-local", Locale.FRANCE, network = false, quality = 500),
        )
        // The phone's own country first.
        assertEquals("en-gb-x-local", VoicePick.choose(voices, uk)?.name)
        // Elsewhere, the best sounding English one.
        assertEquals("en-us-x-local", VoicePick.choose(voices, Locale.GERMANY)?.name)
        // The engine's default wins a tie of country.
        val two = listOf(VoiceOption("a", Locale.US, false, quality = 400), VoiceOption("b", Locale.US, false, quality = 300))
        assertEquals("b", VoicePick.choose(two, Locale.US, default = "b")?.name)
        // Nothing offline in English: none (text only).
        assertNull(VoicePick.choose(voices.filter { it.network || !it.installed || it.locale.language != "en" }, uk))
        assertNull(VoicePick.choose(emptyList(), uk))
    }

    @Test
    fun `the chime swells in, fades to silence, and is never loud`() {
        val s = Chime.samples()
        assertEquals((1.8 * Chime.RATE).toInt(), s.size)
        val peak = s.maxOf { abs(it.toInt()) }
        assertTrue(peak > 1000)
        assertTrue(peak <= (0.22 * Short.MAX_VALUE).toInt() + 1)
        assertTrue(abs(s.first().toInt()) < 50)
        assertTrue(s.takeLast(200).all { abs(it.toInt()) < 50 })
    }

    @Test
    fun `the reminder goes at its time in the person's zone, and keeps quiet after a meditation`() {
        val zone = ZoneId.of("America/New_York")
        val day = LocalDate.of(2026, 11, 25)
        fun at(d: LocalDate, h: Int, m: Int) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        assertEquals(at(day, 21, 30), MeditationReminderPlan.next(true, "21:30", at(day, 18, 0), zone))
        assertEquals(at(day.plusDays(1), 21, 30), MeditationReminderPlan.next(true, "21:30", at(day, 21, 30), zone))
        assertNull(MeditationReminderPlan.next(false, "21:30", at(day, 18, 0), zone))
        assertNull(MeditationReminderPlan.next(true, "half nine", at(day, 18, 0), zone))
        // The clocks going back: still 9:30 in the evening there.
        val autumn = LocalDate.of(2026, 11, 1)
        assertEquals(at(autumn, 21, 30), MeditationReminderPlan.next(true, "21:30", at(autumn, 1, 0), zone))

        val reminder = at(day, 21, 30)
        assertTrue(MeditationReminderPlan.shows(reminder, null))
        assertFalse(MeditationReminderPlan.shows(reminder, at(day, 21, 10)))
        assertTrue(MeditationReminderPlan.shows(reminder, at(day, 20, 30)))
        assertTrue(MeditationReminderPlan.shows(reminder, at(day.minusDays(1), 21, 20)))
    }

    private fun m(kind: MeditationKind, minutes: Int) = Meditation("${kind.name.lowercase().replace('_', '-')}-$minutes", kind.label, "About ${kind.label}", minutes, kind, emptyList())
    private val all = MeditationKind.entries.flatMap { k -> listOf(5, 10, 15).map { m(k, it) } }

    @Test
    fun `the one used last comes first, at its length`() {
        assertEquals(MeditationKind.entries, MeditationChoice.kinds(all, null))
        assertEquals(MeditationKind.BREATHING, MeditationChoice.kinds(all, "breathing-15").first())
        assertEquals(5, MeditationChoice.kinds(all, "breathing-15").size)
        assertEquals(15, MeditationChoice.minutes(all, MeditationKind.BREATHING, "breathing-15"))
        assertEquals(10, MeditationChoice.minutes(all, MeditationKind.WIND_DOWN, "breathing-15"))
        assertEquals(listOf(5, 10, 15), MeditationChoice.lengths(all, MeditationKind.GRATITUDE))
        assertEquals("body-scan-5", MeditationChoice.find(all, MeditationKind.BODY_SCAN, 5)?.id)
        assertEquals("wind-down-10", MeditationChoice.suggested(all, null))
        assertEquals("gratitude-5", MeditationChoice.suggested(all, "gratitude-5"))
        assertEquals("wind-down-10", MeditationChoice.suggested(all, "gone-7"))
        assertEquals(MeditationKind.entries, MeditationChoice.kinds(all, "gone-7"))
    }

    @Test
    fun `the page says this week plainly`() {
        val today = LocalDate.of(2026, 11, 25)
        val zone = ZoneId.of("America/New_York")
        val sessions = listOf(
            session("2026-11-25", 10.0, true),
            session("2026-11-24", 9.6, true),
            session("2026-11-24", 3.2, false),
            session("2026-11-10", 15.0, true),
        )
        val summary = MeditationSummary(days = 30, sessions = 4, finished = 3, minutes = 37.8, weekMinutes = 22.8, daysMeditated = 3, insights = listOf(Insight("info", "Sleep after meditating", "…")))
        assertEquals(22.8 to 2, MeditationWords.week(summary, sessions, today))
        assertEquals(22.8 to null, MeditationWords.week(summary, null, today))
        assertEquals(0.0 to null, MeditationWords.week(null, null, today))
        assertEquals("On 2 days", MeditationWords.days(2))
        assertEquals("On 1 day", MeditationWords.days(1))
        assertEquals("10 min", MeditationWords.minutes(9.6))
        assertEquals("Under a minute", MeditationWords.minutes(0.4))
        assertEquals("Settled", MeditationWords.felt(4))
        assertNull(MeditationWords.felt(null))
        assertEquals(5, MeditationWords.FELT.size)

        val stopped = sessions[2].copy(startedAt = today.minusDays(1).atTime(21, 42).atZone(zone).toInstant().toEpochMilli(), feltAfter = 3)
        assertEquals("3 min · Yesterday, 9:42 PM · Stopped partway · In between".replace(" ", " "), MeditationWords.detail(stopped, zone, today).replace(" ", " "))
        assertEquals("Sitting quietly", MeditationWords.title(stopped.copy(title = null)))
    }

    private fun session(date: String, minutes: Double, finished: Boolean) =
        MeditationSession(startedAt = 0, minutes = minutes, finished = finished, meditationId = "wind-down-10", id = "s-$date-$minutes", date = date, title = "Wind down", kind = MeditationKind.WIND_DOWN)
}
