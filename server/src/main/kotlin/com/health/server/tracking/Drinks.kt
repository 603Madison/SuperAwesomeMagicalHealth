package com.health.server.tracking

import com.health.api.Drink
import com.health.api.DrinkKind
import com.health.api.DrinksDay
import com.health.api.DrinksSummary
import com.health.api.Insight
import com.health.server.ApiError
import com.health.server.db.Database
import com.health.server.health.ProfileStore
import com.health.server.health.Records
import com.health.server.insights.DayService
import com.health.server.insights.DayStats
import com.health.server.insights.Scoring
import com.health.server.util.Days
import com.health.server.util.Random
import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.roundToInt

/** Coffee, tea, alcohol and the rest. Everything but alcohol counts towards the day's water. */
class DrinksService(
    private val db: Database,
    private val profiles: ProfileStore,
    private val records: Records,
    private val days: DayService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun list(userId: String, from: LocalDate, to: LocalDate): List<Drink> = db.tx {
        query("SELECT * FROM drinks WHERE user_id = ? AND date >= ? AND date <= ? ORDER BY at", userId, from.toString(), to.toString()) { r ->
            Drink(
                at = r.long("at"), kind = runCatching { DrinkKind.valueOf(r.string("kind")) }.getOrDefault(DrinkKind.OTHER),
                volumeMl = r.int("volume_ml"), caffeineMg = r.double("caffeine_mg"), alcoholG = r.double("alcohol_g"),
                name = r.stringOrNull("name"), id = r.string("id"), date = r.string("date"),
            )
        }
    }

    suspend fun save(userId: String, input: Drink, id: String? = null): Drink {
        val drink = validated(input, clock())
        val zone = profiles.days(userId)
        val date = zone.dateOf(drink.at)
        val before = id?.let { existing -> db.tx { one("SELECT date FROM drinks WHERE id = ? AND user_id = ?", existing, userId) { it.string("date") } } ?: throw ApiError.notFound("No such drink") }
        val saved = id ?: Random.id()
        db.tx {
            if (id != null) {
                update(
                    "UPDATE drinks SET date = ?, at = ?, kind = ?, volume_ml = ?, caffeine_mg = ?, alcohol_g = ?, name = ? WHERE id = ? AND user_id = ?",
                    date.toString(), drink.at, drink.kind, drink.volumeMl, drink.caffeineMg, drink.alcoholG, drink.name, id, userId,
                )
            } else {
                update(
                    "INSERT INTO drinks (id, user_id, date, at, kind, volume_ml, caffeine_mg, alcohol_g, name, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    saved, userId, date.toString(), drink.at, drink.kind, drink.volumeMl, drink.caffeineMg, drink.alcoholG, drink.name, clock(),
                )
            }
        }
        // Water (and the night after a drink) change.
        days.changedDate(userId, date)
        before?.let { days.changedDate(userId, LocalDate.parse(it)) }
        return drink.copy(id = saved, date = date.toString())
    }

    suspend fun delete(userId: String, id: String) {
        val date = db.tx {
            val d = one("SELECT date FROM drinks WHERE id = ? AND user_id = ?", id, userId) { it.string("date") } ?: throw ApiError.notFound("No such drink")
            update("DELETE FROM drinks WHERE id = ?", id)
            d
        }
        days.changedDate(userId, LocalDate.parse(date))
    }

    suspend fun day(userId: String, date: LocalDate): DrinksDay {
        val zone = profiles.days(userId)
        val bedtime = usualBedtime(userId, date, zone)
        return dayOf(date, list(userId, date, date), bedtime, zone.start(date))
    }

    /** Each day with drinks from [from] to [to]. */
    suspend fun daysOf(userId: String, from: LocalDate, to: LocalDate): Map<LocalDate, DrinksDay> =
        list(userId, from, to).groupBy { LocalDate.parse(it.date!!) }.mapValues { (date, list) -> dayOf(date, list, null) }

    suspend fun summary(userId: String, count: Int): DrinksSummary {
        val profile = profiles.get(userId)
        val zone = Days(Days.zoneOf(profile.timezone))
        val to = zone.today(clock())
        val from = to.minusDays((count.coerceIn(1, 730) - 1).toLong())
        val byDay = daysOf(userId, from, to)
        val list = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { byDay[it] ?: DrinksDay(it.toString()) }.toList()
        val stats = days.stats(userId, from, to.plusDays(1), zone).associateBy { LocalDate.parse(it.date) }
        val logged = list.filter { it.drinks.isNotEmpty() }
        return DrinksSummary(
            days = list,
            averageCaffeineMg = logged.takeIf { it.isNotEmpty() }?.map { it.caffeineMg }?.average()?.roundToInt()?.toDouble(),
            averageStandardDrinks = logged.takeIf { it.isNotEmpty() }?.map { it.standardDrinks }?.average()?.let { (it * 10).roundToInt() / 10.0 },
            patterns = patterns(list, stats, profile.goals, zone),
        )
    }

    /** The day's insights: caffeine close to bedtime today, and last night after alcohol. */
    suspend fun insights(userId: String, date: LocalDate, zone: Days, isToday: Boolean, hourNow: Int, history: List<DayStats>): List<Pair<Int, Insight>> = buildList {
        val today = day(userId, date)
        val atBedtime = today.caffeineAtBedtimeMg
        if (isToday && hourNow >= 12 && atBedtime != null && atBedtime >= 50) {
            val bed = usualBedtime(userId, date, zone)
            add(46 to Insight("info", "Caffeine at bedtime", "About ${atBedtime.roundToInt()} mg of today's caffeine will still be around at your usual bedtime (${clock(bed)}). It can make sleep lighter.", "moon"))
        }
        val yesterday = daysOf(userId, date.minusDays(1), date.minusDays(1))[date.minusDays(1)]
        val night = history.lastOrNull { it.date == date.toString() }
        if (yesterday != null && yesterday.standardDrinks >= 2 && night?.hrv != null) {
            val usual = Scoring.median(history.filter { it.date != date.toString() }.takeLast(28).mapNotNull { it.hrv })
            if (usual != null && night.hrv < usual * 0.9) {
                add(66 to Insight("info", "Alcohol last night", "After ${drinksText(yesterday.standardDrinks)}, your HRV was ${night.hrv.roundToInt()} ms against your usual ${usual.roundToInt()} ms. Your body works to clear alcohol while you sleep.", "moon"))
            }
        }
    }

    /** When the person usually goes to bed, as minutes after midnight (may be over 1440 for after midnight); 23:00 to start. */
    suspend fun usualBedtime(userId: String, date: LocalDate, zone: Days): Int {
        val nights = records.sleep(userId, zone.start(date.minusDays(14)), zone.end(date)).filter { it.end - it.start >= 3 * 3_600_000L }
        val starts = nights.map { s -> zone.minuteOfDay(s.start).let { if (it < 12 * 60) it + 1440 else it } }
        return Scoring.median(starts.map { it.toDouble() })?.roundToInt() ?: (23 * 60)
    }

    private fun patterns(list: List<DrinksDay>, stats: Map<LocalDate, DayStats>, goals: com.health.api.Goals, zone: Days): List<Insight> = buildList {
        // The night after each day: its sleep score and HRV are in the next day's numbers.
        fun nightAfter(d: DrinksDay) = stats[LocalDate.parse(d.date).plusDays(1)]
        fun compare(title: String, label: String, with: List<DrinksDay>, without: List<DrinksDay>) {
            val a = with.mapNotNull { nightAfter(it)?.let { n -> Scoring.sleep(n.sleep, goals, zone.zone)?.score?.toDouble() } }
            val b = without.mapNotNull { nightAfter(it)?.let { n -> Scoring.sleep(n.sleep, goals, zone.zone)?.score?.toDouble() } }
            if (a.size >= 4 && b.size >= 4 && b.average() - a.average() >= 4) {
                add(Insight("info", title, "Your sleep score averaged ${a.average().roundToInt()} $label, against ${b.average().roundToInt()} otherwise.", "moon"))
            }
        }
        val late = list.filter { d -> d.lastCaffeineAt?.let { zone.hourOf(it) >= 14 } == true }
        val early = list.filter { d -> d.drinks.isNotEmpty() && (d.lastCaffeineAt == null || zone.hourOf(d.lastCaffeineAt!!) < 14) }
        compare("Caffeine after 2 pm", "after caffeine in the afternoon or evening", late, early)
        val drinking = list.filter { it.standardDrinks >= 2 }
        val none = list.filter { it.drinks.isNotEmpty() && it.alcoholG == 0.0 }
        compare("Alcohol and sleep", "after two or more drinks", drinking, none)
        val hrvWith = drinking.mapNotNull { nightAfter(it)?.hrv }
        val hrvWithout = none.mapNotNull { nightAfter(it)?.hrv }
        if (hrvWith.size >= 4 && hrvWithout.size >= 4 && hrvWith.average() < hrvWithout.average() * 0.9) {
            add(Insight("info", "Alcohol and HRV", "Your HRV averaged ${hrvWith.average().roundToInt()} ms after two or more drinks, against ${hrvWithout.average().roundToInt()} ms otherwise.", "activity"))
        }
    }

    companion object {
        /** Grams of alcohol in a standard drink. */
        const val STANDARD_DRINK_G = 14.0
        /** Caffeine's half-life in the body, in hours (about 5 for most people). */
        const val HALF_LIFE_HOURS = 5.0

        fun dayOf(date: LocalDate, list: List<Drink>, bedtimeMinute: Int?, dayStart: Long = 0): DrinksDay {
            val caffeine = list.sumOf { it.caffeineMg }
            val alcohol = list.sumOf { it.alcoholG }
            val lastCaffeine = list.filter { it.caffeineMg > 0 }.maxOfOrNull { it.at }
            return DrinksDay(
                date = date.toString(),
                drinks = list,
                caffeineMg = caffeine,
                alcoholG = alcohol,
                standardDrinks = (alcohol / STANDARD_DRINK_G * 10).roundToInt() / 10.0,
                lastCaffeineAt = lastCaffeine,
                caffeineAtBedtimeMg = bedtimeMinute?.takeIf { caffeine > 0 }?.let { atBedtime(list, it, dayStart) },
                fluidMl = list.filter { it.alcoholG == 0.0 }.sumOf { it.volumeMl },
            )
        }

        /** Caffeine left at bedtime ([bedtimeMinute] after the day's midnight), halving every five hours. */
        fun atBedtime(list: List<Drink>, bedtimeMinute: Int, dayStart: Long): Double {
            val bed = dayStart + bedtimeMinute * 60_000L
            return list.filter { it.caffeineMg > 0 && it.at <= bed }.sumOf { d ->
                d.caffeineMg * 0.5.pow((bed - d.at) / 3_600_000.0 / HALF_LIFE_HOURS)
            }.roundToInt().toDouble()
        }

        fun drinksText(n: Double) = if (n == 1.0) "1 drink" else "${if (n % 1.0 == 0.0) n.roundToInt().toString() else n.toString()} drinks"

        private fun clock(minute: Int) = (minute % 1440).let { String.format(java.util.Locale.ROOT, "%d:%02d", it / 60, it % 60) }

        fun validated(d: Drink, now: Long): Drink {
            if (d.volumeMl !in 1..5000) throw ApiError.badRequest("A drink is between 1 and 5,000 ml")
            if (!d.caffeineMg.isFinite() || d.caffeineMg !in 0.0..2000.0) throw ApiError.badRequest("Caffeine is in mg, up to 2,000")
            if (!d.alcoholG.isFinite() || d.alcoholG !in 0.0..500.0) throw ApiError.badRequest("Alcohol is in grams, up to 500")
            if (d.at > now + 3_600_000 || d.at < 946_684_800_000L) throw ApiError.badRequest("That time doesn't look right")
            return d.copy(name = d.name?.trim()?.take(120)?.ifBlank { null }, id = null, date = null)
        }
    }
}
