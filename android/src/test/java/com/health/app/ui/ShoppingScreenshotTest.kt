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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.health.api.Food
import com.health.api.FoodGrade
import com.health.api.FoodSearch
import com.health.api.FoodSource
import com.health.api.LayoutPrefs
import com.health.api.Meal
import com.health.api.Nutrients
import com.health.api.ShoppingItem
import com.health.api.ShoppingList
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.components.LocalLayoutStore
import com.health.app.ui.components.MemoryLayoutStore
import com.health.app.ui.food.AddFoodActions
import com.health.app.ui.food.AddFoodScreen
import com.health.app.ui.food.AddFoodState
import com.health.app.ui.food.ShoppingActions
import com.health.app.ui.food.ShoppingScreen
import com.health.app.ui.food.ShoppingState
import com.health.app.ui.theme.HealthTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/** Shopping (a product with its grade, two side by side, the list) and graded foods in the add-food search, drawn to PNGs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ShoppingScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private fun render(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                CompositionLocalProvider(LocalLayoutStore provides remember { MemoryLayoutStore(LayoutPrefs()) }) {
                    AppFrame(selected = tabOf("food"), snackbar = remember { SnackbarHostState() }, onTab = {}, content = content)
                }
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

    private val yogurt = Food(
        "Greek yogurt, plain", Nutrients(146.0, 20.0, 8.0, 3.8, fiber = 0.0, sugar = 7.0, saturatedFat = 2.4, sodiumMg = 68.0), "1 cup (200 g)", 200.0,
        brand = "Fage", source = FoodSource.USDA, grade = FoodGrade("A", listOf("Good protein for your goal", "Lower in fibre")),
    )
    private val granola = Food(
        "Honey granola", Nutrients(240.0, 5.0, 36.0, 9.0, fiber = 3.0, sugar = 14.0, saturatedFat = 1.5, sodiumMg = 95.0), "1/2 cup (55 g)", 55.0,
        brand = "Nature's Path", source = FoodSource.USDA, grade = FoodGrade("D", listOf("High in sugars for your blood sugar goal", "Energy-dense", "Good fibre")),
    )
    private val list = ShoppingList(
        listOf(
            ShoppingItem("1", "Greek yogurt, plain", "Fage", yogurt),
            ShoppingItem("2", "Bananas"),
            ShoppingItem("3", "Oat milk", checked = true),
        ),
    )

    @Test
    fun product() {
        render { ShoppingScreen(ShoppingState(query = "yogurt", product = yogurt, compare = listOf(yogurt, granola), list = list), ShoppingActions()) }
        save("shopping_product_light")
    }

    @Test
    fun compareAndListDark() {
        render(dark = true) {
            ShoppingScreen(ShoppingState(query = "granola", results = listOf(granola, yogurt), compare = listOf(yogurt, granola), list = list), ShoppingActions())
        }
        save("shopping_dark")
    }

    @Test
    fun clearingBoughtAndTicking() {
        var cleared = 0
        val ticked = mutableListOf<String>()
        render { ShoppingScreen(ShoppingState(list = list), ShoppingActions(clearBought = { cleared++ }, tick = { ticked += it.id })) }
        compose.onNodeWithText("Clear 1 bought").performClick()
        compose.onNodeWithText("Bananas").performClick()
        assertEquals(1, cleared)
        assertEquals(listOf("2"), ticked)
    }

    @Test
    fun addFoodWithGradesAndClearing() {
        var cleared = 0
        render {
            AddFoodScreen(Meal.BREAKFAST, AddFoodState(recent = listOf(yogurt.copy(id = "a"), granola.copy(id = "b"))), AddFoodActions(clearFoods = { cleared++ }))
        }
        save("food_add_graded_light")
        compose.onNodeWithText("Clear my foods").performClick()
        compose.onNodeWithText("Clear").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun searchResultsGraded() {
        render(dark = true) { AddFoodScreen(Meal.LUNCH, AddFoodState(query = "yogurt", results = FoodSearch(emptyList(), listOf(yogurt, granola))), AddFoodActions()) }
        save("food_search_graded_dark")
    }
}
