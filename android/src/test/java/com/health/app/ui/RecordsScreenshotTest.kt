package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.health.api.HealthRecords
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.RecordConnect
import com.health.api.RecordConnection
import com.health.api.RecordFlag
import com.health.api.RecordItem
import com.health.api.RecordKind
import com.health.api.RecordOrganization
import com.health.api.RecordProvider
import com.health.api.ScheduleKind
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.records.RecordsActions
import com.health.app.ui.records.RecordsScreen
import com.health.app.ui.records.RecordsState
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * You › Medical records, as hers would look: not set up on the server (for her and for an admin),
 * finding her hospital, Mass General Brigham connected with its whole record, and Wentworth-Douglass
 * needing her to sign in again. Light and dark. PNGs in android/build/outputs/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class RecordsScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val zone = ZoneId.of("America/New_York")
    private val now = LocalDate.of(2026, 11, 18).atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
    private val h = 3_600_000L
    private val d = 24 * h
    private fun day(daysAgo: Long) = LocalDate.of(2026, 11, 18).minusDays(daysAgo).toString()

    private val mgb = RecordConnection(
        "c1", "Mass General Brigham", "https://epicproxy.example/api/FHIR/R4", RecordProvider.EPIC, connectedAt = now - 9 * d, lastSyncAt = now - 2 * h,
        keepsUpdating = true,
        counts = mapOf(RecordKind.LAB to 10, RecordKind.VITAL to 3, RecordKind.MEDICATION to 4, RecordKind.CONDITION to 3, RecordKind.ALLERGY to 2, RecordKind.IMMUNIZATION to 3, RecordKind.PROCEDURE to 2),
    )
    private val wdh = RecordConnection(
        "c2", "Wentworth-Douglass Hospital", "https://epicfhir.wdh.example/R4", RecordProvider.EPIC, connectedAt = now - 40 * d, lastSyncAt = now - 33 * d,
        keepsUpdating = false, problem = "Your sign-in there has run out. Sign in again to bring it up to date.",
        counts = mapOf(RecordKind.LAB to 2, RecordKind.IMMUNIZATION to 1),
    )

    private fun med(name: String, dose: String, schedule: MedSchedule = MedSchedule()) = Medication(name, dose, schedule = schedule)

    private val items: List<RecordItem> = run {
        fun it(id: String, kind: RecordKind, title: String, detail: String? = null, date: String? = null, status: String? = null, flag: RecordFlag? = null, medication: Medication? = null) =
            RecordItem(id, "c1", kind, title, detail, date, status = status, flag = flag, organization = "Mass General Brigham", medication = medication)
        listOf(
            it("l1", RecordKind.LAB, "TSH", "5.8 mIU/L (usual 0.4–4.0)", day(6), "final", RecordFlag.HIGH),
            it("l2", RecordKind.LAB, "Ferritin", "9 ng/mL (usual 15–150)", day(6), "final", RecordFlag.LOW),
            it("l3", RecordKind.LAB, "Estradiol", "142 pg/mL (usual 30–400)", day(6), "final"),
            it("l4", RecordKind.LAB, "Hemoglobin", "12.9 g/dL (usual 12.0–15.5)", day(6), "final"),
            it("l5", RecordKind.LAB, "Vitamin D, 25-hydroxy", "31 ng/mL (usual 30–100)", day(40), "final"),
            it("l6", RecordKind.LAB, "Hemoglobin A1c", "5.3 % (usual under 5.7)", day(40), "final"),
            it("l7", RecordKind.LAB, "Potassium", "4.1 mmol/L (usual 3.5–5.1)", day(40), "final"),
            it("l8", RecordKind.LAB, "Urine culture", "Mixed growth", day(72), "final", RecordFlag.ABNORMAL),
            it("l9", RecordKind.LAB, "LDL cholesterol", "96 mg/dL (usual under 100)", day(120), "final"),
            it("l10", RecordKind.LAB, "Creatinine", "0.8 mg/dL (usual 0.6–1.1)", day(120), "final"),
            it("v1", RecordKind.VITAL, "Blood pressure", "118/76 mmHg", day(6)),
            it("v2", RecordKind.VITAL, "Heart rate", "68 bpm", day(6)),
            it("v3", RecordKind.VITAL, "Weight", "64.2 kg", day(6)),
            it("m1", RecordKind.MEDICATION, "Levothyroxine 50 mcg tablet", "1 tablet, once a day, before breakfast", day(6), "active", medication = med("Levothyroxine", "50 mcg")),
            it("m2", RecordKind.MEDICATION, "Ferrous sulfate 325 mg tablet", "1 tablet every other day", day(6), "active",
                medication = med("Ferrous sulfate", "325 mg", MedSchedule(ScheduleKind.EVERY_N_DAYS, everyDays = 2))),
            it("m3", RecordKind.MEDICATION, "Vitamin D3 1000 unit capsule", "1 capsule a day", day(40), "active", medication = med("Vitamin D", "1000 units")),
            it("m4", RecordKind.MEDICATION, "Amoxicillin 500 mg capsule", "3 times a day for 7 days", day(300), "stopped"),
            it("d1", RecordKind.CONDITION, "Hypothyroidism", "Since 2019", status = "active"),
            it("d2", RecordKind.CONDITION, "Iron deficiency", "Since this year", status = "active"),
            it("d3", RecordKind.CONDITION, "Migraine", "2016 to 2021", status = "resolved"),
            it("a1", RecordKind.ALLERGY, "Penicillin", "Hives · severe", status = "active"),
            it("a2", RecordKind.ALLERGY, "Latex", "Itchy skin · mild", status = "active"),
            it("i1", RecordKind.IMMUNIZATION, "Influenza vaccine", date = day(30), status = "completed"),
            it("i2", RecordKind.IMMUNIZATION, "COVID-19 vaccine", date = day(60), status = "completed"),
            it("i3", RecordKind.IMMUNIZATION, "Tdap", date = "2022-05-12", status = "completed"),
            it("p1", RecordKind.PROCEDURE, "Pelvic ultrasound", date = day(72), status = "completed"),
            it("p2", RecordKind.PROCEDURE, "Wisdom tooth removal", date = "2012-07-03", status = "completed"),
        )
    }

    private val both = listOf(RecordProvider.EPIC, RecordProvider.ATHENA)
    private val withRecord = RecordsState(HealthRecords(both, connections = listOf(mgb)), items, loading = false, myMeds = setOf("Levothyroxine"))

    private fun render(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                AppFrame(selected = tabOf("records"), snackbar = remember { SnackbarHostState() }, onTab = {}, content = content)
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
    private fun hides(text: String) = assertEquals("Hides $text", 0, compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size)

    // ---- Not set up on the server

    @Test
    fun notSetUpForHer() {
        render { RecordsScreen(RecordsState(HealthRecords(), loading = false), RecordsActions(), now, zone) }
        shows("Medical records")
        shows("Your server's admin hasn't set this up yet.")
        hides("Open Admin")
        hides("Your hospital or health system")
        save("records_notset_light")
    }

    @Test
    fun notSetUpForAnAdmin() {
        var opened = 0
        render(dark = true) { RecordsScreen(RecordsState(HealthRecords(), loading = false, admin = true), RecordsActions(openAdmin = { opened++ }), now, zone) }
        shows("Set up medical records in Admin first")
        compose.onNodeWithText("Open Admin").performClick()
        assertEquals(1, opened)
        save("records_notset_admin_dark")
    }

    // ---- Connecting

    private val found = listOf(
        RecordOrganization("Mass General Brigham", "https://epicproxy.example/api/FHIR/R4"),
        RecordOrganization("Massachusetts Eye and Ear", "https://fhir.meei.example/R4"),
        RecordOrganization("A practice on athenahealth", "https://api.platform.athenahealth.com/fhir/r4", RecordProvider.ATHENA),
    )

    private fun connecting(dark: Boolean, name: String) {
        val searched = mutableListOf<String>()
        val connects = mutableListOf<RecordConnect>()
        render(dark) {
            RecordsScreen(RecordsState(HealthRecords(both), loading = false, results = found), RecordsActions(search = { searched += it }, connect = { connects += it }), now, zone)
        }
        shows("Connect your patient portal")
        shows("Search by its name, like Mass General Brigham.")
        shows("You'll sign in on your portal's own page. Your record comes straight to this server; nothing else sees it.")
        compose.onNode(hasSetTextAction()).performTextInput("mass")
        assertEquals("mass", searched.last())
        shows("Massachusetts Eye and Ear")
        shows("athenahealth")
        save(name)
        compose.onNodeWithText("Mass General Brigham").performClick()
        assertEquals(RecordConnect("Mass General Brigham", "https://epicproxy.example/api/FHIR/R4", RecordProvider.EPIC, app = true), connects.single())
    }

    @Test
    fun connectLight() = connecting(false, "records_connect_light")

    @Test
    fun connectDark() = connecting(true, "records_connect_dark")

    // ---- A whole record

    private fun record(dark: Boolean, name: String) {
        render(dark) { RecordsScreen(withRecord, RecordsActions(), now, zone) }
        shows("Mass General Brigham")
        shows("Updated 2 h ago · Updates itself daily")
        shows("10 lab results · 3 vital signs · 4 medications · 3 conditions · 2 allergies · 3 immunizations · 2 procedures")
        shows("Update now")
        hides("Sign in again")
        shows("Connect another")
        // Flags in words.
        shows("High")
        shows("Low")
        shows("Outside range")
        shows("Show all 10")
        save(name)
    }

    @Test
    @Config(qualifiers = "w411dp-h4300dp-xxhdpi")
    fun recordLight() = record(false, "records_record_light")

    @Test
    @Config(qualifiers = "w411dp-h4300dp-xxhdpi")
    fun recordDark() = record(true, "records_record_dark")

    @Test
    fun topOfTheRecord() {
        render { RecordsScreen(withRecord, RecordsActions(), now, zone) }
        save("records_top_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h4300dp-xxhdpi")
    fun medicationsCanBeAdded() {
        val added = mutableListOf<Medication>()
        render { RecordsScreen(withRecord, RecordsActions(addMedication = { added += it }), now, zone) }
        // Levothyroxine is already hers; the others can be added.
        shows("In your medications")
        assertEquals(2, compose.onAllNodesWithText("Add to my medications").fetchSemanticsNodes().size)
        compose.onAllNodesWithText("Add to my medications")[0].performScrollTo().performClick()
        assertEquals("Ferrous sulfate", added.single().name)
    }

    @Test
    fun medicationsOffMeansNoAdding() {
        render { RecordsScreen(withRecord.copy(medsOn = false), RecordsActions(), now, zone) }
        hides("Add to my medications")
    }

    // ---- A connection with a problem

    private fun problem(dark: Boolean, name: String) {
        val signIns = mutableListOf<RecordConnect>()
        render(dark) {
            RecordsScreen(withRecord.copy(records = HealthRecords(both, connections = listOf(wdh, mgb))), RecordsActions(connect = { signIns += it }), now, zone)
        }
        shows("Wentworth-Douglass Hospital")
        shows("Updated on 16 Oct · Sign in again to update")
        shows("Your sign-in there has run out.")
        shows("2 lab results · 1 immunization")
        save(name)
        compose.onNodeWithText("Sign in again").performClick()
        assertEquals("Wentworth-Douglass Hospital", signIns.single().organization)
        assertTrue(signIns.single().app)
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun problemLight() = problem(false, "records_problem_light")

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun problemDark() = problem(true, "records_problem_dark")

    // ---- Disconnected, with what it brought kept

    private fun disconnected(dark: Boolean, name: String) {
        val signIns = mutableListOf<RecordConnect>()
        val removed = mutableListOf<String>()
        val gone = wdh.copy(problem = "Disconnected: what it brought in is kept here, as it was.")
        render(dark) {
            RecordsScreen(
                withRecord.copy(records = HealthRecords(both, connections = listOf(mgb, gone))),
                RecordsActions(connect = { signIns += it }, remove = { removed += it.id }), now, zone,
            )
        }
        shows("Disconnected: what it brought in is kept here")
        shows("Updated on 16 Oct")
        hides("Updated on 16 Oct · Sign in again to update")
        shows("Connect again")
        shows("Remove")
        save(name)
        compose.onNodeWithText("Connect again").performClick()
        assertEquals("Wentworth-Douglass Hospital", signIns.single().organization)
    }

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun disconnectedLight() = disconnected(false, "records_disconnected_light")

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun disconnectedDark() = disconnected(true, "records_disconnected_dark")

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun youHasTheRow() {
        val opened = mutableListOf<String>()
        val line = com.health.app.ui.records.RecordsLogic.youLine(HealthRecords(both, connections = listOf(mgb)), now, zone)
        render { com.health.app.ui.you.YouScreen(com.health.app.ui.you.YouState(records = line), com.health.app.ui.you.YouActions(open = { opened += it }), now, zone) }
        shows("Mass General Brigham · updated 2 h ago")
        save("records_you_light")
        compose.onNodeWithText("Medical records").performScrollTo().performClick()
        assertEquals(listOf("records"), opened)
    }

    @Test
    fun pausedSaysSoAndLinksWhereDataGoes() {
        val opened = mutableListOf<String>()
        render { RecordsScreen(withRecord.copy(records = HealthRecords(both, paused = true, connections = listOf(mgb))), RecordsActions(open = { opened += it }), now, zone) }
        shows("Paused in Where your data goes")
        hides("Connect another")
        save("records_paused_light")
        compose.onNodeWithText("Open").performClick()
        assertEquals(listOf("settings/privacy"), opened)
    }
}
