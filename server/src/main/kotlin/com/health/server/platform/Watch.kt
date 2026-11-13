package com.health.server.platform

import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.Drink
import com.health.api.DrinkKind
import com.health.api.HabitTick
import com.health.api.HotFlash
import com.health.api.LeakSize
import com.health.api.Module
import com.health.api.MoodEntry
import com.health.api.SymptomEntry
import com.health.api.WaterEntry
import com.health.api.WatchDose
import com.health.api.WatchHabit
import com.health.api.WatchLog
import com.health.api.WatchLogKind
import com.health.api.WatchToday
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.util.Days
import kotlin.math.roundToInt

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
        // Water turned off: no water tap and no water progress.
        val water = services.days.showsWater(userId)
        return WatchToday(
            date = day.date,
            readiness = day.readiness?.score,
            sleep = day.sleep?.score,
            activity = day.activity?.score,
            steps = day.steps,
            stepGoal = day.goals.steps,
            waterMl = if (water) day.waterMl else 0,
            waterGoal = if (water) day.goals.waterMl else 0,
            modules = modules,
            doses = doses,
            habits = habits,
            stress = day.stressDay?.spans?.lastOrNull()?.state,
            water = water,
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
                // An older watch app (or a tap queued before water was turned off) may still send one.
                if (!services.days.showsWater(userId)) throw ApiError.badRequest("Water is turned off. You can turn it on in Settings › Features, under Food.")
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
            WatchLogKind.COFFEE -> {
                val ml = (log.value ?: DrinkKind.COFFEE.defaultMl).takeIf { it in 1..2000 } ?: throw ApiError.badRequest("A coffee is 1 to 2000 ml")
                services.tracking.drinks.save(userId, Drink(at, DrinkKind.COFFEE, ml, caffeineMg = (ml * DrinkKind.COFFEE.caffeineMgPer100Ml / 100).roundToInt().toDouble()))
            }
            WatchLogKind.HABIT -> {
                val id = log.ref?.trim().orEmpty().ifBlank { throw ApiError.badRequest("Which habit?") }
                val habit = services.tracking.habits.list(userId).firstOrNull { it.id == id } ?: throw ApiError.notFound("No such habit")
                // A tap marks it done for the day (0 clears it).
                val count = if (log.value == 0) 0 else habit.perDay
                services.tracking.habits.tick(userId, id, HabitTick(zone.dateOf(at).toString(), count))
            }
            WatchLogKind.HOT_FLASH -> {
                val intensity = (log.value ?: 2).coerceIn(1, 3)
                services.lifeStages.saveHotFlash(userId, HotFlash(at, intensity, night = zone.hourOf(at) !in 7..21))
            }
            WatchLogKind.SYMPTOM -> {
                val symptom = log.ref?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 60 } ?: throw ApiError.badRequest("Which symptom?")
                val severity = (log.value ?: 5).takeIf { it in 0..10 } ?: throw ApiError.badRequest("Severity is 0 to 10")
                services.tracking.symptoms.save(userId, SymptomEntry(at, symptom, severity))
            }
        }
    }
}
