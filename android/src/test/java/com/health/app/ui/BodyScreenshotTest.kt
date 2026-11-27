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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.health.api.AlertSettings
import com.health.api.AlertWatch
import com.health.api.Device
import com.health.api.DeviceKind
import com.health.api.Goals
import com.health.api.HealthAlerts
import com.health.api.LayoutPrefs
import com.health.api.Metric
import com.health.api.Module
import com.health.api.Sample
import com.health.api.ScreenLayout
import com.health.api.TrendDay
import com.health.api.Trends
import com.health.api.Units
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.body.BodyActions
import com.health.app.ui.body.BodyScreen
import com.health.app.ui.body.BodySpan
import com.health.app.ui.body.BodyState
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.devices.DevicesActions
import com.health.app.ui.devices.DevicesScreen
import com.health.app.ui.devices.DevicesState
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.LocalArrangements
import com.health.app.ui.recent.RecentFixtures
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
import kotlin.math.PI
import kotlin.math.sin

/**
 * Body: the last 24 hours shaped like hers (a COLMI R02's night with its stages, heart rate, blood
 * oxygen, HRV and stress, the phone's steps) and the week, month and year. All devices in light,
 * the ring alone in dark, imperial units, nothing recorded, a narrow phone, arranged, and the
 * week in Ocean. PNGs in android/build/outputs/screenshots as body_*.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class BodyScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val f = RecentFixtures

    private val alerts = HealthAlerts(
        settings = AlertSettings(),
        watching = listOf(AlertWatch("Heart rate", "From your ring", true), AlertWatch("Blood oxygen", "From your ring", true), AlertWatch("Rhythm", "Needs a chest strap", false)),
    )
    private val known = listOf(
        Device(f.RING, DeviceKind.COLMI_RING, "aa:bb", "COLMI R02_C301", lastSyncAt = f.now - 600_000L, createdAt = 0, samples = 4200),
        Device(f.PHONE, DeviceKind.HEALTH_CONNECT, "hc", "Health Connect", lastSyncAt = f.now - 2_500_000L, createdAt = 0, samples = 900),
    )

    /** A month of days like hers, the last of them today, and weigh-ins now and then. */
    private fun trend(i: Int, count: Int): TrendDay {
        val d = f.today.minusDays((count - 1 - i).toLong())
        val wobble = sin(i * PI / 5)
        return TrendDay(
            date = d.toString(),
            readiness = (78 + 8 * wobble).toInt(), sleepScore = (80 + 6 * sin(i / 2.0)).toInt(), activityScore = (85 + 7 * sin(i / 3.0)).toInt(),
            steps = (7500 + 2500 * sin(i / 2.5)).toInt(), activeCalories = 420 + 80 * wobble,
            restingHeartRate = 53 - 2 * wobble, hrv = 45 + 6 * wobble, sleepMinutes = (430 + 40 * sin(i / 1.7)).toInt(),
            weightKg = 67.2 - i * 0.03, calories = 1900 + 200 * sin(i / 1.3), protein = 95 + 15 * wobble,
            workouts = if (i % 3 == 0) 1 else 0, workoutMinutes = if (i % 3 == 0) 40 else 0,
        )
    }

    private fun trends(span: BodySpan) = Trends(f.today.minusDays(span.days - 1L).toString(), f.today.toString(), (0 until span.days).map { trend(it, span.days) })

    private val weights = (0 until 10).map { i -> Sample(Metric.WEIGHT, f.at(f.today.minusDays(27L - i * 3), 7), value = 67.3 - i * 0.1, id = i.toLong(), source = "Entered by hand") }

    private fun render(dark: Boolean = false, palette: Palette = Palette.AURORA, layout: LayoutPrefs? = null, readOnly: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = palette) {
                CompositionLocalProvider(LocalArrangements provides layout, LocalReadOnly provides readOnly) {
                    AppFrame(selected = "body", snackbar = remember { SnackbarHostState() }, onTab = {}, content = content)
                }
            }
        }
    }

    private fun day(state: BodyState, units: Units = Units.METRIC, actions: BodyActions = BodyActions()) = @Composable {
        BodyScreen(state, units, f.zone, actions, modules = Module.DEFAULT, unseenAlerts = 1, alerts = alerts, now = f.now)
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

    private val dayState = BodyState(readings = f.all, known = known, loading = false, compare = "The ring read 3 bpm higher than the strap")

    // ---- 24 hours

    @Test
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    fun dayAllDevicesLight() {
        render { day(dayState)() }
        listOf("Body", "24 hours", "Week", "Month", "Year", "All devices", "Last night", "7h 7m", "Heart rate", "COLMI R02_C301 and Health Connect together", "Steps").forEach(::shows)
        listOf("HRV", "Blood oxygen", "Stress", "Health alerts", "1 new · watching heart rate and blood oxygen", "Compare devices").forEach(::shows)
        compose.onNode(hasContentDescription("Heart rate from", substring = true)).assertExists()
        compose.onNode(hasContentDescription("Sleep stages: deep 1h 25m, REM 1h 46m, light 3h 56m, awake 15m")).assertExists()
        compose.onNode(hasContentDescription("Steps by the hour, 9,260 in all")).assertExists()
        save("body_24h_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    fun dayRingAloneDark() {
        render(dark = true) { day(BodyState(device = f.RING, readings = f.ringAlone, known = known, loading = false))() }
        shows("COLMI R02_C301")
        // The ring has no steps: no Steps card.
        assertEquals(0, compose.onAllNodes(hasText("Steps")).fetchSemanticsNodes().size)
        save("body_24h_ring_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    fun dayImperialEmber() {
        render(palette = Palette.EMBER) { day(BodyState(readings = f.withTemperature, known = known, loading = false), Units.IMPERIAL)() }
        shows("°F")
        shows("warmer than usual")
        shows("Breathing")
        save("body_24h_imperial_ember_light")
    }

    @Test
    fun dayNothingRecorded() {
        var synced = 0
        val ring = known.first().copy(lastSyncAt = f.now - 3 * 3_600_000L - 600_000L)
        render { day(BodyState(readings = f.empty, known = listOf(ring), loading = false), actions = BodyActions(syncNow = { synced++ }))() }
        shows(com.health.app.ui.recent.Recent.NOTHING)
        shows("Last synced 3 h ago")
        save("body_24h_empty")
        compose.onNodeWithText("Sync now").performClick()
        assertEquals(1, synced)
    }

    @Test
    @Config(qualifiers = "w360dp-h2000dp-xxhdpi")
    fun dayNarrowPhone() {
        render { day(dayState)() }
        save("body_24h_narrow")
    }

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun dayArrangedAndNoNight() {
        // Heart rate moved first, steps and two tiles hidden; and a day without a night.
        val layout = LayoutPrefs(mapOf(FeatureScreens.RECENT to ScreenLayout(order = listOf("heart", "sleep"), hidden = setOf("steps", "spo2", "hrv"))))
        render(layout = layout) { day(BodyState(readings = f.all.copy(sleep = emptyList()), loading = false))() }
        shows("No sleep recorded in the last 24 hours.")
        assertEquals(0, compose.onAllNodes(hasText("Steps")).fetchSemanticsNodes().size)
        val heart = compose.onNodeWithText("Heart rate").fetchSemanticsNode().boundsInRoot.top
        val sleep = compose.onNodeWithText("Last night").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Heart rate is first", heart < sleep)
        save("body_24h_arranged")
    }

    @Test
    fun choosingADeviceAndASpan() {
        var chosen: String? = "unset"
        val spans = mutableListOf<BodySpan>()
        render { day(dayState, actions = BodyActions(choose = { chosen = it }, span = { spans += it }))() }
        compose.onNodeWithContentDescription("Showing All devices. Choose a device").performClick()
        compose.onNodeWithText("Health Connect").performClick()
        assertEquals(f.PHONE, chosen)
        compose.onNodeWithContentDescription("Showing All devices. Choose a device").performClick()
        compose.onAllNodesWithText("All devices").onLast().performClick()
        assertEquals(null, chosen)
        listOf("Week", "Month", "Year", "24 hours").forEach { compose.onNodeWithText(it).performClick() }
        assertEquals(listOf(BodySpan.WEEK, BodySpan.MONTH, BodySpan.YEAR, BodySpan.DAY), spans)
    }

    @Test
    fun theRowsAtTheBottomOpenTheirPages() {
        val opened = mutableListOf<String>()
        render { day(dayState, actions = BodyActions(open = { opened += it }))() }
        compose.onNodeWithText("Health alerts").performScrollTo().performClick()
        compose.onNodeWithText("Compare devices").performScrollTo().performClick()
        assertEquals(listOf("alerts", "devices/compare"), opened)
    }

    @Test
    fun eachDeviceOpensItsLast24Hours() {
        var opened: String? = null
        compose.setContent {
            HealthTheme(mode = ThemeMode.LIGHT, palette = Palette.AURORA) {
                DevicesScreen(DevicesState(serverDevices = known.take(1), loading = false), DevicesActions(recent = { opened = it }), now = f.now)
            }
        }
        compose.onNodeWithText("COLMI R02_C301", substring = true).performScrollTo().performClick()
        assertEquals(f.RING, opened)
    }

    // ---- The week, month and year

    private fun span(span: BodySpan) = @Composable {
        BodyScreen(
            BodyState(span = span, trends = trends(span), weights = weights, known = known, loading = false, compare = "The ring read 3 bpm higher than the strap"),
            Units.METRIC, f.zone, BodyActions(), goals = Goals(steps = 8000, sleepMinutes = 450, targetWeightKg = 65.0), modules = Module.DEFAULT,
            unseenAlerts = 1, alerts = alerts, now = f.now,
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h2300dp-xxhdpi")
    fun weekLight() {
        render { span(BodySpan.WEEK)() }
        listOf("Scores", "Sleep", "Steps", "Resting heart rate", "Heart rate variability", "Weight", "Workouts").forEach(::shows)
        // The device filter is the 24 hours' own.
        assertEquals(0, compose.onAllNodesWithText("All devices").fetchSemanticsNodes().size)
        save("body_week_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2300dp-xxhdpi")
    fun weekDark() {
        render(dark = true) { span(BodySpan.WEEK)() }
        save("body_week_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h2300dp-xxhdpi")
    fun weekOceanDark() {
        render(dark = true, palette = Palette.OCEAN) { span(BodySpan.WEEK)() }
        save("body_week_ocean_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h2300dp-xxhdpi")
    fun yearLight() {
        render { span(BodySpan.YEAR)() }
        save("body_year_light")
    }

    @Test
    fun viewingAShareStartsAtTheWeek() {
        render(readOnly = true) { day(dayState)() }
        shows("Only for your own data")
        assertEquals(0, compose.onAllNodesWithText("Health alerts").fetchSemanticsNodes().size)
    }

    // ---- A card, opened

    @Test
    fun eachCardOpensItsDetails() {
        val opened = mutableListOf<String>()
        render { day(dayState, actions = BodyActions(open = { opened += it }))() }
        compose.onNodeWithContentDescription("Last night: details").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Heart rate: details").performScrollTo().performClick()
        compose.onNodeWithContentDescription("HRV: details").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Steps: details").performScrollTo().performClick()
        assertEquals(listOf("body/detail/sleep?span=DAY", "body/detail/heart?span=DAY", "body/detail/hrv?span=DAY", "body/detail/steps?span=DAY"), opened)
    }

    @Test
    @Config(qualifiers = "w411dp-h2300dp-xxhdpi")
    fun theWeeksCardsOpenTheirDetailsAndTheirButtonsStillWork() {
        val opened = mutableListOf<String>()
        val deleted = mutableListOf<Long>()
        render {
            BodyScreen(
                BodyState(span = BodySpan.MONTH, trends = trends(BodySpan.MONTH), weights = weights, known = known, loading = false),
                Units.METRIC, f.zone, BodyActions(open = { opened += it }, deleteWeight = { deleted += it }), modules = Module.DEFAULT, now = f.now,
            )
        }
        listOf("Scores", "Sleep", "Steps", "Resting heart rate", "Heart rate variability", "Weight", "Workouts").forEach { name ->
            compose.onNodeWithContentDescription("$name: details").performScrollTo().performClick()
        }
        assertEquals(listOf("scores", "sleep", "steps", "heart", "hrv", "weight", "workouts").map { "body/detail/$it?span=MONTH" }, opened)
        // The weigh-ins' own delete is still its own button.
        compose.onAllNodes(hasContentDescription("Delete")).onLast().performScrollTo().performClick()
        assertEquals(1, deleted.size)
        assertEquals(7, opened.size)
    }

    private val detailCtx = com.health.app.ui.body.BodyDetail.Ctx(Units.METRIC, f.zone, f.today, Goals(steps = 8000, sleepMinutes = 450, targetWeightKg = 65.0))

    private fun detail(id: String, state: com.health.app.ui.body.BodyDetailState, ctx: com.health.app.ui.body.BodyDetail.Ctx = detailCtx) = @Composable {
        com.health.app.ui.body.BodyDetailScreen(id, state, ctx, com.health.app.ui.body.BodyDetailActions())
    }

    @Test
    @Config(qualifiers = "w411dp-h1700dp-xxhdpi")
    fun detailRestingHeartRateWeek() {
        render {
            detail(
                "heart",
                com.health.app.ui.body.BodyDetailState(BodySpan.WEEK, trends = trends(BodySpan.WEEK), usual = trends(BodySpan.MONTH), loading = false),
            )()
        }
        listOf("Resting heart rate", "Past 7 days", "Average", "Lowest", "Highest", "Each day", "Dashed line: your usual").forEach(::shows)
        compose.onNodeWithContentDescription("Back").assertExists()
        // The 24 hours are there to switch to.
        shows("24 hours")
        save("body_detail_week_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    fun detailHeartRate24Hours() {
        render { detail("heart", com.health.app.ui.body.BodyDetailState(BodySpan.DAY, readings = f.all, loading = false))() }
        listOf("Heart rate", "Past 24 hours", "latest at", "Each hour", "Show all").forEach(::shows)
        save("body_detail_24h_light")
        compose.onNodeWithText("Show all", substring = true).performScrollTo().performClick()
        assertEquals(0, compose.onAllNodesWithText("Show all", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    fun detailLastNight24HoursDark() {
        render(dark = true) { detail("sleep", com.health.app.ui.body.BodyDetailState(BodySpan.DAY, readings = f.all, loading = false))() }
        listOf("Sleep", "7h 7m", "Deep", "Each sleep").forEach(::shows)
        save("body_detail_sleep_24h_dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h1600dp-xxhdpi")
    fun detailWeightMonthNarrow() {
        var deleted: Long? = null
        render {
            com.health.app.ui.body.BodyDetailScreen(
                "weight", com.health.app.ui.body.BodyDetailState(BodySpan.MONTH, weights = weights, loading = false), detailCtx,
                com.health.app.ui.body.BodyDetailActions(deleteWeight = { deleted = it }),
            )
        }
        listOf("Weight", "Change", "Each weigh-in", "Dashed line: your goal").forEach(::shows)
        // No 24 hours for weight.
        assertEquals(0, compose.onAllNodesWithText("24 hours").fetchSemanticsNodes().size)
        save("body_detail_weight_month_narrow")
        compose.onAllNodes(hasContentDescription("Delete the weigh-in", substring = true)).onFirst().performScrollTo().performClick()
        assertEquals(9L, deleted)
    }

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun detailScoresYearAndNothingInTheSpan() {
        render { detail("scores", com.health.app.ui.body.BodyDetailState(BodySpan.YEAR, trends = trends(BodySpan.YEAR), loading = false))() }
        listOf("Readiness", "Activity", "Past year").forEach(::shows)
        save("body_detail_scores_year_light")
    }

    @Test
    fun detailNothingInTheSpanAndViewingAShare() {
        val empty = Trends(f.today.minusDays(6).toString(), f.today.toString(), (0 until 7).map { TrendDay(f.today.minusDays(6L - it).toString()) })
        render(readOnly = true) { detail("hrv", com.health.app.ui.body.BodyDetailState(BodySpan.WEEK, trends = empty, loading = false))() }
        shows(com.health.app.ui.body.BodyDetail.EMPTY)
        // While viewing a share, no 24 hours.
        assertEquals(0, compose.onAllNodesWithText("24 hours").fetchSemanticsNodes().size)
    }
}
