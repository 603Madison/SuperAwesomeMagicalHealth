package com.health.server.modules

import com.health.api.CyclePhase
import com.health.api.DoseStatus
import com.health.api.Insight
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.Profile
import com.health.api.Screener
import com.health.server.insights.DayExtras
import com.health.server.insights.DayExtrasResult
import com.health.server.insights.ModuleTrend
import com.health.server.util.Days
import java.time.LocalDate

/** The health modules' part in each day: Today's cards, their insights, and their trend numbers. */
class ModuleService(
    val mind: MindService,
    val cycle: CycleService,
    val continence: ContinenceService,
    val meds: MedsService,
    private val clock: () -> Long = System::currentTimeMillis,
) : DayExtras {
    /** Hormone therapy, life stages and appointments, once they exist (care/CareDay). */
    var care: com.health.server.care.CareDay? = null

    /** Supplies (bladder and bowel, periods), for what's running low. Set at start-up. */
    var supplies: SuppliesService? = null

    /** Whether the person wants cycle copy without fertility words (neutral wording, or the fertile window hidden). Set at start-up. */
    var plainCycle: (suspend (userId: String) -> Boolean)? = null

    override suspend fun today(userId: String, profile: Profile, date: LocalDate): DayExtrasResult {
        val on = profile.modules
        val days = Days(Days.zoneOf(profile.timezone))
        val isToday = date == days.today(clock())
        val hour = days.hourOf(clock())
        val insights = mutableListOf<Pair<Int, Insight>>()

        val mindDay = if (Module.MIND in on) {
            val byDay = mind.daysOf(userId, date.minusDays(27), date)
            val week = (0L until 7L).mapNotNull { byDay[date.minusDays(it)]?.mood }
            val before = (7L until 28L).mapNotNull { byDay[date.minusDays(it)]?.mood }
            val today = byDay[date]
            if (today?.mood != null && today.mood!! <= 2.0) {
                insights += 70 to Insight("info", "A hard day", "Be gentle with yourself. A few minutes of slow breathing can take the edge off, and so can reaching out to someone.", "heart", Module.MIND)
            } else if (week.size >= 3 && before.size >= 5 && before.average() - week.average() >= 0.7) {
                insights += 72 to Insight("info", "Your mood has been lower this week", "Sleep, movement and time with people often help. If it keeps up, talking to someone you trust or a professional can too.", "heart", Module.MIND)
            }
            val recent = mind.screenerResults(userId, 14)
            if (recent.any { it.flagged }) {
                insights += 96 to Insight("warn", "You don't have to go through this alone", "Support is a tap away in Mind, any time of day.", "heart", Module.MIND)
            } else if ((byDay.size >= 5) && mind.screenerResults(userId, 28).none { it.screener == Screener.PHQ9 }) {
                insights += 15 to Insight("info", "Time for a check-in?", "A two-minute mood and anxiety check-in every few weeks helps you notice changes.", "sparkles", Module.MIND)
            }
            today
        } else null

        var phase: CyclePhase? = null
        val cycleToday = if (Module.CYCLE in on) {
            val c = cycle.today(userId, date)
            phase = c.phase
            val until = c.daysUntilPeriod
            when {
                until == null -> Unit
                until in 1..3 -> insights += 60 to Insight("info", if (until == 1) "Period likely tomorrow" else "Period likely in $until days", "Based on your recent cycles.", "calendar", Module.CYCLE)
                until == 0 && c.phase != CyclePhase.PERIOD -> insights += 60 to Insight("info", "Period expected today", "Log it when it comes, and the next prediction gets better.", "calendar", Module.CYCLE)
                until <= -3 && c.phase != CyclePhase.PERIOD -> insights += 55 to Insight("info", "Period ${-until} days later than expected", "Stress, travel, illness, exercise and hormones can all shift a cycle.", "calendar", Module.CYCLE)
                else -> Unit
            }
            c
        } else null

        val continenceDay = if (Module.CONTINENCE in on) {
            continence.daysOf(userId, date, date)[date]?.also { d ->
                if (d.leaks >= 3) insights += 50 to Insight("info", "${d.leaks} leaks today", "Noting what came before each one (a cough, a sudden urge) helps find what helps.", "droplet", Module.CONTINENCE)
                if (d.nightVoids >= 2) insights += 48 to Insight("info", "Up ${d.nightVoids} times last night", "Less to drink in the two hours before bed often helps.", "moon", Module.CONTINENCE)
            }.also {
                // The detail: signs of an infection, leaks that came through, sore skin.
                val day = continence.entriesOn(userId, date)
                if (day.any { it.utiSigns.isNotEmpty() }) {
                    insights += 90 to Insight("warn", "Signs that can go with a urine infection", "Worth telling a doctor or nurse about soon, and today if you have a fever or back pain.", "thermometer", Module.CONTINENCE)
                }
                if (day.any { it.leakedThrough }) {
                    insights += 45 to Insight("info", "A leak came through", "A more absorbent product, a better fit, or changing a little sooner can help.", "droplet", Module.CONTINENCE)
                }
                if (day.any { (it.skin ?: 0) >= 2 }) {
                    insights += 44 to Insight("info", "Sore skin today", "Changing soon after a leak, gentle cleansing and a barrier cream help. If it breaks or doesn't settle, a nurse or pharmacist can help.", "droplet", Module.CONTINENCE)
                }
            }
        } else null

        // Supplies running low, for the features they belong to.
        val supplyCategories = buildSet {
            if (Module.CONTINENCE in on) { add(com.health.api.SupplyCategory.CONTINENCE); add(com.health.api.SupplyCategory.OTHER) }
            if (Module.CYCLE in on) add(com.health.api.SupplyCategory.PERIOD)
        }
        val lowSupplies = if (supplyCategories.isEmpty()) null else supplies?.low(userId, supplyCategories)?.takeIf { it.isNotEmpty() }
        // Shown by the apps as their own card (ModulesToday.lowSupplies), so no insight repeats it.

        val medsDay = if (Module.MEDICATIONS in on) {
            meds.day(userId, date).takeIf { it.due.isNotEmpty() || it.asNeeded.isNotEmpty() }?.also { m ->
                val missed = m.due.count { it.log == null && it.scheduledFor < clock() }
                if (isToday && missed > 0 && hour >= 12) {
                    insights += 80 to Insight("info", if (missed == 1) "1 dose still to take" else "$missed doses still to take", m.due.filter { it.log == null && it.scheduledFor < clock() }.joinToString(", ") { it.name }, "pill", Module.MEDICATIONS)
                } else if (m.due.isNotEmpty() && m.due.all { it.log?.status == DoseStatus.TAKEN }) {
                    insights += 35 to Insight("good", "All medications taken", "Nice and steady.", "pill", Module.MEDICATIONS)
                }
                m.lowStock.firstOrNull()?.let { low ->
                    insights += 65 to Insight(
                        "info", if (low.stock == 0) "Out of ${low.name}" else "Running low on ${low.name}",
                        if (low.stock == 0) "Time to order more." else "${low.stock} ${if (low.stock == 1) "dose" else "doses"} left: time to order more.", "pill", Module.MEDICATIONS,
                    )
                }
            }
        } else null

        var modulesToday = ModulesToday(mindDay, cycleToday, continenceDay, medsDay, lowSupplies = lowSupplies)
        // Hormone therapy, life stages and appointments (care/), after the others, since life stages read them.
        care?.let { modulesToday = it.today(userId, profile, date, modulesToday, insights) }
        // No fertile window or ovulation for people who'd rather not see them.
        if (Module.CYCLE in on && plainCycle?.invoke(userId) == true) {
            insights.removeAll { (_, i) -> mentionsFertility(i.title + " " + i.body) }
            modulesToday.lifeStage?.takeIf { mentionsFertility(it.headline) }?.let { ls ->
                modulesToday = modulesToday.copy(lifeStage = ls.copy(headline = cycleToday?.cycleDay?.let { "Cycle day $it" } ?: "Trying to conceive"))
            }
        }
        return DayExtrasResult(modulesToday, insights, phase)
    }

    companion object {
        fun mentionsFertility(text: String) = text.lowercase().let { "fertile" in it || "ovulat" in it }
    }

    override suspend fun trends(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, ModuleTrend> {
        val on = profile.modules
        val days = Days(Days.zoneOf(profile.timezone))
        val out = mutableMapOf<String, ModuleTrend>()
        fun at(date: LocalDate) = out.getOrPut(date.toString()) { ModuleTrend() }
        if (Module.MIND in on) {
            mind.daysOf(userId, from, to).forEach { (date, d) ->
                out[date.toString()] = at(date).copy(mood = d.mood, anxiety = d.anxiety, mindfulMinutes = d.mindfulMinutes.takeIf { it > 0 })
            }
        }
        if (Module.CYCLE in on) {
            cycle.logged(userId, from, to).forEach { d -> d.flow?.let { flow -> out[d.date] = at(LocalDate.parse(d.date)).copy(flow = flow) } }
        }
        if (Module.CONTINENCE in on) {
            continence.daysOf(userId, from, to).forEach { (date, d) ->
                out[date.toString()] = at(date).copy(voids = d.voids, nightVoids = d.nightVoids, leaks = d.leaks + d.bowelLeaks)
            }
        }
        if (Module.MEDICATIONS in on) {
            val list = meds.list(userId)
            if (list.isNotEmpty()) {
                val logs = meds.doses(userId, from, to.plusDays(1))
                val now = clock()
                var date = from
                while (!date.isAfter(to)) {
                    val slots = list.flatMap { m -> Schedules.times(m.schedule, date, days).filter { it <= now }.map { m.id to it } }
                    if (slots.isNotEmpty()) {
                        val taken = slots.count { (id, slot) -> logs.any { it.medicationId == id && it.scheduledFor == slot && it.status == DoseStatus.TAKEN } }
                        out[date.toString()] = at(date).copy(dosesDue = slots.size, dosesTaken = taken)
                    }
                    date = date.plusDays(1)
                }
            }
        }
        care?.hotFlashes(userId, profile, from, to)?.forEach { (date, count) -> out[date] = at(LocalDate.parse(date)).copy(hotFlashes = count) }
        return out
    }
}
