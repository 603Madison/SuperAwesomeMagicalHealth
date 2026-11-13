package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.health.api.AppPage
import com.health.api.DaySummary
import com.health.api.DueDose
import com.health.api.FoodEntry
import com.health.api.FoodSuggestion
import com.health.api.FoodSuggestions
import com.health.api.Goals
import com.health.api.Insight
import com.health.api.LayoutPrefs
import com.health.api.Layouts
import com.health.api.MedForm
import com.health.api.MedsDay
import com.health.api.Meal
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.Nutrients
import com.health.api.NutritionTarget
import com.health.api.Score
import com.health.api.ScreenLayout
import com.health.api.TodaySection
import com.health.api.Units
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.LocalLayoutStore
import com.health.app.ui.components.MemoryLayoutStore
import com.health.app.ui.components.ServerNoticeState
import com.health.app.ui.food.FoodActions
import com.health.app.ui.food.FoodScreen
import com.health.app.ui.food.FoodState
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.today.TodayActions
import com.health.app.ui.today.TodayScreen
import com.health.app.ui.today.TodayState
import org.junit.Assert.assertEquals
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
 * Today's "For you": insights as rows that open their page (with chevrons), the protein insight's
 * food ideas as chips with Add, and the notice when the server is older than the app; the Food
 * tab's ideas. Drawn to PNGs in android/build/outputs/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h1500dp-xxhdpi")
class ForYouScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val today: LocalDate = LocalDate.of(2026, 11, 13)
    private fun at(h: Int, m: Int = 0): Long = today.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun entry(meal: Meal, name: String, kcal: Double, protein: Double, serving: String = "1 serving") =
        FoodEntry(today.toString(), meal, name, Nutrients(kcal, protein), serving = serving, foodId = name.lowercase())

    private val ideas = listOf(
        FoodSuggestion(
            "Chicken stir-fry and rice", null, "1 meal", 1.0, Nutrients(650.0, 38.0), Meal.DINNER,
            listOf(entry(Meal.DINNER, "Chicken stir-fry", 450.0, 32.0), entry(Meal.DINNER, "Rice", 200.0, 6.0)), "You had this for dinner on Tuesday", 1, "2026-11-10",
        ),
        FoodSuggestion("Greek yoghurt", null, "1 pot", 1.0, Nutrients(150.0, 17.0), Meal.DINNER, listOf(entry(Meal.DINNER, "Greek yoghurt", 150.0, 17.0, "1 pot")), "One of your regulars", 9, "2026-11-12"),
        FoodSuggestion(
            "Salmon, potatoes and peas", null, "1 meal", 1.0, Nutrients(560.0, 34.0), Meal.DINNER,
            listOf(entry(Meal.DINNER, "Salmon fillet", 300.0, 28.0), entry(Meal.DINNER, "New potatoes", 180.0, 4.0), entry(Meal.DINNER, "Peas", 80.0, 2.0)),
            "You often have this at dinner", 4, "2026-11-06",
        ),
    )

    private val doses = Insight("info", "2 doses still to take", "Progesterone, Estradiol", "pill", Module.MEDICATIONS, link = AppPage.MEDS, shownBy = TodaySection.MEDS)

    private val summary = DaySummary(
        date = today.toString(),
        readiness = Score(74, "Good", emptyList()), sleep = Score(68, "Fair", emptyList()), activity = Score(61, "Fair", emptyList()),
        steps = 5120, nutrition = Nutrients(1180.0, 54.0, 140.0, 42.0),
        nutritionTarget = NutritionTarget(1900, 100, 220, 63, 2000, "Your goal"), goals = Goals(), hasData = true,
        insights = listOf(
            doses,
            Insight("info", "1h 5m less sleep than usual", "Try winding down a little earlier tonight.", "moon", link = AppPage.SLEEP),
            Insight(
                "info", "Protein 46 g under target",
                "For dinner, the chicken stir-fry and rice you had Tuesday (38 g protein) or Greek yoghurt (17 g) would close a good part of it.",
                "egg", link = AppPage.FOOD, suggestions = ideas,
            ),
            Insight("info", "2,880 steps to go", "An evening walk would get you there.", "footprints", link = AppPage.ACTIVITY),
        ),
        modules = ModulesToday(
            meds = MedsDay(
                today.toString(),
                due = listOf(DueDose("m1", "Progesterone", "100 mg", MedForm.PILL, at(8)), DueDose("m2", "Estradiol", "2 mg", MedForm.PILL, at(8))),
            ),
        ),
    )

    private val modules = Module.DEFAULT + Module.MEDICATIONS
    private val notice = ServerNoticeState("0.2.0", "0.3.0")

    /** Medications hidden on Today, so the day's doses show in "For you". */
    private val noMedsCard = LayoutPrefs(mapOf(Layouts.TODAY to ScreenLayout(hidden = setOf(TodaySection.MEDS.name))))

    private fun render(dark: Boolean = false, layout: LayoutPrefs = LayoutPrefs(), readOnly: Boolean = false, tab: String = "today", content: @Composable () -> Unit) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                CompositionLocalProvider(LocalLayoutStore provides remember { MemoryLayoutStore(layout) }, LocalReadOnly provides readOnly) {
                    AppFrame(selected = tab, snackbar = remember { SnackbarHostState() }, onTab = {}, tabs = tabsFor(modules), content = content)
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
    private fun Today(opened: (String) -> Unit = {}, added: (FoodSuggestion) -> Unit = {}) {
        TodayScreen(
            TodayState(today, today, summary, loading = false), Units.METRIC, zone,
            TodayActions(open = opened, addIdea = added), modules = modules, serverNotice = notice,
        )
    }

    @Test
    fun forYouLight() {
        render(layout = noMedsCard) { Today() }
        save("for_you_light")
    }

    @Test
    fun forYouDark() {
        render(dark = true, layout = noMedsCard) { Today() }
        save("for_you_dark")
    }

    /** With Medications showing the doses, "For you" leaves them out (and has no "Show more"). */
    @Test
    fun forYouWithMedsCard() {
        render { Today() }
        assertEquals(0, compose.onAllNodesWithContentDescription("2 doses still to take. Open Medications").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Show 1 more").fetchSemanticsNodes().size)
        save("for_you_meds_card_light")
    }

    /** Viewing someone's share: the rows still open their pages, the ideas show without Add. */
    @Test
    fun forYouViewingDark() {
        render(dark = true, layout = noMedsCard, readOnly = true) { Today() }
        assertEquals(0, compose.onAllNodesWithContentDescription("Add Greek yoghurt to dinner").fetchSemanticsNodes().size)
        save("for_you_viewing_dark")
    }

    @Test
    fun rowsAreButtonsNamedForWhereTheyGo() {
        val opened = mutableListOf<String>()
        val added = mutableListOf<String>()
        render(layout = noMedsCard) { Today(opened = { opened += it }, added = { added += it.name }) }
        compose.onNodeWithContentDescription("2 doses still to take. Open Medications").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("1h 5m less sleep than usual. Open Trends").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Protein 46 g under target. Open Food").assertHasClickAction().performClick()
        assertEquals(listOf("meds", "trends", "food"), opened)
        compose.onNodeWithContentDescription("Add Greek yoghurt to dinner").assertHasClickAction().performClick()
        assertEquals(listOf("Greek yoghurt"), added)
    }

    @Test
    fun foodIdeasLight() {
        val state = FoodState(
            today, today, loading = false,
            day = com.health.api.FoodDay(today.toString(), emptyList(), emptyList(), Nutrients(900.0, 30.0, 140.0, 23.0), 1250, NutritionTarget(1900, 100, 220, 63, 2000, "Your goal")),
            ideas = FoodSuggestions(Meal.DINNER, Nutrients(1000.0, 70.0), ideas + FoodSuggestion("Lentil soup", null, "1 bowl", 1.0, Nutrients(320.0, 18.0), Meal.DINNER, listOf(entry(Meal.DINNER, "Lentil soup", 320.0, 18.0, "1 bowl")), "You had this last week", 2, "2026-11-03")),
        )
        render(tab = "food") { FoodScreen(state, Units.METRIC, FoodActions(), zone = zone) }
        save("food_ideas_light")
    }

    @Test
    fun foodIdeasInRecoveryDark() {
        val state = FoodState(
            today, today, loading = false,
            day = com.health.api.FoodDay(today.toString(), emptyList(), emptyList(), Nutrients(), 0, NutritionTarget(1900, 100, 220, 63, 2000, "Your goal")),
            ideas = FoodSuggestions(Meal.DINNER, null, ideas, showNumbers = false),
        )
        render(dark = true, tab = "food") { FoodScreen(state, Units.METRIC, FoodActions(), zone = zone) }
        save("food_ideas_no_numbers_dark")
    }
}
