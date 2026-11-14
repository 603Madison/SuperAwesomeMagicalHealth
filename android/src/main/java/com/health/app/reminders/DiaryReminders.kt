package com.health.app.reminders

import com.health.api.BladderTraining
import com.health.app.attempt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Bladder training, as the server plans it (modules/DiaryHooks.kt, BladderTrainingPlan): a trip
 * every so often while awake, the gap stretched a step every so many days up to the goal. The
 * phone plans the same reminders itself, so they come offline too; unless the person shows names
 * in reminders, they say only "Time for your planned break", as the server's do.
 */
object BladderTrainingPlan {
    /** The interval on [date]: the start, plus a step every so many days, up to the goal. */
    fun interval(t: BladderTraining, date: LocalDate): Int {
        val started = t.startedOn?.let { attempt { LocalDate.parse(it) }.getOrNull() } ?: date
        val steps = (ChronoUnit.DAYS.between(started, date).coerceAtLeast(0) / t.stepEveryDays.coerceAtLeast(1)).toInt()
        return (t.intervalMinutes + steps * t.stepMinutes).coerceAtMost(maxOf(t.goalMinutes, t.intervalMinutes))
    }

    /** Reminder times in [from, until), each with that day's interval: one interval after the day's start, then every interval to its end. */
    fun times(t: BladderTraining, from: Long, until: Long, zone: ZoneId): List<Pair<Long, Int>> {
        if (!t.enabled || until <= from) return emptyList()
        val start = ReminderPlanner.parseTime(t.from) ?: return emptyList()
        val end = ReminderPlanner.parseTime(t.to) ?: return emptyList()
        val out = mutableListOf<Pair<Long, Int>>()
        var date = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(until).atZone(zone).toLocalDate()
        while (!date.isAfter(last)) {
            val interval = interval(t, date).coerceAtLeast(15)
            var minute = start.toSecondOfDay() / 60 + interval
            while (minute <= end.toSecondOfDay() / 60) {
                val at = date.atTime(LocalTime.ofSecondOfDay(minute * 60L)).atZone(zone).toInstant().toEpochMilli()
                if (at in from until until) out += at to interval
                minute += interval
            }
            date = date.plusDays(1)
        }
        return out
    }

    /** "hour", "2 hours", "1 h 15 min", "45 minutes": as the server says it. */
    fun say(minutes: Int): String = when {
        minutes % 60 == 0 -> if (minutes == 60) "hour" else "${minutes / 60} hours"
        minutes > 60 -> "${minutes / 60} h ${minutes % 60} min"
        else -> "$minutes minutes"
    }

    /** "Every 1 h 15 min today, 8:00 to 21:00": for the diary's card. */
    fun today(t: BladderTraining, date: LocalDate): String {
        return "Every ${say(interval(t, date))} today, ${t.from} to ${t.to}"
    }

    /** The reminders between [from] and [until], as the phone shows them. */
    fun alarms(t: BladderTraining?, from: Long, until: Long, zone: ZoneId, quiet: (Long) -> Boolean): List<PlannedAlarm> {
        if (t == null || !t.enabled) return emptyList()
        return times(t, from, until, zone).map { (at, interval) ->
            val local = Instant.ofEpochMilli(at).atZone(zone)
            PlannedAlarm(
                at = at, kind = AlarmKind.BLADDER, key = "bladder:${local.toLocalDate()}:${local.toLocalTime()}",
                title = "Time for a toilet trip",
                body = "Bladder training: every ${say(interval)} today. Going now, even if you don't feel you need to, keeps the rhythm.",
                privateTitle = "Reminder",
                privateBody = "Time for your planned break.",
                silent = quiet(at),
            )
        }
    }
}
