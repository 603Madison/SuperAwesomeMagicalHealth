@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.api.DrinksDay
import com.health.api.GlucoseUnit
import com.health.api.HabitStatus
import com.health.api.Resilience
import com.health.api.StressDay
import com.health.api.StressState
import com.health.api.SupplyStatus
import com.health.api.SymptomsDay
import com.health.api.Units
import com.health.api.VitalsToday
import com.health.app.ui.Format
import com.health.app.ui.components.CardAction
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LegendItem
import com.health.app.ui.components.Pill
import com.health.app.ui.components.ProgressBar
import com.health.app.ui.components.Stat
import com.health.app.ui.modules.continence.ContinenceText
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.tracking.Glucose
import com.health.app.ui.tracking.HabitIcons
import com.health.app.ui.tracking.HabitTickControl
import com.health.app.ui.tracking.StressStrip
import com.health.app.ui.tracking.bpColor
import com.health.app.ui.tracking.resilienceColor
import com.health.app.ui.tracking.severityColor
import com.health.app.ui.tracking.stressColor
import kotlin.math.roundToInt

/*
 * Today's cards for habits, blood pressure and glucose, symptoms, stress, drinks, supplies and
 * water. Each has the same title row (InfoCard): a chevron to the feature's page and at most one
 * action; with nothing to show yet, the card is just that row.
 */

@Composable
fun HabitsTodayCard(habits: List<HabitStatus>, readOnly: Boolean, onTick: (HabitStatus, Int) -> Unit, onOpen: () -> Unit) {
    InfoCard(
        "Habits", icon = Icons.Rounded.Checklist, onClick = onOpen, onOpen = onOpen,
        status = if (habits.isEmpty()) "None yet" else "${habits.count { it.done }} of ${habits.size}",
        content = if (habits.isEmpty()) null else {
            {
                habits.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(HabitIcons.of(s.habit.icon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(s.habit.name, style = MaterialTheme.typography.bodyLarge)
                            val streak = if (s.streakWeeks > 0) " · ${s.streakWeeks} ${if (s.streakWeeks == 1) "week" else "weeks"} in a row" else ""
                            Text("${s.weekDone} this week$streak", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!readOnly) HabitTickControl(s) { n -> onTick(s, n) }
                        else if (s.done) Icon(Icons.Rounded.Check, "Done", tint = AppTheme.status.good)
                    }
                }
            }
        },
    )
}

/** Blood pressure and glucose: the latest of each; "+" offers either. */
@Composable
fun VitalsTodayCard(vitals: VitalsToday?, unit: GlucoseUnit, readOnly: Boolean, onBloodPressure: () -> Unit, onGlucose: () -> Unit, onOpen: () -> Unit) {
    val bp = vitals?.bloodPressure
    val g = vitals?.glucose
    InfoCard(
        "Blood pressure & glucose", icon = Icons.Rounded.MonitorHeart, iconTint = AppTheme.data.heart, onClick = onOpen,
        onOpen = onOpen, openLabel = "blood pressure and glucose",
        action = if (readOnly) null else CardAction(Icons.Rounded.Add, "Log blood pressure or glucose", menu = listOf("Blood pressure" to onBloodPressure, "Glucose" to onGlucose)),
        content = if (bp == null && g == null) null else {
            {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    bp?.let { Stat("${it.systolic}/${it.diastolic}", "latest, mmHg", Modifier.weight(1f)) }
                    g?.let { Stat(Glucose.number(it.mmolL, unit), "glucose, ${unit.label}", Modifier.weight(1f)) }
                    vitals?.timeInRange?.let { Stat("$it%", "in range", Modifier.weight(1f), color = AppTheme.status.good) }
                }
                vitals?.category?.let { Pill(it.label, bpColor(it), Modifier.padding(top = 6.dp)) }
            }
        },
    )
}

@Composable
fun SymptomsTodayCard(day: SymptomsDay?, readOnly: Boolean, onLog: () -> Unit, onOpen: () -> Unit) {
    val entries = day?.entries.orEmpty()
    val migraines = day?.migraines.orEmpty()
    InfoCard(
        "Symptoms", icon = Icons.Rounded.Healing, onClick = onOpen, onOpen = onOpen,
        action = if (readOnly) null else CardAction(Icons.Rounded.Add, "Log a symptom", onClick = onLog),
        content = if (entries.isEmpty() && migraines.isEmpty()) null else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (entries.map { it.symptom } + migraines.map { "migraine" }).distinct().joinToString(", ").replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                    )
                    day?.worst?.let { Pill("Worst $it/10", severityColor(it)) }
                }
            }
        },
    )
}

@Composable
fun StressTodayCard(day: StressDay?, resilience: Resilience?, onOpen: () -> Unit) {
    InfoCard(
        "Stress & recovery", icon = Icons.Rounded.SelfImprovement, onClick = onOpen, onOpen = onOpen, openLabel = "trends",
        status = day?.summary, statusColor = stressSummaryColor(day?.summary),
    ) {
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
                Text(
                    when (r.trend) {
                        "up" -> "Building on the two weeks before"
                        "down" -> "Lower than the two weeks before"
                        else -> "Steady"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            r.contributors.forEach { c ->
                Spacer(Modifier.height(8.dp))
                Row {
                    Text(c.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(c.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ProgressBar(c.score / 100f, color = resilienceColor(r.level), height = 6.dp)
            }
        }
    }
}

@Composable
private fun stressSummaryColor(summary: String?) = when (summary?.lowercase()) {
    "restored" -> AppTheme.status.good
    "stressful" -> AppTheme.status.warn
    else -> MaterialTheme.colorScheme.primary
}

/**
 * Today's drinks: caffeine, what's likely still in you at bedtime, and alcohol. One-tap coffee and
 * water are quick actions, so the card's only action is "+" for any drink.
 */
@Composable
fun DrinksTodayCard(day: DrinksDay?, units: Units, water: Boolean, readOnly: Boolean, onAdd: () -> Unit, onOpen: () -> Unit) {
    val d = day ?: DrinksDay("")
    val any = d.drinks.isNotEmpty() || d.caffeineMg > 0 || d.alcoholG > 0
    InfoCard(
        "Drinks", icon = Icons.Rounded.LocalCafe, iconTint = AppTheme.data.carbs, onClick = onOpen, onOpen = onOpen,
        action = if (readOnly) null else CardAction(Icons.Rounded.Add, "Add a drink", onClick = onAdd),
        content = if (!any) null else {
            {
                Row(Modifier.fillMaxWidth()) {
                    Stat("${d.caffeineMg.roundToInt()}", "mg caffeine", Modifier.weight(1f), color = AppTheme.data.carbs)
                    Stat(d.caffeineAtBedtimeMg?.let { "${it.roundToInt()}" } ?: "—", "mg at bedtime", Modifier.weight(1f))
                    Stat(
                        Format.compact(d.standardDrinks), if (Format.compact(d.standardDrinks) == "1") "drink" else "drinks", Modifier.weight(1f),
                        color = if (d.standardDrinks > 0) AppTheme.data.protein else MaterialTheme.colorScheme.onSurface,
                    )
                }
                d.caffeineAtBedtimeMg?.takeIf { it >= 50 }?.let {
                    Hint("About ${it.roundToInt()} mg may still be with you at bedtime, which can make sleep lighter.", Modifier.padding(top = 6.dp))
                }
                if (water && d.fluidMl > 0) Hint("${Format.water(d.fluidMl, units)} of these count towards your water.", Modifier.padding(top = 4.dp))
            }
        },
    )
}

/** Water against the goal; "+" only when Water isn't among the quick actions on Today. */
@Composable
fun WaterTodayCard(ml: Int, goalMl: Int, units: Units, onAdd: (() -> Unit)?, onOpen: () -> Unit) {
    InfoCard(
        "Water", icon = Icons.Rounded.WaterDrop, iconTint = AppTheme.data.water, onClick = onOpen, onOpen = onOpen, openLabel = "food",
        status = "${Format.water(ml, units)} of ${Format.water(goalMl, units)}",
        action = onAdd?.let { CardAction(Icons.Rounded.Add, "Add ${Format.water(QUICK_WATER_ML, units)} of water", onClick = it) },
    ) {
        ProgressBar(if (goalMl > 0) ml.toFloat() / goalMl else 0f, color = AppTheme.data.water, height = 8.dp)
    }
}

/** Supplies running low (bladder and bowel, periods), each with what's left. */
@Composable
fun SuppliesTodayCard(low: List<SupplyStatus>, onOpen: () -> Unit) {
    InfoCard("Running low", icon = Icons.Rounded.Inventory2, iconTint = AppTheme.status.warn, onClick = onOpen, onOpen = onOpen, openLabel = "supplies") {
        low.take(4).forEach { s ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ContinenceText.supplyName(s.supply), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    "${s.supply.onHand} left" + (s.daysLeft?.let { " · about $it ${if (it == 1) "day" else "days"}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = AppTheme.status.warn,
                )
            }
        }
        if (low.size > 4) Hint("And ${low.size - 4} more.")
    }
}
