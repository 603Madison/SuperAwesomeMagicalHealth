package com.health.app.ui.modules.meds

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.MedForm
import com.health.api.Medication
import com.health.api.MedsHistoryDay
import com.health.app.ui.Format
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.modules.IconTile
import com.health.app.ui.modules.meds.MedsCalendar.Mark
import com.health.app.ui.theme.AppTheme
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The history (Medications' History section): a month at a time, each day marked by what happened
 * to its doses, its shape as well as its colour telling them apart; a day picked shows its doses
 * under the calendar, each with Undo, or for one missed, Took it and Skipped. Doses taken as needed
 * can be removed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun History(state: MedsState, zone: ZoneId, actions: MedsActions, sitesFor: (String) -> List<String>) {
    // Today as the page loaded it (as the day's doses were), so the two agree.
    val today = state.date
    val locale = LocalLocale.current.platformLocale
    val days = state.history?.days.orEmpty().associateBy { it.date }
    InfoCard("History") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.month(-1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "The month before") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(MedsCalendar.title(state.month, locale), style = MaterialTheme.typography.titleSmall)
                MedsCalendar.summary(state.history?.days.orEmpty(), today)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            IconButton(onClick = { actions.month(1) }, enabled = state.month < YearMonth.from(today)) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "The month after")
            }
        }
        state.historyError?.let { Hint(it) }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            MedsCalendar.WEEKDAYS.forEach {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        MedsCalendar.weeks(state.month).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { date ->
                    Box(Modifier.weight(1f)) {
                        if (date != null) DayCell(date, days[date.toString()], today, picked = state.picked == date) { actions.pick(date) }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MedsCalendar.KEY.forEach { (mark, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MarkDot(mark)
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        state.picked?.let { date -> PickedDay(state, date, today, zone, actions, sitesFor) }
    }
}

/** A day in the calendar: its number, and its mark under it. Days with nothing to show can't be picked. */
@Composable
private fun DayCell(date: LocalDate, day: MedsHistoryDay?, today: LocalDate, picked: Boolean, onPick: () -> Unit) {
    val mark = MedsCalendar.mark(day, today)
    val canPick = mark != Mark.NONE && !(mark == Mark.FUTURE && (day?.scheduled ?: 0) == 0)
    val shape = RoundedCornerShape(12.dp)
    val said = "${MedsCalendar.dayTitle(date, today)}: ${MedsCalendar.text(day, today)}"
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
            .then(if (picked) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .then(if (canPick) Modifier.clickable(role = Role.Button, onClick = onPick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = said; selected = picked }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (date == today) FontWeight.Bold else null,
            color = when {
                date == today -> MaterialTheme.colorScheme.primary
                canPick -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            },
        )
        MarkDot(mark)
    }
}

/** A day's mark: filled for all taken, a ring for one missed, a dash for skipped, half filled for today's still to take. */
@Composable
private fun MarkDot(mark: Mark, size: Dp = 10.dp) {
    val chart = AppTheme.chart
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        when (mark) {
            Mark.TAKEN -> drawCircle(chart.c2, r)
            Mark.MISSED -> drawCircle(chart.c3, r - 1.25.dp.toPx(), style = Stroke(2.5.dp.toPx()))
            Mark.SKIPPED -> drawRoundRect(muted, topLeft = Offset(0f, center.y - 1.5.dp.toPx()), size = Size(this.size.width, 3.dp.toPx()), cornerRadius = CornerRadius(1.5.dp.toPx()))
            Mark.OPEN -> {
                drawArc(chart.c1, 90f, 180f, useCenter = true)
                drawCircle(chart.c1, r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            }
            Mark.EXTRA -> drawCircle(muted, r * 0.6f)
            Mark.NONE, Mark.FUTURE -> Unit
        }
    }
}

/** The day picked: its scheduled doses (with Undo, or Took it and Skipped), then any taken as needed (with Remove). */
@Composable
private fun PickedDay(state: MedsState, date: LocalDate, today: LocalDate, zone: ZoneId, actions: MedsActions, sitesFor: (String) -> List<String>) {
    val readOnly = LocalReadOnly.current
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Text(MedsCalendar.dayTitle(date, today, LocalLocale.current.platformLocale), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    val day = state.pickedDay?.takeIf { it.date == date.toString() } ?: return
    if (day.due.isEmpty() && day.extra.isEmpty()) {
        Hint("Nothing that day.")
        return
    }
    DoseList(day, zone, actions, sitesFor = sitesFor, framed = false, today = today)
    val meds = state.meds.associateBy { it.id }
    day.extra.forEach { log -> Extra(log, meds[log.medicationId], zone, readOnly, actions, divided = day.due.isNotEmpty() || log != day.extra.first()) }
}

/** A dose off the schedule (as needed): what, when, and Remove. */
@Composable
private fun Extra(log: DoseLog, med: Medication?, zone: ZoneId, readOnly: Boolean, actions: MedsActions, divided: Boolean) {
    if (divided) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(formIcon(med?.form ?: MedForm.PILL), size = 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(med?.name ?: "A medication you've removed", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${if (log.status == DoseStatus.TAKEN) "Taken" else "Skipped"} ${Format.timeOfDay(log.at, zone)}" + (log.site?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!readOnly) TextButton(onClick = { actions.remove(log) }) { Text("Remove") }
    }
}
