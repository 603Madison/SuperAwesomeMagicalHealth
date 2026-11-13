package com.health.app.ui.modules.transition.voice

import com.health.api.ExerciseKind
import com.health.api.VoiceExercise
import com.health.api.VoiceGoal
import com.health.api.VoiceLesson
import com.health.api.VoiceMeter
import com.health.api.VoicePlan
import com.health.api.VoiceProgram
import com.health.api.VoiceProgress
import com.health.api.VoiceSkill
import com.health.api.VoiceUnit
import com.health.api.VoiceWeek

/**
 * A small programme for tests and screenshots, in the shape of the server's catalogue: three units,
 * every kind of exercise, and exercises that suit particular goals.
 */
object VoiceFixtures {
    val exercises = listOf(
        VoiceExercise(
            "breath", "Easy breathing", VoiceSkill.WARM_UP, ExerciseKind.GUIDED,
            steps = listOf("Sit or stand comfortably, shoulders loose.", "Breathe in through your nose for four counts.", "Breathe out on a soft \"sss\" for eight."),
            seconds = 60,
        ),
        VoiceExercise(
            "hum", "Gentle hum", VoiceSkill.WARM_UP, ExerciseKind.HOLD,
            steps = listOf("Hum on \"mm\" at a comfortable pitch.", "Feel the buzz on your lips and nose."),
            seconds = 45, meters = listOf(VoiceMeter.PITCH, VoiceMeter.LOUDNESS),
        ),
        VoiceExercise(
            "sirens", "Sirens", VoiceSkill.PITCH, ExerciseKind.GLIDE,
            steps = listOf("Slide from low to high on \"ng\", like a siren.", "Keep it light; go only as far as is comfortable."),
            seconds = 60, meters = listOf(VoiceMeter.PITCH),
            tip = "If it catches or cracks, that's fine: glide back down and try again more gently.",
        ),
        VoiceExercise(
            "match", "Match the note", VoiceSkill.PITCH, ExerciseKind.MATCH,
            steps = listOf("Listen to the note.", "Hum it back and hold it until it's matched."),
            seconds = 90, meters = listOf(VoiceMeter.PITCH), notes = listOf(0, 2, 4, 2, 0, -2),
        ),
        VoiceExercise(
            "bright-vowels", "Bright vowels", VoiceSkill.RESONANCE, ExerciseKind.RESONANCE,
            steps = listOf("Say \"eee\" and hold it.", "Smile a little and feel the sound move forward."),
            seconds = 60, meters = listOf(VoiceMeter.RESONANCE, VoiceMeter.PITCH), goals = setOf(VoiceGoal.LIGHTER, VoiceGoal.EXPLORING),
        ),
        VoiceExercise(
            "dark-vowels", "Open, darker vowels", VoiceSkill.RESONANCE, ExerciseKind.RESONANCE,
            steps = listOf("Say \"aww\" with a relaxed, open throat.", "Feel the sound settle lower in your chest."),
            seconds = 60, meters = listOf(VoiceMeter.RESONANCE, VoiceMeter.PITCH), goals = setOf(VoiceGoal.DEEPER, VoiceGoal.EXPLORING),
        ),
        VoiceExercise(
            "phrases", "Everyday phrases", VoiceSkill.SPEECH, ExerciseKind.SAY,
            steps = listOf("Say each phrase at an easy pace.", "Keep the resonance you found."),
            seconds = 120, meters = listOf(VoiceMeter.PITCH, VoiceMeter.RESONANCE, VoiceMeter.WEIGHT),
            texts = listOf("Good morning, how are you?", "Could I get a coffee, please?", "Thanks so much, have a lovely day.", "I'll call you back in five minutes."),
        ),
        VoiceExercise(
            "melody", "Phrases with melody", VoiceSkill.INTONATION, ExerciseKind.SAY,
            steps = listOf("Let your pitch rise and fall with the meaning."),
            seconds = 90, meters = listOf(VoiceMeter.PITCH, VoiceMeter.INTONATION),
            texts = listOf("Really? That's wonderful!", "Would you like tea, or coffee?"),
        ),
        VoiceExercise(
            "read", "Read aloud", VoiceSkill.SPEECH, ExerciseKind.READ,
            steps = listOf("Read the passage at your usual pace.", "Breathe at the commas."),
            seconds = 120, meters = listOf(VoiceMeter.PITCH, VoiceMeter.RESONANCE),
            texts = listOf("When the sunlight strikes raindrops in the air, they act as a prism and form a rainbow. The rainbow is a division of white light into many beautiful colours."),
        ),
        VoiceExercise(
            "talk", "Talk about your day", VoiceSkill.SPEECH, ExerciseKind.SPEAK,
            steps = listOf("Talk freely, as if to a friend."),
            seconds = 90, meters = listOf(VoiceMeter.PITCH, VoiceMeter.RESONANCE, VoiceMeter.INTONATION, VoiceMeter.LOUDNESS),
            texts = listOf("What did you have for breakfast?", "Somewhere you'd love to visit"),
        ),
    )

    val lessons = listOf(
        VoiceLesson("u1-care", 1, "Starting well", "Breath, a warm-up, and how practice should feel.", VoiceSkill.HEALTH, listOf("breath", "hum"), 5),
        VoiceLesson("u1-pitch", 1, "Finding your range", "Gentle glides and matching notes, to get to know your range.", VoiceSkill.PITCH, listOf("hum", "sirens", "match"), 8),
        VoiceLesson("u2-resonance", 2, "Brighter and darker", "Hearing and feeling resonance change.", VoiceSkill.RESONANCE, listOf("hum", "bright-vowels", "dark-vowels"), 10),
        VoiceLesson("u2-fuller", 2, "Fuller and warmer", "Settling into a fuller, warmer sound.", VoiceSkill.WEIGHT, listOf("hum", "dark-vowels"), 8, goals = setOf(VoiceGoal.DEEPER)),
        VoiceLesson("u2-speech", 2, "Into words", "Taking what you've found into short phrases.", VoiceSkill.SPEECH, listOf("phrases", "melody"), 10),
        VoiceLesson("u3-talk", 3, "Everyday talk", "Reading and talking freely.", VoiceSkill.SPEECH, listOf("read", "talk"), 12),
    )

    val units = listOf(
        VoiceUnit(1, "Foundations", "Looking after your voice, and getting to know your range."),
        VoiceUnit(2, "Resonance", "Brighter and darker, and taking it into words."),
        VoiceUnit(3, "Everyday voice", "Reading aloud and talking freely."),
    )

    val program = VoiceProgram(
        lessons, exercises,
        health = listOf(
            "Little and often: ten minutes most days does more than an hour once a week.",
            "Warm up first, and keep water nearby.",
            "It should never hurt. If your throat feels tight, scratchy or tired, stop and rest.",
            "A speech therapist (SLP) is worth seeing for anything that worries you.",
        ),
        daily = listOf("Two minutes of easy breathing", "A gentle hum or lip trill", "One lesson, or a little free practice", "A sip of water, and rest"),
        units = units,
    )

    val plan = VoicePlan(
        goal = VoiceGoal.LIGHTER, targetLowHz = 165.0, targetHighHz = 220.0, targetResonance = 65.0,
        baselinePitchHz = 138.0, baselineResonance = 41.0, baselineAt = 1_760_000_000_000, dailyMinutes = 10, reminder = "18:30", restDays = listOf(7),
    )

    val progress = VoiceProgress(
        plan = plan,
        lessonsDone = listOf("u1-care", "u1-pitch"),
        nextLessonId = "u2-resonance",
        streakDays = 4,
        minutesThisWeek = 38,
        weeks = listOf(
            VoiceWeek("2026-08-24", 30, 3, 142.0, 18, 40.0),
            VoiceWeek("2026-08-31", 42, 4, 148.0, 24, 43.0),
            VoiceWeek("2026-09-07", 25, 3, 151.0, 29, 45.0),
            VoiceWeek("2026-09-14", 50, 5, 157.0, 35, 47.0),
            VoiceWeek("2026-09-21", 20, 2, 155.0, 31, 46.0),
            VoiceWeek("2026-09-28", 55, 6, 162.0, 42, 50.0),
            VoiceWeek("2026-10-05", 48, 5, 166.0, 48, 53.0),
            VoiceWeek("2026-10-12", 35, 4, 168.0, 51, 52.0),
            VoiceWeek("2026-10-19", 60, 6, 171.0, 58, 56.0),
            VoiceWeek("2026-10-26", 52, 5, 174.0, 61, 58.0),
            VoiceWeek("2026-11-02", 45, 5, 176.0, 64, 60.0),
            VoiceWeek("2026-11-09", 38, 4, 178.0, 66, 61.0),
        ),
        highlights = listOf("In your range 66% of the time this week, up from 18% in August.", "Four days in a row: lovely and steady."),
    )

    fun exercise(id: String) = exercises.single { it.id == id }
}
