package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.health.api.AiConsent
import com.health.api.AiStatus
import com.health.api.EstimateConfidence
import com.health.api.Food
import com.health.api.FoodPhotoEstimate
import com.health.api.FoodPhotoItem
import com.health.api.Meal
import com.health.api.Module
import com.health.api.Nutrients
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.food.AddFoodActions
import com.health.app.ui.food.AddFoodScreen
import com.health.app.ui.food.AddFoodState
import com.health.app.ui.food.FoodPhotoActions
import com.health.app.ui.food.FoodPhotoScreen
import com.health.app.ui.food.FoodPhotoState
import com.health.app.ui.food.FoodPhotoText
import com.health.app.ui.food.PhotoOffer
import com.health.app.ui.theme.HealthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/** Food photo estimates, drawn to PNGs: the first-time notice, the photo, and checking what it saw. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class FoodPhotoScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private fun render(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) { content() }
        }
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        AppFrame(selected = "food", snackbar = remember { SnackbarHostState() }, onTab = {}, tabs = tabsFor(Module.DEFAULT), content = content)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val status = AiStatus(
        available = true, model = "claude-opus-5-5",
        consent = AiConsent(setOf(Module.NUTRITION, Module.SLEEP), acknowledged = true),
        usedToday = 3, dailyLimit = 40,
    )

    private val estimate = FoodPhotoEstimate(
        items = listOf(
            FoodPhotoItem(
                "Spaghetti bolognese", 320.0, Nutrients(480.0, 24.0, 62.0, 14.0, fiber = 5.0), EstimateConfidence.MEDIUM, "assumed cooked weight",
                match = Food("Homemade spaghetti bolognese", Nutrients(560.0, 30.0, 70.0, 16.0), "1 bowl", servingGrams = 350.0, id = "f1", uses = 6),
            ),
            FoodPhotoItem("Green salad", 60.0, Nutrients(20.0, 1.0, 3.0, 0.2, fiber = 1.5), EstimateConfidence.LOW, "dressing not visible"),
            FoodPhotoItem("Orange juice", 250.0, Nutrients(110.0, 1.7, 26.0, 0.5), EstimateConfidence.HIGH, "assumed a standard glass", unit = "ml"),
        ),
        usedToday = 4,
    )

    private fun reviewing(hide: Boolean = false, mine: Boolean = false): FoodPhotoState {
        val e = estimate.copy(hideNumbers = hide)
        val rows = FoodPhotoText.rows(e).let { r -> if (mine) listOf(FoodPhotoText.useMine(r[0], true)) + r.drop(1) else r }
        return FoodPhotoState(noticeSeen = true, status = status, estimate = e, rows = rows)
    }

    /** A plate, drawn: for the photo's preview. */
    private fun plate(): Bitmap {
        val b = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawColor(Color.rgb(196, 170, 140))
            drawCircle(400f, 300f, 250f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 248, 244) })
            drawCircle(360f, 300f, 140f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(214, 120, 52) })
            drawCircle(520f, 250f, 70f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(96, 168, 72) })
        }
        return b
    }

    @Test
    fun notice() {
        render { Frame { FoodPhotoScreen(Meal.LUNCH, FoodPhotoState(status = status), FoodPhotoActions()) } }
        save("food_photo_notice")
    }

    @Test
    fun noticeDark() {
        render(dark = true) { Frame { FoodPhotoScreen(Meal.LUNCH, FoodPhotoState(status = status), FoodPhotoActions()) } }
        save("food_photo_notice_dark")
    }

    @Test
    fun review() {
        render { Frame { FoodPhotoScreen(Meal.DINNER, reviewing(), FoodPhotoActions()) } }
        save("food_photo_review")
    }

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun reviewDarkWhole() {
        render(dark = true) { Frame { FoodPhotoScreen(Meal.DINNER, reviewing(mine = true), FoodPhotoActions()) } }
        save("food_photo_review_dark")
    }

    @Test
    fun reviewInRecovery() {
        render { Frame { FoodPhotoScreen(Meal.DINNER, reviewing(hide = true), FoodPhotoActions()) } }
        save("food_photo_review_recovery")
    }

    @Test
    fun photoTaken() {
        render { Frame { FoodPhotoScreen(Meal.DINNER, FoodPhotoState(noticeSeen = true, status = status, photo = ByteArray(1), preview = plate(), note = "large plate"), FoodPhotoActions(), camera = false) } }
        save("food_photo_taken")
    }

    @Test
    fun addFoodWithPhoto() {
        render { Frame { AddFoodScreen(Meal.LUNCH, AddFoodState(photoOffer = PhotoOffer.READY), AddFoodActions()) } }
        save("food_add_photo_button")
    }

    @Test
    fun addFoodPointsToSettings() {
        render(dark = true) { Frame { AddFoodScreen(Meal.LUNCH, AddFoodState(photoOffer = PhotoOffer.SETTINGS), AddFoodActions()) } }
        save("food_add_photo_settings_dark")
    }
}
