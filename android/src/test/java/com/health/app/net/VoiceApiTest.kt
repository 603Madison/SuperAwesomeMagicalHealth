package com.health.app.net

import com.health.api.ExerciseResult
import com.health.api.VoiceGoal
import com.health.api.VoicePlan
import com.health.api.VoiceSession
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The voice trainer's endpoints (docs/API.md, "Transition"). */
class VoiceApiTest {
    private val server = MockWebServer()
    private lateinit var api: ServerApi

    @Before
    fun start() {
        server.start()
        api = ServerApi(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, { "app-token" }, {})
    }

    @After
    fun stop() = server.shutdown()

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `the programme, plan and progress are read from their paths`() = runBlocking {
        server.enqueue(
            json(
                """{"lessons":[{"id":"l1","unit":1,"title":"Starting well","about":"…","skill":"HEALTH","exerciseIds":["e1"],"minutes":5}],
                |"exercises":[{"id":"e1","title":"Hum","skill":"WARM_UP","kind":"HOLD","steps":["Hum"],"seconds":30,"meters":["PITCH"]}],
                |"health":["Rest"],"daily":["Hum"]}""".trimMargin(),
            ),
        )
        val program = api.voiceProgram()
        assertEquals("/api/transition/voice/program", server.takeRequest().path)
        assertEquals("Hum", program.exercises.single().title)
        assertEquals(listOf("e1"), program.lessons.single().exerciseIds)

        server.enqueue(json("""{"goal":"DEEPER","targetLowHz":100.0,"targetHighHz":140.0}"""))
        assertEquals(VoiceGoal.DEEPER, api.voicePlan().goal)
        assertEquals("/api/transition/voice/plan", server.takeRequest().path)

        server.enqueue(json("""{"plan":{},"lessonsDone":["l1"],"nextLessonId":"l2","streakDays":3,"minutesThisWeek":25,"weeks":[{"weekStart":"2026-11-09","minutes":25,"sessions":3,"inRange":40}]}"""))
        val progress = api.voiceProgress()
        assertEquals("/api/transition/voice/progress", server.takeRequest().path)
        assertEquals(3, progress.streakDays)
        assertEquals(40, progress.weeks.single().inRange)
    }

    @Test
    fun `the plan is saved with a PUT, and a session with its results is posted`() = runBlocking {
        server.enqueue(json("""{"goal":"LIGHTER","targetLowHz":165.0,"targetHighHz":220.0,"reminder":"18:30","restDays":[7]}"""))
        api.saveVoicePlan(VoicePlan(VoiceGoal.LIGHTER, 165.0, 220.0, 65.0, reminder = "18:30", restDays = listOf(7)))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/transition/voice/plan", put.path)
        val plan = put.body.readUtf8()
        assertTrue(plan, plan.contains("\"targetResonance\":65.0") && plan.contains("\"restDays\":[7]"))

        server.enqueue(json("""{"at":1,"minutes":8,"lessonId":"l1","id":"v1"}"""))
        api.addVoiceSession(
            VoiceSession(
                at = 1, minutes = 8, lessonId = "l1", strain = true, resonance = 55.0,
                results = listOf(ExerciseResult("e1", 30, pitchAvgHz = 180.0, inRange = 70, matched = 4, of = 5, feeling = 4)),
            ),
        )
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/api/transition/voice", post.path)
        val body = post.body.readUtf8()
        assertTrue(body, body.contains("\"lessonId\":\"l1\"") && body.contains("\"matched\":4") && body.contains("\"strain\":true"))
        // Numbers only: no sound ever goes.
        assertTrue(body, !body.contains("audio") && !body.contains("wav"))
    }
}
