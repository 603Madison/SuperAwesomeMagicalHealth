package com.health.app.ui.tracking

import com.health.api.BloodPressure
import com.health.api.Drink
import com.health.api.DrinkKind
import com.health.api.GlucoseReading
import com.health.api.GlucoseUnit
import com.health.api.HabitStatus
import com.health.api.HabitTick
import com.health.api.SymptomEntry
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.addBloodPressure
import com.health.app.net.addDrink
import com.health.app.net.addGlucose
import com.health.app.net.addSymptom
import com.health.app.net.deleteDrink
import com.health.app.net.tickHabit
import com.health.app.net.updateDrink
import kotlinx.coroutines.launch

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
