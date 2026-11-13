package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.health.api.ExerciseResult
import com.health.api.Module
import com.health.api.VoiceGoal
import com.health.api.VoicePlan
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.modules.transition.PitchPoint
import com.health.app.ui.modules.transition.voice.Baseline
import com.health.app.ui.modules.transition.voice.BaselineActions
import com.health.app.ui.modules.transition.voice.BaselinePhase
import com.health.app.ui.modules.transition.voice.BaselineRecorder
import com.health.app.ui.modules.transition.voice.MatchRound
import com.health.app.ui.modules.transition.voice.MatchUi
import com.health.app.ui.modules.transition.voice.MicState
import com.health.app.ui.modules.transition.voice.PlanActions
import com.health.app.ui.modules.transition.voice.PlanSettings
import com.health.app.ui.modules.transition.voice.PlanSetup
import com.health.app.ui.modules.transition.voice.PlanStep
import com.health.app.ui.modules.transition.voice.ProgressCard
import com.health.app.ui.modules.transition.voice.TrainerActions
import com.health.app.ui.modules.transition.voice.TrainerPhase
import com.health.app.ui.modules.transition.voice.TrainerScreen
import com.health.app.ui.modules.transition.voice.TrainerUi
import com.health.app.ui.modules.transition.voice.VoiceFixtures
import com.health.app.ui.modules.transition.voice.VoiceHome
import com.health.app.ui.modules.transition.voice.VoiceHomeActions
import com.health.app.ui.modules.transition.voice.VoiceLive
import com.health.app.ui.modules.transition.voice.VoiceState
import com.health.app.ui.modules.transition.voice.VoiceWords
import com.health.app.ui.theme.CalmLayer
import com.health.app.ui.theme.HealthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The voice trainer drawn with realistic data to PNGs: setting up the plan, the home, the trainer
 * for each kind of exercise, the end-of-exercise summary and progress, in light and dark. Tall
 * windows, so each page can be checked top to bottom.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h1500dp-xxhdpi")
class VoiceScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val program = VoiceFixtures.program
    private val plan = VoiceFixtures.plan
    private val progress = VoiceFixtures.progress
    private val today = LocalDate.of(2026, 11, 13)

    private fun render(dark: Boolean = false, calm: Boolean = false, fontScale: Float? = null, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(density.density, fontScale) else density) {
                HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                    if (calm) CalmLayer(true) { content() } else content()
                }
            }
        }
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        AppFrame(selected = "health", snackbar = remember { SnackbarHostState() }, onTab = {}, tabs = tabsFor(Module.entries.toSet()), content = content)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- Live readings ----

    /** Ten seconds of trace every 20 ms, from [hz] (null for a gap). */
    private fun trace(hz: (Int) -> Double?): List<PitchPoint> = (0 until 500).map { PitchPoint(it * 20L, hz(it)) }

    private fun live(trace: List<PitchPoint>, resonance: Double? = 58.0, weight: Double? = 38.0, intonation: Double? = 2.4, db: Double = -34.0, pushing: Boolean = false) =
        VoiceLive(true, trace.last().hz, resonance, weight, intonation, db, pushing, trace, 32.0)

    private val speechTrace = trace { i -> if ((i / 60) % 3 == 2 && i % 60 < 18) null else 182 * 2.0.pow((sin(i / 23.0) * 2.2 + sin(i / 7.0) * 0.6) / 12) }

    // ---- Setting up ----

    @Test
    fun planGoalLight() {
        render { Frame { PlanSetup(PlanStep.GOAL, VoicePlan(goal = VoiceGoal.EXPLORING), program.health, PlanActions()) {} } }
        save("voice_plan_goal_light")
    }

    @Test
    fun planGoalDark() {
        render(dark = true) { Frame { PlanSetup(PlanStep.GOAL, VoicePlan(goal = VoiceGoal.LIGHTER), program.health, PlanActions()) {} } }
        save("voice_plan_goal_dark")
    }

    @Test
    fun planHealth() {
        render { Frame { PlanSetup(PlanStep.HEALTH, plan, program.health, PlanActions()) {} } }
        save("voice_plan_health")
    }

    @Test
    fun planBaselineReading() {
        val reading = live(speechTrace).copy(pitchHz = 141.0)
        render {
            Frame {
                PlanSetup(PlanStep.BASELINE, plan, program.health, PlanActions()) {
                    BaselineRecorder(BaselinePhase.READING, reading, 20, null, MicState.ALLOWED, BaselineActions(skip = {}))
                }
            }
        }
        save("voice_plan_baseline_reading")
    }

    @Test
    fun planBaselineResultDark() {
        render(dark = true) {
            Frame {
                PlanSetup(PlanStep.BASELINE, plan, program.health, PlanActions()) {
                    BaselineRecorder(BaselinePhase.RESULT, VoiceLive(), 0, Baseline(138.0, 118.0, 164.0, 41.0, -37.0), MicState.ALLOWED, BaselineActions(skip = {}))
                }
            }
        }
        save("voice_plan_baseline_result_dark")
    }

    @Test
    fun planTargetsLight() {
        val draft = VoicePlan(goal = VoiceGoal.LIGHTER, baselinePitchHz = 138.0, baselineResonance = 41.0, reminder = "18:30", restDays = listOf(7))
        render { Frame { PlanSetup(PlanStep.TARGETS, draft, program.health, PlanActions()) {} } }
        save("voice_plan_targets_light")
    }

    @Test
    fun planTargetsDark() {
        val draft = VoicePlan(goal = VoiceGoal.DEEPER, targetLowHz = 105.0, targetHighHz = 140.0, targetResonance = 35.0, baselinePitchHz = 168.0, baselineResonance = 55.0)
        render(dark = true) { Frame { PlanSetup(PlanStep.TARGETS, draft, program.health, PlanActions()) {} } }
        save("voice_plan_targets_dark")
    }

    @Test
    fun planSettings() {
        render { Frame { PlanSettings(plan, keepRecordings = true, recordings = 6, actions = PlanActions()) } }
        save("voice_plan_settings")
    }

    // ---- Home ----

    private val state = VoiceState(program = program, plan = plan, progress = progress, loading = false)

    @Test
    fun homeLight() {
        render { Frame { VoiceHome(state, today, readOnly = false, recordings = 6, keepRecordings = true, actions = VoiceHomeActions()) } }
        save("voice_home_light")
    }

    @Test
    fun homeDark() {
        render(dark = true) { Frame { VoiceHome(state, today, readOnly = false, recordings = 0, keepRecordings = false, actions = VoiceHomeActions()) } }
        save("voice_home_dark")
    }

    @Test
    fun homeFirstRunCalm() {
        val fresh = VoiceState(program = program, plan = VoicePlan(), progress = null, loading = false)
        render(calm = true) { Frame { VoiceHome(fresh, today, readOnly = false, recordings = 0, keepRecordings = false, actions = VoiceHomeActions()) } }
        save("voice_home_first_run_calm")
    }

    @Test
    fun progressLight() {
        render { Frame { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { ProgressCard(plan, progress) } } }
        save("voice_progress_light")
    }

    @Test
    fun progressDark() {
        render(dark = true) { Frame { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { ProgressCard(plan, progress) } } }
        save("voice_progress_dark")
    }

    // ---- The trainer ----

    private val pitchLesson = program.lessons.single { it.id == "u1-pitch" }
    private val resonanceLesson = program.lessons.single { it.id == "u2-resonance" }
    private val speechLesson = program.lessons.single { it.id == "u2-speech" }

    private fun ui(lessonId: String, index: Int, phase: TrainerPhase = TrainerPhase.ACTIVE) = program.lessons.single { it.id == lessonId }.let { lesson ->
        TrainerUi(
            lesson = lesson,
            exercises = lesson.exerciseIds.map(VoiceFixtures::exercise).filter { it.goals.isEmpty() || plan.goal in it.goals },
            index = index, phase = phase, elapsed = 41, listened = true, startedAt = 1,
        )
    }

    private fun matchUi(): TrainerUi {
        val targets = MatchRound.targets(VoiceFixtures.exercise("match").notes, plan)
        return ui(pitchLesson.id, 2).copy(match = MatchUi(targets, index = 2, matched = listOf(true, true, false, false, false, false), progress = 0.6f))
    }

    private fun matchTrace(): List<PitchPoint> {
        val target = MatchRound.targets(VoiceFixtures.exercise("match").notes, plan)[2]
        return trace { i -> if (i in 150..215) null else target * 2.0.pow(((if (i < 300) -1.6 + i / 300.0 * 1.2 else -0.3) + sin(i / 9.0) * 0.15) / 12) }
    }

    @Test
    fun trainerMatchLight() {
        val ui = matchUi()
        render { Frame { TrainerScreen(ui, live(matchTrace()), plan, MicState.ALLOWED, TrainerActions()) } }
        save("voice_trainer_match_light")
    }

    @Test
    fun trainerMatchDark() {
        val ui = matchUi()
        render(dark = true) { Frame { TrainerScreen(ui, live(matchTrace()), plan, MicState.ALLOWED, TrainerActions()) } }
        save("voice_trainer_match_dark")
    }

    /** Sirens: low to high, a breath, again; caught partway up the last one. */
    private fun glideTrace() = trace { i ->
        val k = (i + 30) % 125
        if (k > 112) null else 130 * 2.0.pow((1 - kotlin.math.cos(k / 112.0 * PI)) / 2 * 15 / 12)
    }

    @Test
    fun trainerGlideLight() {
        render { Frame { TrainerScreen(ui(pitchLesson.id, 1), live(glideTrace()), plan, MicState.ALLOWED, TrainerActions()) } }
        save("voice_trainer_glide_light")
    }

    @Test
    fun trainerGlideDark() {
        render(dark = true) { Frame { TrainerScreen(ui(pitchLesson.id, 1), live(glideTrace()), plan, MicState.ALLOWED, TrainerActions()) } }
        save("voice_trainer_glide_dark")
    }

    @Test
    fun trainerResonanceLight() {
        val holding = trace { i -> if (i % 150 > 130) null else 188.0 + sin(i / 11.0) * 2 }
        render { Frame { TrainerScreen(ui(resonanceLesson.id, 1), live(holding, resonance = 58.0), plan, MicState.ALLOWED, TrainerActions()) } }
        save("voice_trainer_resonance_light")
    }

    @Test
    fun trainerResonanceDark() {
        val holding = trace { i -> if (i % 150 > 130) null else 188.0 + sin(i / 11.0) * 2 }
        render(dark = true) { Frame { TrainerScreen(ui(resonanceLesson.id, 1), live(holding, resonance = 71.0), plan, MicState.ALLOWED, TrainerActions()) } }
        save("voice_trainer_resonance_dark")
    }

    @Test
    fun trainerSayLight() {
        render { Frame { TrainerScreen(ui(speechLesson.id, 0).copy(textIndex = 1), live(speechTrace), plan, MicState.ALLOWED, TrainerActions()) } }
        save("voice_trainer_say_light")
    }

    @Test
    fun trainerSayDark() {
        render(dark = true) { Frame { TrainerScreen(ui(speechLesson.id, 0).copy(textIndex = 1), live(speechTrace), plan, MicState.ALLOWED, TrainerActions()) } }
        save("voice_trainer_say_dark")
    }

    @Test
    fun trainerSpeakLoudLargeText() {
        val talk = ui("u3-talk", 1)
        render(fontScale = 1.6f) { Frame { TrainerScreen(talk, live(speechTrace, intonation = 3.1, db = -21.0, pushing = true), plan, MicState.ALLOWED, TrainerActions(), speakingDb = -37.0) } }
        save("voice_trainer_speak_large_text")
    }

    @Test
    fun trainerIntroAskingForTheMicrophone() {
        render { Frame { TrainerScreen(ui(pitchLesson.id, 1, TrainerPhase.INTRO).copy(listened = false), VoiceLive(), plan, MicState.UNKNOWN, TrainerActions()) } }
        save("voice_trainer_intro_light")
    }

    @Test
    fun trainerIntroWithoutTheMicrophoneDark() {
        render(dark = true) { Frame { TrainerScreen(ui(pitchLesson.id, 2, TrainerPhase.INTRO).copy(listened = false), VoiceLive(), plan, MicState.DENIED, TrainerActions()) } }
        save("voice_trainer_intro_denied_dark")
    }

    // ---- After an exercise, and after a lesson ----

    private val bright = VoiceFixtures.exercise("bright-vowels")
    private val brightResult = ExerciseResult("bright-vowels", 64, pitchAvgHz = 186.0, pitchMinHz = 171.0, pitchMaxHz = 203.0, inRange = 72, resonance = 61.0, weight = 36.0)

    private fun summary(strain: Boolean?, feeling: Int?) = ui(resonanceLesson.id, 1, TrainerPhase.SUMMARY).copy(
        elapsed = 64, result = brightResult, summary = VoiceWords.summary(bright, brightResult, plan, 1), feeling = feeling, strain = strain,
        recording = File("build/tmp/voice-take.wav"),
    )

    @Test
    fun summaryLight() {
        render { Frame { TrainerScreen(summary(false, 4), VoiceLive(), plan, MicState.ALLOWED, TrainerActions(), baseline = File("build/tmp/voice-baseline.wav")) } }
        save("voice_summary_light")
    }

    @Test
    fun summaryStrainDark() {
        render(dark = true) { Frame { TrainerScreen(summary(true, 2), VoiceLive(), plan, MicState.ALLOWED, TrainerActions()) } }
        save("voice_summary_strain_dark")
    }

    @Test
    fun matchSummary() {
        val match = VoiceFixtures.exercise("match")
        val result = ExerciseResult("match", 88, pitchAvgHz = 192.0, inRange = 81, matched = 5, of = 6)
        val ui = ui(pitchLesson.id, 2, TrainerPhase.SUMMARY).copy(elapsed = 88, result = result, summary = VoiceWords.summary(match, result, plan, 2))
        render { Frame { TrainerScreen(ui, VoiceLive(), plan, MicState.ALLOWED, TrainerActions()) } }
        save("voice_summary_match")
    }

    @Test
    fun lessonFinished() {
        val results = listOf(
            ExerciseResult("hum", 47, pitchAvgHz = 176.0, inRange = 64, resonance = 52.0, feeling = 4),
            brightResult.copy(feeling = 4),
        )
        val ui = ui(resonanceLesson.id, 1, TrainerPhase.FINISHED).copy(results = results, saved = true)
        render(dark = true) { Frame { TrainerScreen(ui, VoiceLive(), plan, MicState.ALLOWED, TrainerActions()) } }
        save("voice_lesson_finished_dark")
    }
}
