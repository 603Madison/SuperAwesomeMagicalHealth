package com.health.server.care

import com.health.api.Insight
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.Profile
import com.health.server.util.Days
import java.time.LocalDate

/** Hormone therapy, life stages and appointments: their part of Today, its insights, and trends. */
class CareDay(
    private val hormones: HormonesService,
    private val lifeStages: LifeStageService,
    private val appointments: AppointmentsService,
) {
    /** Adds the care features to [current] (what the other features put on Today), and their insights to [insights]. */
    suspend fun today(userId: String, profile: Profile, date: LocalDate, current: ModulesToday, insights: MutableList<Pair<Int, Insight>>): ModulesToday {
        val on = profile.modules
        var m = current
        if (Module.HORMONES in on) {
            val (today, extra) = hormones.today(userId, date)
            m = m.copy(hormones = today)
            insights += extra
        }
        if (Module.CYCLE in on) {
            val (today, extra) = lifeStages.today(userId, profile, date, m)
            m = m.copy(lifeStage = today)
            insights += extra
        }
        if (Module.APPOINTMENTS in on) {
            val (list, extra) = appointments.today(userId, date, profile.timezone)
            m = m.copy(appointments = list)
            insights += extra
        }
        return m
    }

    /** Hot flashes a day, for trends. */
    suspend fun hotFlashes(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, Int> {
        if (Module.CYCLE !in profile.modules) return emptyMap()
        val zone = Days(Days.zoneOf(profile.timezone))
        return lifeStages.hotFlashesBetween(userId, from, to).groupingBy { zone.dateOf(it.at).toString() }.eachCount()
    }
}
