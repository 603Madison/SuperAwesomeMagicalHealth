package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.health.api.AlertKind
import com.health.api.AlertLevel
import com.health.api.AppPage
import com.health.api.DayFeed
import com.health.api.DaySummary
import com.health.api.FoodEntry
import com.health.api.FoodSuggestion
import com.health.api.HealthAlert
import com.health.api.Insight
import com.health.api.LayoutPrefs
import com.health.api.Meal
import com.health.api.Module
import com.health.api.NowAction
import com.health.api.NowItem
import com.health.api.NowKind
import com.health.api.Nutrients
import com.health.api.Point
import com.health.api.QuickAction
import com.health.api.Score
import com.health.api.SleepStage
import com.health.api.SleepSummary
import com.health.api.StageSpan
import com.health.api.TimelineItem
import com.health.api.TimelineKind
import com.health.api.Units
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.Backdrop
import com.health.app.ui.components.LocalLayoutStore
import com.health.app.ui.components.MemoryLayoutStore
import com.health.app.ui.log.LogSheetActions
import com.health.app.ui.log.LogSheetContent
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.today.TodayActions
import com.health.app.ui.today.TodayLayout
import com.health.app.ui.today.TodayRows
import com.health.app.ui.today.TodayScreen
import com.health.app.ui.today.TodayState
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
import java.time.ZoneId
import java.util.Locale

/**
 * Today, redesigned: the day and a greeting with the bell, the health alert's card, the three
 * scores with their sentence, Right now and Your day from the server's day feed, and For you;
 * and the + sheet. A realistic afternoon, in Aurora light and dark, Ocean and Ember, at 360 dp
 * with text at 130%, an urgent alert, a day gone by, the feed out of reach, and viewing a share.
 * PNGs in android/build/outputs/screenshots as today_* and plus_*.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h2000dp-xxhdpi")
class TodayScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 11, 17)
    private fun at(h: Int, m: Int = 0, day: LocalDate = today): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(13, 42)
    private val modules = Module.DEFAULT + setOf(Module.MEDICATIONS, Module.CONTINENCE, Module.HORMONES, Module.TRANSITION)

    // ---- An afternoon like hers

    private val stages: List<StageSpan> = run {
        var t = at(23, 8, today.minusDays(1))
        listOf(
            SleepStage.LIGHT to 36, SleepStage.DEEP to 64, SleepStage.LIGHT to 82, SleepStage.AWAKE to 12, SleepStage.REM to 55, SleepStage.DEEP to 46,
            SleepStage.LIGHT to 70, SleepStage.AWAKE to 8, SleepStage.REM to 57, SleepStage.LIGHT to 32,
        ).map { (stage, minutes) -> StageSpan(stage, t, t + minutes * 60_000L).also { t += minutes * 60_000L } }
    }

    private fun entry(meal: Meal, name: String, kcal: Double, protein: Double) = FoodEntry(today.toString(), meal, name, Nutrients(kcal, protein), foodId = name.lowercase())

    private val ideas = listOf(
        FoodSuggestion("Greek yoghurt", null, "1 pot", 1.0, Nutrients(150.0, 17.0), Meal.DINNER, listOf(entry(Meal.DINNER, "Greek yoghurt", 150.0, 17.0)), "One of your regulars", 9, "2026-11-12"),
        FoodSuggestion("Lentil soup", null, "1 bowl", 1.0, Nutrients(320.0, 18.0), Meal.DINNER, listOf(entry(Meal.DINNER, "Lentil soup", 320.0, 18.0)), "You had this last week", 2, "2026-11-03"),
    )

    private val summary = DaySummary(
        date = today.toString(),
        readiness = Score(90, "Optimal", emptyList()), sleep = Score(87, "Optimal", emptyList()), activity = Score(53, "Pay attention", emptyList()),
        steps = 6240, heartRate = listOf(Point(now - 4 * 60_000, 72.0)),
        sleepSummary = SleepSummary(stages.first().start, stages.last().end, 462, 482, 96, 110, 112, 220, 20, true, stages = stages),
        hasData = true,
        insights = listOf(
            Insight("warn", "Your body may be fighting something off", "Resting heart rate is up.", "thermometer", link = AppPage.ALERTS),
            Insight("good", "Well rested", "Well rested, but your body's working on something. Go easy today.", "zap", link = AppPage.TODAY),
            Insight("info", "Protein 38 g under target", "Greek yoghurt or lentil soup would close a good part of it.", "egg", link = AppPage.FOOD, suggestions = ideas),
            Insight("good", "Steady sleep this week", "Within half an hour of your usual every night.", "moon", link = AppPage.SLEEP),
        ),
    )

    private val alert = HealthAlert(
        "a1", AlertKind.GETTING_SICK, AlertLevel.WATCH, "Your body may be fighting something off",
        "Resting heart rate +6, HRV 22% lower, warmer last night. Rest helps.", "Rest, drink water, and see how tomorrow looks.",
        start = at(6, 50), createdAt = at(7, 5),
    )

    private val feed = DayFeed(
        today.toString(),
        now = listOf(
            NowItem("wearing:d", NowKind.WEARING, "Night brief", "On for 7 h, since 6:40 AM · usually about 8 h · dry so far", progress = 0.86, module = Module.CONTINENCE, page = AppPage.CONTINENCE,
                action = NowAction("Change", NowAction.CONTINENCE_CHANGE, mapOf("supplyId" to "d")), priority = 70, at = at(6, 40)),
            NowItem("dose:m2", NowKind.DOSE_DUE, "Estradiol", "2 mg · due at 2:00 PM", module = Module.MEDICATIONS, page = AppPage.MEDS,
                action = NowAction("Take", NowAction.DOSE_TAKE, mapOf("medicationId" to "m2", "scheduledFor" to at(14).toString())), priority = 80, at = at(14)),
            NowItem("food:today", NowKind.FOOD_TODAY, "Food today", "1,240 of 1,900 kcal", progress = 0.65, module = Module.NUTRITION, page = AppPage.FOOD,
                action = NowAction("Add", NowAction.FOOD_ADD, mapOf("meal" to "LUNCH", "date" to today.toString())), priority = 30),
            NowItem("alert:a1", NowKind.HEALTH_ALERT, "Your body may be fighting something off", "Resting heart rate +6.", page = AppPage.ALERTS,
                action = NowAction("Open", NowAction.ALERT_OPEN, mapOf("alertId" to "a1")), priority = 60),
        ),
        items = listOf(
            TimelineItem("meal:lunch", TimelineKind.MEAL, at(13, 5), title = "Lunch", detail = "Chicken salad, sparkling water · 610 kcal", module = Module.NUTRITION, page = AppPage.FOOD),
            TimelineItem("workout:w1", TimelineKind.WORKOUT, at(12, 20), end = at(12, 58), title = "Walk", detail = "38 min · 4,120 steps · heart rate 108 on average", page = AppPage.ACTIVITY, ref = "w1"),
            TimelineItem("bathroom:c1", TimelineKind.BATHROOM, at(11, 45), title = "Bathroom", module = Module.CONTINENCE, page = AppPage.CONTINENCE, ref = "c1"),
            TimelineItem("change:c0", TimelineKind.PRODUCT_CHANGE, at(9, 10), title = "Night brief", detail = "Changed · the one before was damp after 2 h 30 min", module = Module.CONTINENCE, page = AppPage.CONTINENCE, ref = "c0"),
            TimelineItem("dose:m1", TimelineKind.DOSE_TAKEN, at(8, 2), title = "Progesterone", detail = "Taken, on time · 100 mg", module = Module.MEDICATIONS, page = AppPage.MEDS, ref = "d1"),
            TimelineItem("meal:breakfast", TimelineKind.MEAL, at(7, 30), title = "Breakfast", detail = "Oatmeal, coffee · 420 kcal", module = Module.NUTRITION, page = AppPage.FOOD),
            TimelineItem("sleep:s1", TimelineKind.SLEEP, stages.first().start, end = stages.last().end, title = "Sleep", detail = "7h 42m asleep · 1h 50m deep · 1h 52m REM", page = AppPage.SLEEP, ref = "s1"),
        ),
    )

    private val state = TodayState(today, today, summary, feed = feed, loading = false, plan = "Chili")

    // ---- Drawing

    private fun render(
        dark: Boolean = false, palette: Palette = Palette.AURORA, fontScale: Float? = null, readOnly: Boolean = false, content: @Composable () -> Unit,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(density.density, fontScale) else density) {
                HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = palette) {
                    CompositionLocalProvider(LocalLayoutStore provides remember { MemoryLayoutStore(LayoutPrefs()) }, LocalReadOnly provides readOnly) {
                        AppFrame(selected = "today", snackbar = remember { SnackbarHostState() }, onTab = {}, canLog = !readOnly, content = content)
                    }
                }
            }
        }
    }

    @Composable
    private fun Today(s: TodayState = state, actions: TodayActions = TodayActions(), a: HealthAlert? = alert, unseen: Int = 1) =
        TodayScreen(s, Units.METRIC, zone, actions, modules = modules, now = now, alerts = unseen, alert = a)

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shows(text: String) = assertTrue("Shows $text", compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty())

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    // ---- Today

    @Test
    fun todayLight() {
        render { Today() }
        listOf("Tuesday, 17 November", "Good afternoon", "Keep an eye on").forEach(::shows)
        // The scores read out as one: each with its number, and the sentence.
        compose.onNode(hasContentDescription("Readiness 90, Sleep 87, Activity 53. Well rested, but your body's working on something. Go easy today.")).assertExists()
        listOf("Right now", "Night brief", "Estradiol", "Food today", "1,240 of 1,900 kcal", "Your day", "Last 24 hours", "Now", "Heart rate 72 · 6,240 steps so far").forEach(::shows)
        listOf("Lunch", "Walk", "Bathroom", "Breakfast", "For you").forEach(::shows)
        compose.onNode(hasContentDescription("Steady sleep this week. Open Body")).assertExists()
        // The alert with the card isn't repeated in Right now; nor is the scores' insight, or any alert, in For you.
        assertEquals(1, count("Your body may be fighting something off"))
        assertEquals(0, count("Go easy today."))
        compose.onNodeWithContentDescription("1 new health alert", useUnmergedTree = true).assertExists()
        compose.onNode(hasContentDescription("Take Estradiol")).assertExists()
        save("today_light")
    }

    @Test
    fun todayDark() {
        render(dark = true) { Today() }
        save("today_dark")
    }

    @Test
    fun todayOceanLight() {
        render(palette = Palette.OCEAN) { Today() }
        save("today_ocean_light")
    }

    @Test
    fun todayEmberDark() {
        render(dark = true, palette = Palette.EMBER) { Today() }
        save("today_ember_dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h2600dp-xxhdpi")
    fun todaySmallLargeText() {
        render(fontScale = 1.3f) { Today() }
        save("today_small_large_text")
    }

    @Test
    fun anUrgentAlertSaysWhenToCallEmergencyServices() {
        val urgent = alert.copy(
            id = "a9", kind = AlertKind.LOW_OXYGEN, level = AlertLevel.URGENT, title = "Blood oxygen stayed very low",
            body = "It was under 85% for 12 minutes.", advice = "If you're short of breath or confused, call emergency services now. Otherwise sit up and breathe slowly.",
        )
        render { Today(a = urgent) }
        shows("Could need care now")
        shows("call emergency services now.")
        save("today_alert_urgent")
    }

    @Test
    fun eachRightNowActionGoesToItsFlow() {
        val opened = mutableListOf<String>()
        val taken = mutableListOf<Triple<String, Long?, String>>()
        render { Today(actions = TodayActions(open = { opened += it }, takeDose = { m, t, n -> taken += Triple(m, t, n) })) }
        compose.onNode(hasContentDescription("Take Estradiol")).performClick()
        assertEquals(listOf(Triple("m2", at(14), "Estradiol")), taken)
        compose.onNode(hasContentDescription("Log food")).performClick()
        compose.onNodeWithText("Last 24 hours").performClick()
        compose.onNodeWithText("Lunch").performScrollTo().performClick()
        compose.onNodeWithContentDescription("1 new health alert", useUnmergedTree = true).performClick()
        assertEquals(listOf("food/add/2026-11-17/LUNCH", "recent", "food", "alerts"), opened)
    }

    @Test
    fun aDayGoneByHasNoRightNow() {
        val yesterday = today.minusDays(1)
        val past = TodayState(yesterday, today, summary.copy(date = yesterday.toString()), feed = feed.copy(date = yesterday.toString(), now = emptyList()), loading = false)
        var went: LocalDate? = null
        render { Today(s = past, actions = TodayActions(go = { went = it })) }
        shows("Yesterday")
        assertEquals(0, count("Right now"))
        assertEquals(0, count("Heart rate 72"))
        save("today_yesterday")
        compose.onNodeWithText("Back to today").performClick()
        assertEquals(today, went)
    }

    @Test
    fun withoutTheFeedTodayStillShowsTheDay() {
        var retried = 0
        render { Today(s = state.copy(feed = null, feedError = "Couldn't load your day"), actions = TodayActions(retry = { retried++ })) }
        compose.onNode(hasContentDescription("Readiness 90", substring = true)).assertExists()
        shows("Couldn't load your day")
        assertEquals(0, count("Right now"))
        save("today_feed_error")
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        assertEquals(1, retried)
    }

    @Test
    fun viewingAShareHasNoButtonsOrBell() {
        render(readOnly = true) { Today(s = state.copy(feed = feed.copy(now = emptyList())), a = null, unseen = 0) }
        assertEquals(0, compose.onAllNodes(hasContentDescription("Health alerts")).fetchSemanticsNodes().size)
        assertEquals(0, count("Last 24 hours"))
        assertEquals(0, count("Arrange Today"))
        compose.onNodeWithContentDescription("Logging is off while you view a share").assertExists()
        save("today_viewing_share")
    }

    // ---- The + sheet

    @Composable
    private fun Plus(actions: LogSheetActions = LogSheetActions()) {
        Backdrop {
            Column(Modifier.fillMaxSize().padding(top = 160.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom) {
                Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        LogSheetContent(
                            TodayRows.logNow(feed.now), "1:42 PM", "Afternoon snack", "Or tonight's plan: Chili, from Mealie",
                            TodayLayout.grid(TodayLayout.quickActions(modules + Module.MIND + Module.SYMPTOMS), null), Units.METRIC, actions,
                        )
                    }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1000dp-xxhdpi")
    fun plusSheetLight() {
        val now = mutableListOf<String>()
        val quick = mutableListOf<QuickAction>()
        var meal = 0
        render { Plus(LogSheetActions(now = { now += it.id }, meal = { meal++ }, quick = { quick += it })) }
        listOf("Log", "now · 1:42 PM", "Right now", "Anything else").forEach(::shows)
        // Right now's to-do (not food: the meal suggestion is that), each button read out as what it does.
        listOf("Take Estradiol", "Log a change", "Log afternoon snack. Or tonight's plan: Chili, from Mealie", "Log food", "Log your mood")
            .forEach { compose.onNode(hasContentDescription(it, substring = true)).assertExists() }
        assertEquals(0, compose.onAllNodes(hasContentDescription("Food today", substring = true)).fetchSemanticsNodes().size)
        save("plus_light")
        compose.onNode(hasContentDescription("Take Estradiol", substring = true)).performClick()
        compose.onNode(hasContentDescription("Log afternoon snack", substring = true)).performClick()
        compose.onNode(hasContentDescription("Log your mood")).performClick()
        assertEquals(listOf("dose:m2"), now)
        assertEquals(1, meal)
        assertEquals(listOf(QuickAction.MOOD), quick)
    }

    @Test
    @Config(qualifiers = "w411dp-h1000dp-xxhdpi")
    fun plusSheetDark() {
        render(dark = true) { Plus() }
        save("plus_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h1000dp-xxhdpi")
    fun plusSheetVioletLight() {
        render(palette = Palette.VIOLET) { Plus() }
        save("plus_violet_light")
    }

    @Test
    fun theTabBarIsFixedAndThePlusOpensTheSheet() {
        val tapped = mutableListOf<String>()
        var logged = 0
        compose.setContent {
            HealthTheme(mode = ThemeMode.LIGHT, palette = Palette.AURORA) {
                AppFrame(selected = "today", snackbar = remember { SnackbarHostState() }, onTab = { tapped += it }, onLog = { logged++ }) { Surface(color = Color.Transparent) {} }
            }
        }
        listOf("Today", "Body", "Care", "You").forEach { compose.onNodeWithText(it).performClick() }
        compose.onNodeWithContentDescription("Log something").performClick()
        assertEquals(listOf("today", "body", "care", "you"), tapped)
        assertEquals(1, logged)
    }
}
