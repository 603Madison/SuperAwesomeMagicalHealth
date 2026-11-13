package com.health.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.api.ContinenceDay
import com.health.api.ContinenceKind
import com.health.api.CyclePhase
import com.health.api.CycleToday
import com.health.api.DueDose
import com.health.api.MedsDay
import com.health.api.MindDay
import com.health.api.Module
import com.health.app.ui.Format
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Pill
import com.health.app.ui.components.Stat
import com.health.app.ui.modules.FaceScale
import com.health.app.ui.modules.ModuleInfo
import com.health.app.ui.modules.Moods
import com.health.app.ui.modules.continence.QuickButtons
import com.health.app.ui.modules.cycle.periodColor
import com.health.app.ui.modules.cycle.periodText
import com.health.app.ui.modules.cycle.phaseColor
import com.health.app.ui.modules.meds.DoseList
import com.health.app.ui.modules.meds.MedsActions
import com.health.app.ui.theme.AppTheme
import java.time.ZoneId

@Composable
private fun More(onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Today's mood: one tap on a face logs it. */
@Composable
fun MindTodayCard(mind: MindDay?, onMood: (Int) -> Unit, onOpen: () -> Unit) {
    InfoCard("Mind", icon = ModuleInfo.icon(Module.MIND), onClick = onOpen, trailing = { More(onOpen) }) {
        val mood = mind?.mood
        if (mood != null) {
            val n = Moods.nearest(mood)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Moods.icon(n), null, tint = Moods.color(n))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Feeling ${Moods.label(n).lowercase()}" + if (mind.entries > 1) " on average, from ${mind.entries} check-ins" else "",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (mind.mindfulMinutes > 0 || mind.journal > 0) {
                Hint(
                    listOfNotNull(
                        mind.mindfulMinutes.takeIf { it > 0 }?.let { "${Format.minutes(it.toInt().coerceAtLeast(1))} of calm" },
                        mind.journal.takeIf { it > 0 }?.let { if (it == 1) "1 journal entry" else "$it journal entries" },
                    ).joinToString(" · "),
                    Modifier.padding(top = 4.dp),
                )
            }
            if (com.health.app.ui.care.LocalReadOnly.current) return@InfoCard
            Spacer(Modifier.height(10.dp))
            Hint("How about now?")
        } else {
            Hint(if (com.health.app.ui.care.LocalReadOnly.current) "No mood logged yet today." else "How are you feeling?")
        }
        if (com.health.app.ui.care.LocalReadOnly.current) return@InfoCard
        Spacer(Modifier.height(8.dp))
        FaceScale(null, { it?.let(onMood) }, size = 48.dp, labels = false)
    }
}

@Composable
fun CycleTodayCard(cycle: CycleToday?, onLog: () -> Unit, onOpen: () -> Unit) {
    InfoCard("Cycle", icon = ModuleInfo.icon(Module.CYCLE), iconTint = periodColor(), onClick = onOpen, trailing = { More(onOpen) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cycle?.cycleDay?.let { "Day $it" } ?: "Not started yet", style = MaterialTheme.typography.headlineSmall)
                val phase = cycle?.phase ?: CyclePhase.UNKNOWN
                if (phase != CyclePhase.UNKNOWN) Pill(com.health.app.ui.modules.cycle.CycleText.phase(phase, com.health.app.ui.modules.cycle.plainOnToday()), phaseColor(phase), Modifier.padding(top = 4.dp))
                periodText(cycle?.daysUntilPeriod)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                cycle?.logged?.let { d ->
                    val parts = listOfNotNull(d.flow?.let { "${it.label} flow" }, d.symptoms.takeIf { it.isNotEmpty() }?.joinToString())
                    if (parts.isNotEmpty()) Hint("Today: ${parts.joinToString(" · ")}", Modifier.padding(top = 4.dp))
                }
            }
            if (!com.health.app.ui.care.LocalReadOnly.current) FilledTonalButton(onClick = onLog) { Text(if (cycle?.logged != null) "Edit today" else "Log today") }
        }
    }
}

@Composable
fun ContinenceTodayCard(day: ContinenceDay?, onLog: (ContinenceKind) -> Unit, onOpen: () -> Unit) {
    InfoCard("Bladder & bowel", icon = ModuleInfo.icon(Module.CONTINENCE), onClick = onOpen, trailing = { More(onOpen) }) {
        Row(Modifier.fillMaxWidth()) {
            val trips = day?.voids ?: 0
            val leaks = day?.leaks ?: 0
            Stat("$trips", if (trips == 1) "toilet trip" else "toilet trips", Modifier.weight(1f))
            Stat("$leaks", if (leaks == 1) "leak" else "leaks", Modifier.weight(1f), color = if (leaks > 0) AppTheme.status.warn else MaterialTheme.colorScheme.onSurface)
            Stat("${day?.nightVoids ?: 0}", "at night", Modifier.weight(1f))
        }
        if (!com.health.app.ui.care.LocalReadOnly.current) {
            Spacer(Modifier.height(12.dp))
            QuickButtons(onLog)
        }
    }
}

@Composable
fun MedsTodayCard(meds: MedsDay?, zone: ZoneId, onTake: (DueDose, String?) -> Unit, onOpen: () -> Unit) {
    val due = meds?.due.orEmpty()
    val left = due.count { it.log == null }
    InfoCard(
        "Medications", icon = ModuleInfo.icon(Module.MEDICATIONS), onClick = onOpen,
        trailing = {
            if (due.isNotEmpty()) {
                Pill(if (left == 0) "All done" else "$left to go", if (left == 0) AppTheme.status.good else MaterialTheme.colorScheme.primary)
            }
            More(onOpen)
        },
    ) {
        if (meds == null || due.isEmpty()) {
            Hint(if (meds?.asNeeded?.isNotEmpty() == true) "Nothing scheduled today." else "Add your medications for reminders and a one-tap checklist.")
        } else if (com.health.app.ui.care.LocalReadOnly.current) {
            // Someone viewing a share sees what's taken, without the buttons.
            due.forEach { d ->
                Text(
                    "${d.name} · ${com.health.app.ui.Format.timeOfDay(d.scheduledFor, zone)} · " +
                        when (d.log?.status) { com.health.api.DoseStatus.TAKEN -> "taken"; com.health.api.DoseStatus.SKIPPED -> "skipped"; null -> "not yet" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        } else {
            DoseList(meds, zone, MedsActions(take = onTake), compact = true)
        }
        meds?.lowStock?.takeIf { it.isNotEmpty() }?.let { low ->
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                low.take(2).forEach { Pill("${it.name}: ${it.stock ?: 0} left", AppTheme.status.warn) }
            }
        }
    }
}

/**
 * Autism & ADHD on Today: energy left, the social battery, times things got too much, focus time
 * and routines still to do. With only Mind on, just the energy budget (Mind's own).
 */
@Composable
fun NeuroTodayCard(today: com.health.api.NeuroToday, energyOnly: Boolean, onOpen: () -> Unit) {
    val title = if (energyOnly) "Energy" else Module.NEURO.label
    InfoCard(title, icon = ModuleInfo.icon(if (energyOnly) Module.MIND else Module.NEURO), onClick = onOpen, trailing = { More(onOpen) }) {
        Row(Modifier.fillMaxWidth()) {
            today.energyLeft?.let { Stat("$it", if (it == 1) "spoon left" else "spoons left", Modifier.weight(1f)) }
            today.socialBattery?.let { Stat("$it/10", "social battery", Modifier.weight(1f)) }
            if (!energyOnly && today.focusMinutes > 0) Stat(Format.minutes(today.focusMinutes), "focus", Modifier.weight(1f))
        }
        if (energyOnly) {
            if (today.energyLeft == null && today.socialBattery == null) Hint("No energy budget for today yet.")
            return@InfoCard
        }
        if (today.overloadsToday > 0) {
            Hint(
                if (today.overloadsToday == 1) "Today's been a lot once already. Go gently." else "Today's been a lot ${today.overloadsToday} times. Go gently.",
                Modifier.padding(top = 6.dp),
            )
        }
        if (today.routinesDue.isNotEmpty()) Hint("Still to do: ${today.routinesDue.joinToString()}", Modifier.padding(top = 6.dp))
        if (today.energyLeft == null && today.socialBattery == null && today.overloadsToday == 0 && today.routinesDue.isEmpty() && today.focusMinutes == 0) {
            Hint("Nothing logged yet today.")
        }
    }
}

/** Transition on Today: the headline, moments of euphoria this week, and milestones coming up. */
@Composable
fun TransitionTodayCard(today: com.health.api.TransitionToday, onOpen: () -> Unit) {
    InfoCard(Module.TRANSITION.label, icon = ModuleInfo.icon(Module.TRANSITION), onClick = onOpen, trailing = { More(onOpen) }) {
        today.headline?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        if (today.euphoriaThisWeek > 0) {
            Hint(
                if (today.euphoriaThisWeek == 1) "1 moment of euphoria this week" else "${today.euphoriaThisWeek} moments of euphoria this week",
                Modifier.padding(top = 4.dp),
            )
        }
        if (today.milestonesDue.isNotEmpty()) Hint("Coming up: ${today.milestonesDue.joinToString()}", Modifier.padding(top = 4.dp))
        if (today.headline == null && today.euphoriaThisWeek == 0 && today.milestonesDue.isEmpty()) Hint("Your milestones, changes and moments, at your pace.")
    }
}
