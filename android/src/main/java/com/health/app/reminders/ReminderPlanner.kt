package com.health.app.reminders

import com.health.api.MedForm
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.ScheduleKind
import com.health.app.attempt
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A dose a medication is due for, at [at]. */
data class PlannedDose(
    val medicationId: String,
    val name: String,
    val dose: String?,
    val form: MedForm,
    val at: Long,
)

/**
 * When medications are due, worked out from their schedules: plain Kotlin, so the reminders and the
 * screens agree and it can be tested without a phone.
 */
object ReminderPlanner {
    /** "08:00", "8:30", "20:15" as a time of day, or null. */
    fun parseTime(text: String): LocalTime? {
        val parts = text.trim().split(':')
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }

    /** A time of day as the schedule stores it: "08:05". */
    fun formatTime(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    /** Whether a dose is due at all on [date] (before any times). */
    fun isDueOn(schedule: MedSchedule, date: LocalDate): Boolean {
        val start = schedule.startDate?.let { attempt { LocalDate.parse(it) }.getOrNull() }
        if (start != null && date.isBefore(start)) return false
        return when (schedule.kind) {
            ScheduleKind.DAILY -> true
            ScheduleKind.WEEKLY -> {
                val days = schedule.weekdays.filter { it in 1..7 }.ifEmpty { listOfNotNull(start?.dayOfWeek?.value) }
                date.dayOfWeek.value in days
            }
            ScheduleKind.EVERY_N_DAYS -> {
                val every = schedule.everyDays.coerceAtLeast(1).toLong()
                val from = start ?: LocalDate.ofEpochDay(0)
                ChronoUnit.DAYS.between(from, date) % every == 0L
            }
            ScheduleKind.AS_NEEDED -> false
        }
    }

    /** The moments [med] is due on [date], in [zone], earliest first (none if it's off or as needed). */
    fun timesOn(med: Medication, date: LocalDate, zone: ZoneId): List<Long> {
        if (!med.active || !isDueOn(med.schedule, date)) return emptyList()
        return med.schedule.times.mapNotNull(::parseTime).distinct().sorted()
            .map { date.atTime(it).atZone(zone).toInstant().toEpochMilli() }
    }

    /** Every dose due in [from, until), soonest first; with [remindersOnly], only medications with reminders on. */
    fun upcoming(meds: List<Medication>, from: Long, until: Long, zone: ZoneId, remindersOnly: Boolean = true): List<PlannedDose> {
        if (until <= from) return emptyList()
        val first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(until).atZone(zone).toLocalDate()
        val out = mutableListOf<PlannedDose>()
        var date = first
        while (!date.isAfter(last)) {
            meds.filter { it.id != null && (!remindersOnly || it.reminders) }.forEach { med ->
                timesOn(med, date, zone).filter { it in from until until }.forEach { at ->
                    out += PlannedDose(med.id!!, med.name, med.dose, med.form, at)
                }
            }
            date = date.plusDays(1)
        }
        return out.sortedWith(compareBy({ it.at }, { it.name }))
    }

    /** The next moment after [now] that has reminders, and the doses due then; null if none within [horizonDays]. */
    fun next(meds: List<Medication>, now: Long, zone: ZoneId, horizonDays: Long = 15): Pair<Long, List<PlannedDose>>? {
        val doses = upcoming(meds, now + 1, now + horizonDays * DAY_MS, zone)
        val at = doses.firstOrNull()?.at ?: return null
        return at to doses.filter { it.at == at }
    }

    /** The doses due at exactly [at] (when an alarm goes off). */
    fun dueAt(meds: List<Medication>, at: Long, zone: ZoneId): List<PlannedDose> =
        upcoming(meds, at, at + 60_000, zone).filter { it.at == at }

    /** "Every day at 8:00", "Mon, Thu at 20:00", "Every 5 days at 9:00", "As needed". */
    fun describe(schedule: MedSchedule): String {
        val times = schedule.times.mapNotNull(::parseTime).sorted().joinToString(", ") { "%d:%02d".format(it.hour, it.minute) }
        val at = if (times.isEmpty()) "" else " at $times"
        return when (schedule.kind) {
            ScheduleKind.DAILY -> "Every day$at"
            ScheduleKind.WEEKLY -> {
                val days = schedule.weekdays.filter { it in 1..7 }.sorted()
                    .joinToString(", ") { DayOfWeek.of(it).name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
                (if (days.isEmpty()) "Every week" else days) + at
            }
            ScheduleKind.EVERY_N_DAYS -> (if (schedule.everyDays <= 1) "Every day" else "Every ${schedule.everyDays} days") + at
            ScheduleKind.AS_NEEDED -> "As needed"
        }
    }

    private const val DAY_MS = 24L * 3600 * 1000
}
