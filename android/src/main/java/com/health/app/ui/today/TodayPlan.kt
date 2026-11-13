package com.health.app.ui.today

import com.health.api.BladderTraining
import com.health.api.DaySummary
import com.health.api.DueDose
import com.health.api.LifeStage
import com.health.api.Meal
import com.health.api.Module
import com.health.api.TodaySection
import com.health.app.reminders.BladderTrainingPlan
import com.health.app.reminders.CarePlanner
import com.health.app.ui.Format
import com.health.app.ui.modules.continence.ContinenceText
import com.health.app.ui.modules.continence.SupplyArea
import com.health.app.ui.modules.continence.WearText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What Today shows where, in plain Kotlin so it's tested without a phone:
 *
 * - **Up next** ([upNext]): what needs doing now, most urgent first, each with one action.
 * - **Your day** ([place] is [Place.CARD]): a card for each feature with something to show today.
 * - **Also today** ([Place.ALSO]): the features that are on with nothing logged yet, as short rows.
 *
 * Each feature's part follows its own section ([TodaySection]): hiding Medications on Today
 * leaves its doses out of Up next too, and hiding Also today leaves the quiet features out.
 */
object TodayPlan {
    /** Doses show in Up next from this long before they're due (and stay while they're not marked). */
    const val DOSE_AHEAD_MS = 2 * 60 * 60_000L

    /** A dose this late reads as overdue. */
    const val LATE_MS = 30 * 60_000L

    enum class Kind { DOSE, APPOINTMENT, ROUTINE, BLADDER, CHANGE, RESTOCK }

    /**
     * One thing to do: [title] and [detail] as its row says them; [at] when it's for (null: any time
     * today); [late] when it's past its time. [route] is where its action goes when that's a page.
     */
    data class Item(
        val kind: Kind,
        val key: String,
        val title: String,
        val detail: String,
        val at: Long? = null,
        val late: Boolean = false,
        val dose: DueDose? = null,
        val route: String? = null,
    )

    /**
     * Up next, on today only: doses not marked yet that are overdue or due within [DOSE_AHEAD_MS],
     * today's appointments still to come, the next bladder-training trip ([bladder], the
     * person's own settings: never while viewing a share), a change once its reminder time has
     * passed (what's on comes with the day), then a routine still to do and
     * supplies to restock. Timed things go by time (so overdue doses come first); the rest after.
     * [showing] are Today's visible sections: a hidden one's things stay out.
     */
    fun upNext(
        summary: DaySummary,
        modules: Set<Module>,
        showing: Collection<TodaySection>,
        now: Long,
        zone: ZoneId,
        isToday: Boolean,
        bladder: BladderTraining? = null,
    ): List<Item> {
        if (!isToday) return emptyList()
        val m = summary.modules
        val timed = mutableListOf<Item>()
        val later = mutableListOf<Item>()

        if (Module.MEDICATIONS in modules && TodaySection.MEDS in showing) {
            m.meds?.due.orEmpty()
                .filter { it.log == null && it.scheduledFor <= now + DOSE_AHEAD_MS }
                .forEach { d ->
                    val time = Format.timeOfDay(d.scheduledFor, zone)
                    timed += Item(
                        Kind.DOSE, "dose:${d.medicationId}@${d.scheduledFor}", d.name,
                        listOfNotNull(if (d.scheduledFor <= now) "Due $time" else "At $time", d.dose, d.nextSite?.let { "next site: $it" }).joinToString(" · "),
                        at = d.scheduledFor, late = d.scheduledFor < now - LATE_MS, dose = d,
                    )
                }
        }

        if (Module.APPOINTMENTS in modules && TodaySection.APPOINTMENTS in showing) {
            todaysAppointments(summary, now, zone).forEach { a ->
                val open = a.questions.count { !it.done }
                timed += Item(
                    Kind.APPOINTMENT, "appointment:${a.id ?: a.at}",
                    a.title + (a.with?.takeIf { it.isNotBlank() && !a.title.contains(it, ignoreCase = true) }?.let { " with $it" } ?: ""),
                    listOfNotNull(
                        CarePlanner.appointmentWhen(a.at, now, zone).replaceFirstChar(Char::uppercase),
                        a.place?.takeIf { it.isNotBlank() },
                        open.takeIf { it > 0 }?.let { if (it == 1) "1 question to ask" else "$it questions to ask" },
                    ).joinToString(" · "),
                    at = a.at, route = "appointments/edit" + (a.id?.let { "?id=$it" } ?: ""),
                )
            }
        }

        if (Module.CONTINENCE in modules && TodaySection.CONTINENCE in showing && bladder?.enabled == true) {
            val end = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            BladderTrainingPlan.times(bladder, now, end, zone).firstOrNull()?.let { (at, interval) ->
                timed += Item(
                    Kind.BLADDER, "bladder:$at", "Toilet trip at ${Format.timeOfDay(at, zone)}",
                    "Bladder training: every ${BladderTrainingPlan.say(interval)} today", at = at,
                )
            }
        }

        if (Module.CONTINENCE in modules && TodaySection.CONTINENCE in showing) {
            m.continence?.wearing?.takeIf { WearText.changeDue(it, now) }?.let { w ->
                timed += Item(
                    Kind.CHANGE, "change:${w.since}", WearText.dueTitle(w, now),
                    "On since ${Format.timeOfDay(w.since!!, zone)}", at = w.changeDueAt,
                )
            }
        }

        if (Module.NEURO in modules && TodaySection.NEURO in showing) {
            m.neuro?.routinesDue?.takeIf { it.isNotEmpty() }?.let { due ->
                later += Item(
                    Kind.ROUTINE, "routine:${due.first()}", due.first(),
                    if (due.size == 1) "Still to do today" else "Still to do today, and ${due.size - 1} more",
                    route = "neuro/routines",
                )
            }
        }

        if (TodaySection.SUPPLIES in showing && (Module.CONTINENCE in modules || Module.CYCLE in modules)) {
            val low = m.lowSupplies.orEmpty()
            val route = "supplies/" + SupplyArea.forLow(low).route
            low.forEach { s ->
                later += Item(
                    Kind.RESTOCK, "restock:${s.supply.id ?: s.supply.name}", "${ContinenceText.supplyName(s.supply)} running low",
                    "${s.supply.onHand} left" + (s.daysLeft?.let { " · about $it ${if (it == 1) "day" else "days"}" } ?: ""),
                    route = route,
                )
            }
        }

        return timed.sortedBy { it.at } + later
    }

    /**
     * The sections Up next is showing for ([com.health.api.Insight.shownBy]): Medications while it
     * has doses, Appointments while it has today's appointment. For you leaves out the insights
     * that would repeat them.
     */
    fun showing(upNext: List<Item>): Set<TodaySection> = buildSet {
        if (upNext.any { it.kind == Kind.DOSE }) add(TodaySection.MEDS)
        if (upNext.any { it.kind == Kind.APPOINTMENT }) add(TodaySection.APPOINTMENTS)
    }

    /** Today's appointments that haven't finished yet. */
    fun todaysAppointments(summary: DaySummary, now: Long, zone: ZoneId): List<com.health.api.Appointment> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return summary.modules.appointments.orEmpty()
            .filter { a -> Instant.ofEpochMilli(a.at).atZone(zone).toLocalDate() == today && a.at + (a.durationMinutes ?: 60) * 60_000L > now }
            .sortedBy { it.at }
    }

    /** Where a section goes today: its own card, a row in Also today, or nowhere. */
    enum class Place { CARD, ALSO, NONE }

    /**
     * What decides where each section goes: the day, the features on, whether amounts of food
     * show ([foodNumbers]) and the meals logged when they don't ([meals]), and what's in Up next.
     */
    data class Day(
        val summary: DaySummary,
        val modules: Set<Module>,
        val upNext: List<Item> = emptyList(),
        val now: Long = System.currentTimeMillis(),
        val zone: ZoneId = ZoneId.systemDefault(),
        val meals: List<Meal>? = null,
    )

    /**
     * Where [section] goes. Features with something to show today (something logged, or a status
     * like the cycle day) get a card; those that are on with nothing yet, a row in Also today;
     * readings that only come from devices (heart, stress, workouts) and things shown elsewhere
     * (doses and supplies in Up next) go nowhere when there's nothing. Today's own sections
     * (scores, Up next, For you, Quick log, Also today) aren't features: [Place.NONE].
     */
    fun place(section: TodaySection, day: Day): Place {
        val s = day.summary
        val m = s.modules
        fun card(has: Boolean, orElse: Place = Place.ALSO) = if (has) Place.CARD else orElse
        return when (section) {
            TodaySection.SCORES, TodaySection.UP_NEXT, TodaySection.INSIGHTS, TodaySection.QUICK, TodaySection.ALSO -> Place.NONE
            // Doses are Up next's, and Medications has no card of its own; with none to show and nothing marked, a quiet row.
            TodaySection.MEDS -> {
                val marked = m.meds?.let { d -> d.due.any { it.log != null } || d.extra.isNotEmpty() } ?: false
                if (day.upNext.any { it.kind == Kind.DOSE } || marked) Place.NONE else Place.ALSO
            }
            TodaySection.SUPPLIES -> Place.NONE
            TodaySection.MIND -> card(m.mind?.let { it.mood != null || it.entries > 0 || it.mindfulMinutes > 0 || it.journal > 0 } == true)
            TodaySection.CYCLE -> card(m.cycle?.let { it.logged != null || it.cycleDay != null } == true)
            // What's being worn counts as something to show, even before anything's logged today.
            TodaySection.CONTINENCE -> card(m.continence?.let { c -> c.voids + c.nightVoids + c.leaks + c.bowel + c.pads + c.pelvicFloor + c.products + c.catheters + c.bowelLeaks + c.wetNights > 0 || c.wearing?.since != null } == true)
            // With Mind alone, its energy budget: Mind's own row stands for it when there's nothing.
            TodaySection.NEURO -> card(
                m.neuro?.let { it.energyLeft != null || it.socialBattery != null || it.focusMinutes > 0 || it.overloadsToday > 0 } == true,
                orElse = if (Module.NEURO in day.modules) Place.ALSO else Place.NONE,
            )
            TodaySection.TRANSITION -> card(m.transition?.let { it.headline != null || it.euphoriaThisWeek > 0 || it.milestonesDue.isNotEmpty() } == true)
            TodaySection.VITALS -> card(m.vitals?.let { it.bloodPressure != null || it.glucose != null } == true)
            TodaySection.DRINKS -> card(s.drinks?.let { it.drinks.isNotEmpty() || it.caffeineMg > 0 || it.alcoholG > 0 } == true)
            TodaySection.SYMPTOMS -> card(m.symptoms?.let { it.entries.isNotEmpty() || it.migraines.isNotEmpty() } == true)
            TodaySection.HABITS -> card(!m.habits.isNullOrEmpty())
            TodaySection.HORMONES -> card(m.hormones?.let { it.nextLabsDue != null || it.outOfRange.isNotEmpty() } == true, orElse = Place.NONE)
            TodaySection.LIFE_STAGE -> card(m.lifeStage?.let { it.stage != LifeStage.CYCLING } == true, orElse = Place.NONE)
            TodaySection.APPOINTMENTS -> card(laterAppointments(day).isNotEmpty())
            TodaySection.ACTIVITY -> card(s.steps > 0 || s.activeCalories > 0 || s.distanceM > 0)
            TodaySection.SLEEP -> card(s.sleepSummary != null)
            TodaySection.HEART -> card(s.restingHeartRate != null || s.hrv != null || s.heartRate.isNotEmpty() || s.spo2 != null, orElse = Place.NONE)
            TodaySection.STRESS -> card(s.stressDay != null || s.resilience != null, orElse = Place.NONE)
            TodaySection.FOOD -> card(s.nutrition.calories > 0 || s.nutrition.protein > 0 || !day.meals.isNullOrEmpty())
            TodaySection.WATER -> card(s.waterMl > 0)
            TodaySection.WORKOUTS -> card(s.workouts.isNotEmpty(), orElse = Place.NONE)
            TodaySection.BODY -> card(s.weightKg != null || s.bodyFat != null)
        }
    }

    /** The week's appointments the Appointments card shows: those not already in Up next. */
    fun laterAppointments(day: Day): List<com.health.api.Appointment> {
        val inUpNext = day.upNext.filter { it.kind == Kind.APPOINTMENT }.map { it.key }.toSet()
        return day.summary.modules.appointments.orEmpty().filter { a -> "appointment:${a.id ?: a.at}" !in inUpNext && a.at > day.now - 60 * 60_000L }.sortedBy { it.at }
    }

    /** A row's quiet words in Also today: "Nothing yet" (or "Nothing logged" for a day gone by), or what's true for the feature. */
    fun alsoStatus(section: TodaySection, day: Day, isToday: Boolean): String {
        val nothing = if (isToday) "Nothing yet" else "Nothing logged"
        return when (section) {
            TodaySection.HABITS -> if (day.summary.modules.habits == null || day.summary.modules.habits?.isEmpty() == true) "None set up yet" else nothing
            TodaySection.APPOINTMENTS -> "None this week"
            TodaySection.MEDS -> day.summary.modules.meds?.due?.filter { it.log == null && it.scheduledFor > day.now }?.minByOrNull { it.scheduledFor }
                ?.takeIf { isToday }?.let { "Next at ${Format.timeOfDay(it.scheduledFor, day.zone)}" } ?: nothing
            else -> nothing
        }
    }

    /** The sections, in the person's order, that collapse into Also today. */
    fun also(visible: List<TodaySection>, day: Day): List<TodaySection> = visible.filter { place(it, day) == Place.ALSO }

    /** "Breakfast, lunch logged": what the Food card says when amounts of food don't show. */
    fun mealsLogged(meals: List<Meal>?): String? {
        val logged = Meal.entries.filter { it in meals.orEmpty() }
        if (logged.isEmpty()) return null
        val words = logged.map { if (it == Meal.SNACK) "snacks" else it.label.lowercase() }
        return words.joinToString(", ").replaceFirstChar(Char::uppercase) + " logged"
    }
}
