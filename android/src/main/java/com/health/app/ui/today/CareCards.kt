package com.health.app.ui.today

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Science
import androidx.compose.runtime.Composable
import com.health.api.Appointment
import com.health.api.HormonesToday
import com.health.api.LifeStage
import com.health.api.LifeStageToday
import com.health.api.Module
import com.health.app.attempt
import com.health.app.ui.care.CareFormat
import com.health.app.ui.components.InfoCard
import com.health.app.ui.modules.ModuleInfo
import com.health.app.ui.theme.AppTheme
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Hormone therapy on Today: when labs are due, and levels outside the person's range. */
@Composable
fun HormonesTodayCard(h: HormonesToday, today: LocalDate, onOpen: () -> Unit) {
    InfoCard("Hormone therapy", icon = Icons.Rounded.Science, onClick = onOpen, onOpen = onOpen) {
        val due = h.nextLabsDue?.let { attempt { LocalDate.parse(it) }.getOrNull() }
        val labs = due?.let {
            val days = ChronoUnit.DAYS.between(today, it)
            when {
                days < 0 -> "Labs were due ${CareFormat.date(it)}"
                days == 0L -> "Labs are due today"
                days <= 14 -> "Labs due in $days ${if (days == 1L) "day" else "days"}"
                else -> "Next labs around ${CareFormat.date(it)}"
            }
        }
        val out = h.outOfRange.takeIf { it.isNotEmpty() }?.let { "Outside your range: ${it.joinToString()}" }
        if (labs != null) Headline(labs, supporting = out, supportingColor = AppTheme.status.warn)
        else Headline(out ?: "${h.medications} ${if (h.medications == 1) "medication" else "medications"}", supporting = if (out != null) "Worth mentioning to your clinician" else null)
    }
}

@Composable
fun LifeStageTodayCard(s: LifeStageToday, zone: ZoneId, onOpen: () -> Unit) {
    InfoCard(s.stage.label, icon = ModuleInfo.icon(Module.CYCLE), onClick = onOpen, onOpen = onOpen, openLabel = "cycle") {
        val more = when (s.stage) {
            LifeStage.PREGNANT -> s.pregnancy?.let { p -> "Due ${CareFormat.date(p.dueDate) ?: p.dueDate}" + if (p.daysToGo > 0) " · ${p.daysToGo} days to go" else "" }
            LifeStage.POSTPARTUM -> listOfNotNull(
                s.feedsToday.takeIf { it > 0 }?.let { "$it ${if (it == 1) "feed" else "feeds"} today" },
                s.lastFeed?.let { "last: ${it.kind.label.lowercase()} at ${CareFormat.time(it.start, zone)}" },
            ).joinToString(" · ").replaceFirstChar(Char::uppercase).ifEmpty { null }
            LifeStage.PERIMENOPAUSE, LifeStage.MENOPAUSE ->
                s.hotFlashesToday.takeIf { it > 0 }?.let { "$it hot ${if (it == 1) "flash" else "flashes"} today" }
            else -> null
        }
        Headline(s.headline, supporting = more)
    }
}

/** The week's next appointment (today's are in Up next), with how many more there are. */
@Composable
fun AppointmentsTodayCard(list: List<Appointment>, zone: ZoneId, now: Long, onOpen: () -> Unit) {
    val next = list.minByOrNull { it.at } ?: return
    InfoCard("Appointments", icon = Icons.Rounded.Event, onClick = onOpen, onOpen = onOpen, openLabel = "appointments") {
        val open = next.questions.count { !it.done }
        Headline(
            next.title + (next.with?.takeIf { it.isNotBlank() && !next.title.contains(it, true) }?.let { " with $it" } ?: ""),
            supporting = listOfNotNull(
                com.health.app.reminders.CarePlanner.appointmentWhen(next.at, now, zone).replaceFirstChar(Char::uppercase),
                open.takeIf { it > 0 }?.let { if (it == 1) "1 question to ask" else "$it questions to ask" },
                (list.size - 1).takeIf { it > 0 }?.let { "$it more this week" },
            ).joinToString(" · "),
        )
    }
}
