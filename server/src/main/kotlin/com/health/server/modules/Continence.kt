package com.health.server.modules

import com.health.api.ContinenceCheckIn
import com.health.api.ContinenceDay
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceProgress
import com.health.api.ContinenceSettings
import com.health.api.ContinenceSummary
import com.health.api.ContinenceDiary
import com.health.api.ContinenceDiaryItem
import com.health.api.ContinenceWear
import com.health.api.ContinenceWearing
import com.health.api.Supply
import com.health.api.Insight
import com.health.api.LeakSize
import com.health.api.ProductStats
import com.health.api.ProgressMetric
import com.health.api.ProgressWeek
import com.health.api.Verdict
import com.health.server.ApiError
import com.health.server.health.ProfileStore
import com.health.server.health.Records
import com.health.server.insights.DayService
import com.health.server.modules.MindService.Companion.counts
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt

/** The bladder and bowel diary: toilet trips, leaks, bowel movements, pads and pelvic floor exercise. */
class ContinenceService(
    private val entries: EntryStore,
    private val profiles: ProfileStore,
    private val records: Records,
    private val days: DayService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Supplies used (pads, briefs…) come off their counts. Set at start-up. */
    var supplies: SuppliesService? = null

    /** The person's own settings. Set at start-up. */
    var settings: UserSettingsStore? = null

    /** Caffeine each day (tracking/Drinks), for progress notes. Set at start-up. */
    var caffeine: (suspend (userId: String, from: LocalDate, to: LocalDate) -> Map<LocalDate, Double>)? = null

    suspend fun list(userId: String, count: Int): List<ContinenceEntry> {
        val zone = profiles.days(userId)
        val to = zone.today(clock()).plusDays(1)
        val from = to.minusDays(count.coerceIn(1, 3650).toLong())
        return worn(userId, from, to).filter { !LocalDate.parse(it.date!!).isBefore(from) }.reversed()
    }

    /**
     * The entries from [from] to [to], marked by night and with each change's wear time: the day
     * before [from] comes too (and is left in), so the first changes pair with what put them on.
     */
    private suspend fun worn(userId: String, from: LocalDate, to: LocalDate): List<ContinenceEntry> =
        Wear.withWorn(withNight(userId, entries.list(userId, EntryKind.CONTINENCE, from.minusDays(1), to, ContinenceEntry.serializer()).map { it.value.copy(id = it.id, date = it.date.toString()) }))

    suspend fun save(userId: String, entry: ContinenceEntry, id: String? = null): ContinenceEntry {
        fun check(ok: Boolean, message: String) { if (!ok) throw ApiError.badRequest(message) }
        entry.urgency?.let { check(it in 0..3, "Urgency is from 0 to 3") }
        entry.bristol?.let { check(it in 1..7, "The Bristol scale is from 1 to 7") }
        entry.volumeMl?.let { check(it in 1..2000, "Volume is in ml, up to 2,000") }
        entry.reps?.let { check(it in 1..500, "Up to 500 repetitions") }
        entry.holdSeconds?.let { check(it in 1..120, "Holds of up to 2 minutes") }
        entry.warningSeconds?.let { check(it in 0..3600, "Warning is in seconds, up to an hour") }
        entry.skin?.let { check(it in 0..3, "Skin comfort is from 0 to 3") }
        entry.count?.let { check(it in 1..20, "1 to 20 at a time") }
        entry.padWeightG?.let { check(it > 0 && it <= 5000, "Pad weight is in grams, up to 5,000") }
        check(entry.details.size <= 30, "Up to 30 of your own fields")
        fun words(list: List<String>, max: Int = 10) = list.map { it.trim().lowercase().take(40) }.filter { it.isNotEmpty() }.distinct().take(max)
        val supply = entry.supplyId?.let { supplies?.get(userId, it) ?: throw ApiError.badRequest("No such supply") }
        val change = entry.kind == ContinenceKind.PAD_CHANGE
        if (change) {
            check(!(entry.nothingOn && entry.putOnSupplyId != null), "Something put on, or nothing for now: not both")
            entry.putOnSupplyId?.let { id -> supplies?.let { check(it.get(userId, id) != null, "No such supply") } }
        }
        val loss = if (entry.padWeightG != null && supply?.dryWeightG != null) {
            ((entry.padWeightG!! - supply.dryWeightG!!).coerceAtLeast(0.0) * 10).roundToInt() / 10.0
        } else null
        val clean = entry.copy(
            triggers = words(entry.triggers),
            activity = words(entry.activity),
            utiSigns = words(entry.utiSigns),
            tags = words(entry.tags, 20),
            location = entry.location?.trim()?.take(60)?.ifBlank { null },
            details = entry.details.entries.associate { (k, v) -> k.trim().take(40) to v.trim().take(500) }.filterKeys { it.isNotEmpty() },
            note = entry.note?.trim()?.take(2000)?.ifBlank { null },
            leak = entry.leak ?: if (entry.kind == ContinenceKind.LEAK) LeakSize.SMALL else null,
            lossG = loss,
            putOnSupplyId = entry.putOnSupplyId.takeIf { change },
            nothingOn = entry.nothingOn && change,
            id = null, date = null, night = false, wornMinutes = null,
        )
        val date = profiles.days(userId).dateOf(clean.at)
        val saved = entries.put(userId, EntryKind.CONTINENCE, id, date, clean.at, clean, ContinenceEntry.serializer())
        supplies?.syncUse(userId, source(saved), usesOf(clean), clean.at)
        val worn = if (change) worn(userId, date, date).firstOrNull { it.id == saved }?.wornMinutes else null
        return withNight(userId, listOf(clean.copy(id = saved, date = date.toString(), wornMinutes = worn))).first()
    }

    // ---- What's being worn, and how each product goes

    /**
     * What's on now: from the latest change in the last day that put something on or took
     * everything off; all null when there's none. [typical] also works out the product's usual
     * wear time from the last [TYPICAL_DAYS] days (the reminders don't need it).
     */
    suspend fun wearing(userId: String, typical: Boolean = true): ContinenceWearing {
        val zone = profiles.days(userId)
        val now = clock()
        val today = zone.today(now)
        val recent = entries.list(userId, EntryKind.CONTINENCE, today.minusDays(1), today.plusDays(1), ContinenceEntry.serializer()).map { it.value.copy(id = it.id, date = it.date.toString()) }
        val latest = Wear.latest(recent, now) ?: return ContinenceWearing()
        val minutes = ((now - latest.at) / 60_000).toInt().coerceAtLeast(0)
        val id = Wear.putOn(latest) ?: return ContinenceWearing(since = latest.at, minutes = minutes, nothingOn = true)
        val supply = supplies?.get(userId, id)
        val own = settings(userId)
        val due = own.changeReminderHours?.let { hours ->
            Wear.dueAt(latest.at, hours, zone, minuteOf(own.nightFrom, 23 * 60), minuteOf(own.nightTo, 6 * 60), own.changeReminderAtNight)
        }
        val usual = if (typical) Wear.typical(worn(userId, today.minusDays(TYPICAL_DAYS), today), id) else null
        val wear = Wear.spans(recent, now).lastOrNull { it.current && it.on?.id == latest.id }?.let { named(it, now, supply?.let { s -> mapOf(id to s) } ?: emptyMap()) }
        return ContinenceWearing(id, supply?.name, supply?.kind, latest.at, minutes, usual, due, wear = wear)
    }

    /**
     * A day of the diary, grouped: each product worn that day (on at any time in it) with the
     * accidents while it was on, and every other entry of the day on its own, newest first.
     */
    suspend fun diary(userId: String, date: LocalDate): ContinenceDiary {
        val zone = profiles.days(userId)
        val now = clock()
        val list = worn(userId, date.minusDays(1), date.plusDays(1))
        val from = zone.start(date)
        val to = zone.start(date.plusDays(1))
        val spans = Wear.spans(list, now).filter { it.overlaps(from, to) }
        val names = supplies?.list(userId, all = true).orEmpty().filter { it.id != null }.associateBy { it.id!! }
        val grouped = spans.flatMap { s -> listOfNotNull(s.on?.id, s.off?.id) + s.accidents.mapNotNull { it.id } }.toSet()
        val own = list.filter { it.date == date.toString() && it.id !in grouped }
        val items = spans.map { ContinenceDiaryItem(it.listedAt(now), wear = named(it, now, names)) } + own.map { ContinenceDiaryItem(it.at, entry = it) }
        return ContinenceDiary(date.toString(), items.sortedByDescending { it.at })
    }

    private fun named(s: Wear.Span, now: Long, names: Map<String, Supply>): ContinenceWear {
        val supply = names[s.supplyId]
        val next = s.off?.takeIf { !it.nothingOn }?.let { Wear.putOn(it) }
        return ContinenceWear(
            supplyId = s.supplyId, name = supply?.name, kind = supply?.kind, on = s.on, off = s.off, start = s.start, end = s.end,
            current = s.current, minutes = s.minutes(now), fullness = s.fullness, leakedThrough = s.leakedThrough, accidents = s.accidents,
            nextName = next?.let { names[it]?.name },
        )
    }

    /** How each product went from [from] to [to]: from the changes that took it off. */
    suspend fun products(userId: String, from: LocalDate, to: LocalDate): List<ProductStats> {
        val all = supplies?.list(userId, all = true).orEmpty().filter { it.id != null }.associateBy { it.id!! }
        if (all.isEmpty()) return emptyList()
        val zone = profiles.days(userId)
        val own = settings(userId)
        val nightFrom = minuteOf(own.nightFrom, 23 * 60)
        val nightTo = minuteOf(own.nightTo, 6 * 60)
        return Wear.stats(worn(userId, from, to), all, since = zone.start(from)) { Wear.inNight(zone.minuteOfDay(it), nightFrom, nightTo) }
    }

    /** The last [count] days' products, up to today. */
    suspend fun products(userId: String, count: Int): List<ProductStats> {
        val today = profiles.days(userId).today(clock())
        return products(userId, today.minusDays((count.coerceIn(1, 365) - 1).toLong()), today)
    }

    private fun minuteOf(time: String?, default: Int): Int =
        time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }?.let { it.hour * 60 + it.minute } ?: default

    suspend fun delete(userId: String, id: String) {
        entries.delete(userId, EntryKind.CONTINENCE, id)
        supplies?.syncUse(userId, source(id), emptyMap(), clock())
    }

    /** A day's entries, as kept. */
    suspend fun entriesOn(userId: String, date: LocalDate): List<ContinenceEntry> =
        entries.list(userId, EntryKind.CONTINENCE, date, date, ContinenceEntry.serializer()).map { it.value.copy(id = it.id, date = it.date.toString()) }

    // ---- Settings and check-ins

    suspend fun settings(userId: String): ContinenceSettings =
        settings?.get(userId, SETTINGS_KEY, ContinenceSettings.serializer()) ?: ContinenceSettings()

    suspend fun putSettings(userId: String, input: ContinenceSettings): ContinenceSettings {
        fun check(ok: Boolean, message: String) { if (!ok) throw ApiError.badRequest(message) }
        fun time(t: String?, what: String) = t?.let { check(runCatching { LocalTime.parse(it) }.isSuccess, "$what looks like 07:30") }
        time(input.nightFrom, "Night from"); time(input.nightTo, "Night to")
        val bt = input.bladderTraining
        time(bt.from, "Training from"); time(bt.to, "Training to")
        check(bt.intervalMinutes in 15..480, "The interval is 15 minutes to 8 hours")
        check(bt.goalMinutes in bt.intervalMinutes..600, "The goal is at least the starting interval, up to 10 hours")
        check(bt.stepMinutes in 0..120 && bt.stepEveryDays in 1..60, "Steps of up to 2 hours, every 1 to 60 days")
        bt.startedOn?.let { check(runCatching { LocalDate.parse(it) }.isSuccess, "Started on looks like 2026-01-31") }
        input.changeReminderHours?.let { check(it in 1..24, "A change reminder comes after 1 to 24 hours") }
        check(input.customFields.size <= 30, "Up to 30 of your own fields")
        input.defaultSupplyId?.let { check(supplies?.get(userId, it) != null, "No such supply") }
        fun words(list: List<String>) = list.map { it.trim().lowercase().take(40) }.filter { it.isNotEmpty() }.distinct().take(50)
        val clean = input.copy(
            quickButtons = input.quickButtons.distinct().take(8),
            triggers = words(input.triggers), activities = words(input.activities), locations = words(input.locations),
            customFields = input.customFields.distinctBy { it.key }.map { it.copy(key = it.key.trim().take(40), label = it.label.trim().take(60)) },
            bladderTraining = bt.copy(startedOn = bt.startedOn ?: if (bt.enabled) profiles.days(userId).today(clock()).toString() else null),
        )
        return settings?.put(userId, SETTINGS_KEY, clean, ContinenceSettings.serializer()) ?: clean
    }

    suspend fun checkIns(userId: String, count: Int): List<ContinenceCheckIn> {
        val zone = profiles.days(userId)
        val to = zone.today(clock())
        return entries.list(userId, EntryKind.CONTINENCE_CHECKIN, to.minusDays(count.coerceIn(1, 3650).toLong()), to, ContinenceCheckIn.serializer())
            .map { it.value.copy(id = it.id) }.reversed()
    }

    suspend fun saveCheckIn(userId: String, input: ContinenceCheckIn): ContinenceCheckIn {
        if (input.bother !in 0..10) throw ApiError.badRequest("Bother is from 0 to 10")
        input.compared?.let { if (it !in -2..2) throw ApiError.badRequest("Compared is from -2 (much worse) to 2 (much better)") }
        val date = runCatching { LocalDate.parse(input.date) }.getOrElse { throw ApiError.badRequest("Dates look like 2026-01-31") }
        val clean = input.copy(
            date = date.toString(),
            impact = input.impact.map { it.trim().lowercase().take(40) }.filter { it.isNotEmpty() }.distinct().take(15),
            note = input.note?.trim()?.take(2000)?.ifBlank { null },
            id = null,
        )
        val noon = profiles.days(userId).start(date) + 12 * 3_600_000L
        val id = entries.put(userId, EntryKind.CONTINENCE_CHECKIN, null, date, noon, clean, ContinenceCheckIn.serializer())
        return clean.copy(id = id)
    }

    suspend fun deleteCheckIn(userId: String, id: String) {
        entries.delete(userId, EntryKind.CONTINENCE_CHECKIN, id)
    }

    // ---- Days, summary and progress

    /** Each day's counts from [from] to [to], with the water logged. */
    suspend fun daysOf(userId: String, from: LocalDate, to: LocalDate): Map<LocalDate, ContinenceDay> {
        val list = withNight(userId, entries.list(userId, EntryKind.CONTINENCE, from, to, ContinenceEntry.serializer()).map { it.value.copy(date = it.date.toString()) })
        if (list.isEmpty()) return emptyMap()
        val water = days.stats(userId, from, to, profiles.days(userId)).associate { LocalDate.parse(it.date) to it.waterMl }
        return list.groupBy { LocalDate.parse(it.date!!) }.mapValues { (date, day) -> dayOf(date, day, water[date] ?: 0) }
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
        val leaks = all.filter { isLeak(it) || it.kind == ContinenceKind.BOWEL_LEAK }
        // The longest daytime gap between trips, each day, averaged.
        val gaps = all.filter { it.kind == ContinenceKind.URINATION && !it.night }.groupBy { it.date }.values.mapNotNull { day ->
            day.map { it.at }.sorted().zipWithNext { a, b -> b - a }.maxOrNull()
        }
        return ContinenceSummary(
            days = list,
            averageVoids = avg { it.voids },
            averageNightVoids = avg { it.nightVoids },
            averageLeaks = avg { it.leaks },
            triggers = counts(leaks.flatMap { it.triggers + it.activity }),
            averageInterval = gaps.takeIf { it.isNotEmpty() }?.average()?.let { (it / 60_000).roundToInt() },
            patterns = patterns(leaks, logged) + detailPatterns(all, logged),
        )
    }

    /**
     * Whether things are getting better: the last weeks against the ones before, week by week,
     * up to [end] (today unless given: a past digest or report gives its own last day).
     */
    suspend fun progress(userId: String, weekCount: Int, end: LocalDate? = null): ContinenceProgress {
        val zone = profiles.days(userId)
        val today = zone.today(clock()).let { now -> end?.let { minOf(it, now) } ?: now }
        val weeks = weekCount.coerceIn(2, 52)
        val firstWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((weeks - 1).toLong())
        val all = withNight(userId, entries.list(userId, EntryKind.CONTINENCE, firstWeek, today, ContinenceEntry.serializer()).map { it.value.copy(date = it.date.toString()) })
        val checks = entries.list(userId, EntryKind.CONTINENCE_CHECKIN, firstWeek, today, ContinenceCheckIn.serializer()).map { it.value }
        val caffeineByDay = runCatching { caffeine?.invoke(userId, firstWeek, today) }.getOrNull().orEmpty()
        return Progress.analyze(all, checks, caffeineByDay, firstWeek, weeks, today)
    }

    /** Marks entries made during a recorded night's sleep (or, without one, the person's night hours, or 11 pm to 6 am). */
    private suspend fun withNight(userId: String, list: List<ContinenceEntry>): List<ContinenceEntry> {
        if (list.isEmpty()) return list
        val zone = profiles.days(userId)
        val own = runCatching { settings(userId) }.getOrNull()
        val nightFrom = own?.nightFrom?.let { LocalTime.parse(it).let { t -> t.hour * 60 + t.minute } } ?: (23 * 60)
        val nightTo = own?.nightTo?.let { LocalTime.parse(it).let { t -> t.hour * 60 + t.minute } } ?: (6 * 60)
        val nights = records.sleep(userId, list.minOf { it.at } - 12 * 3_600_000L, list.maxOf { it.at } + 14 * 3_600_000L)
        return list.map { e ->
            val inSleep = nights.any { e.at in it.start..it.end }
            val minute = zone.minuteOfDay(e.at)
            val inHours = if (nightFrom > nightTo) minute >= nightFrom || minute < nightTo else minute in nightFrom until nightTo
            e.copy(night = e.kind == ContinenceKind.BEDWETTING || if (nights.isNotEmpty()) inSleep else inHours)
        }
    }

    companion object {
        const val SETTINGS_KEY = "continence"

        /** How far back a product's usual wear time looks. */
        const val TYPICAL_DAYS = 60L
        private val STRESS = setOf("cough", "sneeze", "laugh", "lifting", "exercise", "jumping")
        private val URGE = setOf("urge", "on the way", "key in the door", "running water")

        fun source(entryId: String) = "continence:$entryId"

        /**
         * What an entry used: a change uses one (or its count) of what it put on, and nothing with
         * nothing put on (what it took off was counted when it went on). A change from before what
         * went on was recorded uses its product, as then; any other entry with a product and a count uses that many.
         */
        fun usesOf(e: ContinenceEntry): Map<String, Int> {
            if (e.kind == ContinenceKind.PAD_CHANGE) {
                e.putOnSupplyId?.let { return mapOf(it to (e.count ?: 1)) }
                if (e.nothingOn) return emptyMap()
            }
            val id = e.supplyId ?: return emptyMap()
            val count = when {
                e.kind == ContinenceKind.PAD_CHANGE -> e.count ?: 1
                e.count != null -> e.count!!
                else -> 0
            }
            return if (count > 0) mapOf(id to count) else emptyMap()
        }

        /** Products an entry used, counted or not: a change with no product named still used one, unless nothing went on. */
        fun productsUsed(e: ContinenceEntry): Int = usesOf(e).values.sum().takeIf { it > 0 } ?: when {
            e.kind != ContinenceKind.PAD_CHANGE || e.nothingOn -> 0
            else -> e.count ?: 1
        }

        /** A leak of urine (a wet night counts); bowel accidents are counted on their own. */
        fun isLeak(e: ContinenceEntry) = e.kind == ContinenceKind.LEAK || e.kind == ContinenceKind.BEDWETTING ||
            (e.kind == ContinenceKind.URINATION && e.leak != null)

        fun dayOf(date: LocalDate, day: List<ContinenceEntry>, fluidMl: Int): ContinenceDay {
            val voids = day.filter { it.kind == ContinenceKind.URINATION }
            val catheters = day.filter { it.kind == ContinenceKind.CATHETER }
            val losses = day.mapNotNull { it.lossG }
            return ContinenceDay(
                date = date.toString(),
                voids = voids.size,
                nightVoids = voids.count { it.night },
                leaks = day.count { isLeak(it) },
                bowel = day.count { it.kind == ContinenceKind.BOWEL },
                pads = day.count { it.kind == ContinenceKind.PAD_CHANGE },
                pelvicFloor = day.count { it.kind == ContinenceKind.PELVIC_FLOOR },
                voidedMl = voids.mapNotNull { it.volumeMl }.takeIf { it.isNotEmpty() }?.sum(),
                fluidMl = fluidMl,
                urgentTrips = day.count { (it.urgency ?: 0) >= 2 },
                bowelLeaks = day.count { it.kind == ContinenceKind.BOWEL_LEAK },
                wetNights = day.count { it.kind == ContinenceKind.BEDWETTING },
                products = day.sumOf { productsUsed(it) },
                leakedThrough = day.count { it.leakedThrough },
                catheters = catheters.size,
                catheterMl = catheters.mapNotNull { it.volumeMl }.takeIf { it.isNotEmpty() }?.sum(),
                lossG = losses.takeIf { it.isNotEmpty() }?.sum()?.let { (it * 10).roundToInt() / 10.0 },
            )
        }

        /** Gentle pointers from the diary: never a diagnosis, and always "worth asking about". */
        fun patterns(leaks: List<ContinenceEntry>, days: List<ContinenceDay>): List<Insight> {
            val out = mutableListOf<Insight>()
            val urine = leaks.filter { it.kind != ContinenceKind.BOWEL_LEAK }
            val stress = urine.count { e -> (e.triggers + e.activity).any { it in STRESS } }
            val urge = urine.count { e -> e.triggers.any { it in URGE } || (e.urgency ?: 0) >= 2 }
            if (urine.size >= 3 && stress >= urine.size / 2.0) {
                out += Insight("info", "Most leaks came with coughs, laughs or lifting", "Daily pelvic floor exercises often help with these. A pelvic health physiotherapist can show you how to do them well.", "activity")
            }
            if (urine.size >= 3 && urge >= urine.size / 2.0) {
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

        /** What the detail fields add: bowel accidents, leaks through, sore skin, signs of an infection. */
        fun detailPatterns(all: List<ContinenceEntry>, days: List<ContinenceDay>): List<Insight> {
            val out = mutableListOf<Insight>()
            val bowel = all.count { it.kind == ContinenceKind.BOWEL_LEAK }
            if (bowel >= 2) {
                out += Insight("info", "$bowel bowel accidents", "These are common and very treatable. A doctor, continence nurse or pelvic health physiotherapist can help, and this diary is what they'll want to see.", "droplet")
            }
            val through = all.count { it.leakedThrough }
            if (through >= 3) {
                out += Insight("info", "$through leaks came through", "A more absorbent product, a better fit, or changing a little sooner can help. Continence nurses can suggest products to try.", "droplet")
            }
            if (all.count { (it.skin ?: 0) >= 2 } >= 2) {
                out += Insight("info", "Sore skin a few times", "Changing soon after a leak, gentle cleansing and a barrier cream help protect skin. If it breaks or doesn't settle, a nurse or pharmacist can help.", "droplet")
            }
            if (all.any { it.utiSigns.isNotEmpty() }) {
                out += Insight("warn", "Signs that can go with a urine infection", "Burning, cloudy or strong-smelling urine, or going much more often, are worth telling a doctor or nurse about soon, and today if you have a fever or back pain.", "thermometer")
            }
            val wet = days.sumOf { it.wetNights }
            if (wet >= 3) {
                out += Insight("info", "$wet wet nights", "Bed pads and an alarm can make nights easier, and a doctor or continence nurse can look at what's behind them.", "moon")
            }
            return out
        }
    }
}

/** Getting better or worse: pure, so it can be tested on its own. */
object Progress {
    /** At least this many logged days on each side before a number gets a verdict. */
    private const val ENOUGH_DAYS = 5

    private class Metric(val key: String, val label: String, val unit: String, val minChange: Double, val weight: Int, val value: (List<ContinenceEntry>, Int) -> Double?)

    private val METRICS = listOf(
        Metric("leaks", "Leaks a day", "", 0.2, 2) { e, d -> e.count { ContinenceService.isLeak(it) }.toDouble() / d },
        Metric("bowelLeaks", "Bowel accidents a day", "", 0.1, 2) { e, d -> e.count { it.kind == ContinenceKind.BOWEL_LEAK }.toDouble() / d },
        Metric("urgency", "Urgency (0–3)", "", 0.3, 1) { e, _ -> e.mapNotNull { it.urgency }.takeIf { it.size >= 3 }?.average() },
        Metric("nightVoids", "Night-time trips", "", 0.3, 1) { e, d -> e.count { it.kind == ContinenceKind.URINATION && it.night }.toDouble() / d },
        Metric("products", "Products a day", "", 0.3, 1) { e, d -> e.sumOf { ContinenceService.productsUsed(it) }.toDouble() / d },
        Metric("fullness", "How full products were (0–5)", "", 0.4, 1) { e, _ -> e.mapNotNull { it.fullness?.level }.takeIf { it.size >= 3 }?.average() },
        Metric("loss", "Loss a day", "g", 5.0, 1) { e, d -> e.mapNotNull { it.lossG }.takeIf { it.size >= 3 }?.let { it.sum() / d } },
    )

    fun analyze(
        entries: List<ContinenceEntry>,
        checks: List<ContinenceCheckIn>,
        caffeine: Map<LocalDate, Double>,
        firstWeek: LocalDate,
        weeks: Int,
        today: LocalDate,
    ): ContinenceProgress {
        val byDate = entries.groupBy { LocalDate.parse(it.date!!) }
        val weekStarts = (0 until weeks).map { firstWeek.plusWeeks(it.toLong()) }
        fun round(v: Double?, places: Int = 1) = v?.let { val f = Math.pow(10.0, places.toDouble()); (it * f).roundToInt() / f }
        val series = weekStarts.map { start ->
            val end = minOf(start.plusDays(6), today)
            val inWeek = entries.filter { val d = LocalDate.parse(it.date!!); !d.isBefore(start) && !d.isAfter(end) }
            val loggedDays = inWeek.map { it.date }.distinct().size
            val bother = checks.filter { val d = LocalDate.parse(it.date); !d.isBefore(start) && !d.isAfter(end) }.map { it.bother }
            fun per(m: String) = if (loggedDays == 0) null else METRICS.first { it.key == m }.value(inWeek, loggedDays)
            ProgressWeek(
                weekStart = start.toString(),
                days = loggedDays,
                voidsPerDay = round(if (loggedDays == 0) null else inWeek.count { it.kind == ContinenceKind.URINATION }.toDouble() / loggedDays),
                nightVoidsPerDay = round(per("nightVoids")),
                leaksPerDay = round(per("leaks")),
                bowelLeaksPerDay = round(per("bowelLeaks")),
                urgency = round(per("urgency")),
                productsPerDay = round(per("products")),
                fullness = round(per("fullness")),
                lossGPerDay = round(per("loss")),
                bother = round(bother.takeIf { it.isNotEmpty() }?.average()),
                pelvicFloorSets = inWeek.count { it.kind == ContinenceKind.PELVIC_FLOOR },
            )
        }

        // The last four weeks against the four before (or halves, with fewer weeks).
        val recentWeeks = minOf(4, weeks / 2).coerceAtLeast(1)
        val recentStart = weekStarts[weeks - recentWeeks]
        val earlierStart = weekStarts[(weeks - 2 * recentWeeks).coerceAtLeast(0)]
        fun window(from: LocalDate, until: LocalDate) = entries.filter { val d = LocalDate.parse(it.date!!); !d.isBefore(from) && d.isBefore(until) }
        val recent = window(recentStart, today.plusDays(1))
        val earlier = window(earlierStart, recentStart)
        val recentDays = recent.map { it.date }.distinct().size
        val earlierDays = earlier.map { it.date }.distinct().size

        val metrics = METRICS.mapNotNull { m ->
            val before = if (earlierDays > 0) m.value(earlier, earlierDays) else null
            val after = if (recentDays > 0) m.value(recent, recentDays) else null
            if (before == null && after == null) return@mapNotNull null
            val enough = earlierDays >= ENOUGH_DAYS && recentDays >= ENOUGH_DAYS && before != null && after != null
            ProgressMetric(m.key, m.label, round(before, 2), round(after, 2), m.unit, true, if (enough) verdict(before!!, after!!, m.minChange) else Verdict.NOT_ENOUGH)
        }.toMutableList()
        val botherBefore = checks.filter { val d = LocalDate.parse(it.date); !d.isBefore(earlierStart) && d.isBefore(recentStart) }.map { it.bother }
        val botherAfter = checks.filter { !LocalDate.parse(it.date).isBefore(recentStart) }.map { it.bother }
        if (botherBefore.isNotEmpty() || botherAfter.isNotEmpty()) {
            val b = botherBefore.takeIf { it.isNotEmpty() }?.average()
            val a = botherAfter.takeIf { it.isNotEmpty() }?.average()
            val enough = botherBefore.size >= 2 && botherAfter.size >= 2
            metrics += ProgressMetric("bother", "How much it bothers you (0–10)", round(b, 1), round(a, 1), "", true, if (enough) verdict(b!!, a!!, 1.0) else Verdict.NOT_ENOUGH)
        }

        val weightOf = METRICS.associate { it.key to it.weight } + ("bother" to 2)
        val judged = metrics.filter { it.verdict != Verdict.NOT_ENOUGH }
        val score = judged.sumOf { (weightOf[it.key] ?: 1) * when (it.verdict) { Verdict.IMPROVING -> 1; Verdict.WORSENING -> -1; else -> 0 } }
        val overall = when {
            judged.isEmpty() -> Verdict.NOT_ENOUGH
            score >= 2 -> Verdict.IMPROVING
            score <= -2 -> Verdict.WORSENING
            else -> Verdict.STEADY
        }
        val headline = when (overall) {
            Verdict.NOT_ENOUGH -> "A few more weeks of the diary and this will show how things are going."
            Verdict.STEADY -> "About the same as the weeks before."
            Verdict.IMPROVING -> judged.filter { it.verdict == Verdict.IMPROVING }.maxByOrNull { weightOf[it.key] ?: 1 }!!
                .let { "Getting better: ${it.label.lowercase()} went from ${say(it.before)} to ${say(it.after)}." }
            Verdict.WORSENING -> judged.filter { it.verdict == Verdict.WORSENING }.maxByOrNull { weightOf[it.key] ?: 1 }!!
                .let { "Harder lately: ${it.label.lowercase()} went from ${say(it.before)} to ${say(it.after)}. Worth mentioning to your doctor or continence nurse." }
        }

        // Things that went along with it: said as going together, never as a cause.
        val notes = mutableListOf<String>()
        val pfBefore = earlier.count { it.kind == ContinenceKind.PELVIC_FLOOR }
        val pfAfter = recent.count { it.kind == ContinenceKind.PELVIC_FLOOR }
        if (pfAfter >= pfBefore + 3) notes += "You did pelvic floor exercises more often lately ($pfBefore sets before, $pfAfter since). Regular practice often goes with fewer leaks."
        if (pfBefore >= pfAfter + 3) notes += "Fewer pelvic floor sets lately ($pfBefore before, $pfAfter since). Picking them back up often helps."
        val cafBefore = caffeine.filterKeys { !it.isBefore(earlierStart) && it.isBefore(recentStart) }.values
        val cafAfter = caffeine.filterKeys { !it.isBefore(recentStart) }.values
        if (cafBefore.size >= 5 && cafAfter.size >= 5) {
            val b = cafBefore.average()
            val a = cafAfter.average()
            if (a <= b - 50) notes += "Less caffeine lately (about ${b.roundToInt()} mg a day before, ${a.roundToInt()} mg since). For some people that goes with fewer urges."
            if (a >= b + 50) notes += "More caffeine lately (about ${b.roundToInt()} mg a day before, ${a.roundToInt()} mg since). For some people caffeine brings on urges."
        }
        if (recent.any { it.utiSigns.isNotEmpty() }) notes += "Signs that can go with a urine infection were logged lately: those are worth telling a doctor or nurse about."
        return ContinenceProgress(overall, headline, recentWeeks, metrics, series, notes)
    }

    /** Lower is better for every number here. */
    fun verdict(before: Double, after: Double, minChange: Double): Verdict {
        val diff = after - before
        if (abs(diff) < minChange) return Verdict.STEADY
        val relative = if (before > 0) abs(diff) / before else 1.0
        if (relative < 0.2) return Verdict.STEADY
        return if (diff < 0) Verdict.IMPROVING else Verdict.WORSENING
    }

    private fun say(v: Double?) = when {
        v == null -> "–"
        v == v.roundToInt().toDouble() -> v.roundToInt().toString()
        else -> "%.1f".format(v)
    }

    /** Days between two dates, for tests. */
    fun span(a: LocalDate, b: LocalDate) = ChronoUnit.DAYS.between(a, b)
}
