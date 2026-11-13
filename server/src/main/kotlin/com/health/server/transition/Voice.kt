package com.health.server.transition

import com.health.api.ExerciseResult
import com.health.api.Profile
import com.health.api.ReminderAction
import com.health.api.VoiceCatalog
import com.health.api.VoicePlan
import com.health.api.VoiceProgress
import com.health.api.VoiceSession
import com.health.api.VoiceWeek
import com.health.api.WebhookEvent
import com.health.server.ApiError
import com.health.server.health.ProfileStore
import com.health.server.modules.EntryKind
import com.health.server.modules.EntryStore
import com.health.server.modules.UserSettingsStore
import com.health.server.neuro.plural
import com.health.server.platform.Reminder
import com.health.server.platform.Say
import com.health.server.util.Days
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** A voice session and the day it belongs to (in the person's time zone). */
data class DatedSession(val date: LocalDate, val session: VoiceSession)

/**
 * The voice trainer's server side (with Module.TRANSITION): the person's plan (sealed in their
 * settings), checking sessions against the programme (VoiceCatalog), progress, and the practice
 * reminder. Sound never reaches the server: only the numbers the apps measured.
 */
class VoiceTrainer(
    private val entries: EntryStore,
    private val personal: UserSettingsStore,
    private val profiles: ProfileStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun plan(userId: String): VoicePlan = personal.get(userId, PLAN, VoicePlan.serializer()) ?: VoicePlan()

    suspend fun savePlan(userId: String, plan: VoicePlan): VoicePlan = personal.put(userId, PLAN, checkPlan(plan, clock()), VoicePlan.serializer())

    /** Sessions from [from] to [to] (all of them by default), newest first. */
    suspend fun sessions(userId: String, from: LocalDate = EVER, to: LocalDate = FOREVER): List<DatedSession> =
        entries.list(userId, EntryKind.TRANSITION_VOICE, from, to, VoiceSession.serializer())
            .map { DatedSession(it.date, it.value.copy(id = it.id)) }.sortedByDescending { it.session.at }

    suspend fun progress(userId: String): VoiceProgress = progress(plan(userId), sessions(userId), profiles.days(userId).today(clock()))

    /**
     * A gentle nudge at the plan's time: not on rest days, and not once the day's practice is done.
     * What shows on a locked screen doesn't say what it's for.
     */
    suspend fun reminders(userId: String, profile: Profile, from: Long, to: Long): List<Reminder> {
        val plan = plan(userId)
        val time = plan.reminder?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return emptyList()
        val zone = Days(Days.zoneOf(profile.timezone))
        val out = mutableListOf<Reminder>()
        var date = zone.dateOf(from)
        while (!date.isAfter(zone.dateOf(to))) {
            val at = date.atTime(time).atZone(zone.zone).toInstant().toEpochMilli()
            if (at in from until to && date.dayOfWeek.value !in plan.restDays) {
                val near = sessions(userId, date.minusDays(1), date)
                if (near.none { it.date == date }) {
                    val gently = near.any { it.date == date.minusDays(1) && it.session.strain }
                    val next = VoiceCatalog.nextLesson(plan.goal, sessions(userId).mapNotNull { it.session.lessonId }.toSet())
                    out += Reminder(
                        key = "voice:$date", at = at, event = WebhookEvent.VOICE_PRACTICE,
                        title = "Time for voice practice",
                        body = if (gently) "Take it gently today: a short warm-up is plenty."
                        else "About ${plural(plan.dailyMinutes, "minute")}." + (next?.let { " Next up: ${it.title}." } ?: ""),
                        privateTitle = PRIVATE_TITLE, privateBody = PRIVATE_BODY,
                        url = "/transition/voice", actions = listOf(ReminderAction("open", "Open")),
                        data = buildJsonObject { put("minutes", plan.dailyMinutes) },
                        detailedData = buildJsonObject { put("minutes", plan.dailyMinutes); next?.let { put("lesson", it.title) } },
                    )
                }
            }
            date = date.plusDays(1)
        }
        return out
    }

    companion object {
        const val PLAN = "voice.plan"
        const val PRIVATE_TITLE = "Time for a little practice"
        const val PRIVATE_BODY = "A few minutes, whenever you're ready."
        private const val MIN_HZ = 60.0
        private const val MAX_HZ = 500.0
        private val TIME = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""")
        private val EVER = LocalDate.of(1900, 1, 1)
        private val FOREVER = LocalDate.of(2200, 1, 1)

        // ------------------------------------------------------------ Checking what's sent

        /** The plan, or a plain reason it can't be saved. */
        fun checkPlan(p: VoicePlan, now: Long): VoicePlan {
            fun hz(v: Double?, what: String) = v?.also { if (!it.isFinite() || it < MIN_HZ || it > MAX_HZ) throw ApiError.badRequest("$what should be between 60 and 500 Hz") }
            val low = hz(p.targetLowHz, "The bottom of your range")
            val high = hz(p.targetHighHz, "The top of your range")
            if ((low == null) != (high == null)) throw ApiError.badRequest("A range needs both a bottom and a top")
            if (low != null && high != null && low >= high) throw ApiError.badRequest("The bottom of your range should be below the top")
            if (p.baselineAt != null && (p.baselineAt!! <= 0 || p.baselineAt!! > now + 6 * 3_600_000L)) throw ApiError.badRequest("That's in the future")
            if (p.dailyMinutes !in 1..60) throw ApiError.badRequest("Daily practice can be 1 to 60 minutes")
            val reminder = p.reminder?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                val m = TIME.matchEntire(text) ?: throw ApiError.badRequest("Reminder times look like 18:30")
                "${m.groupValues[1].padStart(2, '0')}:${m.groupValues[2]}"
            }
            if (p.restDays.any { it !in 1..7 }) throw ApiError.badRequest("Rest days go from 1 (Monday) to 7 (Sunday)")
            return VoicePlan(
                goal = p.goal,
                targetLowHz = low,
                targetHighHz = high,
                targetResonance = score(p.targetResonance, "Resonance"),
                baselinePitchHz = hz(p.baselinePitchHz, "Your starting pitch"),
                baselineResonance = score(p.baselineResonance, "Resonance"),
                baselineAt = p.baselineAt,
                dailyMinutes = p.dailyMinutes,
                reminder = reminder,
                restDays = p.restDays.distinct().sorted(),
            )
        }

        /** A session from the trainer: its lesson and exercises must be in the programme, and its numbers sane. */
        fun checkSession(v: VoiceSession): VoiceSession {
            val lesson = v.lessonId?.trim()?.takeIf { it.isNotEmpty() }
            if (lesson != null && VoiceCatalog.lesson(lesson) == null) throw ApiError.badRequest("That lesson isn't in the programme")
            if (v.results.size > 100) throw ApiError.badRequest("That's more exercises than one session holds")
            val results = v.results.map { checkResult(it) }
            return v.copy(
                lessonId = lesson,
                results = results,
                resonance = score(v.resonance, "Resonance"),
                weight = score(v.weight, "Weight"),
                // Sessions from the trainer name what was done, for the log and the export.
                exercises = v.exercises.ifEmpty { results.mapNotNull { VoiceCatalog.exercise(it.exerciseId)?.title }.distinct() },
            )
        }

        private fun score(v: Double?, what: String) = v?.also { if (!it.isFinite() || it < 0 || it > 100) throw ApiError.badRequest("$what goes from 0 to 100") }

        private fun checkResult(r: ExerciseResult): ExerciseResult {
            VoiceCatalog.exercise(r.exerciseId) ?: throw ApiError.badRequest("There's no exercise called ${r.exerciseId.take(40)} in the programme")
            fun bad(): Nothing = throw ApiError.badRequest("Some of the numbers for ${r.exerciseId} don't look right")
            fun Double?.within(lo: Double, hi: Double) { if (this != null && (!isFinite() || this < lo || this > hi)) bad() }
            fun Int?.within(lo: Int, hi: Int) { if (this != null && this !in lo..hi) bad() }
            r.seconds.within(0, 3600)
            r.pitchAvgHz.within(40.0, 600.0)
            r.pitchMinHz.within(40.0, 600.0)
            r.pitchMaxHz.within(40.0, 600.0)
            if (r.pitchMinHz != null && r.pitchMaxHz != null && r.pitchMinHz!! > r.pitchMaxHz!!) bad()
            r.inRange.within(0, 100)
            r.resonance.within(0.0, 100.0)
            r.weight.within(0.0, 100.0)
            r.intonation.within(0.0, 48.0)
            r.of.within(0, 200)
            r.matched.within(0, r.of ?: 200)
            r.feeling.within(1, 5)
            return r
        }

        // ------------------------------------------------------------ Progress

        fun progress(plan: VoicePlan, sessions: List<DatedSession>, today: LocalDate): VoiceProgress {
            val past = sessions.filter { !it.date.isAfter(today) }
            val done = past.mapNotNull { it.session.lessonId }.toSet()
            val path = VoiceCatalog.lessonsFor(plan.goal)
            val streak = streak(past.map { it.date }.toSet(), plan.restDays.toSet(), today)
            val weekStart = today.with(DayOfWeek.MONDAY)
            val weeks = weeks(past, today)
            return VoiceProgress(
                plan = plan,
                lessonsDone = VoiceCatalog.LESSONS.map { it.id }.filter { it in done },
                nextLessonId = VoiceCatalog.nextLesson(plan.goal, done)?.id,
                streakDays = streak,
                minutesThisWeek = past.filter { !it.date.isBefore(weekStart) }.sumOf { it.session.minutes },
                weeks = weeks,
                highlights = highlights(plan, weeks, streak, path.count { it.id in done }, path.size, today),
                restNote = restNote(past, today),
            )
        }

        /** Days in a row with practice, up to today (or yesterday, while today's still to come). Rest days don't break it. */
        fun streak(days: Set<LocalDate>, restDays: Set<Int>, today: LocalDate): Int {
            val first = days.minOrNull() ?: return 0
            var date = if (today in days) today else today.minusDays(1)
            var n = 0
            while (!date.isBefore(first)) {
                when {
                    date in days -> n++
                    date.dayOfWeek.value in restDays -> {}
                    else -> break
                }
                date = date.minusDays(1)
            }
            return n
        }

        /** Each week (Monday first) from the first session, up to [count] weeks, oldest first. */
        fun weeks(sessions: List<DatedSession>, today: LocalDate, count: Int = 12): List<VoiceWeek> {
            if (sessions.isEmpty()) return emptyList()
            val thisWeek = today.with(DayOfWeek.MONDAY)
            var week = maxOf(sessions.minOf { it.date }.with(DayOfWeek.MONDAY), thisWeek.minusWeeks(count - 1L))
            val out = mutableListOf<VoiceWeek>()
            while (!week.isAfter(thisWeek)) {
                val start = week
                val list = sessions.filter { !it.date.isBefore(start) && it.date.isBefore(start.plusDays(7)) }.map { it.session }
                out += VoiceWeek(start.toString(), list.sumOf { it.minutes }, list.size, pitch(list), inRange(list), resonance(list))
                week = week.plusWeeks(1)
            }
            return out
        }

        /** A session's pitch: its own average, or its exercises' (by time). */
        fun pitchOf(s: VoiceSession): Double? = s.pitchAvgHz ?: weighted(s.results) { it.pitchAvgHz }

        fun resonanceOf(s: VoiceSession): Double? = s.resonance ?: weighted(s.results) { it.resonance }

        fun pitch(list: List<VoiceSession>): Double? = list.mapNotNull { pitchOf(it) }.takeIf { it.isNotEmpty() }?.average()?.round1()

        fun resonance(list: List<VoiceSession>): Double? = list.mapNotNull { resonanceOf(it) }.takeIf { it.isNotEmpty() }?.average()?.round1()

        /** Share of time in the target range across every exercise that measured it, 0–100. */
        fun inRange(list: List<VoiceSession>): Int? = weighted(list.flatMap { it.results }) { it.inRange?.toDouble() }?.roundToInt()

        private fun weighted(results: List<ExerciseResult>, value: (ExerciseResult) -> Double?): Double? {
            val pairs = results.mapNotNull { r -> value(r)?.let { it to r.seconds.coerceAtLeast(1).toDouble() } }
            if (pairs.isEmpty()) return null
            return pairs.sumOf { it.first * it.second } / pairs.sumOf { it.second }
        }

        private fun Double.round1() = (this * 10).roundToInt() / 10.0

        private fun semitones(ratio: Double) = 12 * ln(ratio) / ln(2.0)

        /** How far [hz] is outside the range, in semitones (0 inside it). */
        private fun outside(hz: Double, low: Double, high: Double) = when {
            hz < low -> semitones(low / hz)
            hz > high -> semitones(hz / high)
            else -> 0.0
        }

        /**
         * A few kind, plain lines about the latest week of practice, against the week before and
         * the person's starting point. Never "higher" or "lower": only nearer their own target.
         */
        fun highlights(plan: VoicePlan, weeks: List<VoiceWeek>, streak: Int, done: Int, total: Int, today: LocalDate): List<String> {
            val index = weeks.indexOfLast { it.sessions > 0 }
            if (index < 0) {
                val first = VoiceCatalog.lessonsFor(plan.goal).firstOrNull()
                return listOf("Start whenever you're ready" + (first?.let { ": the first lesson takes about ${it.minutes} minutes." } ?: "."))
            }
            val current = weeks[index]
            val previous = weeks.getOrNull(index - 1)
            val thisWeek = today.with(DayOfWeek.MONDAY)
            val label = when (LocalDate.parse(current.weekStart)) {
                thisWeek -> "this week"
                thisWeek.minusWeeks(1) -> "last week"
                else -> "in the week of ${Say.shortDate(LocalDate.parse(current.weekStart))}"
            }
            val out = mutableListOf<String>()
            out += if (previous != null && current.minutes > previous.minutes) "${plural(current.minutes, "minute")} of practice $label, more than the week before."
            else "${plural(current.sessions, "session")} $label, ${plural(current.minutes, "minute")} in all."

            current.inRange?.let { r ->
                val before = previous?.inRange
                out += if (before != null && r > before) "In your range $r% of the time $label, up from $before%." else "In your range $r% of the time $label."
                if (before != null && r <= before - 10) out += "Voices vary from week to week. A dip is part of it, not a step back."
            }

            val pitch = current.pitchAvgHz
            val low = plan.targetLowHz
            val high = plan.targetHighHz
            if (pitch != null) {
                val base = plan.baselinePitchHz
                val hz = pitch.roundToInt()
                when {
                    low != null && high != null && outside(pitch, low, high) == 0.0 ->
                        out += "Your average pitch $label, $hz Hz, sat in your target range" + (base?.let { ". You started at ${it.roundToInt()} Hz." } ?: ".")
                    low != null && high != null && base != null && outside(pitch, low, high) < outside(base, low, high) - 0.5 ->
                        out += "Your average pitch $label, $hz Hz, was nearer your range than when you started (${base.roundToInt()} Hz)."
                    low == null && base != null -> out += "Your average pitch $label was $hz Hz. You started at ${base.roundToInt()} Hz."
                }
            }

            val resonance = current.resonance
            val target = plan.targetResonance
            if (resonance != null) {
                val r = resonance.roundToInt()
                val base = plan.baselineResonance
                val before = previous?.resonance
                when {
                    target != null && abs(resonance - target) <= 5 -> out += "Resonance $r $label: right around your target of ${target.roundToInt()}."
                    target != null && base != null && abs(resonance - target) < abs(base - target) - 3 ->
                        out += "Resonance $r $label, nearer your target of ${target.roundToInt()} than when you started (${base.roundToInt()})."
                    target != null && before != null && abs(resonance - target) < abs(before - target) - 3 ->
                        out += "Resonance $r $label, nearer your target of ${target.roundToInt()} than the week before (${before.roundToInt()})."
                    target == null && base != null -> out += "Resonance $r $label. You started at ${base.roundToInt()}."
                }
            }

            if (streak >= 3) out += "$streak practice days in a row. Lovely consistency."
            if (total > 0 && done >= total) out += "Every lesson done. Keep going with your daily routine, and check in now and then."
            else if (done > 0) out += "$done of $total lessons done."
            return out.take(6)
        }

        /** After strain: a gentle note to rest, and to see someone if it keeps happening. */
        fun restNote(sessions: List<DatedSession>, today: LocalDate): String? {
            val past = sessions.filter { !it.date.isAfter(today) }.sortedByDescending { it.session.at }
            val latest = past.firstOrNull() ?: return null
            val strained = past.count { it.session.strain && !it.date.isBefore(today.minusDays(13)) }
            return when {
                strained >= 3 ->
                    "Strain has come up $strained times in the last two weeks. Give your voice a few days' rest and go lighter when you come back. " +
                        "A speech-language pathologist (SLP), ideally one experienced in gender-affirming voice care, can help you find an easier way."
                latest.session.strain && !latest.date.isBefore(today.minusDays(2)) ->
                    "You felt some strain last time. Rest your voice today if you can, sip some water, and start gently next time. " +
                        "If it keeps happening, or hoarseness lasts more than a couple of weeks, a speech-language pathologist (SLP) can help."
                else -> null
            }
        }

        // ------------------------------------------------------------ Digest and report

        /** The digest's line for a week of practice, against the week before. */
        fun digestLine(week: List<DatedSession>, before: List<DatedSession>, plan: VoicePlan): String? {
            if (week.isEmpty()) return null
            val s = week.map { it.session }
            val parts = mutableListOf("${plural(s.sumOf { it.minutes }, "minute")} in ${plural(s.size, "session")} over ${plural(week.map { it.date }.distinct().size, "day")}")
            inRange(s)?.let { parts += "in your range $it% of the time" }
            resonance(s)?.let { r ->
                val b = resonance(before.map { it.session })
                val t = plan.targetResonance
                parts += when {
                    b != null && t != null && abs(r - t) < abs(b - t) - 3 -> "resonance ${r.roundToInt()}, nearer your target than the week before (${b.roundToInt()})"
                    b != null -> "resonance ${r.roundToInt()} (${b.roundToInt()} the week before)"
                    else -> "resonance ${r.roundToInt()}"
                }
            }
            return "Voice practice: ${parts.joinToString(", ")}."
        }

        /** The report's lines for the period: time, time in range, how resonance moved, and the programme. */
        fun reportLines(sessions: List<DatedSession>, plan: VoicePlan, allDone: Set<String>): List<String> {
            val out = mutableListOf<String>()
            if (sessions.isNotEmpty()) {
                val s = sessions.map { it.session }
                val parts = mutableListOf("${plural(s.sumOf { it.minutes }, "minute")} in ${plural(s.size, "session")}")
                inRange(s)?.let { parts += "in the target range $it% of the time" }
                val res = sessions.sortedBy { it.session.at }.mapNotNull { resonanceOf(it.session) }
                if (res.size >= 2) {
                    // The first few sessions against the latest few (up to three each, never overlapping).
                    val n = (res.size / 2).coerceIn(1, 3)
                    val first = res.take(n).average().roundToInt()
                    val last = res.takeLast(n).average().roundToInt()
                    parts += "resonance $first at first and $last lately (0 darker, 100 brighter)"
                }
                out += "Voice practice: ${parts.joinToString(", ")}."
                if (s.any { it.strain }) out += "Strain noted in ${plural(s.count { it.strain }, "session")}."
            }
            if (allDone.isNotEmpty() || plan != VoicePlan()) {
                val path = VoiceCatalog.lessonsFor(plan.goal)
                val range = if (plan.targetLowHz != null && plan.targetHighHz != null) ", target range ${plan.targetLowHz!!.roundToInt()}–${plan.targetHighHz!!.roundToInt()} Hz" else ""
                out += "Voice programme: ${path.count { it.id in allDone }} of ${path.size} lessons done (goal: ${plan.goal.label.lowercase()}$range)."
            }
            return out
        }
    }
}
