package com.health.server.care

import com.health.api.Appointment
import com.health.api.AppointmentQuestion
import com.health.api.DigestSection
import com.health.api.Insight
import com.health.api.Module
import com.health.api.Profile
import com.health.api.ReminderAction
import com.health.api.ReportSectionKey
import com.health.api.SuggestedQuestion
import com.health.api.WebhookEvent
import com.health.server.ApiError
import com.health.server.health.ProfileStore
import com.health.server.modules.EntryKind
import com.health.server.modules.EntryStore
import com.health.server.platform.FeatureHooks
import com.health.server.platform.Reminder
import com.health.server.platform.ReportSection
import com.health.server.platform.ReportTable
import com.health.server.util.Days
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Appointments: when and with whom, questions to ask (the person's own, and ones suggested from
 * what they've logged since the last appointment), notes afterwards, and reminders before.
 * Sealed like every sensitive entry: where someone goes can say a lot about their health.
 */
class AppointmentsService(
    private val entries: EntryStore,
    private val profiles: ProfileStore,
    /** Every feature's hooks, for suggested questions. */
    private val hooks: () -> List<FeatureHooks>,
    private val clock: () -> Long = System::currentTimeMillis,
) : FeatureHooks {
    override val module = Module.APPOINTMENTS

    private suspend fun between(userId: String, from: LocalDate, to: LocalDate): List<Appointment> =
        entries.list(userId, EntryKind.APPOINTMENT, from, to, Appointment.serializer()).map { it.value.copy(id = it.id, date = it.date.toString()) }.sortedBy { it.at }

    /** With [upcoming], those from now to [days] ahead; otherwise the last [days] and everything ahead. */
    suspend fun list(userId: String, days: Int, upcoming: Boolean): List<Appointment> {
        val zone = profiles.days(userId)
        val today = zone.today(clock())
        val span = days.coerceIn(1, 3650).toLong()
        return if (upcoming) {
            between(userId, today, today.plusDays(span)).filter { it.at + (it.durationMinutes ?: 0) * 60_000L >= clock() }
        } else {
            between(userId, today.minusDays(span), today.plusYears(5))
        }
    }

    suspend fun get(userId: String, id: String): Appointment =
        entries.get(userId, EntryKind.APPOINTMENT, id, Appointment.serializer())?.let { it.value.copy(id = it.id, date = it.date.toString()) }
            ?: throw ApiError.notFound("No such appointment")

    suspend fun save(userId: String, appointment: Appointment, id: String? = null): Appointment {
        val clean = validated(appointment)
        val date = profiles.days(userId).dateOf(clean.at)
        val saved = entries.put(userId, EntryKind.APPOINTMENT, id, date, clean.at, clean, Appointment.serializer())
        return clean.copy(id = saved, date = date.toString())
    }

    suspend fun delete(userId: String, id: String) {
        entries.delete(userId, EntryKind.APPOINTMENT, id)
    }

    /**
     * Questions worth asking at [id], from what was logged since the last appointment before it
     * (or the last 30 days), by every feature the person has on. Ones already on the list are left out.
     * Someone viewing a share ([visible]: the features shared with them) gets only questions from
     * those features: never ones built from anything else.
     */
    suspend fun suggestions(userId: String, id: String, visible: Set<com.health.api.Module>? = null): List<SuggestedQuestion> {
        val appointment = get(userId, id)
        val profile = profiles.get(userId)
        val zone = Days(Days.zoneOf(profile.timezone))
        val today = zone.today(clock())
        val day = LocalDate.parse(appointment.date)
        val to = minOf(today, day)
        val previous = between(userId, to.minusYears(2), day).filter { it.at < appointment.at && it.id != id && it.at < clock() }.maxByOrNull { it.at }
        val since = previous?.date?.let(LocalDate::parse)?.takeIf { it.isBefore(to) } ?: to.minusDays(30)
        val asked = appointment.questions.map { it.text.lowercase() }.toSet()
        fun shown(module: com.health.api.Module?) = if (visible == null) module == null || module in profile.modules else module != null && module in visible
        return hooks().filter { shown(it.module) }
            .flatMap { hook -> runCatching { hook.questions(userId, profile, since, to) }.getOrDefault(emptyList()) }
            .filter { visible == null || shown(it.module) }
            .distinctBy { it.text.lowercase() }
            .filter { it.text.lowercase() !in asked }
            .take(20)
    }

    /** Today's card (the next seven days) and an insight for an appointment today or tomorrow. */
    suspend fun today(userId: String, date: LocalDate, timezone: String): Pair<List<Appointment>, List<Pair<Int, Insight>>> {
        val zone = Days(Days.zoneOf(timezone))
        val now = clock()
        val list = between(userId, date, date.plusDays(7)).filter { it.at + (it.durationMinutes ?: 60) * 60_000L >= now }
        val insights = mutableListOf<Pair<Int, Insight>>()
        list.firstOrNull { val d = zone.dateOf(it.at); d == date || d == date.plusDays(1) }?.let { a ->
            val day = if (zone.dateOf(a.at) == date) "today" else "tomorrow"
            val open = a.questions.count { !it.done }
            insights += 58 to Insight(
                "info", "${a.title} $day at ${time(zone, a.at)}",
                listOfNotNull(a.with?.let { "With $it" }, a.place?.let { "at $it" }).joinToString(" ").ifEmpty { null }.let { where ->
                    listOfNotNull(where?.let { "$it." }, if (open > 0) "${plural(open, "question")} ready to ask." else "Add the questions you want to ask.").joinToString(" ")
                },
                "calendar", Module.APPOINTMENTS,
                // Today's, the apps show in Up next, so they leave this out while it does.
                shownBy = if (day == "today") com.health.api.TodaySection.APPOINTMENTS else null,
            )
        }
        return list to insights
    }

    // ---- Hooks ----

    override suspend fun reminders(userId: String, profile: Profile, from: Long, to: Long): List<Reminder> {
        if (!profile.reminders.appointments) return emptyList()
        val zone = Days(Days.zoneOf(profile.timezone))
        val list = between(userId, zone.dateOf(from).minusDays(1), zone.dateOf(to).plusDays(15))
        return list.flatMap { a ->
            a.remindBefore.mapNotNull { minutes ->
                val at = a.at - minutes * 60_000L
                if (at < from || at >= to || a.at < from) return@mapNotNull null
                val whenText = "${relative(minutes)}, at ${time(zone, a.at)}"
                val details = buildJsonObject {
                    put("title", a.title); a.with?.let { put("with", it) }; a.place?.let { put("place", it) }
                    put("at", a.at); put("minutesBefore", minutes)
                }
                Reminder(
                    key = "appointment:${a.id}:${a.at}:$minutes",
                    at = at,
                    event = WebhookEvent.APPOINTMENT_SOON,
                    title = a.title,
                    body = listOfNotNull(whenText, a.with?.let { "with $it" }, a.place?.let { "at $it" }).joinToString(" "),
                    privateTitle = "Appointment coming up",
                    privateBody = whenText.replaceFirstChar { it.uppercase() },
                    url = "/appointments",
                    actions = listOf(ReminderAction("open", "Open")),
                    data = buildJsonObject { put("at", a.at); put("minutesBefore", minutes) },
                    detailedData = details,
                )
            }
        }
    }

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val zone = Days(Days.zoneOf(profile.timezone))
        val past = between(userId, from, to)
        val next = between(userId, to.plusDays(1), to.plusDays(7))
        if (past.isEmpty() && next.isEmpty()) return null
        val lines = mutableListOf<String>()
        past.forEach { lines += "${it.title}${it.with?.let { w -> " with $w" } ?: ""} on ${dayText(zone, it.at)}." }
        next.forEach { a ->
            val open = a.questions.count { !it.done }
            lines += "Coming up: ${a.title} on ${dayText(zone, a.at)} at ${time(zone, a.at)}${if (open > 0) " (${plural(open, "question")} ready)" else ""}."
        }
        return DigestSection("Appointments", Module.APPOINTMENTS, "info", lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection? {
        val zone = Days(Days.zoneOf(profile.timezone))
        val list = between(userId, from, to.plusDays(90))
        if (list.isEmpty()) return null
        val past = list.filter { zone.dateOf(it.at) <= to }
        val next = list.filter { zone.dateOf(it.at) > to }
        val summary = listOfNotNull(
            "${plural(past.size, "appointment")} in this period.",
            next.firstOrNull()?.let { "Next: ${it.title} on ${longDate(zone.dateOf(it.at).toString())}." },
        )
        val open = (next.ifEmpty { past.takeLast(1) }).flatMap { a -> a.questions.filter { !it.done }.map { a.title to it.text } }
        return ReportSection(
            ReportSectionKey.APPOINTMENTS, summary,
            listOfNotNull(
                ReportTable("Appointments", listOf("Date", "What", "With", "Notes"), past.map { listOf(zone.dateOf(it.at).toString(), it.title, it.with.orEmpty(), it.notes.orEmpty()) }).takeIf { it.rows.isNotEmpty() },
                ReportTable("Questions to ask", listOf("For", "Question"), open.map { listOf(it.first, it.second) }).takeIf { it.rows.isNotEmpty() },
            ),
        )
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val zone = Days(Days.zoneOf(profile.timezone))
        val rows = between(userId, from, to).map { a ->
            listOf(
                Instant.ofEpochMilli(a.at).atZone(zone.zone).toLocalDateTime().toString(), a.title, a.durationMinutes?.toString().orEmpty(), a.with.orEmpty(), a.place.orEmpty(),
                a.questions.joinToString(" | ") { (if (it.done) "[x] " else "[ ] ") + it.text }, a.notes.orEmpty(),
            )
        }
        return if (rows.isEmpty()) emptyMap() else mapOf("appointments.csv" to listOf(listOf("time", "title", "minutes", "with", "place", "questions", "notes")) + rows)
    }

    companion object {
        fun validated(a: Appointment): Appointment {
            val title = a.title.trim().take(120).ifBlank { throw ApiError.badRequest("An appointment needs a title") }
            a.durationMinutes?.let { if (it !in 1..1440) throw ApiError.badRequest("An appointment lasts 1 minute to a day") }
            if (a.questions.size > 50) throw ApiError.badRequest("Up to 50 questions")
            if (a.remindBefore.any { it !in 0..20_160 }) throw ApiError.badRequest("Reminders are up to two weeks before")
            return a.copy(
                title = title,
                with = a.with?.trim()?.take(120)?.ifBlank { null },
                place = a.place?.trim()?.take(200)?.ifBlank { null },
                questions = a.questions.map { AppointmentQuestion(it.text.trim().take(300), it.done, it.suggested) }.filter { it.text.isNotEmpty() },
                notes = a.notes?.trim()?.take(10_000)?.ifBlank { null },
                remindBefore = a.remindBefore.distinct().sortedDescending().take(5),
                id = null,
                date = null,
            )
        }

        private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

        fun time(zone: Days, at: Long): String = Instant.ofEpochMilli(at).atZone(zone.zone).format(TIME)
        fun dayText(zone: Days, at: Long): String = Instant.ofEpochMilli(at).atZone(zone.zone).format(DAY)

        /** "in an hour", "tomorrow", "in 2 days". */
        fun relative(minutes: Int): String = when {
            minutes <= 0 -> "now"
            minutes < 60 -> "in $minutes minutes"
            minutes == 60 -> "in an hour"
            minutes < 1440 -> "in ${minutes / 60} hours"
            minutes == 1440 -> "tomorrow"
            else -> "in ${minutes / 1440} days"
        }
    }
}
