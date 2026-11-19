package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.health.api.Food
import com.health.api.FoodSource
import com.health.api.Nutrients
import com.health.api.PortionEstimate
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.food.PortionPhoto
import com.health.app.ui.food.PortionText
import com.health.app.ui.theme.HealthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/** "How much did I have?" in the servings dialog, drawn to PNGs: the button, and the open panel. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class PortionPhotoScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val crisps = Food("Sea salt crisps", Nutrients(130.0, 2.0, 13.0, 8.0), "1 bag (25 g)", servingGrams = 25.0, source = FoodSource.OPEN_FOOD_FACTS, packageGrams = 150.0)

    private fun render(dark: Boolean) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(24.dp)) {
                        PortionPhoto(crisps, null, { _, _, _, _ -> PortionEstimate(1.5, "About one and a half bags") }) {}
                    }
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

    @Test
    fun button() {
        render(dark = false)
        save("food_portion_button")
    }

    @Test
    fun openDark() {
        render(dark = true)
        compose.onNodeWithText(PortionText.BUTTON).performClick()
        save("food_portion_open_dark")
    }
}
