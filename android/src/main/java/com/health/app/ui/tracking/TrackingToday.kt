@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.health.app.ui.tracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.api.BloodPressure
import com.health.api.DaySummary
import com.health.api.Drink
import com.health.api.DrinkKind
import com.health.api.GlucoseReading
import com.health.api.GlucoseUnit
import com.health.api.HabitStatus
import com.health.api.HabitTick
import com.health.api.Module
import com.health.api.Resilience
import com.health.api.StressDay
import com.health.api.StressState
import com.health.api.SymptomEntry
import com.health.api.SymptomsDay
import com.health.api.Units
import com.health.api.VitalsToday
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.addBloodPressure
import com.health.app.net.addDrink
import com.health.app.net.addGlucose
import com.health.app.net.addSymptom
import com.health.app.net.deleteDrink
import com.health.app.net.tickHabit
import com.health.app.net.updateDrink
import com.health.app.ui.Format
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LegendItem
import com.health.app.ui.components.Pill
import com.health.app.ui.components.ProgressBar
import com.health.app.ui.components.Stat
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.time.ZoneId

/** What Today's tracking cards can do, as callbacks (so they can be drawn with sample data). */
class TrackingTodayActions(
    /** The person's glucose unit (from their targets), for showing and logging readings. */
    val glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL_L,
    val logBloodPressure: (BloodPressure) -> Unit = {},
    val logGlucose: (GlucoseReading) -> Unit = {},
    val tickHabit: (HabitStatus, Int) -> Unit = { _, _ -> },
    val logSymptom: (SymptomEntry) -> Unit = {},
    val quickDrink: (DrinkKind) -> Unit = {},
    val saveDrink: (Drink) -> Unit = {},
    val deleteDrink: (String) -> Unit = {},
) {
    companion object {
        /** The real thing: each call goes to the server, then Today (and the rest) reload. */
        fun of(c: Container, glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL_L): TrackingTodayActions {
            fun act(message: String?, block: suspend () -> Unit) {
                c.scope.launch {
                    attempt { block() }
                        .onSuccess { message?.let(c::tell); c.dataChanged() }
                        .onFailure { c.tell(it.message ?: "That didn't work") }
                }
            }
            return TrackingTodayActions(
                glucoseUnit = glucoseUnit,
                logBloodPressure ={ bp -> act("Blood pressure saved") { c.ui.addBloodPressure(bp) } },
                logGlucose = { g -> act("Glucose saved") { c.ui.addGlucose(g) } },
                tickHabit = { s, n -> act(if (n >= s.habit.perDay && !s.done) "Nice: ${s.habit.name} done" else null) { c.ui.tickHabit(s.habit.id!!, HabitTick(s.date, n.coerceAtLeast(0))) } },
                logSymptom = { e -> act("${e.symptom.replaceFirstChar { it.uppercase() }} logged") { c.ui.addSymptom(e) } },
                quickDrink = { kind -> act("${kind.label} logged") { c.ui.addDrink(quickDrink(kind)) } },
                saveDrink = { d -> act("Saved") { if (d.id == null) c.ui.addDrink(d) else c.ui.updateDrink(d) } },
                deleteDrink = { id -> act(null) { c.ui.deleteDrink(id) } },
            )
        }
    }
}

/**
 * Today's cards for stress and resilience, blood pressure and glucose, habits, symptoms and
 * drinks: each only when its feature is on and (for stress) when there's something to show.
 */
@Composable
fun TrackingTodayCards(
    summary: DaySummary,
    modules: Set<Module>,
    units: Units,
    zone: ZoneId,
    actions: TrackingTodayActions,
    open: (String) -> Unit,
) {
    if (Module.HABITS in modules) HabitsTodayCard(summary.modules.habits.orEmpty(), actions.tickHabit) { open("habits") }
    if (Module.VITALS in modules) VitalsTodayCard(summary.modules.vitals, actions.glucoseUnit, actions) { open("vitals") }
    if (Module.SYMPTOMS in modules) SymptomsTodayCard(summary.modules.symptoms, actions.logSymptom) { open("symptoms") }
    if (Module.ACTIVITY in modules && (summary.stressDay != null || summary.resilience != null)) StressCard(summary.stressDay, summary.resilience)
    if (Module.NUTRITION in modules) DrinksCard(summary.drinks, units, zone, actions.quickDrink, actions.saveDrink, actions.deleteDrink, compact = true)
}

@Composable
private fun OpenButton(label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, label) }
}

@Composable
fun StressCard(day: StressDay?, resilience: Resilience?) {
    InfoCard("Stress & recovery", icon = Icons.Rounded.SelfImprovement, trailing = { day?.let { Pill(it.summary, summaryColor(it.summary)) } }) {
        if (day != null) {
            Row(Modifier.fillMaxWidth()) {
                Stat(Format.minutes(day.stressedMinutes), "stressed", Modifier.weight(1f), color = stressColor(StressState.STRESSED))
                Stat(Format.minutes(day.restoredMinutes), "restored", Modifier.weight(1f), color = stressColor(StressState.RESTORED))
                Stat(Format.minutes(day.relaxedMinutes), "relaxed", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            StressStrip(day.spans)
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StressState.entries.forEach { LegendItem(stressColor(it), it.label) }
            }
        }
        resilience?.let { r ->
            if (day != null) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Resilience", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(r.level.label, style = MaterialTheme.typography.titleLarge, color = resilienceColor(r.level))
                }
                val trend = when (r.trend) {
                    "up" -> "Building on the two weeks before"
                    "down" -> "Lower than the two weeks before"
                    else -> "Steady"
                }
                Text(trend, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            r.contributors.forEach { c ->
                Spacer(Modifier.height(8.dp))
                Row {
                    Text(c.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(c.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ProgressBar(c.score / 100f, color = resilienceColor(r.level), height = 6.dp)
            }
            Hint("How well rest and sleep have balanced stress over the last two weeks.", Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun summaryColor(summary: String) = when (summary.lowercase()) {
    "restored" -> AppTheme.status.good
    "stressful" -> AppTheme.status.warn
    else -> MaterialTheme.colorScheme.primary
}

@Composable
fun VitalsTodayCard(vitals: VitalsToday?, unit: GlucoseUnit, actions: TrackingTodayActions, onOpen: () -> Unit) {
    var dialog by remember { mutableStateOf<String?>(null) }
    InfoCard("Blood pressure & glucose", icon = Icons.Rounded.MonitorHeart, iconTint = AppTheme.data.heart, onClick = onOpen, trailing = { OpenButton("Open blood pressure and glucose", onOpen) }) {
        val bp = vitals?.bloodPressure
        val g = vitals?.glucose
        if (bp != null || g != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                bp?.let { Stat("${it.systolic}/${it.diastolic}", "latest, mmHg", Modifier.weight(1f)) }
                g?.let { Stat(Glucose.number(it.mmolL, unit), "glucose, ${unit.label}", Modifier.weight(1f)) }
                vitals.timeInRange?.let { Stat("$it%", "in range", Modifier.weight(1f), color = AppTheme.status.good) }
            }
            vitals.category?.let { Pill(it.label, bpColor(it), Modifier.padding(top = 6.dp)) }
        } else {
            Hint("Nothing yet today.")
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { dialog = "bp" }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Icon(Icons.Rounded.MonitorHeart, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Blood pressure")
            }
            FilledTonalButton(onClick = { dialog = "glucose" }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Icon(Icons.Rounded.Bloodtype, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Glucose")
            }
        }
    }
    when (dialog) {
        "bp" -> BloodPressureDialog(onDismiss = { dialog = null }) { actions.logBloodPressure(it); dialog = null }
        "glucose" -> GlucoseDialog(unit, onDismiss = { dialog = null }) { actions.logGlucose(it); dialog = null }
    }
}

@Composable
fun HabitsTodayCard(habits: List<HabitStatus>, onTick: (HabitStatus, Int) -> Unit, onOpen: () -> Unit) {
    InfoCard("Habits", icon = Icons.Rounded.Checklist, onClick = onOpen, trailing = {
        if (habits.isNotEmpty()) Text("${habits.count { it.done }} of ${habits.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OpenButton("Open habits", onOpen)
    }) {
        if (habits.isEmpty()) {
            Hint("Add a small habit, and tick it here.")
            return@InfoCard
        }
        habits.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(HabitIcons.of(s.habit.icon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.habit.name, style = MaterialTheme.typography.bodyLarge)
                    val streak = if (s.streakWeeks > 0) " · ${s.streakWeeks} ${if (s.streakWeeks == 1) "week" else "weeks"} in a row" else ""
                    Text("${s.weekDone} this week$streak", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HabitTickControl(s) { n -> onTick(s, n) }
            }
        }
    }
}

@Composable
fun SymptomsTodayCard(day: SymptomsDay?, onLog: (SymptomEntry) -> Unit, onOpen: () -> Unit) {
    var logging by remember { mutableStateOf<SymptomEntry?>(null) }
    InfoCard("Symptoms", icon = Icons.Rounded.Healing, onClick = onOpen, trailing = { OpenButton("Open symptoms", onOpen) }) {
        val entries = day?.entries.orEmpty()
        val migraines = day?.migraines.orEmpty()
        if (entries.isEmpty() && migraines.isEmpty()) {
            Hint("Feeling something? One tap notes it, and you can say how bad.")
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (entries.map { it.symptom } + migraines.map { "migraine" }).distinct().joinToString(", ").replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                )
                day?.worst?.let { Pill("Worst $it/10", severityColor(it)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("headache", "fatigue", "back pain", "nausea", "stomach ache").forEach { s ->
                SuggestionChip(onClick = { logging = SymptomEntry(System.currentTimeMillis(), s, 4) }, label = { Text(s) })
            }
        }
    }
    logging?.let { entry ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { logging = null }) {
            SymptomForm(entry, onSave = { onLog(it); logging = null }, onDelete = null)
        }
    }
}
