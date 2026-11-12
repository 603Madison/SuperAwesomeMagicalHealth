@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.health.api.EnergyProfile
import com.health.api.Goals
import com.health.api.Module
import com.health.api.Profile
import com.health.api.Units
import com.health.app.BuildConfig
import com.health.app.data.Palette
import com.health.app.data.Settings
import com.health.app.data.ThemeMode
import com.health.app.ui.Format
import com.health.app.ui.components.Hint
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.Loading
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.SectionCard
import com.health.app.ui.components.SegmentedChoice
import com.health.app.ui.components.ValueRow
import com.health.app.ui.components.appContainer
import com.health.app.ui.log.NumberField
import com.health.app.ui.modules.ModuleInfo
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.roundToInt

class SettingsActions(
    val saveProfile: (Profile) -> Unit = {},
    val updateSettings: ((Settings) -> Settings) -> Unit = {},
    val signOut: () -> Unit = {},
)

@Composable
fun SettingsRoute() {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val settings by c.settings.collectAsState()
    val profile by c.profile.collectAsState()
    SettingsScreen(
        settings, profile,
        SettingsActions(
            saveProfile = { p -> scope.launch { runCatching { c.saveProfile(p) }.onFailure { c.tell(it.message ?: "Couldn't save that") } } },
            updateSettings = { t -> scope.launch { c.prefs.update(t) } },
            signOut = { scope.launch { c.signOut() } },
        ),
    )
}

/** Which dialog is open: a field of the profile or goals. */
private enum class Edit { BIRTH_YEAR, HEIGHT, ENERGY, STEPS, ACTIVE, SLEEP, CALORIES, PROTEIN, WATER, WORKOUTS, TARGET_WEIGHT, WEEKLY }

@Composable
fun SettingsScreen(settings: Settings, profile: Profile?, actions: SettingsActions) {
    var editing by remember { mutableStateOf<Edit?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val units = profile?.units ?: settings.units
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Settings")
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (profile == null) {
                Loading()
            } else {
                val g = profile.goals
                SectionCard("You") {
                    ValueRow("Born in", profile.birthYear?.toString() ?: "Not set", onClick = { editing = Edit.BIRTH_YEAR })
                    ValueRow("Height", profile.heightCm?.let { Format.height(it, units) } ?: "Not set", onClick = { editing = Edit.HEIGHT })
                    ValueRow(
                        "Calorie estimates use", profile.energyProfile.label,
                        subtitle = "The formula for the energy your body uses at rest",
                        onClick = { editing = Edit.ENERGY },
                    )
                    Text("Units", style = MaterialTheme.typography.bodyLarge)
                    SegmentedChoice(Units.entries, profile.units, { if (it == Units.METRIC) "Metric" else "Imperial" }, { actions.saveProfile(profile.copy(units = it)) })
                    val phone = ZoneId.systemDefault().id
                    ValueRow("Time zone", profile.timezone.ifBlank { phone }, subtitle = "When your days start and end")
                    if (profile.timezone.isNotBlank() && profile.timezone != phone) {
                        OutlinedButton(onClick = { actions.saveProfile(profile.copy(timezone = phone)) }) { Text("Use this phone's ($phone)") }
                    }
                }
                SectionCard("Features") {
                    Hint("Turn on what's useful to you. What's off is hidden, and nothing you've logged is lost.")
                    Module.entries.forEach { m ->
                        val on = m in profile.modules
                        LabeledSwitch(
                            m.label, on,
                            { v -> actions.saveProfile(profile.copy(modules = if (v) profile.modules + m else profile.modules - m)) },
                            subtitle = ModuleInfo.description(m),
                        )
                    }
                    val sensitive = Module.entries.filter { it.sensitive }.map { it.label }
                    Hint(
                        sensitive.dropLast(1).joinToString(", ") + " and ${sensitive.last()} are encrypted on your server, and an AI connector " +
                            "link only sees them if you allow it when making the link.",
                    )
                }
                SectionCard("Goals") {
                    ValueRow("Steps", Format.number(g.steps), onClick = { editing = Edit.STEPS })
                    ValueRow("Active calories", "${Format.number(g.activeCalories)} kcal", onClick = { editing = Edit.ACTIVE })
                    ValueRow("Sleep", Format.minutes(g.sleepMinutes), onClick = { editing = Edit.SLEEP })
                    ValueRow("Workouts", "${g.workoutsPerWeek} a week", onClick = { editing = Edit.WORKOUTS })
                    ValueRow("Calories to eat", g.calories?.let { "${Format.number(it)} kcal" } ?: "Automatic", subtitle = "Automatic follows what you burn and your weight goal", onClick = { editing = Edit.CALORIES })
                    ValueRow("Protein", g.protein?.let { "$it g" } ?: "Automatic", onClick = { editing = Edit.PROTEIN })
                    ValueRow("Water", Format.water(g.waterMl, units), onClick = { editing = Edit.WATER })
                    ValueRow("Goal weight", g.targetWeightKg?.let { Format.weight(it, units) } ?: "Not set", onClick = { editing = Edit.TARGET_WEIGHT })
                    ValueRow("Weight change", weeklyLabel(g.weeklyChangeKg, units), onClick = { editing = Edit.WEEKLY })
                }
            }
            SectionCard("Appearance") {
                Text("Theme", style = MaterialTheme.typography.bodyLarge)
                val wallpaperOn = settings.wallpaperColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Palette.entries.forEach { palette ->
                        FilterChip(
                            selected = palette == settings.palette,
                            enabled = !wallpaperOn,
                            onClick = { actions.updateSettings { it.copy(palette = palette) } },
                            label = { Text(palette.label) },
                            leadingIcon = {
                                Box(Modifier.size(20.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(palette.swatchA), Color(palette.swatchB)))))
                            },
                        )
                    }
                }
                if (wallpaperOn) Hint("Your wallpaper's colours are in use.")
                Text("Style", style = MaterialTheme.typography.bodyLarge)
                SegmentedChoice(ThemeMode.entries, settings.themeMode, { it.label }, { mode -> actions.updateSettings { it.copy(themeMode = mode) } })
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    LabeledSwitch(
                        "Wallpaper colours", settings.wallpaperColors, { v -> actions.updateSettings { it.copy(wallpaperColors = v) } },
                        subtitle = "Use your phone's wallpaper colours instead of the theme's.",
                    )
                }
                LabeledSwitch(
                    "Bold text", settings.boldText, { v -> actions.updateSettings { it.copy(boldText = v) } },
                    subtitle = "All text in Lexend Bold, the way your phone's Bold text setting draws it.",
                )
            }
            SectionCard("Sync") {
                LabeledSwitch(
                    "Sync in the background", settings.backgroundSync, { v -> actions.updateSettings { it.copy(backgroundSync = v) } },
                    subtitle = "About once an hour, your ring, Health Connect and Gadgetbridge send what's new to your server.",
                )
                LabeledSwitch(
                    "Only on Wi-Fi", settings.syncOnWifiOnly, { v -> actions.updateSettings { it.copy(syncOnWifiOnly = v) } },
                    enabled = settings.backgroundSync,
                    subtitle = "Background syncs wait for Wi-Fi. Opening the app still syncs.",
                )
            }
            SectionCard("Account") {
                Text(settings.user?.displayName ?: "", style = MaterialTheme.typography.titleMedium)
                Hint("${settings.user?.username ?: ""} on ${settings.serverName.ifBlank { settings.server }}")
                Hint(settings.server)
                Hint("Your password, signed-in devices and upload keys are on the server's web page.")
                OutlinedButton(onClick = { confirmSignOut = true }) { Text("Sign out") }
            }
            Text(
                "SuperAwesomeMagicalHealth ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("The app forgets this server and your paired ring here. What's on the server stays.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; actions.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }

    val p = profile ?: return
    val g = p.goals
    fun goals(change: (Goals) -> Goals) = actions.saveProfile(p.copy(goals = change(g)))
    val close = { editing = null }
    when (editing) {
        Edit.BIRTH_YEAR -> NumberDialog("Born in", p.birthYear?.toString(), null, valid = { it in 1900.0..2020.0 }, close) { v -> actions.saveProfile(p.copy(birthYear = v?.roundToInt())); close() }
        Edit.HEIGHT -> HeightDialog(p.heightCm, units, close) { cm -> actions.saveProfile(p.copy(heightCm = cm)); close() }
        Edit.ENERGY -> EnergyDialog(p.energyProfile, close) { e -> actions.saveProfile(p.copy(energyProfile = e)); close() }
        Edit.STEPS -> NumberDialog("Daily steps", g.steps.toString(), "steps", valid = { it in 500.0..60_000.0 }, close) { v -> goals { it.copy(steps = v!!.roundToInt()) }; close() }
        Edit.ACTIVE -> NumberDialog("Active calories", g.activeCalories.toString(), "kcal", valid = { it in 50.0..5_000.0 }, close) { v -> goals { it.copy(activeCalories = v!!.roundToInt()) }; close() }
        Edit.SLEEP -> NumberDialog("Sleep", Format.compact(g.sleepMinutes / 60.0), "hours", valid = { it in 4.0..12.0 }, close) { v -> goals { it.copy(sleepMinutes = (v!! * 60).roundToInt()) }; close() }
        Edit.WORKOUTS -> NumberDialog("Workouts a week", g.workoutsPerWeek.toString(), null, valid = { it in 0.0..21.0 }, close) { v -> goals { it.copy(workoutsPerWeek = v!!.roundToInt()) }; close() }
        Edit.CALORIES -> NumberDialog("Calories to eat", g.calories?.toString(), "kcal", valid = { it in 800.0..8_000.0 }, close, automatic = true) { v -> goals { it.copy(calories = v?.roundToInt()) }; close() }
        Edit.PROTEIN -> NumberDialog("Protein", g.protein?.toString(), "g", valid = { it in 10.0..500.0 }, close, automatic = true) { v -> goals { it.copy(protein = v?.roundToInt()) }; close() }
        Edit.WATER -> {
            val shown = if (units == Units.IMPERIAL) (g.waterMl / Format.ML_PER_FL_OZ).roundToInt().toString() else g.waterMl.toString()
            NumberDialog("Water", shown, if (units == Units.IMPERIAL) "fl oz" else "ml", valid = { it > 0 }, close) { v ->
                val ml = if (units == Units.IMPERIAL) v!! * Format.ML_PER_FL_OZ else v!!
                if (ml in 250.0..8_000.0) goals { it.copy(waterMl = ml.roundToInt()) }
                close()
            }
        }
        Edit.TARGET_WEIGHT -> NumberDialog(
            "Goal weight", g.targetWeightKg?.let { Format.compact(Format.weightValue(it, units)) }, Format.weightUnit(units),
            valid = { Format.weightToKg(it, units) in 25.0..350.0 }, close, automatic = true, automaticLabel = "No goal",
        ) { v -> goals { it.copy(targetWeightKg = v?.let { x -> Format.weightToKg(x, units) }) }; close() }
        Edit.WEEKLY -> WeeklyDialog(g.weeklyChangeKg, units, close) { kg -> goals { it.copy(weeklyChangeKg = kg) }; close() }
        null -> Unit
    }
}

private fun weeklyLabel(kg: Double, units: Units): String {
    val amount = "${Format.upTo2(Format.weightValue(kotlin.math.abs(kg), units))} ${Format.weightUnit(units)}"
    return when {
        kg == 0.0 -> "Stay where I am"
        kg < 0 -> "Lose $amount a week"
        else -> "Gain $amount a week"
    }
}

/** A number to type, or (with [automatic]) nothing, which the server works out. */
@Composable
private fun NumberDialog(
    title: String,
    initial: String?,
    suffix: String?,
    valid: (Double) -> Boolean,
    onDismiss: () -> Unit,
    automatic: Boolean = false,
    automaticLabel: String = "Automatic",
    onSave: (Double?) -> Unit,
) {
    var text by remember { mutableStateOf(initial.orEmpty()) }
    val value = Format.parse(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { NumberField(text, { text = it }, title, suffix = suffix) },
        confirmButton = { TextButton(enabled = value != null && valid(value), onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (automatic) TextButton(onClick = { onSave(null) }) { Text(automaticLabel) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun HeightDialog(cm: Double?, units: Units, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    val inches = cm?.let { it / Format.CM_PER_INCH }
    var a by remember { mutableStateOf(if (units == Units.IMPERIAL) inches?.let { (it / 12).toInt().toString() }.orEmpty() else cm?.roundToInt()?.toString().orEmpty()) }
    var b by remember { mutableStateOf(if (units == Units.IMPERIAL) inches?.let { (it % 12).roundToInt().toString() }.orEmpty() else "") }
    val result = if (units == Units.IMPERIAL) {
        a.trim().toIntOrNull()?.let { ft -> Format.heightToCm(ft, Format.parse(b) ?: 0.0) }
    } else Format.parse(a)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Height") },
        text = {
            if (units == Units.IMPERIAL) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(a, { a = it }, "Feet", Modifier.weight(1f), decimals = false)
                    NumberField(b, { b = it }, "Inches", Modifier.weight(1f))
                }
            } else {
                NumberField(a, { a = it }, "Height", suffix = "cm")
            }
        },
        confirmButton = { TextButton(enabled = result != null && result in 100.0..250.0, onClick = { onSave(result!!) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EnergyDialog(current: EnergyProfile, onDismiss: () -> Unit, onSave: (EnergyProfile) -> Unit) {
    var choice by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calorie estimates use") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Hint("Resting energy is estimated with the Mifflin–St Jeor formula, which has a female and a male version. Pick whichever fits your body best (hormones and body composition matter more than anything else here), or the average of the two.")
                EnergyProfile.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).selectable(choice == option, role = Role.RadioButton) { choice = option }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == option, onClick = null)
                        Text(option.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(choice) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WeeklyDialog(current: Double, units: Units, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    val options = listOf(-1.0, -0.75, -0.5, -0.25, 0.0, 0.25, 0.5)
    var choice by remember { mutableDoubleStateOf(options.minByOrNull { kotlin.math.abs(it - current) } ?: 0.0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weight change") },
        text = {
            Column {
                options.forEach { kg ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { choice = kg }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == kg, onClick = { choice = kg })
                        Text(weeklyLabel(kg, units), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Hint("Automatic calorie targets follow this. Slow and steady is easier to keep up.")
            }
        },
        confirmButton = { TextButton(onClick = { onSave(choice) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
