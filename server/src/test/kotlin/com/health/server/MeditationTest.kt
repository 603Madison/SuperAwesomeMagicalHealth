package com.health.server

import com.health.api.ApiJson
import com.health.api.AppPage
import com.health.api.DayFeed
import com.health.api.Meditation
import com.health.api.MeditationKind
import com.health.api.MeditationSession
import com.health.api.MeditationSettings
import com.health.api.MeditationSummary
import com.health.api.Module
import com.health.api.NowAction
import com.health.api.NowKind
import com.health.api.Profile
import com.health.api.SleepSession
import com.health.api.TimelineKind
import com.health.api.WebhookEvent
import com.health.server.ai.AiTools
import com.health.server.care.SharedView
import com.health.server.modules.EntryKind
import com.health.server.modules.MeditationHooks
import com.health.server.modules.MeditationService
import com.health.server.modules.Meditations
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
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
import kotlin.math.abs

/** Guided meditation: the scripts, sessions (sealed, shared only with Mind), Today, the reminder and the nights after. */
class MeditationTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 3, 20)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
    private fun at(hour: Int, minute: Int = 0) = at(today, hour, minute)
    private var time = at(15)

    private fun services() = testServices(clock = { time })

    private fun Services.admin() = runBlocking { users.byUsername("admin")!!.id }

    private fun session(startedAt: Long, minutes: Double = 10.0, finished: Boolean = true, id: String? = "wind-down-10", felt: Int? = null) =
        ApiJson.encodeToString(MeditationSession.serializer(), MeditationSession(startedAt, minutes, finished, id, felt))

    // ---------------------------------------------------------------- The scripts

    @Test
    fun `every script lands within 15 percent of its length, read at 2_5 words a second`() {
        assertEquals(15, Meditations.ALL.size)
        Meditations.ALL.forEach { m ->
            // Worked out here on its own, not with the server's helper.
            val spoken = m.steps.sumOf { s -> s.say.trim().split(Regex("\\s+")).size / 2.5 }
            val pacer = m.steps.sumOf { s -> s.breath?.let { b -> b.rounds * (b.inSeconds + b.holdSeconds + b.outSeconds) } ?: 0 }
            val pauses = m.steps.sumOf { it.pauseSeconds }
            val total = spoken + pacer + pauses
            val target = m.minutes * 60.0
            assertTrue("${m.id}: ${total.toInt()} s against $target", abs(total - target) <= target * 0.15)
            // The silence carries most of the time, and no step runs into the next.
            assertTrue("${m.id}: pauses carry most of it", pauses + pacer > spoken)
            assertTrue("${m.id}: every step has a pause", m.steps.all { it.pauseSeconds >= 3 && it.say.isNotBlank() })
        }
    }

    @Test
    fun `each kind comes in 5, 10 and 15 minutes, with stable ids`() {
        assertEquals(
            listOf(
                "wind-down-5", "wind-down-10", "wind-down-15", "body-scan-5", "body-scan-10", "body-scan-15",
                "breathing-5", "breathing-10", "breathing-15", "drifting-off-5", "drifting-off-10", "drifting-off-15",
                "gratitude-5", "gratitude-10", "gratitude-15",
            ),
            Meditations.ALL.map { it.id },
        )
        MeditationKind.entries.forEach { k -> assertEquals(listOf(5, 10, 15), Meditations.ALL.filter { it.kind == k }.map { it.minutes }) }
        // Slow breathing has its pacer: gentle, with no long holds.
        val breathing = Meditations.ALL.filter { it.kind == MeditationKind.BREATHING }
        assertTrue(breathing.all { m -> m.steps.any { it.breath != null } })
        assertTrue(Meditations.ALL.flatMap { it.steps }.mapNotNull { it.breath }.all { it.holdSeconds <= 2 && it.outSeconds >= it.inSeconds })
    }

    @Test
    fun `the words are kind and plain, for anybody, and never promise anything about health`() {
        val never = Regex(
            "\\b(he|she|him|his|her|hers|man|men|woman|women|girl|boy|lady|ladies|guy|guys|spoons?|cure|cures|heal|heals|healing|treat|treats|treatment|anxiety|insomnia|disorder|should)\\b",
            RegexOption.IGNORE_CASE,
        )
        Meditations.ALL.forEach { m ->
            (m.steps.map { it.say } + m.title + m.about).forEach { text -> assertNull("${m.id}: \"$text\"", never.find(text)) }
            val all = m.steps.joinToString(" ") { it.say }.lowercase()
            // Eyes open is fine, and stopping or moving any time is said at the start of every one.
            assertTrue(m.id, "keep them open" in all && "stop at any time" in all && "change position" in all)
        }
    }

    // ---------------------------------------------------------------- Sessions

    @Test
    fun `sessions are logged, listed, changed and deleted, and checked`() {
        val s = services()
        serverTest(s) {
            val me = s.admin()
            s.profiles.put(me, Profile(timezone = zone.id))
            val web = adminBrowser()

            val catalog = ApiJson.decodeFromString(ListSerializer(Meditation.serializer()), web.get("/api/meditations").bodyAsText())
            assertEquals(Meditations.ALL.map { it.id }, catalog.map { it.id })

            val posted = web.postJson("/api/meditations/sessions", session(at(today.minusDays(1), 21, 40), felt = 4))
            assertEquals(HttpStatusCode.OK, posted.status)
            val saved = ApiJson.decodeFromString(MeditationSession.serializer(), posted.bodyAsText())
            assertNotNull(saved.id)
            assertEquals(today.minusDays(1).toString(), saved.date)
            assertEquals("Wind down", saved.title)
            assertEquals(MeditationKind.WIND_DOWN, saved.kind)

            web.postJson("/api/meditations/sessions", session(at(14), minutes = 3.5, finished = false, id = null))
            val list = ApiJson.decodeFromString(ListSerializer(MeditationSession.serializer()), web.get("/api/meditations/sessions?days=7").bodyAsText())
            assertEquals(2, list.size)
            assertEquals(listOf(false, true), list.map { it.finished })
            assertNull(list.first().title)

            val changed = web.putJson("/api/meditations/sessions/${saved.id}", session(at(today.minusDays(1), 21, 40), felt = 5))
            assertEquals(5, ApiJson.decodeFromString(MeditationSession.serializer(), changed.bodyAsText()).feltAfter)

            assertEquals(HttpStatusCode.BadRequest, web.postJson("/api/meditations/sessions", session(at(14), minutes = 0.0)).status)
            assertEquals(HttpStatusCode.BadRequest, web.postJson("/api/meditations/sessions", session(at(14), felt = 9)).status)
            assertEquals(HttpStatusCode.BadRequest, web.postJson("/api/meditations/sessions", session(at(14), id = "not-one-of-ours")).status)
            assertEquals(HttpStatusCode.BadRequest, web.postJson("/api/meditations/sessions", session(at(today.plusDays(1), 14))).status)
            assertEquals(HttpStatusCode.NotFound, web.putJson("/api/meditations/sessions/nope", session(at(14))).status)

            assertEquals(HttpStatusCode.OK, web.delete("/api/meditations/sessions/${saved.id}") { csrf() }.status)
            assertEquals(1, ApiJson.decodeFromString(ListSerializer(MeditationSession.serializer()), web.get("/api/meditations/sessions").bodyAsText()).size)
            assertEquals(HttpStatusCode.NotFound, web.delete("/api/meditations/sessions/${saved.id}") { csrf() }.status)

            // Settings: off at 21:30 to start, sealed, and checked.
            assertEquals(MeditationSettings(false, "21:30"), ApiJson.decodeFromString(MeditationSettings.serializer(), web.get("/api/meditations/settings").bodyAsText()))
            val set = web.putJson("/api/meditations/settings", """{"reminder":true,"time":"9:05"}""")
            assertEquals(MeditationSettings(true, "09:05"), ApiJson.decodeFromString(MeditationSettings.serializer(), set.bodyAsText()))
            assertEquals(HttpStatusCode.BadRequest, web.putJson("/api/meditations/settings", """{"reminder":true,"time":"late"}""").status)
            val raw = s.db.tx { one("SELECT data FROM user_settings WHERE user_id = ? AND key = ?", me, MeditationService.SETTINGS_KEY) { it.string("data") } }!!
            assertFalse(raw.contains("09:05"))
        }
    }

    @Test
    fun `a session is sealed in the entries table`() = runBlocking {
        val s = services()
        s.bootstrap()
        val me = s.admin()
        s.profiles.put(me, Profile(timezone = zone.id))
        time = at(23)
        val saved = s.meditation.save(me, MeditationSession(at(21, 30), 12.5, true, "body-scan-15", 3))
        val rows = s.db.tx { query("SELECT kind, date, data FROM entries WHERE user_id = ?", me) { Triple(it.string("kind"), it.string("date"), it.string("data")) } }
        assertEquals(1, rows.size)
        val (kind, date, data) = rows.single()
        assertEquals(EntryKind.MEDITATION.name, kind)
        assertEquals(today.toString(), date)
        listOf("body-scan", "12.5", "feltAfter", "finished").forEach { assertFalse("$it is in the clear", data.contains(it)) }
        assertEquals(saved.copy(title = null, kind = null), s.meditation.sessions(me, 1).single().copy(title = null, kind = null))
    }

    @Test
    fun `every write and delete works the day out again`() = runBlocking {
        val s = services()
        s.bootstrap()
        val me = s.admin()
        s.profiles.put(me, Profile(timezone = zone.id))
        val days = s.profiles.days(me)
        suspend fun kept(date: LocalDate) = s.db.tx { one("SELECT 1 FROM day_cache WHERE user_id = ? AND date = ?", me, date.toString()) { true } } == true
        suspend fun keep(vararg dates: LocalDate) = dates.forEach { s.days.stats(me, it, it, days) }
        time = at(23)

        keep(today)
        assertTrue(kept(today))
        val saved = s.meditation.save(me, MeditationSession(at(21), 10.0, true))
        assertFalse("a new session", kept(today))

        // Moved to the day before: both days.
        keep(today, today.minusDays(1))
        s.meditation.save(me, MeditationSession(at(today.minusDays(1), 22), 10.0, true), saved.id)
        assertFalse(kept(today))
        assertFalse(kept(today.minusDays(1)))

        keep(today.minusDays(1))
        s.meditation.delete(me, saved.id!!)
        assertFalse("a deleted session", kept(today.minusDays(1)))
    }

    // ---------------------------------------------------------------- Sharing and AI

    @Test
    fun `a share sees sessions only with Mind shared and on, and never the settings`() {
        val s = services()
        serverTest(s) {
            val ownerId = s.admin()
            s.profiles.put(ownerId, Profile(timezone = zone.id))
            s.users.create("sam", "Sam", password = "sam-password-123")
            val owner = adminBrowser()
            owner.postJson("/api/meditations/sessions", session(at(10)))
            val viewer = browser().also { it.post("/api/auth/login") { json("""{"username":"sam","password":"sam-password-123"}""") } }
            suspend fun look(path: String) = viewer.get(path) { header(SharedView.HEADER, ownerId) }
            suspend fun feed() = ApiJson.decodeFromString(DayFeed.serializer(), look("/api/feed?date=$today").bodyAsText())

            // Shared without Mind: nothing of it.
            owner.postJson("/api/shares", """{"username":"sam","modules":["ACTIVITY"]}""")
            assertEquals(HttpStatusCode.Forbidden, look("/api/meditations/sessions").status)
            assertEquals(HttpStatusCode.Forbidden, look("/api/meditations/summary").status)
            assertTrue(feed().items.none { it.page == AppPage.MEDITATION })

            // Mind shared, but the owner hasn't Mind on: still nothing (and their own Today has it, untagged).
            owner.postJson("/api/shares", """{"username":"sam","modules":["ACTIVITY","MIND"]}""")
            assertEquals(HttpStatusCode.Forbidden, look("/api/meditations/sessions").status)
            assertTrue(feed().items.none { it.page == AppPage.MEDITATION })
            val mine = ApiJson.decodeFromString(DayFeed.serializer(), owner.get("/api/feed?date=$today").bodyAsText())
            assertNull(mine.items.single { it.page == AppPage.MEDITATION }.module)

            // With Mind on and shared: the sessions and the timeline, read-only; never the reminder setting.
            s.profiles.put(ownerId, Profile(timezone = zone.id, modules = Module.DEFAULT + Module.MIND))
            assertEquals(HttpStatusCode.OK, look("/api/meditations/sessions").status)
            assertEquals(1, ApiJson.decodeFromString(ListSerializer(MeditationSession.serializer()), look("/api/meditations/sessions").bodyAsText()).size)
            assertEquals(HttpStatusCode.OK, look("/api/meditations/summary").status)
            val item = feed().items.single { it.page == AppPage.MEDITATION }
            assertEquals(Module.MIND, item.module)
            assertEquals(HttpStatusCode.Forbidden, look("/api/meditations/settings").status)
            assertEquals(HttpStatusCode.Forbidden, viewer.post("/api/meditations/sessions") { csrf(); header(SharedView.HEADER, ownerId); json(session(at(11))) }.status)
            assertEquals(HttpStatusCode.Forbidden, viewer.delete("/api/meditations/sessions/${item.ref}") { csrf(); header(SharedView.HEADER, ownerId) }.status)
        }
    }

    @Test
    fun `meditation is never offered to AI`() {
        assertTrue(AiTools.MODULES.keys.none { "meditat" in it })
        runBlocking {
            val s = services()
            s.bootstrap()
            val user = s.users.byUsername("admin")!!
            s.profiles.put(user.id, Profile(timezone = zone.id, modules = Module.entries.toSet()))
            val tools = com.health.server.mcp.McpServer(s).handle(
                com.health.server.mcp.McpServer.Caller(user, canLog = true, scope = Module.entries.filter { it.sensitive }.toSet()),
                ApiJson.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") as kotlinx.serialization.json.JsonObject,
            ).toString()
            assertFalse(tools.contains("meditat", ignoreCase = true))
        }
    }

    // ---------------------------------------------------------------- Today and the reminder

    @Test
    fun `Today shows each finished session, and offers Wind down in the evening`() = runBlocking {
        val s = services()
        s.bootstrap()
        val me = s.admin()
        s.profiles.put(me, Profile(timezone = zone.id))
        time = at(20)
        s.meditation.save(me, MeditationSession(at(7), 10.0, true, "breathing-10"))
        s.meditation.save(me, MeditationSession(at(12), 2.0, false, "gratitude-5"))

        val feed = s.feed.feed(me, "today")
        val item = feed.items.single { it.page == AppPage.MEDITATION }
        assertEquals(TimelineKind.CALM, item.kind)
        assertEquals("Meditated 10 min", item.title)
        assertEquals("Slow breathing", item.detail)
        assertEquals(at(7) + 600_000, item.end)
        assertTrue("before the window", feed.now.none { it.kind == NowKind.WIND_DOWN })

        // In the window (from half an hour before 21:30), after meditating this week: the one last used.
        time = at(21, 10)
        val wind = s.feed.feed(me, "today").now.single { it.kind == NowKind.WIND_DOWN }
        assertEquals("Wind down", wind.title)
        assertEquals(NowAction("Start", NowAction.MEDITATION_OPEN, mapOf("meditationId" to "gratitude-5")), wind.action)
        assertEquals(AppPage.MEDITATION, wind.page)
        assertEquals("meditation:wind-down:$today", wind.id)
        // Just after midnight it's still the same evening's.
        time = at(today.plusDays(1), 0, 20)
        assertEquals("meditation:wind-down:$today", s.feed.feed(me, "today").now.single { it.kind == NowKind.WIND_DOWN }.id)

        // Done tonight: gone.
        time = at(22)
        s.meditation.save(me, MeditationSession(at(21, 45), 10.0, true, "wind-down-10"))
        assertTrue(s.feed.feed(me, "today").now.none { it.kind == NowKind.WIND_DOWN })

        // Nobody new is nudged: no sessions in the last week and no reminder, nothing.
        s.users.create("new", "New", password = "new-password-123")
        val other = s.users.byUsername("new")!!.id
        s.profiles.put(other, Profile(timezone = zone.id))
        time = at(21, 40)
        assertTrue(s.feed.feed(other, "today").now.none { it.kind == NowKind.WIND_DOWN })
        // With the reminder on, they are.
        s.meditation.saveSettings(other, MeditationSettings(true, "21:30"))
        assertEquals("wind-down-10", s.feed.feed(other, "today").now.single { it.kind == NowKind.WIND_DOWN }.action!!.ids["meditationId"])
    }

    @Test
    fun `the nightly reminder goes at the person's time, unless they've begun already`() = runBlocking {
        val s = services()
        s.bootstrap()
        val me = s.admin()
        s.profiles.put(me, Profile(timezone = zone.id))
        val profile = s.profiles.get(me)
        suspend fun due(now: Long) = s.platform.scheduler.collect(me, profile, now - 15 * 60_000, now + 60_000).filter { it.event == WebhookEvent.WIND_DOWN }

        assertTrue("off to start", due(at(21, 30)).isEmpty())
        s.meditation.saveSettings(me, MeditationSettings(true, "21:30"))
        val r = due(at(21, 30)).single()
        assertEquals("meditation:$today", r.key)
        assertEquals(at(21, 30), r.at)
        assertEquals("Time to wind down", r.privateTitle)
        assertTrue(r.push)
        assertTrue(due(at(20)).isEmpty())
        // Already meditating: no nudge.
        time = at(21, 30)
        s.meditation.save(me, MeditationSession(at(21, 15), 10.0, false))
        assertTrue(due(at(21, 30)).isEmpty())
    }

    // ---------------------------------------------------------------- Nights after

    @Test
    fun `nights after meditating are set beside nights without, only with enough of each`() {
        val start = LocalDate.of(2026, 3, 1)
        val nights = (0 until 12).map { i -> MeditationService.Night(start.plusDays(i.toLong()), if (i % 2 == 0) 84 else 76, if (i % 2 == 0) 450 else 400) }
        // Meditated the evening before each even night.
        val evenings = nights.filter { it.score == 84 }.map { it.date.minusDays(1) }.toSet()
        val compared = MeditationService.compare(nights, evenings)!!
        assertEquals(6, compared.nightsAfter)
        assertEquals(6, compared.nightsWithout)
        assertEquals(84, compared.scoreAfter)
        assertEquals(76, compared.scoreWithout)
        assertEquals(450, compared.asleepAfter)
        val insight = MeditationService.insight(compared, 30)
        assertTrue(insight.body.contains("not proof"))
        assertFalse(Regex("\\b(because|improves?|helps?|better sleep)\\b", RegexOption.IGNORE_CASE).containsMatchIn(insight.body + insight.title))
        // Four of one kind isn't enough.
        assertNull(MeditationService.compare(nights.take(9), evenings))
        // About the same is said as about the same.
        val same = MeditationService.compare(nights.map { it.copy(score = 80, asleep = 420) }, evenings)!!
        assertTrue(MeditationService.insight(same, 30).body.contains("about the same"))
    }

    @Test
    fun `an evening's meditation goes with the night after it`() = runBlocking {
        val s = services()
        s.bootstrap()
        val me = s.admin()
        s.profiles.put(me, Profile(timezone = zone.id))
        val days = s.profiles.days(me)
        assertEquals(today, MeditationService.eveningOf(MeditationSession(at(22), 10.0), days))
        assertEquals(today.minusDays(1), MeditationService.eveningOf(MeditationSession(at(1), 10.0), days))
        assertNull(MeditationService.eveningOf(MeditationSession(at(9), 10.0), days))

        // Ten nights: meditated before five of them, slept longer after those.
        time = at(15)
        (1..10).forEach { i ->
            val morning = today.minusDays(i.toLong())
            val meditated = i % 2 == 0
            if (meditated) s.meditation.save(me, MeditationSession(at(morning.minusDays(1), 22), 10.0, true, "wind-down-10"))
            s.ingest.manualSleep(me, SleepSession(at(morning.minusDays(1), 23), at(morning, if (meditated) 7 else 6)))
        }
        val summary = ApiJson.decodeFromString(MeditationSummary.serializer(), ApiJson.encodeToString(MeditationSummary.serializer(), s.meditation.summary(me, 30)))
        assertEquals(5, summary.sessions)
        assertEquals(50.0, summary.minutes, 0.01)
        assertEquals("wind-down-10", summary.lastMeditationId)
        val nights = summary.nights!!
        assertEquals(5, nights.nightsAfter)
        assertEquals(5, nights.nightsWithout)
        assertTrue(nights.asleepAfter!! > nights.asleepWithout!!)
        assertEquals("Sleep after meditating", summary.insights.single().title)
    }
}
