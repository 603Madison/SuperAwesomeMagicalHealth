package com.health.server.platform

import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.LeakSize
import com.health.api.Module
import com.health.api.MoodEntry
import com.health.api.WaterEntry
import com.health.api.WatchDose
import com.health.api.WatchHabit
import com.health.api.WatchLog
import com.health.api.WatchLogKind
import com.health.api.WatchToday
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.util.Days
import io.ktor.http.HttpStatusCode

/** The watch app's view of today, and its one-tap logs. */
class WatchService(private val services: Services) {
    suspend fun today(userId: String): WatchToday {
        val profile = services.profiles.get(userId)
        val zone = Days(Days.zoneOf(profile.timezone))
        val day = services.days.summary(userId, "today")
        val modules = profile.modules
        val doses = if (Module.MEDICATIONS in modules) {
            services.modules.meds.day(userId, zone.today(services.clock())).due.map { d ->
                WatchDose(d.medicationId, d.scheduledFor, d.name, Say.time(d.scheduledFor, zone.zone), d.log?.status == DoseStatus.TAKEN)
            }
        } else emptyList()
        val habits = if (Module.HABITS in modules) {
            day.modules.habits.orEmpty().mapNotNull { h -> h.habit.id?.let { WatchHabit(it, h.habit.name, h.done) } }
        } else emptyList()
        return WatchToday(
            date = day.date,
            readiness = day.readiness?.score,
            sleep = day.sleep?.score,
            activity = day.activity?.score,
            steps = day.steps,
            stepGoal = day.goals.steps,
            waterMl = day.waterMl,
            waterGoal = day.goals.waterMl,
            modules = modules,
            doses = doses,
            habits = habits,
            stress = day.stressDay?.spans?.lastOrNull()?.state,
        )
    }

    /** One tap: logs it through the same services the apps use. */
    suspend fun log(userId: String, log: WatchLog) {
        val now = services.clock()
        val at = log.at?.takeIf { it in (now - 7 * 86_400_000L)..(now + 60_000L) } ?: now
        val zone = services.profiles.days(userId)
        val m = services.modules
        when (log.kind) {
            WatchLogKind.MOOD -> {
                val mood = log.value?.takeIf { it in 1..5 } ?: throw ApiError.badRequest("A mood is 1 to 5")
                m.mind.saveMood(userId, MoodEntry(at = at, mood = mood))
            }
            WatchLogKind.WATER -> {
                val ml = (log.value ?: 250).takeIf { it in 1..3000 } ?: throw ApiError.badRequest("Water is 1 to 3000 ml")
                val entry = services.foods.addWater(userId, WaterEntry(zone.dateOf(at).toString(), ml, at = at))
                services.days.changedDate(userId, java.time.LocalDate.parse(entry.date))
            }
            WatchLogKind.TOILET -> m.continence.save(userId, ContinenceEntry(at, ContinenceKind.URINATION))
            WatchLogKind.LEAK -> m.continence.save(userId, ContinenceEntry(at, ContinenceKind.LEAK, leak = LeakSize.SMALL))
            WatchLogKind.BOWEL -> m.continence.save(userId, ContinenceEntry(at, ContinenceKind.BOWEL))
            WatchLogKind.DOSE_TAKEN -> {
                val ref = log.ref?.trim().orEmpty().ifBlank { throw ApiError.badRequest("Which dose?") }
                val medicationId = ref.substringBefore('@')
                val slot = ref.substringAfter('@', "").toLongOrNull()
                m.meds.logDose(userId, DoseLog(medicationId, at, DoseStatus.TAKEN, scheduledFor = slot))
            }
            // These features are being built alongside this one; they're wired in once they're here.
            WatchLogKind.COFFEE, WatchLogKind.HABIT, WatchLogKind.HOT_FLASH, WatchLogKind.SYMPTOM ->
                throw ApiError(HttpStatusCode.NotImplemented, "This server can't log that from the watch yet")
        }
    }
}
