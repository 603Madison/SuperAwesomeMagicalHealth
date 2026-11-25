package com.health.api

import kotlinx.serialization.Serializable

/*
 * Guided meditation, for winding down at night (docs/API.md, "Meditation"). The server keeps one
 * copy of the scripts (GET /api/meditations) so both apps say the same thing. The apps read each
 * step's `say` aloud with the device's own text-to-speech, show it on screen, then keep quiet for
 * `pauseSeconds`: nothing is streamed or fetched from anywhere else.
 *
 * Meditation is there for everyone, whatever features are on. What someone logs is sealed on the
 * server, and counts as part of Mind for sharing: a share sees it only when it includes Mind (and
 * Mind is on). AI never sees it.
 */

/** The kinds of guided meditation, each for a different way into sleep. */
@Serializable
enum class MeditationKind(val label: String) {
    /** Settling at the end of the day: arriving, setting the day down, softening. */
    WIND_DOWN("Wind down"),
    /** Noticing the body, region by region, without needing to feel or change anything. */
    BODY_SCAN("Body scan"),
    /** Slow, easy breathing with a pacer. */
    BREATHING("Slow breathing"),
    /** Gentle pictures to drift into sleep with, like a story with no plot. */
    DRIFTING_OFF("Drifting off"),
    /** Noticing something from the day that was OK, or simply steady. */
    GRATITUDE("Gratitude"),
}

/**
 * A breathing pacer, shown while a step runs: [rounds] breaths of [inSeconds] in, [holdSeconds]
 * resting (0 for none), [outSeconds] out. The apps show it after the step's words, before its pause.
 */
@Serializable
data class BreathPacing(val inSeconds: Int, val holdSeconds: Int = 0, val outSeconds: Int, val rounds: Int)

/**
 * One step of a meditation: [say] is read aloud and shown; then, with [breath], the pacer runs its
 * rounds; then [pauseSeconds] of silence before the next step.
 */
@Serializable
data class MeditationStep(
    val say: String,
    val pauseSeconds: Int = 0,
    val breath: BreathPacing? = null,
)

/** A guided meditation, as the server serves it. [id] is stable (sessions and saved choices keep it). */
@Serializable
data class Meditation(
    val id: String,
    val title: String,
    /** One line: what it's like. */
    val about: String,
    /** About how long it takes, read at a calm pace (about 2.5 words a second) with its pauses and pacer. */
    val minutes: Int,
    val kind: MeditationKind,
    val steps: List<MeditationStep>,
)

/** A meditation done (or begun), logged when it ends: sealed on the server. */
@Serializable
data class MeditationSession(
    /** When it began (epoch ms). */
    val startedAt: Long,
    /** How long it actually went on, in minutes (more than 0, up to 600). */
    val minutes: Double,
    /** Whether it was heard to the end; false for one stopped partway. */
    val finished: Boolean = false,
    /** Which of the server's meditations ([Meditation.id]); none for sitting quietly without one. */
    val meditationId: String? = null,
    /** How it felt afterwards, 1 (still unsettled) to 5 (very settled); optional ([Fields.MEDITATION_FELT_AFTER]). */
    val feltAfter: Int? = null,
    /** Set by the server. */
    val id: String? = null,
    /** Set by the server: the person's day it began on. */
    val date: String? = null,
    /** Set by the server from [meditationId]: its title and kind. */
    val title: String? = null,
    val kind: MeditationKind? = null,
)

/**
 * The nightly reminder (GET/PUT /api/meditations/settings): off to start. [time] is "HH:mm" in the
 * person's own time zone (Profile.timezone). It also starts the evening's "Wind down" on Today.
 */
@Serializable
data class MeditationSettings(
    val reminder: Boolean = false,
    val time: String = "21:30",
)

/** Nights after meditating in the evening, set beside nights without, over the period. Only with enough of each. */
@Serializable
data class MeditationNights(
    val nightsAfter: Int,
    val nightsWithout: Int,
    /** Average sleep scores (0–100). */
    val scoreAfter: Int? = null,
    val scoreWithout: Int? = null,
    /** Average time asleep, in minutes. */
    val asleepAfter: Int? = null,
    val asleepWithout: Int? = null,
)

/** Meditation over the last [days] days (GET /api/meditations/summary). */
@Serializable
data class MeditationSummary(
    val days: Int,
    /** Sessions in the period, finished or not, and the finished ones. */
    val sessions: Int = 0,
    val finished: Int = 0,
    /** Minutes in the period, and in the last 7 days (today included). */
    val minutes: Double = 0.0,
    val weekMinutes: Double = 0.0,
    /** Days with any meditation, in the period. */
    val daysMeditated: Int = 0,
    /** The meditation used last, to offer again. */
    val lastMeditationId: String? = null,
    /** Sleep after meditating, against without: only with at least 5 nights of each. */
    val nights: MeditationNights? = null,
    /** What that's like, said as what happened, never as a cause. */
    val insights: List<Insight> = emptyList(),
)
