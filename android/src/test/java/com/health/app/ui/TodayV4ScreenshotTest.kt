package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.health.api.AppPage
import com.health.api.DaySummary
import com.health.api.DueDose
import com.health.api.FoodEntry
import com.health.api.FoodSuggestion
import com.health.api.Goals
import com.health.api.Insight
import com.health.api.LayoutPrefs
import com.health.api.Meal
import com.health.api.MedForm
import com.health.api.MedsDay
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.NeuroToday
import com.health.api.Nutrients
import com.health.api.NutritionTarget
import com.health.api.Supply
import com.health.api.SupplyKind
import com.health.api.SupplyStatus
import com.health.api.TodaySection
import com.health.api.ContinenceDay
import com.health.api.TransitionToday
import com.health.api.Units
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.Backdrop
import com.health.app.ui.components.LocalLayoutStore
import com.health.app.ui.components.MemoryLayoutStore
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.today.LogSheetContent
import com.health.app.ui.today.TodayActions
import com.health.app.ui.today.TodayLayout
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

/**
 * Today, redesigned (v4): scores as a slim strip or one quiet line, Up next, For you, the Quick
 * log row, a card for each feature with something today and Also today for the rest, and + Log.
 * A busy day like the owner's, a quiet first day, water off in eating disorder recovery, and the
 * + Log sheet; light and dark, at 411 dp and at 360 dp with text at 130%. Drawn to PNGs in
 * android/build/outputs/screenshots as today_v4_*.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h2200dp-xxhdpi")
class TodayV4ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val today: LocalDate = LocalDate.of(2026, 11, 13)
    private fun at(h: Int, m: Int = 0): Long = today.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(14, 20)

    // ---- A busy day, like the owner's ----

    private val busyModules = Module.DEFAULT + setOf(
        Module.MEDICATIONS, Module.MIND, Module.CYCLE, Module.CONTINENCE, Module.NEURO, Module.TRANSITION, Module.HABITS, Module.SYMPTOMS,
    )

    private fun entry(meal: Meal, name: String, kcal: Double, protein: Double, serving: String = "1 serving") =
        FoodEntry(today.toString(), meal, name, Nutrients(kcal, protein), serving = serving, foodId = name.lowercase())

    private val ideas = listOf(
        FoodSuggestion(
            "Chicken stir-fry and rice", null, "1 meal", 1.0, Nutrients(650.0, 38.0), Meal.DINNER,
            listOf(entry(Meal.DINNER, "Chicken stir-fry", 450.0, 32.0), entry(Meal.DINNER, "Rice", 200.0, 6.0)), "You had this for dinner on Tuesday", 1, "2026-11-10",
        ),
        FoodSuggestion("Greek yoghurt", null, "1 pot", 1.0, Nutrients(150.0, 17.0), Meal.DINNER, listOf(entry(Meal.DINNER, "Greek yoghurt", 150.0, 17.0, "1 pot")), "One of your regulars", 9, "2026-11-12"),
        FoodSuggestion("Lentil soup", null, "1 bowl", 1.0, Nutrients(320.0, 18.0), Meal.DINNER, listOf(entry(Meal.DINNER, "Lentil soup", 320.0, 18.0, "1 bowl")), "You had this last week", 2, "2026-11-03"),
    )

    private val busy = DaySummary(
        date = today.toString(),
        nutrition = Nutrients(1050.0, 37.0, 140.0, 38.0),
        nutritionTarget = NutritionTarget(1900, 130, 220, 63, 2000, "Your goal"),
        goals = Goals(steps = 8000),
        hasData = true,
        insights = listOf(
            Insight(
                "info", "2 doses still to take", "Progesterone, Estradiol", "pill", Module.MEDICATIONS, link = AppPage.MEDS, shownBy = TodaySection.MEDS,
            ),
            Insight(
                "info", "Protein 93 g under target",
                "For dinner, the chicken stir-fry and rice you had Tuesday (38 g protein) or Greek yoghurt (17 g) would close a good part of it.",
                "egg", link = AppPage.FOOD, suggestions = ideas,
            ),
            Insight("info", "Time for some water", "0 ml so far of your 2000 ml.", "droplet", link = AppPage.FOOD),
        ),
        modules = ModulesToday(
            meds = MedsDay(
                today.toString(),
                due = listOf(
                    DueDose("m1", "Progesterone", "100 mg", MedForm.PILL, at(8)),
                    DueDose("m2", "Estradiol", "2 mg", MedForm.PILL, at(14)),
                ),
            ),
            continence = ContinenceDay(today.toString(), voids = 4, nightVoids = 1),
            neuro = NeuroToday(routinesDue = listOf("Evening wind-down")),
            transition = TransitionToday(headline = "1 year, 3 months since you began", euphoriaThisWeek = 2),
            lowSupplies = listOf(SupplyStatus(Supply("Pads", SupplyKind.PAD, absorbency = "Maxi", onHand = 4, id = "pads"), daysLeft = 2, low = true)),
            habits = emptyList(),
        ),
    )

    // ---- A quiet first day ----

    private val quiet = DaySummary(date = today.toString(), goals = Goals(), nutritionTarget = NutritionTarget(2000, 110, 220, 67, 2000, "Your goal"))

    // ---- Water off, in eating disorder recovery ----

    private val recoveryModules = Module.DEFAULT + Module.MIND

    private val recovery = DaySummary(
        date = today.toString(),
        steps = 4210, distanceM = 3100.0, activeCalories = 180.0,
        nutrition = Nutrients(980.0, 41.0, 120.0, 30.0),
        nutritionTarget = NutritionTarget(2100, 110, 240, 70, 2000, "Your goal"),
        weightKg = 58.2, goals = Goals(steps = 7000, targetWeightKg = 60.0),
        hasData = true,
        insights = listOf(Insight("info", "A gentle walk could help", "Even ten minutes outside can lift the afternoon.", "footprints", link = AppPage.ACTIVITY)),
        modules = ModulesToday(mind = com.health.api.MindDay(today.toString(), mood = 4.0, entries = 2, mindfulMinutes = 10.0)),
    )

    // ---- Drawing ----

    private fun render(dark: Boolean, fontScale: Float? = null, readOnly: Boolean = false, modules: Set<Module> = busyModules, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(density.density, fontScale) else density) {
                HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                    CompositionLocalProvider(LocalLayoutStore provides remember { MemoryLayoutStore(LayoutPrefs()) }, LocalReadOnly provides readOnly) {
                        AppFrame(selected = "today", snackbar = remember { SnackbarHostState() }, onTab = {}, tabs = tabsFor(modules), content = content)
                    }
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

    @Composable
    private fun Busy(actions: TodayActions = TodayActions()) =
        TodayScreen(TodayState(today, today, busy, loading = false), Units.METRIC, zone, actions, modules = busyModules, now = now)

    @Composable
    private fun Quiet() = TodayScreen(TodayState(today, today, quiet, loading = false), Units.METRIC, zone, TodayActions(), modules = Module.DEFAULT, now = now)

    @Composable
    private fun Recovery() = TodayScreen(
        TodayState(today, today, recovery, loading = false, meals = listOf(Meal.BREAKFAST, Meal.LUNCH)), Units.METRIC, zone, TodayActions(),
        modules = recoveryModules, water = false, foodNumbers = false, now = now,
    )

    /** The + Log sheet's content, drawn as the sheet shows it. */
    @Composable
    private fun LogSheet() {
        Backdrop {
            Column(Modifier.fillMaxWidth().padding(top = 96.dp)) {
                Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(top = 28.dp)) {
                        val available = TodayLayout.quickActions(busyModules)
                        LogSheetContent(TodayLayout.logGroups(available, null), Units.METRIC, {}, {})
                    }
                }
            }
        }
    }

    // ---- The busy day ----

    @Test
    fun busyLight() {
        render(false) { Busy() }
        // Up next has the doses, so For you doesn't repeat them.
        compose.onNodeWithContentDescription("Take Progesterone").assertHasClickAction()
        assertEquals(0, compose.onAllNodesWithText("2 doses still to take").fetchSemanticsNodes().size)
        compose.onNodeWithText("None set up yet").assertExists()
        save("today_v4_busy_light")
    }

    @Test
    fun busyDark() {
        render(true) { Busy() }
        save("today_v4_busy_dark")
    }

    /** What a glance sees first, on a phone's own screen. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun busyFoldLight() {
        render(false) { Busy() }
        save("today_v4_busy_fold_light")
    }

    @Test
    @Config(qualifiers = "w360dp-h3200dp-xxhdpi")
    fun busySmallLargeTextLight() {
        render(false, fontScale = 1.3f) { Busy() }
        quickLogFits(360)
        save("today_v4_busy_360_large_text_light")
    }

    @Test
    @Config(qualifiers = "w360dp-h3200dp-xxhdpi")
    fun busySmallLargeTextDark() {
        render(true, fontScale = 1.3f) { Busy() }
        quickLogFits(360)
        save("today_v4_busy_360_large_text_dark")
    }

    /** Viewing someone's share: no + Log, no Quick log, no buttons in Up next or Also today. */
    @Test
    fun busyViewingLight() {
        render(false, readOnly = true) { Busy() }
        assertEquals(0, compose.onAllNodesWithText("Log", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithContentDescription("Take Progesterone").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithContentDescription("Log your mood").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithContentDescription("More ways to log").fetchSemanticsNodes().size)
        save("today_v4_busy_viewing_light")
    }

    /** Up next's buttons do what they say; + Log and More open every way to log. */
    @Test
    fun actionsWork() {
        val taken = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val opened = mutableListOf<String>()
        render(false) { Busy(TodayActions(takeDose = { d, _ -> taken += d.name }, skipDose = { skipped += it.name }, open = { opened += it })) }
        compose.onNodeWithContentDescription("Take Progesterone").performClick()
        compose.onNodeWithContentDescription("Skip Estradiol").performClick()
        compose.onNodeWithContentDescription("Restock Pads (Maxi)").performClick()
        compose.onNodeWithContentDescription("Start Evening wind-down").performClick()
        assertEquals(listOf("Progesterone"), taken)
        assertEquals(listOf("Estradiol"), skipped)
        assertEquals(listOf("supplies/continence", "neuro/routines"), opened)
        // Quick log: five and More.
        listOf("Log food", "Add 250 ml of water", "Log your mood", "Log your period", "Log bladder and bowel", "More ways to log").forEach {
            compose.onAllNodesWithContentDescription(it).onFirst().assertHasClickAction()
        }
        compose.onNodeWithText("Log", useUnmergedTree = true).performClick()
        compose.onNodeWithText("How you feel").assertExists()
        compose.onNodeWithText("Choose what's in Quick log").assertExists()
    }

    // ---- A quiet first day ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun quietLight() {
        render(false, modules = Module.DEFAULT) { Quiet() }
        compose.onNodeWithText("Scores appear after a night with your ring or watch").assertExists()
        save("today_v4_quiet_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun quietDark() {
        render(true, modules = Module.DEFAULT) { Quiet() }
        save("today_v4_quiet_dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun quietSmallLargeTextLight() {
        render(false, fontScale = 1.3f, modules = Module.DEFAULT) { Quiet() }
        quickLogFits(360)
        save("today_v4_quiet_360_large_text_light")
    }

    @Test
    @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun quietSmallLargeTextDark() {
        render(true, fontScale = 1.3f, modules = Module.DEFAULT) { Quiet() }
        save("today_v4_quiet_360_large_text_dark")
    }

    // ---- Water off, in eating disorder recovery ----

    @Test
    fun recoveryLight() {
        render(false, modules = recoveryModules) { Recovery() }
        // No calories, macros or targets anywhere; the meals logged instead.
        assertEquals(0, compose.onAllNodesWithText("kcal", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("goal", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Breakfast, lunch logged").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Water", substring = true).fetchSemanticsNodes().size)
        save("today_v4_recovery_water_off_light")
    }

    @Test
    fun recoveryDark() {
        render(true, modules = recoveryModules) { Recovery() }
        save("today_v4_recovery_water_off_dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h1800dp-xxhdpi")
    fun recoverySmallLargeTextLight() {
        render(false, fontScale = 1.3f, modules = recoveryModules) { Recovery() }
        save("today_v4_recovery_water_off_360_large_text_light")
    }

    @Test
    @Config(qualifiers = "w360dp-h1800dp-xxhdpi")
    fun recoverySmallLargeTextDark() {
        render(true, fontScale = 1.3f, modules = recoveryModules) { Recovery() }
        save("today_v4_recovery_water_off_360_large_text_dark")
    }

    // ---- The + Log sheet ----

    @Test
    @Config(qualifiers = "w411dp-h1200dp-xxhdpi")
    fun logSheetLight() {
        render(false) { LogSheet() }
        save("today_v4_log_sheet_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1200dp-xxhdpi")
    fun logSheetDark() {
        render(true) { LogSheet() }
        save("today_v4_log_sheet_dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h1400dp-xxhdpi")
    fun logSheetSmallLargeTextLight() {
        render(false, fontScale = 1.3f) { LogSheet() }
        save("today_v4_log_sheet_360_large_text_light")
    }

    @Test
    @Config(qualifiers = "w360dp-h1400dp-xxhdpi")
    fun logSheetSmallLargeTextDark() {
        render(true, fontScale = 1.3f) { LogSheet() }
        save("today_v4_log_sheet_360_large_text_dark")
    }

    /** The Quick log row's six buttons sit side by side inside the screen: no wrapping, no scrolling sideways. */
    private fun quickLogFits(widthDp: Int) {
        val root = compose.onRoot().getBoundsInRoot()
        val more = compose.onNodeWithContentDescription("More ways to log").getBoundsInRoot()
        val food = compose.onAllNodesWithContentDescription("Log food").onFirst().getBoundsInRoot()
        assertTrue("More is on the screen", more.right <= root.right)
        assertEquals("one row", food.top, more.top)
        assertTrue(root.right.value <= widthDp + 1)
        // Each a 48 dp target at least.
        assertTrue(more.bottom - more.top >= 48.dp)
        assertTrue(more.right - more.left >= 48.dp)
    }
}
