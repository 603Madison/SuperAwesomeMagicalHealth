package com.health.server.modules

import com.health.api.ApiJson
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.DueDose
import com.health.api.LabResult
import com.health.api.MedForm
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.MedsDay
import com.health.api.ScheduleKind
import com.health.server.ApiError
import com.health.server.db.Database
import com.health.server.db.Tx
import com.health.server.health.ProfileStore
import com.health.server.util.Days
import com.health.server.util.Random
import com.health.server.util.SecretBox
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Medications and supplements, their doses, and lab results. Medications are sealed like entries. */
class MedsService(
    private val db: Database,
    private val box: SecretBox,
    private val entries: EntryStore,
    private val profiles: ProfileStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private fun context(userId: String, id: String) = "medication:$userId:$id"

    /** Told of every saved medication, with how it was before (null for a new one): hormone therapy keeps a dose history. */
    var onChanged: (suspend (userId: String, before: Medication?, after: Medication) -> Unit)? = null

    /** A medication as kept, with when it was added. */
    private class Row(val med: Medication, val created: Long)

    private fun open(userId: String, id: String, data: String): Medication? =
        runCatching { ApiJson.decodeFromString(Medication.serializer(), box.open(data, context(userId, id))).copy(id = id) }.getOrNull()

    private suspend fun rows(userId: String, all: Boolean): List<Row> = db.tx {
        query("SELECT id, data, created_at FROM medications WHERE user_id = ?${if (all) "" else " AND active = 1"} ORDER BY created_at", userId) {
            Triple(it.string("id"), it.string("data"), it.long("created_at"))
        }
    }.mapNotNull { (id, data, created) -> open(userId, id, data)?.let { Row(it, created) } }

    suspend fun list(userId: String, all: Boolean = false): List<Medication> = rows(userId, all).map { it.med }

    /** Each medication with the first day its doses count (see [Schedules.firstDay]): for what's due, adherence and reminders. */
    suspend fun scheduled(userId: String, all: Boolean = false): List<ScheduledMed> {
        val zone = profiles.days(userId)
        return rows(userId, all).map { ScheduledMed(it.med, Schedules.firstDay(it.med, zone.dateOf(it.created))) }
    }

    suspend fun get(userId: String, id: String): Medication = list(userId, all = true).firstOrNull { it.id == id } ?: throw ApiError.notFound("No such medication")

    suspend fun save(userId: String, med: Medication, id: String? = null): Medication {
        var clean = validated(med)
        val medId = id ?: Random.id()
        val zone = profiles.days(userId)
        val existing = if (id != null) rows(userId, all = true).firstOrNull { it.med.id == id } else null
        val before = existing?.med
        // Every few days needs a day to count from: the one it already counted from, or today.
        if (clean.schedule.kind == ScheduleKind.EVERY_N_DAYS && clean.schedule.startDate == null) {
            val counted = existing?.takeIf { it.med.schedule.kind == ScheduleKind.EVERY_N_DAYS && it.med.schedule.startDate == null }
                ?.let { Schedules.firstDay(it.med, zone.dateOf(it.created)) }
            clean = clean.copy(schedule = clean.schedule.copy(startDate = (counted ?: zone.today(clock())).toString()))
        }
        val sealed = box.seal(ApiJson.encodeToString(Medication.serializer(), clean.copy(id = null)), context(userId, medId))
        db.tx {
            if (id != null) {
                if (update("UPDATE medications SET data = ?, active = ?, updated_at = ? WHERE id = ? AND user_id = ?", sealed, clean.active, clock(), id, userId) == 0) {
                    throw ApiError.notFound("No such medication")
                }
            } else {
                update("INSERT INTO medications (id, user_id, data, active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)", medId, userId, sealed, clean.active, clock(), clock())
            }
        }
        onChanged?.invoke(userId, before, clean.copy(id = medId))
        return clean.copy(id = medId)
    }

    suspend fun delete(userId: String, id: String) {
        if (db.tx { update("DELETE FROM medications WHERE id = ? AND user_id = ?", id, userId) } == 0) throw ApiError.notFound("No such medication")
    }

    // ---- Doses ----

    suspend fun doses(userId: String, from: LocalDate, to: LocalDate): List<DoseLog> =
        entries.list(userId, EntryKind.DOSE, from, to, DoseLog.serializer()).map { it.value.copy(id = it.id, date = it.date.toString()) }

    /**
     * Marks a dose. The mark, any earlier mark it replaces, and the stock count change together in
     * one transaction, so doses marked at the same moment (from a watch and a phone) all count.
     */
    suspend fun logDose(userId: String, dose: DoseLog): DoseLog {
        val clean = dose.copy(site = dose.site?.trim()?.take(60)?.ifBlank { null }, note = dose.note?.trim()?.take(1000)?.ifBlank { null }, id = null, date = null)
        val date = profiles.days(userId).dateOf(clean.scheduledFor ?: clean.at)
        val id = db.tx {
            val med = medication(this, userId, clean.medicationId) ?: throw ApiError.notFound("No such medication")
            var stockBy = 0
            // Marking a scheduled dose again replaces the earlier mark.
            clean.scheduledFor?.let { slot ->
                entries.list(this, userId, EntryKind.DOSE, date.minusDays(1), date.plusDays(1), DoseLog.serializer())
                    .filter { it.value.medicationId == clean.medicationId && it.value.scheduledFor == slot }
                    .forEach { old ->
                        entries.delete(this, userId, EntryKind.DOSE, old.id)
                        if (old.value.status == DoseStatus.TAKEN) stockBy++
                    }
            }
            val saved = entries.put(this, userId, EntryKind.DOSE, null, date, clean.at, clean, DoseLog.serializer())
            if (clean.status == DoseStatus.TAKEN) stockBy--
            adjustStock(this, userId, med, stockBy)
            saved
        }
        return clean.copy(id = id, date = date.toString())
    }

    suspend fun deleteDose(userId: String, id: String) {
        db.tx {
            val dose = entries.get(this, userId, EntryKind.DOSE, id, DoseLog.serializer()) ?: throw ApiError.notFound("No such dose")
            entries.delete(this, userId, EntryKind.DOSE, id)
            if (dose.value.status == DoseStatus.TAKEN) medication(this, userId, dose.value.medicationId)?.let { adjustStock(this, userId, it, +1) }
        }
    }

    /** A medication, read inside a transaction (null if there's none, or it can't be opened). */
    private fun medication(tx: Tx, userId: String, id: String): Medication? =
        tx.one("SELECT data FROM medications WHERE id = ? AND user_id = ?", id, userId) { it.string("data") }?.let { open(userId, id, it) }

    /** Changes a counted medication's stock, inside the transaction that read [med]. */
    private fun adjustStock(tx: Tx, userId: String, med: Medication, by: Int) {
        val stock = med.stock ?: return
        if (by == 0) return
        val next = med.copy(stock = (stock + by).coerceAtLeast(0), id = null)
        tx.update(
            "UPDATE medications SET data = ?, updated_at = ? WHERE id = ? AND user_id = ?",
            box.seal(ApiJson.encodeToString(Medication.serializer(), next), context(userId, med.id!!)), clock(), med.id, userId,
        )
    }

    /** The day's scheduled doses, what happened to each, and the next site for injections and patches. */
    suspend fun day(userId: String, date: LocalDate): MedsDay {
        val zone = profiles.days(userId)
        val scheduled = scheduled(userId)
        val meds = scheduled.map { it.med }
        val logs = doses(userId, date.minusDays(120), date.plusDays(1))
        val today = logs.filter { it.date == date.toString() }
        val due = scheduled.flatMap { s ->
            val med = s.med
            s.times(date, zone).map { slot ->
                DueDose(
                    medicationId = med.id!!, name = med.name, dose = med.dose, form = med.form, scheduledFor = slot,
                    log = today.firstOrNull { it.medicationId == med.id && it.scheduledFor == slot },
                    nextSite = nextSite(med, logs),
                )
            }
        }.sortedBy { it.scheduledFor }
        val scheduledIds = due.mapNotNull { it.log?.id }.toSet()
        return MedsDay(
            date = date.toString(),
            due = due,
            asNeeded = meds.filter { it.schedule.kind == ScheduleKind.AS_NEEDED },
            extra = today.filter { it.id !in scheduledIds },
            adherence7d = adherence(scheduled, logs, date, zone),
            lowStock = meds.filter { m -> m.stock != null && m.stock!! <= maxOf(5, Schedules.perWeek(m.schedule)) },
        )
    }

    private fun adherence(meds: List<ScheduledMed>, logs: List<DoseLog>, date: LocalDate, zone: Days): Int? {
        val now = clock()
        var due = 0
        var taken = 0
        for (back in 0L until 7L) {
            val day = date.minusDays(back)
            meds.forEach { s ->
                s.times(day, zone).filter { it <= now }.forEach { slot ->
                    due++
                    if (logs.any { it.medicationId == s.med.id && it.scheduledFor == slot && it.status == DoseStatus.TAKEN }) taken++
                }
            }
        }
        return if (due == 0) null else (taken * 100.0 / due).roundToInt()
    }

    // ---- Labs ----

    suspend fun labs(userId: String, count: Int): List<LabResult> {
        val zone = profiles.days(userId)
        val to = zone.today(clock()).plusDays(1)
        return entries.list(userId, EntryKind.LAB, to.minusDays(count.coerceIn(1, 36500).toLong()), to, LabResult.serializer())
            .map { it.value.copy(id = it.id, date = it.date.toString()) }.reversed()
    }

    suspend fun saveLab(userId: String, lab: LabResult): LabResult {
        if (lab.name.isBlank() || !lab.value.isFinite()) throw ApiError.badRequest("A lab result needs a name and a value")
        val clean = lab.copy(name = lab.name.trim().take(80), unit = lab.unit.trim().take(30), note = lab.note?.trim()?.take(1000)?.ifBlank { null }, id = null, date = null)
        val date = profiles.days(userId).dateOf(clean.at)
        val id = entries.put(userId, EntryKind.LAB, null, date, clean.at, clean, LabResult.serializer())
        return clean.copy(id = id, date = date.toString())
    }

    suspend fun deleteLab(userId: String, id: String) {
        entries.delete(userId, EntryKind.LAB, id)
    }

    companion object {
        fun validated(med: Medication): Medication {
            val name = med.name.trim().take(120).ifBlank { throw ApiError.badRequest("A medication needs a name") }
            val s = med.schedule
            val times = s.times.map { t ->
                runCatching { LocalTime.parse(t.trim()) }.getOrElse { throw ApiError.badRequest("Times look like 08:00") }
            }.distinct().sorted().map { "%02d:%02d".format(it.hour, it.minute) }
            if (s.kind != ScheduleKind.AS_NEEDED && times.isEmpty()) throw ApiError.badRequest("Give at least one time to take it")
            if (s.kind == ScheduleKind.EVERY_N_DAYS && s.everyDays !in 1..90) throw ApiError.badRequest("Every 1 to 90 days")
            if (s.kind == ScheduleKind.WEEKLY && s.weekdays.any { it !in 1..7 }) throw ApiError.badRequest("Weekdays are 1 (Monday) to 7 (Sunday)")
            val startDate = s.startDate?.trim()?.ifBlank { null }
            startDate?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw ApiError.badRequest("Dates look like 2026-01-31") } }
            return med.copy(
                name = name,
                dose = med.dose?.trim()?.take(80)?.ifBlank { null },
                purpose = med.purpose?.trim()?.take(200)?.ifBlank { null },
                notes = med.notes?.trim()?.take(2000)?.ifBlank { null },
                sites = med.sites.map { it.trim().take(60) }.filter { it.isNotEmpty() }.distinct().take(20),
                stock = med.stock?.coerceIn(0, 100_000),
                schedule = s.copy(times = times, weekdays = s.weekdays.distinct().sorted(), startDate = startDate),
                startedOn = med.startedOn?.trim()?.ifBlank { null }?.also {
                    runCatching { LocalDate.parse(it) }.getOrElse { throw ApiError.badRequest("Dates look like 2026-01-31") }
                },
                id = null,
            )
        }

        /** The injection or patch site used longest ago (or never), to go next. */
        fun nextSite(med: Medication, logs: List<DoseLog>): String? {
            if (med.sites.isEmpty() || (med.form != MedForm.INJECTION && med.form != MedForm.PATCH && med.form != MedForm.GEL)) return null
            val last = logs.filter { it.medicationId == med.id && it.status == DoseStatus.TAKEN && it.site != null }
                .groupBy { it.site!! }.mapValues { (_, l) -> l.maxOf { it.at } }
            return med.sites.minByOrNull { last[it] ?: Long.MIN_VALUE }
        }
    }
}

/** A medication and the first day its doses count (see [Schedules.firstDay]). */
class ScheduledMed(val med: Medication, val since: LocalDate) {
    fun times(date: LocalDate, zone: Days): List<Long> = Schedules.times(med.schedule, date, zone, since)
}

/** When a schedule's doses fall on a day. Pure, so reminders and the server agree. */
object Schedules {
    /**
     * The doses on [date]. None before the schedule's start date or [since] (the first day the
     * medication's doses count); every few days counts from the start date, or else from [since].
     */
    fun times(schedule: MedSchedule, date: LocalDate, zone: Days, since: LocalDate? = null): List<Long> {
        val start = startOf(schedule)
        val on = when (schedule.kind) {
            ScheduleKind.AS_NEEDED -> false
            ScheduleKind.DAILY -> true
            ScheduleKind.WEEKLY -> schedule.weekdays.ifEmpty { listOf(start?.dayOfWeek?.value ?: 1) }.contains(date.dayOfWeek.value)
            ScheduleKind.EVERY_N_DAYS -> {
                val from = start ?: since ?: date
                val between = ChronoUnit.DAYS.between(from, date)
                between >= 0 && between % schedule.everyDays.coerceAtLeast(1) == 0L
            }
        }
        if (!on || (start != null && date.isBefore(start)) || (since != null && date.isBefore(since))) return emptyList()
        return schedule.times.mapNotNull { t -> runCatching { LocalTime.parse(t) }.getOrNull() }
            .map { date.atTime(it).atZone(zone.zone).toInstant().toEpochMilli() }
    }

    /**
     * The first day a medication's doses count: the latest of its schedule's start date, the day
     * it was started and the day it was [added], so nothing before it existed counts as missed.
     */
    fun firstDay(med: Medication, added: LocalDate): LocalDate =
        listOfNotNull(startOf(med.schedule), med.startedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, added).max()

    fun perWeek(schedule: MedSchedule): Int = when (schedule.kind) {
        ScheduleKind.AS_NEEDED -> 0
        ScheduleKind.DAILY -> 7 * schedule.times.size
        ScheduleKind.WEEKLY -> schedule.weekdays.size.coerceAtLeast(1) * schedule.times.size
        ScheduleKind.EVERY_N_DAYS -> ((7.0 / schedule.everyDays.coerceAtLeast(1)) * schedule.times.size).roundToInt().coerceAtLeast(1)
    }

    private fun startOf(schedule: MedSchedule) = schedule.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
