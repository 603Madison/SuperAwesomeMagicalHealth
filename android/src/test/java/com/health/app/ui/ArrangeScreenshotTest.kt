package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.health.api.AiStatus
import com.health.api.CheckIn
import com.health.api.Contributor
import com.health.api.DaySummary
import com.health.api.Drink
import com.health.api.DrinkKind
import com.health.api.DrinksDay
import com.health.api.Goals
import com.health.api.Insight
import com.health.api.LayoutPrefs
import com.health.api.Layouts
import com.health.api.MindDay
import com.health.api.ModulesToday
import com.health.api.Module
import com.health.api.NavPage
import com.health.api.Nutrients
import com.health.api.NutritionTarget
import com.health.api.Profile
import com.health.api.Role
import com.health.api.ScreenLayout
import com.health.api.Score
import com.health.api.TodaySection
import com.health.api.Units
import com.health.api.User
import com.health.api.Workout
import com.health.api.WorkoutType
import com.health.app.data.Palette
import com.health.app.data.Settings
import com.health.app.data.ThemeMode
import com.health.app.ui.components.ArrangeContent
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.Backdrop
import com.health.app.ui.components.LocalLayoutStore
import com.health.app.ui.components.MemoryLayoutStore
import com.health.app.ui.settings.SettingsActions
import com.health.app.ui.settings.SettingsPage
import com.health.app.ui.settings.SettingsScreen
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.log.LogSheetActions
import com.health.app.ui.log.LogSheetContent
import com.health.app.ui.today.TodayActions
import com.health.app.ui.today.TodayLayout
import com.health.app.ui.today.TodayScreen
import com.health.app.ui.today.TodayState
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
 * Arranging screens, drawn to PNGs in android/build/outputs/screenshots (arrange_*): Today as it
 * comes and arranged; the Arrange sheet; the + sheet's grid in the person's order and arranging
 * it; Care arranged; and What you track. Light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ArrangeScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 11, 12)
    private fun at(h: Int, m: Int = 0): Long = today.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun render(dark: Boolean, layout: LayoutPrefs = LayoutPrefs(), content: @Composable () -> Unit) {
        val store = MemoryLayoutStore(layout)
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                CompositionLocalProvider(LocalLayoutStore provides store) { content() }
            }
        }
    }

    @Composable
    private fun Frame(tab: String, modules: Set<Module>, content: @Composable () -> Unit) {
        AppFrame(selected = tabOf(tab), snackbar = remember { SnackbarHostState() }, onTab = {}, content = content)
    }

    /** A sheet's content as it sits over the page: on the sheet's colour, rounded at the top. */
    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        Backdrop {
            Column(Modifier.fillMaxWidth().padding(top = 120.dp)) {
                Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(top = 28.dp)) { content() }
                }
            }
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

    // ---- Sample data: a day with scores and food, and features with nothing logged yet ----

    private val modules = Module.DEFAULT + setOf(Module.MIND, Module.MEDICATIONS, Module.VITALS, Module.HABITS, Module.SYMPTOMS)

    private val goals = Goals(steps = 8000, activeCalories = 450, sleepMinutes = 480, protein = 120, targetWeightKg = 65.0)

    private val summary = DaySummary(
        date = today.toString(),
        readiness = Score(84, "Good", listOf(Contributor("rhr", "Resting heart rate", 92, "51 bpm"))),
        sleep = Score(86, "Good", emptyList()),
        activity = Score(91, "Optimal", emptyList()),
        steps = 7214, distanceM = 5320.0, activeCalories = 462.0, restingCalories = 1420.0,
        restingHeartRate = 51.0, hrv = 48.0,
        workouts = listOf(Workout(WorkoutType.RUN, at(7, 10), at(7, 44), name = "Morning run", distanceM = 5120.0, activeCalories = 312.0)),
        nutrition = Nutrients(1240.0, 82.0, 131.0, 41.0),
        nutritionTarget = NutritionTarget(1950, 120, 210, 65, 2000, "Losing ¼ kg a week"),
        waterMl = 1250, weightKg = 66.4,
        drinks = DrinksDay(today.toString(), listOf(Drink(at(8, 5), DrinkKind.COFFEE, 240, caffeineMg = 96.0, id = "d1")), caffeineMg = 96.0, caffeineAtBedtimeMg = 18.0, fluidMl = 240),
        checkIn = CheckIn(today.toString(), mood = 4, energy = 3),
        insights = listOf(Insight("good", "Recovered well", "Your resting heart rate is 2 bpm below your usual and HRV is up.", "heart")),
        hourlySteps = listOf(0, 0, 0, 0, 0, 0, 120, 2800, 640, 310, 220, 180, 900, 410, 600, 30, 0, 0, 0, 0, 0, 0, 0, 0),
        goals = goals,
        hasData = true,
        modules = ModulesToday(mind = MindDay(today.toString(), mood = 4.0, entries = 1), habits = emptyList()),
    )

    private val state = TodayState(today, today, summary, loading = false)

    /** For you first, then the scores (an older app's cards in the saved order are left out); Your day hidden. Check in first in the + sheet. */
    private val arranged = LayoutPrefs(
        mapOf(
            Layouts.TODAY to ScreenLayout(
                listOf("FOOD", "INSIGHTS", "WATER", "QUICK", "MEDS", "SCORES", "UP_NEXT"),
                hidden = setOf(TodaySection.DAY.name, TodaySection.HEART.name),
            ),
            Layouts.QUICK to ScreenLayout(listOf("CHECK_IN", "FOOD", "COFFEE", "MOOD"), hidden = setOf("WATER", "DRINK", "SYMPTOM", "BLOOD_PRESSURE", "GLUCOSE", "HABIT", "CALM", "SLEEP", "DOSE")),
        ),
    )

    private fun today(dark: Boolean, name: String, layout: LayoutPrefs = LayoutPrefs()) {
        render(dark, layout) { Frame("today", modules) { TodayScreen(state, Units.METRIC, zone, TodayActions(), modules = modules) } }
        save(name)
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun todayDefaultLight() = today(false, "arrange_today_light")

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun todayDefaultDark() = today(true, "arrange_today_dark")

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun todayArrangedLight() {
        today(false, "arrange_today_arranged_light", arranged)
        val forYou = compose.onNodeWithText("For you").fetchSemanticsNode().boundsInRoot.top
        val scores = compose.onNodeWithText("Readiness", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue("For you is first", forYou < scores)
        org.junit.Assert.assertEquals(0, compose.onAllNodesWithText("Your day").fetchSemanticsNodes().size)
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun todayArrangedDark() = today(true, "arrange_today_arranged_dark", arranged)

    @Test
    fun todayNoScores() {
        val empty = DaySummary(date = today.toString(), goals = goals, nutritionTarget = NutritionTarget(2000, 110, 220, 67, 2000, "Your goal"), modules = ModulesToday(habits = emptyList()))
        render(false) { Frame("today", modules) { TodayScreen(TodayState(today, today, empty, loading = false), Units.METRIC, zone, TodayActions(), modules = modules) } }
        save("arrange_today_empty_light")
    }

    // ---- The Arrange sheet ----

    private val todayItems = TodayLayout.sections(modules).map { ArrangeItem(it.name, TodayLayout.label(it)) }

    private fun arrangeSheet(dark: Boolean, name: String) {
        render(dark, arranged) { Sheet { ArrangeContent(Layouts.TODAY, "Today", todayItems, {}) } }
        save(name)
    }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun arrangeSheetLight() = arrangeSheet(false, "arrange_sheet_light")

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun arrangeSheetDark() = arrangeSheet(true, "arrange_sheet_dark")

    // ---- The + sheet's grid, in the person's order, and arranging it (order only: everything tracked shows) ----

    private val quickModules = modules + setOf(Module.CYCLE, Module.CONTINENCE)

    private fun quick(dark: Boolean, name: String) {
        val available = TodayLayout.quickActions(quickModules)
        render(dark, arranged) {
            Sheet {
                LogSheetContent(emptyList(), "1:42 PM", null, "", TodayLayout.grid(available, arranged.screens[Layouts.QUICK]), Units.METRIC, LogSheetActions())
                Spacer(Modifier.height(24.dp))
                ArrangeContent(Layouts.QUICK, "the + sheet", available.map { ArrangeItem(it.name, it.label) }, {}, orderOnly = true)
            }
        }
        save(name)
        // Nothing can be hidden from the grid.
        org.junit.Assert.assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Hide", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun quickMoreLight() = quick(false, "arrange_plus_grid_light")

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun quickMoreDark() = quick(true, "arrange_plus_grid_dark")

    // ---- Care (until its own page), in the person's order (Layouts.NAV) ----

    private val healthModules = Module.DEFAULT + setOf(Module.MIND, Module.CYCLE, Module.MEDICATIONS, Module.HABITS, Module.SYMPTOMS, Module.PHOTOS)

    private val healthArranged = LayoutPrefs(
        mapOf(Layouts.NAV to ScreenLayout(listOf("TODAY", "MEDICATIONS", "HABITS", "MIND", NavPage.CONDITIONS.name), hidden = setOf("CYCLE", "PHOTOS"))),
    )

    private fun health(dark: Boolean, name: String) {
        render(dark, healthArranged) { Frame("care", healthModules) { CareTabPlaceholder(open = {}, modules = healthModules) } }
        save(name)
    }

    @Test
    fun healthArrangedLight() = health(false, "arrange_health_light")

    @Test
    fun healthArrangedDark() = health(true, "arrange_health_dark")

    // ---- What you track: features hidden from Care say so, and open from there ----

    private val settings = Settings(
        server = "https://health.home.arpa", token = "t", serverName = "Health at home",
        user = User("u1", "maddie", "Maddie", Role.ADMIN, createdAt = 0),
    )

    private fun settings(dark: Boolean, name: String, water: Boolean) {
        val profile = Profile(birthYear = 1994, heightCm = 170.0, timezone = "America/New_York", goals = goals, modules = healthModules)
        render(dark) {
            Frame("settings", healthModules) {
                SettingsScreen(SettingsPage.TRACKING, settings, profile, SettingsActions(), water = water, ai = AiStatus(available = true), hiddenFromHealth = setOf("CYCLE", "PHOTOS"))
            }
        }
        save(name)
    }

    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun settingsLight() = settings(false, "arrange_settings_light", water = true)

    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun settingsDark() = settings(true, "arrange_settings_dark", water = false)
}
