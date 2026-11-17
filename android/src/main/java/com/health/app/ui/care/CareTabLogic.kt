package com.health.app.ui.care

import com.health.api.Appointment
import com.health.api.ContinenceDay
import com.health.api.ContinenceSummary
import com.health.api.ContinenceWearing
import com.health.api.CyclePhase
import com.health.api.DaySummary
import com.health.api.DoseStatus
import com.health.api.DueDose
import com.health.api.Fields
import com.health.api.GlucoseUnit
import com.health.api.HormoneOverview
import com.health.api.HormonesToday
import com.health.api.LabResult
import com.health.api.LifeStage
import com.health.api.Meal
import com.health.api.MealiePlan
import com.health.api.Medication
import com.health.api.Module
import com.health.api.NavPage
import com.health.api.Routine
import com.health.api.SupplyCategory
import com.health.api.SupplyStatus
import com.health.api.TransitionOverview
import com.health.api.VoiceProgress
import com.health.app.reminders.CarePlanner
import com.health.app.reminders.ReminderPlanner
import com.health.app.ui.Format
import com.health.app.ui.modules.Moods
import com.health.app.ui.modules.body.BODY_MEASUREMENTS
import com.health.app.ui.modules.continence.ContinenceText
import com.health.app.ui.modules.continence.WearText
import com.health.app.ui.modules.cycle.CycleText
import com.health.app.ui.modules.cycle.periodText
import com.health.app.ui.modules.transition.voice.HOME
import com.health.app.ui.modules.transition.voice.VoicePlans
import com.health.app.ui.modules.transition.voice.lessonRoute
import com.health.app.ui.tracking.Glucose
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.floor

/*
 * The Care tab (CareTab.kt), in plain Kotlin so it's tested without a phone: one card for each area
 * the person looks after, each saying what's true now in a line or two with at most one thing to do.
 * Anything due in the next few hours comes first; the rest follow the person's own order of their
 * features ([com.health.api.Layouts.NAV], shared with the web's sidebar). Hormone therapy lives in
 * Medications, supplies in Bladder & bowel, and voice, measurements and photos in Transition: each
 * has a card of its own only when the area it belongs to is off.
 */

/**
 * An area of Care: the feature it stands for, and its page. [navId] is its id in
 * [com.health.api.Layouts.NAV] (saved: never change it). The order here is the default one, the
 * same as the features list has always had.
 */
enum class CareArea(val module: Module, val route: String) {
    FOOD(Module.NUTRITION, "food"),
    MIND(Module.MIND, "mind"),
    NEURO(Module.NEURO, "neuro"),
    CYCLE(Module.CYCLE, "cycle"),
    CONTINENCE(Module.CONTINENCE, "continence"),
    MEDICATIONS(Module.MEDICATIONS, "meds"),
    VITALS(Module.VITALS, "vitals"),
    HABITS(Module.HABITS, "habits"),
    SYMPTOMS(Module.SYMPTOMS, "symptoms"),
    HORMONES(Module.HORMONES, "hormones"),
    TRANSITION(Module.TRANSITION, "transition"),
    APPOINTMENTS(Module.APPOINTMENTS, "appointments"),
    PHOTOS(Module.PHOTOS, "photos"),
    ;

    /** One name per place: the feature's own. */
    val title: String get() = module.label

    /** Food is the page [NavPage.FOOD] in the features list; the rest are their features' names. */
    val navId: String get() = if (module == Module.NUTRITION) NavPage.FOOD.name else module.name

    companion object {
        /**
         * The areas for [modules], in the default order. Hormone therapy is part of Medications and
         * progress photos part of Transition, so each is a card of its own only without the other.
         */
        fun shown(modules: Set<Module>): List<CareArea> = entries.filter { a ->
            a.module in modules && when (a) {
                HORMONES -> Module.MEDICATIONS !in modules
                PHOTOS -> Module.TRANSITION !in modules
                else -> true
            }
        }
    }
}

/** What the tab loads, each on its own, so one that fails or is slow never holds up the rest. */
enum class CarePart {
    DAY, MEDS, LABS, HORMONES, WEARING, CONTINENCE, SUPPLIES, APPOINTMENTS, MEALS, PLAN, TRANSITION, VOICE, PHOTOS, ROUTINES;

    companion object {
        /** Extras whose failing is said on their own pages, not here (tonight's Mealie plan). */
        val QUIET = setOf(PLAN)
    }
}

/** Everything the cards are made from: null until loaded (or when it couldn't be). */
data class CareData(
    val day: DaySummary? = null,
    val meds: List<Medication>? = null,
    val labs: List<LabResult>? = null,
    val hormones: HormoneOverview? = null,
    val wearing: ContinenceWearing? = null,
    val continence: ContinenceSummary? = null,
    val supplies: List<SupplyStatus>? = null,
    val appointments: List<Appointment>? = null,
    /** The meals logged today, when amounts of food don't show. */
    val meals: List<Meal>? = null,
    /** Today's plan in the person's Mealie (connected and not paused; never while viewing a share). */
    val plan: MealiePlan? = null,
    val transition: TransitionOverview? = null,
    val voice: VoiceProgress? = null,
    /** How many progress photos there are (never asked while they're locked). */
    val photos: Int? = null,
    val routines: List<Routine>? = null,
)

/**
 * What the cards need to know besides the data: the time, the features and fields that are on (the
 * shared person's while viewing), whether amounts of food show, whether it's read-only (viewing a
 * share: no actions), and how the person likes things said. [clock] says a time of day.
 */
data class CareContext(
    val now: Long,
    val zone: ZoneId,
    val today: LocalDate,
    val modules: Set<Module>,
    val fields: Set<String> = Fields.DEFAULTS,
    val foodNumbers: Boolean = true,
    val readOnly: Boolean = false,
    val glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL_L,
    /** Cycle words without fertility (the person's choice, or their fields). */
    val plainCycle: Boolean = false,
    /** Progress photos are locked on this phone: not even their number shows. */
    val photosLocked: Boolean = false,
    val viewing: Boolean = false,
    val clock: (Long) -> String,
)

/** A card's one thing to do. */
enum class CareActionKind { TAKE, CHANGE, PUT_ON, START }

/** The button: its word, what it says to a screen reader, and what it acts on. */
data class CareAction(
    val kind: CareActionKind,
    val label: String,
    val description: String,
    val dose: DueDose? = null,
    val route: String? = null,
)

/** The live row inside a card (the next dose, what's being worn, voice practice), with its one action. */
data class CareNow(val title: String, val detail: String?, val action: CareAction?)

/** A day of bladder and bowel: toilet trips (and catheter use), and leaks of either. */
data class TripDay(val date: LocalDate, val trips: Int, val leaks: Int, val today: Boolean)

/**
 * One card: its [area] (the header opens its page), an optional [subtitle] under the title, the live
 * row ([now]), Food's progress ([bar], 0–1), Bladder & bowel's week ([week]) and plain [lines].
 * [dueAt] is when something in it is due, when that's within the next few hours (or already past).
 * [loading]: nothing to say yet while its data comes.
 */
data class CareCard(
    val area: CareArea,
    val title: String = area.title,
    val subtitle: String? = null,
    val now: CareNow? = null,
    val bar: Float? = null,
    val week: List<TripDay>? = null,
    val lines: List<String> = emptyList(),
    val dueAt: Long? = null,
    val loading: Boolean = false,
) {
    val hasContent: Boolean get() = subtitle != null || now != null || bar != null || week != null || lines.isNotEmpty()
}

/** Which parts each area needs, and so which the tab loads. */
object CareParts {
    fun of(area: CareArea, ctx: CareContext): Set<CarePart> = buildSet {
        when (area) {
            CareArea.MEDICATIONS -> {
                add(CarePart.DAY)
                add(CarePart.MEDS)
                add(if (Module.HORMONES in ctx.modules) CarePart.HORMONES else CarePart.LABS)
            }
            CareArea.HORMONES -> addAll(listOf(CarePart.DAY, CarePart.HORMONES))
            CareArea.CONTINENCE -> {
                addAll(listOf(CarePart.DAY, CarePart.CONTINENCE))
                if (WearText.tracksProducts(ctx.fields, null)) add(CarePart.WEARING)
                if (Fields.CONTINENCE_SUPPLIES in ctx.fields) add(CarePart.SUPPLIES)
            }
            CareArea.APPOINTMENTS -> addAll(listOf(CarePart.DAY, CarePart.APPOINTMENTS))
            CareArea.FOOD -> {
                add(CarePart.DAY)
                if (!ctx.foodNumbers) add(CarePart.MEALS)
                // The person's own Mealie: never asked through someone else's share.
                if (!ctx.viewing) add(CarePart.PLAN)
            }
            CareArea.TRANSITION -> {
                addAll(listOf(CarePart.DAY, CarePart.TRANSITION))
                if (Fields.TRANSITION_VOICE in ctx.fields) add(CarePart.VOICE)
                if (Module.PHOTOS in ctx.modules && !ctx.photosLocked) add(CarePart.PHOTOS)
            }
            CareArea.PHOTOS -> if (!ctx.photosLocked) add(CarePart.PHOTOS)
            CareArea.NEURO -> addAll(listOf(CarePart.DAY, CarePart.ROUTINES))
            else -> add(CarePart.DAY)
        }
    }

    fun needed(ctx: CareContext): Set<CarePart> = CareArea.shown(ctx.modules).flatMapTo(LinkedHashSet()) { of(it, ctx) }
}

/** Anything due in the next few hours first (soonest first), then the rest as they were. */
object CareOrder {
    /** How far ahead "due" reaches. */
    const val AHEAD_MS = 3 * 60 * 60_000L

    fun dueFirst(cards: List<CareCard>, now: Long): List<CareCard> {
        val (due, rest) = cards.partition { c -> c.dueAt?.let { it <= now + AHEAD_MS } == true }
        return due.sortedBy { it.dueAt } + rest
    }
}

/** Each area's card from what's loaded. */
object CareCards {
    const val COULD_NOT_LOAD = "Couldn't load this just now"

    /**
     * [area]'s card. With nothing to say yet, it says why: still loading (its [pending] parts), it
     * couldn't load ([failed]), or plainly that there's nothing.
     */
    fun card(area: CareArea, data: CareData, ctx: CareContext, pending: Set<CarePart> = emptySet(), failed: Set<CarePart> = emptySet()): CareCard {
        val built = when (area) {
            CareArea.MEDICATIONS -> medications(data, ctx)
            CareArea.HORMONES -> hormones(data, ctx)
            CareArea.CONTINENCE -> continence(data, ctx)
            CareArea.APPOINTMENTS -> appointments(data, ctx)
            CareArea.FOOD -> food(data, ctx)
            CareArea.TRANSITION -> transition(data, ctx)
            CareArea.PHOTOS -> photos(data, ctx)
            CareArea.NEURO -> neuro(data, ctx)
            CareArea.MIND -> mind(data)
            CareArea.CYCLE -> cycle(data, ctx)
            CareArea.VITALS -> vitals(data, ctx)
            CareArea.SYMPTOMS -> symptoms(data)
            CareArea.HABITS -> habits(data, ctx)
        }
        if (built.hasContent) return built
        val parts = CareParts.of(area, ctx)
        return when {
            parts.any { it in pending } -> built.copy(loading = true)
            parts.any { it in failed } -> built.copy(lines = listOf(COULD_NOT_LOAD))
            else -> built.copy(lines = listOf("Nothing to show yet"))
        }
    }

    // ---- Medications, with hormone therapy

    private fun medications(data: CareData, ctx: CareContext): CareCard {
        val day = data.day?.modules?.meds
        val due = day?.due.orEmpty().sortedBy { it.scheduledFor }
        val taken = due.count { it.log?.status == DoseStatus.TAKEN }
        // The next dose not marked yet: one that's late comes first, as it's earliest.
        val next = due.firstOrNull { it.log == null }
        val now = next?.let { d ->
            val med = data.meds?.firstOrNull { it.id == d.medicationId }
            CareNow(
                "${d.name} · ${ctx.clock(d.scheduledFor)}",
                listOfNotNull(CareWords.taken(taken, due.size), med?.let { CareWords.supplyLeft(it, ctx.zone, ctx.today) }).joinToString(" · "),
                CareAction(CareActionKind.TAKE, "Take", "Take ${d.name}", dose = d).takeUnless { ctx.readOnly },
            )
        }
        val lines = buildList {
            if (day != null && next == null) add(CareWords.dosesDone(taken, due.size))
            day?.lowStock.orEmpty().filter { it.id != next?.medicationId }.takeIf { it.isNotEmpty() }?.let { low ->
                add("Running low: " + low.take(2).joinToString(", ") { "${it.name} (${it.stock ?: 0} left)" })
            }
            if (Module.HORMONES in ctx.modules) {
                CareWords.hormoneLine(data.hormones, data.day?.modules?.hormones, ctx.today)?.let(::add)
            } else {
                CareWords.lastLab(data.labs, ctx.zone)?.let(::add)
            }
        }
        return CareCard(CareArea.MEDICATIONS, now = now, lines = lines, dueAt = next?.scheduledFor?.takeIf { it <= ctx.now + CareOrder.AHEAD_MS })
    }

    /** Hormone therapy on its own (Medications off): labs, and how many medications. */
    private fun hormones(data: CareData, ctx: CareContext): CareCard {
        val today = data.day?.modules?.hormones
        val lines = listOfNotNull(
            CareWords.hormoneLine(data.hormones, today, ctx.today),
            (data.hormones?.medications?.size ?: today?.medications)?.takeIf { it > 0 }?.let { CareWords.count(it, "medication") },
        )
        return CareCard(CareArea.HORMONES, lines = lines.ifEmpty { if (data.hormones != null) listOf("No labs or medications yet") else emptyList() })
    }

    // ---- Bladder & bowel, with supplies

    private fun continence(data: CareData, ctx: CareContext): CareCard {
        val day = data.day?.modules?.continence
        val w = data.wearing ?: day?.wearing
        val row = w?.takeIf { WearText.tracksProducts(ctx.fields, it) }?.let { CareWords.wearing(it, ctx) }
        val dueAt = w?.takeIf { row != null && it.supplyId != null }?.changeDueAt?.takeIf { it <= ctx.now + CareOrder.AHEAD_MS }
        val lines = listOfNotNull(
            CareWords.trips(data.continence?.days, day, ctx.today) ?: "Nothing logged today".takeIf { data.continence != null || day != null },
            CareWords.supplies(data.supplies.takeIf { Fields.CONTINENCE_SUPPLIES in ctx.fields }, w?.supplyId),
        )
        return CareCard(CareArea.CONTINENCE, now = row, week = data.continence?.let { CareWords.week(it.days, day, ctx.today) }, lines = lines, dueAt = dueAt)
    }

    // ---- Appointments

    private fun appointments(data: CareData, ctx: CareContext): CareCard {
        if (data.appointments == null && data.day == null) return CareCard(CareArea.APPOINTMENTS)
        // The year ahead, and today's from the day (one that's begun is still on).
        val all = (data.appointments.orEmpty() + data.day?.modules?.appointments.orEmpty()).distinctBy { it.id ?: "${it.at}:${it.title}" }
        val next = all.filter { it.at + (it.durationMinutes ?: 60) * 60_000L > ctx.now }.minByOrNull { it.at }
            ?: return CareCard(CareArea.APPOINTMENTS, lines = listOf("Nothing coming up"))
        val open = next.questions.count { !it.done }
        val line = listOfNotNull(
            CareWords.appointmentWhat(next),
            open.takeIf { it > 0 }?.let { if (it == 1) "1 question ready to ask" else "$it questions ready to ask" },
        ).joinToString(" · ")
        return CareCard(
            CareArea.APPOINTMENTS, title = "Next appointment", subtitle = CareWords.appointmentWhen(next.at, ctx),
            lines = listOf(line), dueAt = next.at.takeIf { it <= ctx.now + CareOrder.AHEAD_MS },
        )
    }

    // ---- Food

    private fun food(data: CareData, ctx: CareContext): CareCard {
        val d = data.day
        val target = d?.nutritionTarget
        val bar = if (ctx.foodNumbers && d != null) target?.calories?.takeIf { it > 0 }?.let { (d.nutrition.calories / it).toFloat().coerceIn(0f, 1f) } else null
        val amounts = if (ctx.foodNumbers && d != null) {
            listOf(
                target?.calories?.takeIf { it > 0 }?.let { "${Format.number(d.nutrition.calories)} of ${Format.number(it)} kcal" } ?: "${Format.number(d.nutrition.calories)} kcal",
                target?.protein?.takeIf { it > 0 }?.let { "${Format.number(d.nutrition.protein)} of $it g protein" } ?: "${Format.number(d.nutrition.protein)} g protein",
            )
        } else {
            // Amounts don't show (eating disorder recovery): which meals are logged, nothing more.
            listOfNotNull(CareWords.mealsLogged(data.meals) ?: "Nothing logged yet today".takeIf { data.meals != null })
        }
        val tonight = data.plan?.entries?.firstOrNull { it.meal == Meal.DINNER && !it.logged }?.let { "tonight: ${it.title}" }
        val line = (amounts + listOfNotNull(tonight)).joinToString(" · ").replaceFirstChar(Char::uppercase)
        return CareCard(CareArea.FOOD, bar = bar, lines = listOfNotNull(line.ifEmpty { null }))
    }

    // ---- Transition, with voice, body measurements and progress photos (only the parts the person has on)

    private fun transition(data: CareData, ctx: CareContext): CareCard {
        val o = data.transition
        val t = data.day?.modules?.transition
        val voice = if (Fields.TRANSITION_VOICE in ctx.fields && (o != null || data.voice != null)) CareWords.voice(data.voice, o, ctx) else null
        val bits = listOfNotNull(
            o?.takeIf { Fields.TRANSITION_MEASUREMENTS in ctx.fields }?.let { CareWords.measured(it, ctx) },
            CareWords.photos(data.photos, ctx).takeIf { Module.PHOTOS in ctx.modules },
            t?.milestonesDue?.firstOrNull()?.let { "coming up: $it" },
        )
        val lines = buildList {
            if (bits.isNotEmpty()) add(bits.joinToString(" · ").replaceFirstChar(Char::uppercase))
            // Nothing else to say: how long since they began, in whatever sense matters to them.
            if (voice == null && bits.isEmpty()) {
                (t?.headline ?: o?.sinceStart?.let { "$it since you began" } ?: t?.euphoriaThisWeek?.takeIf { it > 0 }?.let { CareWords.count(it, "moment") + " of euphoria this week" })?.let(::add)
            }
        }
        val reminder = voice?.let { CareWords.voiceDue(data.voice, o, ctx) }
        return CareCard(CareArea.TRANSITION, now = voice, lines = lines.ifEmpty { if (o != null && voice == null) listOf("Nothing new this week") else emptyList() }, dueAt = reminder)
    }

    /** Progress photos on their own (Transition off): how many, or that they're locked. */
    private fun photos(data: CareData, ctx: CareContext): CareCard =
        CareCard(CareArea.PHOTOS, lines = listOfNotNull(CareWords.photos(data.photos, ctx)?.replaceFirstChar(Char::uppercase) ?: "No photos yet".takeIf { data.photos == 0 }))

    // ---- Autism & ADHD

    private fun neuro(data: CareData, ctx: CareContext): CareCard {
        val n = data.day?.modules?.neuro ?: return CareCard(CareArea.NEURO, lines = listOfNotNull("Nothing logged today".takeIf { data.day != null }))
        val routineName = n.routinesDue.firstOrNull()
        val routine = routineName?.let { name -> data.routines?.firstOrNull { it.active && it.name == name } }
        val routineAt = routine?.reminder?.let(ReminderPlanner::parseTime)?.let { ctx.today.atTime(it).atZone(ctx.zone).toInstant().toEpochMilli() }
        val first = listOfNotNull(
            n.energyLeft?.let { if (it == 1) "1 spoon left" else "$it spoons left" }
                ?: n.socialBattery?.let { "social battery $it of 10" }
                ?: n.focusMinutes.takeIf { it > 0 }?.let { "${Format.minutes(it)} of focus" },
            routineName?.let { name -> routineAt?.let { "$name at ${ctx.clock(it)}" } ?: "$name still to do" },
        ).joinToString(" · ").replaceFirstChar(Char::uppercase)
        val lines = listOfNotNull(
            first.ifEmpty { null },
            when (n.overloadsToday) {
                0 -> null
                1 -> "Today's been a lot once already. Go gently."
                else -> "Today's been a lot ${n.overloadsToday} times. Go gently."
            },
        )
        return CareCard(CareArea.NEURO, lines = lines.ifEmpty { listOf("Nothing logged today") }, dueAt = routineAt?.takeIf { it <= ctx.now + CareOrder.AHEAD_MS })
    }

    // ---- Mind, Cycle, Blood pressure & glucose, Symptoms & pain, Habits: a line each

    private fun mind(data: CareData): CareCard {
        val d = data.day ?: return CareCard(CareArea.MIND)
        val m = d.modules.mind
        val parts = listOfNotNull(
            m?.mood?.let { "Feeling ${Moods.label(Moods.nearest(it)).lowercase()}" },
            m?.entries?.takeIf { it > 1 }?.let { "$it check-ins" },
            m?.mindfulMinutes?.takeIf { it > 0 }?.let { "${Format.minutes(it.toInt().coerceAtLeast(1))} of calm" },
            m?.journal?.takeIf { it > 0 }?.let { CareWords.count(it, "journal entry", "journal entries") },
        )
        return CareCard(CareArea.MIND, lines = listOf(parts.joinToString(" · ").replaceFirstChar(Char::uppercase).ifEmpty { "No check-in yet today" }))
    }

    private fun cycle(data: CareData, ctx: CareContext): CareCard {
        val d = data.day ?: return CareCard(CareArea.CYCLE)
        val stage = d.modules.lifeStage
        val c = d.modules.cycle
        val line = when {
            stage != null && stage.stage != LifeStage.CYCLING && stage.stage != LifeStage.TRYING -> stage.headline
            c?.cycleDay != null -> listOfNotNull(
                "Day ${c.cycleDay}",
                c.phase.takeIf { it != CyclePhase.UNKNOWN }?.let { CycleText.phase(it, ctx.plainCycle) },
                periodText(c.daysUntilPeriod)?.replaceFirstChar(Char::lowercase),
            ).joinToString(" · ")
            c?.logged != null -> "Logged today" + (c.logged?.flow?.let { ": ${it.label.lowercase()} flow" } ?: "")
            else -> periodText(c?.daysUntilPeriod) ?: "Nothing logged today"
        }
        return CareCard(CareArea.CYCLE, lines = listOf(line))
    }

    private fun vitals(data: CareData, ctx: CareContext): CareCard {
        val d = data.day ?: return CareCard(CareArea.VITALS)
        val v = d.modules.vitals
        val bp = v?.bloodPressure?.let { b ->
            "${b.systolic}/${b.diastolic} mmHg" + (v.category?.let { ", ${it.label.lowercase()}" } ?: "") + CareWords.whenRead(b.at, ctx)
        }
        val glucose = v?.glucose?.let { g -> "glucose ${Glucose.text(g.mmolL, ctx.glucoseUnit)}" + CareWords.whenRead(g.at, ctx) }
        val parts = listOfNotNull(bp, glucose, v?.timeInRange?.let { "$it% in range" })
        return CareCard(CareArea.VITALS, lines = listOf(parts.joinToString(" · ").replaceFirstChar(Char::uppercase).ifEmpty { "No readings yet" }))
    }

    private fun symptoms(data: CareData): CareCard {
        val d = data.day ?: return CareCard(CareArea.SYMPTOMS)
        val s = d.modules.symptoms
        val names = (s?.entries.orEmpty().map { it.symptom } + s?.migraines.orEmpty().map { "migraine" }).map { it.lowercase() }.distinct()
        val line = if (names.isEmpty()) "Nothing logged today" else (listOf(names.joinToString(", ")) + listOfNotNull(s?.worst?.let { "worst $it of 10" })).joinToString(" · ")
        return CareCard(CareArea.SYMPTOMS, lines = listOf(line.replaceFirstChar(Char::uppercase)))
    }

    private fun habits(data: CareData, ctx: CareContext): CareCard {
        val d = data.day ?: return CareCard(CareArea.HABITS)
        val list = d.modules.habits.orEmpty().filter { it.habit.active }
        if (list.isEmpty()) return CareCard(CareArea.HABITS, lines = listOf("No habits yet"))
        val left = list.filter { !it.done }
        val line = when {
            left.isEmpty() -> if (list.size == 1) "Done today" else "All ${list.size} done today"
            else -> "${list.size - left.size} of ${list.size} done · still to do: ${left.joinToString(", ") { it.habit.name }}"
        }
        val due = left.mapNotNull { h -> h.habit.reminderTime?.let(ReminderPlanner::parseTime)?.let { ctx.today.atTime(it).atZone(ctx.zone).toInstant().toEpochMilli() } }.minOrNull()
        return CareCard(CareArea.HABITS, lines = listOf(line), dueAt = due?.takeIf { it <= ctx.now + CareOrder.AHEAD_MS })
    }
}

/** The cards' words: short, warm and plain. */
object CareWords {
    /** "1 trip", "3 trips"; [plural] where adding an "s" won't do. */
    fun count(n: Int, one: String, plural: String = "${one}s"): String = if (n == 1) "1 $one" else "$n $plural"

    /** "1 of 2 taken today". */
    fun taken(taken: Int, of: Int): String? = if (of == 0) null else "$taken of $of taken today"

    /** Every scheduled dose is marked: how it went. */
    fun dosesDone(taken: Int, of: Int): String = when {
        of == 0 -> "Nothing scheduled today"
        taken == of -> if (of == 1) "Today's dose is taken" else "All $of doses taken today"
        else -> "$taken of $of taken today"
    }

    /** "18 days left", "12 left": how long a medication's counted doses will last. */
    fun supplyLeft(med: Medication, zone: ZoneId, today: LocalDate): String? {
        val stock = med.stock ?: return null
        val days = CarePlanner.daysLeft(med, zone, today) ?: return "$stock left"
        return when {
            days < 1 -> "less than a day left"
            else -> floor(days).toInt().let { if (it == 1) "1 day left" else "$it days left" }
        }
    }

    /**
     * "Hormone levels: last lab 1 Oct, in your range · next due 24 Dec". In range only when the
     * person has targets and the day says none is outside them; null with nothing to say.
     */
    fun hormoneLine(o: HormoneOverview?, t: HormonesToday?, today: LocalDate): String? {
        val last = date(o?.lastLabs)
        val next = date(o?.nextLabsDue ?: t?.nextLabsDue)
        val out = t?.outOfRange.orEmpty()
        val range = when {
            out.isNotEmpty() -> "${out.joinToString(" and ")} outside your range"
            t != null && last != null && o?.settings?.targets.orEmpty().isNotEmpty() -> "in your range"
            else -> null
        }
        val parts = listOfNotNull(
            last?.let { "last lab ${Format.shortDay(it)}" + (range?.let { r -> ", $r" } ?: "") } ?: range,
            next?.let {
                when {
                    it.isBefore(today) -> "labs were due ${Format.shortDay(it)}"
                    it == today -> "labs due today"
                    else -> "next due ${Format.shortDay(it)}"
                }
            },
        )
        return if (parts.isEmpty()) null else "Hormone levels: " + parts.joinToString(" · ")
    }

    /** "Last lab: Ferritin, 3 Oct". */
    fun lastLab(labs: List<LabResult>?, zone: ZoneId): String? = labs?.maxByOrNull { it.at }?.let { l ->
        "Last lab: ${l.name}, " + Format.shortDay(date(l.date) ?: Instant.ofEpochMilli(l.at).atZone(zone).toLocalDate())
    }

    /** What's being worn, or nothing on, as the live row; with Change (or Put on) unless read-only. */
    fun wearing(w: ContinenceWearing, ctx: CareContext): CareNow? = when {
        w.supplyId != null -> {
            val name = w.name ?: WearText.kindWord(w.kind)?.lowercase()?.let { "a $it" } ?: "a product"
            val since = w.since?.let { "on since ${ctx.clock(it)} (${WearText.hoursShort(WearText.onFor(w, ctx.now))})" }
            val state = WearText.stateLine(w.wear?.takeIf { it.supplyId == w.supplyId })
            CareNow(
                if (WearText.changeDue(w, ctx.now)) "Time to change: $name" else "Wearing $name",
                listOfNotNull(state, since).joinToString(" · ").replaceFirstChar(Char::uppercase).ifEmpty { null },
                CareAction(CareActionKind.CHANGE, "Change", "Log a change").takeUnless { ctx.readOnly },
            )
        }
        w.nothingOn -> CareNow(
            "Nothing on for now", w.since?.let { "Since ${ctx.clock(it)}" },
            CareAction(CareActionKind.PUT_ON, "Put on", "Put something on").takeUnless { ctx.readOnly },
        )
        else -> null
    }

    /** The last seven days, oldest first, today last: the summary's days, with today's from the day (fresher). */
    fun week(days: List<ContinenceDay>, today: ContinenceDay?, date: LocalDate): List<TripDay> {
        val byDate = days.associateBy { it.date } + listOfNotNull(today?.let { date.toString() to it })
        return (6 downTo 0).map { back ->
            val d = date.minusDays(back.toLong())
            val day = byDate[d.toString()]
            TripDay(d, (day?.voids ?: 0) + (day?.catheters ?: 0), (day?.leaks ?: 0) + (day?.bowelLeaks ?: 0), back == 0)
        }
    }

    /** "5 trips today, no leaks · 1 leak this week, down from 4"; null with nothing logged. */
    fun trips(days: List<ContinenceDay>?, today: ContinenceDay?, date: LocalDate): String? {
        val t = today ?: days?.firstOrNull { it.date == date.toString() }
        val todayPart = t?.let {
            val trips = it.voids + it.catheters
            val leaks = it.leaks + it.bowelLeaks
            when {
                trips > 0 -> "${count(trips, "trip")} today, " + if (leaks == 0) "no leaks" else count(leaks, "leak")
                leaks > 0 -> "${count(leaks, "leak")} today"
                it.pads > 0 -> "${count(it.pads, "change")} today"
                else -> null
            }
        }
        val weekPart = days?.let { list ->
            val thisWeek = week(list, today, date).sumOf { it.leaks }
            // The week before, when the diary goes back that far.
            val earlier = list.filter { d -> date(d.date)?.let { it.isBefore(date.minusDays(6)) } == true }
            val before = earlier.takeIf { it.isNotEmpty() }?.sumOf { it.leaks + it.bowelLeaks }
            val words = if (thisWeek == 0) "no leaks this week" else "${count(thisWeek, "leak")} this week"
            words + when {
                before == null -> ""
                thisWeek < before -> ", down from $before"
                thisWeek > before -> ", up from $before"
                thisWeek == 0 -> ""
                else -> ", the same as the week before"
            }
        }
        return listOfNotNull(todayPart, weekPart).joinToString(" · ").replaceFirstChar(Char::uppercase).ifEmpty { null }
    }

    /** What a screen reader says for the week's chart. */
    fun weekDescription(week: List<TripDay>): String {
        fun name(d: TripDay) = if (d.today) "today" else d.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val trips = week.joinToString(", ") { "${name(it)} ${it.trips}" }
        val leaks = week.filter { it.leaks > 0 }
        val leakWords = if (leaks.isEmpty()) "No leaks." else "Leaks: " + leaks.joinToString(", ") { "${name(it)} ${it.leaks}" } + "."
        return "Toilet trips over the last 7 days: $trips. $leakWords"
    }

    /** "Night briefs: 14 left · about a week": what's on now, else whatever will run out first. */
    fun supplies(statuses: List<SupplyStatus>?, wornId: String?): String? {
        val list = statuses.orEmpty().filter { it.supply.active && !it.supply.reusable && it.supply.kind.category == SupplyCategory.CONTINENCE }
        val s = list.firstOrNull { it.supply.id != null && it.supply.id == wornId }
            ?: list.filter { it.daysLeft != null }.minByOrNull { it.daysLeft!! }
            ?: list.firstOrNull()
            ?: return null
        return "${ContinenceText.supplyName(s.supply)}: ${s.supply.onHand} left" + (s.daysLeft?.let { " · ${lasts(it)}" } ?: "")
    }

    /** "about 3 days", "about a week", "about 2 weeks", "a month or more". */
    fun lasts(days: Int): String = when {
        days <= 1 -> "about a day"
        days < 7 -> "about $days days"
        days < 11 -> "about a week"
        days < 28 -> "about ${(days + 3) / 7} weeks"
        else -> "a month or more"
    }

    /** "Today, 2:30 PM", "Tomorrow, 9:30 AM", "Thursday 24 November, 2:30 PM". */
    fun appointmentWhen(at: Long, ctx: CareContext): String {
        val day = Instant.ofEpochMilli(at).atZone(ctx.zone).toLocalDate()
        val words = when (day) {
            ctx.today -> "Today"
            ctx.today.plusDays(1) -> "Tomorrow"
            else -> DateTimeFormatter.ofPattern(if (day.year == ctx.today.year) "EEEE d MMMM" else "EEEE d MMMM yyyy").format(day)
        }
        return "$words, ${ctx.clock(at)}"
    }

    /** "Check-up with Dr Patel". */
    fun appointmentWhat(a: Appointment): String =
        a.title + (a.with?.takeIf { it.isNotBlank() && !a.title.contains(it, ignoreCase = true) }?.let { " with $it" } ?: "")

    /** "Breakfast, lunch logged", for when amounts of food don't show. */
    fun mealsLogged(meals: List<Meal>?): String? {
        val logged = Meal.entries.filter { it in meals.orEmpty() }
        if (logged.isEmpty()) return null
        return logged.joinToString(", ") { if (it == Meal.SNACK) "snacks" else it.label.lowercase() }.replaceFirstChar(Char::uppercase) + " logged"
    }

    /** Voice practice as the live row: the streak and today, and Start (today's lesson, or the trainer to set it up). */
    fun voice(progress: VoiceProgress?, o: TransitionOverview?, ctx: CareContext): CareNow {
        val plan = progress?.plan
        val setUp = VoicePlans.isSetUp(plan)
        val minutes = voiceMinutesToday(o, ctx)
        val rest = plan != null && VoicePlans.restDay(plan, ctx.today)
        val detail = listOfNotNull(
            progress?.streakDays?.takeIf { it > 0 }?.let { if (it == 1) "1 day in a row" else "$it days in a row" },
            when {
                minutes > 0 -> count(minutes, "minute") + " today"
                rest -> "a rest day today"
                else -> null
            },
        ).joinToString(" · ").replaceFirstChar(Char::uppercase).ifEmpty { if (setUp) "Not yet today" else "Short guided lessons, at your pace" }
        val route = progress?.nextLessonId?.takeIf { setUp }?.let(::lessonRoute) ?: HOME
        return CareNow("Voice practice", detail, CareAction(CareActionKind.START, "Start", "Start voice practice", route = route).takeUnless { ctx.readOnly })
    }

    /** When practice is due today (the plan's reminder), if it hasn't happened yet and today isn't a rest day. */
    fun voiceDue(progress: VoiceProgress?, o: TransitionOverview?, ctx: CareContext): Long? {
        val plan = progress?.plan ?: return null
        if (!VoicePlans.isSetUp(plan) || VoicePlans.restDay(plan, ctx.today) || voiceMinutesToday(o, ctx) > 0) return null
        val at = plan.reminder?.let(ReminderPlanner::parseTime)?.let { ctx.today.atTime(it).atZone(ctx.zone).toInstant().toEpochMilli() } ?: return null
        return at.takeIf { it <= ctx.now + CareOrder.AHEAD_MS }
    }

    private fun voiceMinutesToday(o: TransitionOverview?, ctx: CareContext): Int =
        o?.voice.orEmpty().filter { Instant.ofEpochMilli(it.at).atZone(ctx.zone).toLocalDate() == ctx.today }.sumOf { it.minutes }

    /** "Measurements last taken 3 Nov": the latest set with a body measurement in it. */
    fun measured(o: TransitionOverview, ctx: CareContext): String? =
        o.measurements.filter { set -> set.values.keys.any { it in BODY_MEASUREMENTS } }.maxByOrNull { it.at }
            ?.let { "measurements last taken ${Format.shortDay(Instant.ofEpochMilli(it.at).atZone(ctx.zone).toLocalDate())}" }

    /** "12 photos", or that they're locked (and then not even how many). */
    fun photos(n: Int?, ctx: CareContext): String? = when {
        ctx.photosLocked -> "photos locked"
        n == null || n == 0 -> null
        else -> count(n, "photo")
    }

    /** ", at 8:10 AM" today, ", on 12 Nov" before. */
    fun whenRead(at: Long, ctx: CareContext): String {
        val day = Instant.ofEpochMilli(at).atZone(ctx.zone).toLocalDate()
        return if (day == ctx.today) " at ${ctx.clock(at)}" else " on ${Format.shortDay(day)}"
    }

    private fun date(text: String?): LocalDate? = text?.let { com.health.app.attempt { LocalDate.parse(it) }.getOrNull() }

}
