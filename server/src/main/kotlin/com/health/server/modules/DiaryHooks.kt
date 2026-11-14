package com.health.server.modules

import com.health.api.BladderTraining
import com.health.api.ContinenceKind
import com.health.api.DigestSection
import com.health.api.Flow
import com.health.api.Module
import com.health.api.Profile
import com.health.api.ReportSectionKey
import com.health.api.SuggestedQuestion
import com.health.api.SupplyCategory
import com.health.api.Verdict
import com.health.api.WebhookEvent
import com.health.server.health.ProfileStore
import com.health.server.platform.FeatureHooks
import com.health.server.platform.Reminder
import com.health.server.platform.ReportSection
import com.health.server.platform.ReportTable
import com.health.server.util.Days
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** The deeper bladder and bowel diary's part: bladder training reminders, progress in digests and reports, the detail in exports. */
class ContinenceHooks(private val continence: ContinenceService, private val clock: () -> Long, private val supplies: SuppliesService? = null) : FeatureHooks {
    override val module = Module.CONTINENCE

    override suspend fun reminders(userId: String, profile: Profile, from: Long, to: Long): List<Reminder> {
        val settings = continence.settings(userId)
        val change = if (settings.changeReminderHours != null) changeReminder(userId, from, to) else null
        val training = settings.bladderTraining
        if (!training.enabled) return listOfNotNull(change)
        val days = Days(Days.zoneOf(profile.timezone))
        return listOfNotNull(change) + BladderTrainingPlan.times(training, days, from, to).map { (at, interval) ->
            val local = Days(days.zone).let { LocalTime.ofSecondOfDay((it.minuteOfDay(at) * 60).toLong()) }
            Reminder(
                key = "bladder:${days.dateOf(at)}:$local",
                at = at,
                event = WebhookEvent.BLADDER_TRAINING,
                title = "Time for a toilet trip",
                body = "Bladder training: every ${BladderTrainingPlan.say(interval)} today. Going now, even if you don't feel you need to, keeps the rhythm.",
                privateTitle = "Reminder",
                privateBody = "Time for your planned break.",
                url = "/bladder",
                data = buildJsonObject { put("intervalMinutes", interval) },
            )
        }
    }

    /**
     * A discreet check once a product has been on for the hours the person chose (once each time
     * something goes on). It never names the product where a screen might show it: only a webhook
     * that includes details gets the name.
     */
    private suspend fun changeReminder(userId: String, from: Long, to: Long): Reminder? {
        val w = continence.wearing(userId, typical = false)
        val since = w.since ?: return null
        val due = w.changeDueAt ?: return null
        if (w.supplyId == null || due !in from until to) return null
        val minutes = ((due - since) / 60_000).toInt()
        val hours = (minutes / 60.0).roundToInt()
        val long = when {
            minutes < 60 -> "$minutes minutes"
            hours == 1 -> "an hour"
            else -> "$hours hours"
        }
        return Reminder(
            key = "continence:change:$since",
            at = due,
            event = WebhookEvent.CONTINENCE_CHANGE,
            title = "Time for a check",
            body = "About $long since your last change.",
            privateTitle = "Time for a check",
            privateBody = "A gentle reminder.",
            url = "/bladder",
            data = buildJsonObject { put("minutes", minutes) },
            detailedData = buildJsonObject { put("minutes", minutes); w.name?.let { put("product", it) } },
        )
    }

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        // The weeks up to the digest's last day, whenever it's worked out.
        val progress = continence.progress(userId, 8, to)
        val week = progress.weeks.lastOrNull { it.weekStart <= to.toString() && it.days > 0 } ?: return null
        val lines = mutableListOf<String>()
        week.leaksPerDay?.let { lines += "Leaks: ${say(it)} a day on the ${week.days} days you logged." }
        week.bowelLeaksPerDay?.takeIf { it > 0 }?.let { lines += "Bowel accidents: ${say(it)} a day." }
        week.productsPerDay?.takeIf { it > 0 }?.let { lines += "Products used: ${say(it)} a day." }
        if (week.pelvicFloorSets > 0) lines += "Pelvic floor exercises: ${week.pelvicFloorSets} ${if (week.pelvicFloorSets == 1) "set" else "sets"}."
        if (progress.verdict != Verdict.NOT_ENOUGH) lines += progress.headline
        return DigestSection(
            "Bladder and bowel", Module.CONTINENCE,
            when (progress.verdict) { Verdict.IMPROVING -> "good"; Verdict.WORSENING -> "warn"; else -> "info" },
            lines,
        )
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection? {
        val weeks = (ChronoUnit.WEEKS.between(from, to).toInt() + 1).coerceIn(2, 52)
        val progress = continence.progress(userId, weeks, to)
        if (progress.weeks.none { it.days > 0 }) return null
        val summary = mutableListOf(progress.headline)
        progress.metrics.filter { it.before != null || it.after != null }.forEach { m ->
            summary += "${m.label}: ${m.before?.let(::say) ?: "–"} before, ${m.after?.let(::say) ?: "–"} in the last ${progress.recentWeeks} weeks${m.unit.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}."
        }
        summary += progress.notes
        supplies?.status(userId)?.filter { it.supply.kind.category != SupplyCategory.PERIOD && it.perDay != null }?.forEach { s ->
            summary += "${s.supply.name}: about ${say(s.perDay!!)} used a day lately${s.supply.absorbency?.let { " ($it)" } ?: ""}."
        }
        val products = continence.products(userId, from, to)
        summary += products.map { Wear.describe(it) }
        val productTable = ReportTable(
            "Products",
            listOf("Product", "Changes", "Usually worn", "Most often", "Leaked through", "At night"),
            products.map { p ->
                listOf(p.name, p.changes.toString(), p.avgWornMinutes?.let { Wear.say(Wear.rough(it)) } ?: "–", p.typicalFullness?.label ?: "–",
                    "${p.leakedThrough} (${p.leakedThroughPct}%)", "${(p.nightShare * 100).roundToInt()}%")
            },
        )
        val table = ReportTable(
            "Week by week",
            listOf("Week of", "Days logged", "Trips a day", "Night trips", "Leaks a day", "Bowel accidents a day", "Urgency", "Products a day", "Loss g a day", "Bother"),
            progress.weeks.filter { it.days > 0 }.map { w ->
                listOf(w.weekStart, w.days.toString(), sayOrDash(w.voidsPerDay), sayOrDash(w.nightVoidsPerDay), sayOrDash(w.leaksPerDay),
                    sayOrDash(w.bowelLeaksPerDay), sayOrDash(w.urgency), sayOrDash(w.productsPerDay), sayOrDash(w.lossGPerDay), sayOrDash(w.bother))
            },
        )
        return ReportSection(ReportSectionKey.CONTINENCE, summary, listOfNotNull(table, productTable.takeIf { it.rows.isNotEmpty() }))
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val days = ChronoUnit.DAYS.between(from, Days(Days.zoneOf(profile.timezone)).today(clock())).toInt() + 1
        val list = continence.list(userId, days.coerceIn(1, 3650)).filter { val d = LocalDate.parse(it.date!!); !d.isBefore(from) && !d.isAfter(to) }.reversed()
        val checks = continence.checkIns(userId, days.coerceIn(1, 3650)).filter { val d = LocalDate.parse(it.date); !d.isBefore(from) && !d.isAfter(to) }.reversed()
        val zone = Days.zoneOf(profile.timezone)
        val out = linkedMapOf<String, List<List<String>>>()
        if (list.isNotEmpty()) {
            out["bladder-bowel-details.csv"] = listOf(
                listOf("time", "kind", "night", "contents", "leak", "urgency", "warning_s", "awareness", "reached_toilet", "triggers", "activity",
                    "location", "supply", "put_on", "nothing_on", "worn_minutes", "fullness", "pad_weight_g", "loss_g", "count", "leaked_through",
                    "changed_clothes", "changed_bedding", "skin", "infection_signs", "volume_ml", "bristol", "tags", "note"),
            ) + list.map { e ->
                listOf(
                    java.time.Instant.ofEpochMilli(e.at).atZone(zone).toLocalDateTime().toString(), e.kind.name, e.night.toString(),
                    e.contents?.name.orEmpty(), e.leak?.name.orEmpty(), e.urgency?.toString().orEmpty(), e.warningSeconds?.toString().orEmpty(),
                    e.awareness?.name.orEmpty(), e.reachedToilet?.name.orEmpty(), e.triggers.joinToString("; "), e.activity.joinToString("; "),
                    e.location.orEmpty(), e.supplyId.orEmpty(), e.putOnSupplyId.orEmpty(), e.nothingOn.toString(), e.wornMinutes?.toString().orEmpty(),
                    e.fullness?.name.orEmpty(), e.padWeightG?.toString().orEmpty(), e.lossG?.toString().orEmpty(),
                    e.count?.toString().orEmpty(), e.leakedThrough.toString(), e.changedClothes.toString(), e.changedBedding.toString(),
                    e.skin?.toString().orEmpty(), e.utiSigns.joinToString("; "), e.volumeMl?.toString().orEmpty(), e.bristol?.toString().orEmpty(),
                    e.tags.joinToString("; "), e.note.orEmpty(),
                )
            }
        }
        val products = continence.products(userId, from, to)
        if (products.isNotEmpty()) {
            out["bladder-bowel-products.csv"] = listOf(listOf("supply_id", "name", "kind", "changes", "avg_worn_minutes", "typical_fullness", "leaked_through", "leaked_through_pct", "night_share")) +
                products.map { p ->
                    listOf(p.supplyId, p.name, p.kind?.name.orEmpty(), p.changes.toString(), p.avgWornMinutes?.toString().orEmpty(), p.typicalFullness?.name.orEmpty(),
                        p.leakedThrough.toString(), p.leakedThroughPct.toString(), p.nightShare.toString())
                }
        }
        if (checks.isNotEmpty()) {
            out["bladder-bowel-check-ins.csv"] = listOf(listOf("date", "bother_0_10", "compared", "impact", "note")) +
                checks.map { listOf(it.date, it.bother.toString(), it.compared?.toString().orEmpty(), it.impact.joinToString("; "), it.note.orEmpty()) }
        }
        return out
    }

    override suspend fun questions(userId: String, profile: Profile, since: LocalDate, to: LocalDate): List<SuggestedQuestion> {
        val progress = continence.progress(userId, 8, to)
        val out = mutableListOf<SuggestedQuestion>()
        val leaks = progress.metrics.firstOrNull { it.key == "leaks" }
        when (progress.verdict) {
            Verdict.WORSENING -> out += SuggestedQuestion("My bladder or bowel has been harder lately: what could be behind it, and what should we try?", progress.headline, Module.CONTINENCE)
            Verdict.STEADY -> if ((leaks?.after ?: 0.0) >= 0.5) out += SuggestedQuestion("I'm still leaking most days: is there more we could try (pelvic floor physiotherapy, bladder training, medication)?", progress.headline, Module.CONTINENCE)
            else -> Unit
        }
        val recent = continence.list(userId, ChronoUnit.DAYS.between(since, to).toInt().coerceIn(1, 365) + 1)
        if (recent.any { it.utiSigns.isNotEmpty() }) out += SuggestedQuestion("Could I have a urine infection?", "You logged signs that can go with one.", Module.CONTINENCE)
        if (recent.count { it.kind == ContinenceKind.BOWEL_LEAK } >= 2) out += SuggestedQuestion("What can help with bowel accidents?", "${recent.count { it.kind == ContinenceKind.BOWEL_LEAK }} logged since your last appointment.", Module.CONTINENCE)
        progress.weeks.lastOrNull { it.productsPerDay != null }?.productsPerDay?.takeIf { it >= 3 }?.let {
            out += SuggestedQuestion("Could different products, or a referral to a continence service, help?", "About ${say(it)} products a day lately.", Module.CONTINENCE)
        }
        return out
    }

    private fun say(v: Double) = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.1f".format(v)
    private fun sayOrDash(v: Double?) = v?.let(::say) ?: "–"
}

/** When bladder training reminders come: pure, for tests. */
object BladderTrainingPlan {
    /** The interval on [date]: the start, plus a step every so many days, up to the goal. */
    fun interval(t: BladderTraining, date: LocalDate): Int {
        val started = t.startedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: date
        val steps = (ChronoUnit.DAYS.between(started, date).coerceAtLeast(0) / t.stepEveryDays.coerceAtLeast(1)).toInt()
        return (t.intervalMinutes + steps * t.stepMinutes).coerceAtMost(maxOf(t.goalMinutes, t.intervalMinutes))
    }

    /** Reminder times in [from, to), each with that day's interval. */
    fun times(t: BladderTraining, days: Days, from: Long, to: Long): List<Pair<Long, Int>> {
        val start = runCatching { LocalTime.parse(t.from) }.getOrNull() ?: return emptyList()
        val end = runCatching { LocalTime.parse(t.to) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Pair<Long, Int>>()
        var date = days.dateOf(from)
        val last = days.dateOf(to)
        while (!date.isAfter(last)) {
            val interval = interval(t, date)
            // The first reminder is one interval after the day's start: the start itself is when the day begins.
            var minute = start.toSecondOfDay() / 60 + interval
            while (minute <= end.toSecondOfDay() / 60) {
                val at = days.start(date) + minute * 60_000L
                if (at in from until to) out += at to interval
                minute += interval
            }
            date = date.plusDays(1)
        }
        return out
    }

    fun say(minutes: Int): String = when {
        minutes % 60 == 0 -> if (minutes == 60) "hour" else "${minutes / 60} hours"
        minutes > 60 -> "${minutes / 60} h ${minutes % 60} min"
        else -> "$minutes minutes"
    }
}

/** Supplies running low: one reminder a day, at the person's refill time. */
class SuppliesHooks(private val supplies: SuppliesService, private val profiles: ProfileStore, private val clock: () -> Long) : FeatureHooks {
    override val module: Module? = null

    override suspend fun reminders(userId: String, profile: Profile, from: Long, to: Long): List<Reminder> {
        val categories = categoriesFor(profile)
        if (categories.isEmpty()) return emptyList()
        val days = Days(Days.zoneOf(profile.timezone))
        val time = runCatching { LocalTime.parse(profile.reminders.refillTime) }.getOrNull() ?: LocalTime.of(10, 0)
        val at = days.start(days.dateOf(to)) + time.toSecondOfDay() * 1000L
        val due = listOf(at, at - 86_400_000L).firstOrNull { it in from until to } ?: return emptyList()
        val low = supplies.low(userId, categories)
        if (low.isEmpty()) return emptyList()
        val names = low.joinToString(", ") { it.supply.name }
        return listOf(
            Reminder(
                key = "supplies:${days.dateOf(due)}",
                at = due,
                event = WebhookEvent.SUPPLIES_LOW,
                title = if (low.size == 1) "Running low on ${low.first().supply.name}" else "Running low on ${low.size} supplies",
                body = low.joinToString(". ") { s -> "${s.supply.name}: ${s.supply.onHand} left${s.daysLeft?.let { d -> ", about $d ${if (d == 1) "day" else "days"}" } ?: ""}" } + ".",
                privateTitle = "Time to restock",
                privateBody = "Something you keep on hand is running low.",
                url = "/bladder",
                data = buildJsonObject { put("count", low.size) },
                detailedData = buildJsonObject { put("count", low.size); put("names", names) },
            ),
        )
    }

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val categories = categoriesFor(profile)
        val low = supplies.status(userId).filter { it.low && it.supply.kind.category in categories }
        if (low.isEmpty()) return null
        return DigestSection(
            "Supplies", if (SupplyCategory.PERIOD in low.map { it.supply.kind.category } && low.size == 1) Module.CYCLE else Module.CONTINENCE, "info",
            low.map { s -> "${s.supply.name}: ${s.supply.onHand} left${s.daysLeft?.let { d -> ", about $d ${if (d == 1) "day" else "days"}" } ?: ""}. Time to restock." },
        )
    }

    private fun categoriesFor(profile: Profile) = buildSet {
        if (Module.CONTINENCE in profile.modules) { add(SupplyCategory.CONTINENCE); add(SupplyCategory.OTHER) }
        if (Module.CYCLE in profile.modules) add(SupplyCategory.PERIOD)
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val list = supplies.status(userId)
        if (list.isEmpty()) return emptyMap()
        return mapOf(
            "supplies.csv" to listOf(listOf("name", "kind", "brand", "size", "absorbency", "on_hand", "per_pack", "used_a_day", "days_left", "low", "reusable")) +
                list.map { s ->
                    val x = s.supply
                    listOf(x.name, x.kind.name, x.brand.orEmpty(), x.size.orEmpty(), x.absorbency.orEmpty(), x.onHand.toString(), x.perPack?.toString().orEmpty(),
                        s.perDay?.toString().orEmpty(), s.daysLeft?.toString().orEmpty(), s.low.toString(), x.reusable.toString())
                },
        )
    }
}

/** Cycle reminders (before a period, when late, a daily nudge), and the detail in reports and exports. */
class CycleHooks(private val cycle: CycleService, private val clock: () -> Long) : FeatureHooks {
    override val module = Module.CYCLE

    override suspend fun reminders(userId: String, profile: Profile, from: Long, to: Long): List<Reminder> {
        val settings = cycle.settings(userId)
        val days = Days(Days.zoneOf(profile.timezone))
        val out = mutableListOf<Reminder>()
        fun timeToday(t: LocalTime): Long? {
            val at = days.start(days.dateOf(to)) + t.toSecondOfDay() * 1000L
            return listOf(at, at - 86_400_000L).firstOrNull { it in from until to }
        }
        // Before and after a period: at 9 in the morning, worked out only when that's now.
        val morning = timeToday(LocalTime.of(9, 0))
        if (morning != null && (settings.remindBeforePeriod != null || settings.remindLateAfter != null)) {
            val date = days.dateOf(morning)
            val today = cycle.today(userId, date)
            val until = today.daysUntilPeriod
            if (until != null && settings.remindBeforePeriod != null && settings.remindBeforePeriod!! > 0 && until == settings.remindBeforePeriod) {
                out += Reminder(
                    "cycle:before:$date", morning, WebhookEvent.CYCLE_REMINDER,
                    if (until == 1) "Period likely tomorrow" else "Period likely in $until days",
                    "A good time to check you have what you need.", "Reminder", "A reminder from your calendar.", "/cycle",
                )
            }
            val late = settings.remindLateAfter
            if (until != null && late != null && until == -late && today.phase != com.health.api.CyclePhase.PERIOD) {
                out += Reminder(
                    "cycle:late:$date", morning, WebhookEvent.CYCLE_REMINDER, "Period $late days later than expected",
                    "Stress, travel, illness and hormones can all shift a cycle. Log it when it comes.", "Reminder", "A reminder from your calendar.", "/cycle",
                )
            }
        }
        settings.dailyReminder?.let { runCatching { LocalTime.parse(it) }.getOrNull() }?.let { t ->
            val at = timeToday(t) ?: return@let
            val date = days.dateOf(at)
            if (cycle.logged(userId, date, date).isEmpty()) {
                out += Reminder("cycle:daily:$date", at, WebhookEvent.CYCLE_REMINDER, "How's today?", "A quick note for your cycle: bleeding, symptoms, how you feel.", "Reminder", "A quick check-in.", "/cycle")
            }
        }
        return out
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection? {
        val logged = cycle.logged(userId, from, to)
        if (logged.isEmpty()) return null
        val summary = mutableListOf<String>()
        val areas = logged.flatMap { it.painAreas }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        if (areas.isNotEmpty()) summary += "Pain was felt most in: ${areas.take(5).joinToString(", ") { "${it.key} (${it.value} ${if (it.value == 1) "day" else "days"})" }}."
        val severe = logged.flatMap { d -> d.symptomSeverity.filterValues { it >= 3 }.keys }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        if (severe.isNotEmpty()) summary += "Symptoms rated severe: ${severe.take(6).joinToString(", ") { "${it.key} (${it.value})" }}."
        val heavy = logged.count { it.flow == Flow.HEAVY }
        val clots = logged.count { it.clots == com.health.api.Clots.LARGE }
        if (heavy > 0 || clots > 0) summary += "Heavy days: $heavy; days with clots larger than a coin: $clots."
        val products = logged.flatMap { it.products }
        if (products.isNotEmpty()) {
            val periodDays = logged.count { it.products.isNotEmpty() }
            summary += "Period products: ${products.sumOf { it.count }} over $periodDays ${if (periodDays == 1) "day" else "days"}" +
                products.mapNotNull { it.fullness }.takeIf { it.isNotEmpty() }?.let { f -> ", ${f.count { it.level >= 4 }} of them soaked or leaked through" }.orEmpty() + "."
        }
        if (summary.isEmpty()) return null
        val rows = logged.filter { it.painAreas.isNotEmpty() || it.symptomSeverity.isNotEmpty() || it.products.isNotEmpty() || it.clots != null }.map { d ->
            listOf(d.date, d.flow?.label.orEmpty(), d.pain?.toString().orEmpty(), d.painAreas.joinToString(", "),
                d.symptomSeverity.entries.joinToString(", ") { "${it.key} ${listOf("", "mild", "moderate", "severe")[it.value]}" },
                d.products.joinToString(", ") { "${it.count} ${it.kind.label.lowercase()}" }, d.clots?.label.orEmpty())
        }
        return ReportSection(ReportSectionKey.CYCLE, summary, listOf(ReportTable("Pain, symptoms and products", listOf("Date", "Bleeding", "Pain", "Where", "Symptoms", "Products", "Clots"), rows)))
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val logged = cycle.logged(userId, from, to)
        if (logged.isEmpty()) return emptyMap()
        return mapOf(
            "cycle-details.csv" to listOf(listOf("date", "products", "clots", "color", "pain_areas", "symptom_severity", "mood", "energy", "sex_drive", "lh", "pregnancy_test", "discharge", "tags")) +
                logged.map { d ->
                    listOf(d.date, d.products.joinToString("; ") { "${it.count} ${it.kind.name}${it.fullness?.let { f -> " ${f.name}" } ?: ""}" }, d.clots?.name.orEmpty(),
                        d.color.orEmpty(), d.painAreas.joinToString("; "), d.symptomSeverity.entries.joinToString("; ") { "${it.key}=${it.value}" },
                        d.mood.joinToString("; "), d.energy?.toString().orEmpty(), d.libido?.toString().orEmpty(), d.lh?.toString().orEmpty(),
                        d.pregnancyTest?.name.orEmpty(), d.discharge.orEmpty(), d.tags.joinToString("; "))
                },
        )
    }
}
