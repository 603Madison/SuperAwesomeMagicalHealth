package com.health.app.reminders

import com.health.api.Appointment
import com.health.api.Medication
import com.health.api.Module
import com.health.api.ReminderPrefs
import com.health.api.ScheduleKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.roundToInt

/** What a reminder is for; each has its own notification channel. */
enum class AlarmKind {
    DOSE, DOSE_AGAIN, REFILL, HABIT, APPOINTMENT,
    /** Bladder training (the bladder and bowel diary). */
    BLADDER,
    /** Time to check or change what's being worn (the bladder and bowel diary). */
    CHANGE,
}

/** A habit with a reminder time, and whether it's done on [date] (the day its status was loaded). */
data class HabitReminder(val id: String, val name: String, val time: String, val date: String, val done: Boolean)

/**
 * A notification to show at [at]. [title] and [body] may name medications, habits and
 * appointments; [privateTitle] and [privateBody] never do (for a locked screen). [silent] is for
 * quiet hours, which never quiet a dose. [key] is unique for this one reminder.
 */
data class PlannedAlarm(
    val at: Long,
    val kind: AlarmKind,
    val key: String,
    val title: String,
    val body: String,
    val privateTitle: String,
    val privateBody: String,
    val silent: Boolean = false,
    /** For doses: which, so Taken and Snooze work (its [PlannedDose.at] is when it was due). */
    val dose: PlannedDose? = null,
)

/** Everything reminders are planned from, kept on the phone so they work offline. */
data class ReminderInputs(
    val meds: List<Medication> = emptyList(),
    /** Doses already taken or skipped, as "medicationId@scheduledFor". */
    val marked: Set<String> = emptySet(),
    val prefs: ReminderPrefs = ReminderPrefs(),
    val modules: Set<Module> = emptySet(),
    val habits: List<HabitReminder> = emptyList(),
    val appointments: List<Appointment> = emptyList(),
    /** Bladder training's settings (the bladder and bowel diary), when it's on. */
    val bladder: com.health.api.BladderTraining? = null,
    /** When a change is due, and when what's being worn went on (from the server, which allows for night): null for none. */
    val changeDueAt: Long? = null,
    val wornSince: Long? = null,
)

/**
 * Every reminder in plain Kotlin, so it can be tested without a phone: doses at their time and
 * again while they're not marked, refills when doses run low, habits not ticked by their time,
 * and appointments ahead of time. Quiet hours make all but doses silent.
 */
object CarePlanner {
    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE

    fun doseKey(medicationId: String, scheduledFor: Long) = "$medicationId@$scheduledFor"

    /** Every reminder in [from, until), soonest first. */
    fun plan(inputs: ReminderInputs, from: Long, until: Long, zone: ZoneId): List<PlannedAlarm> {
        if (until <= from) return emptyList()
        val out = mutableListOf<PlannedAlarm>()
        if (Module.MEDICATIONS in inputs.modules) {
            out += doses(inputs, from, until, zone)
            out += refills(inputs, from, until, zone)
        }
        if (Module.HABITS in inputs.modules && inputs.prefs.habits) out += habits(inputs, from, until, zone)
        if (Module.APPOINTMENTS in inputs.modules && inputs.prefs.appointments) out += appointments(inputs, from, until, zone)
        // Bladder training (the bladder and bowel diary).
        if (Module.CONTINENCE in inputs.modules) out += BladderTrainingPlan.alarms(inputs.bladder, from, until, zone) { quiet(inputs.prefs, it, zone) }
        // A check or change of what's being worn: the same discreet words everywhere, never the product's name.
        if (Module.CONTINENCE in inputs.modules) inputs.changeDueAt?.takeIf { it in from until until }?.let { at ->
            val hours = inputs.wornSince?.let { ((at - it) / (60 * MINUTE)).toInt() }?.takeIf { it > 0 }
            val body = hours?.let { "About $it ${if (it == 1) "hour" else "hours"} since your last change." } ?: "A good time for a check."
            out += PlannedAlarm(at, AlarmKind.CHANGE, "change:$at", "Time for a check", body, "Time for a check", body, silent = quiet(inputs.prefs, at, zone))
        }
        return out.sortedWith(compareBy({ it.at }, { it.kind.ordinal }, { it.key }))
    }

    /** The next moment after [now] that has reminders, and those reminders. */
    fun next(inputs: ReminderInputs, now: Long, zone: ZoneId, horizonDays: Long = 15): Pair<Long, List<PlannedAlarm>>? {
        val all = plan(inputs, now + 1, now + horizonDays * DAY, zone)
        val at = all.firstOrNull()?.at ?: return null
        return at to all.filter { it.at == at }
    }

    /** The reminders due at [at] (when an alarm goes off). */
    fun dueAt(inputs: ReminderInputs, at: Long, zone: ZoneId): List<PlannedAlarm> =
        plan(inputs, at, at + MINUTE, zone).filter { it.at == at }

    // ---- Doses ----

    private fun doses(inputs: ReminderInputs, from: Long, until: Long, zone: ZoneId): List<PlannedAlarm> {
        val again = inputs.prefs.remindAgainMinutes.coerceAtLeast(0)
        val times = if (again == 0) 0 else inputs.prefs.remindAgainTimes.coerceIn(0, 10)
        // Doses due a little before [from] can still be reminded again inside it.
        val due = ReminderPlanner.upcoming(inputs.meds, from - times * again * MINUTE, until, zone)
        val out = mutableListOf<PlannedAlarm>()
        // Doses due at the same moment share one reminder each, but their "private" text is the same.
        due.forEach { dose ->
            val key = doseKey(dose.medicationId, dose.at)
            if (key in inputs.marked) return@forEach
            val time = clock(dose.at, zone)
            if (dose.at in from until until) {
                out += PlannedAlarm(
                    at = dose.at, kind = AlarmKind.DOSE, key = "dose:$key",
                    title = "Time for ${dose.name}",
                    body = listOfNotNull(dose.dose, "due at $time").joinToString(" · "),
                    privateTitle = "Time for your $time medications",
                    privateBody = "Open the app to see what's due.",
                    dose = dose,
                )
            }
            for (n in 1..times) {
                val at = dose.at + n * again * MINUTE
                if (at in from until until) {
                    out += PlannedAlarm(
                        at = at, kind = AlarmKind.DOSE_AGAIN, key = "again$n:$key",
                        title = "Still to take: ${dose.name}",
                        body = listOfNotNull(dose.dose, "due at $time").joinToString(" · "),
                        privateTitle = "Still to take: your $time medications",
                        privateBody = "Mark it when you've taken it, or skip it.",
                        dose = dose,
                    )
                }
            }
        }
        return out
    }

    // ---- Refills ----

    /** Doses a day, on average over four weeks of its schedule (0 for as-needed). */
    fun perDay(med: Medication, zone: ZoneId, today: LocalDate): Double {
        if (med.schedule.kind == ScheduleKind.AS_NEEDED) return 0.0
        val count = (0L until 28L).sumOf { d -> ReminderPlanner.timesOn(med.copy(active = true), today.plusDays(d), zone).size }
        return count / 28.0
    }

    /** About how many days the doses left will last, or null if they aren't counted. */
    fun daysLeft(med: Medication, zone: ZoneId, today: LocalDate): Double? {
        val stock = med.stock ?: return null
        val rate = perDay(med, zone, today)
        if (rate <= 0) return null
        return stock / rate
    }

    private fun refills(inputs: ReminderInputs, from: Long, until: Long, zone: ZoneId): List<PlannedAlarm> {
        val within = inputs.prefs.refillDays
        if (within <= 0) return emptyList()
        val time = ReminderPlanner.parseTime(inputs.prefs.refillTime) ?: LocalTime.of(10, 0)
        val out = mutableListOf<PlannedAlarm>()
        var date = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(until).atZone(zone).toLocalDate()
        val today = date
        while (!date.isAfter(last)) {
            val at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
            if (at in from until until) {
                inputs.meds.filter { it.active && it.id != null }.forEach { med ->
                    val rate = perDay(med, zone, today)
                    val stockNow = med.stock ?: return@forEach
                    if (rate <= 0) return@forEach
                    // Stock goes down as doses are taken: what's likely left by that day.
                    val left = (stockNow - rate * ChronoUnit.DAYS.between(today, date)).coerceAtLeast(0.0)
                    val days = left / rate
                    if (days > within) return@forEach
                    val doses = floor(left).toInt()
                    val (title, body) = if (doses <= 0) {
                        "Out of ${med.name}" to "Time to get more."
                    } else {
                        "Running low on ${med.name}" to "About ${days.roundToInt().coerceAtLeast(1)} ${plural(days.roundToInt().coerceAtLeast(1), "day")} left. Time to refill."
                    }
                    out += PlannedAlarm(
                        at = at, kind = AlarmKind.REFILL, key = "refill:${med.id}:$date",
                        title = title, body = body,
                        privateTitle = "Time to refill a medication", privateBody = "One of your medications is running low.",
                        silent = quiet(inputs.prefs, at, zone),
                    )
                }
            }
            date = date.plusDays(1)
        }
        return out
    }

    // ---- Habits ----

    private fun habits(inputs: ReminderInputs, from: Long, until: Long, zone: ZoneId): List<PlannedAlarm> {
        val out = mutableListOf<PlannedAlarm>()
        var date = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(until).atZone(zone).toLocalDate()
        while (!date.isAfter(last)) {
            inputs.habits.forEach { h ->
                val time = ReminderPlanner.parseTime(h.time) ?: return@forEach
                // Done on the day its status was loaded; later days aren't known yet, so they remind.
                if (h.done && h.date == date.toString()) return@forEach
                if (h.date > date.toString()) return@forEach
                val at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                if (at in from until until) {
                    out += PlannedAlarm(
                        at = at, kind = AlarmKind.HABIT, key = "habit:${h.id}:$date",
                        title = h.name, body = "A gentle nudge: it isn't ticked yet today.",
                        privateTitle = "A habit isn't ticked yet", privateBody = "A gentle nudge for today.",
                        silent = quiet(inputs.prefs, at, zone),
                    )
                }
            }
            date = date.plusDays(1)
        }
        return out
    }

    // ---- Appointments ----

    private fun appointments(inputs: ReminderInputs, from: Long, until: Long, zone: ZoneId): List<PlannedAlarm> =
        inputs.appointments.filter { it.id != null }.flatMap { a ->
            a.remindBefore.filter { it > 0 }.distinct().mapNotNull { minutes ->
                val at = a.at - minutes * MINUTE
                if (at !in from until until) return@mapNotNull null
                val whenText = appointmentWhen(a.at, at, zone)
                PlannedAlarm(
                    at = at, kind = AlarmKind.APPOINTMENT, key = "appt:${a.id}:$minutes",
                    title = listOfNotNull(a.title, a.with?.takeIf { it.isNotBlank() && !a.title.contains(it, true) }?.let { "with $it" }).joinToString(" ") + " $whenText",
                    body = a.place?.takeIf { it.isNotBlank() } ?: "Your questions are in the app.",
                    privateTitle = "An appointment $whenText", privateBody = "Open the app for the details.",
                    silent = quiet(inputs.prefs, at, zone),
                )
            }
        }

    /** "in 1 hour", "tomorrow at 9:30", "on Friday at 14:00", from the moment [now]. */
    fun appointmentWhen(at: Long, now: Long, zone: ZoneId): String {
        val minutes = ((at - now) / MINUTE).toInt()
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = clock(at, zone)
        return when {
            minutes < 90 -> "in ${minutes.coerceAtLeast(1)} ${plural(minutes.coerceAtLeast(1), "minute")}".let { if (minutes in 55..65) "in 1 hour" else it }
            day == today -> "today at $time"
            day == today.plusDays(1) -> "tomorrow at $time"
            ChronoUnit.DAYS.between(today, day) < 7 -> "on ${day.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)} at $time"
            else -> "on ${DateTimeFormatter.ofPattern("d MMM").format(day)} at $time"
        }
    }

    // ---- Quiet hours ----

    /** Whether [at] falls in the person's quiet hours (which may run past midnight). */
    fun quiet(prefs: ReminderPrefs, at: Long, zone: ZoneId): Boolean {
        val from = prefs.quietFrom?.let(ReminderPlanner::parseTime) ?: return false
        val to = prefs.quietTo?.let(ReminderPlanner::parseTime) ?: return false
        if (from == to) return false
        val t = Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
        return if (from < to) !t.isBefore(from) && t.isBefore(to) else !t.isBefore(from) || t.isBefore(to)
    }

    private fun clock(at: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("H:mm").format(Instant.ofEpochMilli(at).atZone(zone))

    private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"
}
