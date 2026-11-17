package com.health.api

import kotlinx.serialization.Serializable

/*
 * Today as one place (GET /api/feed?date=): "Right now", only the live things, each with one
 * action, and "Your day", one timeline of everything logged or recorded that day, from every
 * feature that's on. Each feature adds its part through the server's FeatureHooks.
 */

/**
 * What a timeline item is. [OTHER] stands in for a kind this version doesn't know (a newer
 * server's): the apps show its title and detail plainly.
 */
@Serializable
enum class TimelineKind {
    SLEEP, NAP, WORKOUT, MEAL, DRINK,
    DOSE_TAKEN, DOSE_SKIPPED,
    /** A hormone medication's dose or schedule changed (or it started). */
    DOSE_CHANGE,
    /** A toilet trip, a bowel movement, emptying with a catheter. */
    BATHROOM,
    /** A leak, a bowel accident, a wet night. */
    LEAK,
    PRODUCT_CHANGE,
    PELVIC_FLOOR,
    MOOD,
    /** The day's check-in, or an Autism & ADHD check-in. */
    CHECK_IN,
    /** A breathing exercise, grounding or meditation. */
    CALM,
    JOURNAL,
    /** The day's energy budget. */
    ENERGY,
    /** A time things got too much. */
    OVERLOAD,
    FOCUS,
    ROUTINE,
    /** A symptom, pain or a migraine. */
    SYMPTOM,
    BLOOD_PRESSURE,
    GLUCOSE,
    WEIGHT,
    MEASUREMENT,
    HABIT,
    APPOINTMENT,
    VOICE_PRACTICE,
    /** A moment of euphoria or dysphoria. */
    TRANSITION_MOMENT,
    CYCLE_DAY,
    PHOTO,
    HEALTH_ALERT,
    OTHER,
}

/** One thing in the day's timeline ("Your day"). */
@Serializable
data class TimelineItem(
    /** Stable: the kind and the source's own id ("dose_taken:4f2a…"). */
    val id: String,
    val kind: TimelineKind = TimelineKind.OTHER,
    /** When it happened (or began). */
    val at: Long,
    /** When it ended, for things that last (a night, a workout, a migraine). */
    val end: Long? = null,
    /** Something of the day without a time of its own (a cycle day, a habit ticked, the check-in): [at] is the day's start. */
    val allDay: Boolean = false,
    /** Short and plain: "Sleep", "Night brief", "Porridge and banana". */
    val title: String,
    /** A factual line: "Changed · the one before was damp after 2 h 30 min", "Lunch · 610 kcal". */
    val detail: String? = null,
    /** The feature it comes from (shown to someone viewing a share only if it's shared). */
    val module: Module? = null,
    /** The page a tap opens. */
    val page: AppPage? = null,
    /** What the apps can open or edit: the entry's id (a date, `YYYY-MM-DD`, for a day's entry like the cycle or the check-in). */
    val ref: String? = null,
)

/** What's live now ("Right now"). [OTHER] stands in for a kind this version doesn't know. */
@Serializable
enum class NowKind {
    /** What's being worn now (bladder and bowel). */
    WEARING,
    /** A dose due within the hour. */
    DOSE_DUE,
    /** A dose whose time has passed without being marked. */
    DOSE_OVERDUE,
    /** Food so far today. */
    FOOD_TODAY,
    /** An appointment in the next seven days. */
    APPOINTMENT_SOON,
    /** Today's energy budget. */
    ENERGY_TODAY,
    VOICE_PRACTICE_DUE,
    /** A health alert not put away yet. */
    HEALTH_ALERT,
    OTHER,
}

/** The one thing a "Right now" item offers. */
@Serializable
data class NowAction(
    /** The button's words: "Change", "Take", "Add", "Start", "Open". */
    val label: String,
    /** What it does: one of the keys below. */
    val action: String,
    /** What it needs, by name: "medicationId", "scheduledFor" (epoch ms), "supplyId", "meal", "date", "lessonId", "appointmentId", "alertId". */
    val ids: Map<String, String> = emptyMap(),
) {
    companion object {
        /** Opens the change sheet for what's on ([ids]: supplyId). */
        const val CONTINENCE_CHANGE = "continence.change"
        /** Marks a scheduled dose taken ([ids]: medicationId, scheduledFor). */
        const val DOSE_TAKE = "dose.take"
        /** Logs food ([ids]: meal, date). */
        const val FOOD_ADD = "food.add"
        /** Adds to today's energy budget ([ids]: date). */
        const val ENERGY_ADD = "energy.add"
        /** Starts voice practice ([ids]: lessonId, when there's a next lesson). */
        const val VOICE_START = "voice.start"
        /** Opens an appointment ([ids]: appointmentId). */
        const val APPOINTMENT_OPEN = "appointment.open"
        /** Opens a health alert ([ids]: alertId). */
        const val ALERT_OPEN = "alert.open"
    }
}

/** One live thing ("Right now"). */
@Serializable
data class NowItem(
    /** Stable while it's the same thing ("dose:<medication>:<time>"). */
    val id: String,
    val kind: NowKind = NowKind.OTHER,
    val title: String,
    val detail: String? = null,
    /** How far along, 0–1, where that means something (food against the day's target, time on against the usual). */
    val progress: Double? = null,
    val module: Module? = null,
    val page: AppPage? = null,
    val action: NowAction? = null,
    /** Higher comes first: 100 could need care now, 80s a dose, 70 time for a check, down to 20 for a gentle nudge. */
    val priority: Int = 0,
    /** The moment it's about: a dose's time, an appointment's, when something went on. */
    val at: Long? = null,
)

/**
 * A day for Today (GET /api/feed?date=): [now] is only for today and only for the person's own
 * account (empty while viewing a share); [items] newest first, then the day's untimed things.
 */
@Serializable
data class DayFeed(
    val date: String,
    val now: List<NowItem> = emptyList(),
    val items: List<TimelineItem> = emptyList(),
)
