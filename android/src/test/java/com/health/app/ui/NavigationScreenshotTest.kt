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
import com.health.api.AiStatus
import com.health.api.Device
import com.health.api.DeviceKind
import com.health.api.DigestPrefs
import com.health.api.Goals
import com.health.api.Module
import com.health.api.Profile
import com.health.api.Role
import com.health.api.User
import com.health.app.data.Palette
import com.health.app.data.Settings
import com.health.app.data.ThemeMode
import com.health.app.devices.strap.PairedStrap
import com.health.app.ui.care.DigestSettingsScreen
import com.health.app.ui.components.LocalBack
import com.health.app.ui.devices.DevicesActions
import com.health.app.ui.devices.DevicesScreen
import com.health.app.ui.devices.DevicesState
import com.health.app.ui.settings.SettingsActions
import com.health.app.ui.settings.SettingsPage
import com.health.app.ui.settings.SettingsScreen
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.you.YouActions
import com.health.app.ui.you.YouScreen
import com.health.app.ui.you.YouState
import com.health.app.ui.you.YouText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId
import java.util.Locale

/**
 * The app's map: You (devices, records, Where your data goes and Settings in five groups), the
 * Care tab until its own page is in, each Settings page, light and dark (You in Violet too), and
 * a page within a tab with its back arrow; and where each row goes. PNGs in
 * android/build/outputs/screenshots as you_* and nav_*.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class NavigationScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val settings = Settings(
        server = "https://health.home.arpa", token = "t", serverName = "Health at home",
        user = User("u1", "maddie", "Maddie", Role.ADMIN, createdAt = 0),
    )
    private val modules = Module.DEFAULT + setOf(Module.MIND, Module.CYCLE, Module.MEDICATIONS, Module.VITALS, Module.HABITS, Module.SYMPTOMS, Module.PHOTOS)
    private val profile = Profile(
        birthYear = 1994, heightCm = 170.0, timezone = "America/New_York", modules = modules,
        goals = Goals(steps = 8000, activeCalories = 450, sleepMinutes = 480, protein = 120, targetWeightKg = 65.0, weeklyChangeKg = -0.25),
        digest = DigestPrefs(enabled = true, day = 7, hour = 18),
    )
    private val zone = ZoneId.of("America/New_York")
    private val now = 1_763_400_000_000L

    private fun render(
        dark: Boolean = false, palette: Palette = Palette.AURORA, selected: String? = "you", back: (() -> Unit)? = null, onTab: (String) -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = palette) {
                AppFrame(selected = selected, snackbar = remember { SnackbarHostState() }, onTab = onTab) {
                    CompositionLocalProvider(LocalBack provides back) { content() }
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

    private fun shows(text: String) = assertTrue("Shows $text", compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty())

    // ---- You

    private val devices = listOf(
        Device("r", DeviceKind.COLMI_RING, "aa", "Ring", battery = 78, lastSyncAt = now - 4 * 60_000L, createdAt = 0, samples = 4200),
        Device("s", DeviceKind.HEART_RATE_STRAP, "bb", "Chest strap", battery = 90, lastSyncAt = now - 26 * 3_600_000L, createdAt = 0, samples = 900),
        Device("m", DeviceKind.MANUAL, "manual", "Entered by hand", lastSyncAt = now - 3_600_000L, createdAt = 0, samples = 12),
    )

    private val you = YouState(
        devices = YouText.devices(devices),
        strap = PairedStrap("bb", "Chest strap", battery = 90),
        assistant = true,
        privacy = "2 ways out on · 1 paused",
        week = "Slept more, walked less than last week",
        serverVersion = "0.8.0",
    )

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun youLight() {
        render { YouScreen(you, YouActions(), now = now, zone = zone) }
        listOf("You", "Your devices", "Ring", "Synced 4 min ago · battery 78%", "Chest strap", "Ready · battery 90%", "Record tonight", "Manage devices").forEach(::shows)
        listOf("Your week", "Slept more, walked less than last week", "A report for your doctor", "Assistant", "Where your data goes", "2 ways out on · 1 paused").forEach(::shows)
        SettingsPage.entries.forEach { shows(it.title) }
        // Entered by hand isn't a device to show.
        assertEquals(0, compose.onAllNodesWithText("Entered by hand").fetchSemanticsNodes().size)
        save("you_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun youDark() {
        render(dark = true) { YouScreen(you, YouActions(), now = now, zone = zone) }
        save("you_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun youVioletLight() {
        render(palette = Palette.VIOLET) { YouScreen(you.copy(recordingSince = now - 2 * 3_600_000L), YouActions(), now = now, zone = zone) }
        shows("Recording since")
        shows("Stop")
        save("you_violet_light")
    }

    @Test
    fun youFirstRunHasAWayToAddADevice() {
        render { YouScreen(YouState(), YouActions(), now = now, zone = zone) }
        shows("No devices yet")
        shows("Add a device")
    }

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun everyRowOfYouGoesSomewhere() {
        val opened = mutableListOf<String>()
        var recorded = 0
        render { YouScreen(you, YouActions(open = { opened += it }, recordStrap = { recorded++ }), now = now, zone = zone) }
        compose.onNode(hasContentDescription("Record tonight with Chest strap")).performClick()
        listOf("Manage devices", "Your week", "A report for your doctor", "Assistant", "Where your data goes").forEach { compose.onNodeWithText(it).performScrollTo().performClick() }
        SettingsPage.entries.forEach { compose.onNodeWithText(it.title).performScrollTo().performClick() }
        assertEquals(1, recorded)
        assertEquals(listOf("devices", "digest", "reports", "assistant", "settings/privacy") + SettingsPage.entries.map { it.route }, opened)
    }

    // ---- A page within a tab has a back arrow

    @Test
    fun aPageWithinHasAWayBack() {
        var back = 0
        val ring = Device("r", DeviceKind.COLMI_RING, "aa", "COLMI R02_C301", lastSyncAt = now - 600_000L, createdAt = 0, samples = 4200)
        val strap = Device("s", DeviceKind.HEART_RATE_STRAP, "bb", "Polar H10 7A2B", lastSyncAt = now - 900_000L, createdAt = 0, samples = 900)
        render(back = { back++ }) { DevicesScreen(DevicesState(serverDevices = listOf(ring, strap), loading = false), DevicesActions(compare = {}), now = now) }
        save("nav_devices_with_back")
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
    }

    // ---- Settings, five pages

    private fun settingsPage(page: SettingsPage, dark: Boolean, actions: SettingsActions = SettingsActions(), onOpen: (String) -> Unit = {}) {
        render(dark = dark) {
            SettingsScreen(
                page, settings, profile, actions, onBack = {}, onOpen = onOpen,
                ai = AiStatus(available = true), hiddenFromHealth = setOf("PHOTOS"), arrangedScreens = 4,
            )
        }
        save("nav_settings_${page.name.lowercase()}_${if (dark) "dark" else "light"}")
    }

    @Test
    @Config(qualifiers = "w411dp-h2300dp-xxhdpi")
    fun trackingLight() {
        val opened = mutableListOf<String>()
        settingsPage(SettingsPage.TRACKING, false, onOpen = { opened += it })
        shows("What you track")
        shows("Hidden from Care")
        compose.onNodeWithText("Your conditions and goals").performClick()
        compose.onNodeWithText("Fields").performClick()
        assertEquals(listOf("settings/conditions", "settings/fields"), opened)
    }

    @Test
    @Config(qualifiers = "w411dp-h2300dp-xxhdpi")
    fun trackingDark() = settingsPage(SettingsPage.TRACKING, true)

    @Test
    fun remindersLight() {
        val opened = mutableListOf<String>()
        settingsPage(SettingsPage.REMINDERS, false, onOpen = { opened += it })
        shows("Reminders, alerts and sharing")
        listOf("Reminders", "Health alerts", "Weekly digest", "Sharing").forEach { compose.onNodeWithText(it).performClick() }
        assertEquals(listOf("settings/reminders", "alerts", "settings/digest", "settings/sharing"), opened)
    }

    @Test
    fun remindersDark() = settingsPage(SettingsPage.REMINDERS, true)

    @Test
    @Config(qualifiers = "w411dp-h1700dp-xxhdpi")
    fun profileLight() {
        settingsPage(SettingsPage.YOU, false)
        shows("Profile and goals")
        shows("Calorie estimates use")
    }

    @Test
    @Config(qualifiers = "w411dp-h1700dp-xxhdpi")
    fun profileDark() = settingsPage(SettingsPage.YOU, true)

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun connectionsLight() {
        val opened = mutableListOf<String>()
        val web = mutableListOf<String>()
        settingsPage(SettingsPage.CONNECTIONS, false, SettingsActions(openWeb = { web += it }), onOpen = { opened += it })
        // Where your data goes is on You, highlighted, not here.
        assertEquals(0, compose.onAllNodesWithText("Where your data goes").fetchSemanticsNodes().size)
        listOf("Mealie", "AI assistant", "Webhooks & Home Assistant", "Pebble watch", "Send to other apps").forEach {
            compose.onNodeWithText(it).performScrollTo().performClick()
        }
        compose.onNodeWithText("AI connector links").performScrollTo().performClick()
        assertEquals(listOf("settings/mealie", "settings/ai", "settings/integrations", "settings/watch", "settings/export"), opened)
        assertEquals(listOf("connector"), web)
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun connectionsDark() = settingsPage(SettingsPage.CONNECTIONS, true)

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun lookAndAccountLight() {
        var signedOut = 0
        settingsPage(SettingsPage.LOOK, false, SettingsActions(signOut = { signedOut++ }))
        listOf("Look and account", "Theme", "Bold text", "4 screens arranged.", "maddie on Health at home").forEach(::shows)
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        compose.onAllNodesWithText("Sign out")[1].performClick()
        assertEquals(1, signedOut)
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun lookAndAccountDark() = settingsPage(SettingsPage.LOOK, true)

    @Test
    fun weeklyDigestSettings() {
        render { DigestSettingsScreen(profile, onBack = {}) }
        shows("Weekly digest")
        shows("A weekly digest")
        save("nav_weekly_digest_light")
    }
}
