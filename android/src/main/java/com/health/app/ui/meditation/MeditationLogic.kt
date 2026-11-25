package com.health.app.ui.meditation

import com.health.api.BreathPacing
import com.health.api.Meditation
import com.health.api.MeditationKind
import com.health.api.MeditationSession
import com.health.api.MeditationStep
import com.health.api.MeditationSummary
import com.health.app.attempt
import com.health.app.ui.Format
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Guided meditation on the phone, in plain Kotlin so it's tested without one: how a meditation
 * plays (its timeline, the time left, the breathing pacer), which of the phone's voices reads it
 * (only ever one that works offline), the chime, when the nightly reminder goes, and the words the
 * page uses.
 */

/** One part of playing a meditation, in order: a step's words, its breathing, then its quiet. */
sealed interface Segment {
    /** The step it belongs to (0-based). */
    val step: Int

    /** About how long it takes, in seconds. */
    val seconds: Double

    data class Speak(override val step: Int, val text: String, override val seconds: Double) : Segment
    data class Breathe(override val step: Int, val pacing: BreathPacing, override val seconds: Double) : Segment
    data class Pause(override val step: Int, override val seconds: Double) : Segment
}

object MeditationPlan {
    /** The calm pace the server's lengths assume. */
    const val WORDS_PER_SECOND = 2.5

    /** About how long [text] takes to read at the calm pace (a second at least). */
    fun speakSeconds(text: String): Double = max(1.0, text.split(Regex("\\s+")).count { it.isNotBlank() } / WORDS_PER_SECOND)

    /** Each step: its words, then (with a pacer) its breaths, then its quiet. Nothing of no length. */
    fun timeline(steps: List<MeditationStep>): List<Segment> = steps.flatMapIndexed { i, step ->
        buildList {
            if (step.say.isNotBlank()) add(Segment.Speak(i, step.say.trim(), speakSeconds(step.say)))
            step.breath?.let { b -> Pacer.seconds(b).takeIf { it > 0 }?.let { add(Segment.Breathe(i, b, it.toDouble())) } }
            if (step.pauseSeconds > 0) add(Segment.Pause(i, step.pauseSeconds.toDouble()))
        }
    }

    fun total(timeline: List<Segment>): Double = timeline.sumOf { it.seconds }

    /** Seconds left when segment [index] has gone on for [into] seconds (past the end: none). */
    fun secondsLeft(timeline: List<Segment>, index: Int, into: Double): Double {
        if (index >= timeline.size) return 0.0
        val current = timeline.getOrNull(index)?.let { (it.seconds - into).coerceAtLeast(0.0) } ?: 0.0
        return current + timeline.drop(index + 1).sumOf { it.seconds }
    }

    /** "12 min left", "1 min left", "Less than a minute left". */
    fun leftText(seconds: Double): String {
        val minutes = ceil(seconds / 60.0 - 1e-9).toInt()
        return when {
            seconds < 60 -> "Less than a minute left"
            else -> "$minutes min left"
        }
    }

    /** The words on screen for [step]: its own, or the last step's that had any. */
    fun sayAt(steps: List<MeditationStep>, step: Int): String =
        (step.coerceAtMost(steps.lastIndex) downTo 0).firstNotNullOfOrNull { steps.getOrNull(it)?.say?.takeIf(String::isNotBlank) }.orEmpty()
}

/** A breath's three parts; a pacer without a rest has only two. */
enum class BreathPhase(val words: String) {
    IN("Breathe in"),
    HOLD("Rest"),
    OUT("Breathe out"),
}

/** Where the pacer is: which breath (from 1), which part of it, how far through (0–1), and that part's length. */
data class PacerPoint(val round: Int, val phase: BreathPhase, val progress: Float, val phaseSeconds: Int)

object Pacer {
    /** The circle's size breathed out, against 1 breathed in. */
    const val SMALL = 0.55f

    /** One breath's parts, each with its length (a rest of 0 is left out). */
    fun parts(p: BreathPacing): List<Pair<BreathPhase, Int>> = listOfNotNull(
        (BreathPhase.IN to p.inSeconds).takeIf { it.second > 0 },
        (BreathPhase.HOLD to p.holdSeconds).takeIf { it.second > 0 },
        (BreathPhase.OUT to p.outSeconds).takeIf { it.second > 0 },
    )

    /** How long the pacer runs, in seconds. */
    fun seconds(p: BreathPacing): Int = parts(p).sumOf { it.second } * p.rounds.coerceAtLeast(0)

    /** Where the pacer is [elapsed] seconds in; null once it's done (or before it starts). */
    fun at(p: BreathPacing, elapsed: Double): PacerPoint? {
        val parts = parts(p)
        val breath = parts.sumOf { it.second }
        if (breath <= 0 || p.rounds <= 0 || elapsed < 0 || elapsed >= breath * p.rounds) return null
        val round = (elapsed / breath).toInt()
        var into = elapsed - round * breath
        for ((phase, length) in parts) {
            if (into < length) return PacerPoint(round + 1, phase, (into / length).toFloat().coerceIn(0f, 1f), length)
            into -= length
        }
        return null
    }

    /** The circle's size at [point]: growing breathing in, full while resting, shrinking breathing out; eased, so it never jolts. */
    fun scale(point: PacerPoint?): Float {
        point ?: return SMALL
        val eased = ((1 - cos(PI * point.progress)) / 2).toFloat()
        return when (point.phase) {
            BreathPhase.IN -> SMALL + (1 - SMALL) * eased
            BreathPhase.HOLD -> 1f
            BreathPhase.OUT -> 1 - (1 - SMALL) * eased
        }
    }
}

/** One of the phone's text-to-speech voices, as far as choosing one goes. */
data class VoiceOption(
    val name: String,
    val locale: Locale,
    /** Whether it needs the network (it then sends what it reads to someone's servers): never chosen. */
    val network: Boolean,
    /** Whether it's on the phone (one still to download isn't). */
    val installed: Boolean = true,
    /** Android's Voice.QUALITY_* (higher is better). */
    val quality: Int = 300,
    /** Android's Voice.LATENCY_* (lower is quicker). */
    val latency: Int = 300,
)

object VoicePick {
    /**
     * The voice to read with: only one that works offline and is on the phone, in the scripts'
     * language ([language], English); the phone's own country first ([phone]), then the engine's
     * default ([default]), then the best sounding. Null when there's none: then it's text only.
     */
    fun choose(voices: List<VoiceOption>, phone: Locale = Locale.getDefault(), default: String? = null, language: String = "en"): VoiceOption? =
        voices.filter { !it.network && it.installed && it.locale.language == language }
            .sortedWith(
                compareByDescending<VoiceOption> { it.locale.country.equals(phone.country, ignoreCase = true) && phone.language == language }
                    .thenByDescending { it.name == default }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency }
                    .thenBy { it.name },
            ).firstOrNull()
}

/** The soft chime at the start and end, made on the phone (nothing is downloaded). */
object Chime {
    const val RATE = 22_050

    /**
     * A bell-like tone: [hz] with a quieter octave above, a short swell in, then a long fade to
     * silence, never louder than [loudness] (0–1 of full scale).
     */
    fun samples(seconds: Double = 1.8, hz: Double = 528.0, loudness: Double = 0.22, rate: Int = RATE): ShortArray {
        val n = (seconds * rate).toInt().coerceAtLeast(1)
        val attack = 0.04 * rate
        return ShortArray(n) { i ->
            val t = i.toDouble() / rate
            val swell = if (i < attack) i / attack else 1.0
            // Fades away, and reaches silence exactly at the end so it never clicks.
            val fade = exp(-2.6 * t) * (1.0 - i.toDouble() / n)
            val tone = 0.8 * sin(2 * PI * hz * t) + 0.2 * sin(2 * PI * hz * 2 * t)
            (tone * swell * fade * loudness * Short.MAX_VALUE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}

/** When the nightly reminder goes. */
object MeditationReminderPlan {
    /** Not if a meditation was begun this long before. */
    const val RECENT_MS = 30 * 60_000L

    fun parse(time: String?): LocalTime? = time?.let { attempt { LocalTime.parse(it) }.getOrNull() }

    /** The next time after [now] it goes, at [time] ("HH:mm") in [zone]; null when it's off. */
    fun next(on: Boolean, time: String?, now: Long, zone: ZoneId): Long? {
        if (!on) return null
        val at = parse(time) ?: return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (0..1L).map { today.plusDays(it).atTime(at).atZone(zone).toInstant().toEpochMilli() }.first { it > now }
    }

    /** Whether the reminder for [at] shows: not when a meditation began in the half hour before. */
    fun shows(at: Long, lastStartedAt: Long?): Boolean = lastStartedAt == null || lastStartedAt !in (at - RECENT_MS)..at + 60_000L

    const val TITLE = "Time to wind down"
    const val TEXT = "A guided meditation is ready when you are."
}

/** The words of the Meditation page. */
object MeditationWords {
    /** How it felt afterwards, 1 to 5, in words (the scale the server keeps). */
    val FELT = listOf("Still unsettled", "A little unsettled", "In between", "Settled", "Very settled")

    fun felt(value: Int?): String? = value?.let { FELT.getOrNull(it - 1) }

    /** "10 min", "1 min", "Under a minute": how long one went on. */
    fun minutes(minutes: Double): String = when {
        minutes < 1 -> "Under a minute"
        else -> "${minutes.roundToInt()} min"
    }

    /** This week's minutes and days (the last 7, today included): from the sessions where they've come, else the summary's minutes. */
    fun week(summary: MeditationSummary?, sessions: List<MeditationSession>?, today: LocalDate): Pair<Double, Int?> {
        val from = today.minusDays(6)
        val recent = sessions?.filter { s -> s.date?.let { d -> attempt { LocalDate.parse(d) }.getOrNull() }?.let { !it.isBefore(from) && !it.isAfter(today) } == true }
        val minutes = summary?.weekMinutes ?: recent?.sumOf { it.minutes } ?: 0.0
        return minutes to recent?.mapNotNull { it.date }?.distinct()?.size
    }

    /** "On 3 days", "On 1 day" (under "This week"). */
    fun days(days: Int): String = "On $days ${if (days == 1) "day" else "days"}"

    const val NOTHING_YET = "Nothing yet this week. Whenever you're ready."

    /** A session's first line: its meditation's name, or sitting quietly without one. */
    fun title(s: MeditationSession): String = s.title ?: "Sitting quietly"

    /** "10 min · Yesterday, 9:42 PM · Stopped partway · Settled". */
    fun detail(s: MeditationSession, zone: ZoneId, today: LocalDate): String {
        val start = Instant.ofEpochMilli(s.startedAt).atZone(zone)
        return listOfNotNull(
            minutes(s.minutes),
            "${Format.day(start.toLocalDate(), today)}, ${Format.timeOfDay(s.startedAt, zone)}",
            "Stopped partway".takeIf { !s.finished },
            felt(s.feltAfter),
        ).joinToString(" · ")
    }
}

/** Which meditations the page offers first, and at what length. */
object MeditationChoice {
    /** The lengths offered. */
    val LENGTHS = listOf(5, 10, 15)

    /** The kinds, as the server orders them, with the one used last first. */
    fun kinds(meditations: List<Meditation>, lastId: String?): List<MeditationKind> {
        val kinds = meditations.map { it.kind }.distinct()
        val last = meditations.firstOrNull { it.id == lastId }?.kind ?: return kinds
        return listOf(last) + (kinds - last)
    }

    /** The lengths [kind] comes in. */
    fun lengths(meditations: List<Meditation>, kind: MeditationKind): List<Int> = meditations.filter { it.kind == kind }.map { it.minutes }.distinct().sorted()

    /** The length to offer first: the last one used, for its kind; else 10 minutes, or the nearest there is. */
    fun minutes(meditations: List<Meditation>, kind: MeditationKind, lastId: String?): Int {
        meditations.firstOrNull { it.id == lastId && it.kind == kind }?.let { return it.minutes }
        val lengths = lengths(meditations, kind)
        return lengths.minByOrNull { kotlin.math.abs(it - 10) } ?: 10
    }

    fun find(meditations: List<Meditation>, kind: MeditationKind, minutes: Int): Meditation? =
        meditations.firstOrNull { it.kind == kind && it.minutes == minutes } ?: meditations.firstOrNull { it.kind == kind }

    /** One to start from somewhere with no choice (Care's card): the last used, else ten minutes' winding down. */
    fun suggested(meditations: List<Meditation>, lastId: String?): String =
        lastId?.takeIf { id -> meditations.isEmpty() || meditations.any { it.id == id } } ?: "wind-down-10"
}
