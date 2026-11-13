package com.health.app.ui.tracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.api.Module
import com.health.api.StressState
import com.health.api.TrendDay
import com.health.api.VitalsTargets
import com.health.app.ui.Format
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.BarChart
import com.health.app.ui.components.LegendItem
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.trends.ChartCard
import kotlin.math.roundToInt

private fun average(values: List<Double?>): Double? = values.filterNotNull().takeIf { it.isNotEmpty() }?.average()

/**
 * Trends' charts for blood pressure and glucose, stress, drinks, symptoms and habits, for the
 * features that are on: sections of Trends, by id.
 */
fun trackingTrendSections(modules: Set<Module>): List<ArrangeItem> = buildList {
    if (Module.VITALS in modules) {
        add(ArrangeItem("bp", "Blood pressure"))
        add(ArrangeItem("glucose", "Glucose"))
    }
    if (Module.ACTIVITY in modules) add(ArrangeItem("stress", "Stress and recovery"))
    if (Module.NUTRITION in modules) {
        add(ArrangeItem("caffeine", "Caffeine"))
        add(ArrangeItem("alcohol", "Alcohol"))
    }
    if (Module.SYMPTOMS in modules) add(ArrangeItem("symptoms", "Symptoms"))
    if (Module.HABITS in modules) add(ArrangeItem("habits", "Habits"))
}

/** One of [trackingTrendSections]' charts, by id: drawn when there's something in the range to show. */
@Composable
fun TrackingTrend(id: String, days: List<TrendDay>, labels: Triple<String, String?, String>, targets: VitalsTargets = VitalsTargets()) {
    if (id == "bp" && days.any { it.systolic != null }) {
        val sys = average(days.map { it.systolic })?.roundToInt()
        val dia = average(days.map { it.diastolic })?.roundToInt()
        val sysColor = AppTheme.data.heart
        val diaColor = MaterialTheme.colorScheme.primary
        ChartCard(
            "Blood pressure", Icons.Rounded.MonitorHeart, sysColor,
            if (sys != null && dia != null) "$sys/$dia" else null, "mmHg on average", labels,
            footer = "Your targets: under ${targets.systolicHigh}/${targets.diastolicHigh}",
        ) {
            BandChart(listOf(ChartSeries(days.map { it.systolic }, sysColor), ChartSeries(days.map { it.diastolic }, diaColor)), height = 110.dp, description = "Blood pressure each day")
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(sysColor, "Top")
                LegendItem(diaColor, "Bottom")
            }
        }
    }
    if (id == "glucose" && days.any { it.glucose != null }) {
        val unit = targets.glucoseUnit
        ChartCard(
            "Glucose", Icons.Rounded.Bloodtype, MaterialTheme.colorScheme.tertiary,
            average(days.map { it.glucose })?.let { Glucose.number(it, unit) }, "${unit.label} on average", labels,
            footer = "Your range, shaded: ${Glucose.number(targets.glucoseLow, unit)} to ${Glucose.text(targets.glucoseHigh, unit)}",
        ) {
            BandChart(
                listOf(ChartSeries(days.map { d -> d.glucose?.let { Glucose.value(it, unit) } }, MaterialTheme.colorScheme.tertiary)),
                band = Glucose.value(targets.glucoseLow, unit)..Glucose.value(targets.glucoseHigh, unit), height = 110.dp,
                description = "Glucose each day",
            )
        }
    }
    if (id == "stress" && days.any { it.stressedMinutes != null || it.restoredMinutes != null }) {
        val stressed = stressColor(StressState.STRESSED)
        val restored = stressColor(StressState.RESTORED)
        ChartCard(
            "Stress and recovery", Icons.Rounded.SelfImprovement, stressed,
            average(days.map { it.stressedMinutes?.toDouble() })?.let { Format.minutes(it.roundToInt()) }, "stressed a day", labels,
            footer = average(days.map { it.restoredMinutes?.toDouble() })?.let { "${Format.minutes(it.roundToInt())} restored a day" },
        ) {
            BandChart(
                listOf(ChartSeries(days.map { it.stressedMinutes?.toDouble() }, stressed), ChartSeries(days.map { it.restoredMinutes?.toDouble() }, restored)),
                height = 100.dp, description = "Stressed and restored minutes each day",
            )
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(stressed, "Stressed")
                LegendItem(restored, "Restored")
            }
        }
    }
    if (id == "caffeine" && days.any { (it.caffeineMg ?: 0.0) > 0 }) {
        ChartCard(
            "Caffeine", Icons.Rounded.LocalCafe, AppTheme.data.carbs,
            average(days.map { it.caffeineMg })?.let { "${it.roundToInt()} mg" }, "a day", labels,
        ) { BarChart(days.map { it.caffeineMg }, color = AppTheme.data.carbs) }
    }
    if (id == "alcohol" && days.any { (it.standardDrinks ?: 0.0) > 0 }) {
        ChartCard(
            "Alcohol", Icons.Rounded.LocalBar, AppTheme.data.protein,
            Format.compact(days.sumOf { it.standardDrinks ?: 0.0 }), "standard drinks in these ${days.size} days", labels,
            footer = "${days.count { (it.standardDrinks ?: 0.0) == 0.0 }} days without",
        ) { BarChart(days.map { it.standardDrinks }, color = AppTheme.data.protein) }
    }
    if (id == "symptoms" && days.any { it.symptomWorst != null || it.migraine == true }) {
        val migraines = days.count { it.migraine == true }
        ChartCard(
            "Symptoms", Icons.Rounded.Healing, AppTheme.status.warn,
            days.count { it.symptomWorst != null }.toString(), "days with symptoms", labels,
            footer = if (migraines > 0) "$migraines ${if (migraines == 1) "migraine day" else "migraine days"}" else null,
        ) { BarChart(days.map { it.symptomWorst?.toDouble() }, color = AppTheme.status.warn) }
    }
    if (id == "habits" && days.any { (it.habitsDue ?: 0) > 0 }) {
        val due = days.sumOf { it.habitsDue ?: 0 }
        val done = days.sumOf { minOf(it.habitsDone ?: 0, it.habitsDue ?: 0) }
        ChartCard(
            "Habits", Icons.Rounded.Checklist, AppTheme.status.good,
            if (due > 0) "${(done * 100.0 / due).roundToInt()}%" else null, "done", labels,
        ) {
            BarChart(days.map { d -> d.habitsDue?.takeIf { it > 0 }?.let { (d.habitsDone ?: 0).coerceAtMost(it) * 100.0 / it } }, color = AppTheme.status.good, goal = 100.0)
        }
    }
}
