package com.fitness.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.fitness.api.AuthConfig
import com.fitness.api.CheckIn
import com.fitness.api.Contributor
import com.fitness.api.DaySummary
import com.fitness.api.Device
import com.fitness.api.DeviceKind
import com.fitness.api.Food
import com.fitness.api.FoodDay
import com.fitness.api.FoodEntry
import com.fitness.api.FoodSearch
import com.fitness.api.FoodSource
import com.fitness.api.Goals
import com.fitness.api.Insight
import com.fitness.api.Meal
import com.fitness.api.Metric
import com.fitness.api.NutritionTarget
import com.fitness.api.Nutrients
import com.fitness.api.OidcButton
import com.fitness.api.Point
import com.fitness.api.Profile
import com.fitness.api.Role
import com.fitness.api.Sample
import com.fitness.api.Score
import com.fitness.api.SleepStage
import com.fitness.api.SleepSummary
import com.fitness.api.StageSpan
import com.fitness.api.TrendDay
import com.fitness.api.Trends
import com.fitness.api.Units
import com.fitness.api.User
import com.fitness.api.WaterEntry
import com.fitness.api.Workout
import com.fitness.api.WorkoutType
import com.fitness.app.data.Palette
import com.fitness.app.data.Settings
import com.fitness.app.data.ThemeMode
import com.fitness.app.devices.ble.FoundRing
import com.fitness.app.devices.colmi.ColmiConduit
import com.fitness.app.devices.gadgetbridge.GadgetbridgeConduit
import com.fitness.app.devices.healthconnect.HealthConnectConduit
import com.fitness.app.sync.ConduitStatus
import com.fitness.app.sync.PairedRing
import com.fitness.app.ui.components.Backdrop
import com.fitness.app.ui.devices.DevicesActions
import com.fitness.app.ui.devices.DevicesScreen
import com.fitness.app.ui.devices.DevicesState
import com.fitness.app.ui.devices.HealthConnectState
import com.fitness.app.ui.devices.RingPairScreen
import com.fitness.app.ui.food.AddFoodActions
import com.fitness.app.ui.food.AddFoodScreen
import com.fitness.app.ui.food.AddFoodState
import com.fitness.app.ui.food.FoodActions
import com.fitness.app.ui.food.FoodScreen
import com.fitness.app.ui.food.FoodState
import com.fitness.app.ui.settings.SettingsActions
import com.fitness.app.ui.settings.SettingsScreen
import com.fitness.app.ui.signin.SignInActions
import com.fitness.app.ui.signin.SignInScreen
import com.fitness.app.ui.theme.FitnessTheme
import com.fitness.app.ui.today.ScoreDetails
import com.fitness.app.ui.today.TodayActions
import com.fitness.app.ui.today.TodayScreen
import com.fitness.app.ui.today.TodayState
import com.fitness.app.ui.trends.TrendsActions
import com.fitness.app.ui.trends.TrendsScreen
import com.fitness.app.ui.trends.TrendsState
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
import kotlin.math.PI
import kotlin.math.sin

/**
 * Draws each screen with realistic data to PNGs in android/build/outputs/screenshots, for a look.
 * Not a pixel-diff test: it checks the screens come up, and lets people see them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 11, 12)
    private fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(today, 14, 20)

    private fun render(dark: Boolean = false, palette: Palette = Palette.AURORA, content: @Composable () -> Unit) {
        compose.setContent {
            FitnessTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = palette) { content() }
        }
    }

    @Composable
    private fun Frame(tab: String, content: @Composable () -> Unit) {
        AppFrame(selected = tab, snackbar = remember { SnackbarHostState() }, onTab = {}, content = content)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- Sample data ----

    private val goals = Goals(steps = 8000, activeCalories = 450, sleepMinutes = 480, protein = 120, targetWeightKg = 65.0, weeklyChangeKg = -0.25)

    private val night: SleepSummary by lazy {
        val start = at(today.minusDays(1), 22, 48)
        val pattern = listOf(
            SleepStage.AWAKE to 12, SleepStage.LIGHT to 35, SleepStage.DEEP to 50, SleepStage.LIGHT to 30, SleepStage.REM to 22,
            SleepStage.LIGHT to 40, SleepStage.DEEP to 35, SleepStage.LIGHT to 25, SleepStage.REM to 34, SleepStage.AWAKE to 6,
            SleepStage.LIGHT to 45, SleepStage.REM to 38, SleepStage.LIGHT to 30, SleepStage.AWAKE to 4, SleepStage.REM to 22,
        )
        var t = start
        val stages = pattern.map { (stage, minutes) -> StageSpan(stage, t, t + minutes * 60_000L).also { t += minutes * 60_000L } }
        SleepSummary(
            start = start, end = t, asleepMinutes = 386, inBedMinutes = 408, efficiency = 95,
            deepMinutes = 85, remMinutes = 116, lightMinutes = 205, awakeMinutes = 22, staged = true, latencyMinutes = 12,
            lowestHeartRate = 51.0, avgHeartRate = 56.0, avgHrv = 48.0, stages = stages, source = "Colmi R02",
        )
    }

    private val summary: DaySummary by lazy {
        DaySummary(
            date = today.toString(),
            readiness = Score(
                84, "Good",
                listOf(
                    Contributor("rhr", "Resting heart rate", 92, "51 bpm", "2 bpm below your usual"),
                    Contributor("hrv", "HRV balance", 81, "48 ms"),
                    Contributor("sleep", "Last night's sleep", 86, "6h 26m"),
                    Contributor("balance", "Sleep balance", 68, "6h 52m a night", "A little under your 8h goal this fortnight"),
                    Contributor("temp", "Body temperature", 95, "+0.1 °C"),
                    Contributor("activity", "Activity balance", 77, "Steady"),
                ),
            ),
            sleep = Score(86, "Good", listOf(Contributor("total", "Total sleep", 78, "6h 26m"), Contributor("eff", "Efficiency", 95, "95%"), Contributor("deep", "Deep sleep", 92, "1h 25m"), Contributor("rem", "REM sleep", 98, "1h 56m"))),
            activity = Score(91, "Optimal", listOf(Contributor("goal", "Daily goal", 100, "462 kcal"), Contributor("move", "Move every hour", 83, "1 long sit"), Contributor("training", "Training", 90, "3 workouts this week"))),
            steps = 7214, distanceM = 5320.0, activeCalories = 462.0, restingCalories = 1420.0,
            restingHeartRate = 51.0, avgHeartRate = 68.0, minHeartRate = 49.0, maxHeartRate = 142.0, hrv = 48.0,
            spo2 = 97.0, respiratoryRate = 14.2, temperatureDeviation = 0.1,
            sleepSummary = night,
            workouts = listOf(
                Workout(WorkoutType.RUN, at(today, 7, 10), at(today, 7, 44), name = "Morning run", distanceM = 5120.0, activeCalories = 312.0, avgHeartRate = 148.0),
                Workout(WorkoutType.YOGA, at(today, 12, 30), at(today, 13, 0), activeCalories = 80.0),
            ),
            nutrition = Nutrients(1240.0, 82.0, 131.0, 41.0),
            nutritionTarget = NutritionTarget(1950, 120, 210, 65, 2000, "Burned about 2,200 kcal; losing ¼ kg a week"),
            waterMl = 1250, weightKg = 66.4, bodyFat = 24.1,
            checkIn = CheckIn(today.toString(), mood = 4, energy = 4, stress = 2, tags = listOf("Late caffeine")),
            insights = listOf(
                Insight("good", "Recovered well", "Your resting heart rate is 2 bpm below your usual and HRV is up. A good day to push a little.", "heart"),
                Insight("info", "Protein's behind", "38 g to go to reach 120 g. A yoghurt or some eggs would close the gap.", "food"),
            ),
            hourlySteps = listOf(0, 0, 0, 0, 0, 0, 120, 2800, 640, 310, 220, 180, 900, 410, 600, 30, 0, 0, 0, 0, 0, 0, 0, 0),
            heartRate = (0..170).map { i ->
                val t = at(today, 0) + i * 5 * 60_000L
                val hour = i / 12.0
                val base = if (hour < 6.5) 54.0 else 66.0
                val run = if (hour in 7.1..7.75) 80.0 else 0.0
                Point(t, base + 6 * sin(i / 9.0) + run)
            },
            goals = goals,
            hasData = true,
        )
    }

    private val todayState get() = TodayState(today, today, summary, loading = false)

    @Test
    fun todayLight() {
        render { Frame("today") { TodayScreen(todayState, Units.METRIC, zone, TodayActions()) } }
        save("today_light")
    }

    @Test
    fun todayDark() {
        render(dark = true) { Frame("today") { TodayScreen(todayState, Units.METRIC, zone, TodayActions()) } }
        save("today_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun todayWholePage() {
        render { Frame("today") { TodayScreen(todayState, Units.IMPERIAL, zone, TodayActions()) } }
        save("today_full_imperial")
    }

    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun todayWholePageDarkOcean() {
        render(dark = true, palette = Palette.OCEAN) { Frame("today") { TodayScreen(todayState, Units.METRIC, zone, TodayActions()) } }
        save("today_full_dark_ocean")
    }

    @Test
    fun todayEmpty() {
        val empty = DaySummary(date = today.toString(), goals = goals, nutritionTarget = NutritionTarget(2000, 110, 220, 67, 2000, "Your goal"))
        render { Frame("today") { TodayScreen(TodayState(today, today, empty, loading = false), Units.METRIC, zone, TodayActions()) } }
        save("today_empty")
    }

    @Test
    fun scoreDetails() {
        render { Backdrop { ScoreDetails("Readiness", summary.readiness!!) } }
        save("score_readiness")
    }

    private val foodDay = FoodDay(
        date = today.toString(),
        entries = listOf(
            FoodEntry(today.toString(), Meal.BREAKFAST, "Greek yoghurt", Nutrients(146.0, 20.0, 8.0, 4.0), 1.0, "170 g pot", brand = "Fage", id = "1"),
            FoodEntry(today.toString(), Meal.BREAKFAST, "Blueberries", Nutrients(57.0, 0.7, 14.5, 0.3), 1.0, "100 g", id = "2"),
            FoodEntry(today.toString(), Meal.BREAKFAST, "Coffee with oat milk", Nutrients(45.0, 1.0, 6.0, 1.5), 1.0, "1 mug", id = "3"),
            FoodEntry(today.toString(), Meal.LUNCH, "Chicken burrito bowl", Nutrients(640.0, 42.0, 72.0, 18.0), 1.0, "1 bowl", brand = "Homemade", id = "4"),
            FoodEntry(today.toString(), Meal.SNACK, "Almonds", Nutrients(352.0, 13.0, 13.0, 30.0), 2.0, "30 g", id = "5"),
        ),
        water = listOf(WaterEntry(today.toString(), 500, "w1"), WaterEntry(today.toString(), 750, "w2")),
        totals = Nutrients(1240.0, 76.7, 113.5, 53.8),
        waterMl = 1250,
        target = NutritionTarget(1950, 120, 210, 65, 2000, "About 2,200 kcal burned · losing ¼ kg a week"),
        burned = 1882.0,
    )

    @Test
    fun food() {
        render { Frame("food") { FoodScreen(FoodState(today, today, foodDay, loading = false), Units.METRIC, FoodActions()) } }
        save("food_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1800dp-xxhdpi")
    fun foodDarkWhole() {
        render(dark = true) { Frame("food") { FoodScreen(FoodState(today, today, foodDay, loading = false), Units.METRIC, FoodActions()) } }
        save("food_full_dark")
    }

    @Test
    fun addFood() {
        val results = FoodSearch(
            mine = listOf(Food("Overnight oats", Nutrients(320.0, 14.0, 48.0, 8.0), "1 jar", 250.0, id = "f1", uses = 12)),
            online = listOf(
                Food("Rolled oats", Nutrients(150.0, 5.0, 27.0, 2.5), "40 g", 40.0, brand = "Quaker", source = FoodSource.OPEN_FOOD_FACTS),
                Food("Oat milk barista", Nutrients(59.0, 1.0, 6.6, 3.0), "100 ml", brand = "Oatly", source = FoodSource.OPEN_FOOD_FACTS),
                Food("Oatcakes", Nutrients(45.0, 1.1, 5.8, 1.9), "1 oatcake", 10.0, brand = "Nairn's", source = FoodSource.OPEN_FOOD_FACTS),
            ),
        )
        render { Frame("food") { AddFoodScreen(Meal.BREAKFAST, AddFoodState(query = "oat", results = results), AddFoodActions()) } }
        save("food_add_search")
    }

    private fun trend(i: Int): TrendDay {
        val d = today.minusDays(29L - i)
        val wobble = sin(i * PI / 5)
        return TrendDay(
            date = d.toString(),
            readiness = (78 + 8 * wobble).toInt(), sleepScore = (80 + 6 * sin(i / 2.0)).toInt(), activityScore = (85 + 7 * sin(i / 3.0)).toInt(),
            steps = if (i == 12) null else (7500 + 2500 * sin(i / 2.5)).toInt(), activeCalories = 420 + 80 * wobble,
            restingHeartRate = 53 - 2 * wobble, hrv = 45 + 6 * wobble, sleepMinutes = (430 + 40 * sin(i / 1.7)).toInt(),
            weightKg = 67.2 - i * 0.03, calories = 1900 + 200 * sin(i / 1.3), protein = 95 + 15 * wobble,
            workouts = if (i % 3 == 0) 1 else 0, workoutMinutes = if (i % 3 == 0) 40 else 0,
        )
    }

    private val trendsState = TrendsState(
        days = 30,
        trends = Trends(today.minusDays(29).toString(), today.toString(), (0 until 30).map(::trend)),
        weights = (0 until 10).map { i -> Sample(Metric.WEIGHT, at(today.minusDays(27L - i * 3), 7), value = 67.3 - i * 0.1 + 0.1 * sin(i.toDouble()), id = i.toLong(), source = "Entered by hand") },
        loading = false,
    )

    @Test
    fun trends() {
        render { Frame("trends") { TrendsScreen(trendsState, Units.METRIC, goals, zone, TrendsActions()) } }
        save("trends_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun trendsWholeDark() {
        render(dark = true) { Frame("trends") { TrendsScreen(trendsState, Units.IMPERIAL, goals, zone, TrendsActions()) } }
        save("trends_full_dark")
    }

    private val devicesState = DevicesState(
        ring = PairedRing("C4:7A:12:9E:33:01", "R02_9E33", battery = 82, firmware = "RY02_3.00.33"),
        healthConnect = HealthConnectState.ON,
        gadgetbridgeFile = null,
        statuses = mapOf(
            ColmiConduit.ID to ConduitStatus(now - 7 * 60_000, true, null, samples = 412, sleep = 1),
            HealthConnectConduit.ID to ConduitStatus(now - 52 * 60_000, true, null, samples = 1830, workouts = 2),
            GadgetbridgeConduit.ID to ConduitStatus(),
        ),
        serverDevices = listOf(
            Device("d1", DeviceKind.COLMI_RING, "C4:7A:12:9E:33:01", "R02_9E33", battery = 82, lastSyncAt = now - 7 * 60_000, createdAt = 0, samples = 48_211),
            Device("d2", DeviceKind.GARMIN, "com.garmin.android.apps.connectmobile", "Garmin Connect", lastSyncAt = now - 52 * 60_000, createdAt = 0, samples = 120_330),
            Device("d3", DeviceKind.MANUAL, "manual", "Entered by hand", lastSyncAt = now - 3 * 3_600_000, createdAt = 0, samples = 38),
        ),
        loading = false,
    )

    @Test
    fun devices() {
        render { Frame("devices") { DevicesScreen(devicesState, DevicesActions(), now) } }
        save("devices_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun devicesWholeDarkFirstRun() {
        val first = DevicesState(healthConnect = HealthConnectState.OFF, loading = false)
        render(dark = true) { Frame("devices") { DevicesScreen(first, DevicesActions(), now) } }
        save("devices_full_dark_first_run")
    }

    @Test
    fun ringPairing() {
        val found = listOf(FoundRing("C4:7A:12:9E:33:01", "R02_9E33", -54), FoundRing("D1:00:42:11:7B:20", "R10_7B20", -81))
        render { Frame("devices") { RingPairScreen(found, searching = true, error = null, onBack = {}, onSearchAgain = {}, onPick = {}) } }
        save("devices_ring_pairing")
    }

    private val settings = Settings(
        server = "https://fitness.home.arpa", token = "t", serverName = "Fitness at home",
        user = User("u1", "maddie", "Maddie", Role.ADMIN, createdAt = 0),
    )
    private val profile = Profile(birthYear = 1994, heightCm = 170.0, timezone = "America/New_York", goals = goals)

    @Test
    @Config(qualifiers = "w411dp-h2300dp-xxhdpi")
    fun settingsWhole() {
        render { Frame("settings") { SettingsScreen(settings, profile, SettingsActions()) } }
        save("settings_full")
    }

    @Test
    fun settingsDarkBold() {
        render(dark = true) { Frame("settings") { SettingsScreen(settings.copy(boldText = true), profile, SettingsActions()) } }
        save("settings_dark")
    }

    private val noSignIn = SignInActions({ error("unused") }, {}, { _, _, _ -> })

    @Test
    fun signIn() {
        render { SignInScreen("", finishing = false, error = null, actions = noSignIn) }
        save("sign_in")
    }

    @Test
    fun signInWithSso() {
        val config = AuthConfig("Fitness at home", "0.1.0", localLogin = false, oidc = OidcButton("Sign in with Authentik"))
        render(dark = true) { SignInScreen("fitness.home.arpa", finishing = false, error = null, actions = noSignIn, initialConfig = "https://fitness.home.arpa" to config) }
        save("sign_in_sso_dark")
    }
}
