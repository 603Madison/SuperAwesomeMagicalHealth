package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.health.api.CheckTag
import com.health.api.EnergyCheck
import com.health.api.EnergyFactor
import com.health.api.EnergyFocus
import com.health.api.EnergyFocusSettings
import com.health.api.FactorKind
import com.health.api.Medication
import com.health.api.TimeBlock
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.Backdrop
import com.health.app.ui.modules.neuro.EnergyCheckSheetContent
import com.health.app.ui.modules.neuro.EnergyFocusActions
import com.health.app.ui.modules.neuro.EnergyFocusSection
import com.health.app.ui.modules.neuro.SocialBatteryCard
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
 * Autism & ADHD's energy and focus, drawn to PNGs: the section with patterns and before there's
 * enough, the quick check-in sheet, and the social battery, in light, dark, other palettes and Bold.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h2400dp-xxhdpi")
class EnergyFocusScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private companion object {
        /** A phone's height, for the sheet. */
        const val SHEET = "w411dp-h891dp-xxhdpi"
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 11, 17)
    private fun at(h: Int, m: Int = 0): Long = today.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    // ---- The data: a few weeks of check-ins, the server's words ----

    private val about = "Quick check-ins through the day show how your energy and focus actually move, rather than how a day is remembered: " +
        "it's how researchers measure it too (ecological momentary assessment). Your patterns compare your own check-ins with each other, " +
        "and with your sleep, doses, meals and movement. They're patterns in your data, not proof of what causes what, and they get more " +
        "reliable the more you check in. Putting the hardest things into your best hours is part of the therapies for adult ADHD with the " +
        "best evidence. Sleep and ADHD are closely linked both ways, and exercise's short-term boost to attention has mostly been studied " +
        "in children and young people. Take patterns about your medication to your prescriber rather than changing a dose yourself."

    private val sources = listOf(
        "Shiffman, Stone & Hufford (2008). Ecological momentary assessment. Annual Review of Clinical Psychology, 4, 1–32.",
        "Safren et al. (2010). Cognitive behavioral therapy vs relaxation with educational support for medication-treated adults with ADHD and persistent symptoms. JAMA, 304(8), 875–880.",
        "Solanto et al. (2010). Efficacy of meta-cognitive therapy for adult ADHD. American Journal of Psychiatry, 167(8), 958–968.",
        "Hvolby (2015). Associations of sleep disturbance with ADHD: implications for treatment. ADHD Attention Deficit and Hyperactivity Disorders, 7(1), 1–18.",
        "Cerrillo-Urbina et al. (2015). The effects of physical exercise in children with ADHD: a systematic review and meta-analysis of randomized controlled trials. Child: Care, Health and Development, 41(6), 779–788.",
    )

    private val todayChecks = listOf(
        EnergyCheck(at(7, 50), energy = 2, focus = 1, tags = setOf(CheckTag.UNWELL), id = "c1"),
        EnergyCheck(at(10, 5), energy = 4, focus = 4, id = "c2"),
        EnergyCheck(at(11, 40), energy = 4, focus = 5, tags = setOf(CheckTag.HYPERFOCUS), id = "c3"),
        EnergyCheck(at(14, 20), energy = 3, focus = 2, tags = setOf(CheckTag.STRESSED), note = "Too many emails at once", id = "c4"),
    )

    private val sleep = EnergyFactor(
        FactorKind.SLEEP, "Sleep",
        "After 7 hours' sleep or more, your energy averaged 3.6, against 2.4 after less than 6 (9 and 5 days).",
        "energy", 3.6, 2.4, 9, 5,
    )
    private val stress = EnergyFactor(
        FactorKind.STRESS, "Stress",
        "When you felt stressed, your focus averaged 2.3, against 3.4 otherwise (8 and 38 check-ins).",
        "focus", 2.3, 3.4, 8, 38,
    )

    private val patterns = EnergyFocus(
        settings = EnergyFocusSettings(reminders = listOf("10:00", "15:00")),
        medication = "Ritalin",
        today = todayChecks,
        dosesToday = listOf(at(8, 30)),
        checks = 46, days = 19, enough = true,
        byHour = listOf(
            TimeBlock(6, 9, 2.4, 2.1, 6), TimeBlock(9, 12, 3.6, 4.1, 12), TimeBlock(12, 15, 3.1, 3.2, 10),
            TimeBlock(15, 18, 2.5, 2.6, 9), TimeBlock(18, 21, 2.9, 2.4, 7), TimeBlock(21, 24, 2.2, 3.4, 2),
        ),
        sinceDose = listOf(TimeBlock(1, 3, 3.5, 4.2, 11), TimeBlock(3, 5, 3.3, 3.8, 9), TimeBlock(5, 7, 2.8, 2.9, 8), TimeBlock(7, 9, 2.6, 2.3, 6)),
        bestFocus = TimeBlock(9, 12, 3.6, 4.1, 12),
        factors = listOf(sleep, stress),
        summary = listOf(
            "Your focus is usually best 9 AM – noon: a good time for whatever needs it most.",
            "Your energy tends to dip 3 – 6 PM.",
            "Your focus is usually highest 1–3 hours after your Ritalin, and lower again from about 5 hours.",
            sleep.sentence, stress.sentence,
        ),
        about = about, sources = sources,
    )

    private val starting = EnergyFocus(
        medication = "Ritalin",
        today = listOf(EnergyCheck(at(9, 15), energy = 3, focus = 4, id = "c1")),
        dosesToday = listOf(at(8, 30)),
        checks = 7, days = 3, enough = false,
        needed = "5 more check-ins over 2 more days and your patterns start to show.",
        about = about, sources = sources,
    )

    private val meds = listOf(Medication("Ritalin", "10 mg", id = "r"), Medication("Vitamin D", "1000 IU", id = "d"))

    // ---- Drawing ----

    private fun render(dark: Boolean = false, palette: Palette = Palette.AURORA, bold: Boolean = false, readOnly: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = palette, bold = bold) {
                CompositionLocalProvider(LocalReadOnly provides readOnly) { content() }
            }
        }
    }

    @Composable
    private fun Page(ef: EnergyFocus, actions: EnergyFocusActions = EnergyFocusActions(), howOpen: Boolean = false) {
        AppFrame(selected = "care", snackbar = remember { SnackbarHostState() }, onTab = {}) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EnergyFocusSection(ef, null, meds, today, zone, LocalReadOnly.current, meds = true, actions = actions, howOpen = howOpen)
                SocialBatteryCard(4, LocalReadOnly.current) {}
            }
        }
    }

    /** The quick check-in as its bottom sheet draws it: over the app, behind the sheet's scrim. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Sheet(onSave: (EnergyCheck) -> Unit = {}) {
        Backdrop {
            Column(Modifier.fillMaxSize().background(BottomSheetDefaults.ScrimColor), verticalArrangement = Arrangement.Bottom) {
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BottomSheetDefaults.DragHandle()
                        EnergyCheckSheetContent(onSave)
                    }
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

    /** [text] is somewhere on screen (once or more). */
    private fun shows(text: String) = assertTrue("$text shows", compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty())

    // ---- With patterns ----

    @Test
    fun patternsLight() {
        render { Page(patterns) }
        listOf(
            "Check in", "Running on empty", "Plenty", "All over the place", "Locked in", "4 check-ins",
            "Your patterns", "By time of day", "Since your Ritalin", "Your energy tends to dip 3 – 6 PM.", "Sleep", "Stress",
            "Patterns in your own check-ins, not proof of cause.", "From 46 check-ins over 19 days.", "How this works",
            "Your ADHD medication", "Ritalin, found by its name", "Reminders to check in", "Social battery",
        ).forEach(::shows)
        // Each factor's sentence once (under its title), not again in the summary.
        assertEquals(1, compose.onAllNodesWithText(sleep.sentence).fetchSemanticsNodes().size)
        // The strip says what's on it.
        compose.onNode(hasContentDescription("Today's check-ins", substring = true)).assertExists()
        compose.onNode(hasContentDescription("Ritalin at 8:30", substring = true)).assertExists()
        save("energy_focus_patterns_light")
    }

    @Test
    fun patternsDark() {
        render(dark = true) { Page(patterns) }
        save("energy_focus_patterns_dark")
    }

    @Test
    fun patternsEmberLight() {
        render(palette = Palette.EMBER) { Page(patterns) }
        save("energy_focus_patterns_ember")
    }

    @Test
    fun patternsOceanDarkBold() {
        render(dark = true, palette = Palette.OCEAN, bold = true) { Page(patterns, howOpen = true) }
        listOf("The research", "Shiffman, Stone & Hufford (2008)").forEach(::shows)
        save("energy_focus_patterns_ocean_dark_bold")
    }

    @Test
    fun howThisWorksOpensAndCloses() {
        render { Page(patterns) }
        assertEquals(0, compose.onAllNodesWithText("The research").fetchSemanticsNodes().size)
        compose.onNodeWithText("How this works").performClick()
        shows("The research")
        shows("ecological momentary assessment")
        save("energy_focus_how_light")
    }

    @Test
    fun choosingTheMedicationOrNoneOfThese() {
        val saved = mutableListOf<EnergyFocusSettings>()
        render { Page(patterns, EnergyFocusActions(saveSettings = { saved += it })) }
        compose.onNodeWithText("Change").performClick()
        // Found by its name: that one's picked out, with "None of these" there too.
        compose.onNodeWithContentDescription("Ritalin, 10 mg").assertIsSelected()
        compose.onNodeWithContentDescription("None of these").performClick()
        assertEquals(EnergyFocusSettings(medicationId = null, noMedication = true, reminders = listOf("10:00", "15:00")), saved.last())
        compose.onNodeWithText("Change").performClick()
        compose.onNodeWithContentDescription("Vitamin D, 1000 IU").performClick()
        assertEquals(EnergyFocusSettings(medicationId = "d", noMedication = false, reminders = listOf("10:00", "15:00")), saved.last())
    }

    @Test
    fun noneOfTheseStaysChosen() {
        val none = patterns.copy(settings = EnergyFocusSettings(noMedication = true), medication = null, sinceDose = emptyList(), dosesToday = emptyList())
        render { Page(none) }
        shows("None of these")
        compose.onNodeWithText("Choose").performClick()
        compose.onNodeWithContentDescription("None of these").assertIsSelected()
        compose.onNodeWithContentDescription("Ritalin, 10 mg").assertIsNotSelected()
    }

    // ---- Before there's enough ----

    @Test
    fun startingLight() {
        render { Page(starting) }
        listOf("5 more check-ins over 2 more days and your patterns start to show.", "7 check-ins over 3 days so far", "Patterns in your own check-ins, not proof of cause.").forEach(::shows)
        assertEquals(0, compose.onAllNodesWithText("By time of day").fetchSemanticsNodes().size)
        save("energy_focus_starting_light")
    }

    @Test
    fun startingDark() {
        render(dark = true) { Page(starting) }
        save("energy_focus_starting_dark")
    }

    @Test
    fun startingVioletLight() {
        render(palette = Palette.VIOLET) { Page(starting.copy(today = emptyList(), dosesToday = emptyList(), medication = null, checks = 0, days = 0, needed = "12 more check-ins over 5 more days and your patterns start to show.")) }
        listOf("Nothing yet today", "None chosen", "Check in a few times a day, whenever you think of it.").forEach(::shows)
        save("energy_focus_starting_violet")
    }

    @Test
    fun viewingAShareHasNoCheckInOrSettings() {
        render(readOnly = true) { Page(patterns) }
        assertEquals(0, compose.onAllNodesWithText("Save").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Your ADHD medication").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasContentDescription("Remove the", substring = true)).fetchSemanticsNodes().size)
        shows("Your patterns")
        save("energy_focus_viewing")
    }

    // ---- Checking in ----

    @Test
    fun checkingInIsTwoTapsAndSave() {
        val saved = mutableListOf<EnergyCheck>()
        render { Page(starting, EnergyFocusActions(checkIn = { saved += it })) }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Energy 4 of 5").performClick()
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithContentDescription("Focus 1 of 5, all over the place").performClick()
        compose.onNodeWithText("Stressed").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(1, saved.size)
        assertEquals(4, saved[0].energy)
        assertEquals(1, saved[0].focus)
        assertEquals(setOf(CheckTag.STRESSED), saved[0].tags)
        // It starts afresh.
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = SHEET)
    fun quickSheetLight() {
        val saved = mutableListOf<EnergyCheck>()
        render { Sheet { saved += it } }
        listOf("Energy and focus", "How are you right now? Two taps, for your patterns.", "Running on empty", "Locked in", "In a hyperfocus").forEach(::shows)
        // No note in the quick sheet: two taps and Save.
        assertEquals(0, compose.onAllNodesWithText("Add a note").fetchSemanticsNodes().size)
        save("energy_focus_sheet_light")
        compose.onNodeWithContentDescription("Focus 5 of 5, locked in").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf(5), saved.map { it.focus })
        assertEquals(listOf<Int?>(null), saved.map { it.energy })
    }

    @Test
    @Config(qualifiers = SHEET)
    fun quickSheetDark() {
        render(dark = true) { Sheet() }
        compose.onNodeWithContentDescription("Energy 2 of 5").performClick()
        compose.onNodeWithContentDescription("Focus 3 of 5").performClick()
        save("energy_focus_sheet_dark")
    }

    @Test
    @Config(qualifiers = SHEET)
    fun quickSheetEmberDarkBold() {
        render(dark = true, palette = Palette.EMBER, bold = true) { Sheet() }
        compose.onNodeWithContentDescription("Energy 5 of 5, plenty").performClick()
        save("energy_focus_sheet_ember_dark_bold")
    }
}
