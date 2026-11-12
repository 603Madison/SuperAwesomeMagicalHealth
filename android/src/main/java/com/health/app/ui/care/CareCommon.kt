@file:OptIn(ExperimentalMaterial3Api::class)

package com.health.app.ui.care

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Dates and times the way the care screens show them. */
object CareFormat {
    fun date(date: LocalDate): String = DateTimeFormatter.ofPattern("d MMM yyyy").format(date)

    fun date(text: String?): String? = text?.let { runCatching { date(LocalDate.parse(it)) }.getOrNull() }

    fun dayTime(at: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("EEE d MMM, H:mm").format(Instant.ofEpochMilli(at).atZone(zone))

    fun time(at: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("H:mm").format(Instant.ofEpochMilli(at).atZone(zone))

    /** "1 year, 3 months", "5 months", "3 weeks", "4 days": how long since [since]. */
    fun since(since: LocalDate, today: LocalDate): String {
        if (since.isAfter(today)) return "starts ${date(since)}"
        val period = java.time.Period.between(since, today)
        val months = period.years * 12 + period.months
        return when {
            period.years >= 1 -> buildString {
                append("${period.years} ${if (period.years == 1) "year" else "years"}")
                if (period.months > 0) append(", ${period.months} ${if (period.months == 1) "month" else "months"}")
            }
            months >= 1 -> "$months ${if (months == 1) "month" else "months"}"
            period.days >= 7 -> (period.days / 7).let { "$it ${if (it == 1) "week" else "weeks"}" }
            else -> "${period.days} ${if (period.days == 1) "day" else "days"}"
        }
    }

    /** "2:05" from milliseconds: minutes and seconds, for timers. */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** "45 sec", "12 min", "1 h 5 min": how long something lasted, so it isn't read as a time. */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s sec"
            s < 3600 -> "${(s + 30) / 60} min"
            else -> "${s / 3600} h" + ((s / 60) % 60).let { if (it > 0) " $it min" else "" }
        }
    }

    /** "1 day before", "1 hour before", "30 minutes before". */
    fun before(minutes: Int): String = when {
        minutes % 1440 == 0 -> (minutes / 1440).let { "$it ${if (it == 1) "day" else "days"} before" }
        minutes % 60 == 0 -> (minutes / 60).let { "$it ${if (it == 1) "hour" else "hours"} before" }
        else -> "$minutes minutes before"
    }
}

/** A calendar to pick a day from. */
@Composable
fun DatePickDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val picker = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(picker) }
}

/** A clock to pick a time of day from. */
@Composable
fun TimePickDialog(initial: LocalTime, title: String, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val picker = rememberTimePickerState(initial.hour, initial.minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column { TimePicker(picker) } },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(picker.hour, picker.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A line of results over time with the person's own target range shaded behind it, so it's
 * plain at a glance whether a result is inside. [values] in order; [low]/[high] may be missing.
 */
@Composable
fun RangeChart(
    values: List<Double>,
    low: Double?,
    high: Double?,
    color: Color,
    band: Color,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val outside = MaterialTheme.colorScheme.error
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.isEmpty()) return@Canvas
        val all = values + listOfNotNull(low, high)
        var lo = all.min()
        var hi = all.max()
        if (hi - lo < 1e-6) {
            lo -= 1
            hi += 1
        }
        val pad = (hi - lo) * 0.15
        lo -= pad
        hi += pad
        fun y(v: Double) = (size.height - ((v - lo) / (hi - lo)).toFloat() * size.height).coerceIn(0f, size.height)
        val n = values.size
        fun x(i: Int) = if (n > 1) i * size.width / (n - 1) else size.width / 2
        if (low != null || high != null) {
            val top = y(high ?: hi)
            val bottom = y(low ?: lo)
            drawRect(band, Offset(0f, top), Size(size.width, bottom - top))
        }
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        if (n >= 2) {
            val line = Path()
            values.forEachIndexed { i, v -> if (i == 0) line.moveTo(x(i), y(v)) else line.lineTo(x(i), y(v)) }
            drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        values.forEachIndexed { i, v ->
            val out = (low != null && v < low) || (high != null && v > high)
            drawCircle(if (out) outside else color, 4.5.dp.toPx(), Offset(x(i), y(v)))
        }
    }
}
