package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.health.api.CombinedSleep
import com.health.api.CombinedValue
import com.health.api.DaySummary
import com.health.api.Device
import com.health.api.DeviceChoices
import com.health.api.DeviceKind
import com.health.api.Goals
import com.health.api.Metric
import com.health.api.Module
import com.health.api.Point
import com.health.api.SleepSource
import com.health.api.SleepStage
import com.health.api.SleepSummary
import com.health.api.SourceValue
import com.health.api.StageSpan
import com.health.api.Units
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.devices.colmi.Answer
import com.health.app.devices.colmi.ColmiConduit
import com.health.app.devices.colmi.ReadingStatus
import com.health.app.devices.colmi.RingReading
import com.health.app.devices.colmi.RingReadingsState
import com.health.app.sync.ConduitStatus
import com.health.app.sync.PairedRing
import com.health.app.ui.components.Backdrop
import com.health.app.ui.components.LocalSourceChooser
import com.health.app.ui.components.SourceChooser
import com.health.app.ui.components.SourcesDetails
import com.health.app.ui.devices.DevicesActions
import com.health.app.ui.devices.DevicesScreen
import com.health.app.ui.devices.DevicesState
import com.health.app.ui.devices.HealthConnectState
import com.health.app.ui.devices.Overlap
import com.health.app.ui.devices.OverlapDevice
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.today.TodayActions
import com.health.app.ui.today.TodayScreen
import com.health.app.ui.today.TodayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import kotlin.math.sin

/**
 * A ring and a watch measuring the same things: Today's "From 2 devices", the sheet that says
 * where the numbers come from, and the Devices page's ring readings and overlaps. PNGs in
 * android/build/outputs/screenshots, light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class DeviceSourcesScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 11, 12)
    private fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(today, 14, 20)

    private fun render(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                CompositionLocalProvider(LocalSourceChooser provides chooser) { content() }
            }
        }
    }

    @Composable
    private fun Frame(tab: String, content: @Composable () -> Unit) {
        AppFrame(selected = tab, snackbar = remember { SnackbarHostState() }, onTab = {}, tabs = tabsFor(Module.DEFAULT), content = content)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Choosing, as the person (nothing is sent anywhere). */
    private val chooser = object : SourceChooser {
        override val choices: StateFlow<DeviceChoices?> = MutableStateFlow(DeviceChoices())
        override fun load() = Unit
        override fun choose(metric: Metric, deviceId: String?, device: String?) = Unit
        override fun chooseSleep(deviceId: String?, device: String?) = Unit
    }

    // ---- A ring and a watch ----

    private val ring = "ring"
    private val watch = "watch"

    private val combined = listOf(
        CombinedValue(
            Metric.RESTING_HEART_RATE, 52.0,
            listOf(
                SourceValue(ring, "Colmi R02", DeviceKind.COLMI_RING, 51.0, 0.5, readings = 288),
                SourceValue(watch, "Pebble Time 2", DeviceKind.PEBBLE, 53.0, 0.5, readings = 96),
            ),
            "From both devices' heart rate, merged: each 5 minutes, their average.",
        ),
        CombinedValue(
            Metric.HRV, 48.0,
            listOf(
                SourceValue(ring, "Colmi R02", DeviceKind.COLMI_RING, 46.0, 0.72, readings = 14),
                SourceValue(watch, "Pebble Time 2", DeviceKind.PEBBLE, 54.0, 0.28, readings = 6, note = "only part of the night"),
            ),
            "Each compared with its own usual first, then weighted to the ring at night.",
        ),
        CombinedValue(
            Metric.SPO2, 97.0,
            listOf(
                SourceValue(ring, "Colmi R02", DeviceKind.COLMI_RING, 97.0, 1.0, readings = 9),
                SourceValue(watch, "Pebble Time 2", DeviceKind.PEBBLE, 95.0, 0.0, readings = 3),
            ),
            "You chose the ring for blood oxygen.",
            chosenDeviceId = ring,
        ),
        CombinedValue(
            Metric.STEPS, 7214.0,
            listOf(
                SourceValue(ring, "Colmi R02", DeviceKind.COLMI_RING, 6880.0, 0.46, readings = 58),
                SourceValue(watch, "Pebble Time 2", DeviceKind.PEBBLE, 7020.0, 0.54, readings = 61),
            ),
            "For each 15 minutes, the device that counted most, so a walk isn't counted twice.",
        ),
    )

    private val combinedSleep = CombinedSleep(
        listOf(
            SleepSource(ring, "Colmi R02", at(today.minusDays(1), 22, 48), at(today, 6, 14), 386, staged = true),
            SleepSource(watch, "Pebble Time 2", at(today.minusDays(1), 22, 55), at(today, 6, 20), 371, staged = false),
        ),
        "Bedtime and waking from both (they're close); the stages from the ring, which tells them apart.",
        stagesFrom = ring,
    )

    private val night: SleepSummary by lazy {
        val start = at(today.minusDays(1), 22, 48)
        val pattern = listOf(SleepStage.LIGHT to 40, SleepStage.DEEP to 60, SleepStage.LIGHT to 50, SleepStage.REM to 30, SleepStage.LIGHT to 90, SleepStage.REM to 40, SleepStage.AWAKE to 6, SleepStage.LIGHT to 70, SleepStage.REM to 40)
        var t = start
        val stages = pattern.map { (stage, minutes) -> StageSpan(stage, t, t + minutes * 60_000L).also { t += minutes * 60_000L } }
        SleepSummary(
            start = start, end = t, asleepMinutes = 386, inBedMinutes = 406, efficiency = 95, deepMinutes = 60, remMinutes = 110,
            lightMinutes = 250, awakeMinutes = 6, staged = true, stages = stages, combined = combinedSleep,
        )
    }

    private val summary: DaySummary by lazy {
        DaySummary(
            date = today.toString(),
            steps = 7214, distanceM = 5320.0, activeCalories = 462.0, restingCalories = 1420.0,
            restingHeartRate = 52.0, avgHeartRate = 68.0, minHeartRate = 49.0, maxHeartRate = 131.0, hrv = 48.0, spo2 = 97.0, stress = 31.0,
            sleepSummary = night,
            hourlySteps = listOf(0, 0, 0, 0, 0, 0, 120, 2800, 640, 310, 220, 180, 900, 410, 600, 30, 0, 0, 0, 0, 0, 0, 0, 0),
            heartRate = (0..170).map { i -> Point(at(today, 0) + i * 5 * 60_000L, (if (i < 78) 54.0 else 66.0) + 6 * sin(i / 9.0)) },
            goals = Goals(steps = 8000, activeCalories = 450),
            hasData = true,
            combined = combined,
        )
    }

    private fun today(dark: Boolean, name: String) {
        render(dark) { Frame("today") { TodayScreen(TodayState(today, today, summary, loading = false), Units.METRIC, zone, TodayActions()) } }
        // Down to the Heart card.
        compose.onNodeWithText("Heart").assertExists()
        save(name)
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun todayFromTwoDevicesLight() = today(false, "sources_today_light")

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun todayFromTwoDevicesDark() = today(true, "sources_today_dark")

    @Test
    fun theLineOpensItsCardsSources() {
        render { Frame("today") { TodayScreen(TodayState(today, today, summary, loading = false), Units.METRIC, zone, TodayActions()) } }
        // Activity, Sleep and Heart each have one; Activity's speaks for steps only.
        val lines = compose.onAllNodesWithText("From 2 devices")
        lines.assertCountEquals(3)
        lines[0].performScrollTo().performClick()
        compose.onNodeWithText("Use only Pebble Time 2 for steps").assertExists()
        compose.onNodeWithText("Use only Pebble Time 2 for heart rate variability").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun trendsFromTwoDevices() {
        val days = (13 downTo 0).map { ago ->
            val i = 13 - ago
            com.health.api.TrendDay(
                today.minusDays(ago.toLong()).toString(), steps = 6000 + (i * 397) % 4000, restingHeartRate = 52.0 + (i % 4),
                hrv = 44.0 + (i * 3) % 9, sleepMinutes = 380 + (i * 17) % 80,
            )
        }
        val state = com.health.app.ui.trends.TrendsState(
            days = 14, trends = com.health.api.Trends(days.first().date, days.last().date, days), loading = false,
            combined = combined, combinedSleep = combinedSleep,
        )
        render { Frame("trends") { com.health.app.ui.trends.TrendsScreen(state, Units.METRIC, Goals(), zone, com.health.app.ui.trends.TrendsActions()) } }
        // Sleep, steps, resting heart rate and HRV each have one.
        compose.onAllNodesWithText("From 2 devices").assertCountEquals(4)
        save("sources_trends_light")
    }

    private fun sheet(dark: Boolean, name: String, sleep: Boolean) {
        render(dark) {
            Backdrop {
                if (sleep) SourcesDetails("Sleep", emptyList(), combinedSleep, Units.METRIC, zone, chooser)
                else SourcesDetails("Heart", combined.filter { it.metric != Metric.STEPS }, null, Units.METRIC, zone, chooser)
            }
        }
        save(name)
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun sourcesSheetLight() = sheet(false, "sources_sheet_heart_light", sleep = false)

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun sourcesSheetDark() = sheet(true, "sources_sheet_heart_dark", sleep = false)

    @Test
    fun sleepSourcesLight() = sheet(false, "sources_sheet_sleep_light", sleep = true)

    @Test
    fun sleepSourcesDark() = sheet(true, "sources_sheet_sleep_dark", sleep = true)

    @Test
    fun sheetWhileViewingAShareOnlyShows() {
        compose.setContent {
            HealthTheme(mode = ThemeMode.LIGHT, palette = Palette.AURORA) {
                CompositionLocalProvider(com.health.app.ui.care.LocalReadOnly provides true) {
                    Backdrop { SourcesDetails("Heart", combined.take(2), null, Units.METRIC, zone, com.health.app.ui.components.sourceChooser()) }
                }
            }
        }
        compose.onNodeWithText("Use only Colmi R02 for heart rate variability").assertDoesNotExist()
        compose.onNodeWithText("Only the person whose data this is can choose which device to use.").assertExists()
        save("sources_sheet_viewing")
    }

    // ---- Devices ----

    private val readings = RingReadingsState(
        address = "C4:7A:12:9E:33:01",
        autoMeasure = RingReadingsState.AUTO_MEASURE,
        readings = mapOf(
            RingReading.HEART_RATE to ReadingStatus(latest = now - 9 * 60_000, arrivedAt = now - 7 * 60_000),
            RingReading.ACTIVITY to ReadingStatus(latest = now - 20 * 60_000, arrivedAt = now - 7 * 60_000),
            RingReading.SLEEP to ReadingStatus(latest = at(today, 6, 14), arrivedAt = now - 7 * 60_000),
            RingReading.SPO2 to ReadingStatus(answer = Answer.COMPLETE),
            RingReading.HRV to ReadingStatus(latest = now - 50 * 60_000, arrivedAt = now - 7 * 60_000, answer = Answer.COMPLETE),
            RingReading.STRESS to ReadingStatus(answer = Answer.SILENT),
            RingReading.SKIN_TEMPERATURE to ReadingStatus(offered = false),
        ),
    )

    private val devicesState = DevicesState(
        ring = PairedRing("C4:7A:12:9E:33:01", "R02_9E33", battery = 82, firmware = "RY02_3.00.33"),
        healthConnect = HealthConnectState.ON,
        statuses = mapOf(ColmiConduit.ID to ConduitStatus(now - 7 * 60_000, true, null, samples = 412, sleep = 1)),
        serverDevices = listOf(
            Device(ring, DeviceKind.COLMI_RING, "C4:7A:12:9E:33:01", "Colmi R02", battery = 82, lastSyncAt = now - 7 * 60_000, createdAt = 0, samples = 48_211),
            Device(watch, DeviceKind.PEBBLE, "pebble", "Pebble Time 2", lastSyncAt = now - 52 * 60_000, createdAt = 0, samples = 20_330),
        ),
        loading = false,
        ringReadings = readings,
        overlaps = listOf(
            Overlap(Metric.STEPS, listOf(OverlapDevice(ring, "Colmi R02"), OverlapDevice(watch, "Pebble Time 2"))),
            Overlap(Metric.RESTING_HEART_RATE, listOf(OverlapDevice(ring, "Colmi R02"), OverlapDevice(watch, "Pebble Time 2"))),
            Overlap(Metric.HRV, listOf(OverlapDevice(ring, "Colmi R02"), OverlapDevice(watch, "Pebble Time 2"))),
            Overlap(Metric.SPO2, listOf(OverlapDevice(ring, "Colmi R02"), OverlapDevice(watch, "Pebble Time 2")), chosen = ring),
            Overlap(null, listOf(OverlapDevice(ring, "Colmi R02"), OverlapDevice(watch, "Pebble Time 2"))),
        ),
    )

    @Test
    fun ringReadingsLight() {
        render { Frame("devices") { DevicesScreen(devicesState, DevicesActions(), now) } }
        compose.onNodeWithText("No answer from the ring yet").assertExists()
        save("devices_ring_readings_light")
    }

    @Test
    fun ringReadingsDark() {
        render(dark = true) { Frame("devices") { DevicesScreen(devicesState, DevicesActions(), now) } }
        save("devices_ring_readings_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun overlapsLight() {
        render { Frame("devices") { DevicesScreen(devicesState, DevicesActions(), now) } }
        compose.onNodeWithText("When devices overlap").assertExists()
        save("devices_overlaps_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun overlapsDark() {
        render(dark = true) { Frame("devices") { DevicesScreen(devicesState, DevicesActions(), now) } }
        save("devices_overlaps_dark")
    }

    @Test
    fun overlapMenuOffersEachDevice() {
        var chose: Pair<Metric?, String?>? = null
        render { Frame("devices") { DevicesScreen(devicesState.copy(overlaps = devicesState.overlaps.takeLast(2)), DevicesActions(choose = { o, id -> chose = o.metric to id }), now) } }
        // "Blood oxygen" is also one of the ring's readings: the overlap's row is the one that opens a menu.
        compose.onNode(hasText("Blood oxygen") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Use only Pebble Time 2 for blood oxygen").assertExists()
        compose.onNodeWithText("Combine them").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(Metric.SPO2 to null, chose)
    }
}
