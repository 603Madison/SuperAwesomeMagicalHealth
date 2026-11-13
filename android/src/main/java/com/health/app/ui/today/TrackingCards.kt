package com.health.app.ui.today

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.runtime.Composable
import com.health.api.DrinksDay
import com.health.api.GlucoseUnit
import com.health.api.HabitStatus
import com.health.api.Resilience
import com.health.api.StressDay
import com.health.api.SymptomsDay
import com.health.api.Units
import com.health.api.VitalsToday
import com.health.app.ui.Format
import com.health.app.ui.components.CardAction
import com.health.app.ui.components.InfoCard
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.tracking.Glucose
import kotlin.math.roundToInt

/*
 * Today's cards for habits, blood pressure and glucose, symptoms, stress, drinks and water. Each
 * has the same title row (InfoCard): a chevron to the feature's page and at most one action, then
 * one headline ([Figure]) and at most one line under it.
 */

/** Habits done today, and what's still to do: ticking them is on their page. */
@Composable
fun HabitsTodayCard(habits: List<HabitStatus>, onOpen: () -> Unit) {
    InfoCard("Habits", icon = Icons.Rounded.Checklist, onClick = onOpen, onOpen = onOpen) {
        val left = habits.filter { !it.done }.map { it.habit.name }
        Figure(
            "${habits.count { it.done }}", "of ${habits.size} done",
            if (left.isEmpty()) "All done today" else "Still to do: ${left.joinToString()}",
            supportingColor = if (left.isEmpty()) AppTheme.status.good else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Blood pressure and glucose: the latest of each; "+" offers either. */
@Composable
fun VitalsTodayCard(vitals: VitalsToday, unit: GlucoseUnit, readOnly: Boolean, onBloodPressure: () -> Unit, onGlucose: () -> Unit, onOpen: () -> Unit) {
    val bp = vitals.bloodPressure
    val g = vitals.glucose
    InfoCard(
        "Blood pressure & glucose", icon = Icons.Rounded.MonitorHeart, iconTint = AppTheme.data.heart, onClick = onOpen,
        onOpen = onOpen, openLabel = "blood pressure and glucose",
        action = if (readOnly) null else CardAction(Icons.Rounded.Add, "Log blood pressure or glucose", menu = listOf("Blood pressure" to onBloodPressure, "Glucose" to onGlucose)),
    ) {
        val glucose = g?.let { "Glucose ${Glucose.number(it.mmolL, unit)} ${unit.label}" }
        val inRange = vitals.timeInRange?.let { "$it% in range" }
        if (bp != null) {
            Figure("${bp.systolic}/${bp.diastolic}", "mmHg", listOfNotNull(vitals.category?.label, glucose, inRange).joinToString(" · ").ifEmpty { null })
        } else if (g != null) {
            Figure(Glucose.number(g.mmolL, unit), "${unit.label} glucose", inRange?.replaceFirstChar(Char::uppercase))
        }
    }
}

/** The day's symptoms by name, and the worst of them. */
@Composable
fun SymptomsTodayCard(day: SymptomsDay, readOnly: Boolean, onLog: () -> Unit, onOpen: () -> Unit) {
    InfoCard(
        "Symptoms", icon = Icons.Rounded.Healing, onClick = onOpen, onOpen = onOpen,
        action = if (readOnly) null else CardAction(Icons.Rounded.Add, "Log a symptom", onClick = onLog),
    ) {
        val names = (day.entries.map { it.symptom } + day.migraines.map { "migraine" }).distinct().joinToString(", ").replaceFirstChar { it.uppercase() }
        Headline(names, supporting = day.worst?.let { "Worst $it out of 10" })
    }
}

/** Time stressed and restored while awake, or how resilience is going. */
@Composable
fun StressTodayCard(day: StressDay?, resilience: Resilience?, onOpen: () -> Unit) {
    InfoCard("Stress & recovery", icon = Icons.Rounded.SelfImprovement, onClick = onOpen, onOpen = onOpen, openLabel = "trends") {
        val r = resilience?.let { "Resilience: ${it.level.label.lowercase()}" }
        if (day != null) {
            Figure(Format.minutes(day.stressedMinutes), "stressed", listOfNotNull("${Format.minutes(day.restoredMinutes)} restored", r).joinToString(" · "))
        } else if (resilience != null) {
            Figure(
                resilience.level.label, "resilience",
                when (resilience.trend) {
                    "up" -> "Building on the two weeks before"
                    "down" -> "Lower than the two weeks before"
                    else -> "Steady"
                },
            )
        }
    }
}

/** Today's caffeine, what's likely still in you at bedtime, and alcohol; "+" adds any drink. */
@Composable
fun DrinksTodayCard(day: DrinksDay, readOnly: Boolean, onAdd: () -> Unit, onOpen: () -> Unit) {
    InfoCard(
        "Drinks", icon = Icons.Rounded.LocalCafe, iconTint = AppTheme.data.carbs, onClick = onOpen, onOpen = onOpen,
        action = if (readOnly) null else CardAction(Icons.Rounded.Add, "Add a drink", onClick = onAdd),
    ) {
        val alcohol = day.standardDrinks.takeIf { it > 0 }?.let { Format.compact(it) }?.let { "$it ${if (it == "1") "drink" else "drinks"} with alcohol" }
        val bedtime = day.caffeineAtBedtimeMg?.takeIf { it >= 1 }?.let { "about ${it.roundToInt()} mg at bedtime" }
        if (day.caffeineMg > 0) {
            Figure("${day.caffeineMg.roundToInt()}", "mg caffeine", listOfNotNull(bedtime, alcohol).joinToString(" · ").replaceFirstChar(Char::uppercase).ifEmpty { null })
        } else if (alcohol != null) {
            Figure(Format.compact(day.standardDrinks), if (Format.compact(day.standardDrinks) == "1") "drink with alcohol" else "drinks with alcohol", "Standard drinks, 14 g of alcohol each")
        } else {
            val n = day.drinks.size
            Figure("$n", if (n == 1) "drink" else "drinks", "No caffeine or alcohol")
        }
    }
}

/**
 * Water today, against the goal while amounts of food and drink show ([target]: not in eating
 * disorder recovery); "+" only when Water isn't among the quick actions on Today.
 */
@Composable
fun WaterTodayCard(ml: Int, goalMl: Int, units: Units, target: Boolean, onAdd: (() -> Unit)?, onOpen: () -> Unit) {
    InfoCard(
        "Water", icon = Icons.Rounded.WaterDrop, iconTint = AppTheme.data.water, onClick = onOpen, onOpen = onOpen, openLabel = "food",
        action = onAdd?.let { CardAction(Icons.Rounded.Add, "Add ${Format.water(QUICK_WATER_ML, units)} of water", onClick = it) },
    ) {
        if (target && goalMl > 0) {
            val left = goalMl - ml
            Figure(Format.water(ml, units), "of ${Format.water(goalMl, units)}", if (left > 0) "${Format.water(left, units)} to go" else "Goal reached", supportingColor = if (left > 0) androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant else AppTheme.status.good)
        } else {
            Figure(Format.water(ml, units), "so far today")
        }
    }
}
