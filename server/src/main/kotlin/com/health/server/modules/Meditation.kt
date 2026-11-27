package com.health.server.modules

import com.health.api.AppPage
import com.health.api.DigestSection
import com.health.api.Insight
import com.health.api.Meditation
import com.health.api.MeditationNights
import com.health.api.MeditationSession
import com.health.api.MeditationSettings
import com.health.api.MeditationSummary
import com.health.api.Module
import com.health.api.NowAction
import com.health.api.NowItem
import com.health.api.NowKind
import com.health.api.Profile
import com.health.api.ReportSectionKey
import com.health.api.TimelineItem
import com.health.api.TimelineKind
import com.health.api.WebhookEvent
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.auth.requireUser
import com.health.server.care.viewScope
import com.health.server.health.ProfileStore
import com.health.server.insights.DayService
import com.health.server.insights.Scoring
import com.health.server.platform.Csv
import com.health.server.platform.FeatureHooks
import com.health.server.platform.Feed
import com.health.server.platform.Reminder
import com.health.server.platform.ReportSection
import com.health.server.platform.Say
import com.health.server.util.Days
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Guided meditation: the scripts ([Meditations]), the sessions people log (sealed entries), the
 * nightly reminder (a sealed setting), and how nights after meditating compare with nights without.
 *
 * It's there for everyone, whatever features are on. For anyone else it counts as part of Mind: a
 * share sees it only when it includes Mind (and Mind is on), and it's never offered to AI (no
 * connector tool, nothing in [com.health.server.ai.AiTools]). Nothing here leaves the server: the
 * apps read the scripts aloud with the device's own text-to-speech.
 */
class MeditationService(
    private val entries: EntryStore,
    private val settingsStore: UserSettingsStore,
    private val profiles: ProfileStore,
    private val days: DayService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun catalog(): List<Meditation> = Meditations.ALL

    /** The last [count] days' sessions (today included), newest first. */
    suspend fun sessions(userId: String, count: Int): List<MeditationSession> {
        val zone = profiles.days(userId)
        val to = zone.today(clock())
        return between(userId, to.minusDays((count.coerceIn(1, 3650) - 1).toLong()), to).reversed()
    }

    /** Sessions begun on the days [from]..[to], oldest first, with their meditation's name. */
    suspend fun between(userId: String, from: LocalDate, to: LocalDate): List<MeditationSession> =
        entries.list(userId, EntryKind.MEDITATION, from, to, MeditationSession.serializer()).map { named(it.value, it.id, it.date) }

    /** Logs a session (or changes session [id], the person's own). The day it's on is worked out again. */
    suspend fun save(userId: String, session: MeditationSession, id: String? = null): MeditationSession {
        if (!session.minutes.isFinite() || session.minutes <= 0 || session.minutes > 600) throw ApiError.badRequest("A session lasts more than 0 and up to 600 minutes")
        if (session.startedAt <= 0 || session.startedAt > clock() + 10 * 60_000L) throw ApiError.badRequest("A session can't begin in the future")
        session.feltAfter?.let { if (it !in 1..5) throw ApiError.badRequest("How it felt is from 1 to 5") }
        if (session.meditationId != null && Meditations.byId(session.meditationId) == null) throw ApiError.badRequest("That isn't one of this server's meditations")
        val clean = session.copy(id = null, date = null, title = null, kind = null, minutes = (session.minutes * 100).roundToInt() / 100.0)
        val zone = profiles.days(userId)
        val date = zone.dateOf(clean.startedAt)
        val before = id?.let { entries.get(userId, EntryKind.MEDITATION, it, MeditationSession.serializer()) ?: throw ApiError.notFound("No such session") }
        val saved = entries.put(userId, EntryKind.MEDITATION, id, date, clean.startedAt, clean, MeditationSession.serializer())
        days.changedDate(userId, date)
        if (before != null && before.date != date) days.changedDate(userId, before.date)
        return named(clean, saved, date)
    }

    suspend fun delete(userId: String, id: String) {
        val date = entries.delete(userId, EntryKind.MEDITATION, id)
        days.changedDate(userId, date)
    }

    suspend fun settings(userId: String): MeditationSettings =
        settingsStore.get(userId, SETTINGS_KEY, MeditationSettings.serializer()) ?: MeditationSettings()

    suspend fun saveSettings(userId: String, input: MeditationSettings): MeditationSettings {
        val time = runCatching { LocalTime.parse(input.time.trim(), H_MM) }.getOrNull() ?: throw ApiError.badRequest("A time looks like 21:30")
        return settingsStore.put(userId, SETTINGS_KEY, MeditationSettings(input.reminder, time.format(HH_MM)), MeditationSettings.serializer())
    }

    /** The last [count] days: how much, the meditation used last, and sleep after meditating against without. */
    suspend fun summary(userId: String, count: Int): MeditationSummary {
        val n = count.coerceIn(1, 365)
        val profile = profiles.get(userId)
        val zone = Days(Days.zoneOf(profile.timezone))
        val today = zone.today(clock())
        val from = today.minusDays((n - 1).toLong())
        // The evening before the first night counts too.
        val all = between(userId, from.minusDays(1), today)
        val inPeriod = all.filter { LocalDate.parse(it.date!!) >= from }
        val week = today.minusDays(6)
        val evenings = all.mapNotNull { eveningOf(it, zone) }.toSet()
        val stats = days.stats(userId, from, today, zone).associateBy { LocalDate.parse(it.date) }
        val nights = stats.mapNotNull { (date, s) ->
            s.sleep?.let { night -> Night(date, Scoring.sleep(night, profile.goals, zone.zone)?.score, night.asleepMinutes) }
        }
        val compared = compare(nights, evenings)
        return MeditationSummary(
            days = n,
            sessions = inPeriod.size,
            finished = inPeriod.count { it.finished },
            minutes = round(inPeriod.sumOf { it.minutes }),
            weekMinutes = round(inPeriod.filter { LocalDate.parse(it.date!!) >= week }.sumOf { it.minutes }),
            daysMeditated = inPeriod.map { it.date }.distinct().size,
            lastMeditationId = inPeriod.lastOrNull { it.meditationId != null }?.meditationId,
            nights = compared,
            insights = listOfNotNull(compared?.let { insight(it, n) }),
        )
    }

    /** Whether "Wind down" shows tonight: with the reminder on, or after meditating in the last week. */
    suspend fun offersWindDown(userId: String, settings: MeditationSettings, today: LocalDate): Boolean =
        settings.reminder || between(userId, today.minusDays(7), today).isNotEmpty()

    private fun named(s: MeditationSession, id: String, date: LocalDate): MeditationSession {
        val m = Meditations.byId(s.meditationId)
        return s.copy(id = id, date = date.toString(), title = m?.title, kind = m?.kind)
    }

    /** A night's numbers: [date] is the morning it ended. */
    data class Night(val date: LocalDate, val score: Int?, val asleep: Int)

    companion object {
        const val SETTINGS_KEY = "meditation.settings"

        /** Nights of each kind needed before anything is said. */
        const val ENOUGH = 5

        private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val H_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

        private fun round(v: Double) = (v * 10).roundToInt() / 10.0

        /**
         * The evening a session belongs to, for the night after it: one begun from 5 pm to before
         * 4 am (that's the evening before). Daytime sessions have none.
         */
        fun eveningOf(s: MeditationSession, zone: Days): LocalDate? {
            val date = zone.dateOf(s.startedAt)
            val hour = zone.hourOf(s.startedAt)
            return when {
                hour >= 17 -> date
                hour < 4 -> date.minusDays(1)
                else -> null
            }
        }

        /**
         * Nights after an evening with meditation ([evenings]), against the other nights: only with
         * [ENOUGH] of each that have a sleep score. Pure, for tests.
         */
        fun compare(nights: List<Night>, evenings: Set<LocalDate>): MeditationNights? {
            val (after, without) = nights.partition { it.date.minusDays(1) in evenings }
            val scoredAfter = after.mapNotNull { it.score }
            val scoredWithout = without.mapNotNull { it.score }
            if (scoredAfter.size < ENOUGH || scoredWithout.size < ENOUGH) return null
            return MeditationNights(
                nightsAfter = after.size,
                nightsWithout = without.size,
                scoreAfter = scoredAfter.average().roundToInt(),
                scoreWithout = scoredWithout.average().roundToInt(),
                asleepAfter = after.map { it.asleep }.average().roundToInt(),
                asleepWithout = without.map { it.asleep }.average().roundToInt(),
            )
        }

        /** What happened, plainly: never that meditating did it. */
        fun insight(n: MeditationNights, days: Int): Insight {
            val a = n.scoreAfter!!
            val b = n.scoreWithout!!
            val sameScore = abs(a - b) <= 2
            val sameSleep = abs((n.asleepAfter ?: 0) - (n.asleepWithout ?: 0)) <= 10
            val body = if (sameScore && sameSleep) {
                "Over the last $days days, your sleep was about the same on the ${Say.plural(n.nightsAfter, "night")} after you meditated " +
                    "(a sleep score of $a, ${Say.minutes(n.asleepAfter)} asleep) as on the ${Say.plural(n.nightsWithout, "other night")} ($b, ${Say.minutes(n.asleepWithout)})."
            } else {
                "Over the last $days days, on the ${Say.plural(n.nightsAfter, "night")} after you meditated, your sleep score averaged $a " +
                    "and you slept ${Say.minutes(n.asleepAfter)}. On the ${Say.plural(n.nightsWithout, "other night")}, it was $b and ${Say.minutes(n.asleepWithout)}. " +
                    "That's what happened, not proof of what made the difference."
            }
            return Insight("info", "Sleep after meditating", body, "moon", Module.MIND, link = AppPage.MEDITATION)
        }

        /** "Meditated 10 min". */
        fun done(minutes: Double) = "Meditated ${Feed.duration(minutes.roundToInt().coerceAtLeast(1))}"
    }
}

/**
 * Meditation's part in Today, the digest, the report, the export and reminders. Everyone's (no
 * [module]); what it adds belongs to Mind only while Mind is on, so a share that includes Mind can
 * see it, and nothing untagged is ever shared.
 */
class MeditationHooks(private val meditation: MeditationService, private val clock: () -> Long) : FeatureHooks {
    override val module: Module? = null

    private fun tag(profile: Profile): Module? = Module.MIND.takeIf { it in profile.modules }

    private fun zoneOf(profile: Profile) = Days(Days.zoneOf(profile.timezone))

    /** Each finished session: "Meditated 10 min · Wind down". */
    override suspend fun timeline(userId: String, profile: Profile, from: LocalDate, to: LocalDate): List<TimelineItem> =
        meditation.between(userId, from, to).filter { it.finished }.map { s ->
            TimelineItem(
                Feed.id(TimelineKind.CALM, "meditation:${s.id}"), TimelineKind.CALM, s.startedAt, end = s.startedAt + (s.minutes * 60_000).toLong(),
                title = MeditationService.done(s.minutes), detail = s.title ?: "Meditation",
                module = tag(profile), page = AppPage.MEDITATION, ref = s.id,
            )
        }

    /**
     * "Wind down", in the evening from half an hour before the person's wind-down time to three
     * hours after: with the reminder on, or after meditating in the last week. Gone once a
     * meditation's been done tonight.
     */
    override suspend fun now(userId: String, profile: Profile, at: Long): List<NowItem> {
        val zone = zoneOf(profile)
        val settings = meditation.settings(userId)
        val start = windowStart(settings, zone, at) ?: return emptyList()
        val evening = zone.dateOf(start + WINDOW_BEFORE)
        if (!meditation.offersWindDown(userId, settings, evening)) return emptyList()
        val recent = meditation.between(userId, zone.dateOf(start).minusDays(7), zone.dateOf(at))
        if (recent.any { it.startedAt >= start && it.startedAt <= at }) return emptyList()
        val suggested = Meditations.byId(recent.lastOrNull { it.meditationId != null }?.meditationId) ?: Meditations.byId(DEFAULT)!!
        return listOf(
            NowItem(
                id = "meditation:wind-down:$evening",
                kind = NowKind.WIND_DOWN,
                title = "Wind down",
                detail = Feed.line("Your wind-down time", "${suggested.title}, ${suggested.minutes} min"),
                page = AppPage.MEDITATION,
                action = NowAction("Start", NowAction.MEDITATION_OPEN, mapOf("meditationId" to suggested.id)),
                priority = 30,
                at = start + WINDOW_BEFORE,
            ),
        )
    }

    /** The nightly reminder, at the person's time, unless they've already begun one this evening. */
    override suspend fun reminders(userId: String, profile: Profile, from: Long, to: Long): List<Reminder> {
        val settings = meditation.settings(userId)
        if (!settings.reminder) return emptyList()
        val time = runCatching { LocalTime.parse(settings.time) }.getOrNull() ?: return emptyList()
        val zone = zoneOf(profile)
        val today = zone.dateOf(to)
        // By the clock on the day (or the day before, just after midnight).
        val at = listOf(zone.at(today, time), zone.at(today.minusDays(1), time)).firstOrNull { it in from until to } ?: return emptyList()
        val date = zone.dateOf(at)
        if (meditation.between(userId, date, date).any { it.startedAt >= at - WINDOW_BEFORE }) return emptyList()
        return listOf(
            Reminder(
                "meditation:$date", at, WebhookEvent.WIND_DOWN,
                "Time to wind down", "A few quiet minutes before sleep, whenever you're ready.",
                "Time to wind down", "Whenever you're ready.", "/meditation",
            ),
        )
    }

    /** Minutes this week, against the week before. Mind's, for email: left out unless Mind is listed for it. */
    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val week = meditation.between(userId, from, to)
        if (week.isEmpty()) return null
        val before = meditation.between(userId, from.minusDays(7), to.minusDays(7))
        val minutes = week.sumOf { it.minutes }
        val change = Say.change(minutes, before.sumOf { it.minutes }.takeIf { before.isNotEmpty() }, { Say.minutes(it) }, 5.0)
        val days = week.map { it.date }.distinct().size
        return DigestSection(
            "Meditation", Module.MIND, "good",
            listOf("${Say.minutes(minutes)} of meditation on ${Say.plural(days, "day")}" + (change?.let { ", $it" } ?: "") + "."),
        )
    }

    /** A line for the report: with Mind when that's on, otherwise in the overview. */
    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection? {
        val list = meditation.between(userId, from, to)
        if (list.isEmpty()) return null
        val line = "Meditated on ${Say.plural(list.map { it.date }.distinct().size, "day")}, ${Say.minutes(list.sumOf { it.minutes })} in all."
        return ReportSection(if (Module.MIND in profile.modules) ReportSectionKey.MIND else ReportSectionKey.OVERVIEW, listOf(line))
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val zone = zoneOf(profile).zone
        val list = meditation.between(userId, from, to)
        if (list.isEmpty()) return emptyMap()
        return mapOf(
            "meditation.csv" to listOf(listOf("started_at", "meditation", "minutes", "finished", "felt_after")) + list.map {
                listOf(Csv.iso(it.startedAt, zone), it.meditationId.orEmpty(), Csv.num(it.minutes, 1), it.finished.toString(), it.feltAfter?.toString().orEmpty())
            },
        )
    }

    companion object {
        /** "Wind down" shows from this long before the person's time… */
        const val WINDOW_BEFORE = 30 * 60_000L

        /** …to this long after. */
        const val WINDOW_AFTER = 3 * 3_600_000L

        /** What's offered before anything's been done. */
        const val DEFAULT = "wind-down-10"

        /** When tonight's window began, if [at] is in it (it may have begun yesterday, before midnight). */
        fun windowStart(settings: MeditationSettings, zone: Days, at: Long): Long? {
            val time = runCatching { LocalTime.parse(settings.time) }.getOrNull() ?: return null
            val today = zone.dateOf(at)
            return listOf(today, today.minusDays(1)).map { zone.at(it, time) - WINDOW_BEFORE }
                .firstOrNull { at >= it && at < it + WINDOW_BEFORE + WINDOW_AFTER }
        }
    }
}

/**
 * Meditation's API (docs/API.md, "Meditation"). Through a share, the sessions and summary need Mind
 * shared (see [com.health.server.care.SharedView]); the settings are never shared.
 */
fun Route.meditationRoutes(services: Services) {
    val m = services.meditation
    fun ApplicationCall.days(default: Int) = request.queryParameters["days"]?.toIntOrNull() ?: default
    fun ApplicationCall.id() = parameters["id"] ?: throw ApiError.notFound()

    /** The person's own, or a viewer's with Mind shared (SharedView already asks for it; this says so here too). */
    suspend fun ApplicationCall.owner(): String {
        val user = requireUser(services)
        viewScope?.let { if (Module.MIND !in it.modules) throw ApiError.forbidden("That isn't shared with you") }
        return user.id
    }

    route("/api/meditations") {
        get { call.owner(); call.respond(m.catalog()) }
        route("/sessions") {
            get { call.respond(m.sessions(call.owner(), call.days(30))) }
            post { call.respond(m.save(call.requireUser(services).id, call.receive<MeditationSession>())) }
            put("/{id}") { call.respond(m.save(call.requireUser(services).id, call.receive<MeditationSession>(), call.id())) }
            delete("/{id}") { m.delete(call.requireUser(services).id, call.id()); call.respond(mapOf("ok" to true)) }
        }
        get("/summary") { call.respond(m.summary(call.owner(), call.days(30))) }
        get("/settings") { call.respond(m.settings(call.requireUser(services).id)) }
        put("/settings") { call.respond(m.saveSettings(call.requireUser(services).id, call.receive<MeditationSettings>())) }
    }
}
