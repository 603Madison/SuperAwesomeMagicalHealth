package com.health.server.tracking

import com.health.api.ApiJson
import com.health.api.DoseStatus
import com.health.api.Habit
import com.health.api.HabitSource
import com.health.api.HabitStatus
import com.health.api.HabitTick
import com.health.api.Insight
import com.health.api.Module
import com.health.api.Profile
import com.health.server.ApiError
import com.health.server.db.Database
import com.health.server.health.ProfileStore
import com.health.server.insights.DayService
import com.health.server.modules.ModuleService
import com.health.server.modules.Schedules
import com.health.server.util.Days
import com.health.server.util.Random
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Habits: ticked by hand, or by what's already recorded (steps, sleep, water, a mood, every dose
 * taken…). Streaks forgive: a week counts when it reaches the habit's days-a-week, and a day
 * streak lets one missed day in any seven go.
 */
class HabitsService(
    private val db: Database,
    private val profiles: ProfileStore,
    private val days: DayService,
    private val modules: ModuleService,
    private val drinks: DrinksService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A habit with the day it began: when it was made, or its first tick if that's earlier (days ticked afterwards count). */
    class Kept(val habit: Habit, val since: LocalDate)

    suspend fun list(userId: String, all: Boolean = false): List<Habit> = kept(userId, all).map { it.habit }

    suspend fun kept(userId: String, all: Boolean = false): List<Kept> {
        val zone = profiles.days(userId)
        class Row(val id: String, val data: String, val created: Long, val firstTick: String?)
        return db.tx {
            query(
                "SELECT h.id, h.data, h.created_at, (SELECT MIN(c.date) FROM habit_checks c WHERE c.habit_id = h.id AND c.count > 0) AS first_tick " +
                    "FROM habits h WHERE h.user_id = ?${if (all) "" else " AND h.active = 1"} ORDER BY h.sort, h.created_at",
                userId,
            ) { r -> Row(r.string("id"), r.string("data"), r.long("created_at"), r.stringOrNull("first_tick")) }
        }.mapNotNull { row ->
            val made = zone.dateOf(row.created)
            val since = row.firstTick?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { it.isBefore(made) } ?: made
            runCatching { ApiJson.decodeFromString(Habit.serializer(), row.data) }.getOrNull()?.let { Kept(it.copy(id = row.id), since) }
        }
    }

    suspend fun save(userId: String, input: Habit, id: String? = null): Habit {
        val habit = validated(input)
        val saved = id ?: Random.id()
        db.tx {
            if (id != null) {
                if (update("UPDATE habits SET data = ?, active = ?, updated_at = ? WHERE id = ? AND user_id = ?", ApiJson.encodeToString(Habit.serializer(), habit), habit.active, clock(), id, userId) == 0) {
                    throw ApiError.notFound("No such habit")
                }
            } else {
                if (long("SELECT COUNT(*) FROM habits WHERE user_id = ?", userId) >= 50) throw ApiError.badRequest("That's a lot of habits: 50 at most")
                val sort = long("SELECT COALESCE(MAX(sort), 0) + 1 FROM habits WHERE user_id = ?", userId)
                update(
                    "INSERT INTO habits (id, user_id, data, active, sort, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    saved, userId, ApiJson.encodeToString(Habit.serializer(), habit), habit.active, sort, clock(), clock(),
                )
            }
        }
        return habit.copy(id = saved)
    }

    suspend fun delete(userId: String, id: String) = db.tx {
        if (update("DELETE FROM habits WHERE id = ? AND user_id = ?", id, userId) == 0) throw ApiError.notFound("No such habit")
    }

    /** Sets a day's count (0 clears it). */
    suspend fun tick(userId: String, id: String, tick: HabitTick): HabitTick {
        val date = runCatching { LocalDate.parse(tick.date) }.getOrNull() ?: throw ApiError.badRequest("Dates look like 2026-01-31")
        if (tick.count !in 0..50) throw ApiError.badRequest("A day's count is from 0 to 50")
        if (date.isAfter(profiles.days(userId).today(clock()))) throw ApiError.badRequest("That day hasn't happened yet")
        db.tx {
            one("SELECT id FROM habits WHERE id = ? AND user_id = ?", id, userId) { it.string("id") } ?: throw ApiError.notFound("No such habit")
            if (tick.count == 0) update("DELETE FROM habit_checks WHERE habit_id = ? AND date = ?", id, date.toString())
            else update("INSERT OR REPLACE INTO habit_checks (habit_id, user_id, date, count) VALUES (?, ?, ?, ?)", id, userId, date.toString(), tick.count)
        }
        return HabitTick(date.toString(), tick.count)
    }

    suspend fun history(userId: String, id: String, count: Int): List<HabitTick> {
        val to = profiles.days(userId).today(clock())
        val from = to.minusDays((count.coerceIn(1, 3650) - 1).toLong())
        return db.tx {
            one("SELECT id FROM habits WHERE id = ? AND user_id = ?", id, userId) { it.string("id") } ?: throw ApiError.notFound("No such habit")
            query("SELECT date, count FROM habit_checks WHERE habit_id = ? AND date >= ? AND date <= ? ORDER BY date", id, from.toString(), to.toString()) {
                HabitTick(it.string("date"), it.int("count"))
            }
        }
    }

    /** Each active habit's status on [date] (default today). */
    suspend fun statuses(userId: String, date: LocalDate? = null): List<HabitStatus> {
        val profile = profiles.get(userId)
        val zone = Days(Days.zoneOf(profile.timezone))
        val day = date ?: zone.today(clock())
        val habits = kept(userId, all = false)
        if (habits.isEmpty()) return emptyList()
        val first = habits.minOf { it.since }
        // Two months is usually enough; a streak that reaches back that far is followed further.
        val recentFrom = maxOf(first, day.minusDays(62))
        val recentDone = doneDays(userId, profile, zone, habits, recentFrom, day)
        val recent = habits.map { k -> status(k, day, recentDone[k.habit.id].orEmpty()) }
        if (recentFrom == first || recent.none { it.streakDays >= 50 || it.streakWeeks >= 8 }) return recent
        val done = doneDays(userId, profile, zone, habits, maxOf(first, day.minusDays(LOOKBACK_DAYS)), day)
        return habits.map { k -> status(k, day, done[k.habit.id].orEmpty()) }
    }

    /** Each habit's count on each day from [from] to [to], with auto-sourced ones counting 1 when done. */
    suspend fun doneDays(userId: String, profile: Profile, zone: Days, habits: List<Kept>, from: LocalDate, to: LocalDate): Map<String?, Map<LocalDate, Pair<Int, Boolean>>> {
        if (to.isBefore(from)) return emptyMap()
        val ticks = db.tx {
            query("SELECT habit_id, date, count FROM habit_checks WHERE user_id = ? AND date >= ? AND date <= ?", userId, from.toString(), to.toString()) {
                Triple(it.string("habit_id"), LocalDate.parse(it.string("date")), it.int("count"))
            }
        }.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, list) -> list.toMap() }
        val sources = habits.map { it.habit.source }.toSet()
        val auto = autoDone(userId, profile, zone, sources - HabitSource.MANUAL, from, to)
        val range = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        return habits.associate { k ->
            val h = k.habit
            k.habit.id to range.associateWith { d ->
                val count = ticks[h.id]?.get(d) ?: 0
                val byData = h.source != HabitSource.MANUAL && auto[h.source]?.contains(d) == true
                val isDone = byData || count >= h.perDay
                (if (byData) maxOf(count, 1) else count) to isDone
            }
        }
    }

    /** The days each source was met. */
    private suspend fun autoDone(userId: String, profile: Profile, zone: Days, sources: Set<HabitSource>, from: LocalDate, to: LocalDate): Map<HabitSource, Set<LocalDate>> {
        if (sources.isEmpty()) return emptyMap()
        val out = mutableMapOf<HabitSource, Set<LocalDate>>()
        val goals = profile.goals
        val range = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        val needsStats = sources.any { it in setOf(HabitSource.STEPS_GOAL, HabitSource.SLEEP_GOAL, HabitSource.WATER_GOAL, HabitSource.WORKOUT) }
        if (needsStats) {
            val stats = days.stats(userId, from, to, zone).associateBy { LocalDate.parse(it.date) }
            out[HabitSource.STEPS_GOAL] = range.filter { (stats[it]?.steps ?: 0) >= goals.steps && goals.steps > 0 }.toSet()
            out[HabitSource.SLEEP_GOAL] = range.filter { (stats[it]?.sleep?.asleepMinutes ?: 0) >= goals.sleepMinutes }.toSet()
            out[HabitSource.WATER_GOAL] = range.filter { (stats[it]?.waterMl ?: 0) >= goals.waterMl && goals.waterMl > 0 }.toSet()
            out[HabitSource.WORKOUT] = range.filter { (stats[it]?.workouts ?: 0) > 0 }.toSet()
        }
        if (sources.any { it in setOf(HabitSource.MOOD_LOGGED, HabitSource.JOURNAL, HabitSource.MINDFUL) }) {
            val mind = modules.mind.daysOf(userId, from, to)
            out[HabitSource.MOOD_LOGGED] = mind.filterValues { it.entries > 0 }.keys
            out[HabitSource.JOURNAL] = mind.filterValues { it.journal > 0 }.keys
            out[HabitSource.MINDFUL] = mind.filterValues { it.mindfulMinutes > 0 }.keys
        }
        if (HabitSource.PELVIC_FLOOR in sources) {
            out[HabitSource.PELVIC_FLOOR] = modules.continence.daysOf(userId, from, to).filterValues { it.pelvicFloor > 0 }.keys
        }
        if (HabitSource.MEDS_TAKEN in sources) {
            val meds = modules.meds.list(userId)
            val logs = modules.meds.doses(userId, from, to.plusDays(1))
            out[HabitSource.MEDS_TAKEN] = range.filter { d ->
                val slots = meds.flatMap { m -> Schedules.times(m.schedule, d, zone).map { m.id to it } }
                slots.all { (id, slot) -> logs.any { it.medicationId == id && it.scheduledFor == slot && it.status == DoseStatus.TAKEN } }
            }.toSet()
        }
        if (HabitSource.NO_ALCOHOL in sources || HabitSource.CAFFEINE_CUTOFF in sources) {
            val list = drinks.list(userId, from, to).groupBy { LocalDate.parse(it.date!!) }
            out[HabitSource.NO_ALCOHOL] = range.filter { d -> list[d].orEmpty().none { it.alcoholG > 0 } }.toSet()
            out[HabitSource.CAFFEINE_CUTOFF] = range.filter { d -> list[d].orEmpty().none { it.caffeineMg > 0 && zone.hourOf(it.at) >= 14 } }.toSet()
        }
        return out
    }

    /** Habits' insights for Today: streak milestones, and what's left in the evening. */
    fun insights(statuses: List<HabitStatus>, isToday: Boolean, hourNow: Int): List<Pair<Int, Insight>> = buildList {
        statuses.firstOrNull { it.streakWeeks in MILESTONES && it.weekDone >= it.habit.timesPerWeek }?.let { s ->
            add(34 to Insight("good", "${s.streakWeeks}-week streak", "${s.habit.name}: ${s.streakWeeks} weeks in a row. Lovely and steady.", "sparkles", Module.HABITS))
        }
        if (isToday && hourNow >= 18) {
            val left = statuses.filter { !it.done && it.weekDone < it.habit.timesPerWeek }
            if (left.isNotEmpty()) {
                add(33 to Insight("info", if (left.size == 1) "1 habit left today" else "${left.size} habits left today", left.joinToString(", ") { it.habit.name }, "sparkles", Module.HABITS))
            }
        }
    }

    companion object {
        /** How far back streaks are counted. */
        const val LOOKBACK_DAYS = 371L
        private val MILESTONES = setOf(4, 8, 12, 26, 52)
        private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

        fun weekStart(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())

        /** One habit on [date], from each day's (count, done) since it began. */
        fun status(k: Kept, date: LocalDate, days: Map<LocalDate, Pair<Int, Boolean>>): HabitStatus {
            val h = k.habit
            fun done(d: LocalDate) = days[d]?.second == true
            val ws = weekStart(date)
            val weekDone = generateSequence(maxOf(ws, k.since)) { it.plusDays(1) }.takeWhile { !it.isAfter(date) }.count { done(it) }
            return HabitStatus(
                habit = h,
                date = date.toString(),
                count = days[date]?.first ?: 0,
                done = done(date),
                weekDone = weekDone,
                streakWeeks = streakWeeks(h.timesPerWeek, k.since, date, ::done),
                streakDays = streakDays(k.since, date, ::done),
                recent = (13L downTo 0L).map { back -> date.minusDays(back).let { d -> if (d.isBefore(k.since)) null else done(d) } },
            )
        }

        /** A week's target: the habit's days a week, or its share of the days left in the week it began. */
        private fun target(timesPerWeek: Int, weekStart: LocalDate, since: LocalDate): Int {
            val first = maxOf(weekStart, since)
            val available = ChronoUnit.DAYS.between(first, weekStart.plusDays(7)).toInt()
            if (available >= 7) return timesPerWeek
            return ((timesPerWeek * available + 6) / 7).coerceIn(1, available)
        }

        /**
         * Weeks in a row that reached the target. This week counts once it's reached; while it
         * still can be, it doesn't break the streak; once it can't, the streak is over.
         */
        fun streakWeeks(timesPerWeek: Int, since: LocalDate, date: LocalDate, done: (LocalDate) -> Boolean): Int {
            val ws = weekStart(date)
            val thisWeek = generateSequence(maxOf(ws, since)) { it.plusDays(1) }.takeWhile { !it.isAfter(date) }.count(done)
            val left = ChronoUnit.DAYS.between(date, ws.plusDays(6)).toInt() + if (done(date)) 0 else 1
            val target = target(timesPerWeek, ws, since)
            var streak = when {
                thisWeek >= target -> 1
                thisWeek + left >= target -> 0
                else -> return 0
            }
            var w = ws.minusWeeks(1)
            while (!w.plusDays(6).isBefore(since) && !w.isBefore(date.minusDays(LOOKBACK_DAYS))) {
                val count = generateSequence(maxOf(w, since)) { it.plusDays(1) }.takeWhile { !it.isAfter(w.plusDays(6)) }.count(done)
                if (count >= target(timesPerWeek, w, since)) streak++ else break
                w = w.minusWeeks(1)
            }
            return streak
        }

        /** Days in a row, letting one missed day in any seven go; today not done yet doesn't break it. */
        fun streakDays(since: LocalDate, date: LocalDate, done: (LocalDate) -> Boolean): Int {
            var count = 0
            var lastMiss: LocalDate? = null
            var d = if (done(date)) date else date.minusDays(1)
            while (!d.isBefore(since) && !d.isBefore(date.minusDays(LOOKBACK_DAYS))) {
                if (done(d)) {
                    count++
                } else if (lastMiss == null || ChronoUnit.DAYS.between(d, lastMiss) >= 7) {
                    lastMiss = d
                } else {
                    break
                }
                d = d.minusDays(1)
            }
            return count
        }

        fun validated(h: Habit): Habit {
            val name = h.name.trim().take(60)
            if (name.isEmpty()) throw ApiError.badRequest("Give the habit a name")
            if (h.timesPerWeek !in 1..7) throw ApiError.badRequest("Days a week is from 1 to 7")
            if (h.perDay !in 1..20) throw ApiError.badRequest("Times a day is from 1 to 20")
            h.reminderTime?.let { if (!TIME.matches(it)) throw ApiError.badRequest("Reminder times look like 08:30") }
            return h.copy(name = name, icon = h.icon.trim().take(40).ifBlank { "sparkles" }, id = null)
        }
    }
}
