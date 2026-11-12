package com.health.server.modules

import com.health.api.ContinenceDay
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceSummary
import com.health.api.Insight
import com.health.server.ApiError
import com.health.server.health.ProfileStore
import com.health.server.health.Records
import com.health.server.insights.DayService
import com.health.server.modules.MindService.Companion.counts
import java.time.LocalDate
import kotlin.math.roundToInt

/** The bladder and bowel diary: toilet trips, leaks, bowel movements, pads and pelvic floor exercise. */
class ContinenceService(
    private val entries: EntryStore,
    private val profiles: ProfileStore,
    private val records: Records,
    private val days: DayService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun list(userId: String, count: Int): List<ContinenceEntry> {
        val zone = profiles.days(userId)
        val to = zone.today(clock()).plusDays(1)
        val from = to.minusDays(count.coerceIn(1, 3650).toLong())
        return withNight(userId, entries.list(userId, EntryKind.CONTINENCE, from, to, ContinenceEntry.serializer()).map { it.value.copy(id = it.id, date = it.date.toString()) })
            .reversed()
    }

    suspend fun save(userId: String, entry: ContinenceEntry, id: String? = null): ContinenceEntry {
        fun check(ok: Boolean, message: String) { if (!ok) throw ApiError.badRequest(message) }
        entry.urgency?.let { check(it in 0..3, "Urgency is from 0 to 3") }
        entry.bristol?.let { check(it in 1..7, "The Bristol scale is from 1 to 7") }
        entry.volumeMl?.let { check(it in 1..2000, "Volume is in ml, up to 2,000") }
        entry.reps?.let { check(it in 1..500, "Up to 500 repetitions") }
        entry.holdSeconds?.let { check(it in 1..120, "Holds of up to 2 minutes") }
        val clean = entry.copy(
            triggers = entry.triggers.map { it.trim().lowercase().take(40) }.filter { it.isNotEmpty() }.distinct().take(10),
            note = entry.note?.trim()?.take(2000)?.ifBlank { null },
            leak = entry.leak ?: if (entry.kind == ContinenceKind.LEAK) com.health.api.LeakSize.SMALL else null,
            id = null, date = null, night = false,
        )
        val date = profiles.days(userId).dateOf(clean.at)
        val saved = entries.put(userId, EntryKind.CONTINENCE, id, date, clean.at, clean, ContinenceEntry.serializer())
        return withNight(userId, listOf(clean.copy(id = saved, date = date.toString()))).first()
    }

    suspend fun delete(userId: String, id: String) {
        entries.delete(userId, EntryKind.CONTINENCE, id)
    }

    /** Each day's counts from [from] to [to], with the water logged. */
    suspend fun daysOf(userId: String, from: LocalDate, to: LocalDate): Map<LocalDate, ContinenceDay> {
        val list = withNight(userId, entries.list(userId, EntryKind.CONTINENCE, from, to, ContinenceEntry.serializer()).map { it.value.copy(date = it.date.toString()) })
        if (list.isEmpty()) return emptyMap()
        val water = days.stats(userId, from, to, profiles.days(userId)).associate { LocalDate.parse(it.date) to it.waterMl }
        return list.groupBy { LocalDate.parse(it.date!!) }.mapValues { (date, day) ->
            val voids = day.filter { it.kind == ContinenceKind.URINATION }
            ContinenceDay(
                date = date.toString(),
                voids = voids.size,
                nightVoids = voids.count { it.night },
                leaks = day.count { it.kind == ContinenceKind.LEAK || it.leak != null },
                bowel = day.count { it.kind == ContinenceKind.BOWEL },
                pads = day.count { it.kind == ContinenceKind.PAD_CHANGE },
                pelvicFloor = day.count { it.kind == ContinenceKind.PELVIC_FLOOR },
                voidedMl = voids.mapNotNull { it.volumeMl }.takeIf { it.isNotEmpty() }?.sum(),
                fluidMl = water[date] ?: 0,
                urgentTrips = day.count { (it.urgency ?: 0) >= 2 },
            )
        }
    }

    suspend fun summary(userId: String, count: Int): ContinenceSummary {
        val zone = profiles.days(userId)
        val to = zone.today(clock())
        val from = to.minusDays((count.coerceIn(1, 365) - 1).toLong())
        val byDay = daysOf(userId, from, to)
        val all = withNight(userId, entries.list(userId, EntryKind.CONTINENCE, from, to, ContinenceEntry.serializer()).map { it.value.copy(date = it.date.toString()) })
        val list = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { byDay[it] ?: ContinenceDay(it.toString(), fluidMl = 0) }.toList()
        val logged = list.filter { byDay.containsKey(LocalDate.parse(it.date)) }
        fun avg(f: (ContinenceDay) -> Int) = logged.takeIf { it.isNotEmpty() }?.let { l -> (l.map(f).average() * 10).roundToInt() / 10.0 }
        val leaks = all.filter { it.kind == ContinenceKind.LEAK || it.leak != null }
        // The longest daytime gap between trips, each day, averaged.
        val gaps = all.filter { it.kind == ContinenceKind.URINATION && !it.night }.groupBy { it.date }.values.mapNotNull { day ->
            day.map { it.at }.sorted().zipWithNext { a, b -> b - a }.maxOrNull()
        }
        return ContinenceSummary(
            days = list,
            averageVoids = avg { it.voids },
            averageNightVoids = avg { it.nightVoids },
            averageLeaks = avg { it.leaks },
            triggers = counts(leaks.flatMap { it.triggers }),
            averageInterval = gaps.takeIf { it.isNotEmpty() }?.average()?.let { (it / 60_000).roundToInt() },
            patterns = patterns(leaks, logged),
        )
    }

    /** Marks entries made during a recorded night's sleep (or, without one, between 11 pm and 6 am). */
    private suspend fun withNight(userId: String, list: List<ContinenceEntry>): List<ContinenceEntry> {
        if (list.isEmpty()) return list
        val zone = profiles.days(userId)
        val nights = records.sleep(userId, list.minOf { it.at } - 12 * 3_600_000L, list.maxOf { it.at } + 14 * 3_600_000L)
        return list.map { e ->
            val inSleep = nights.any { e.at in it.start..it.end }
            val hour = zone.hourOf(e.at)
            e.copy(night = if (nights.isNotEmpty()) inSleep else hour >= 23 || hour < 6)
        }
    }

    companion object {
        private val STRESS = setOf("cough", "sneeze", "laugh", "lifting", "exercise", "jumping")
        private val URGE = setOf("urge", "on the way", "key in the door", "running water")

        /** Gentle pointers from the diary: never a diagnosis, and always "worth asking about". */
        fun patterns(leaks: List<ContinenceEntry>, days: List<ContinenceDay>): List<Insight> {
            val out = mutableListOf<Insight>()
            val stress = leaks.count { e -> e.triggers.any { it in STRESS } }
            val urge = leaks.count { e -> e.triggers.any { it in URGE } || (e.urgency ?: 0) >= 2 }
            if (leaks.size >= 3 && stress >= leaks.size / 2.0) {
                out += Insight("info", "Most leaks came with coughs, laughs or lifting", "Daily pelvic floor exercises often help with these. A pelvic health physiotherapist can show you how to do them well.", "activity")
            }
            if (leaks.size >= 3 && urge >= leaks.size / 2.0) {
                out += Insight("info", "Most leaks came with a sudden urge", "Bladder training (stretching the time between trips bit by bit) and cutting back on caffeine often help. Worth mentioning to your doctor.", "droplet")
            }
            val nights = days.map { it.nightVoids }
            if (nights.size >= 3 && nights.average() >= 2) {
                out += Insight("info", "Up %.1f times a night on average".format(nights.average()), "Drinking less in the two hours before bed can help. Waking twice or more most nights is worth asking a doctor about.", "moon")
            }
            val voids = days.filter { it.voids > 0 }.map { it.voids }
            if (voids.size >= 3 && voids.average() > 8.5) {
                out += Insight("info", "About %.0f toilet trips a day".format(voids.average()), "More than 8 a day is more often than usual. Your diary is just what a doctor or continence nurse would want to see.", "droplet")
            }
            return out
        }
    }
}
