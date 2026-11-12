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

    suspend fun list(userId: String, all: Boolean = false): List<Medication> = db.tx {
        query("SELECT id, data FROM medications WHERE user_id = ?${if (all) "" else " AND active = 1"} ORDER BY created_at", userId) { it.string("id") to it.string("data") }
    }.mapNotNull { (id, data) ->
        runCatching { ApiJson.decodeFromString(Medication.serializer(), box.open(data, context(userId, id))).copy(id = id) }.getOrNull()
    }

    suspend fun get(userId: String, id: String): Medication = list(userId, all = true).firstOrNull { it.id == id } ?: throw ApiError.notFound("No such medication")

    suspend fun save(userId: String, med: Medication, id: String? = null): Medication {
        val clean = validated(med)
        val medId = id ?: Random.id()
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
        return clean.copy(id = medId)
    }

    suspend fun delete(userId: String, id: String) {
        if (db.tx { update("DELETE FROM medications WHERE id = ? AND user_id = ?", id, userId) } == 0) throw ApiError.notFound("No such medication")
    }

    // ---- Doses ----

    suspend fun doses(userId: String, from: LocalDate, to: LocalDate): List<DoseLog> =
        entries.list(userId, EntryKind.DOSE, from, to, DoseLog.serializer()).map { it.value.copy(id = it.id, date = it.date.toString()) }

    suspend fun logDose(userId: String, dose: DoseLog): DoseLog {
        val med = get(userId, dose.medicationId)
        val clean = dose.copy(site = dose.site?.trim()?.take(60)?.ifBlank { null }, note = dose.note?.trim()?.take(1000)?.ifBlank { null }, id = null, date = null)
        val date = profiles.days(userId).dateOf(clean.scheduledFor ?: clean.at)
        // Marking a scheduled dose again replaces the earlier mark.
        clean.scheduledFor?.let { slot ->
            doses(userId, date.minusDays(1), date.plusDays(1)).filter { it.medicationId == clean.medicationId && it.scheduledFor == slot }.forEach { old ->
                entries.delete(userId, EntryKind.DOSE, old.id!!)
                if (old.status == DoseStatus.TAKEN) adjustStock(userId, med, +1)
            }
        }
        val id = entries.put(userId, EntryKind.DOSE, null, date, clean.at, clean, DoseLog.serializer())
        if (clean.status == DoseStatus.TAKEN) adjustStock(userId, get(userId, med.id!!), -1)
        return clean.copy(id = id, date = date.toString())
    }

    suspend fun deleteDose(userId: String, id: String) {
        val dose = entries.get(userId, EntryKind.DOSE, id, DoseLog.serializer()) ?: throw ApiError.notFound("No such dose")
        entries.delete(userId, EntryKind.DOSE, id)
        if (dose.value.status == DoseStatus.TAKEN) runCatching { adjustStock(userId, get(userId, dose.value.medicationId), +1) }
    }

    private suspend fun adjustStock(userId: String, med: Medication, by: Int) {
        val stock = med.stock ?: return
        save(userId, med.copy(stock = (stock + by).coerceAtLeast(0)), med.id)
    }

    /** The day's scheduled doses, what happened to each, and the next site for injections and patches. */
    suspend fun day(userId: String, date: LocalDate): MedsDay {
        val zone = profiles.days(userId)
        val meds = list(userId)
        val logs = doses(userId, date.minusDays(120), date.plusDays(1))
        val today = logs.filter { it.date == date.toString() }
        val due = meds.flatMap { med ->
            Schedules.times(med.schedule, date, zone).map { slot ->
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
            adherence7d = adherence(meds, logs, date, zone),
            lowStock = meds.filter { m -> m.stock != null && m.stock!! <= maxOf(5, Schedules.perWeek(m.schedule)) },
        )
    }

    private fun adherence(meds: List<Medication>, logs: List<DoseLog>, date: LocalDate, zone: Days): Int? {
        val now = clock()
        var due = 0
        var taken = 0
        for (back in 0L until 7L) {
            val day = date.minusDays(back)
            meds.forEach { med ->
                Schedules.times(med.schedule, day, zone).filter { it <= now }.forEach { slot ->
                    due++
                    if (logs.any { it.medicationId == med.id && it.scheduledFor == slot && it.status == DoseStatus.TAKEN }) taken++
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
            s.startDate?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw ApiError.badRequest("Dates look like 2026-01-31") } }
            return med.copy(
                name = name,
                dose = med.dose?.trim()?.take(80)?.ifBlank { null },
                purpose = med.purpose?.trim()?.take(200)?.ifBlank { null },
                notes = med.notes?.trim()?.take(2000)?.ifBlank { null },
                sites = med.sites.map { it.trim().take(60) }.filter { it.isNotEmpty() }.distinct().take(20),
                stock = med.stock?.coerceIn(0, 100_000),
                schedule = s.copy(times = times, weekdays = s.weekdays.distinct().sorted()),
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

/** When a schedule's doses fall on a day. Pure, so reminders and the server agree. */
object Schedules {
    fun times(schedule: MedSchedule, date: LocalDate, zone: Days): List<Long> {
        val on = when (schedule.kind) {
            ScheduleKind.AS_NEEDED -> false
            ScheduleKind.DAILY -> true
            ScheduleKind.WEEKLY -> schedule.weekdays.ifEmpty { listOf(startOf(schedule)?.dayOfWeek?.value ?: 1) }.contains(date.dayOfWeek.value)
            ScheduleKind.EVERY_N_DAYS -> {
                val start = startOf(schedule) ?: date
                val between = ChronoUnit.DAYS.between(start, date)
                between >= 0 && between % schedule.everyDays.coerceAtLeast(1) == 0L
            }
        }
        val start = startOf(schedule)
        if (!on || (start != null && date.isBefore(start))) return emptyList()
        return schedule.times.mapNotNull { t -> runCatching { LocalTime.parse(t) }.getOrNull() }
            .map { date.atTime(it).atZone(zone.zone).toInstant().toEpochMilli() }
    }

    fun perWeek(schedule: MedSchedule): Int = when (schedule.kind) {
        ScheduleKind.AS_NEEDED -> 0
        ScheduleKind.DAILY -> 7 * schedule.times.size
        ScheduleKind.WEEKLY -> schedule.weekdays.size.coerceAtLeast(1) * schedule.times.size
        ScheduleKind.EVERY_N_DAYS -> ((7.0 / schedule.everyDays.coerceAtLeast(1)) * schedule.times.size).roundToInt().coerceAtLeast(1)
    }

    private fun startOf(schedule: MedSchedule) = schedule.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
