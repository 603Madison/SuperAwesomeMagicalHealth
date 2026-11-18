package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.health.api.Device
import com.health.api.DeviceKind
import com.health.api.LayoutPrefs
import com.health.api.ScreenLayout
import com.health.api.Units
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.devices.CompareActions
import com.health.app.ui.devices.CompareFixtures
import com.health.app.ui.devices.CompareScreen
import com.health.app.ui.devices.CompareState
import com.health.app.ui.devices.DevicesActions
import com.health.app.ui.devices.DevicesScreen
import com.health.app.ui.devices.DevicesState
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.LocalArrangements
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
import java.util.Locale

/**
 * Compare devices, shaped like hers: her COLMI ring checked against a Polar chest strap over four
 * nights in light, the ring against her Garmin watch (each with its own night) in dark, one device
 * only, and a narrow phone. PNGs in android/build/outputs/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class CompareScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val f = CompareFixtures

    private fun render(dark: Boolean = false, layout: LayoutPrefs? = null, content: @Composable () -> Unit) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                CompositionLocalProvider(LocalArrangements provides layout) {
                    AppFrame(selected = "more", snackbar = remember { SnackbarHostState() }, onTab = {}, content = content)
                }
            }
        }
    }

    private fun page(state: CompareState, units: Units = Units.METRIC, actions: CompareActions = CompareActions()) = @Composable {
        CompareScreen(state, units, f.zone, actions, today = f.today)
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
    @Config(qualifiers = "w411dp-h2900dp-xxhdpi")
    fun ringAgainstStrapLight() {
        render { page(CompareState(a = f.STRAP, b = f.RING, comparison = f.ringVsStrap, loading = false))() }
        shows("Compare devices")
        shows("Compare against")
        shows("Polar H10 7A2B4C1D")
        shows("The ring read 3 bpm higher than the strap · 3 apart on average · from −1 to +7 · 4 nights")
        shows("(12% lower)")
        shows("The strap doesn't record sleep here")
        shows("Last night")
        assertEquals(4, compose.onAllNodes(hasContentDescription("Ring: sleep stages", substring = true)).fetchSemanticsNodes().size)
        compose.onNode(hasContentDescription("Heart rate overnight: Strap 55 bpm, Ring 58 bpm")).assertExists()
        save("compare-ring-strap-light")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun ringAgainstStrapTop() {
        render { page(CompareState(a = f.STRAP, b = f.RING, comparison = f.ringVsStrap, loading = false))() }
        save("compare-ring-strap-top")
    }

    @Test
    @Config(qualifiers = "w411dp-h4300dp-xxhdpi")
    fun ringAgainstWatchDark() {
        render(dark = true) { page(CompareState(a = f.WATCH, b = f.RING, days = 7, comparison = f.ringVsWatch, loading = false))() }
        shows("The ring counted")
        shows("Fell asleep")
        assertEquals(3, compose.onAllNodes(hasContentDescription("Watch: sleep stages", substring = true)).fetchSemanticsNodes().size)
        save("compare-ring-watch-dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun phoneAgainstRingOneDay() {
        render { page(CompareState(a = f.RING, b = f.phoneSteps.id, comparison = f.phoneVsRing, loading = false))() }
        shows("In the 4 hours both were counting, the phone counted 58% fewer steps than the ring · only 1 day so far: a few more will show what's usual")
        save("compare-phone-ring-one-day")
    }

    @Test
    fun oneDeviceOnly() {
        render { page(CompareState(comparison = f.oneDevice, loading = false))() }
        shows("One device so far")
        shows("Wear both on the same night to compare them.")
        save("compare-one-device")
    }

    @Test
    fun neverTogetherDark() {
        render(dark = true) { page(CompareState(comparison = f.neverTogether, loading = false))() }
        shows("No nights together yet")
        save("compare-never-together-dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h2900dp-xxhdpi")
    fun narrowPhone() {
        render { page(CompareState(a = f.STRAP, b = f.RING, comparison = f.ringVsStrap, loading = false))() }
        save("compare-narrow")
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun arrangedNightsFirst() {
        val layout = LayoutPrefs(mapOf(FeatureScreens.COMPARE to ScreenLayout(order = listOf("nights", "hrv", "heart", "lowest-heart", "day-heart"), hidden = setOf("day-heart", "lowest-heart"))))
        render(layout = layout) { page(CompareState(a = f.STRAP, b = f.RING, comparison = f.ringVsStrap, loading = false))() }
        // The day's heart rate card is hidden (its two days were only on its card).
        assertEquals(0, compose.onAllNodesWithText("· 2 days", substring = true).fetchSemanticsNodes().size)
        // Where each starts on the page (positionInRoot, as the cards below the screen are clipped out of their bounds).
        val nights = compose.onNodeWithText("Night by night").fetchSemanticsNode().positionInRoot.y
        val heart = compose.onAllNodesWithText("Heart rate overnight").fetchSemanticsNodes().minOf { it.positionInRoot.y }
        assertTrue("Nights come first", nights < heart)
    }

    @Test
    fun choosingSwappingAndHowLong() {
        var a: String? = null
        var b: String? = null
        var swapped = 0
        var days = 0
        render {
            page(
                CompareState(a = f.STRAP, b = f.RING, comparison = f.ringVsStrap, loading = false),
                actions = CompareActions(chooseA = { a = it }, chooseB = { b = it }, swap = { swapped++ }, days = { days = it }),
            )()
        }
        compose.onNodeWithText("Checking").performClick()
        compose.onNodeWithText("Health Connect").performClick()
        assertEquals(f.PHONE, b)
        compose.onNodeWithText("Compare against").performClick()
        compose.onNodeWithText("Health Connect").performClick()
        assertEquals(f.PHONE, a)
        compose.onNodeWithContentDescription("Swap them").performClick()
        assertEquals(1, swapped)
        compose.onNodeWithText("30 nights").performClick()
        assertEquals(30, days)
    }

    @Test
    @Config(qualifiers = "w411dp-h2100dp-xxhdpi")
    fun devicesOffersItWithTwoDevices() {
        var opened = 0
        val ring = Device(f.RING, DeviceKind.COLMI_RING, "aa:bb", "COLMI R02_C301", lastSyncAt = f.now - 600_000L, createdAt = 0, samples = 4200)
        val strap = Device(f.STRAP, DeviceKind.HEART_RATE_STRAP, "cc:dd", "Polar H10 7A2B4C1D", lastSyncAt = f.now - 900_000L, createdAt = 0, samples = 980)
        render {
            DevicesScreen(DevicesState(serverDevices = listOf(ring, strap), loading = false), DevicesActions(compare = { opened++ }), now = f.now)
        }
        shows("Heart rate strap")
        compose.onNodeWithText("Compare devices").performScrollTo().performClick()
        assertEquals(1, opened)
        save("devices-compare-entry")
    }

    @Test
    fun devicesLeavesItOutWithOneDevice() {
        val ring = Device(f.RING, DeviceKind.COLMI_RING, "aa:bb", "COLMI R02_C301", lastSyncAt = f.now - 600_000L, createdAt = 0, samples = 4200)
        render { DevicesScreen(DevicesState(serverDevices = listOf(ring), loading = false), DevicesActions(compare = {}), now = f.now) }
        assertEquals(0, compose.onAllNodesWithText("Compare devices").fetchSemanticsNodes().size)
    }
}
