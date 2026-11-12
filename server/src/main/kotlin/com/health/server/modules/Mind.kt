package com.health.server.modules

import com.health.api.Count
import com.health.api.CrisisResource
import com.health.api.Insight
import com.health.api.JournalEntry
import com.health.api.JournalKind
import com.health.api.MindDay
import com.health.api.MindSummary
import com.health.api.MindfulnessSession
import com.health.api.MoodEntry
import com.health.api.ScoreBand
import com.health.api.Screener
import com.health.api.ScreenerDefinition
import com.health.api.ScreenerResult
import com.health.server.ApiError
import com.health.server.SettingsStore
import com.health.server.health.ProfileStore
import com.health.server.insights.DayService
import com.health.server.insights.DayStats
import java.time.LocalDate
import kotlin.math.roundToInt

/** Mood, the journal, screeners, calming practice, and where to turn for support. */
class MindService(
    private val entries: EntryStore,
    private val profiles: ProfileStore,
    private val settings: SettingsStore,
    private val days: DayService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // ---- Moods ----

    suspend fun moods(userId: String, count: Int): List<MoodEntry> = range(userId, count) { from, to ->
        entries.list(userId, EntryKind.MOOD, from, to, MoodEntry.serializer()).map { it.value.copy(id = it.id, date = it.date.toString()) }
    }.reversed()

    suspend fun saveMood(userId: String, mood: MoodEntry, id: String? = null): MoodEntry {
        check(mood.mood in 1..5) { "Mood is from 1 to 5" }
        mood.energy?.let { check(it in 1..5) { "Energy is from 1 to 5" } }
        mood.anxiety?.let { check(it in 1..5) { "Anxiety is from 1 to 5" } }
        val clean = mood.copy(
            feelings = tags(mood.feelings), context = tags(mood.context), note = mood.note?.trim()?.take(4000)?.ifBlank { null },
            id = null, date = null,
        )
        val date = profiles.days(userId).dateOf(clean.at)
        val saved = entries.put(userId, EntryKind.MOOD, id, date, clean.at, clean, MoodEntry.serializer())
        return clean.copy(id = saved, date = date.toString())
    }

    // ---- Journal ----

    suspend fun journal(userId: String, count: Int): List<JournalEntry> = range(userId, count) { from, to ->
        entries.list(userId, EntryKind.JOURNAL, from, to, JournalEntry.serializer()).map { it.value.copy(id = it.id, date = it.date.toString()) }
    }.reversed()

    suspend fun saveJournal(userId: String, entry: JournalEntry, id: String? = null): JournalEntry {
        val clean = entry.copy(
            title = entry.title?.trim()?.take(200)?.ifBlank { null },
            text = entry.text.take(50_000),
            prompt = entry.prompt?.take(300),
            gratitude = entry.gratitude.map { it.trim().take(500) }.filter { it.isNotEmpty() }.take(10),
            thought = entry.thought?.let { t ->
                t.copy(
                    beliefBefore = t.beliefBefore?.coerceIn(0, 100), beliefAfter = t.beliefAfter?.coerceIn(0, 100),
                )
            }.takeIf { entry.kind == JournalKind.THOUGHT_RECORD },
            id = null, date = null,
        )
        if (clean.text.isBlank() && clean.gratitude.isEmpty() && clean.thought == null) throw ApiError.badRequest("Write something first")
        val date = profiles.days(userId).dateOf(clean.at)
        val saved = entries.put(userId, EntryKind.JOURNAL, id, date, clean.at, clean, JournalEntry.serializer())
        return clean.copy(id = saved, date = date.toString())
    }

    // ---- Screeners ----

    suspend fun screenerResults(userId: String, count: Int): List<ScreenerResult> = range(userId, count) { from, to ->
        entries.list(userId, EntryKind.SCREENER, from, to, ScreenerResult.serializer()).map { it.value.copy(id = it.id, date = it.date.toString()) }
    }.reversed()

    suspend fun submit(userId: String, result: ScreenerResult): ScreenerResult {
        val scored = score(result)
        val date = profiles.days(userId).dateOf(scored.at)
        val id = entries.put(userId, EntryKind.SCREENER, null, date, scored.at, scored, ScreenerResult.serializer())
        return scored.copy(id = id, date = date.toString())
    }

    // ---- Calm practice ----

    suspend fun sessions(userId: String, count: Int): List<MindfulnessSession> = range(userId, count) { from, to ->
        entries.list(userId, EntryKind.MINDFUL, from, to, MindfulnessSession.serializer()).map { it.value.copy(id = it.id, date = it.date.toString()) }
    }.reversed()

    suspend fun saveSession(userId: String, session: MindfulnessSession): MindfulnessSession {
        check(session.minutes > 0 && session.minutes <= 600) { "A session lasts up to 10 hours" }
        val clean = session.copy(kind = session.kind.trim().take(60).ifBlank { "meditation" }, note = session.note?.trim()?.take(2000)?.ifBlank { null }, id = null, date = null)
        val date = profiles.days(userId).dateOf(clean.at)
        val id = entries.put(userId, EntryKind.MINDFUL, null, date, clean.at, clean, MindfulnessSession.serializer())
        return clean.copy(id = id, date = date.toString())
    }

    suspend fun delete(userId: String, kind: EntryKind, id: String) {
        entries.delete(userId, kind, id)
    }

    suspend fun resources(): List<CrisisResource> = settings.get().crisisResources

    // ---- Summary ----

    /** Each day's moods and practice, from [from] to [to]. */
    suspend fun daysOf(userId: String, from: LocalDate, to: LocalDate): Map<LocalDate, MindDay> {
        val moods = entries.list(userId, EntryKind.MOOD, from, to, MoodEntry.serializer()).groupBy { it.date }
        val sessions = entries.list(userId, EntryKind.MINDFUL, from, to, MindfulnessSession.serializer()).groupBy { it.date }
        val journal = entries.list(userId, EntryKind.JOURNAL, from, to, JournalEntry.serializer()).groupBy { it.date }
        return (moods.keys + sessions.keys + journal.keys).associateWith { date ->
            val m = moods[date].orEmpty().map { it.value }
            MindDay(
                date = date.toString(),
                mood = m.map { it.mood.toDouble() }.averageOrNull(),
                energy = m.mapNotNull { it.energy?.toDouble() }.averageOrNull(),
                anxiety = m.mapNotNull { it.anxiety?.toDouble() }.averageOrNull(),
                entries = m.size,
                mindfulMinutes = sessions[date].orEmpty().sumOf { it.value.minutes },
                journal = journal[date].orEmpty().size,
            )
        }
    }

    suspend fun summary(userId: String, count: Int): MindSummary {
        val zone = profiles.days(userId)
        val to = zone.today(clock())
        val from = to.minusDays((count.coerceIn(1, 730) - 1).toLong())
        val byDay = daysOf(userId, from, to)
        val list = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { byDay[it] ?: MindDay(it.toString()) }.toList()
        val moods = entries.list(userId, EntryKind.MOOD, from, to, MoodEntry.serializer()).map { it.value }
        val stats = days.stats(userId, from, to, zone).associateBy { LocalDate.parse(it.date) }
        val goals = profiles.get(userId).goals
        val latest = screenerResults(userId, 365).groupBy { it.screener }.mapNotNull { (_, results) -> results.firstOrNull() }
        return MindSummary(
            days = list,
            averageMood = moods.map { it.mood.toDouble() }.averageOrNull(),
            feelings = counts(moods.flatMap { it.feelings }),
            context = counts(moods.flatMap { it.context }),
            patterns = patterns(list, stats, goals.steps),
            latest = latest,
            mindfulMinutes = list.sumOf { it.mindfulMinutes },
        )
    }

    private suspend fun <T> range(userId: String, count: Int, read: suspend (LocalDate, LocalDate) -> List<T>): List<T> {
        val zone = profiles.days(userId)
        val to = zone.today(clock()).plusDays(1)
        return read(to.minusDays(count.coerceIn(1, 3650).toLong()), to)
    }

    private fun check(ok: Boolean, message: () -> String) {
        if (!ok) throw ApiError.badRequest(message())
    }

    companion object {
        val PHQ9 = ScreenerDefinition(
            id = Screener.PHQ9,
            title = "Depression check-in (PHQ-9)",
            about = "Nine questions doctors use to check for depression and how strong it is. It's not a diagnosis; it's a way to notice changes and talk about them.",
            question = "Over the last 2 weeks, how often have you been bothered by any of the following problems?",
            items = listOf(
                "Little interest or pleasure in doing things",
                "Feeling down, depressed, or hopeless",
                "Trouble falling or staying asleep, or sleeping too much",
                "Feeling tired or having little energy",
                "Poor appetite or overeating",
                "Feeling bad about yourself, or that you are a failure or have let yourself or your family down",
                "Trouble concentrating on things, such as reading the newspaper or watching television",
                "Moving or speaking so slowly that other people could have noticed, or the opposite: being so fidgety or restless that you have been moving around a lot more than usual",
                "Thoughts that you would be better off dead, or of hurting yourself",
            ),
            options = FREQUENCY,
            bands = listOf(ScoreBand(0, "Minimal"), ScoreBand(5, "Mild"), ScoreBand(10, "Moderate"), ScoreBand(15, "Moderately severe"), ScoreBand(20, "Severe")),
            source = "Patient Health Questionnaire (PHQ-9), Kroenke, Spitzer & Williams, 2001. Free to use.",
        )

        val GAD7 = ScreenerDefinition(
            id = Screener.GAD7,
            title = "Anxiety check-in (GAD-7)",
            about = "Seven questions doctors use to check for anxiety and how strong it is. It's not a diagnosis; it's a way to notice changes and talk about them.",
            question = "Over the last 2 weeks, how often have you been bothered by the following problems?",
            items = listOf(
                "Feeling nervous, anxious, or on edge",
                "Not being able to stop or control worrying",
                "Worrying too much about different things",
                "Trouble relaxing",
                "Being so restless that it's hard to sit still",
                "Becoming easily annoyed or irritable",
                "Feeling afraid, as if something awful might happen",
            ),
            options = FREQUENCY,
            bands = listOf(ScoreBand(0, "Minimal"), ScoreBand(5, "Mild"), ScoreBand(10, "Moderate"), ScoreBand(15, "Severe")),
            source = "Generalized Anxiety Disorder 7 (GAD-7), Spitzer, Kroenke, Williams & Löwe, 2006. Free to use.",
        )

        val SCREENERS = listOf(PHQ9, GAD7)

        private val FREQUENCY get() = listOf("Not at all", "Several days", "More than half the days", "Nearly every day")

        /** Scores a screener's answers: the sum, its band, and (PHQ-9) whether the last question needs support shown. */
        fun score(result: ScreenerResult): ScreenerResult {
            val definition = SCREENERS.first { it.id == result.screener }
            if (result.answers.size != definition.items.size || result.answers.any { it !in 0..3 }) {
                throw ApiError.badRequest("Answer each of the ${definition.items.size} questions with 0 to 3")
            }
            val score = result.answers.sum()
            val band = definition.bands.last { score >= it.from }.label
            val flagged = result.screener == Screener.PHQ9 && result.answers.last() > 0
            return result.copy(score = score, severity = band, flagged = flagged, id = null, date = null)
        }

        /** The crisis lines the server starts with; admins change them for where their people are. */
        val DEFAULT_RESOURCES = listOf(
            CrisisResource("988 Suicide & Crisis Lifeline", "Call or text 988", "United States", "https://988lifeline.org"),
            CrisisResource("Trans Lifeline", "Call 877-565-8860 (US) or 877-330-6366 (Canada)", "United States and Canada", "https://translifeline.org", "Peer support run by and for trans people"),
            CrisisResource("Crisis Text Line", "Text HOME to 741741", "United States", "https://www.crisistextline.org"),
            CrisisResource("9-8-8 Suicide Crisis Helpline", "Call or text 988", "Canada", "https://988.ca"),
            CrisisResource("Samaritans", "Call 116 123", "United Kingdom and Ireland", "https://www.samaritans.org"),
            CrisisResource("Lifeline", "Call 13 11 14", "Australia", "https://www.lifeline.org.au"),
            CrisisResource("Find a Helpline", "Free, confidential support in many countries", "Everywhere else", "https://findahelpline.com"),
        )

        fun counts(values: List<String>): List<Count> =
            values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12).map { Count(it.key, it.value) }

        private fun tags(values: List<String>) = values.map { it.trim().lowercase().take(40) }.filter { it.isNotEmpty() }.distinct().take(20)

        fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else (average() * 100).roundToInt() / 100.0

        /**
         * What seems to go with better days: mood on days with more sleep, a workout, enough steps
         * or calm practice, against days without. Only said when both sides have a few days and
         * the gap is worth mentioning.
         */
        fun patterns(days: List<MindDay>, stats: Map<LocalDate, DayStats>, stepGoal: Int): List<Insight> {
            val rated = days.filter { it.mood != null }
            fun compare(label: String, otherwise: String, icon: String, test: (MindDay, DayStats?) -> Boolean?): Insight? {
                val split = rated.mapNotNull { d -> test(d, stats[LocalDate.parse(d.date)])?.let { it to d.mood!! } }
                val yes = split.filter { it.first }.map { it.second }
                val no = split.filter { !it.first }.map { it.second }
                if (yes.size < 4 || no.size < 4) return null
                val gap = yes.average() - no.average()
                if (kotlin.math.abs(gap) < 0.3) return null
                val better = gap > 0
                return Insight(
                    if (better) "good" else "info",
                    if (better) "Your mood is better $label" else "Your mood is lower $label",
                    "Averaging %.1f, against %.1f %s.".format(yes.average(), no.average(), otherwise),
                    icon,
                )
            }
            return listOfNotNull(
                compare("after 7 hours of sleep or more", "after shorter nights", "moon") { d, s ->
                    s?.sleep?.asleepMinutes?.let { it >= 420 }.takeIf { d.mood != null }
                },
                compare("on days you work out", "on days you don't", "dumbbell") { _, s -> s?.let { it.workouts > 0 } },
                compare("on days you reach your step goal", "on days you don't", "footprints") { _, s -> s?.steps?.let { it >= stepGoal } },
                compare("on days with a calm practice", "on days without", "sparkles") { d, _ -> d.mindfulMinutes > 0 },
            )
        }
    }
}
