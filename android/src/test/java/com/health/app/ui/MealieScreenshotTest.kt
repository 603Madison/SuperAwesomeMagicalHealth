package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.health.api.Food
import com.health.api.FoodDay
import com.health.api.FoodEntry
import com.health.api.FoodSearch
import com.health.api.FoodSource
import com.health.api.FoodSuggestion
import com.health.api.FoodSuggestions
import com.health.api.LayoutPrefs
import com.health.api.Layouts
import com.health.api.Meal
import com.health.api.MealiePlan
import com.health.api.MealiePlanEntry
import com.health.api.MealieRecipe
import com.health.api.MealieRecipes
import com.health.api.MealieStatus
import com.health.api.Module
import com.health.api.Nutrients
import com.health.api.NutritionTarget
import com.health.api.Profile
import com.health.api.Role
import com.health.api.ScreenLayout
import com.health.api.Units
import com.health.api.User
import com.health.app.data.Palette
import com.health.app.data.Settings
import com.health.app.data.ThemeMode
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.LocalLayoutStore
import com.health.app.ui.components.MemoryLayoutStore
import com.health.app.ui.food.AddFoodActions
import com.health.app.ui.food.AddFoodScreen
import com.health.app.ui.food.AddFoodState
import com.health.app.ui.food.FoodActions
import com.health.app.ui.food.FoodScreen
import com.health.app.ui.food.FoodState
import com.health.app.ui.food.MealieEdit
import com.health.app.ui.settings.MealieActions
import com.health.app.ui.settings.MealieScreen
import com.health.app.ui.settings.MealieState
import com.health.app.ui.settings.SettingsActions
import com.health.app.ui.settings.SettingsScreen
import com.health.app.ui.theme.HealthTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Mealie, drawn to PNGs: Settings' page (not connected, connecting, a problem, connected, a new
 * token) in light and dark, its row in Settings, the Food tab's Meal plan card (logged, to add, a
 * note, one without nutrition; in recovery; and when Mealie didn't answer), and the add-food
 * search with "Your Mealie" and a recipe's servings.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class MealieScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val today = LocalDate.of(2026, 11, 14)
    private val zone = ZoneId.of("Europe/London")

    private fun render(dark: Boolean = false, tab: String = "food", layout: LayoutPrefs = LayoutPrefs(), readOnly: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { Screen(dark, tab, layout, readOnly, content) }
    }

    @Composable
    private fun Screen(dark: Boolean, tab: String, layout: LayoutPrefs, readOnly: Boolean, content: @Composable () -> Unit) {
        HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
            CompositionLocalProvider(LocalLayoutStore provides remember { MemoryLayoutStore(layout) }, LocalReadOnly provides readOnly) {
                AppFrame(selected = tab, snackbar = remember { SnackbarHostState() }, onTab = {}, tabs = tabsFor(Module.DEFAULT), content = content)
            }
        }
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * The screen with the dialog that's open over it (dialogs are windows of their own), as the
     * phone shows them. With [settle] false, drawn without waiting for Compose to be idle.
     */
    private fun saveWithDialog(name: String, settle: Boolean = true) {
        if (settle) compose.waitForIdle() else repeat(5) { ShadowLooper.idleMainLooper() }
        val view = compose.activity.window.decorView
        val dialog = checkNotNull(ShadowDialog.getLatestDialog()?.window?.decorView) { "No dialog is open" }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread {
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            canvas.drawColor(android.graphics.Color.argb(82, 0, 0, 0))
            canvas.save()
            canvas.translate((view.width - dialog.width) / 2f, (view.height - dialog.height) / 2f)
            dialog.draw(canvas)
            canvas.restore()
        }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- Settings › Mealie

    private val site = "http://mealie.home:9000"
    private val connected = MealieStatus(connected = true, url = site, user = "Sam", version = "v3.1.0")

    @Test
    fun settingsNotConnected() {
        render(tab = "settings") { MealieScreen(MealieState(status = MealieStatus(), loading = false), MealieActions(), {}) }
        save("mealie_settings_light")
    }

    @Test
    fun settingsNotConnectedDark() {
        render(dark = true, tab = "settings") { MealieScreen(MealieState(status = MealieStatus(), loading = false), MealieActions(), {}) }
        save("mealie_settings_dark")
    }

    @Test
    fun settingsConnecting() {
        val state = MealieState(status = MealieStatus(), loading = false, url = "mealie.home:9000", token = "eyJhbGciOiJIUzI1NiJ9.secret", working = true)
        // The spinner a moment in, rather than at its first frame.
        compose.mainClock.autoAdvance = false
        render(tab = "settings") { MealieScreen(state, MealieActions(), {}) }
        compose.mainClock.advanceTimeBy(700)
        save("mealie_settings_connecting")
    }

    @Test
    fun settingsProblemDark() {
        val state = MealieState(
            status = MealieStatus(), loading = false, url = "mealie.home:9000", token = "an-old-token",
            problem = "Mealie didn't accept the token. Make a new one in Mealie (your profile, then API tokens) and connect again",
        )
        render(dark = true, tab = "settings") { MealieScreen(state, MealieActions(), {}) }
        save("mealie_settings_problem_dark")
    }

    @Test
    fun settingsConnected() {
        render(tab = "settings") { MealieScreen(MealieState(status = connected, loading = false), MealieActions(), {}) }
        save("mealie_connected_light")
    }

    @Test
    fun settingsConnectedDark() {
        render(dark = true, tab = "settings") { MealieScreen(MealieState(status = connected, loading = false), MealieActions(), {}) }
        save("mealie_connected_dark")
    }

    @Test
    fun settingsNewToken() {
        render(tab = "settings") { MealieScreen(MealieState(status = connected, loading = false, edit = MealieEdit.TOKEN), MealieActions(), {}) }
        save("mealie_connected_new_token")
    }

    /** Disconnecting asks first. */
    @Test
    fun settingsDisconnectAsks() {
        var gone = 0
        render(tab = "settings") { MealieScreen(MealieState(status = connected, loading = false), MealieActions(disconnect = { gone++ }), {}) }
        compose.onNodeWithText("Disconnect").performClick()
        compose.waitForIdle()
        assertEquals(0, gone)
        saveWithDialog("mealie_disconnect_confirm")
        compose.onAllNodesWithText("Disconnect").onLast().performClick()
        assertEquals(1, gone)
    }

    /** Settings' Connections, with Mealie's row (Food is on): the other sections hidden, to see it. */
    @Test
    fun settingsRow() {
        val settings = Settings(server = "https://health.home.arpa", token = "t", serverName = "Health at home", user = User("u1", "sam", "Sam", Role.USER, createdAt = 0))
        val others = setOf("you", "features", "goals", "reminders", "appearance", "account")
        val layout = LayoutPrefs(mapOf(Layouts.screen(Layouts.ANDROID, "settings") to ScreenLayout(hidden = others)))
        render(tab = "settings", layout = layout) {
            SettingsScreen(settings, Profile(timezone = "Europe/London", modules = Module.DEFAULT), SettingsActions(), mealie = connected)
        }
        save("mealie_settings_row")
    }

    // ---- The Food tab's Meal plan

    private fun recipe(slug: String, name: String, n: Nutrients?, servings: Double? = null) = MealieRecipe(
        slug, name,
        food = n?.let { Food(name, it, source = FoodSource.MEALIE, ref = "mealie:id-$slug", link = "$site/g/home/r/$slug") },
        servings = servings, link = "$site/g/home/r/$slug",
    )

    private val oats = recipe("overnight-oats", "Overnight oats with berries", Nutrients(380.0, 16.0, 52.0, 11.0), 2.0)
    private val wrap = recipe("chicken-wrap", "Chicken and avocado wrap", Nutrients(520.0, 34.0, 45.0, 21.0), 2.0)
    private val soup = recipe("lentil-soup", "Red lentil and tomato soup", Nutrients(320.0, 18.0, 40.0, 6.5), 4.0)
    private val pie = recipe("shepherds-pie", "Grandma's shepherd's pie", null, 6.0)

    private val plan = MealiePlan(
        today.toString(),
        listOf(
            MealiePlanEntry(Meal.BREAKFAST, oats.name, recipe = oats, logged = true),
            MealiePlanEntry(Meal.LUNCH, wrap.name, recipe = wrap),
            MealiePlanEntry(Meal.DINNER, soup.name, recipe = soup, note = "Make double for tomorrow"),
            MealiePlanEntry(Meal.DINNER, pie.name, recipe = pie),
            MealiePlanEntry(Meal.SNACK, "Fruit", note = "Whatever's in the bowl"),
        ),
    )

    private val day = FoodDay(
        date = today.toString(),
        entries = listOf(FoodEntry(today.toString(), Meal.BREAKFAST, oats.name, oats.food!!.nutrients, 1.0, "1 serving", foodId = "f1", id = "1")),
        water = emptyList(), totals = oats.food!!.nutrients, waterMl = 0,
        target = NutritionTarget(2000, 100, 240, 65, 2000, "About 2,000 kcal keeps your weight"),
    )

    private val ideas = FoodSuggestions(
        Meal.LUNCH, items = listOf(
            FoodSuggestion(
                "Chicken burrito bowl", null, "1 bowl", 1.0, Nutrients(640.0, 42.0, 72.0, 18.0), Meal.LUNCH,
                listOf(FoodEntry(today.toString(), Meal.LUNCH, "Chicken burrito bowl", Nutrients(640.0, 42.0, 72.0, 18.0), 1.0, "1 bowl")),
                "You often have this at lunch", 6, "2026-11-11",
            ),
        ),
    )

    private fun foodState(p: MealiePlan = plan) = FoodState(today, today, day, loading = false, ideas = ideas, mealie = true, plan = p)

    @Test
    fun mealPlan() {
        var added = -1
        render { FoodScreen(foodState(), Units.METRIC, FoodActions(addPlanned = { added = it }), zone = zone) }
        // Logged, a note and one without nutrition have no Add; the wrap and the soup do.
        assertEquals(0, compose.onAllNodesWithContentDescription("Add ${oats.name} to breakfast").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithContentDescription("Add ${pie.name} to dinner").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Add ${soup.name} to dinner").performClick()
        assertEquals(2, added)
        save("food_mealplan_light")
    }

    @Test
    fun mealPlanDark() {
        render(dark = true) { FoodScreen(foodState(), Units.METRIC, FoodActions(), zone = zone) }
        save("food_mealplan_dark")
    }

    /** In eating disorder recovery: no calories anywhere, and Add still works. */
    @Test
    fun mealPlanInRecovery() {
        render { FoodScreen(foodState(), Units.METRIC, FoodActions(), zone = zone, numbers = false) }
        assertEquals(0, compose.onAllNodesWithText("kcal", substring = true).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithContentDescription("Add ${soup.name} to dinner").fetchSemanticsNodes().size)
        save("food_mealplan_recovery")
    }

    /** Mealie didn't answer: one quiet line in the card. */
    @Test
    fun mealPlanProblemDark() {
        val problem = MealiePlan(today.toString(), problem = "Mealie didn't answer at mealie.home. Check the address, and that this server can reach it")
        render(dark = true) { FoodScreen(foodState(problem), Units.METRIC, FoodActions(), zone = zone) }
        save("food_mealplan_problem_dark")
    }

    /** Viewing someone's share: no Meal plan at all (it's the viewer's own Mealie). */
    @Test
    fun mealPlanNotWhileViewing() {
        render(readOnly = true) { FoodScreen(foodState(), Units.METRIC, FoodActions(), zone = zone) }
        assertEquals(0, compose.onAllNodesWithText("Meal plan").fetchSemanticsNodes().size)
    }

    // ---- Finding a food

    private val search = FoodSearch(
        mine = listOf(
            soup.food!!.copy(id = "f9", uses = 4, link = null),
            Food("Tomato soup", Nutrients(180.0, 4.0, 22.0, 8.0), "1 can", 400.0, brand = "Heinz", id = "f2", uses = 2),
        ),
        online = listOf(
            Food("Minestrone soup", Nutrients(110.0, 4.2, 18.0, 2.0), "300 g", 300.0, brand = "Baxters", source = FoodSource.OPEN_FOOD_FACTS),
            Food("Pea and ham soup", Nutrients(190.0, 10.0, 24.0, 5.0), "400 g", 400.0, brand = "Glorious!", source = FoodSource.OPEN_FOOD_FACTS),
        ),
    )

    private val recipes = MealieRecipes(listOf(soup, recipe("pumpkin-soup", "Roast pumpkin soup", null)))

    @Test
    fun searchWithMealie() {
        render { AddFoodScreen(Meal.DINNER, AddFoodState(query = "soup", results = search, mealieOn = true, mealie = recipes), AddFoodActions()) }
        // The person's copy of the lentil soup is under Mealie's group, not twice.
        assertEquals(1, compose.onAllNodesWithText(soup.name).fetchSemanticsNodes().size)
        save("food_add_mealie_light")
    }

    @Test
    fun searchWithMealieDark() {
        render(dark = true) { AddFoodScreen(Meal.DINNER, AddFoodState(query = "soup", results = search, mealieOn = true, mealie = recipes), AddFoodActions(), numbers = false) }
        save("food_add_mealie_recovery_dark")
    }

    @Test
    fun searchMealieProblem() {
        val problem = MealieRecipes(problem = "Mealie didn't answer at mealie.home. Check the address, and that this server can reach it")
        render { AddFoodScreen(Meal.DINNER, AddFoodState(query = "soup", results = search, mealieOn = true, mealie = problem), AddFoodActions()) }
        save("food_add_mealie_problem")
    }

    /** A recipe's servings: one serving, and how many the recipe makes. */
    @Test
    fun recipeServings() {
        // The dialog's number field never lets Compose settle under Robolectric (on a phone it's
        // fine): set and drawn without waiting for idle.
        compose.mainClock.autoAdvance = false
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                Screen(false, "food", LayoutPrefs(), false) {
                    AddFoodScreen(Meal.DINNER, AddFoodState(query = "soup", results = search, mealieOn = true, mealie = recipes, picked = soup.food), AddFoodActions())
                }
            }
        }
        saveWithDialog("food_add_mealie_servings", settle = false)
    }
}
