package com.health.server.neuro

import com.health.api.Insight
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.NeuroToday
import com.health.api.Profile
import com.health.server.insights.DayExtras
import com.health.server.insights.DayExtrasResult
import com.health.server.insights.ModuleTrend
import com.health.server.transition.TransitionService
import java.time.LocalDate

/** Autism & ADHD and transition: their part of Today, and their insights. */
class NeuroTransitionDay(private val neuro: NeuroService, private val transition: TransitionService) : DayExtras {
    override suspend fun today(userId: String, profile: Profile, date: LocalDate): DayExtrasResult {
        val on = profile.modules
        val insights = mutableListOf<Pair<Int, Insight>>()
        var modules = ModulesToday()
        if (Module.NEURO in on) {
            val (today, extra) = neuro.today(userId, profile, date)
            modules = modules.copy(neuro = today)
            insights += extra
        } else if (Module.MIND in on) {
            // Mind's energy budget (Fields.MIND_ENERGY_BUDGET) keeps the same days: just the energy.
            neuro.energyOn(userId, date)?.let { e ->
                modules = modules.copy(neuro = NeuroToday(energyLeft = e.startWith?.let { s -> s - e.spends.sumOf { it.amount } }, socialBattery = e.socialBattery))
            }
        }
        if (Module.TRANSITION in on) {
            val (today, extra) = transition.today(userId, date)
            modules = modules.copy(transition = today)
            insights += extra
        }
        return DayExtrasResult(modules, insights, cyclePhase = null)
    }

    override suspend fun trends(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, ModuleTrend> = emptyMap()
}
