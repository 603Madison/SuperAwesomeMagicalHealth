package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.health.api.Food
import com.health.api.FoodDay
import com.health.api.FoodEntry
import com.health.api.FoodSearch
import com.health.api.FoodSource
import com.health.api.Goals
import com.health.api.Meal
import com.health.api.MicroTarget
import com.health.api.Micronutrient
import com.health.api.Module
import com.health.api.NutrientTargetSetting
import com.health.api.NutrientTargets
import com.health.api.Nutrients
import com.health.api.NutritionTarget
import com.health.api.TrendDay
import com.health.api.Trends
import com.health.api.Units
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.food.AddFoodActions
import com.health.app.ui.food.AddFoodScreen
import com.health.app.ui.food.AddFoodState
import com.health.app.ui.food.CarriedList
import com.health.app.ui.food.FoodActions
import com.health.app.ui.food.FoodScreen
import com.health.app.ui.food.FoodState
import com.health.app.ui.food.MicrosCard
import com.health.app.ui.food.MoreNutrients
import com.health.app.ui.settings.NutrientTargetsScreen
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.trends.TrendsActions
import com.health.app.ui.trends.TrendsScreen
import com.health.app.ui.trends.TrendsState
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
 * Vitamins and minerals, drawn to PNGs in light and dark: the Food tab's section (the main few,
 * and all of them, with only some foods carrying any), a food's details and search results, "More
 * nutrients" for a new food, the Trends chart, and the targets in Settings.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class MicrosScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val today = LocalDate.of(2026, 11, 13)
    private val zone = ZoneId.of("Europe/London")

    private fun render(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) { content() } }
    }

    @Composable
    private fun Frame(tab: String, content: @Composable () -> Unit) {
        AppFrame(selected = tab, snackbar = remember { SnackbarHostState() }, onTab = {}, tabs = tabsFor(Module.DEFAULT), content = content)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val cereal = Nutrients(
        140.0, 4.0, 27.0, 1.0, fiber = 6.0, sugar = 7.0, saturatedFat = 0.3, sodiumMg = 190.0,
        micros = mapOf(Micronutrient.IRON to 5.6, Micronutrient.CALCIUM to 40.0, Micronutrient.VITAMIN_D to 1.7, Micronutrient.FOLATE to 80.0, Micronutrient.VITAMIN_B12 to 0.8, Micronutrient.THIAMIN to 0.4, Micronutrient.POTASSIUM to 220.0),
    )
    private val milk = Nutrients(98.0, 7.0, 10.0, 3.4, sugar = 10.0, saturatedFat = 2.2, sodiumMg = 88.0, micros = mapOf(Micronutrient.CALCIUM to 248.0, Micronutrient.VITAMIN_B12 to 0.9, Micronutrient.IODINE to 60.0, Micronutrient.POTASSIUM to 300.0))
    private val soup = Nutrients(280.0, 16.0, 40.0, 6.0, fiber = 9.0, sodiumMg = 820.0, micros = mapOf(Micronutrient.IRON to 4.1, Micronutrient.POTASSIUM to 690.0, Micronutrient.MAGNESIUM to 70.0, Micronutrient.FOLATE to 150.0))

    private val entries = listOf(
        FoodEntry(today.toString(), Meal.BREAKFAST, "Fortified bran flakes", cereal, 1.0, "40 g", brand = "Kellogg's", id = "1"),
        FoodEntry(today.toString(), Meal.BREAKFAST, "Semi-skimmed milk", milk, 1.0, "200 ml", id = "2"),
        FoodEntry(today.toString(), Meal.BREAKFAST, "Flat white", Nutrients(110.0, 6.0, 9.0, 5.0), 1.0, "1 cup", id = "3"),
        FoodEntry(today.toString(), Meal.LUNCH, "Lentil soup", soup, 1.0, "1 bowl", id = "4"),
        FoodEntry(today.toString(), Meal.LUNCH, "Sourdough", Nutrients(160.0, 6.0, 31.0, 1.0), 1.0, "1 slice", id = "5"),
        FoodEntry(today.toString(), Meal.DINNER, "Chicken stir-fry", Nutrients(520.0, 38.0, 45.0, 18.0), 1.0, "Quick add", id = "6"),
        FoodEntry(today.toString(), Meal.SNACK, "Apple", Nutrients(95.0, 0.5, 25.0, 0.3), 1.0, "1 medium", id = "7"),
    )

    private val target = NutritionTarget(
        2050, 95, 255, 68, 2000, "About 2,050 kcal keeps your weight",
        micros = listOf(
            MicroTarget(Micronutrient.TRANS_FAT, 2.3), MicroTarget(Micronutrient.OMEGA_3, 1.1), MicroTarget(Micronutrient.CHOLESTEROL, 300.0),
            MicroTarget(Micronutrient.POTASSIUM, 2600.0), MicroTarget(Micronutrient.CALCIUM, 1000.0, 2500.0), MicroTarget(Micronutrient.IRON, 18.0, 45.0),
            MicroTarget(Micronutrient.MAGNESIUM, 310.0), MicroTarget(Micronutrient.ZINC, 8.0, 40.0), MicroTarget(Micronutrient.PHOSPHORUS, 700.0, 4000.0),
            MicroTarget(Micronutrient.SELENIUM, 55.0, 400.0), MicroTarget(Micronutrient.IODINE, 150.0, 1100.0), MicroTarget(Micronutrient.VITAMIN_A, 700.0),
            MicroTarget(Micronutrient.VITAMIN_C, 75.0, 2000.0), MicroTarget(Micronutrient.VITAMIN_D, 15.0, 100.0), MicroTarget(Micronutrient.VITAMIN_E, 15.0),
            MicroTarget(Micronutrient.VITAMIN_K, 90.0), MicroTarget(Micronutrient.THIAMIN, 1.1), MicroTarget(Micronutrient.RIBOFLAVIN, 1.1),
            MicroTarget(Micronutrient.NIACIN, 14.0), MicroTarget(Micronutrient.VITAMIN_B6, 1.3, 100.0), MicroTarget(Micronutrient.FOLATE, 400.0),
            MicroTarget(Micronutrient.VITAMIN_B12, 2.4),
        ),
        fiberG = 25.0, sugarG = 90.0, saturatedFatG = 22.8, sodiumMg = 2300.0, microBasis = "Adult reference intake, female values",
    )

    private val day = FoodDay(
        date = today.toString(), entries = entries, water = emptyList(),
        totals = entries.fold(Nutrients()) { sum, e -> sum + e.nutrients }, waterMl = 0, target = target, burned = 1350.0,
    )

    /** The day before today, so the page opens on the totals and this section (ideas are today's). */
    private fun foodState() = FoodState(today, today.plusDays(1), day, loading = false)

    @Test
    fun foodSection() {
        render { Frame("food") { FoodScreen(foodState(), Units.METRIC, FoodActions(), zone, micros = true) } }
        save("micros_food_light")
    }

    @Test
    fun foodSectionDark() {
        render(dark = true) { Frame("food") { FoodScreen(foodState(), Units.METRIC, FoodActions(), zone, micros = true) } }
        save("micros_food_dark")
    }

    @Composable
    private fun Expanded() {
        Frame("food") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { MicrosCard(day, isToday = true, startExpanded = true) }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun foodSectionAll() {
        render { Expanded() }
        save("micros_food_all_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun foodSectionAllDark() {
        render(dark = true) { Expanded() }
        save("micros_food_all_dark")
    }

    private val search = FoodSearch(
        mine = emptyList(),
        online = listOf(
            Food("Fortified bran flakes", cereal, "40 g", 40.0, brand = "Kellogg's", source = FoodSource.OPEN_FOOD_FACTS),
            Food("Bran flakes", Nutrients(350.0, 10.0, 67.0, 2.0, fiber = 15.0), "100 g", 100.0, brand = "Own brand", source = FoodSource.OPEN_FOOD_FACTS),
            Food("Bran muffin", Nutrients(305.0, 5.0, 52.0, 9.0), "1 muffin", 90.0, source = FoodSource.OPEN_FOOD_FACTS),
        ),
    )

    @Test
    fun searchResults() {
        render { Frame("food") { AddFoodScreen(Meal.BREAKFAST, AddFoodState(query = "bran flakes", results = search), AddFoodActions(), micros = true) } }
        save("micros_search_light")
    }

    /** A new food's "More nutrients", and a food's details (in dialogs in the app; drawn in a card here). */
    @Composable
    private fun Forms() {
        Frame("food") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ScreenTitle("New food")
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppCard { MoreNutrients(mapOf("FIBER" to "6", "IRON" to "4.2", "CALCIUM" to "40"), {}, startOpen = true) }
                    AppCard {
                        Text("Fortified bran flakes, 1 serving")
                        CarriedList(cereal)
                    }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    fun moreNutrients() {
        render { Forms() }
        save("micros_more_nutrients_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    fun moreNutrientsDark() {
        render(dark = true) { Forms() }
        save("micros_more_nutrients_dark")
    }

    private fun trend(i: Int): TrendDay {
        val d = today.minusDays(29L - i)
        val wave = sin(i * 0.9) * 0.5 + 0.5
        val logged = i % 7 != 3
        return TrendDay(
            date = d.toString(), calories = if (logged) 1800 + wave * 400 else null, protein = if (logged) 80 + wave * 20 else null,
            fiber = if (logged) 14 + wave * 16 else null, sodiumMg = if (logged) 1500 + wave * 1200 else null,
            micros = if (logged && i % 3 != 1) mapOf(Micronutrient.IRON to 9 + wave * 8, Micronutrient.CALCIUM to 700 + wave * 400) else emptyMap(),
        )
    }

    private val trends = TrendsState(days = 30, trends = Trends(today.minusDays(29).toString(), today.toString(), (0 until 30).map(::trend), target), loading = false)

    @Test
    fun trendsChart() {
        render { Frame("trends") { TrendsScreen(trends, Units.METRIC, Goals(), zone, TrendsActions(), modules = setOf(Module.NUTRITION), water = false, micros = true) } }
        save("micros_trends_light")
    }

    @Test
    fun trendsChartDark() {
        render(dark = true) { Frame("trends") { TrendsScreen(trends, Units.METRIC, Goals(), zone, TrendsActions(), modules = setOf(Module.NUTRITION), water = false, micros = true) } }
        save("micros_trends_dark")
    }

    private val targets = NutrientTargets(
        items = listOf(
            NutrientTargetSetting("FIBER", 25.0, own = 30.0), NutrientTargetSetting("SUGAR", 90.0), NutrientTargetSetting("SATURATED_FAT", 22.8),
            NutrientTargetSetting("SODIUM", 2300.0),
        ) + target.micros.map { NutrientTargetSetting(it.micro.name, it.amount, it.upper, own = if (it.micro == Micronutrient.VITAMIN_D) 20.0 else null) } +
            listOf(NutrientTargetSetting("MONO_FAT"), NutrientTargetSetting("POLY_FAT")),
        basis = "Adult reference intake, female values",
        source = "US National Academies' Dietary Reference Intakes (via the NIH Office of Dietary Supplements); saturated fat from the Dietary Guidelines for Americans, trans fat from the WHO, cholesterol from the FDA's Daily Value, and sugar from the EU's reference intake",
    )

    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun targetsSettings() {
        render { Frame("settings") { NutrientTargetsScreen(targets, null, {}) } }
        save("micros_targets_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun targetsSettingsDark() {
        render(dark = true) { Frame("settings") { NutrientTargetsScreen(targets, null, {}) } }
        save("micros_targets_dark")
    }
}
