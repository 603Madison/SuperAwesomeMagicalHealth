package com.health.app.ui.modules.transition.voice

import com.health.api.ExerciseKind
import com.health.api.ExerciseResult
import com.health.api.VoiceGoal
import com.health.api.VoicePlan
import com.health.api.VoiceProgress
import com.health.app.ui.modules.transition.PitchDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.pow

/** Results, sessions, matching, plans, the programme's order, the reminder's timing and the words. */
class VoiceLogicTest {
    private val plan = VoiceFixtures.plan
    private val program = VoiceFixtures.program

    /** Voiced frames every 20 ms at these pitches, with a resonance and weight. */
    private fun frames(pitches: List<Double>, resonance: Double? = 60.0, weight: Double? = 40.0, db: Double = -36.0) =
        pitches.mapIndexed { i, hz -> VoiceFrame((i + 2) * 20L, db, hz, resonance, weight) }

    private fun silence(n: Int) = List(n) { VoiceFrame(it * 20L, -80.0) }

    // ---- Results ----

    @Test
    fun `an exercise's result has the average, the spread of pitch and the time in range`() {
        // 60 frames at 180 Hz (in range), 40 at 150 Hz (below it).
        val heard = frames(List(60) { 180.0 } + List(40) { 150.0 }) + silence(30)
        val result = VoiceResults.result(VoiceFixtures.exercise("hum"), 45, heard, plan, feeling = 4)
        assertEquals("hum", result.exerciseId)
        assertEquals(45, result.seconds)
        assertEquals(168.0, result.pitchAvgHz!!, 0.5)
        assertEquals(60, result.inRange)
        assertEquals(150.0, result.pitchMinHz!!, 0.1)
        assertEquals(180.0, result.pitchMaxHz!!, 0.1)
        assertEquals(60.0, result.resonance!!, 0.0)
        assertEquals(40.0, result.weight!!, 0.0)
        assertEquals(4, result.feeling)
        // A hum isn't speech: no intonation.
        assertNull(result.intonation)
    }

    @Test
    fun `glides report the range covered, not an average or time in range`() {
        val glide = frames((0 until 100).map { 150.0 * 2.0.pow(it / 100.0) })
        val result = VoiceResults.result(VoiceFixtures.exercise("sirens"), 60, glide, plan)
        assertNull(result.pitchAvgHz)
        assertNull(result.inRange)
        assertTrue(result.pitchMinHz!! < 160 && result.pitchMaxHz!! > 270)
    }

    @Test
    fun `speech reports intonation, and an odd octave slip is steadied away`() {
        val speech = frames((0 until 120).map { if (it == 50) 400.0 else 180.0 * 2.0.pow(((it % 20) - 10) / 24.0) })
        val result = VoiceResults.result(VoiceFixtures.exercise("phrases"), 120, speech, plan)
        assertNotNull(result.intonation)
        assertTrue(result.intonation!! in 1.0..4.0)
        assertTrue("The slip didn't count", result.pitchMaxHz!! < 260)
    }

    @Test
    fun `guided exercises and too little voice measure nothing, and say so kindly`() {
        val guided = VoiceResults.result(VoiceFixtures.exercise("breath"), 60, frames(List(200) { 180.0 }), plan, feeling = 5)
        assertNull(guided.pitchAvgHz)
        assertNull(guided.resonance)
        assertEquals(5, guided.feeling)
        val quiet = VoiceResults.result(VoiceFixtures.exercise("hum"), 30, frames(List(10) { 180.0 }) + silence(200), plan)
        assertNull(quiet.pitchAvgHz)
        assertEquals(30, quiet.seconds)
        val words = VoiceWords.summary(VoiceFixtures.exercise("hum"), quiet, plan)
        assertTrue(words.first(), words.first().startsWith("Nicely done"))
    }

    @Test
    fun `resonance and weight need enough readings`() {
        val heard = frames(List(100) { 180.0 }, resonance = null, weight = null) + frames(List(5) { 180.0 })
        val result = VoiceResults.result(VoiceFixtures.exercise("bright-vowels"), 60, heard, plan)
        assertNull(result.resonance)
        assertNull(result.weight)
        assertNotNull(result.pitchAvgHz)
    }

    @Test
    fun `without a target range there's no time in range`() {
        val result = VoiceResults.result(VoiceFixtures.exercise("hum"), 30, frames(List(100) { 180.0 }), VoicePlan())
        assertNull(result.inRange)
        assertEquals(180.0, result.pitchAvgHz!!, 0.01)
    }

    // ---- Sessions ----

    @Test
    fun `a session adds up its results, weighting by time`() {
        val results = listOf(
            ExerciseResult("hum", 60, pitchAvgHz = 170.0, pitchMinHz = 160.0, pitchMaxHz = 185.0, inRange = 70, resonance = 50.0, weight = 40.0, feeling = 4),
            ExerciseResult("bright-vowels", 120, pitchAvgHz = 185.0, pitchMinHz = 168.0, pitchMaxHz = 205.0, inRange = 90, resonance = 62.0, weight = 30.0, feeling = 5),
            ExerciseResult("breath", 45, feeling = 3),
        )
        val session = VoiceResults.session(1_000, "u2-resonance", listOf("Gentle hum", "Bright vowels", "Easy breathing", "Gentle hum"), results, strain = false, note = "  ")
        assertEquals(1_000, session.at)
        assertEquals("u2-resonance", session.lessonId)
        assertEquals(4, session.minutes) // 225 s
        assertEquals(listOf("Gentle hum", "Bright vowels", "Easy breathing"), session.exercises)
        assertEquals(180.0, session.pitchAvgHz!!, 0.01) // (170·60 + 185·120) / 180
        assertEquals(160.0, session.pitchMinHz!!, 0.0)
        assertEquals(205.0, session.pitchMaxHz!!, 0.0)
        assertEquals(58.0, session.resonance!!, 0.01)
        assertEquals(33.3, session.weight!!, 0.05)
        assertEquals(4, session.feeling)
        assertEquals(results, session.results)
        assertFalse(session.strain)
        assertNull(session.note)
    }

    @Test
    fun `a short session is still a minute, and strain is kept`() {
        val session = VoiceResults.session(5, null, listOf("Gentle hum"), listOf(ExerciseResult("hum", 20)), strain = true)
        assertEquals(1, session.minutes)
        assertTrue(session.strain)
        assertNull(session.lessonId)
        assertNull(session.pitchAvgHz)
        assertNull(session.feeling)
    }

    // ---- Matching ----

    @Test
    fun `notes to match are semitones around the middle of the range`() {
        val targets = MatchRound.targets(listOf(0, 12, -12), plan)
        val middle = VoiceScale.middle(165.0, 220.0)
        assertEquals(middle, targets[0], 1e-9)
        assertEquals(middle * 2, targets[1], 1e-9)
        assertEquals(middle / 2, targets[2], 1e-9)
        // Without a range: the baseline, else the goal's usual middle.
        assertEquals(130.0, MatchRound.targets(listOf(0), VoicePlan(baselinePitchHz = 130.0)).single(), 1e-9)
        assertEquals(120.0, MatchRound.targets(listOf(0), VoicePlan(goal = VoiceGoal.DEEPER)).single(), 1e-9)
    }

    @Test
    fun `a note is matched after half a second within a semitone, in all`() {
        val round = MatchRound(listOf(200.0, 250.0))
        // Off by two semitones: doesn't count.
        repeat(40) { assertFalse(round.hear(VoiceScale.shift(200.0, 2.0))) }
        assertEquals(0f, round.progress)
        // Within a semitone, with a gap in the middle: 24 frames isn't enough, the 25th is.
        repeat(12) { assertFalse(round.hear(VoiceScale.shift(200.0, 0.8))) }
        repeat(5) { round.hear(null) }
        repeat(12) { assertFalse(round.hear(195.0)) }
        assertTrue(round.hear(201.0))
        assertTrue(round.matched(0))
        // Once matched, it stays matched and doesn't fire again.
        assertFalse(round.hear(200.0))
        round.next()
        assertEquals(250.0, round.target!!, 0.0)
        repeat(30) { round.hear(180.0) }
        round.next()
        assertTrue(round.done)
        assertEquals(1, round.count)
        assertFalse(round.matched(1))
        assertNull(round.target)
    }

    // ---- Plans ----

    @Test
    fun `suggestions are gentle starting points for each goal`() {
        val lighter = VoicePlans.suggest(VoiceGoal.LIGHTER, null, null)
        assertEquals(165.0, lighter.lowHz, 0.0)
        assertEquals(220.0, lighter.highHz, 0.0)
        assertEquals(60.0, lighter.resonance, 0.0)
        val deeper = VoicePlans.suggest(VoiceGoal.DEEPER, null, null)
        assertEquals(100.0, deeper.lowHz, 0.0)
        assertEquals(140.0, deeper.highHz, 0.0)
        assertEquals(40.0, deeper.resonance, 0.0)
        // Exploring centres on the baseline, either side.
        val exploring = VoicePlans.suggest(VoiceGoal.EXPLORING, 150.0, 47.0)
        assertTrue(exploring.lowHz < 150 && exploring.highHz > 150)
        assertEquals(0.0, exploring.lowHz % 5, 0.0)
        assertEquals(47.0, exploring.resonance, 0.0)
        // A voice already in the usual range starts from where it is.
        val already = VoicePlans.suggest(VoiceGoal.LIGHTER, 200.0, 70.0)
        assertTrue(already.lowHz < 200 && already.highHz > 200)
        assertEquals(80.0, already.resonance, 0.0)
        VoiceGoal.entries.forEach { goal ->
            val s = VoicePlans.suggest(goal, 140.0, 50.0)
            assertTrue("$goal: range in order", s.lowHz < s.highHz)
            assertTrue("$goal: resonance on the scale", s.resonance in 0.0..100.0)
            assertTrue("$goal: explained", s.why.isNotBlank())
        }
    }

    @Test
    fun `a plan is set up once it has a range, and reads plainly`() {
        assertFalse(VoicePlans.isSetUp(null))
        assertFalse(VoicePlans.isSetUp(VoicePlan(baselinePitchHz = 140.0)))
        assertTrue(VoicePlans.isSetUp(plan))
        assertEquals("165–220 Hz (E3 to A3)", VoicePlans.range(plan))
        assertNull(VoicePlans.range(VoicePlan()))
        assertTrue(VoicePlans.restDay(plan, LocalDate.of(2026, 11, 15))) // a Sunday
        assertFalse(VoicePlans.restDay(plan, LocalDate.of(2026, 11, 13)))
    }

    @Test
    fun `the baseline is the median pitch, its usual range, resonance and loudness`() {
        val heard = frames((0 until 200).map { 120.0 + it * 0.2 }, resonance = 41.0, db = -38.0) + silence(50)
        val baseline = Baseline.of(heard)!!
        assertEquals(140.0, baseline.pitchHz, 0.5)
        assertEquals(124.0, baseline.lowHz, 0.6)
        assertEquals(156.0, baseline.highHz, 0.6)
        assertEquals(41.0, baseline.resonance!!, 0.0)
        assertEquals(-38.0, baseline.speakingDb, 0.0)
        assertNull(Baseline.of(frames(List(50) { 140.0 }) + silence(500)))
    }

    // ---- The programme ----

    private val lighter = VoiceGoal.LIGHTER
    private val deeper = VoiceGoal.DEEPER

    @Test
    fun `only the lessons that suit the goal show, under their units' names`() {
        assertEquals(listOf("u1-care", "u1-pitch", "u2-resonance", "u2-speech", "u3-talk"), VoiceCourse.lessonsFor(program, lighter).map { it.id })
        assertTrue("u2-fuller" in VoiceCourse.lessonsFor(program, deeper).map { it.id })
        assertFalse("u2-fuller" in VoiceCourse.lessonsFor(program, VoiceGoal.EXPLORING).map { it.id })
        assertEquals(listOf(1, 2, 3), VoiceCourse.units(program, lighter).map { it.first })
        assertEquals("Unit 2 · Resonance", VoiceCourse.unitTitle(program, 2))
        assertEquals("Brighter and darker, and taking it into words.", VoiceCourse.unitAbout(program, 2))
        assertEquals("Unit 9", VoiceCourse.unitTitle(program, 9))
        assertNull(VoiceCourse.unitAbout(program.copy(units = emptyList()), 2))
    }

    @Test
    fun `units open one after another, once each of the goal's lessons before has been done`() {
        assertEquals(setOf(1), VoiceCourse.openUnits(program, lighter, emptySet()))
        assertEquals(setOf(1), VoiceCourse.openUnits(program, lighter, setOf("u1-care")))
        assertEquals(setOf(1, 2), VoiceCourse.openUnits(program, lighter, setOf("u1-care", "u1-pitch")))
        val unitTwo = setOf("u1-care", "u1-pitch", "u2-resonance", "u2-speech")
        assertEquals(setOf(1, 2, 3), VoiceCourse.openUnits(program, lighter, unitTwo))
        // A deeper voice's unit 2 has one more lesson.
        assertEquals(setOf(1, 2), VoiceCourse.openUnits(program, deeper, unitTwo))
        assertEquals(setOf(1, 2, 3), VoiceCourse.openUnits(program, deeper, unitTwo + "u2-fuller"))
    }

    @Test
    fun `today's lesson is the server's choice, else the first open one not done`() {
        assertEquals("u2-resonance", VoiceCourse.next(program, VoiceFixtures.progress, lighter)!!.id)
        assertEquals("u1-care", VoiceCourse.next(program, null, lighter)!!.id)
        assertEquals("u1-pitch", VoiceCourse.next(program, VoiceProgress(VoicePlan(), lessonsDone = listOf("u1-care")), lighter)!!.id)
        // An id the phone doesn't know, or one for another goal, falls back to working it out.
        assertEquals("u1-pitch", VoiceCourse.next(program, VoiceProgress(VoicePlan(), lessonsDone = listOf("u1-care"), nextLessonId = "gone"), lighter)!!.id)
        val done = listOf("u1-care", "u1-pitch", "u2-resonance")
        assertEquals("u2-speech", VoiceCourse.next(program, VoiceProgress(VoicePlan(), lessonsDone = done, nextLessonId = "u2-fuller"), lighter)!!.id)
        assertEquals("u2-fuller", VoiceCourse.nextLesson(program, deeper, done.toSet())!!.id)
        val p = VoiceFixtures.progress
        assertEquals(LessonStatus.DONE, VoiceCourse.status(program.lessons[0], program, p, lighter))
        assertEquals(LessonStatus.NEXT, VoiceCourse.status(program.lessons[2], program, p, lighter))
        assertEquals(LessonStatus.OPEN, VoiceCourse.status(program.lessons.single { it.id == "u2-speech" }, program, p, lighter))
        assertEquals(LessonStatus.LOCKED, VoiceCourse.status(program.lessons.last(), program, p, lighter))
    }

    @Test
    fun `a lesson's exercises are only those that suit the goal`() {
        val lesson = program.lessons.single { it.id == "u2-resonance" }
        assertEquals(listOf("hum", "bright-vowels"), VoiceCourse.exercisesFor(program, lesson, lighter).map { it.id })
        assertEquals(listOf("hum", "dark-vowels"), VoiceCourse.exercisesFor(program, lesson, deeper).map { it.id })
        assertEquals(listOf("hum", "bright-vowels", "dark-vowels"), VoiceCourse.exercisesFor(program, lesson, VoiceGoal.EXPLORING).map { it.id })
        val darkOnly = lesson.copy(exerciseIds = listOf("dark-vowels"))
        assertTrue(VoiceCourse.exercisesFor(program, darkOnly, lighter).isEmpty())
        assertFalse(VoiceCourse.listens(VoiceFixtures.exercise("breath")))
        assertTrue(VoiceCourse.listens(VoiceFixtures.exercise("match")))
        assertTrue(VoiceCourse.meters(VoiceFixtures.exercise("breath")).isEmpty())
        assertEquals(VoiceFixtures.exercise("talk").meters, VoiceCourse.meters(VoiceFixtures.exercise("talk")))
        assertTrue(VoiceCourse.meters(VoiceFixtures.exercise("hum").copy(meters = emptyList())).isNotEmpty())
        // Resonance work always shows the live pitch trace too.
        val resonanceOnly = VoiceFixtures.exercise("bright-vowels").copy(meters = listOf(com.health.api.VoiceMeter.RESONANCE))
        assertEquals(listOf(com.health.api.VoiceMeter.RESONANCE, com.health.api.VoiceMeter.PITCH), VoiceCourse.meters(resonanceOnly))
    }

    // ---- The reminder ----

    @Test
    fun `the reminder comes at its time, skipping rest days and days already practised`() {
        val zone = ZoneId.of("Europe/London")
        fun at(date: LocalDate, h: Int, m: Int = 0) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        val friday = LocalDate.of(2026, 11, 13)
        // Before the time: today.
        assertEquals(at(friday, 18, 30), VoiceReminderPlan.next("18:30", emptyList(), null, at(friday, 9), zone))
        // After it: tomorrow.
        assertEquals(at(friday.plusDays(1), 18, 30), VoiceReminderPlan.next("18:30", emptyList(), null, at(friday, 19), zone))
        // Already practised today: tomorrow.
        assertEquals(at(friday.plusDays(1), 18, 30), VoiceReminderPlan.next("18:30", emptyList(), friday, at(friday, 9), zone))
        // Saturday and Sunday rest: Monday.
        assertEquals(at(friday.plusDays(3), 18, 30), VoiceReminderPlan.next("18:30", listOf(6, 7), null, at(friday, 19), zone))
        assertNull(VoiceReminderPlan.next(null, emptyList(), null, at(friday, 9), zone))
        assertNull(VoiceReminderPlan.next("18:30", (1..7).toList(), null, at(friday, 9), zone))
        assertNull(VoiceReminderPlan.next("not a time", emptyList(), null, at(friday, 9), zone))
        // The lock screen never says what it's for.
        listOf(VoiceReminders.TITLE, VoiceReminders.TEXT).forEach { words ->
            listOf("voice", "transition", "pitch", "train").forEach { assertFalse("$words mentions $it", words.lowercase().contains(it)) }
        }
    }

    // ---- Words ----

    @Test
    fun `summaries are short and kind, and never shame`() {
        val match = VoiceFixtures.exercise("match")
        assertTrue(VoiceWords.summary(match, ExerciseResult("match", 60, matched = 6, of = 6), plan).first().contains("all 6"))
        assertEquals("You matched 4 of 6 notes.", VoiceWords.summary(match, ExerciseResult("match", 60, matched = 4, of = 6), plan).first())
        assertTrue(VoiceWords.summary(match, ExerciseResult("match", 60, matched = 0, of = 6), plan).first().contains("takes time"))
        val result = ExerciseResult("phrases", 120, pitchAvgHz = 182.0, inRange = 64, resonance = 58.0, weight = 72.0, intonation = 2.6)
        val lines = VoiceWords.summary(VoiceFixtures.exercise("phrases"), result, plan)
        assertEquals("Your pitch averaged 182 Hz (F#3), in your range 64% of the time.", lines[0])
        assertEquals("Resonance about 58, a little darker than your target.", lines[1])
        assertEquals("Vocal weight about 72, on the heavier side.", lines[2])
        assertEquals("Your melody moved about 2.6 semitones: lively.", lines[3])
        assertEquals(5, lines.size)
        val glide = VoiceWords.summary(VoiceFixtures.exercise("sirens"), ExerciseResult("sirens", 60, pitchMinHz = 130.0, pitchMaxHz = 330.0), plan)
        assertTrue(glide.first().startsWith("You glided from 130 Hz"))
        val all = (lines + glide + VoiceWords.REST_NOTE + VoiceWords.NO_MIC + VoiceWords.MIC_WHY + (0..9).map(VoiceWords::cheer)).joinToString(" ").lowercase()
        listOf("bad", "wrong", "fail", "poor", "should have", "male", "female", "masculine", "feminine").forEach { assertFalse("Says \"$it\"", all.contains(it)) }
        assertEquals("right around your target", VoiceWords.against(63.0, 65.0, "darker", "brighter"))
        assertEquals("In your range", VoiceWords.pitch(180.0, 165.0, 220.0))
        assertEquals("Below your range", VoiceWords.pitch(150.0, 165.0, 220.0))
        assertNull(VoiceWords.pitch(150.0, null, null))
    }

    // ---- Live ----

    @Test
    fun `the live meter steadies values, keeps ten seconds of trace, and notices pushing`() {
        val meter = LiveMeter(speakingDb = -38.0)
        var live = VoiceLive()
        // Six seconds at a comfortable level.
        frames(List(300) { 180.0 }, db = -36.0).forEach { live = meter.add(it) }
        assertEquals(180.0, live.pitchHz!!, 0.0)
        assertEquals(60.0, live.resonance!!, 0.0)
        assertFalse(live.pushing)
        assertEquals(6.0, live.voicedSeconds, 0.01)
        assertEquals(0.0, live.intonation!!, 0.01)
        // Then five seconds much louder.
        var t = 302 * 20L
        repeat(250) {
            live = meter.add(VoiceFrame(t, -22.0, 180.0, 60.0, 40.0))
            t += 20
        }
        assertTrue(live.pushing)
        // The trace keeps the last ten seconds.
        assertTrue(live.trace.first().at >= live.trace.last().at - 10_000)
        // Silence lets go of the reading.
        repeat(20) {
            live = meter.add(VoiceFrame(t, -80.0))
            t += 20
        }
        assertNull(live.pitchHz)
    }

    @Test
    fun `target tones are at their pitch and fade in and out`() {
        val tone = ToneSynth.samples(220.0, 44_100, 1.0)
        assertEquals(0f, tone.first(), 1e-6f)
        assertTrue(kotlin.math.abs(tone.last()) < 0.01f)
        assertTrue(tone.maxOf { kotlin.math.abs(it) } <= 0.36f)
        val middle = tone.copyOfRange(20_000, 22_048)
        assertEquals(220.0, PitchDetector(44_100).detect(middle)!!, 1.5)
    }
}
