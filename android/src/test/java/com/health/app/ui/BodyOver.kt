package com.health.app.ui

import androidx.compose.runtime.Composable
import com.health.api.CombinedSleep
import com.health.api.CombinedValue
import com.health.api.Goals
import com.health.api.Module
import com.health.api.Sample
import com.health.api.Trends
import com.health.api.Units
import com.health.api.VitalsTargets
import com.health.app.ui.body.BodyActions
import com.health.app.ui.body.BodyScreen
import com.health.app.ui.body.BodySpan
import com.health.app.ui.body.BodyState
import java.time.ZoneId

/** Days to draw on Body over a week, month or year (what Trends drew): the days, weigh-ins, and today's readings from more than one device. */
data class Span(
    val trends: Trends,
    val weights: List<Sample> = emptyList(),
    val combined: List<CombinedValue> = emptyList(),
    val combinedSleep: CombinedSleep? = null,
)

/** Body at the span that fits [span]'s days (a week, a month or a year), for the screenshot tests. */
@Composable
fun BodyOver(
    span: Span,
    units: Units,
    goals: Goals,
    zone: ZoneId,
    modules: Set<Module> = Module.DEFAULT,
    vitalsTargets: VitalsTargets = VitalsTargets(),
    water: Boolean = true,
    micros: Boolean = false,
) {
    val days = span.trends.days.size
    val which = BodySpan.entries.firstOrNull { it.days == days && it != BodySpan.DAY } ?: BodySpan.MONTH
    BodyScreen(
        BodyState(span = which, trends = span.trends, weights = span.weights, combined = span.combined, combinedSleep = span.combinedSleep, loading = false),
        units, zone, BodyActions(), goals = goals, modules = modules, vitalsTargets = vitalsTargets, water = water, micros = micros,
    )
}
