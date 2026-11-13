package com.health.api

import kotlinx.serialization.Serializable

/*
 * The voice trainer (with Module.TRANSITION): a free, guided programme with live feedback, for
 * anyone working on their voice, in whichever direction they choose. Everything is listened to on
 * the person's own device; no sound leaves it (recordings, if they keep any, stay on the device).
 * Sessions are saved as VoiceSession (with ExerciseResults); the plan and progress on the server.
 *
 * The lessons and exercises are in VoiceCatalog (served at GET /api/transition/voice/program, and
 * used directly by the phone app). Copy is kind and plain, never assumes a direction, and puts
 * vocal health first: no strain, rest days, stop if it hurts, and a speech therapist (SLP) is
 * worth seeing for anything that worries the person.
 *
 * How the apps measure, so the phone and the web agree (both test against the same synthetic sounds):
 * - pitch: YIN on 40 ms frames, 60–500 Hz; a frame counts as voiced with a YIN threshold of 0.15
 *   and enough loudness; shown in Hz and as a note.
 * - resonance (0 darker … 100 brighter): formants F1–F3 by LPC (order 12, on sound resampled to
 *   11 025 Hz, 25 ms Hamming frames, voiced frames only); formant spacing by least squares through
 *   the origin, ΔF = Σ Fn·(n − ½) / Σ (n − ½)² for n = 1…3 (so the vowel-dependent F1 counts least);
 *   apparent vocal-tract length L = 35 000 / (2·ΔF) cm; resonance = (17.5 − L) / (17.5 − 13.5) × 100,
 *   clamped to 0–100. (A shorter-sounding tract is brighter.) Check: F1–F3 = 515, 1544, 2573 Hz
 *   gives ΔF ≈ 1029 Hz, L ≈ 17.0 cm, resonance ≈ 12; 600, 1800, 3000 Hz gives L ≈ 14.6 cm, ≈ 73.
 * - vocal weight (0 lighter … 100 heavier): H1 − H2, the first two harmonics' levels in dB from
 *   the spectrum at f0 and 2·f0; weight = (12 − (H1 − H2)) / 18 × 100, clamped to 0–100.
 * - intonation: the spread (standard deviation) of voiced pitch in semitones from its median.
 * - loudness: RMS in dBFS, with a gentle warning when the person pushes for long.
 */

@Serializable
enum class VoiceGoal(val label: String, val about: String) {
    LIGHTER("Lighter and brighter", "A higher, brighter, lighter-sounding voice"),
    DEEPER("Deeper and fuller", "A lower, darker, fuller-sounding voice"),
    EXPLORING("Exploring", "Somewhere in between, or finding out what feels like you"),
}

@Serializable
enum class VoiceSkill(val label: String) {
    HEALTH("Looking after your voice"),
    WARM_UP("Breath and warm-up"),
    PITCH("Pitch"),
    RESONANCE("Resonance"),
    WEIGHT("Vocal weight"),
    INTONATION("Intonation"),
    SPEECH("Into everyday speech"),
}

/** What an exercise asks for, which decides how the trainer runs and scores it. */
@Serializable
enum class ExerciseKind {
    /** Instructions and a timer (breathing, stretches, rest). */
    GUIDED,
    /** A steady hum or sound, held. */
    HOLD,
    /** Sliding up and down (sirens, glides). */
    GLIDE,
    /** Matching tones the app plays. */
    MATCH,
    /** Sounds or vowels held while watching resonance. */
    RESONANCE,
    /** Words, phrases or sentences from [VoiceExercise.texts], one at a time. */
    SAY,
    /** A passage read aloud. */
    READ,
    /** Speaking freely to a prompt. */
    SPEAK,
}

/** What the trainer shows while the person practises. */
@Serializable
enum class VoiceMeter { PITCH, RESONANCE, WEIGHT, INTONATION, LOUDNESS }

@Serializable
data class VoiceExercise(
    val id: String,
    val title: String,
    val skill: VoiceSkill,
    val kind: ExerciseKind,
    /** What to do, step by step, in short plain sentences. */
    val steps: List<String>,
    val seconds: Int,
    val meters: List<VoiceMeter> = emptyList(),
    /** Words, phrases, passages or prompts, for SAY, READ and SPEAK. */
    val texts: List<String> = emptyList(),
    /** For MATCH: the notes to play, in semitones from the middle of the person's target range. */
    val notes: List<Int> = emptyList(),
    /** The goals it suits (empty: all). Some exercises differ by direction. */
    val goals: Set<VoiceGoal> = emptySet(),
    /** Something to listen for, or to be careful of. */
    val tip: String? = null,
)

@Serializable
data class VoiceLesson(
    val id: String,
    /** Lessons come in units, in order; a unit unlocks when the one before has been done once. */
    val unit: Int,
    val title: String,
    val about: String,
    val skill: VoiceSkill,
    val exerciseIds: List<String>,
    val minutes: Int,
)

/** The whole programme (GET /api/transition/voice/program). */
@Serializable
data class VoiceProgram(val lessons: List<VoiceLesson>, val exercises: List<VoiceExercise>, val health: List<String>, val daily: List<String>)

/** The person's plan (GET/PUT /api/transition/voice/plan). Target range in Hz; baseline from a first recording. */
@Serializable
data class VoicePlan(
    val goal: VoiceGoal = VoiceGoal.EXPLORING,
    val targetLowHz: Double? = null,
    val targetHighHz: Double? = null,
    /** Resonance to aim for, 0–100 (see the scale above). */
    val targetResonance: Double? = null,
    val baselinePitchHz: Double? = null,
    val baselineResonance: Double? = null,
    val baselineAt: Long? = null,
    val dailyMinutes: Int = 10,
    /** "HH:mm": a gentle reminder to practise. */
    val reminder: String? = null,
    /** ISO days of the week to rest (1 Monday … 7 Sunday). */
    val restDays: List<Int> = emptyList(),
)

/** How one exercise went. Everything is optional: only what the exercise measured. */
@Serializable
data class ExerciseResult(
    val exerciseId: String,
    val seconds: Int,
    val pitchAvgHz: Double? = null,
    val pitchMinHz: Double? = null,
    val pitchMaxHz: Double? = null,
    /** Share of voiced time inside the target range, 0–100. */
    val inRange: Int? = null,
    val resonance: Double? = null,
    val weight: Double? = null,
    val intonation: Double? = null,
    /** For MATCH: how many notes were matched within a semitone. */
    val matched: Int? = null,
    val of: Int? = null,
    /** How it felt, 1–5. */
    val feeling: Int? = null,
)

@Serializable
data class VoiceWeek(val weekStart: String, val minutes: Int, val sessions: Int, val pitchAvgHz: Double? = null, val inRange: Int? = null, val resonance: Double? = null)

/** GET /api/transition/voice/progress: where the person is in the programme, and how it's going. */
@Serializable
data class VoiceProgress(
    val plan: VoicePlan,
    val lessonsDone: List<String> = emptyList(),
    val nextLessonId: String? = null,
    val streakDays: Int = 0,
    val minutesThisWeek: Int = 0,
    val weeks: List<VoiceWeek> = emptyList(),
    /** Kind, plain lines: "In your range 64% of the time this week, up from 41%". */
    val highlights: List<String> = emptyList(),
    /** Set after a session with strain: a gentle note to rest, and to see someone if it keeps happening. */
    val restNote: String? = null,
)
