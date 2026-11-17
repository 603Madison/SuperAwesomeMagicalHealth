package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import com.health.app.ui.devices.DevicesActions
import com.health.app.ui.devices.DevicesScreen
import com.health.app.ui.devices.DevicesState
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.LocalArrangements
import com.health.app.ui.recent.RecentActions
import com.health.app.ui.recent.RecentFixtures
import com.health.app.ui.recent.RecentScreen
import com.health.app.ui.recent.RecentState
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
 * The last 24 hours, shaped like hers: a COLMI R02's night with its stages, heart rate, blood
 * oxygen, HRV and stress, and the phone's steps. All devices in light, the ring alone in dark,
 * imperial units (with a ring that measures skin temperature), nothing recorded, and a narrow
 * phone. PNGs in android/build/outputs/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class RecentScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val f = RecentFixtures

    private fun render(dark: Boolean = false, layout: LayoutPrefs? = null, content: @Composable () -> Unit) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                CompositionLocalProvider(LocalArrangements provides layout) {
                    AppFrame(selected = "recent", snackbar = remember { SnackbarHostState() }, onTab = {}, content = content)
                }
            }
        }
    }

    private fun page(state: RecentState, units: Units = Units.METRIC, actions: RecentActions = RecentActions()) = @Composable {
        RecentScreen(state, units, f.zone, actions, now = f.now)
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
    @Config(qualifiers = "w411dp-h2060dp-xxhdpi")
    fun allDevicesLight() {
        render { page(RecentState(readings = f.all, loading = false))() }
        shows("Last 24 hours")
        shows("As your devices recorded it")
        shows("All devices")
        shows("7h 7m")
        shows("COLMI R02_C301")
        compose.onNode(hasContentDescription("Heart rate from", substring = true)).assertExists()
        compose.onNode(hasContentDescription("Sleep stages: deep 1h 25m, REM 1h 46m, light 3h 56m, awake 15m")).assertExists()
        compose.onNode(hasContentDescription("Steps by the hour, 9,260 in all")).assertExists()
        save("recent-all-light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1820dp-xxhdpi")
    fun ringAloneDark() {
        render(dark = true) { page(RecentState(device = f.RING, readings = f.ringAlone, loading = false))() }
        shows("COLMI R02_C301")
        // The ring has no steps: no Steps card.
        assertEquals(0, compose.onAllNodes(hasText("steps in all")).fetchSemanticsNodes().size)
        save("recent-ring-dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h2920dp-xxhdpi")
    fun imperialUnits() {
        render { page(RecentState(readings = f.withTemperature, loading = false), Units.IMPERIAL)() }
        shows("°F")
        shows("Compared with your usual")
        shows("Breathing rate")
        save("recent-imperial")
    }

    @Test
    fun nothingRecorded() {
        val known = listOf(
            Device(f.RING, DeviceKind.COLMI_RING, "aa:bb", "COLMI R02_C301", lastSyncAt = f.now - 3 * 3_600_000L - 600_000L, createdAt = 0),
        )
        var synced = 0
        render { page(RecentState(readings = f.empty, known = known, loading = false), actions = RecentActions(syncNow = { synced++ }))() }
        shows(com.health.app.ui.recent.Recent.NOTHING)
        shows("Last synced 3 h ago")
        save("recent-empty")
        compose.onNodeWithText("Sync now").performClick()
        assertEquals(1, synced)
    }

    @Test
    @Config(qualifiers = "w360dp-h2100dp-xxhdpi")
    fun narrowPhone() {
        render { page(RecentState(readings = f.all, loading = false))() }
        save("recent-narrow")
    }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun arrangedAndNoNight() {
        // Heart rate moved first, steps hidden; and a day without a night.
        val layout = LayoutPrefs(mapOf(FeatureScreens.RECENT to ScreenLayout(order = listOf("heart", "sleep"), hidden = setOf("steps", "spo2", "hrv"))))
        render(layout = layout) { page(RecentState(readings = f.all.copy(sleep = emptyList()), loading = false))() }
        shows("No sleep recorded in the last 24 hours.")
        assertEquals(0, compose.onAllNodes(hasText("steps in all")).fetchSemanticsNodes().size)
        val heart = compose.onNodeWithText("Heart rate").fetchSemanticsNode().boundsInRoot.top
        val sleep = compose.onNodeWithText("Sleep").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Heart rate is first", heart < sleep)
        save("recent-arranged")
    }

    @Test
    fun choosingADevice() {
        var chosen: String? = "unset"
        render { page(RecentState(readings = f.all, loading = false), actions = RecentActions(choose = { chosen = it }))() }
        compose.onNodeWithText("Health Connect").performClick()
        assertEquals(f.PHONE, chosen)
        compose.onNodeWithText("All devices").performClick()
        assertEquals(null, chosen)
    }

    @Test
    fun eachDeviceOpensItsLast24Hours() {
        var opened: String? = null
        val ring = Device(f.RING, DeviceKind.COLMI_RING, "aa:bb", "COLMI R02_C301", lastSyncAt = f.now - 600_000L, createdAt = 0, samples = 4200)
        compose.setContent {
            HealthTheme(mode = ThemeMode.LIGHT, palette = Palette.AURORA) {
                DevicesScreen(DevicesState(serverDevices = listOf(ring), loading = false), DevicesActions(recent = { opened = it }), now = f.now)
            }
        }
        compose.onNodeWithText("COLMI R02_C301", substring = true).performScrollTo().performClick()
        assertEquals(f.RING, opened)
    }
}
