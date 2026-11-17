package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.health.app.ui.care.DigestSettingsScreen
import com.health.app.ui.components.LocalBack
import com.health.app.ui.devices.DevicesActions
import com.health.app.ui.devices.DevicesScreen
import com.health.app.ui.devices.DevicesState
import com.health.app.ui.modules.HubScreen
import com.health.app.ui.more.MoreScreen
import com.health.app.ui.more.MoreState
import com.health.app.ui.recent.RecentActions
import com.health.app.ui.recent.RecentFixtures
import com.health.app.ui.recent.RecentScreen
import com.health.app.ui.recent.RecentState
import com.health.app.ui.settings.SettingsActions
import com.health.app.ui.settings.SettingsPage
import com.health.app.ui.settings.SettingsScreen
import com.health.app.ui.theme.HealthTheme
import com.health.api.AiStatus
import com.health.api.Units
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
 * The app's map: the five tabs along the bottom, Health, More, and each Settings page, light and
 * dark; a page within a tab with its way back; and where each row goes. PNGs in
 * android/build/outputs/screenshots.
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

    private fun render(dark: Boolean = false, selected: String? = "more", back: (() -> Unit)? = null, onTab: (String) -> Unit = {}, content: @Composable () -> Unit) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
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

    private val more = MoreState(unseenAlerts = 3, compare = true, assistant = true, serverVersion = "0.7.1")

    // ---- More

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun moreLight() {
        render { MoreScreen(more) {} }
        listOf("Your records", "Health alerts", "Your week", "Reports & export", "Devices", "Compare devices", "Assistant", "Settings").forEach(::shows)
        SettingsPage.entries.forEach { shows(it.title) }
        compose.onNodeWithContentDescription("3 new").assertExists()
        save("nav_more_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun moreDark() {
        render(dark = true) { MoreScreen(more) {} }
        save("nav_more_dark")
    }

    @Test
    fun moreWithoutCompareOrAssistant() {
        render { MoreScreen(MoreState()) {} }
        assertEquals(0, compose.onAllNodesWithText("Compare devices").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Ask about your own data").fetchSemanticsNodes().size)
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun everyRowOfMoreGoesSomewhere() {
        val opened = mutableListOf<String>()
        render { MoreScreen(more) { opened += it } }
        listOf("Health alerts", "Your week", "Reports & export", "Compare devices", "Assistant").forEach { compose.onNodeWithText(it).performScrollTo().performClick() }
        compose.onAllNodesWithText("Devices")[1].performScrollTo().performClick()
        SettingsPage.entries.forEach { compose.onNodeWithText(it.title).performScrollTo().performClick() }
        assertEquals(
            listOf("alerts", "digest", "reports", "devices/compare", "assistant", "devices") + SettingsPage.entries.map { it.route },
            opened,
        )
    }

    // ---- The tabs

    @Test
    fun theFiveTabsAndWhereTheyGo() {
        val tapped = mutableListOf<String>()
        render(selected = "recent", onTab = { tapped += it }) { RecentScreen(RecentState(readings = RecentFixtures.all, loading = false), Units.METRIC, RecentFixtures.zone, RecentActions(), now = RecentFixtures.now) }
        listOf("Today", "24 hours", "Health", "Trends", "More").forEach { compose.onNodeWithText(it).performClick() }
        assertEquals(listOf("today", "recent", "health", "trends", "more"), tapped)
        // The 24 hours tab is a tab's own page: no way back.
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Back")).fetchSemanticsNodes().size)
        save("nav_tab_24_hours_light")
    }

    @Test
    fun twentyFourHoursTabDark() {
        render(dark = true, selected = "recent") { RecentScreen(RecentState(readings = RecentFixtures.all, loading = false), Units.METRIC, RecentFixtures.zone, RecentActions(), now = RecentFixtures.now) }
        save("nav_tab_24_hours_dark")
    }

    // ---- Health

    @Test
    fun healthLight() {
        var opened: String? = null
        render(selected = "health") { HubScreen(modules, showMeasurements = true) { opened = it } }
        shows("Food")
        shows("Body measurements")
        assertEquals(0, compose.onAllNodesWithText("Devices").fetchSemanticsNodes().size)
        save("nav_health_light")
        compose.onNodeWithText("Choose what you track").performScrollTo().performClick()
        assertEquals(SettingsPage.TRACKING.route, opened)
    }

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun healthDark() {
        render(dark = true, selected = "health") { HubScreen(modules, showMeasurements = true) {} }
        save("nav_health_dark")
    }

    // ---- A page within a tab has a way back

    @Test
    fun aPageWithinHasAWayBack() {
        var back = 0
        val ring = Device("r", DeviceKind.COLMI_RING, "aa", "COLMI R02_C301", lastSyncAt = RecentFixtures.now - 600_000L, createdAt = 0, samples = 4200)
        val strap = Device("s", DeviceKind.HEART_RATE_STRAP, "bb", "Polar H10 7A2B", lastSyncAt = RecentFixtures.now - 900_000L, createdAt = 0, samples = 900)
        render(back = { back++ }) { DevicesScreen(DevicesState(serverDevices = listOf(ring, strap), loading = false), DevicesActions(compare = {}), now = RecentFixtures.now) }
        save("nav_devices_with_back")
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
    }

    // ---- Settings, a page each

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
    @Config(qualifiers = "w411dp-h1700dp-xxhdpi")
    fun youLight() = settingsPage(SettingsPage.YOU, false)

    @Test
    @Config(qualifiers = "w411dp-h1700dp-xxhdpi")
    fun youDark() = settingsPage(SettingsPage.YOU, true)

    @Test
    @Config(qualifiers = "w411dp-h2300dp-xxhdpi")
    fun trackingLight() {
        val opened = mutableListOf<String>()
        settingsPage(SettingsPage.TRACKING, false, onOpen = { opened += it })
        shows("Hidden from the Health tab")
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
        listOf("Reminders", "Weekly digest", "Sharing").forEach { compose.onNodeWithText(it).performClick() }
        assertEquals(listOf("settings/reminders", "settings/digest", "settings/sharing"), opened)
    }

    @Test
    fun remindersDark() = settingsPage(SettingsPage.REMINDERS, true)

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun connectionsLight() {
        val opened = mutableListOf<String>()
        val web = mutableListOf<String>()
        settingsPage(SettingsPage.CONNECTIONS, false, SettingsActions(openWeb = { web += it }), onOpen = { opened += it })
        // Where your data goes comes first.
        val where = compose.onNodeWithText("Where your data goes").fetchSemanticsNode().positionInRoot.y
        val sync = compose.onNodeWithText("Sync in the background").fetchSemanticsNode().positionInRoot.y
        assertTrue(where < sync)
        listOf("Where your data goes", "Mealie", "AI assistant", "Webhooks & Home Assistant", "Pebble watch", "Send to other apps").forEach {
            compose.onNodeWithText(it).performScrollTo().performClick()
        }
        compose.onNodeWithText("AI connector links").performScrollTo().performClick()
        assertEquals(listOf("settings/privacy", "settings/mealie", "settings/ai", "settings/integrations", "settings/watch", "settings/export"), opened)
        assertEquals(listOf("connector"), web)
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun connectionsDark() = settingsPage(SettingsPage.CONNECTIONS, true)

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun appearanceLight() {
        settingsPage(SettingsPage.APPEARANCE, false)
        shows("4 screens arranged.")
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun appearanceDark() = settingsPage(SettingsPage.APPEARANCE, true)

    @Test
    fun accountLight() {
        var signedOut = 0
        settingsPage(SettingsPage.ACCOUNT, false, SettingsActions(signOut = { signedOut++ }))
        shows("maddie on Health at home")
        compose.onNodeWithText("Sign out").performClick()
        compose.onAllNodesWithText("Sign out")[1].performClick()
        assertEquals(1, signedOut)
    }

    @Test
    fun accountDark() = settingsPage(SettingsPage.ACCOUNT, true)

    @Test
    fun weeklyDigestSettings() {
        render { DigestSettingsScreen(profile, onBack = {}) }
        shows("Weekly digest")
        shows("A weekly digest")
        save("nav_weekly_digest_light")
    }
}
