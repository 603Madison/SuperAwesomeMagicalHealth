package com.health.server.care

import com.health.api.DoseStatus
import com.health.api.Flow
import com.health.api.LeakSize
import com.health.api.ContinenceKind
import com.health.api.Module
import com.health.api.Profile
import com.health.api.Screener
import com.health.api.SuggestedQuestion
import com.health.server.insights.DayService
import com.health.server.modules.ContinenceService
import com.health.server.modules.CycleService
import com.health.server.modules.MedsService
import com.health.server.modules.MindService
import com.health.server.modules.Schedules
import com.health.server.platform.FeatureHooks
import com.health.server.util.Days
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Questions for an appointment from the features that were there first: mood and check-ins,
 * the cycle, the bladder and bowel diary, medications, sleep, activity and weight. Each is said
 * in the person's voice, plainly, with the numbers that prompted it.
 */
class CoreQuestions(override val module: Module, private val ask: suspend (String, Profile, LocalDate, LocalDate) -> List<SuggestedQuestion>) : FeatureHooks {
    override suspend fun questions(userId: String, profile: Profile, since: LocalDate, to: LocalDate) = ask(userId, profile, since, to)

    companion object {
        fun all(mind: MindService, cycle: CycleService, continence: ContinenceService, meds: MedsService, days: DayService, clock: () -> Long): List<FeatureHooks> = listOf(
            CoreQuestions(Module.MIND) { userId, _, since, to -> mindQuestions(mind, userId, since, to) },
            CoreQuestions(Module.CYCLE) { userId, _, since, to -> cycleQuestions(cycle, userId, since, to) },
            CoreQuestions(Module.CONTINENCE) { userId, _, since, to -> continenceQuestions(continence, userId, since, to) },
            CoreQuestions(Module.MEDICATIONS) { userId, profile, since, to -> medsQuestions(meds, userId, profile, since, to, clock()) },
            CoreQuestions(Module.SLEEP) { userId, profile, since, to -> sleepQuestions(days, userId, profile, since, to) },
            CoreQuestions(Module.ACTIVITY) { userId, profile, since, to -> activityQuestions(days, userId, profile, since, to) },
            CoreQuestions(Module.BODY) { userId, profile, since, to -> bodyQuestions(days, userId, profile, since, to) },
        )

        private fun one(d: Double) = "%.1f".format(d)

        private fun screenerName(s: Screener) = when (s) {
            Screener.PHQ9 -> "PHQ-9 (mood)"
            Screener.GAD7 -> "GAD-7 (anxiety)"
            Screener.EPDS -> "Edinburgh (EPDS)"
        }

        private suspend fun mindQuestions(mind: MindService, userId: String, since: LocalDate, to: LocalDate): List<SuggestedQuestion> {
            val out = mutableListOf<SuggestedQuestion>()
            val span = ChronoUnit.DAYS.between(since, to).coerceAtLeast(6)
            val now = mind.daysOf(userId, since, to).values.mapNotNull { it.mood }
            val before = mind.daysOf(userId, since.minusDays(span + 1), since.minusDays(1)).values.mapNotNull { it.mood }
            if (now.size >= 4) {
                val avg = now.average()
                when {
                    before.size >= 4 && before.average() - avg >= 0.5 -> out += SuggestedQuestion("My mood has been lower lately (about ${one(avg)} of 5, down from ${one(before.average())}). Can we talk about it?", "Mood logged in Mind", Module.MIND)
                    avg <= 2.5 -> out += SuggestedQuestion("My mood has been low (about ${one(avg)} of 5). Can we talk about it?", "Mood logged in Mind", Module.MIND)
                }
            }
            val results = mind.screenerResults(userId, 730).filter { it.date != null }
            results.groupBy { it.screener }.forEach { (screener, list) ->
                val inRange = list.filter { LocalDate.parse(it.date) in since..to }.maxByOrNull { it.at } ?: return@forEach
                val previous = list.filter { it.at < inRange.at }.maxByOrNull { it.at }
                when {
                    inRange.flagged -> out += SuggestedQuestion("I've had thoughts of being better off dead or of hurting myself, and I'd like to talk about support.", "Your ${screenerName(screener)} on ${shortDate(inRange.date)}", Module.MIND)
                    previous != null && inRange.score - previous.score >= 5 -> out += SuggestedQuestion("My ${screenerName(screener)} score went from ${previous.score} to ${inRange.score}. What could help?", "Check-ins in Mind", Module.MIND)
                    inRange.score >= 10 -> out += SuggestedQuestion("My ${screenerName(screener)} score was ${inRange.score} (${inRange.severity.lowercase()}). What could help?", "Your check-in on ${shortDate(inRange.date)}", Module.MIND)
                }
            }
            return out
        }

        private suspend fun cycleQuestions(cycle: CycleService, userId: String, since: LocalDate, to: LocalDate): List<SuggestedQuestion> {
            val out = mutableListOf<SuggestedQuestion>()
            val o = cycle.overview(userId, 12, to)
            val lengths = o.cycles.mapNotNull { it.length }.takeLast(6)
            if (lengths.size >= 2) {
                val min = lengths.min()
                val max = lengths.max()
                if (min < 21 || max > 35 || max - min >= 8) {
                    out += SuggestedQuestion("My cycles have ranged from $min to $max days. Is that worth looking into?", "Your last ${lengths.size} cycles", Module.CYCLE)
                }
            }
            val logged = cycle.logged(userId, since, to)
            val heavy = logged.count { it.flow == Flow.HEAVY }
            if (heavy >= 3) out += SuggestedQuestion("I've had heavy bleeding on $heavy days. Is that worth looking into?", "Logged in Cycle since ${shortDate(since.toString())}", Module.CYCLE)
            val painful = logged.mapNotNull { it.pain }.filter { it >= 7 }
            if (painful.size >= 2) out += SuggestedQuestion("Period pain has reached ${painful.max()} out of 10. What could help?", "${plural(painful.size, "day")} of strong pain", Module.CYCLE)
            return out
        }

        private suspend fun continenceQuestions(continence: ContinenceService, userId: String, since: LocalDate, to: LocalDate): List<SuggestedQuestion> {
            val out = mutableListOf<SuggestedQuestion>()
            val days = continence.daysOf(userId, since, to).values.filter { it.voids + it.leaks + it.bowel > 0 }
            if (days.size < 2) return out
            val leaks = days.sumOf { it.leaks }.toDouble() / days.size
            val night = days.sumOf { it.nightVoids }.toDouble() / days.size
            val voids = days.sumOf { it.voids }.toDouble() / days.size
            if (leaks >= 0.5) {
                val count = ChronoUnit.DAYS.between(since, to).toInt() + 1
                val triggers = continence.list(userId, count + 1).filter { it.kind == ContinenceKind.LEAK }.flatMap { it.triggers }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                val big = continence.list(userId, count + 1).count { it.kind == ContinenceKind.LEAK && (it.leak == LeakSize.MEDIUM || it.leak == LeakSize.LARGE) }
                out += SuggestedQuestion(
                    "I've been having about ${one(leaks)} leaks a day${triggers?.let { ", mostly with $it" } ?: ""}${if (big > 0) ", some of them large" else ""}. What could help?",
                    "Bladder diary, ${plural(days.size, "day")}", Module.CONTINENCE,
                )
            }
            if (night >= 2) out += SuggestedQuestion("I get up about ${night.roundToInt()} times a night to use the toilet. Is that worth looking into?", "Bladder diary", Module.CONTINENCE)
            if (voids > 8.5) out += SuggestedQuestion("I use the toilet about ${voids.roundToInt()} times a day. Is that more than it should be?", "Bladder diary", Module.CONTINENCE)
            return out
        }

        private suspend fun medsQuestions(meds: MedsService, userId: String, profile: Profile, since: LocalDate, to: LocalDate, now: Long): List<SuggestedQuestion> {
            val out = mutableListOf<SuggestedQuestion>()
            val list = meds.list(userId)
            if (list.isEmpty()) return out
            val zone = Days(Days.zoneOf(profile.timezone))
            val logs = meds.doses(userId, since, to.plusDays(1))
            list.forEach { med ->
                var due = 0
                var missed = 0
                var date = since
                while (!date.isAfter(to)) {
                    Schedules.times(med.schedule, date, zone).filter { it <= now }.forEach { slot ->
                        due++
                        if (logs.none { it.medicationId == med.id && it.scheduledFor == slot && it.status == DoseStatus.TAKEN }) missed++
                    }
                    date = date.plusDays(1)
                }
                if (missed >= 3) out += SuggestedQuestion("I've missed $missed of $due doses of ${med.name}. Could the timing or the form be easier?", "Doses marked in Medications", Module.MEDICATIONS)
                med.stock?.let { stock -> if (stock <= maxOf(5, Schedules.perWeek(med.schedule))) out += SuggestedQuestion("Can I have a new prescription for ${med.name}?", "${plural(stock, "dose")} left", Module.MEDICATIONS) }
            }
            logs.filter { !it.note.isNullOrBlank() }.sortedByDescending { it.at }.take(2).forEach { dose ->
                val name = list.firstOrNull { it.id == dose.medicationId }?.name ?: return@forEach
                out += SuggestedQuestion("I noted \"${dose.note!!.take(140)}\" with ${name}. Could that be a side effect?", "Your note on ${shortDate(dose.date)}", Module.MEDICATIONS)
            }
            return out
        }

        private suspend fun sleepQuestions(days: DayService, userId: String, profile: Profile, since: LocalDate, to: LocalDate): List<SuggestedQuestion> {
            val nights = days.stats(userId, since, to, Days(Days.zoneOf(profile.timezone))).mapNotNull { it.sleep }
            if (nights.size < 5) return emptyList()
            val asleep = nights.map { it.asleepMinutes }.average()
            val efficiency = nights.map { it.efficiency }.average()
            val out = mutableListOf<SuggestedQuestion>()
            if (asleep < 360) out += SuggestedQuestion("I've been sleeping about ${(asleep / 60).toInt()}h ${(asleep % 60).roundToInt()}m a night. What could help?", "${plural(nights.size, "night")} recorded", Module.SLEEP)
            if (efficiency < 80) out += SuggestedQuestion("I'm awake a lot in the night (asleep about ${efficiency.roundToInt()}% of the time in bed). What could help?", "${plural(nights.size, "night")} recorded", Module.SLEEP)
            return out
        }

        private suspend fun activityQuestions(days: DayService, userId: String, profile: Profile, since: LocalDate, to: LocalDate): List<SuggestedQuestion> {
            val zone = Days(Days.zoneOf(profile.timezone))
            val span = ChronoUnit.DAYS.between(since, to).coerceAtLeast(13)
            val now = days.stats(userId, since, to, zone)
            val before = days.stats(userId, since.minusDays(span + 1), since.minusDays(1), zone)
            val out = mutableListOf<SuggestedQuestion>()
            val rhrNow = now.mapNotNull { it.restingHr }
            val rhrBefore = before.mapNotNull { it.restingHr }
            if (rhrNow.size >= 7 && rhrBefore.size >= 7 && rhrNow.average() - rhrBefore.average() >= 5) {
                out += SuggestedQuestion("My resting heart rate has gone up by about ${(rhrNow.average() - rhrBefore.average()).roundToInt()} bpm, to ${rhrNow.average().roundToInt()}. Is that worth looking into?", "From your ring or watch", Module.ACTIVITY)
            }
            val stepsNow = now.mapNotNull { it.steps }.filter { it > 0 }
            val stepsBefore = before.mapNotNull { it.steps }.filter { it > 0 }
            if (stepsNow.size >= 7 && stepsBefore.size >= 7 && stepsBefore.average() >= 3000 && stepsNow.average() <= stepsBefore.average() * 0.7) {
                out += SuggestedQuestion("I've been much less active lately (about ${"%,d".format(stepsNow.average().roundToInt())} steps a day, down from ${"%,d".format(stepsBefore.average().roundToInt())}).", "Steps recorded", Module.ACTIVITY)
            }
            return out
        }

        private suspend fun bodyQuestions(days: DayService, userId: String, profile: Profile, since: LocalDate, to: LocalDate): List<SuggestedQuestion> {
            val weights = days.stats(userId, since.minusDays(14), to, Days(Days.zoneOf(profile.timezone))).filter { it.weightKg != null }
            if (weights.size < 2) return emptyList()
            val first = weights.first()
            val last = weights.last()
            if (ChronoUnit.DAYS.between(LocalDate.parse(first.date), LocalDate.parse(last.date)) < 14) return emptyList()
            val change = last.weightKg!! - first.weightKg!!
            if (abs(change) < 2.0 && abs(change) / first.weightKg!! < 0.03) return emptyList()
            val sign = if (change > 0) "+" else "−"
            return listOf(SuggestedQuestion("My weight has changed by $sign${one(abs(change))} kg since ${shortDate(first.date)}. Is that something to look at?", "Weights recorded", Module.BODY))
        }
    }
}
