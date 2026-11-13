package com.health.server

import com.health.api.ApiJson
import com.health.api.ExerciseKind
import com.health.api.ExerciseResult
import com.health.api.Module
import com.health.api.Profile
import com.health.api.ReminderPrefs
import com.health.api.ReportSectionKey
import com.health.api.VoiceCatalog
import com.health.api.VoiceGoal
import com.health.api.VoicePlan
import com.health.api.VoiceProgram
import com.health.api.VoiceSession
import com.health.api.VoiceWeek
import com.health.api.WebhookEvent
import com.health.server.platform.Scheduler
import com.health.server.transition.DatedSession
import com.health.server.transition.VoiceTrainer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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

/** The voice trainer: the programme, the plan, sessions, progress, the reminder, and where it shows up. */
class VoiceTrainerTest {
    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 3, 20) // a Friday
    private val now = at(today, 15)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun services(modules: Set<Module> = Module.entries.toSet()) = testServices(clock = { now }).also { s ->
        runBlocking {
            s.bootstrap()
            s.profiles.put(s.users.byUsername("admin")!!.id, Profile(timezone = zone.id, modules = modules))
        }
    }

    private fun Services.admin() = runBlocking { users.byUsername("admin")!!.id }

    // ---- The programme ----

    @Test
    fun `the programme is consistent, with a full path through every unit for every goal`() {
        val p = VoiceCatalog.PROGRAM
        val ids = p.exercises.map { it.id }
        assertEquals("exercise ids are unique", ids.size, ids.toSet().size)
        assertEquals("lesson ids are unique", p.lessons.size, p.lessons.map { it.id }.toSet().size)
        assertTrue("${p.exercises.size} exercises", p.exercises.size in 35..50)
        assertTrue("${p.lessons.size} lessons", p.lessons.size in 20..30)

        // Units are contiguous from 1, lessons come in unit order, and each unit has a heading.
        val units = p.lessons.map { it.unit }
        assertEquals((1..units.max()).toList(), units.distinct())
        assertEquals(units.sorted(), units)
        assertEquals(units.distinct(), p.units.map { it.unit })

        for (lesson in p.lessons) {
            assertTrue(lesson.id, lesson.exerciseIds.isNotEmpty())
            lesson.exerciseIds.forEach { assertNotNull("${lesson.id}: $it", VoiceCatalog.exercise(it)) }
            assertTrue("${lesson.id} takes ${lesson.minutes} minutes", lesson.minutes in 5..15)
            assertTrue(lesson.title.isNotBlank() && lesson.about.isNotBlank())
            // Every exercise in a lesson is done by someone whose goal the lesson suits.
            val goals = lesson.goals.ifEmpty { VoiceGoal.entries.toSet() }
            lesson.exerciseIds.forEach { id -> assertTrue("${lesson.id}: $id", goals.any { VoiceCatalog.suits(VoiceCatalog.exercise(id)!!, it) }) }
        }
        // Every exercise is in some lesson.
        assertEquals(emptySet<String>(), ids.toSet() - p.lessons.flatMap { it.exerciseIds }.toSet())

        for (goal in VoiceGoal.entries) {
            val path = VoiceCatalog.lessonsFor(goal)
            for (unit in units.distinct()) {
                val inUnit = path.filter { it.unit == unit }
                assertTrue("$goal has no lesson in unit $unit", inUnit.isNotEmpty())
                inUnit.forEach { assertTrue("${it.id} is empty for $goal", VoiceCatalog.exercisesFor(it, goal).isNotEmpty()) }
            }
        }

        for (e in p.exercises) {
            assertTrue(e.id, e.title.isNotBlank() && e.steps.isNotEmpty() && e.steps.none { it.isBlank() })
            assertTrue("${e.id}: ${e.seconds} s", e.seconds in 20..300)
            assertTrue(e.id, e.texts.none { it.isBlank() })
            assertTrue(e.id, e.tip == null || e.tip!!.isNotBlank())
            when (e.kind) {
                ExerciseKind.MATCH -> {
                    assertTrue("${e.id} needs notes", e.notes.isNotEmpty())
                    assertTrue(e.id, e.notes.all { it in -7..7 })
                }
                ExerciseKind.SAY, ExerciseKind.READ, ExerciseKind.SPEAK -> assertTrue("${e.id} needs texts", e.texts.isNotEmpty())
                else -> {}
            }
            if (e.kind != ExerciseKind.GUIDED) assertTrue("${e.id} shows a meter", e.meters.isNotEmpty())
        }
        // Lots to say, and two or more passages.
        assertTrue(p.exercises.filter { it.kind == ExerciseKind.SAY }.sumOf { it.texts.size } >= 150)
        assertTrue(p.exercises.filter { it.kind == ExerciseKind.READ }.sumOf { it.texts.size } >= 3)
        // Direction comes only from the goal: brightening for lighter, darkening for deeper, both while exploring.
        val bright = VoiceCatalog.exercise("whisper-siren")!!
        val dark = VoiceCatalog.exercise("yawn-space")!!
        assertTrue(VoiceCatalog.suits(bright, VoiceGoal.LIGHTER) && !VoiceCatalog.suits(bright, VoiceGoal.DEEPER))
        assertTrue(VoiceCatalog.suits(dark, VoiceGoal.DEEPER) && !VoiceCatalog.suits(dark, VoiceGoal.LIGHTER))
        assertTrue(VoiceCatalog.suits(bright, VoiceGoal.EXPLORING) && VoiceCatalog.suits(dark, VoiceGoal.EXPLORING))

        // The health guidance covers rest, stopping, water, and seeing an SLP.
        val health = p.health.joinToString(" ")
        listOf("Warm up", "Stop if it hurts", "rest days", "water", "speech-language pathologist", "isn't medical advice").forEach {
            assertTrue(it, health.contains(it, ignoreCase = true))
        }
        assertTrue(p.daily.isNotEmpty())

        // The copy never assumes anyone's gender.
        val words = Regex("""\b(he|she|his|her|him|hers|man|men|woman|women|male|female|masculine|feminine|girl|boy|lady|ladies|guy|guys|sir|madam)\b""", RegexOption.IGNORE_CASE)
        val copy = p.exercises.flatMap { it.steps + it.texts + listOfNotNull(it.title, it.tip) } +
            p.lessons.flatMap { listOf(it.title, it.about) } + p.units.flatMap { listOf(it.title, it.about) } + p.health + p.daily
        copy.forEach { assertNull(it, words.find(it)?.value) }

        // It goes over the wire as is.
        assertEquals(p, ApiJson.decodeFromString(VoiceProgram.serializer(), ApiJson.encodeToString(VoiceProgram.serializer(), p)))
    }

    // ---- The plan ----

    @Test
    fun `the plan is checked, sealed, and served with the programme`() = serverTest(services()) { s ->
        val admin = adminBrowser()
        assertEquals(VoicePlan(), ApiJson.decodeFromString(VoicePlan.serializer(), admin.get("/api/transition/voice/plan").json().toString()))
        val ok = admin.putJson(
            "/api/transition/voice/plan",
            """{"goal":"LIGHTER","targetLowHz":170,"targetHighHz":210,"targetResonance":65,"baselinePitchHz":118,"baselineResonance":28,"dailyMinutes":12,"reminder":"7:05","restDays":[7,6,6]}""",
        )
        assertEquals(HttpStatusCode.OK, ok.status)
        val plan = ApiJson.decodeFromString(VoicePlan.serializer(), ok.json().toString())
        assertEquals("07:05", plan.reminder)
        assertEquals(listOf(6, 7), plan.restDays)
        assertEquals(plan, s.transition.trainer.plan(s.admin()))
        val raw = s.db.tx { query("SELECT data FROM user_settings") { it.string("data") } }.joinToString()
        assertFalse(raw.contains("LIGHTER") || raw.contains("targetLowHz"))

        val bad = listOf(
            """{"targetLowHz":50,"targetHighHz":200}""",
            """{"targetLowHz":150,"targetHighHz":520}""",
            """{"targetLowHz":200,"targetHighHz":180}""",
            """{"targetLowHz":200,"targetHighHz":200}""",
            """{"targetLowHz":180}""",
            """{"targetResonance":120}""",
            """{"targetResonance":-1}""",
            """{"baselineResonance":101}""",
            """{"dailyMinutes":0}""",
            """{"dailyMinutes":61}""",
            """{"reminder":"7pm"}""",
            """{"reminder":"24:00"}""",
            """{"restDays":[0]}""",
            """{"restDays":[8]}""",
            """{"baselineAt":${now + 86_400_000L}}""",
        )
        bad.forEach { body -> assertEquals(body, HttpStatusCode.BadRequest, admin.putJson("/api/transition/voice/plan", body).status) }
        assertEquals(plan, s.transition.trainer.plan(s.admin()))
        assertEquals(HttpStatusCode.OK, admin.putJson("/api/transition/voice/plan", """{"targetLowHz":60,"targetHighHz":500,"dailyMinutes":60,"reminder":" "}""").status)
        assertNull(s.transition.trainer.plan(s.admin()).reminder)

        val program = admin.get("/api/transition/voice/program").obj()
        assertEquals(VoiceCatalog.LESSONS.size, program["lessons"]!!.jsonArray.size)
        assertEquals(VoiceCatalog.EXERCISES.size, program["exercises"]!!.jsonArray.size)
    }

    // ---- Sessions ----

    @Test
    fun `sessions from the trainer are checked against the programme`() = serverTest(services()) { s ->
        val admin = adminBrowser()
        val good = admin.postJson(
            "/api/transition/voice",
            """{"at":${at(today, 9)},"minutes":9,"lessonId":"res-bright","resonance":61.5,"weight":40,"results":[
                {"exerciseId":"whisper-siren","seconds":120,"resonance":60,"pitchAvgHz":190,"inRange":70},
                {"exerciseId":"mm-hmm","seconds":90,"resonance":63,"inRange":80,"feeling":4},
                {"exerciseId":"match-centre","seconds":90,"matched":8,"of":10}]}""",
        )
        assertEquals(HttpStatusCode.OK, good.status)
        val saved = good.obj()
        assertEquals("res-bright", saved.str("lessonId"))
        assertEquals(listOf("Whisper siren", "'Mm-hmm' into words", "Matching the middle"), saved["exercises"]!!.jsonArray.map { it.jsonPrimitive.content })

        val bad = listOf(
            """{"exerciseId":"sing-opera","seconds":60}""",
            """{"exerciseId":"mm-hmm","seconds":-1}""",
            """{"exerciseId":"mm-hmm","seconds":60,"inRange":140}""",
            """{"exerciseId":"mm-hmm","seconds":60,"resonance":101}""",
            """{"exerciseId":"mm-hmm","seconds":60,"weight":-3}""",
            """{"exerciseId":"mm-hmm","seconds":60,"pitchAvgHz":2000}""",
            """{"exerciseId":"mm-hmm","seconds":60,"pitchMinHz":300,"pitchMaxHz":200}""",
            """{"exerciseId":"match-centre","seconds":60,"matched":11,"of":10}""",
            """{"exerciseId":"mm-hmm","seconds":60,"feeling":9}""",
        )
        bad.forEach { r ->
            assertEquals(r, HttpStatusCode.BadRequest, admin.postJson("/api/transition/voice", """{"at":${at(today, 10)},"minutes":5,"results":[$r]}""").status)
        }
        assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/transition/voice", """{"at":${at(today, 10)},"minutes":5,"lessonId":"karaoke"}""").status)
        assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/transition/voice", """{"at":${at(today, 10)},"minutes":5,"resonance":180}""").status)
        // A session logged by hand, without the trainer, is still fine.
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/transition/voice", """{"at":${at(today, 11)},"minutes":15,"exercises":["humming"],"pitchAvgHz":180}""").status)
        assertEquals(2, admin.get("/api/transition/voice").json().jsonArray.size)
    }

    // ---- Progress ----

    private fun dated(date: LocalDate, minutes: Int = 10, lesson: String? = null, inRange: Int? = null, resonance: Double? = null, pitch: Double? = null, strain: Boolean = false) =
        DatedSession(
            date,
            VoiceSession(
                at(date, 9), minutes, lessonId = lesson, strain = strain, resonance = resonance, pitchAvgHz = pitch,
                results = listOfNotNull(inRange?.let { ExerciseResult("mm-hmm", 120, inRange = it) }),
            ),
        )

    @Test
    fun `the next lesson follows the units, by goal`() {
        val throughUnit3 = VoiceCatalog.LESSONS.filter { it.unit <= 3 }.map { it.id }.toSet()
        assertEquals("care", VoiceCatalog.nextLesson(VoiceGoal.LIGHTER, emptySet())!!.id)
        assertEquals("res-feel", VoiceCatalog.nextLesson(VoiceGoal.DEEPER, throughUnit3)!!.id)
        assertEquals("res-bright", VoiceCatalog.nextLesson(VoiceGoal.LIGHTER, throughUnit3 + "res-feel")!!.id)
        assertEquals("res-dark", VoiceCatalog.nextLesson(VoiceGoal.DEEPER, throughUnit3 + "res-feel")!!.id)
        assertEquals("res-bright", VoiceCatalog.nextLesson(VoiceGoal.EXPLORING, throughUnit3 + "res-feel")!!.id)
        assertEquals("res-dark", VoiceCatalog.nextLesson(VoiceGoal.EXPLORING, throughUnit3 + "res-feel" + "res-bright")!!.id)
        // A lighter path skips the darker lessons.
        assertEquals("res-vowels", VoiceCatalog.nextLesson(VoiceGoal.LIGHTER, throughUnit3 + "res-feel" + "res-bright")!!.id)
        assertNull(VoiceCatalog.nextLesson(VoiceGoal.DEEPER, VoiceCatalog.lessonsFor(VoiceGoal.DEEPER).map { it.id }.toSet()))
        // The pitch lesson gives each goal its own advice on choosing a range.
        val range = VoiceCatalog.lesson("pitch-range")!!
        assertEquals(listOf("range-lighter"), VoiceCatalog.exercisesFor(range, VoiceGoal.LIGHTER).map { it.id }.filter { it.startsWith("range-") && it != "range-explore" })
        assertEquals(listOf("range-deeper"), VoiceCatalog.exercisesFor(range, VoiceGoal.DEEPER).map { it.id }.filter { it.startsWith("range-") && it != "range-explore" })

        val progress = VoiceTrainer.progress(VoicePlan(goal = VoiceGoal.DEEPER), listOf(dated(today, lesson = "care"), dated(today.minusDays(1), lesson = "starting-point")), today)
        assertEquals(listOf("care", "starting-point"), progress.lessonsDone)
        assertEquals("warm-trills", progress.nextLessonId)
    }

    @Test
    fun `the streak counts days in a row, and rest days don't break it`() {
        val fri = today
        val days = setOf(fri, fri.minusDays(1), fri.minusDays(3), fri.minusDays(4)) // Fri, Thu, (Wed off), Tue, Mon
        assertEquals(2, VoiceTrainer.streak(days, emptySet(), fri))
        assertEquals(4, VoiceTrainer.streak(days, setOf(3), fri))
        // Today isn't over yet: the streak still stands from yesterday.
        assertEquals(1, VoiceTrainer.streak(setOf(fri.minusDays(1)), emptySet(), fri))
        assertEquals(0, VoiceTrainer.streak(setOf(fri.minusDays(2)), emptySet(), fri))
        assertEquals(0, VoiceTrainer.streak(emptySet(), setOf(1, 2, 3, 4, 5, 6, 7), fri))
        assertEquals(1, VoiceTrainer.streak(setOf(fri.minusDays(10)), setOf(1, 2, 3, 4, 5, 6, 7), fri))
    }

    @Test
    fun `weeks average each week, and the highlights are kind and compare with the start and the week before`() {
        val monday = LocalDate.of(2026, 3, 16)
        val sessions = listOf(
            dated(monday.minusDays(7), 10, inRange = 40, resonance = 40.0, pitch = 150.0),
            dated(monday.minusDays(5), 10, inRange = 42, resonance = 44.0, pitch = 160.0),
            dated(monday, 15, inRange = 60, resonance = 60.0, pitch = 185.0),
            dated(monday.plusDays(2), 15, inRange = 68, resonance = 64.0, pitch = 195.0),
            dated(today, 20, inRange = 64, resonance = 62.0, pitch = 190.0),
        )
        val weeks = VoiceTrainer.weeks(sessions, today)
        assertEquals(listOf("2026-03-09", "2026-03-16"), weeks.map { it.weekStart })
        assertEquals(listOf(20, 50), weeks.map { it.minutes })
        assertEquals(listOf(2, 3), weeks.map { it.sessions })
        assertEquals(41, weeks[0].inRange)
        assertEquals(64, weeks[1].inRange)
        assertEquals(62.0, weeks[1].resonance!!, 0.01)
        assertEquals(190.0, weeks[1].pitchAvgHz!!, 0.01)
        // Only the last twelve weeks.
        assertEquals(12, VoiceTrainer.weeks(sessions + dated(today.minusDays(200)), today).size)

        val plan = VoicePlan(VoiceGoal.LIGHTER, 170.0, 210.0, targetResonance = 65.0, baselinePitchHz = 120.0, baselineResonance = 30.0)
        val lines = VoiceTrainer.highlights(plan, weeks, streak = 4, done = 5, total = 22, today = today)
        assertTrue(lines.toString(), "In your range 64% of the time this week, up from 41%." in lines)
        assertTrue(lines.toString(), "50 minutes of practice this week, more than the week before." in lines)
        assertTrue(lines.toString(), lines.any { it.startsWith("Your average pitch this week, 190 Hz, sat in your target range") && it.contains("120 Hz") })
        assertTrue(lines.toString(), "Resonance 62 this week: right around your target of 65." in lines)
        assertTrue(lines.toString(), "4 practice days in a row. Lovely consistency." in lines)
        assertTrue(lines.toString(), "5 of 22 lessons done." in lines)
        // A deeper goal reads just as kindly: nearer the target, never "higher" or "lower".
        val deeper = VoicePlan(VoiceGoal.DEEPER, 95.0, 130.0, targetResonance = 20.0, baselinePitchHz = 160.0, baselineResonance = 70.0)
        val low = listOf(VoiceWeek("2026-03-16", 30, 3, pitchAvgHz = 140.0, inRange = 35, resonance = 45.0))
        val deeperLines = VoiceTrainer.highlights(deeper, low, 0, 0, 22, today)
        assertTrue(deeperLines.toString(), deeperLines.any { it.contains("140 Hz, was nearer your range than when you started (160 Hz)") })
        assertTrue(deeperLines.toString(), deeperLines.any { it.contains("Resonance 45 this week, nearer your target of 20 than when you started (70)") })
        (lines + deeperLines).forEach { assertFalse(it, Regex("""\b(higher|lower|deeper|brighter|darker)\b""").containsMatchIn(it)) }
        // A dip is met kindly, and last week stands in until this week's first session.
        val dip = listOf(VoiceWeek("2026-03-09", 40, 4, inRange = 70), VoiceWeek("2026-03-16", 20, 2, inRange = 50))
        assertTrue(VoiceTrainer.highlights(plan, dip, 0, 0, 22, today).any { it.contains("not a step back") })
        val quiet = listOf(VoiceWeek("2026-03-09", 40, 4, inRange = 70), VoiceWeek("2026-03-16", 0, 0))
        assertTrue(VoiceTrainer.highlights(plan, quiet, 0, 0, 22, today).contains("In your range 70% of the time last week."))
        // Nothing yet: an invitation, not a nag.
        assertTrue(VoiceTrainer.highlights(VoicePlan(), emptyList(), 0, 0, 22, today).single().startsWith("Start whenever you're ready"))
    }

    @Test
    fun `strain brings a rest note, and a stronger one if it keeps happening`() = runBlocking {
        val s = services()
        val id = s.admin()
        assertNull(s.transition.trainer.progress(id).restNote)
        s.transition.saveVoice(id, VoiceSession(at(today.minusDays(3), 9), 10))
        s.transition.saveVoice(id, VoiceSession(at(today.minusDays(1), 9), 10, strain = true))
        val note = s.transition.trainer.progress(id).restNote
        assertNotNull(note)
        assertTrue(note!!, note.contains("Rest your voice") && note.contains("speech-language pathologist"))
        // Long after, with no more strain, the note goes.
        assertNull(VoiceTrainer.restNote(listOf(dated(today.minusDays(6), strain = true)), today))
        assertNull(VoiceTrainer.restNote(listOf(dated(today), dated(today.minusDays(1), strain = true)).sortedByDescending { it.session.at }, today))
        val often = listOf(dated(today.minusDays(2), strain = true), dated(today.minusDays(5), strain = true), dated(today.minusDays(9), strain = true), dated(today))
        assertTrue(VoiceTrainer.restNote(often, today)!!.startsWith("Strain has come up 3 times"))
    }

    // ---- The reminder ----

    @Test
    fun `the reminder comes at the plan's time, not on rest days or once practice is done, and says nothing on a locked screen`() = runBlocking {
        val s = services()
        val id = s.admin()
        val profile = s.profiles.get(id)
        val hooks = s.hooks.first { it === s.transition }
        suspend fun around(date: LocalDate) = hooks.reminders(id, profile, at(date, 18, 25), at(date, 18, 35))
        assertTrue("no reminder without a time", around(today).isEmpty())

        s.transition.trainer.savePlan(id, VoicePlan(goal = VoiceGoal.EXPLORING, reminder = "18:30", restDays = listOf(6, 7), dailyMinutes = 10))
        val r = around(today).single()
        assertEquals("voice:2026-03-20", r.key)
        assertEquals(at(today, 18, 30), r.at)
        assertEquals(WebhookEvent.VOICE_PRACTICE, r.event)
        assertEquals("About 10 minutes. Next up: Looking after your voice.", r.body)
        // On a locked screen (names off by default) it doesn't say what it's for.
        val locked = Scheduler.message(r, ReminderPrefs())
        listOf(locked.title, locked.body, r.privateTitle, r.privateBody).forEach { text ->
            assertFalse(text, Regex("voice|transition|pitch|resonance|lesson|trainer", RegexOption.IGNORE_CASE).containsMatchIn(text))
        }
        assertEquals("Time for a little practice", locked.title)
        assertFalse(r.data.toString().contains("lesson"))
        // The scheduler picks it up with Transition on, and not without it.
        assertEquals(listOf("voice:2026-03-20"), s.platform.scheduler.collect(id, profile, at(today, 18, 25), at(today, 18, 35)).map { it.key }.filter { it.startsWith("voice:") })
        val off = profile.copy(modules = Module.DEFAULT)
        assertTrue(s.platform.scheduler.collect(id, off, at(today, 18, 25), at(today, 18, 35)).none { it.key.startsWith("voice:") })

        // Saturday and Sunday are rest days; Monday's back on.
        assertTrue(around(today.plusDays(1)).isEmpty())
        assertTrue(around(today.plusDays(2)).isEmpty())
        assertEquals("voice:2026-03-23", around(today.plusDays(3)).single().key)
        // The day after strain, it asks for a gentle start.
        s.transition.saveVoice(id, VoiceSession(at(today.minusDays(1), 12), 5, strain = true))
        val gently = around(today).single()
        assertTrue(gently.body, gently.body.contains("gently"))
        // Once today's practice is done, there's no reminder.
        s.transition.saveVoice(id, VoiceSession(at(today, 12), 10, lessonId = "care"))
        assertTrue(around(today).isEmpty())
    }

    // ---- Sharing, the digest, the report and the connector ----

    @Test
    fun `a share with transition sees the plan and progress but can't change them`() = serverTest(services()) { s ->
        val ownerId = s.admin()
        s.users.create("sam", "Sam", password = "sam-password-123")
        val owner = adminBrowser()
        assertEquals(HttpStatusCode.OK, owner.putJson("/api/transition/voice/plan", """{"goal":"DEEPER","targetLowHz":95,"targetHighHz":130}""").status)
        val share = owner.postJson("/api/shares", """{"username":"sam","modules":["ACTIVITY"]}""").obj().str("id")
        val viewer = browser().also { it.post("/api/auth/login") { json("""{"username":"sam","password":"sam-password-123"}""") } }
        assertEquals(HttpStatusCode.Forbidden, viewer.get("/api/transition/voice/plan") { header("X-SAMH-View", ownerId) }.status)
        assertEquals(HttpStatusCode.Forbidden, viewer.get("/api/transition/voice/progress") { header("X-SAMH-View", ownerId) }.status)

        assertEquals(HttpStatusCode.OK, owner.putJson("/api/shares/$share", """{"modules":["ACTIVITY","TRANSITION"]}""").status)
        val seen = viewer.get("/api/transition/voice/plan") { header("X-SAMH-View", ownerId) }
        assertEquals(HttpStatusCode.OK, seen.status)
        assertEquals("DEEPER", seen.obj().str("goal"))
        assertEquals(HttpStatusCode.OK, viewer.get("/api/transition/voice/progress") { header("X-SAMH-View", ownerId) }.status)
        assertEquals(HttpStatusCode.OK, viewer.get("/api/transition/voice/program") { header("X-SAMH-View", ownerId) }.status)
        val change = viewer.put("/api/transition/voice/plan") { header("X-SAMH-View", ownerId); csrf(); json("""{"goal":"LIGHTER"}""") }
        assertEquals(HttpStatusCode.Forbidden, change.status)
        assertEquals(VoiceGoal.DEEPER, s.transition.trainer.plan(ownerId).goal)
        // Sam's own plan is Sam's.
        assertEquals("EXPLORING", viewer.get("/api/transition/voice/plan").obj().str("goal"))
    }

    @Test
    fun `practice shows in the digest, the report and the connector`() = runBlocking {
        val s = services()
        val id = s.admin()
        s.transition.trainer.savePlan(id, VoicePlan(VoiceGoal.LIGHTER, 170.0, 210.0, targetResonance = 65.0, baselinePitchHz = 120.0, baselineResonance = 30.0))
        val monday = LocalDate.of(2026, 3, 16)
        s.transition.saveVoice(id, VoiceSession(at(monday.minusDays(5), 9), 10, resonance = 45.0, results = listOf(ExerciseResult("mm-hmm", 120, inRange = 40))))
        s.transition.saveVoice(id, VoiceSession(at(monday, 9), 12, lessonId = "care", resonance = 58.0, results = listOf(ExerciseResult("mm-hmm", 120, inRange = 60, pitchAvgHz = 185.0))))
        s.transition.saveVoice(id, VoiceSession(at(monday.plusDays(2), 9), 8, lessonId = "starting-point", resonance = 62.0, results = listOf(ExerciseResult("mm-hmm", 120, inRange = 70, pitchAvgHz = 190.0))))
        val profile = s.profiles.get(id)

        val digest = s.transition.digest(id, profile, monday, monday.plusDays(6))!!
        val line = digest.lines.single { it.startsWith("Voice practice") }
        assertEquals("Voice practice: 20 minutes in 2 sessions over 2 days, in your range 65% of the time, resonance 60, nearer your target than the week before (45).", line)

        val report = s.platform.reports.sections(id, profile, today.minusDays(30), today, listOf(ReportSectionKey.TRANSITION)).single()
        assertTrue(report.summary.toString(), report.summary.any { it.startsWith("Voice practice: 30 minutes in 3 sessions, in the target range 57% of the time, resonance 45 at first and 62 lately") })
        assertTrue(report.summary.toString(), report.summary.contains("Voice programme: 2 of ${VoiceCatalog.lessonsFor(VoiceGoal.LIGHTER).size} lessons done (goal: lighter and brighter, target range 170–210 Hz)."))
        assertTrue(report.charts.any { it.title == "Voice resonance" })
        assertEquals(170.0..210.0, report.charts.single { it.title == "Voice pitch" }.band)

        val csv = s.transition.export(id, profile, today.minusDays(30), today)["voice.csv"]!!
        assertEquals(listOf("lesson", "resonance", "weight", "in_range", "strain"), csv.first().takeLast(5))

        val mcp = com.health.server.mcp.McpServer(s)
        val request = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0")); put("id", JsonPrimitive(1)); put("method", JsonPrimitive("tools/call"))
            put("params", buildJsonObject { put("name", JsonPrimitive("transition")); put("arguments", buildJsonObject {}) })
        }
        val text = mcp.handle(com.health.server.mcp.McpServer.Caller(s.users.byId(id)!!, false, false, setOf(Module.TRANSITION)), request)!!.toString()
        assertTrue(text, text.contains("Voice programme: goal lighter and brighter, target range 170–210 Hz, resonance target 65 of 100; 2 of"))
        assertTrue(text, text.contains("next: Trills and hums"))
        // Out of the link's scope, nothing.
        val none = mcp.handle(com.health.server.mcp.McpServer.Caller(s.users.byId(id)!!, false, false, setOf(Module.ACTIVITY)), request)!!.toString()
        assertFalse(none.contains("Voice programme"))

        val progress = s.transition.trainer.progress(id)
        assertEquals("warm-trills", progress.nextLessonId)
        assertEquals(20, progress.minutesThisWeek)
        assertTrue(progress.highlights.toString(), progress.highlights.contains("In your range 65% of the time this week, up from 40%."))
    }
}
