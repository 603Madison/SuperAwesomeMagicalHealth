package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.health.api.BreathPacing
import com.health.api.Insight
import com.health.api.Meditation
import com.health.api.MeditationKind
import com.health.api.MeditationSession
import com.health.api.MeditationSettings
import com.health.api.MeditationStep
import com.health.api.MeditationSummary
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.meditation.BreathPhase
import com.health.app.ui.meditation.MeditationActions
import com.health.app.ui.meditation.MeditationPageState
import com.health.app.ui.meditation.MeditationPlayerScreen
import com.health.app.ui.meditation.MeditationScreen
import com.health.app.ui.meditation.Night
import com.health.app.ui.meditation.PacerPoint
import com.health.app.ui.meditation.PlayerActions
import com.health.app.ui.meditation.PlayerPhase
import com.health.app.ui.meditation.PlayerState
import com.health.app.ui.meditation.VoiceMode
import com.health.app.ui.theme.HealthTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Guided meditation: the Meditation page (light and dark), and the player, always dark and dim, in
 * the middle of a step, in a breathing step (and still, for less motion), without an offline voice,
 * and at the end asking how it felt. PNGs in android/build/outputs/screenshots as meditation_*.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class MeditationScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 11, 25)
    private fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private val abouts = mapOf(
        MeditationKind.WIND_DOWN to "Setting the day down, softening, and settling for sleep.",
        MeditationKind.BODY_SCAN to "Noticing each part of you in turn, without needing to change a thing.",
        MeditationKind.BREATHING to "Easy breaths, a little longer out than in, with a pacer to follow.",
        MeditationKind.DRIFTING_OFF to "Gentle pictures to drift into sleep with, like a story with no plot.",
        MeditationKind.GRATITUDE to "Noticing something from today that was OK, or simply steady.",
    )
    private val meditations = MeditationKind.entries.flatMap { k ->
        listOf(5, 10, 15).map { min -> Meditation("${k.name.lowercase().replace('_', '-')}-$min", k.label, abouts.getValue(k), min, k, listOf(MeditationStep("Welcome.", 5))) }
    }

    private fun session(id: String, day: LocalDate, h: Int, m: Int, minutes: Double, kind: MeditationKind, finished: Boolean = true, felt: Int? = null) = MeditationSession(
        at(day, h, m), minutes, finished, "${kind.name.lowercase().replace('_', '-')}-10", felt, id, day.toString(), kind.label, kind,
    )

    private val sessions = listOf(
        session("s1", today.minusDays(1), 21, 42, 10.2, MeditationKind.BREATHING, felt = 4),
        session("s2", today.minusDays(2), 22, 5, 4.4, MeditationKind.WIND_DOWN, finished = false),
        session("s3", today.minusDays(4), 21, 30, 15.1, MeditationKind.BODY_SCAN, felt = 5),
        session("s4", today.minusDays(9), 23, 0, 9.8, MeditationKind.DRIFTING_OFF),
    )

    private val summary = MeditationSummary(
        days = 30, sessions = 4, finished = 3, minutes = 39.5, weekMinutes = 29.7, daysMeditated = 4, lastMeditationId = "breathing-10",
        insights = listOf(
            Insight(
                "info", "Sleep after meditating",
                "On the 6 nights after an evening meditation, your sleep score averaged 81, against 74 on the 9 other nights. That's what happened, not proof of why.",
                module = com.health.api.Module.MIND,
            ),
        ),
    )

    private val page = MeditationPageState(
        meditations = meditations, summary = summary, sessions = sessions,
        settings = MeditationSettings(reminder = true, time = "21:30"), loading = false,
    )

    private fun renderPage(dark: Boolean, state: MeditationPageState = page) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                AppFrame(selected = tabOf("meditation"), snackbar = remember { SnackbarHostState() }, onTab = {}) {
                    MeditationScreen(state, zone, today, readOnly = false, MeditationActions())
                }
            }
        }
    }

    private fun renderPlayer(state: PlayerState?, still: Boolean = false, askFelt: Boolean = false, actions: PlayerActions = PlayerActions()) {
        compose.setContent { Player { MeditationPlayerScreen(state, still, askFelt, actions) } }
    }

    @Composable
    private fun Player(content: @Composable () -> Unit) {
        HealthTheme(mode = ThemeMode.DARK, palette = Palette.AURORA) {
            MaterialTheme(colorScheme = Night.colors, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) { content() }
        }
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shows(text: String) = assertTrue("Shows $text", compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty())

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun pageLight() {
        renderPage(dark = false)
        shows("Meditation")
        shows("30 min")
        shows("On 3 days")
        shows("Sleep after meditating")
        // The one used last first, at its length.
        val titles = compose.onAllNodesWithText("Slow breathing").fetchSemanticsNodes().size
        assertTrue(titles >= 2)
        shows("Yesterday, 9:42")
        shows("Stopped partway")
        shows("Remind me to wind down")
        shows("9:30")
        compose.onNodeWithContentDescription("Back").assertExists()
        save("meditation_page_light")
    }

    @Test
    fun pageDarkTop() {
        renderPage(dark = true)
        save("meditation_page_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun pageDarkFull() {
        renderPage(dark = true, page.copy(summary = summary.copy(insights = emptyList(), weekMinutes = 0.0), sessions = emptyList(), settings = MeditationSettings()))
        shows("Nothing yet this week")
        assertEquals(0, compose.onAllNodesWithText("Sleep after meditating").fetchSemanticsNodes().size)
        shows("Nothing here yet")
        save("meditation_page_dark_empty")
    }

    private val playing = PlayerState(
        "wind-down-10", "Wind down", 10, PlayerPhase.PLAYING, step = 3,
        say = "If something from today comes to mind, you can say to it, quietly: yes, that happened. Then set it down for now.",
        secondsLeft = 412.0, startedAt = at(today, 21, 34), activeMs = 188_000,
    )

    @Test
    fun playerMidStep() {
        renderPlayer(playing)
        shows("Wind down")
        shows("6:52 left")
        shows("yes, that happened")
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithContentDescription("Stop and go back").assertExists()
        shows("Then fade out")
        save("meditation_player_step")
    }

    private val breathing = PlayerState(
        "breathing-10", "Slow breathing", 10, PlayerPhase.PLAYING, step = 2,
        say = "Breathe in for four, and out for six. Follow the circle as it grows and shrinks.",
        breath = PacerPoint(3, BreathPhase.IN, 0.7f, 4), secondsLeft = 455.0, startedAt = at(today, 21, 40), activeMs = 140_000,
    )

    @Test
    fun playerBreathing() {
        renderPlayer(breathing)
        shows("Breathe in")
        shows("Follow the circle")
        save("meditation_player_breathing")
    }

    @Test
    fun playerBreathingStill() {
        renderPlayer(breathing.copy(breath = PacerPoint(3, BreathPhase.OUT, 0.3f, 6), phase = PlayerPhase.PAUSED, voice = VoiceMode.NO_OFFLINE_VOICE), still = true)
        shows("Breathe out")
        shows("Paused")
        shows("no offline voice")
        compose.onNodeWithContentDescription("Resume").assertExists()
        save("meditation_player_still_no_voice")
    }

    @Test
    fun playerEndAsksHowItFelt() {
        var felt: Int? = null
        renderPlayer(playing.copy(phase = PlayerPhase.ENDED, finished = true, activeMs = 600_000), askFelt = true, actions = PlayerActions(felt = { felt = it }))
        shows("Rest well.")
        shows("How do you feel?")
        shows("Very settled")
        shows("Skip")
        save("meditation_player_end")
        compose.onNodeWithText("Settled").performClick()
        assertEquals(4, felt)
    }

    @Test
    fun playerFadedOut() {
        renderPlayer(playing.copy(phase = PlayerPhase.ENDED, finished = true, fadeOut = true, activeMs = 600_000), askFelt = true)
        assertEquals(0, compose.onAllNodesWithText("How do you feel?").fetchSemanticsNodes().size)
        save("meditation_player_faded")
    }
}
