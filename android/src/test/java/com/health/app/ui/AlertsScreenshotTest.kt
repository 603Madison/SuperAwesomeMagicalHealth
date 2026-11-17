package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.health.api.AlertFeedback
import com.health.api.AlertKind
import com.health.api.AlertLevel
import com.health.api.AlertReading
import com.health.api.AlertSettings
import com.health.api.AlertWatch
import com.health.api.AppPage
import com.health.api.DaySummary
import com.health.api.Goals
import com.health.api.HealthAlert
import com.health.api.HealthAlerts
import com.health.api.Insight
import com.health.api.Module
import com.health.api.Point
import com.health.api.Units
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.alerts.Alerts
import com.health.app.ui.alerts.AlertsActions
import com.health.app.ui.alerts.AlertsScreen
import com.health.app.ui.alerts.AlertsState
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.today.TodayActions
import com.health.app.ui.today.TodayScreen
import com.health.app.ui.today.TodayState
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
import kotlin.math.sin

/**
 * Health alerts on the phone: one that could need care now (what to do first, in the theme's
 * danger colours), one worth a doctor's look, one to keep an eye on, one put away; the empty page;
 * the settings; and Today's bell. PNGs in android/build/outputs/screenshots, light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class AlertsScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 11, 17)
    private fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun render(dark: Boolean = false, readOnly: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                CompositionLocalProvider(LocalReadOnly provides readOnly) { content() }
            }
        }
    }

    @Composable
    private fun Frame(tab: String, content: @Composable () -> Unit) {
        AppFrame(selected = tab, snackbar = remember { SnackbarHostState() }, onTab = {}, tabs = tabsFor(Module.DEFAULT), content = content)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- Sample alerts, as the server words them ----

    private val emergency = "If you have chest pain or pressure, feel faint, are very short of breath, or your heart is racing and won't settle, call your local emergency number now."

    private val urgent = HealthAlert(
        "a-urgent", AlertKind.IRREGULAR_RHYTHM, AlertLevel.URGENT, "Your strap picked up a fast, irregular rhythm",
        "For about 25 min, from 2:10 AM to 2:35 AM, the time between beats looked irregular (in 4 of 5 five-minute stretches), with your heart rate at 118–146 bpm. A loose strap or moving about can look like this too.",
        "$emergency If you feel well, call a doctor or a nurse line today.",
        evidence = listOf(AlertReading("Irregular stretches", "4 of 5"), AlertReading("For about", "25 min"), AlertReading("Heart rate", "118–146 bpm")),
        start = at(today, 2, 10), end = at(today, 2, 35), devices = listOf("Polar H10 A1B2C3D4"), createdAt = at(today, 2, 40),
    )

    private val doctor = HealthAlert(
        "a-doctor", AlertKind.OXYGEN_DIPS, AlertLevel.DOCTOR, "Your blood oxygen dipped on 3 nights this week",
        "11 of 64 readings last night were 90% or under (lowest 86%).",
        "Repeated dips while asleep can be a sign of sleep apnea, which is common and treatable: worth asking a doctor about, more so if you snore, wake up tired or with headaches.",
        evidence = listOf(AlertReading("Lowest", "86%"), AlertReading("Low readings", "11 of 64"), AlertReading("Other nights this week", "2")),
        start = at(today.minusDays(1), 23, 30), end = at(today, 6, 40), devices = listOf("Colmi R02"), createdAt = at(today, 7, 5),
    )

    private val watch = HealthAlert(
        "a-watch", AlertKind.GETTING_SICK, AlertLevel.WATCH, "Your body may be fighting something off",
        "Last night: resting heart rate up 6 bpm, HRV down 22%, temperature up 0.4 °C. Together, these often show a day or two before a cold or another bug.",
        "Take it easy if you can: rest, fluids and an earlier night. Alcohol, a late meal, a hard workout or stress can do the same, so see how you feel.",
        evidence = listOf(AlertReading("Resting heart rate", "+6 bpm"), AlertReading("HRV", "−22%"), AlertReading("Temperature", "+0.4 °C")),
        start = at(today.minusDays(1), 23, 0), end = at(today, 7, 0), devices = listOf("Colmi R02", "Polar H10 A1B2C3D4"), createdAt = at(today, 7, 10),
    )

    private val away = HealthAlert(
        "a-away", AlertKind.HIGH_HEART_RATE, AlertLevel.WATCH, "Your heart rate stayed high at rest",
        "It was 104–118 bpm (mostly around 110) for 12 min, from 6:20 PM to 6:32 PM, while you were sitting still.",
        "If it happens again without a reason you know of, it's worth telling a doctor.",
        evidence = listOf(AlertReading("Highest", "118 bpm"), AlertReading("For", "12 min")),
        start = at(today.minusDays(2), 18, 20), end = at(today.minusDays(2), 18, 32), devices = listOf("Colmi R02"), createdAt = at(today.minusDays(2), 18, 40),
        seenAt = at(today.minusDays(2), 19, 0), dismissedAt = at(today.minusDays(2), 19, 1), feedback = AlertFeedback.ACTIVE,
    )

    private val watching = listOf(
        AlertWatch("Heart rate at rest, high and low", "From Colmi R02 and Polar H10 A1B2C3D4", true),
        AlertWatch("Heart rhythm", "From Polar H10 A1B2C3D4, while it records", true),
        AlertWatch("Blood oxygen", "From Colmi R02", true),
        AlertWatch("Signs of getting ill", "Learning your usual: 4 more nights", false),
    )

    private val limits = "These are a heads-up from consumer devices, not a diagnosis, and they can't catch everything: a ring or watch can't tell your heart's rhythm, " +
        "and a quiet day here doesn't mean all is well. If you feel unwell, trust that over any reading, and if it could be an emergency, call your local emergency number."

    private fun state(alerts: List<HealthAlert>, settings: AlertSettings = AlertSettings(), watching: List<AlertWatch> = this.watching): AlertsState {
        val data = HealthAlerts(alerts, settings, watching, limits, at(today, 9))
        return AlertsState(data, Alerts.newestFirst(alerts), fresh = Alerts.unseenIds(alerts), loading = false)
    }

    private val full get() = state(listOf(watch, doctor, urgent, away))

    private fun alerts(dark: Boolean, name: String, s: AlertsState = full) {
        render(dark) { AlertsScreen(s, zone, AlertsActions(), today) }
        save(name)
    }

    @Test
    @Config(qualifiers = "w411dp-h3200dp-xxhdpi")
    fun alertsLight() {
        alerts(false, "alerts_light")
        // The urgent one first, leading with what to do; the newer, milder ones below it.
        compose.onNodeWithText("What to do now").assertExists()
        compose.onNodeWithText("Put away: I was active").assertExists()
    }

    @Test
    @Config(qualifiers = "w411dp-h3200dp-xxhdpi")
    fun alertsDark() = alerts(true, "alerts_dark")

    @Test
    fun alertsTopLight() = alerts(false, "alerts_top_light")

    @Test
    fun alertsTopDark() = alerts(true, "alerts_top_dark")

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun alertsEmptyLight() = alerts(false, "alerts_empty_light", state(emptyList()))

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun alertsEmptyDark() = alerts(true, "alerts_empty_dark", state(emptyList()))

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun alertsOff() = alerts(false, "alerts_off", state(emptyList(), AlertSettings(enabled = false), watching.map { it.copy(watching = false) }))

    @Test
    fun alertsPutAwayMenu() {
        render { AlertsScreen(state(listOf(doctor)), zone, AlertsActions(), today) }
        compose.onNodeWithText("Put away").performClick()
        compose.onNodeWithText("The device slipped or misread").assertExists()
        compose.onNodeWithText("Put it away without saying").assertExists()
        save("alerts_put_away_menu")
    }

    @Test
    fun alertsOnlyYourOwn() {
        render(readOnly = true) { AlertsScreen(full, zone, AlertsActions(), today) }
        compose.onNodeWithText("Only for your own data").assertExists()
        save("alerts_viewing_share")
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun alertsSettingsChanged() {
        val settings = state(emptyList()).copy()
        render(dark = true) { AlertsScreen(settings, zone, AlertsActions(), today) }
        compose.onNodeWithContentDescription("Higher: High heart rate at rest").performClick()
        compose.onNodeWithText("125").assertExists()
        compose.onNodeWithText("Undo changes").assertExists()
        save("alerts_settings_dark")
    }

    // ---- Today's bell ----

    private val summary = DaySummary(
        date = today.toString(),
        steps = 4120, restingHeartRate = 58.0, avgHeartRate = 71.0, hrv = 37.0, spo2 = 96.0,
        heartRate = (0..110).map { i -> Point(at(today, 0) + i * 5 * 60_000L, (if (i < 78) 60.0 else 70.0) + 6 * sin(i / 9.0)) },
        goals = Goals(steps = 8000),
        insights = listOf(
            Insight("alert", urgent.title, "For about 25 min, from 2:10 AM to 2:35 AM, the time between beats looked irregular (in 4 of 5 five-minute stretches), with your heart rate at 118–146 bpm.", "heart-pulse", link = AppPage.ALERTS),
            Insight("warn", watch.title, "Last night: resting heart rate up 6 bpm, HRV down 22%, temperature up 0.4 °C.", "thermometer", link = AppPage.ALERTS),
        ),
        hasData = true,
    )

    private fun todayBell(dark: Boolean, name: String) {
        render(dark) { Frame("today") { TodayScreen(TodayState(today, today, summary, loading = false), Units.METRIC, zone, TodayActions(), alerts = 3) } }
        compose.onNodeWithContentDescription("3 new health alerts").assertExists()
        save(name)
    }

    @Test
    fun todayBellLight() = todayBell(false, "alerts_today_bell_light")

    @Test
    fun todayBellDark() = todayBell(true, "alerts_today_bell_dark")

    @Test
    fun noBellWithNothingNewOrWhileViewingAShare() {
        render(readOnly = true) { Frame("today") { TodayScreen(TodayState(today, today, summary, loading = false), Units.METRIC, zone, TodayActions(), alerts = 3) } }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("3 new health alerts")).fetchSemanticsNodes().size)
    }

    @Test
    fun noBellWithNothingNew() {
        render { Frame("today") { TodayScreen(TodayState(today, today, summary, loading = false), Units.METRIC, zone, TodayActions(), alerts = 0) } }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("new health alert", substring = true)).fetchSemanticsNodes().size)
    }

    // ---- The danger colours' contrast ----

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun theUrgentBoxReadsWellInEveryThemeLightAndDark() {
        val schemes = HashMap<Pair<Palette, Boolean>, ColorScheme>()
        compose.setContent {
            Palette.entries.forEach { palette ->
                listOf(false, true).forEach { dark ->
                    HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = palette) {
                        val scheme = MaterialTheme.colorScheme
                        SideEffect { schemes[palette to dark] = scheme }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(Palette.entries.size * 2, schemes.size)
        schemes.forEach { (key, s) ->
            val (palette, dark) = key
            val where = "$palette ${if (dark) "dark" else "light"}"
            // The advice's words and icon on the box: AAA for text (7:1).
            assertTrue("$where: ${contrast(s.onErrorContainer, s.errorContainer)}", contrast(s.onErrorContainer, s.errorContainer) >= 7.0)
            // "Could need care now" in the error colour on its tinted pill, on the card: AA (4.5:1).
            val pill = s.error.copy(alpha = 0.14f).compositeOver(s.surfaceContainer)
            assertTrue("$where badge: ${contrast(s.error, pill)}", contrast(s.error, pill) >= 4.5)
        }
    }
}
