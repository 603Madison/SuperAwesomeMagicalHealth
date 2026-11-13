package com.health.app.ui.today

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.Composable
import com.health.api.ContinenceDay
import com.health.api.CyclePhase
import com.health.api.CycleToday
import com.health.api.MindDay
import com.health.api.Module
import com.health.app.ui.Format
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.CardAction
import com.health.app.ui.components.InfoCard
import com.health.app.ui.modules.ModuleInfo
import com.health.app.ui.modules.Moods
import com.health.app.ui.modules.cycle.periodColor
import com.health.app.ui.modules.cycle.periodText

/*
 * Today's cards for the health modules: each the same title row (InfoCard) with at most one
 * action, then one headline ([Figure]) and at most one line under it. A module with nothing
 * today has no card: it's a row in Also today (TodayPlan).
 */

/** How the person's feeling today; "+" logs a mood. */
@Composable
fun MindTodayCard(mind: MindDay, onLog: () -> Unit, onOpen: () -> Unit) {
    val readOnly = LocalReadOnly.current
    InfoCard(
        "Mind", icon = ModuleInfo.icon(Module.MIND), onClick = onOpen, onOpen = onOpen,
        action = if (readOnly) null else CardAction(Icons.Rounded.Add, "Log your mood", onClick = onLog),
    ) {
        val extras = listOfNotNull(
            mind.entries.takeIf { it > 1 }?.let { "From $it check-ins" },
            mind.mindfulMinutes.takeIf { it > 0 }?.let { "${Format.minutes(it.toInt().coerceAtLeast(1))} of calm" },
            mind.journal.takeIf { it > 0 }?.let { if (it == 1) "1 journal entry" else "$it journal entries" },
        ).joinToString(" · ").ifEmpty { null }
        val mood = mind.mood
        if (mood != null) Headline("Feeling ${Moods.label(Moods.nearest(mood)).lowercase()}", supporting = extras)
        else Headline(extras ?: "Checked in")
    }
}

/** The cycle day, the phase and when the period's likely; "+" logs today. */
@Composable
fun CycleTodayCard(cycle: CycleToday, plain: Boolean, onLog: () -> Unit, onOpen: () -> Unit) {
    val readOnly = LocalReadOnly.current
    InfoCard(
        "Cycle", icon = ModuleInfo.icon(Module.CYCLE), iconTint = periodColor(), onClick = onOpen, onOpen = onOpen,
        action = if (readOnly) null else if (cycle.logged != null) CardAction(Icons.Rounded.Edit, "Change today's cycle log", onClick = onLog) else CardAction(Icons.Rounded.Add, "Log today", onClick = onLog),
    ) {
        val logged = cycle.logged?.let { d -> listOfNotNull(d.flow?.let { "${it.label} flow" }, d.symptoms.takeIf { it.isNotEmpty() }?.joinToString()).joinToString(" · ").ifEmpty { "Logged" } }
        val phase = cycle.phase.takeIf { it != CyclePhase.UNKNOWN }?.let { com.health.app.ui.modules.cycle.CycleText.phase(it, plain) }
        val period = periodText(cycle.daysUntilPeriod)
        val day = cycle.cycleDay
        if (day != null) {
            Figure("Day $day", supporting = listOfNotNull(phase, logged?.let { "today: ${it.replaceFirstChar(Char::lowercase)}" } ?: period?.replaceFirstChar(Char::lowercase)).joinToString(" · ").replaceFirstChar(Char::uppercase).ifEmpty { null })
        } else {
            Headline(logged ?: "Logged today", supporting = period)
        }
    }
}

/** Toilet trips today, with leaks and night trips under them; "+" logs one. */
@Composable
fun ContinenceTodayCard(day: ContinenceDay, onLog: () -> Unit, onOpen: () -> Unit) {
    val readOnly = LocalReadOnly.current
    InfoCard(
        "Bladder & bowel", icon = ModuleInfo.icon(Module.CONTINENCE), onClick = onOpen, onOpen = onOpen,
        action = if (readOnly) null else CardAction(Icons.Rounded.Add, "Log bladder and bowel", onClick = onLog),
    ) {
        val trips = day.voids
        Figure(
            "$trips", if (trips == 1) "toilet trip" else "toilet trips",
            listOfNotNull(
                day.leaks.takeIf { it > 0 }?.let { if (it == 1) "1 leak" else "$it leaks" },
                day.nightVoids.takeIf { it > 0 }?.let { "$it at night" },
                day.bowel.takeIf { it > 0 }?.let { if (it == 1) "1 bowel movement" else "$it bowel movements" },
                day.pelvicFloor.takeIf { it > 0 }?.let { "pelvic floor ${if (it == 1) "once" else "$it times"}" },
            ).joinToString(" · ").replaceFirstChar(Char::uppercase).ifEmpty { null },
        )
    }
}

/**
 * Autism & ADHD on Today: energy left (or the social battery, or focus time), with a gentle word
 * when today's been a lot. Routines still to do are in Up next. With only Mind on, just the
 * energy budget (Mind's own).
 */
@Composable
fun NeuroTodayCard(today: com.health.api.NeuroToday, energyOnly: Boolean, onLog: (() -> Unit)?, onOpen: () -> Unit) {
    val title = if (energyOnly) "Energy" else Module.NEURO.label
    InfoCard(
        title, icon = ModuleInfo.icon(if (energyOnly) Module.MIND else Module.NEURO), onClick = onOpen, onOpen = onOpen, openLabel = title,
        action = onLog?.takeUnless { LocalReadOnly.current }?.let { CardAction(Icons.Rounded.Add, "Log energy and focus", onClick = it) },
    ) {
        val alot = when (today.overloadsToday) {
            0 -> null
            1 -> "Today's been a lot once already. Go gently."
            else -> "Today's been a lot ${today.overloadsToday} times. Go gently."
        }
        val battery = today.socialBattery?.let { "social battery $it/10" }
        val focus = today.focusMinutes.takeIf { it > 0 && !energyOnly }?.let { "${Format.minutes(it)} focus" }
        when {
            today.energyLeft != null -> Figure("${today.energyLeft}", if (today.energyLeft == 1) "spoon left" else "spoons left", alot ?: listOfNotNull(battery, focus).joinToString(" · ").replaceFirstChar(Char::uppercase).ifEmpty { null })
            today.socialBattery != null -> Figure("${today.socialBattery}/10", "social battery", alot ?: focus?.replaceFirstChar(Char::uppercase))
            focus != null -> Figure(Format.minutes(today.focusMinutes), "of focus", alot)
            else -> Headline("Today's been a lot", supporting = "Go gently.")
        }
    }
}

/** Transition on Today: how long since the person began, and moments of euphoria this week; "+" notes one. */
@Composable
fun TransitionTodayCard(today: com.health.api.TransitionToday, onLog: () -> Unit, onOpen: () -> Unit) {
    val readOnly = LocalReadOnly.current
    InfoCard(
        Module.TRANSITION.label, icon = ModuleInfo.icon(Module.TRANSITION), onClick = onOpen, onOpen = onOpen,
        action = if (readOnly) null else CardAction(Icons.Rounded.Add, "Note a moment of euphoria", onClick = onLog),
    ) {
        val euphoria = today.euphoriaThisWeek.takeIf { it > 0 }?.let { if (it == 1) "1 moment of euphoria this week" else "$it moments of euphoria this week" }
        val coming = today.milestonesDue.takeIf { it.isNotEmpty() }?.let { "Coming up: ${it.joinToString()}" }
        val headline = today.headline
        if (headline != null) Headline(headline, supporting = euphoria ?: coming)
        else Headline(euphoria?.replaceFirstChar(Char::uppercase) ?: coming ?: "This week", supporting = if (euphoria != null) coming else null)
    }
}
