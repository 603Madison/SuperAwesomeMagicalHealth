package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.health.api.AiConsent
import com.health.api.AiStatus
import com.health.api.DataDestination
import com.health.api.DataMap
import com.health.api.DataPath
import com.health.api.DataPathState
import com.health.api.LayoutPrefs
import com.health.api.MealieStatus
import com.health.api.Module
import com.health.api.ScreenLayout
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.ai.AiActions
import com.health.app.ui.ai.AiChatScreen
import com.health.app.ui.ai.AiState
import com.health.app.ui.food.FoodActions
import com.health.app.ui.food.FoodScreen
import com.health.app.ui.food.FoodState
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.LocalArrangements
import com.health.app.ui.settings.MealieActions
import com.health.app.ui.settings.MealieScreen
import com.health.app.ui.settings.MealieState
import com.health.app.ui.settings.WhereActions
import com.health.app.ui.settings.WhereDataGoes
import com.health.app.ui.settings.WhereDataGoesScreen
import com.health.app.ui.settings.WhereState
import com.health.app.ui.theme.HealthTheme
import com.health.api.FoodDay
import com.health.api.Nutrients
import com.health.api.NutritionTarget
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
import java.time.LocalDate
import java.util.Locale

/**
 * Settings › Where your data goes, shaped like hers: the food database and the assistant on, Mealie
 * paused, two AI connector links and a share, webhooks and Home Assistant not set up, email turned
 * off on the server, and signing in through her server's sign-in service; then this phone's part.
 * Also Mealie and the assistant where they're paused. PNGs in android/build/outputs/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class PrivacyScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val map = DataMap(
        listOf(
            DataDestination(
                DataPath.FOOD_DATABASE, "Open Food Facts", "A non-profit food database anyone can use",
                hosts = listOf("search.openfoodfacts.org", "world.openfoodfacts.org"),
                what = "The words you search for and the barcodes you scan. Never who you are or what you ate.",
                whenSent = "When you search for a food or scan a barcode", state = DataPathState.ON,
            ),
            DataDestination(
                DataPath.WEATHER, "Open-Meteo", "A free weather service",
                hosts = listOf("api.open-meteo.com", "archive-api.open-meteo.com", "geocoding-api.open-meteo.com"),
                what = "A rough place (to about 10 km) and dates, to fetch the air pressure, and the words you type when you look for a place. Never your symptoms.",
                whenSent = "When your symptom patterns are worked out, and when you look for a place", state = DataPathState.NOT_SET_UP, setUp = "settings.profile",
            ),
            DataDestination(
                DataPath.MEALIE, "Mealie", "Your own Mealie", hosts = listOf("mealie.home.arpa"),
                what = "Your recipe searches and the days of your meal plan, with your Mealie token. Nothing is ever written to Mealie.",
                whenSent = "When you search for a food or open Food", state = DataPathState.PAUSED, setUp = "settings.connections",
            ),
            DataDestination(
                DataPath.AI_ASSISTANT, "The assistant", "Anthropic, who make Claude", hosts = listOf("api.anthropic.com"),
                what = "What you ask it and the readings it looks up to answer (only the features you let it read), and food photos you send it.",
                whenSent = "Only when you ask it something or snap a meal", state = DataPathState.ON, setUp = "settings.ai",
            ),
            DataDestination(
                DataPath.AI_CONNECTORS, "AI connector links", "The AI app each link was made for",
                what = "What that AI asks for through its link: only the features the link names. It comes to this server to ask; nothing is sent until it does.",
                whenSent = "When you ask that AI something about your health", state = DataPathState.ON, count = 2, setUp = "settings.connector",
            ),
            DataDestination(
                DataPath.WEBHOOKS, "Webhooks", "Addresses you set up, like Home Assistant",
                what = "The events you chose for each one: times and counts, and names only where you turned details on.",
                whenSent = "When one of those events happens", state = DataPathState.NOT_SET_UP, count = 0, setUp = "settings.integrations",
            ),
            DataDestination(
                DataPath.BROWSER_PUSH, "Browser notifications", "Your browser's push service (Google's for Chrome, Mozilla's for Firefox, Apple's for Safari)",
                what = "Reminders and alerts, sealed so only your browser can open them. The push service sees when one is sent, not what it says. The phone app's own reminders never use this.",
                whenSent = "When a reminder or alert is due", state = DataPathState.NOT_SET_UP, count = 0, setUp = "settings.notifications",
            ),
            DataDestination(
                DataPath.EMAIL, "Weekly email", "This server's mail provider, then your inbox's",
                what = "Your weekly digest, leaving out private features you didn't list for email. Email isn't sealed: mail providers along the way can read it.",
                whenSent = "Once a week, if you asked for it by email", state = DataPathState.OFF_ON_SERVER, setUp = "settings.digest",
            ),
            DataDestination(
                DataPath.SHARES, "Shares", "People on this server you share with",
                what = "The features each share names, when they look. It stays on this server.",
                whenSent = "When they open what you share", state = DataPathState.ON, count = 1, setUp = "settings.sharing",
            ),
            DataDestination(
                DataPath.HOME_ASSISTANT, "Home Assistant keys", "Your Home Assistant",
                what = "Today's numbers (sleep, readiness, steps and the like) when Home Assistant asks with its key.",
                whenSent = "Each time Home Assistant checks in", state = DataPathState.NOT_SET_UP, count = 0, setUp = "settings.integrations",
            ),
            DataDestination(
                DataPath.SIGN_IN, "Signing in", "The sign-in service this server uses", hosts = listOf("auth.home.arpa"),
                what = "That you're signing in. It sends this server your name and email; nothing about your health goes to it.",
                whenSent = "When you sign in", state = DataPathState.ON, pausable = false,
            ),
        ),
        "Everything else stays on this server: your readings, diary and photos are never sent anywhere unless it's listed here. The apps load nothing from anywhere else (no fonts, trackers or adverts).",
    )

    private val phone = WhereDataGoes.Phone(
        server = "https://health.home.arpa", ring = "R02_9E33", healthConnect = true, writes = listOf("Weight", "Workouts"),
    )

    private fun render(dark: Boolean = false, layout: LayoutPrefs? = null, tab: String = "settings", content: @Composable () -> Unit) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                CompositionLocalProvider(LocalArrangements provides layout) {
                    AppFrame(selected = tabOf(tab), snackbar = remember { SnackbarHostState() }, onTab = {}, content = content)
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

    @Test
    @Config(qualifiers = "w411dp-h4100dp-xxhdpi")
    fun wholePageLight() {
        render { WhereDataGoesScreen(WhereState(map = map, loading = false), phone, WhereActions()) }
        shows(WhereDataGoes.TITLE)
        shows("On now: Open Food Facts, the assistant, AI connector links, Shares and Signing in.")
        shows("Paused: nothing goes")
        shows("On · 2 links")
        shows("Turned off on this server")
        shows("Always on: it's how you sign in")
        shows("Set up in Webhooks & Home Assistant")
        shows("Choose a place in Symptoms")
        shows("api.anthropic.com")
        shows("Everything else stays on this server")
        shows("This app only talks to your server")
        shows("https://health.home.arpa")
        shows("Written back to it, as you chose in Send to other apps: weight and workouts.")
        save("privacy-light")
    }

    @Test
    fun topOfThePage() {
        render { WhereDataGoesScreen(WhereState(map = map, loading = false), phone, WhereActions()) }
        save("privacy-top")
    }

    @Test
    @Config(qualifiers = "w411dp-h4100dp-xxhdpi")
    fun wholePageDark() {
        render(dark = true) { WhereDataGoesScreen(WhereState(map = map, loading = false), phone.copy(healthConnect = false, writes = emptyList(), gadgetbridgeFile = "Gadgetbridge.db"), WhereActions()) }
        shows("Nothing is written back to it.")
        shows("(Gadgetbridge.db)")
        save("privacy-dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun thisPhoneFirst() {
        val layout = LayoutPrefs(mapOf(FeatureScreens.PRIVACY to ScreenLayout(order = listOf("phone", "server"))))
        render(layout = layout) { WhereDataGoesScreen(WhereState(map = map, loading = false), phone, WhereActions()) }
        val phoneTop = compose.onNodeWithText("On this phone").fetchSemanticsNode().boundsInRoot.top
        val serverTop = compose.onNodeWithText("From your server").fetchSemanticsNode().boundsInRoot.top
        assertTrue("This phone comes first", phoneTop < serverTop)
    }

    @Test
    fun switchesPauseAndResume_andLinksOpenTheirPages() {
        val changes = mutableListOf<Pair<DataPath, Boolean>>()
        val opened = mutableListOf<String>()
        render { WhereDataGoesScreen(WhereState(map = map, loading = false), phone, WhereActions(set = { p, on -> changes += p to on }, open = { opened += it })) }
        // Mealie is paused: its switch turns it back on. The food database is on: its switch pauses it.
        compose.onNode(isToggleable() and hasText("Mealie")).performScrollTo().performClick()
        compose.onNode(isToggleable() and hasText("Open Food Facts")).performScrollTo().performClick()
        assertEquals(listOf(DataPath.MEALIE to true, DataPath.FOOD_DATABASE to false), changes)
        // Signing in, and what isn't set up, have no switch.
        assertEquals(0, compose.onAllNodes(isToggleable() and hasText("Signing in")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(isToggleable() and hasText("Webhooks")).fetchSemanticsNodes().size)
        compose.onAllNodesWithText("Set up in Webhooks & Home Assistant")[0].performScrollTo().performClick()
        compose.onNodeWithText("Writing: Send to other apps").performScrollTo().performClick()
        assertEquals(listOf("settings/integrations", "settings/export"), opened)
    }

    @Test
    fun loadingAndError() {
        var retried = 0
        render { WhereDataGoesScreen(WhereState(loading = false, error = "Can't reach the server"), phone, WhereActions(retry = { retried++ })) }
        shows("Can't reach the server")
        // This phone's part shows even without the server.
        shows("This app only talks to your server")
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    // ---- Where Mealie and the assistant are paused

    @Test
    fun mealiePausedInSettings() {
        var opened = 0
        val status = MealieStatus(connected = true, url = "http://mealie.home.arpa:9000", user = "Maddie", version = "v3.1.0", paused = true)
        render { MealieScreen(MealieState(status = status, loading = false), MealieActions(privacy = { opened++ }), onBack = {}) }
        shows(WhereDataGoes.PAUSED)
        compose.onNodeWithContentDescription("Open Where your data goes").performClick()
        assertEquals(1, opened)
        save("mealie-paused")
    }

    @Test
    fun mealPlanPausedOnFood() {
        var opened = 0
        val today = LocalDate.of(2026, 11, 12)
        val day = FoodDay(today.toString(), emptyList(), emptyList(), Nutrients(1240.0, 82.0, 131.0, 41.0), 1250, NutritionTarget(1950, 120, 210, 65, 2000, "Your goal"))
        val state = FoodState(today, today, day = day, loading = false, mealie = true, mealiePaused = true, plan = null)
        render(tab = "food") { FoodScreen(state, Units.METRIC, FoodActions(privacy = { opened++ })) }
        shows("Mealie is paused in Where your data goes")
        compose.onNodeWithContentDescription("Open Where your data goes").performClick()
        assertEquals(1, opened)
        save("food-mealie-paused")
    }

    @Test
    fun assistantPaused() {
        var opened = 0
        var asked = 0
        val status = AiStatus(available = true, consent = AiConsent(setOf(Module.SLEEP, Module.ACTIVITY), acknowledged = true), usedToday = 3, dailyLimit = 40, paused = true)
        render { AiChatScreen(AiState(status = status, loading = false), status, onBack = {}, actions = AiActions(privacy = { opened++ }, ask = { asked++ })) }
        shows("The assistant is paused in Where your data goes")
        compose.onNode(hasContentDescription("Open Where your data goes")).performClick()
        assertEquals(1, opened)
        // Nothing can be asked while it's paused: no suggestions to tap, and the box is off.
        assertEquals(0, compose.onAllNodesWithText("How has my sleep been this week?").fetchSemanticsNodes().size)
        compose.onNodeWithText("Ask something").assertIsNotEnabled()
        assertEquals(0, asked)
        save("assistant-paused")
    }
}
