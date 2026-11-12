package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.health.api.AuthConfig
import com.health.api.CheckIn
import com.health.api.Contributor
import com.health.api.DaySummary
import com.health.api.Device
import com.health.api.DeviceKind
import com.health.api.Food
import com.health.api.FoodDay
import com.health.api.FoodEntry
import com.health.api.FoodSearch
import com.health.api.FoodSource
import com.health.api.Goals
import com.health.api.Insight
import com.health.api.Meal
import com.health.api.Metric
import com.health.api.NutritionTarget
import com.health.api.Nutrients
import com.health.api.OidcButton
import com.health.api.Point
import com.health.api.Profile
import com.health.api.Role
import com.health.api.Sample
import com.health.api.Score
import com.health.api.SleepStage
import com.health.api.SleepSummary
import com.health.api.StageSpan
import com.health.api.TrendDay
import com.health.api.Trends
import com.health.api.Units
import com.health.api.User
import com.health.api.WaterEntry
import com.health.api.Workout
import com.health.api.WorkoutType
import com.health.api.ContinenceDay
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceSummary
import com.health.api.Count
import com.health.api.CrisisResource
import com.health.api.Cycle
import com.health.api.CycleDay
import com.health.api.CycleOverview
import com.health.api.CyclePhase
import com.health.api.CyclePrediction
import com.health.api.CycleToday
import com.health.api.DatedValue
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.DueDose
import com.health.api.Flow
import com.health.api.JournalEntry
import com.health.api.JournalKind
import com.health.api.LabResult
import com.health.api.LeakSize
import com.health.api.MedForm
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.MedsDay
import com.health.api.MindDay
import com.health.api.MindSummary
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.MoodEntry
import com.health.api.Mucus
import com.health.api.ScheduleKind
import com.health.api.ScoreBand
import com.health.api.Screener
import com.health.api.ScreenerDefinition
import com.health.api.ScreenerResult
import com.health.app.ui.modules.HubScreen
import com.health.app.ui.modules.continence.ContinenceActions
import com.health.app.ui.modules.continence.ContinenceScreen
import com.health.app.ui.modules.continence.ContinenceState
import com.health.app.ui.modules.continence.PelvicFloorScreen
import com.health.app.ui.modules.cycle.CycleActions
import com.health.app.ui.modules.cycle.CycleScreen
import com.health.app.ui.modules.cycle.CycleState
import com.health.app.ui.modules.meds.MedEditorScreen
import com.health.app.ui.modules.meds.MedsActions
import com.health.app.ui.modules.meds.MedsScreen
import com.health.app.ui.modules.meds.MedsState
import com.health.app.ui.modules.meds.MedsTab
import com.health.app.ui.modules.mind.BreathingScreen
import com.health.app.ui.modules.mind.JournalEditor
import com.health.app.ui.modules.mind.MindActions
import com.health.app.ui.modules.mind.MindHome
import com.health.app.ui.modules.mind.MindState
import com.health.app.ui.modules.mind.ScreenerScreen
import com.health.app.ui.modules.mind.SupportContent
import com.health.app.data.Palette
import com.health.app.data.Settings
import com.health.app.data.ThemeMode
import com.health.app.devices.ble.FoundRing
import com.health.app.devices.colmi.ColmiConduit
import com.health.app.devices.gadgetbridge.GadgetbridgeConduit
import com.health.app.devices.healthconnect.HealthConnectConduit
import com.health.app.sync.ConduitStatus
import com.health.app.sync.PairedRing
import com.health.app.ui.components.Backdrop
import com.health.app.ui.devices.DevicesActions
import com.health.app.ui.devices.DevicesScreen
import com.health.app.ui.devices.DevicesState
import com.health.app.ui.devices.HealthConnectState
import com.health.app.ui.devices.RingPairScreen
import com.health.app.ui.food.AddFoodActions
import com.health.app.ui.food.AddFoodScreen
import com.health.app.ui.food.AddFoodState
import com.health.app.ui.food.FoodActions
import com.health.app.ui.food.FoodScreen
import com.health.app.ui.food.FoodState
import com.health.app.ui.settings.SettingsActions
import com.health.app.ui.settings.SettingsScreen
import com.health.app.ui.signin.SignInActions
import com.health.app.ui.signin.SignInScreen
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.today.ScoreDetails
import com.health.app.ui.today.TodayActions
import com.health.app.ui.today.TodayScreen
import com.health.app.ui.today.TodayState
import com.health.app.ui.trends.TrendsActions
import com.health.app.ui.trends.TrendsScreen
import com.health.app.ui.trends.TrendsState
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
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = palette) { content() }
        }
    }

    @Composable
    private fun Frame(tab: String, content: @Composable () -> Unit) {
        // A module's pages sit under Health, which shows once one is turned on.
        val tabs = if (tab == "health") tabsFor(Module.entries.toSet()) else tabsFor(Module.DEFAULT)
        AppFrame(selected = tab, snackbar = remember { SnackbarHostState() }, onTab = {}, tabs = tabs, content = content)
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
        server = "https://health.home.arpa", token = "t", serverName = "Health at home",
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
        val config = AuthConfig("Health at home", "0.1.0", localLogin = false, oidc = OidcButton("Sign in with Authentik"))
        render(dark = true) { SignInScreen("health.home.arpa", finishing = false, error = null, actions = noSignIn, initialConfig = "https://health.home.arpa" to config) }
        save("sign_in_sso_dark")
    }

    // ---- Health modules ----

    private val allModules = Module.entries.toSet()

    private val mindState: MindState by lazy {
        val moods = listOf(3.2, 3.6, 4.0, 3.0, 2.4, 2.8, 3.4, 3.8, 4.2, 4.0, 3.6, 3.0, 3.4, 3.9, 4.4, 4.1, 3.7, 3.2, 3.5, 3.9, 4.2, 4.5, 4.0, 3.6, 3.3, 3.8, 4.1, 4.3, 3.9, 4.0)
        val days = moods.mapIndexed { i, m -> MindDay(today.minusDays(29L - i).toString(), mood = m, entries = 1, mindfulMinutes = if (i % 3 == 0) 5.0 else 0.0) }
        val phq = ScreenerResult(Screener.PHQ9, at(today.minusDays(14), 20), List(9) { if (it < 4) 1 else 0 }, score = 4, severity = "Minimal", id = "r1")
        val gad = ScreenerResult(Screener.GAD7, at(today.minusDays(14), 20), List(7) { if (it < 3) 2 else 1 }, score = 10, severity = "Moderate", id = "r2")
        MindState(
            summary = MindSummary(
                days, averageMood = moods.average(),
                feelings = listOf(Count("calm", 11), Count("tired", 8), Count("hopeful", 6), Count("anxious", 4)),
                patterns = listOf(
                    Insight("good", "Sleep and mood", "On days after 7 hours or more of sleep, your mood averaged 4.0, against 3.1.", "sleep"),
                ),
                latest = listOf(phq, gad),
                mindfulMinutes = 50.0,
            ),
            moods = listOf(MoodEntry(at(today, 9, 10), 4, feelings = listOf("calm", "hopeful"), id = "m1")),
            journal = listOf(
                JournalEntry(at(today.minusDays(1), 21, 30), JournalKind.GRATITUDE, gratitude = listOf("A long walk in the cold sun", "Soup with Sam", "Finishing the hard email"), id = "j1"),
                JournalEntry(at(today.minusDays(3), 22, 0), JournalKind.FREE, title = "Busy week", text = "Lots on, but I made time to stretch every morning and it helped.", id = "j2"),
            ),
            screeners = listOf(phq9),
            results = listOf(phq, gad),
            resources = crisis,
            loading = false,
        )
    }

    private val crisis = listOf(
        CrisisResource("988 Suicide & Crisis Lifeline", "Call or text 988", "US", "https://988lifeline.org"),
        CrisisResource("Crisis Text Line", "Text HOME to 741741", "US", "https://www.crisistextline.org"),
        CrisisResource("Trans Lifeline", "Call 877-565-8860", "US", "https://translifeline.org", note = "Peer support run by and for trans people"),
    )

    private val phq9 = ScreenerDefinition(
        Screener.PHQ9, "PHQ-9", "Nine questions about the last two weeks, used widely to check in on low mood.",
        "Over the last 2 weeks, how often have you been bothered by any of the following problems?",
        listOf(
            "Little interest or pleasure in doing things",
            "Feeling down, depressed, or hopeless",
            "Trouble falling or staying asleep, or sleeping too much",
            "Feeling tired or having little energy",
            "Poor appetite or overeating",
            "Feeling bad about yourself, or that you are a failure or have let yourself or your family down",
            "Trouble concentrating on things, such as reading the newspaper or watching television",
            "Moving or speaking so slowly that other people could have noticed, or the opposite: being so fidgety or restless that you have been moving around a lot more than usual",
            "Thoughts that you would be better off dead, or of hurting yourself in some way",
        ),
        listOf("Not at all", "Several days", "More than half the days", "Nearly every day"),
        listOf(ScoreBand(0, "Minimal"), ScoreBand(5, "Mild"), ScoreBand(10, "Moderate"), ScoreBand(15, "Moderately severe"), ScoreBand(20, "Severe")),
        "Kroenke, Spitzer & Williams, 2001",
    )

    @Test
    fun mindLight() {
        render { Frame("health") { MindHome(mindState, zone, MindActions()) } }
        save("mind_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    fun mindDarkWhole() {
        render(dark = true) { Frame("health") { MindHome(mindState, zone, MindActions()) } }
        save("mind_full_dark")
    }

    private fun breathing(dark: Boolean, name: String) {
        compose.mainClock.autoAdvance = false
        render(dark) { Frame("health") { BreathingScreen("box-breathing", {}, {}, startRunning = true) } }
        compose.mainClock.advanceTimeBy(2_500)
        save(name)
    }

    @Test
    fun breathingLight() = breathing(false, "breathing_light")

    @Test
    fun breathingDark() = breathing(true, "breathing_dark")

    private val someAnswers: List<Int?> = listOf(1, 2, 1, 2, 0, 1, null, null, null)

    @Test
    fun phq9Light() {
        render { Frame("health") { ScreenerScreen(phq9, mindState.results, null, crisis, zone, {}, {}, {}, someAnswers) } }
        save("phq9_light")
    }

    @Test
    fun phq9Dark() {
        render(dark = true) { Frame("health") { ScreenerScreen(phq9, mindState.results, null, crisis, zone, {}, {}, {}, someAnswers) } }
        save("phq9_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun phq9Result() {
        val result = ScreenerResult(Screener.PHQ9, now, List(9) { 1 }, score = 9, severity = "Mild", id = "r3")
        render { Frame("health") { ScreenerScreen(phq9, mindState.results + result, result, crisis, zone, {}, {}, {}) } }
        save("phq9_result")
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun supportSheet() {
        render(dark = true) { Backdrop { SupportContent(crisis) } }
        save("mind_support_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun thoughtRecord() {
        render { Frame("health") { JournalEditor(null, JournalKind.THOUGHT_RECORD, {}, {}, null) } }
        save("mind_thought_record")
    }

    private val cycleOverview: CycleOverview by lazy {
        fun period(start: LocalDate, flows: List<Flow>) = flows.mapIndexed { i, f ->
            CycleDay(start.plusDays(i.toLong()).toString(), f, if (i < 2) listOf("cramps", "fatigue") else emptyList(), pain = if (i == 0) 5 else null)
        }
        val periods = listOf(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 11, 2))
        val flows = listOf(Flow.MEDIUM, Flow.HEAVY, Flow.MEDIUM, Flow.LIGHT, Flow.SPOTTING)
        val days = periods.flatMap { period(it, flows) } +
            CycleDay("2026-11-10", symptoms = listOf("headache"), mucus = Mucus.CREAMY) +
            CycleDay("2026-11-12", mucus = Mucus.WATERY, temperature = 36.42)
        CycleOverview(
            days = days,
            cycles = listOf(
                Cycle("2026-09-07", 28, 5, ovulation = "2026-09-21"),
                Cycle("2026-10-05", 28, 5, ovulation = "2026-10-19"),
                Cycle("2026-11-02", null, 5),
            ),
            averageCycle = 28.0, averagePeriod = 5.0, variation = 0.8,
            prediction = CyclePrediction("2026-11-30", "2026-11-28", "2026-12-02", "2026-11-11", "2026-11-16", "2026-11-15", "medium"),
            cycleDay = 11, phase = CyclePhase.FERTILE, daysUntilPeriod = 18,
            temperatures = (0 until 40).map { i ->
                val d = today.minusDays(39L - i)
                DatedValue(d.toString(), 36.3 + (if (i in 14..27) 0.3 else 0.0) + 0.05 * sin(i * 1.3))
            },
        )
    }

    @Test
    fun cycleLight() {
        render { Frame("health") { CycleScreen(CycleState(today, overview = cycleOverview, loading = false), Units.METRIC, CycleActions()) } }
        save("cycle_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    fun cycleDarkWhole() {
        render(dark = true) { Frame("health") { CycleScreen(CycleState(today, overview = cycleOverview, loading = false), Units.METRIC, CycleActions()) } }
        save("cycle_full_dark")
    }

    private val continenceState: ContinenceState by lazy {
        val entries = listOf(
            ContinenceEntry(at(today, 3, 40), ContinenceKind.URINATION, urgency = 1, night = true, id = "c1"),
            ContinenceEntry(at(today, 7, 5), ContinenceKind.URINATION, volumeMl = 380, urgency = 2, id = "c2"),
            ContinenceEntry(at(today, 8, 30), ContinenceKind.BOWEL, bristol = 4, id = "c3"),
            ContinenceEntry(at(today, 10, 15), ContinenceKind.LEAK, leak = LeakSize.SMALL, triggers = listOf("cough", "sneeze"), id = "c4"),
            ContinenceEntry(at(today, 11, 0), ContinenceKind.URINATION, urgency = 0, id = "c5"),
            ContinenceEntry(at(today, 12, 30), ContinenceKind.PELVIC_FLOOR, reps = 10, holdSeconds = 5, id = "c6"),
            ContinenceEntry(at(today, 13, 50), ContinenceKind.URINATION, urgency = 3, id = "c7"),
        ).map { it.copy(date = today.toString()) }
        val leaks = listOf(2, 1, 0, 1, 3, 1, 0, 0, 1, 2, 0, 1, 0, 1)
        ContinenceState(
            today, entries,
            ContinenceSummary(
                days = leaks.mapIndexed { i, l -> ContinenceDay(today.minusDays(13L - i).toString(), voids = 6 + (i % 3), nightVoids = i % 2 + 1, leaks = l, fluidMl = 1600) },
                averageVoids = 7.0, averageNightVoids = 1.5, averageLeaks = 0.9,
                triggers = listOf(Count("cough", 6), Count("sneeze", 4), Count("lifting", 2)),
                averageInterval = 140,
                patterns = listOf(Insight("info", "Coughs and sneezes", "Most leaks came with a cough or sneeze. Squeezing your pelvic floor just before can help.", "water")),
            ),
            loading = false,
        )
    }

    @Test
    fun continenceLight() {
        render { Frame("health") { ContinenceScreen(continenceState, zone, ContinenceActions()) } }
        save("continence_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun continenceDarkWhole() {
        render(dark = true) { Frame("health") { ContinenceScreen(continenceState, zone, ContinenceActions()) } }
        save("continence_full_dark")
    }

    @Test
    fun pelvicFloor() {
        render { Frame("health") { PelvicFloorScreen({}) { _, _ -> } } }
        save("pelvic_floor")
    }

    private val estradiol = Medication("Estradiol", "2 mg", MedForm.PILL, MedSchedule(times = listOf("08:00", "20:00")), purpose = "HRT", stock = 6, id = "e")
    private val spiro = Medication("Spironolactone", "50 mg", schedule = MedSchedule(times = listOf("09:00")), stock = 48, id = "s")
    private val shot = Medication(
        "Estradiol valerate", "5 mg (0.25 ml)", MedForm.INJECTION, MedSchedule(ScheduleKind.WEEKLY, listOf("19:30"), weekdays = listOf(4)),
        sites = listOf("Left thigh", "Right thigh", "Left abdomen", "Right abdomen"), id = "v",
    )
    private val vitaminD = Medication("Vitamin D", "1000 IU", MedForm.SUPPLEMENT, MedSchedule(times = listOf("09:00")), reminders = false, id = "d")
    private val ibuprofen = Medication("Ibuprofen", "200 mg", schedule = MedSchedule(ScheduleKind.AS_NEEDED, emptyList()), id = "i")
    private val finasteride = Medication("Finasteride", "1 mg", active = false, id = "f")

    private val medsDay = MedsDay(
        today.toString(),
        due = listOf(
            DueDose("e", "Estradiol", "2 mg", MedForm.PILL, at(today, 8), DoseLog("e", at(today, 8, 12), scheduledFor = at(today, 8))),
            DueDose("s", "Spironolactone", "50 mg", MedForm.PILL, at(today, 9), DoseLog("s", at(today, 9, 4), scheduledFor = at(today, 9))),
            DueDose("d", "Vitamin D", "1000 IU", MedForm.SUPPLEMENT, at(today, 9), DoseLog("d", at(today, 9, 30), DoseStatus.SKIPPED, scheduledFor = at(today, 9))),
            DueDose("v", "Estradiol valerate", "5 mg (0.25 ml)", MedForm.INJECTION, at(today, 19, 30), nextSite = "Right thigh"),
            DueDose("e", "Estradiol", "2 mg", MedForm.PILL, at(today, 20)),
        ),
        asNeeded = listOf(ibuprofen),
        extra = listOf(DoseLog("i", at(today, 11, 45))),
        adherence7d = 94,
        lowStock = listOf(estradiol),
    )

    private val labs = listOf(
        LabResult("Estradiol", 96.0, "pg/mL", at(today.minusDays(180), 9), 100.0, 200.0, id = "l1"),
        LabResult("Estradiol", 142.0, "pg/mL", at(today.minusDays(90), 9), 100.0, 200.0, id = "l2"),
        LabResult("Estradiol", 168.0, "pg/mL", at(today.minusDays(7), 9), 100.0, 200.0, id = "l3"),
        LabResult("Testosterone", 85.0, "ng/dL", at(today.minusDays(180), 9), high = 50.0, id = "l4"),
        LabResult("Testosterone", 32.0, "ng/dL", at(today.minusDays(90), 9), high = 50.0, id = "l5"),
        LabResult("Testosterone", 24.0, "ng/dL", at(today.minusDays(7), 9), high = 50.0, id = "l6"),
        LabResult("Potassium", 4.4, "mmol/L", at(today.minusDays(7), 9), 3.5, 5.1, id = "l7"),
    )

    private val medsState get() = MedsState(today, MedsTab.TODAY, medsDay, listOf(estradiol, spiro, shot, vitaminD, ibuprofen, finasteride), labs, loading = false)

    @Test
    fun medsLight() {
        render { Frame("health") { MedsScreen(medsState, zone, MedsActions()) } }
        save("meds_today_light")
    }

    @Test
    fun medsDark() {
        render(dark = true) { Frame("health") { MedsScreen(medsState, zone, MedsActions(), canBeExact = false) } }
        save("meds_today_dark")
    }

    @Test
    fun medsList() {
        render { Frame("health") { MedsScreen(medsState.copy(tab = MedsTab.ALL), zone, MedsActions()) } }
        save("meds_list")
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun medsLabsDark() {
        render(dark = true) { Frame("health") { MedsScreen(medsState.copy(tab = MedsTab.LABS), zone, MedsActions()) } }
        save("meds_labs_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h1800dp-xxhdpi")
    fun medEditor() {
        render { Frame("health") { MedEditorScreen(shot, {}, {}, {}) } }
        save("meds_editor")
    }

    @Test
    fun hub() {
        render { Frame("health") { HubScreen(allModules, showDevices = true) {} } }
        save("health_hub")
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun featuresLight() {
        render { Frame("settings") { SettingsScreen(settings, profile.copy(modules = Module.DEFAULT + Module.MIND + Module.MEDICATIONS), SettingsActions()) } }
        save("settings_features_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun featuresDark() {
        render(dark = true) { Frame("settings") { SettingsScreen(settings, profile.copy(modules = allModules), SettingsActions()) } }
        save("settings_features_dark")
    }

    private val modulesToday = ModulesToday(
        mind = MindDay(today.toString(), mood = 4.0, entries = 2, mindfulMinutes = 6.0, journal = 1),
        cycle = CycleToday(11, CyclePhase.FERTILE, 18, CycleDay(today.toString(), mucus = Mucus.WATERY)),
        continence = ContinenceDay(today.toString(), voids = 4, nightVoids = 1, leaks = 1, bowel = 1, pelvicFloor = 1),
        meds = medsDay,
    )

    private fun allTabs() = tabsFor(allModules)

    @Test
    @Config(qualifiers = "w411dp-h3600dp-xxhdpi")
    fun todayAllModulesLight() {
        val state = TodayState(today, today, summary.copy(modules = modulesToday), loading = false)
        render {
            AppFrame("today", remember { SnackbarHostState() }, {}, allTabs()) {
                TodayScreen(state, Units.METRIC, zone, TodayActions(), modules = allModules)
            }
        }
        save("today_all_modules_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h3600dp-xxhdpi")
    fun todayAllModulesDark() {
        val state = TodayState(today, today, summary.copy(modules = modulesToday), loading = false)
        render(dark = true) {
            AppFrame("today", remember { SnackbarHostState() }, {}, allTabs()) {
                TodayScreen(state, Units.METRIC, zone, TodayActions(), modules = allModules)
            }
        }
        save("today_all_modules_dark")
    }

    @Test
    fun todayModulesOnly() {
        val only = setOf(Module.MIND, Module.CYCLE, Module.MEDICATIONS)
        val state = TodayState(today, today, summary.copy(modules = modulesToday), loading = false)
        render {
            AppFrame("today", remember { SnackbarHostState() }, {}, tabsFor(only)) {
                TodayScreen(state, Units.METRIC, zone, TodayActions(), modules = only)
            }
        }
        save("today_modules_only")
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun trendsWithModules() {
        val days = trendsState.trends!!.days.mapIndexed { i, d ->
            d.copy(
                mood = 3.0 + sin(i / 3.0), mindfulMinutes = if (i % 3 == 0) 5.0 else null,
                flow = if (i in 21..25) listOf(Flow.MEDIUM, Flow.HEAVY, Flow.MEDIUM, Flow.LIGHT, Flow.SPOTTING)[i - 21] else null,
                voids = 6 + i % 3, nightVoids = i % 2, leaks = listOf(0, 1, 0, 2)[i % 4],
                dosesDue = 4, dosesTaken = if (i % 6 == 0) 3 else 4,
            )
        }
        val state = trendsState.copy(trends = trendsState.trends!!.copy(days = days))
        render { AppFrame("trends", remember { SnackbarHostState() }, {}, allTabs()) { TrendsScreen(state, Units.METRIC, goals, zone, TrendsActions(), modules = allModules) } }
        save("trends_modules")
    }
}
