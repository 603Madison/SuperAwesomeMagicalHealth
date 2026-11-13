package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.health.api.AiAuditEntry
import com.health.api.AiConsent
import com.health.api.AiLook
import com.health.api.AiStatus
import com.health.api.Awareness
import com.health.api.BladderTraining
import com.health.api.Clots
import com.health.api.ConditionDefinition
import com.health.api.ConditionKind
import com.health.api.ConditionStatus
import com.health.api.ContinenceCheckIn
import com.health.api.ContinenceDay
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceProgress
import com.health.api.ContinenceSettings
import com.health.api.ContinenceSummary
import com.health.api.Count
import com.health.api.CustomField
import com.health.api.CustomFieldType
import com.health.api.Cycle
import com.health.api.CycleDay
import com.health.api.CycleOverview
import com.health.api.CyclePhase
import com.health.api.CyclePrediction
import com.health.api.CycleSettings
import com.health.api.DaySummary
import com.health.api.FieldLayout
import com.health.api.Fields
import com.health.api.Flow
import com.health.api.Fullness
import com.health.api.Goals
import com.health.api.Insight
import com.health.api.LeakContents
import com.health.api.LeakSize
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.MyCondition
import com.health.api.MyConditions
import com.health.api.ProductUse
import com.health.api.ProgressMetric
import com.health.api.ProgressWeek
import com.health.api.ReachedToilet
import com.health.api.Score
import com.health.api.Supply
import com.health.api.SupplyAdjustment
import com.health.api.SupplyKind
import com.health.api.SupplyReason
import com.health.api.SupplyStatus
import com.health.api.Units
import com.health.api.Verdict
import com.health.app.data.ThemeMode
import com.health.app.ui.ai.AiActions
import com.health.app.ui.ai.AiAuditScreen
import com.health.app.ui.ai.AiChatScreen
import com.health.app.ui.ai.AiConsentScreen
import com.health.app.ui.ai.AiState
import com.health.app.ui.ai.ChatTurn
import com.health.app.ui.conditions.ConditionsActions
import com.health.app.ui.conditions.ConditionsScreen
import com.health.app.ui.conditions.ConditionsState
import com.health.app.ui.conditions.FieldsScreen
import com.health.app.ui.conditions.FieldsState
import com.health.app.ui.modules.continence.CheckInScreen
import com.health.app.ui.modules.continence.CheckInState
import com.health.app.ui.modules.continence.ContinenceActions
import com.health.app.ui.modules.continence.ContinenceScreen
import com.health.app.ui.modules.continence.ContinenceState
import com.health.app.ui.modules.continence.DiarySettingsScreen
import com.health.app.ui.modules.continence.DiarySettingsState
import com.health.app.ui.modules.continence.EntryForm
import com.health.app.ui.modules.continence.FormContext
import com.health.app.ui.modules.continence.ProgressScreen
import com.health.app.ui.modules.continence.ProgressState
import com.health.app.ui.modules.continence.SuppliesActions
import com.health.app.ui.modules.continence.SuppliesScreen
import com.health.app.ui.modules.continence.SuppliesState
import com.health.app.ui.modules.continence.SupplyArea
import com.health.app.ui.modules.continence.SupplyDetail
import com.health.app.ui.modules.cycle.CycleActions
import com.health.app.ui.modules.cycle.CycleScreen
import com.health.app.ui.modules.cycle.CycleSettingsScreen
import com.health.app.ui.modules.cycle.CycleSettingsState
import com.health.app.ui.modules.cycle.CycleState
import com.health.app.ui.modules.cycle.DayForm
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.today.TodayActions
import com.health.app.ui.today.TodayScreen
import com.health.app.ui.today.TodayState
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
 * Conditions and fields, the deeper bladder and bowel diary, supplies, progress, the cycle's
 * detail and settings, and the AI assistant, drawn with realistic data to PNGs in
 * android/build/outputs/screenshots (as ScreenshotTest does for the rest), light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class DiaryScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 11, 12)
    private fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val all = Module.entries.toSet()
    private val everyField = Fields.ALL.keys

    private fun render(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { content() } }
    }

    @Composable
    private fun Frame(tab: String, content: @Composable () -> Unit) {
        AppFrame(selected = tab, snackbar = remember { SnackbarHostState() }, onTab = {}, tabs = tabsFor(all), content = content)
    }

    /** A form as its bottom sheet draws it. */
    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) { content() }
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- Conditions and fields ----

    private val catalog = listOf(
        ConditionDefinition("overactive-bladder", "Overactive bladder", ConditionKind.CONDITION, "Bladder and bowel", "Sudden urges to go, going often, and sometimes leaking before you get there.", setOf(Module.CONTINENCE), aliases = listOf("OAB", "urge incontinence")),
        ConditionDefinition("stress-incontinence", "Stress incontinence", ConditionKind.CONDITION, "Bladder and bowel", "Leaks when coughing, sneezing, laughing, lifting or exercising.", setOf(Module.CONTINENCE), aliases = listOf("SUI")),
        ConditionDefinition("bowel-incontinence", "Bowel leakage", ConditionKind.CONDITION, "Bladder and bowel", "Leaks of stool or wind, or not always making it in time.", setOf(Module.CONTINENCE)),
        ConditionDefinition("endometriosis", "Endometriosis", ConditionKind.CONDITION, "Periods and hormones", "Pain with periods and beyond, sometimes heavy bleeding.", setOf(Module.CYCLE), aliases = listOf("endo")),
        ConditionDefinition("pcos", "PCOS", ConditionKind.CONDITION, "Periods and hormones", "Irregular cycles, and more.", setOf(Module.CYCLE)),
        ConditionDefinition("adhd", "ADHD", ConditionKind.CONDITION, "Neurodivergence", "Attention, energy and time, your way.", setOf(Module.NEURO)),
        ConditionDefinition("sleep-better", "Sleep better", ConditionKind.GOAL, "Goals", "More regular, more restful nights.", setOf(Module.SLEEP)),
        ConditionDefinition("menopause", "Menopause", ConditionKind.LIFE, "Life", "Perimenopause and after.", setOf(Module.CYCLE)),
    )

    private val mine = MyConditions(
        listOf(
            MyCondition("overactive-bladder", "Overactive bladder", status = ConditionStatus.DIAGNOSED, since = "2023-04", notes = "Worse in cold weather"),
            MyCondition("custom:long-covid", "Long Covid", status = ConditionStatus.MANAGING, modules = setOf(Module.SYMPTOMS, Module.HABITS)),
        ),
    )

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun conditionsLight() {
        val state = ConditionsState(catalog, saved = MyConditions(mine.conditions.take(1)), mine = mine, loading = false)
        render { Frame("settings") { ConditionsScreen(state, Module.DEFAULT + Module.CONTINENCE, {}, ConditionsActions()) } }
        save("conditions_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun conditionsDark() {
        val state = ConditionsState(catalog, saved = mine, mine = mine, loading = false)
        render(dark = true) { Frame("settings") { ConditionsScreen(state, Module.DEFAULT + Module.CONTINENCE + Module.SYMPTOMS + Module.HABITS, {}, ConditionsActions()) } }
        save("conditions_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun fieldsLight() {
        val effective = Fields.DEFAULTS + Fields.CONTINENCE_BLADDER_TRAINING + Fields.CONTINENCE_PROTECTION + Fields.CONTINENCE_PAD_WEIGHT - Fields.CONTINENCE_BRISTOL
        val layout = FieldLayout(
            effective, show = setOf(Fields.CONTINENCE_PAD_WEIGHT), hide = setOf(Fields.CONTINENCE_BRISTOL),
            because = effective.associateWith { "default" } + mapOf(Fields.CONTINENCE_BLADDER_TRAINING to "Overactive bladder", Fields.CONTINENCE_PROTECTION to "Overactive bladder", Fields.CONTINENCE_PAD_WEIGHT to "you"),
        )
        render { Frame("settings") { FieldsScreen(FieldsState(layout, loading = false), Module.DEFAULT + Module.CONTINENCE + Module.CYCLE, {}) } }
        save("fields_light")
    }

    // ---- Bladder and bowel ----

    private val pads = Supply("Night pads", SupplyKind.PAD, brand = "Tena", absorbency = "Maxi", dryWeightG = 22.0, onHand = 6, perPack = 24, id = "s1")
    private val pullUps = Supply("Pull-ups", SupplyKind.PULL_UP, absorbency = "Super", onHand = 34, id = "s2")
    private val briefs = Supply("Overnight briefs", SupplyKind.BRIEF, size = "M", absorbency = "10 drops", onHand = 18, lowAt = 10, id = "s3")
    private val wipes = Supply("Wipes", SupplyKind.WIPES, onHand = 2, id = "s4")
    private val tampons = Supply("Tampons", SupplyKind.TAMPON, absorbency = "Regular", onHand = 14, id = "s5")
    private val cup = Supply("Cup", SupplyKind.CUP, reusable = true, id = "s6")
    private val statuses = listOf(
        SupplyStatus(pads, perDay = 1.4, daysLeft = 4, low = true, lastRestocked = "2026-10-20"),
        SupplyStatus(pullUps, perDay = 2.1, daysLeft = 16),
        SupplyStatus(briefs, perDay = 0.9, daysLeft = 20),
        SupplyStatus(wipes, low = true),
        SupplyStatus(tampons, perDay = 0.6, daysLeft = 23),
        SupplyStatus(cup),
    )

    private val diarySettings = ContinenceSettings(
        quickButtons = listOf(ContinenceKind.URINATION, ContinenceKind.LEAK, ContinenceKind.PAD_CHANGE, ContinenceKind.BOWEL_LEAK),
        triggers = listOf("cold hands"), activities = listOf("gardening"), locations = listOf("the allotment"),
        customFields = listOf(CustomField("tired", "How tired", CustomFieldType.SCALE, max = 10), CustomField("fluids", "Drinks since the last trip", CustomFieldType.NUMBER, unit = "ml")),
        defaultSupplyId = "s1", nightFrom = "22:30", nightTo = "06:30",
        bladderTraining = BladderTraining(enabled = true, intervalMinutes = 60, stepMinutes = 15, stepEveryDays = 7, goalMinutes = 180, startedOn = "2026-10-29"),
    )

    private val progress = ContinenceProgress(
        Verdict.IMPROVING, "Getting better: leaks a day went from 1.6 to 0.7.", 4,
        listOf(
            ProgressMetric("leaks", "Leaks a day", 1.6, 0.7, verdict = Verdict.IMPROVING),
            ProgressMetric("bowelLeaks", "Bowel accidents a day", 0.3, 0.25, verdict = Verdict.STEADY),
            ProgressMetric("urgency", "Urgency (0–3)", 1.9, 1.4, verdict = Verdict.IMPROVING),
            ProgressMetric("products", "Products a day", 2.9, 3.4, verdict = Verdict.WORSENING),
            ProgressMetric("loss", "Loss a day", 64.0, 41.0, "g", verdict = Verdict.IMPROVING),
            ProgressMetric("bother", "How much it bothers you (0–10)", 7.0, null, verdict = Verdict.NOT_ENOUGH),
        ),
        (0 until 12).map { i ->
            ProgressWeek(
                today.minusWeeks(11L - i).with(java.time.DayOfWeek.MONDAY).toString(), if (i == 3) 0 else 6 + i % 2,
                voidsPerDay = 8.5 - i * 0.15, leaksPerDay = if (i == 3) null else 1.8 - i * 0.1 + 0.2 * sin(i.toDouble()),
                urgency = 2.0 - i * 0.06, productsPerDay = 2.8 + 0.3 * sin(i / 2.0), lossGPerDay = 70.0 - i * 3, bother = if (i % 3 == 0) 7.0 - i * 0.3 else null,
                pelvicFloorSets = i,
            )
        },
        listOf(
            "You did pelvic floor exercises more often lately (9 sets before, 24 since). Regular practice often goes with fewer leaks.",
            "Less caffeine lately (about 210 mg a day before, 120 mg since). For some people that goes with fewer urges.",
        ),
    )

    private val continenceState: ContinenceState by lazy {
        val entries = listOf(
            ContinenceEntry(at(today, 3, 40), ContinenceKind.BEDWETTING, leak = LeakSize.MEDIUM, supplyId = "s3", fullness = Fullness.SOAKED, changedBedding = true, night = true, id = "c1"),
            ContinenceEntry(at(today, 7, 5), ContinenceKind.URINATION, volumeMl = 380, urgency = 2, utiSigns = listOf("burning"), id = "c2"),
            ContinenceEntry(at(today, 8, 0), ContinenceKind.PAD_CHANGE, supplyId = "s1", count = 1, fullness = Fullness.WET, padWeightG = 61.0, lossG = 39.0, id = "c3"),
            ContinenceEntry(at(today, 10, 15), ContinenceKind.LEAK, leak = LeakSize.SMALL, triggers = listOf("cough"), activity = listOf("lifting"), awareness = Awareness.FELT_DURING, id = "c4"),
            ContinenceEntry(at(today, 12, 40), ContinenceKind.BOWEL_LEAK, contents = LeakContents.WIND, warningSeconds = 5, reachedToilet = ReachedToilet.PARTLY, location = "work", id = "c5"),
            ContinenceEntry(at(today, 13, 50), ContinenceKind.URINATION, urgency = 3, id = "c6"),
        ).map { it.copy(date = today.toString()) }
        val leaks = listOf(2, 1, 0, 1, 3, 1, 0, 0, 1, 2, 0, 1, 0, 1)
        ContinenceState(
            today, entries,
            ContinenceSummary(
                days = leaks.mapIndexed { i, l -> ContinenceDay(today.minusDays(13L - i).toString(), voids = 6 + (i % 3), nightVoids = i % 2 + 1, leaks = l, fluidMl = 1600) },
                averageVoids = 7.0, averageNightVoids = 1.5, averageLeaks = 0.9,
                triggers = listOf(Count("cough", 6), Count("lifting", 4), Count("on the way", 2)),
                averageInterval = 140,
                patterns = listOf(
                    Insight("warn", "Signs that can go with a urine infection", "Burning, cloudy or strong-smelling urine, or going much more often, are worth telling a doctor or nurse about soon, and today if you have a fever or back pain.", "thermometer"),
                    Insight("info", "Most leaks came with coughs, laughs or lifting", "Daily pelvic floor exercises often help with these.", "activity"),
                ),
            ),
            loading = false,
            settings = diarySettings, supplies = statuses, progress = progress,
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h3000dp-xxhdpi")
    fun continenceDetailLight() {
        render { Frame("health") { ContinenceScreen(continenceState, zone, ContinenceActions(), fields = everyField) } }
        save("continence_detail_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h3000dp-xxhdpi")
    fun continenceDetailDark() {
        render(dark = true) { Frame("health") { ContinenceScreen(continenceState, zone, ContinenceActions(), fields = everyField) } }
        save("continence_detail_dark")
    }

    private val accident = ContinenceEntry(
        at(today, 10, 15), ContinenceKind.LEAK, contents = LeakContents.URINE, leak = LeakSize.MEDIUM, urgency = 3, warningSeconds = 30,
        awareness = Awareness.FELT_IT, reachedToilet = ReachedToilet.NO, triggers = listOf("cough"), activity = listOf("lifting"),
        supplyId = "s1", fullness = Fullness.FULL, padWeightG = 96.0, count = 1, changedClothes = true, skin = 1, location = "work",
        utiSigns = listOf("burning"), details = mapOf("tired" to "6"), tags = listOf("long day"), id = "e1",
    )

    @Test
    @Config(qualifiers = "w411dp-h4200dp-xxhdpi")
    fun entryFormLight() {
        render { Sheet { EntryForm(accident, FormContext(everyField, diarySettings, statuses.map { it.supply }), {}, {}) } }
        save("continence_form_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h4200dp-xxhdpi")
    fun entryFormDark() {
        render(dark = true) { Sheet { EntryForm(accident, FormContext(everyField, diarySettings, statuses.map { it.supply }), {}, {}) } }
        save("continence_form_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun entryFormDefaults() {
        // A new entry with only the default fields: the core and little else.
        render { Sheet { EntryForm(ContinenceEntry(at(today, 14, 0), ContinenceKind.LEAK), FormContext(Fields.DEFAULTS), {}, null) } }
        save("continence_form_defaults")
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun entryFormPadChange() {
        render { Sheet { EntryForm(ContinenceEntry(at(today, 14, 0), ContinenceKind.PAD_CHANGE, supplyId = "s1", count = 2, fullness = Fullness.SOAKED, leakedThrough = true), FormContext(everyField, diarySettings, statuses.map { it.supply }), {}, {}) } }
        save("continence_form_pad_change")
    }

    private val history = listOf(
        SupplyAdjustment(at(today, 8, 0), -1, SupplyReason.USED, id = "a1"),
        SupplyAdjustment(at(today.minusDays(1), 21, 30), -2, SupplyReason.USED, id = "a2"),
        SupplyAdjustment(at(today.minusDays(3), 17, 10), 24, SupplyReason.RESTOCKED, "Pharmacy", id = "a3"),
        SupplyAdjustment(at(today.minusDays(9), 9, 0), 0, SupplyReason.COUNTED, id = "a4"),
    )

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun suppliesLight() {
        render { Frame("health") { SuppliesScreen(SupplyArea.CONTINENCE, SuppliesState(statuses, putAway = listOf(Supply("Old pads", SupplyKind.PAD, active = false, id = "x")), loading = false), zone, {}, SuppliesActions()) } }
        save("supplies_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun suppliesDark() {
        render(dark = true) { Frame("health") { SuppliesScreen(SupplyArea.CONTINENCE, SuppliesState(statuses, loading = false), zone, {}, SuppliesActions()) } }
        save("supplies_dark")
    }

    @Test
    fun suppliesPeriodEmpty() {
        render { Frame("health") { SuppliesScreen(SupplyArea.CYCLE, SuppliesState(statuses.take(2), loading = false), zone, {}, SuppliesActions()) } }
        save("supplies_period_empty")
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun supplyDetail() {
        render { Sheet { SupplyDetail(statuses.first(), history, zone, readOnly = false, onAdjust = {}, onEdit = {}) } }
        save("supply_detail")
    }

    @Test
    @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    fun progressLight() {
        render { Frame("health") { ProgressScreen(ProgressState(12, progress, loading = false), {}) } }
        save("progress_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    fun progressDark() {
        render(dark = true) { Frame("health") { ProgressScreen(ProgressState(12, progress, loading = false), {}) } }
        save("progress_dark")
    }

    @Test
    fun progressNotEnough() {
        val early = ContinenceProgress(
            Verdict.NOT_ENOUGH, "A few more weeks of the diary and this will show how things are going.", 4,
            listOf(ProgressMetric("leaks", "Leaks a day", null, 1.2, verdict = Verdict.NOT_ENOUGH)),
            (0 until 8).map { i -> ProgressWeek(today.minusWeeks(7L - i).with(java.time.DayOfWeek.MONDAY).toString(), if (i >= 6) 3 else 0, leaksPerDay = if (i >= 6) 1.2 else null) },
        )
        render { Frame("health") { ProgressScreen(ProgressState(8, early, loading = false), {}) } }
        save("progress_not_enough")
    }

    @Test
    @Config(qualifiers = "w411dp-h1800dp-xxhdpi")
    fun checkIn() {
        val list = (0 until 6).map { i -> ContinenceCheckIn(today.minusWeeks(i.toLong()).toString(), 3 + i, compared = if (i == 0) 1 else 0, impact = listOf("sleep", "going out").take(1 + i % 2), id = "k$i") }
        render { Frame("health") { CheckInScreen(CheckInState(today, list, loading = false), {}) } }
        save("continence_check_in")
    }

    @Test
    @Config(qualifiers = "w411dp-h3000dp-xxhdpi")
    fun diarySettings() {
        render { Frame("health") { DiarySettingsScreen(DiarySettingsState(diarySettings, statuses.map { it.supply }, loading = false), everyField, {}, today = today) } }
        save("continence_settings")
    }

    @Test
    @Config(qualifiers = "w411dp-h3000dp-xxhdpi")
    fun diarySettingsDark() {
        render(dark = true) { Frame("health") { DiarySettingsScreen(DiarySettingsState(diarySettings, statuses.map { it.supply }, loading = false), everyField, {}, today = today) } }
        save("continence_settings_dark")
    }

    // ---- Cycle ----

    private val cycleSettings = CycleSettings(
        symptoms = listOf("jaw pain"), hiddenSymptoms = listOf("hot flashes", "night sweats"), moods = listOf("restless"), painAreas = listOf("hips"),
        customFields = listOf(CustomField("iron", "Took iron", CustomFieldType.YES_NO), CustomField("flow-feel", "How heavy it felt", CustomFieldType.CHOICE, listOf("Light", "Usual", "Heavy"))),
        cycleLength = 30, remindBeforePeriod = 2, remindLateAfter = 3, dailyReminder = "20:30", neutralWording = true,
    )

    private val cycleDay = CycleDay(
        today.toString(), Flow.HEAVY, symptoms = listOf("cramps", "fatigue"), pain = 6,
        products = listOf(ProductUse(SupplyKind.TAMPON, 3, Fullness.FULL, "s5"), ProductUse(SupplyKind.PERIOD_UNDERWEAR, 1, Fullness.DAMP)),
        clots = Clots.SMALL, color = "dark red", painAreas = listOf("lower belly", "lower back"), symptomSeverity = mapOf("cramps" to 3, "fatigue" to 2),
        mood = listOf("tearful"), energy = 2, libido = 2, lh = 4.5, discharge = "creamy", details = mapOf("iron" to "yes"), tags = listOf("work trip"),
        note = "Needed a hot water bottle",
    )

    @Test
    @Config(qualifiers = "w411dp-h4600dp-xxhdpi")
    fun cycleDayFormLight() {
        render { Sheet { DayForm(today, cycleDay, Units.METRIC, {}, {}, everyField, cycleSettings, statuses.map { it.supply }) } }
        save("cycle_form_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h4600dp-xxhdpi")
    fun cycleDayFormDark() {
        render(dark = true) { Sheet { DayForm(today, cycleDay, Units.METRIC, {}, {}, everyField, cycleSettings, statuses.map { it.supply }) } }
        save("cycle_form_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun cycleDayFormDefaults() {
        render { Sheet { DayForm(today, null, Units.METRIC, {}, {}) } }
        save("cycle_form_defaults")
    }

    @Test
    @Config(qualifiers = "w411dp-h3000dp-xxhdpi")
    fun cycleSettingsLight() {
        render { Frame("health") { CycleSettingsScreen(CycleSettingsState(cycleSettings, loading = false), {}) } }
        save("cycle_settings_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    fun cyclePlainWording() {
        val overview = CycleOverview(
            days = listOf(CycleDay("2026-11-02", Flow.HEAVY), CycleDay("2026-11-03", Flow.MEDIUM), CycleDay("2026-11-04", Flow.LIGHT)),
            cycles = listOf(Cycle("2026-10-05", 28, 5), Cycle("2026-11-02", null, 3)),
            averageCycle = 28.0, averagePeriod = 4.0, variation = 1.2,
            prediction = CyclePrediction("2026-11-30", "2026-11-28", "2026-12-02", "2026-11-11", "2026-11-16", "2026-11-15", "medium"),
            cycleDay = 11, phase = CyclePhase.FERTILE, daysUntilPeriod = 18,
        )
        val state = CycleState(today, overview = overview, loading = false, settings = cycleSettings, supplies = listOf(SupplyStatus(tampons, perDay = 0.8, daysLeft = 3, low = true)))
        render(dark = true) { Frame("health") { CycleScreen(state, Units.METRIC, CycleActions(), fields = everyField) } }
        save("cycle_plain_dark")
    }

    // ---- AI assistant ----

    private val notice = "When you ask the assistant something, your question, and whatever health data it looks at to answer, is sent to Anthropic, a company " +
        "outside this server, where its Claude model reads it. It only looks at the features you allow here, and never at your photos. Anthropic handles what " +
        "it's sent under its commercial terms and privacy policy, and the settings of the Anthropic account your server's admin set up apply to it. Your " +
        "conversations aren't kept on this server; a log of when you asked and which features it looked at is, so you can check. If you'd rather your data " +
        "didn't leave your server, leave this off: everything else works without it."

    private val status = AiStatus(available = true, model = "a-model", usedToday = 3, dailyLimit = 40, loggingAllowed = true, dataNotice = notice)

    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun aiConsentLight() {
        render { Frame("settings") { AiConsentScreen(status, all, {}, {}) } }
        save("ai_consent_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun aiConsentDark() {
        val existing = status.copy(consent = AiConsent(setOf(Module.SLEEP, Module.ACTIVITY, Module.CONTINENCE), acknowledged = true, sensitiveAcknowledged = true), loggingAllowed = false)
        render(dark = true) { Frame("settings") { AiConsentScreen(existing, all, {}, {}, onForget = {}) } }
        save("ai_consent_dark")
    }

    private val chat = AiState(
        status = status.copy(consent = AiConsent(setOf(Module.SLEEP, Module.ACTIVITY, Module.CONTINENCE), acknowledged = true, sensitiveAcknowledged = true)),
        loading = false,
        turns = listOf(
            ChatTurn(ChatTurn.USER, "Are my leaks getting better?"),
            ChatTurn(
                ChatTurn.ASSISTANT,
                "Yes, gently. Over the last four weeks you've averaged about 0.7 leaks a day, down from 1.6 in the four weeks before, and most of the ones left came with a cough or lifting. You've also done more pelvic floor sets lately, which often goes along with that.",
                listOf(AiLook("continence_progress", Module.CONTINENCE, "Bladder and bowel progress, 12 weeks"), AiLook("continence", Module.CONTINENCE, "Bladder and bowel diary, 28 days")),
            ),
            ChatTurn(ChatTurn.USER, "And my sleep?"),
        ),
        sending = true,
    )

    @Test
    fun aiChatLight() {
        render { Frame("settings") { AiChatScreen(chat, chat.status!!, {}, AiActions()) } }
        save("ai_chat_light")
    }

    @Test
    fun aiChatDarkError() {
        val failed = chat.copy(sending = false, chatError = "Anthropic is busy or unavailable right now; try again shortly", status = chat.status!!.copy(usedToday = 39))
        render(dark = true) { Frame("settings") { AiChatScreen(failed, failed.status!!, {}, AiActions()) } }
        save("ai_chat_dark")
    }

    @Test
    fun aiChatEmpty() {
        val empty = chat.copy(turns = emptyList(), sending = false)
        render { Frame("settings") { AiChatScreen(empty, empty.status!!, {}, AiActions()) } }
        save("ai_chat_empty")
    }

    @Test
    fun aiAudit() {
        val audit = listOf(
            AiAuditEntry(at(today, 14, 5), "chat", setOf(Module.CONTINENCE), listOf("continence_progress", "continence"), "a-model"),
            AiAuditEntry(at(today, 9, 40), "chat", setOf(Module.SLEEP, Module.ACTIVITY), listOf("sleep", "day"), "a-model"),
            AiAuditEntry(at(today.minusDays(2), 20, 15), "chat", emptySet(), emptyList(), "a-model"),
        )
        render { Frame("settings") { AiAuditScreen(audit, zone, {}) } }
        save("ai_audit")
    }

    // ---- Today ----

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun todayLowSupplies() {
        val summary = DaySummary(
            date = today.toString(), readiness = Score(78, "Good", emptyList()), sleep = Score(81, "Good", emptyList()), activity = Score(72, "Good", emptyList()),
            hasData = true, goals = Goals(),
            modules = ModulesToday(continence = ContinenceDay(today.toString(), voids = 5, leaks = 1), lowSupplies = statuses.filter { it.low }),
        )
        render { Frame("today") { TodayScreen(TodayState(today, today, summary, loading = false), Units.METRIC, zone, TodayActions(), modules = Module.DEFAULT + Module.CONTINENCE) } }
        save("today_low_supplies")
    }
}
