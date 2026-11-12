package com.health.server.tracking

import com.health.api.Insight
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.Profile
import com.health.server.insights.DayExtras
import com.health.server.insights.DayExtrasResult
import com.health.server.insights.DayService
import com.health.server.insights.ModuleTrend
import com.health.server.util.Days
import java.time.LocalDate

/**
 * Blood pressure and glucose, drinks, symptoms and habits: their part in each day (Today's cards
 * and insights) and in trends, for the features the person has on.
 */
class TrackingService(
    val vitals: VitalsService,
    val drinks: DrinksService,
    val symptoms: SymptomsService,
    val habits: HabitsService,
    val weather: Weather,
    private val days: DayService,
    private val clock: () -> Long = System::currentTimeMillis,
) : DayExtras {
    override suspend fun today(userId: String, profile: Profile, date: LocalDate): DayExtrasResult {
        val on = profile.modules
        val zone = Days(Days.zoneOf(profile.timezone))
        val isToday = date == zone.today(clock())
        val hour = zone.hourOf(clock())
        val insights = mutableListOf<Pair<Int, Insight>>()

        val vitalsToday = if (Module.VITALS in on) {
            insights += vitals.insights(userId, profile.vitalsTargets, date, zone, isToday)
            vitals.today(userId, profile.vitalsTargets, date, zone)
        } else null

        val habitStatuses = if (Module.HABITS in on) {
            habits.statuses(userId, date).also { insights += habits.insights(it, isToday, hour) }
        } else null

        val symptomsDay = if (Module.SYMPTOMS in on) {
            symptoms.daysOf(userId, date, date)[date].also { insights += symptoms.insights(userId, date, it) }
        } else null

        val drinksDay = if (Module.NUTRITION in on) {
            val day = drinks.day(userId, date)
            if (day.drinks.isNotEmpty() || isToday) {
                insights += drinks.insights(userId, date, zone, isToday, hour, days.stats(userId, date.minusDays(28), date, zone))
            }
            day.takeIf { it.drinks.isNotEmpty() }
        } else null

        return DayExtrasResult(
            modules = ModulesToday(vitals = vitalsToday, habits = habitStatuses?.takeIf { it.isNotEmpty() }, symptoms = symptomsDay),
            insights = insights,
            cyclePhase = null,
            drinks = drinksDay,
        )
    }

    override suspend fun trends(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, ModuleTrend> {
        val on = profile.modules
        val zone = Days(Days.zoneOf(profile.timezone))
        val out = mutableMapOf<String, ModuleTrend>()
        fun at(date: LocalDate) = out.getOrPut(date.toString()) { ModuleTrend() }
        if (Module.VITALS in on) {
            vitals.daily(userId, from, to, zone).forEach { (date, v) ->
                out[date.toString()] = at(date).copy(systolic = v.first, diastolic = v.second, glucose = v.third)
            }
        }
        if (Module.NUTRITION in on) {
            drinks.daysOf(userId, from, to).forEach { (date, d) ->
                out[date.toString()] = at(date).copy(caffeineMg = d.caffeineMg, standardDrinks = d.standardDrinks)
            }
        }
        if (Module.SYMPTOMS in on) {
            symptoms.daysOf(userId, from, to).forEach { (date, d) ->
                out[date.toString()] = at(date).copy(symptomWorst = d.worst, migraine = d.migraines.isNotEmpty())
            }
        }
        if (Module.HABITS in on) {
            val kept = habits.kept(userId)
            if (kept.isNotEmpty()) {
                val done = habits.doneDays(userId, profile, zone, kept, maxOf(from, kept.minOf { it.since }), to)
                var date = from
                while (!date.isAfter(to)) {
                    val due = kept.filter { !it.since.isAfter(date) }
                    if (due.isNotEmpty()) {
                        out[date.toString()] = at(date).copy(habitsDue = due.size, habitsDone = due.count { done[it.habit.id]?.get(date)?.second == true })
                    }
                    date = date.plusDays(1)
                }
            }
        }
        return out
    }
}
