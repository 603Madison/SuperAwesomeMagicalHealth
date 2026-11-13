package com.health.app.ui.today

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.api.Appointment
import com.health.api.HormonesToday
import com.health.api.LifeStage
import com.health.api.LifeStageToday
import com.health.api.Module
import com.health.app.ui.care.CareFormat
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Pill
import com.health.app.ui.modules.ModuleInfo
import com.health.app.ui.theme.AppTheme
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Hormone therapy on Today: when labs are due, and levels outside the person's range. */
@Composable
fun HormonesTodayCard(h: HormonesToday, today: LocalDate, onOpen: () -> Unit) {
    InfoCard("Hormone therapy", icon = Icons.Rounded.Science, onClick = onOpen, onOpen = onOpen) {
        val due = h.nextLabsDue?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        when {
            due == null -> Text("${h.medications} ${if (h.medications == 1) "medication" else "medications"}", style = MaterialTheme.typography.bodyLarge)
            else -> {
                val days = ChronoUnit.DAYS.between(today, due)
                Text(
                    when {
                        days < 0 -> "Labs were due ${CareFormat.date(due)}"
                        days == 0L -> "Labs are due today"
                        days <= 14 -> "Labs due in $days ${if (days == 1L) "day" else "days"}"
                        else -> "Next labs around ${CareFormat.date(due)}"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        if (h.outOfRange.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Pill("Outside your range: ${h.outOfRange.joinToString()}", AppTheme.status.warn)
            Hint("Worth mentioning to your clinician.", Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun LifeStageTodayCard(s: LifeStageToday, zone: ZoneId, onOpen: () -> Unit) {
    InfoCard(s.stage.label, icon = ModuleInfo.icon(Module.CYCLE), onClick = onOpen, onOpen = onOpen, openLabel = "cycle") {
        Text(s.headline, style = MaterialTheme.typography.titleMedium)
        when (s.stage) {
            LifeStage.PREGNANT -> s.pregnancy?.let { p ->
                Hint("Due ${CareFormat.date(p.dueDate) ?: p.dueDate}" + if (p.daysToGo > 0) " · ${p.daysToGo} days to go" else "")
            }
            LifeStage.POSTPARTUM -> {
                if (s.feedsToday > 0) Hint("${s.feedsToday} ${if (s.feedsToday == 1) "feed" else "feeds"} today")
                s.lastFeed?.let { Hint("Last: ${it.kind.label.lowercase()} at ${CareFormat.time(it.start, zone)}") }
            }
            LifeStage.PERIMENOPAUSE, LifeStage.MENOPAUSE ->
                if (s.hotFlashesToday > 0) Hint("${s.hotFlashesToday} hot ${if (s.hotFlashesToday == 1) "flash" else "flashes"} today")
            else -> Unit
        }
    }
}

@Composable
fun AppointmentsTodayCard(list: List<Appointment>, zone: ZoneId, now: Long, onOpen: () -> Unit) {
    InfoCard("This week", icon = Icons.Rounded.Event, onClick = onOpen, onOpen = onOpen, openLabel = "appointments") {
        list.sortedBy { it.at }.take(3).forEach { a ->
            Column(Modifier.padding(vertical = 3.dp)) {
                Text(a.title + (a.with?.takeIf { it.isNotBlank() && !a.title.contains(it, true) }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyLarge)
                val open = a.questions.count { !it.done }
                Hint(
                    com.health.app.reminders.CarePlanner.appointmentWhen(a.at, now, zone).replaceFirstChar(Char::uppercase) +
                        if (open > 0) " · $open ${if (open == 1) "question" else "questions"} to ask" else "",
                )
            }
        }
    }
}
