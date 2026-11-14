package com.health.app.ui.modules.transition.voice

import com.health.api.ExerciseKind
import com.health.api.ExerciseResult
import com.health.api.VoiceExercise
import com.health.api.VoiceGoal
import com.health.api.VoiceLesson
import com.health.api.VoiceMeter
import com.health.api.VoicePlan
import com.health.api.VoiceProgram
import com.health.api.VoiceProgress
import com.health.api.VoiceSession
import com.health.app.attempt
import com.health.app.ui.modules.transition.PitchPoint
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The trainer's plain logic, kept apart from the screens so it can be tested: what an exercise's
 * listening adds up to (ExerciseResult) and a lesson's (VoiceSession), matching notes, starting
 * suggestions for a plan, where the person is in the programme, the reminder's timing, and the
 * kind, plain words the screens use. Nothing here assumes a direction: the goal is the person's.
 */

/** Building results from what was heard. */
object VoiceResults {
    /** Half a second of voice: less than that, and an exercise's numbers would mean little. */
    const val MIN_VOICED = 25

    /** Fewer resonance or weight readings than this aren't reported. */
    const val MIN_READINGS = 10

    /**
     * How one exercise went, from its frames (as heard, voiced and not), against the plan. Only what
     * the exercise measures: no pitch average or time in range for glides (they roam on purpose),
     * intonation for speech and for exercises that show it, nothing measured for guided ones.
     */
    fun result(
        exercise: VoiceExercise,
        seconds: Int,
        frames: List<VoiceFrame>,
        plan: VoicePlan,
        matched: Int? = null,
        of: Int? = null,
        feeling: Int? = null,
    ): ExerciseResult {
        val base = ExerciseResult(exercise.id, seconds, matched = matched, of = of, feeling = feeling)
        val voiced = frames.filter { it.voiced }
        if (exercise.kind == ExerciseKind.GUIDED || voiced.size < MIN_VOICED) return base
        val pitches = steadied(voiced.map { it.hz!! })
        val roams = exercise.kind == ExerciseKind.GLIDE
        val low = plan.targetLowHz
        val high = plan.targetHighHz
        val speech = exercise.kind in setOf(ExerciseKind.SAY, ExerciseKind.READ, ExerciseKind.SPEAK) || VoiceMeter.INTONATION in exercise.meters
        val resonances = voiced.mapNotNull { it.resonance }
        val weights = voiced.mapNotNull { it.weight }
        return base.copy(
            pitchAvgHz = if (roams) null else pitches.average().tenths(),
            pitchMinHz = percentile(pitches, 5.0).tenths(),
            pitchMaxHz = percentile(pitches, 95.0).tenths(),
            inRange = if (roams || low == null || high == null) null else (pitches.count { it in low..high } * 100.0 / pitches.size).roundToInt(),
            resonance = resonances.takeIf { it.size >= MIN_READINGS }?.let { median(it).tenths() },
            weight = weights.takeIf { it.size >= MIN_READINGS }?.let { median(it).tenths() },
            intonation = if (speech) VoiceScale.intonation(pitches)?.let { (it * 100).roundToInt() / 100.0 } else null,
        )
    }

    /** Voiced pitch steadied by a median of five, so a stray octave jump doesn't count. */
    fun steadied(pitches: List<Double>): List<Double> = pitches.indices.map { i ->
        median(pitches.subList((i - 2).coerceAtLeast(0), (i + 3).coerceAtMost(pitches.size)))
    }

    /**
     * A lesson's (or a free practice's) session: its results, the time spent, and averages weighted
     * by each exercise's time. [titles] are the exercises' names, for the transition page's list.
     */
    fun session(
        at: Long,
        lessonId: String?,
        titles: List<String>,
        results: List<ExerciseResult>,
        strain: Boolean,
        note: String? = null,
    ): VoiceSession {
        val seconds = results.sumOf { it.seconds }
        fun weighted(value: (ExerciseResult) -> Double?): Double? {
            val with = results.filter { value(it) != null }
            if (with.isEmpty()) return null
            val weights = with.map { it.seconds.coerceAtLeast(1).toDouble() }
            return (with.indices.sumOf { value(with[it])!! * weights[it] } / weights.sum()).tenths()
        }
        val feelings = results.mapNotNull { it.feeling }
        return VoiceSession(
            at = at,
            minutes = (seconds / 60.0).roundToInt().coerceAtLeast(1),
            exercises = titles.distinct(),
            pitchAvgHz = weighted { it.pitchAvgHz },
            pitchMinHz = results.mapNotNull { it.pitchMinHz }.minOrNull(),
            pitchMaxHz = results.mapNotNull { it.pitchMaxHz }.maxOrNull(),
            feeling = feelings.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            note = note?.trim()?.ifEmpty { null },
            lessonId = lessonId,
            results = results,
            resonance = weighted { it.resonance },
            weight = weighted { it.weight },
            strain = strain,
        )
    }

    private fun Double.tenths() = (this * 10).roundToInt() / 10.0
}

/**
 * Matching notes: each target is matched once the voice has stayed within [tolerance] semitones of
 * it for [holdMs] in all (not necessarily in one go), so a wobble on the way doesn't undo it.
 */
class MatchRound(val targets: List<Double>, private val holdMs: Int = 500, private val tolerance: Double = 1.0) {
    var index = 0
        private set
    private val matched = BooleanArray(targets.size)
    private var heard = 0

    val target: Double? get() = targets.getOrNull(index)
    val done: Boolean get() = index >= targets.size
    val count: Int get() = matched.count { it }
    fun matched(i: Int): Boolean = matched.getOrElse(i) { false }

    /** How close the current note is to matched, 0–1. */
    val progress: Float get() = (heard.toFloat() / holdMs).coerceIn(0f, 1f)

    /** Hears one frame; true when it completes the current note. */
    fun hear(hz: Double?, frameMs: Int = VoiceScale.HOP_MS): Boolean {
        val t = target ?: return false
        if (matched[index]) return false
        if (hz != null && abs(VoiceScale.semitones(hz, t)) <= tolerance) heard += frameMs
        if (heard >= holdMs) {
            matched[index] = true
            return true
        }
        return false
    }

    /** Moves on to the next note (matched or not). */
    fun next() {
        if (index < targets.size) index++
        heard = 0
    }

    companion object {
        /** The notes to play, from semitones around the middle of the person's range. */
        fun targets(notes: List<Int>, plan: VoicePlan): List<Double> = notes.map { VoiceScale.shift(VoicePlans.centre(plan), it.toDouble()) }
    }
}

/** Where the person's voice is today, from the baseline: a passage read aloud, then speaking freely. */
data class Baseline(val pitchHz: Double, val lowHz: Double, val highHz: Double, val resonance: Double?, val speakingDb: Double) {
    companion object {
        /** Two seconds of voice at least; else null. Pitch is the median, the range its 10th to 90th percentile. */
        fun of(frames: List<VoiceFrame>): Baseline? {
            val voiced = frames.filter { it.voiced }
            if (voiced.size < 100) return null
            val pitches = VoiceResults.steadied(voiced.map { it.hz!! })
            val resonances = voiced.mapNotNull { it.resonance }
            return Baseline(
                pitchHz = (median(pitches) * 10).roundToInt() / 10.0,
                lowHz = percentile(pitches, 10.0).roundToInt().toDouble(),
                highHz = percentile(pitches, 90.0).roundToInt().toDouble(),
                resonance = resonances.takeIf { it.size >= VoiceResults.MIN_READINGS }?.let { (median(it) * 10).roundToInt() / 10.0 },
                speakingDb = median(voiced.map { it.db }),
            )
        }
    }
}

/** Starting points for a plan, and reading one. */
object VoicePlans {
    data class Suggestion(val lowHz: Double, val highHz: Double, val resonance: Double, val why: String)

    /** Whether the person has set up a plan (a target range; the baseline is optional). */
    fun isSetUp(plan: VoicePlan?): Boolean = plan?.targetLowHz != null && plan.targetHighHz != null

    /**
     * Gentle starting suggestions for [goal], from the baseline when there is one. Only ever a
     * place to start: the person can change any of it, any time.
     */
    fun suggest(goal: VoiceGoal, baselineHz: Double?, baselineResonance: Double?): Suggestion = when (goal) {
        VoiceGoal.LIGHTER -> {
            val already = baselineHz != null && baselineHz >= 180
            Suggestion(
                lowHz = if (already) round5(VoiceScale.shift(baselineHz!!, -2.0)) else 165.0,
                highHz = if (already) round5(VoiceScale.shift(baselineHz!!, 3.0)) else 220.0,
                resonance = ((baselineResonance ?: 45.0) + 15).coerceIn(60.0, 80.0).roundToInt().toDouble(),
                why = if (already) "Around where your voice already sits, reaching a little higher." else "A range many people start from for a lighter, brighter voice.",
            )
        }
        VoiceGoal.DEEPER -> {
            val already = baselineHz != null && baselineHz <= 125
            Suggestion(
                lowHz = if (already) round5(VoiceScale.shift(baselineHz!!, -3.0)) else 100.0,
                highHz = if (already) round5(VoiceScale.shift(baselineHz!!, 2.0)) else 140.0,
                resonance = ((baselineResonance ?: 55.0) - 15).coerceIn(15.0, 40.0).roundToInt().toDouble(),
                why = if (already) "Around where your voice already sits, reaching a little lower." else "A range many people start from for a deeper, fuller voice.",
            )
        }
        VoiceGoal.EXPLORING -> Suggestion(
            lowHz = baselineHz?.let { round5(VoiceScale.shift(it, -3.0)) } ?: 130.0,
            highHz = baselineHz?.let { round5(VoiceScale.shift(it, 3.0)) } ?: 190.0,
            resonance = (baselineResonance ?: 50.0).roundToInt().toDouble(),
            why = if (baselineHz != null) "Centred on your voice today, with room either side to explore." else "A wide middle range, with room either side to explore.",
        )
    }

    /** Where to centre notes and the trace: the middle of the range, else the baseline, else the goal's usual middle. */
    fun centre(plan: VoicePlan): Double {
        val low = plan.targetLowHz
        val high = plan.targetHighHz
        if (low != null && high != null && low > 0 && high > 0) return VoiceScale.middle(low, high)
        plan.baselinePitchHz?.let { return it }
        return when (plan.goal) {
            VoiceGoal.LIGHTER -> 190.0
            VoiceGoal.DEEPER -> 120.0
            VoiceGoal.EXPLORING -> 155.0
        }
    }

    /** "165–220 Hz (E3 to A3)". */
    fun range(plan: VoicePlan): String? {
        val low = plan.targetLowHz ?: return null
        val high = plan.targetHighHz ?: return null
        return "${low.roundToInt()}–${high.roundToInt()} Hz (${VoiceScale.note(low)} to ${VoiceScale.note(high)})"
    }

    /** Whether [date] is one of the plan's rest days. */
    fun restDay(plan: VoicePlan, date: LocalDate): Boolean = date.dayOfWeek.value in plan.restDays

    private fun round5(hz: Double) = (hz / 5).roundToInt() * 5.0
}

/** Where a lesson stands for the person. */
enum class LessonStatus { DONE, NEXT, OPEN, LOCKED }

/**
 * The programme for the person's goal: the lessons that suit it, by unit, what's open, and what comes
 * next. Local equivalents of VoiceCatalog's helpers (lessonsFor, exercisesFor, nextLesson, suits),
 * working on the programme the server sent (or the phone's copy of it); they can delegate to
 * VoiceCatalog once it's in shared/.
 */
object VoiceCourse {
    /** Whether a lesson suits the goal (one without goals suits everyone). */
    fun suits(lesson: VoiceLesson, goal: VoiceGoal): Boolean = lesson.goals.isEmpty() || goal in lesson.goals

    /** Whether an exercise suits the goal (one without goals suits everyone). */
    fun suits(exercise: VoiceExercise, goal: VoiceGoal): Boolean = exercise.goals.isEmpty() || goal in exercise.goals

    /** The lessons for [goal], in order (VoiceCatalog.lessonsFor). */
    fun lessonsFor(program: VoiceProgram, goal: VoiceGoal): List<VoiceLesson> = program.lessons.filter { suits(it, goal) }

    /** A lesson's exercises for [goal], in order: only those that suit it (VoiceCatalog.exercisesFor). */
    fun exercisesFor(program: VoiceProgram, lesson: VoiceLesson, goal: VoiceGoal): List<VoiceExercise> {
        val byId = program.exercises.associateBy { it.id }
        return lesson.exerciseIds.mapNotNull { byId[it] }.filter { suits(it, goal) }
    }

    /** The goal's lessons by unit, in order. */
    fun units(program: VoiceProgram, goal: VoiceGoal): List<Pair<Int, List<VoiceLesson>>> =
        lessonsFor(program, goal).groupBy { it.unit }.toSortedMap().map { (unit, lessons) -> unit to lessons }

    /** A unit's heading: "Unit 2 · Resonance", from the programme's units, or just its number. */
    fun unitTitle(program: VoiceProgram, unit: Int): String =
        program.units.firstOrNull { it.unit == unit }?.let { "Unit $unit · ${it.title}" } ?: "Unit $unit"

    /** What a unit is about, when the programme says. */
    fun unitAbout(program: VoiceProgram, unit: Int): String? = program.units.firstOrNull { it.unit == unit }?.about?.takeIf { it.isNotBlank() }

    /** The units open to the person: the first, and each whose unit before has had each of the goal's lessons done once. */
    fun openUnits(program: VoiceProgram, goal: VoiceGoal, done: Set<String>): Set<Int> {
        val units = units(program, goal)
        val open = HashSet<Int>()
        for ((i, pair) in units.withIndex()) {
            if (i == 0 || units[i - 1].second.all { it.id in done }) open += pair.first else break
        }
        return open
    }

    /** The first open lesson for [goal] not done yet, else the goal's first (VoiceCatalog.nextLesson). */
    fun nextLesson(program: VoiceProgram, goal: VoiceGoal, done: Set<String>): VoiceLesson? {
        val open = openUnits(program, goal, done)
        return units(program, goal).filter { it.first in open }.flatMap { it.second }.firstOrNull { it.id !in done }
            ?: lessonsFor(program, goal).firstOrNull()
    }

    /** The lesson for today: the server's choice (when it suits the goal), else [nextLesson]. */
    fun next(program: VoiceProgram, progress: VoiceProgress?, goal: VoiceGoal): VoiceLesson? {
        progress?.nextLessonId?.let { id -> program.lessons.firstOrNull { it.id == id && suits(it, goal) }?.let { return it } }
        return nextLesson(program, goal, progress?.lessonsDone.orEmpty().toSet())
    }

    fun status(lesson: VoiceLesson, program: VoiceProgram, progress: VoiceProgress?, goal: VoiceGoal): LessonStatus {
        val done = progress?.lessonsDone.orEmpty().toSet()
        return when {
            next(program, progress, goal)?.id == lesson.id -> LessonStatus.NEXT
            lesson.id in done -> LessonStatus.DONE
            lesson.unit in openUnits(program, goal, done) -> LessonStatus.OPEN
            else -> LessonStatus.LOCKED
        }
    }

    /** Whether an exercise listens through the microphone. */
    fun listens(exercise: VoiceExercise): Boolean = exercise.kind != ExerciseKind.GUIDED

    /**
     * The meters an exercise shows: its own, or sensible ones for its kind when it names none.
     * Holds, glides, matching and resonance work always show the live pitch trace.
     */
    fun meters(exercise: VoiceExercise): List<VoiceMeter> {
        val own = exercise.meters.ifEmpty {
            when (exercise.kind) {
                ExerciseKind.GUIDED -> emptyList()
                ExerciseKind.HOLD, ExerciseKind.GLIDE, ExerciseKind.MATCH -> listOf(VoiceMeter.PITCH)
                ExerciseKind.RESONANCE -> listOf(VoiceMeter.RESONANCE, VoiceMeter.PITCH)
                ExerciseKind.SAY, ExerciseKind.READ, ExerciseKind.SPEAK -> listOf(VoiceMeter.PITCH, VoiceMeter.RESONANCE)
            }
        }
        val traced = exercise.kind in setOf(ExerciseKind.HOLD, ExerciseKind.GLIDE, ExerciseKind.MATCH, ExerciseKind.RESONANCE)
        return if (traced && VoiceMeter.PITCH !in own) own + VoiceMeter.PITCH else own
    }
}

/** When the practice reminder goes off: pure, so it can be tested. */
object VoiceReminderPlan {
    /**
     * The next reminder after [now] at "HH:mm" [time]: not on rest days, and not today if the person
     * has already practised today ([practisedOn]). Null without a time.
     */
    fun next(time: String?, restDays: List<Int>, practisedOn: LocalDate?, now: Long, zone: ZoneId): Long? {
        val at = time?.let { attempt { LocalTime.parse(it) }.getOrNull() } ?: return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (offset in 0..7) {
            val date = today.plusDays(offset.toLong())
            if (date.dayOfWeek.value in restDays) continue
            if (date == practisedOn) continue
            val moment = date.atTime(at).atZone(zone).toInstant().toEpochMilli()
            if (moment > now) return moment
        }
        return null
    }
}

/** The trainer's words: short, warm and plain, never assuming a direction, never shaming. */
object VoiceWords {
    /** How an exercise felt, 1–5. */
    fun feeling(n: Int?): String? = when (n) {
        1 -> "Hard going"
        2 -> "A bit tricky"
        3 -> "OK"
        4 -> "Good"
        5 -> "Great"
        else -> null
    }

    fun resonance(value: Double): String = when {
        value < 30 -> "darker"
        value < 45 -> "a little darker"
        value <= 55 -> "in the middle"
        value <= 70 -> "a little brighter"
        else -> "brighter"
    }

    fun weight(value: Double): String = when {
        value < 35 -> "on the lighter side"
        value <= 65 -> "in the middle"
        else -> "on the heavier side"
    }

    fun intonation(spread: Double): String = when {
        spread < 1.2 -> "very even"
        spread < 2.2 -> "some movement"
        spread < 3.5 -> "lively"
        else -> "very lively"
    }

    /** Where a value sits against a target, in words (never by colour alone). */
    fun against(value: Double, target: Double?, darker: String, brighter: String): String? = when {
        target == null -> null
        abs(value - target) <= 5 -> "right around your target"
        value < target -> darker
        else -> brighter
    }

    /** A pitch against the person's range. */
    fun pitch(hz: Double, low: Double?, high: Double?): String? = when {
        low == null || high == null -> null
        hz < low -> "Below your range"
        hz > high -> "Above your range"
        else -> "In your range"
    }

    private val CHEERS = listOf(
        "Every bit of practice counts.",
        "Some days are easier than others, and that's normal.",
        "Your voice is learning, even when it doesn't feel like it.",
        "Small, steady practice goes a long way.",
        "Progress comes in waves, not a straight line.",
    )

    /** One encouraging line, chosen by [seed] so a screen doesn't change its mind. */
    fun cheer(seed: Int): String = CHEERS[((seed % CHEERS.size) + CHEERS.size) % CHEERS.size]

    /** A short, kind summary of how an exercise went, line by line. */
    fun summary(exercise: VoiceExercise, result: ExerciseResult, plan: VoicePlan, seed: Int = 0): List<String> = buildList {
        val matched = result.matched
        val of = result.of
        if (matched != null && of != null && of > 0) {
            add(
                when (matched) {
                    of -> "You matched all $of notes. Lovely listening."
                    0 -> "Matching notes takes time: your ear learns with every try."
                    else -> "You matched $matched of $of notes."
                },
            )
        }
        val avg = result.pitchAvgHz
        if (avg != null) {
            add("Your pitch averaged ${VoiceScale.hzAndNote(avg)}" + (result.inRange?.let { ", in your range $it% of the time." } ?: "."))
        } else if (exercise.kind == ExerciseKind.GLIDE && result.pitchMinHz != null && result.pitchMaxHz != null) {
            add("You glided from ${VoiceScale.hzAndNote(result.pitchMinHz!!)} to ${VoiceScale.hzAndNote(result.pitchMaxHz!!)}.")
        }
        result.resonance?.let { r ->
            val target = against(r, plan.targetResonance, "a little darker than your target", "a little brighter than your target")
            add("Resonance about ${r.roundToInt()}, ${target ?: resonance(r)}.")
        }
        result.weight?.let { add("Vocal weight about ${it.roundToInt()}, ${weight(it)}.") }
        result.intonation?.let { add("Your melody moved about ${"%.1f".format(java.util.Locale.US, it)} semitones: ${intonation(it)}.") }
        if (isEmpty()) add(if (exercise.kind == ExerciseKind.GUIDED) "Nicely done." else "Nicely done. There wasn't quite enough voice to measure this time, and that's fine.")
        add(cheer(seed))
    }

    const val REST_NOTE =
        "Thank you for noticing. Let's leave it there for today: rest your voice, sip some water, and keep things gentle. " +
            "If it keeps happening or it hurts, a speech therapist (SLP) can really help."

    const val NO_MIC =
        "No problem. You can still follow the steps and the timer; the meters need the microphone. " +
            "You can turn it on any time in your phone's settings."

    const val MIC_WHY =
        "Your phone listens while you practise and works out pitch, resonance and more as you go. " +
            "The sound is worked out on this phone and never sent anywhere."
}

/** What the trainer shows while listening, steadied for the eye. */
data class VoiceLive(
    val listening: Boolean = false,
    val pitchHz: Double? = null,
    val resonance: Double? = null,
    val weight: Double? = null,
    /** The spread of the last few seconds' pitch, in semitones. */
    val intonation: Double? = null,
    val db: Double? = null,
    /** Loud for a while: a gentle nudge to ease off. */
    val pushing: Boolean = false,
    /** The last ten seconds of pitch (milliseconds from the start of listening). */
    val trace: List<PitchPoint> = emptyList(),
    val voicedSeconds: Double = 0.0,
)

/**
 * Turns frames into what the screen shows: each value the median of the last 300 ms (as the web
 * app), the trace, the recent spread, and sustained pushing. Resonance and weight hold a little
 * longer through gaps, as formants drop out now and then.
 */
class LiveMeter(private val speakingDb: Double?, private val traceMs: Long = 10_000) {
    private val pitch = Steady(300, 200)
    private val resonance = Steady(300, 300)
    private val weight = Steady(300, 300)
    private val trace = ArrayDeque<PitchPoint>()
    private val recentPitch = ArrayDeque<Pair<Long, Double>>()
    private val recentDb = ArrayDeque<Pair<Long, Double>>()
    private var voiced = 0

    fun add(frame: VoiceFrame): VoiceLive {
        val at = frame.at
        val hz = pitch.add(at, frame.hz)
        val r = resonance.add(at, frame.resonance)
        val w = weight.add(at, frame.weight)
        if (frame.voiced) {
            voiced++
            recentDb.addLast(at to frame.db)
        }
        hz?.let { recentPitch.addLast(at to it) }
        trace.addLast(PitchPoint(at, hz))
        while (trace.isNotEmpty() && trace.first().at < at - traceMs) trace.removeFirst()
        while (recentPitch.isNotEmpty() && recentPitch.first().first < at - 6_000) recentPitch.removeFirst()
        while (recentDb.isNotEmpty() && recentDb.first().first < at - 5_000) recentDb.removeFirst()
        return VoiceLive(
            listening = true,
            pitchHz = hz,
            resonance = r,
            weight = w,
            intonation = recentPitch.takeIf { it.size >= 50 }?.let { list -> VoiceScale.intonation(list.map { it.second }) },
            db = frame.db.takeIf { frame.voiced || it > -70 },
            pushing = Loudness.pushing(recentDb.map { it.second }, speakingDb),
            trace = trace.toList(),
            voicedSeconds = voiced * VoiceScale.HOP_MS / 1000.0,
        )
    }
}

/** A target tone for matching: a sine with a few softer harmonics, faded in and out so it doesn't click. */
object ToneSynth {
    private val HARMONICS = doubleArrayOf(1.0, 0.45, 0.22, 0.1)

    fun samples(hz: Double, rate: Int, seconds: Double, peak: Double = 0.35): FloatArray {
        val n = (rate * seconds).toInt()
        val attack = (rate * 0.03).toInt()
        val release = (rate * 0.15).toInt()
        val total = HARMONICS.sum()
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            var v = 0.0
            for (h in HARMONICS.indices) if (hz * (h + 1) < rate / 2.0) v += HARMONICS[h] * sin(2 * PI * hz * (h + 1) * t)
            val envelope = when {
                i < attack -> i.toDouble() / attack
                i > n - release -> (n - i).toDouble() / release
                else -> 1.0
            }
            (v / total * peak * envelope).toFloat()
        }
    }
}
