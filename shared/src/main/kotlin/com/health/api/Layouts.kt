package com.health.api

import kotlinx.serialization.Serializable

/*
 * How each person arranges the apps' screens: the order of a screen's sections, and the ones they've
 * hidden (GET/PUT /api/layout). Kept on the server so it follows them between devices.
 *
 * Today, its quick actions and the list of features (the web's sidebar, the phone's Health tab) use
 * the shared ids below, so arranging them on one app arranges them on the other. Every other screen
 * is arranged per app, under a key like "web.food" or "android.mind", with section ids each app
 * chooses (keep them stable: they're saved).
 *
 * A section an app doesn't know is ignored; one the saved order doesn't name yet (a new section or
 * feature) goes where it is by default. Hidden sections can always be shown again from "Arrange".
 */

/** One screen: its sections in the person's order, and those hidden. */
@Serializable
data class ScreenLayout(val order: List<String> = emptyList(), val hidden: Set<String> = emptySet())

/** Every screen the person has arranged, by key ([Layouts]). A screen left out uses the app's default. */
@Serializable
data class LayoutPrefs(val screens: Map<String, ScreenLayout> = emptyMap())

object Layouts {
    /** Today's cards: [TodaySection] names. */
    const val TODAY = "today"

    /** Today's quick actions: [QuickAction] names. Those hidden go under "More". */
    const val QUICK = "today.quick"

    /** The features and pages, in the web's sidebar and the phone's Health tab: [Module] names and [NavPage] names. */
    const val NAV = "nav"

    /** A screen arranged per app: "web.food", "android.mind". */
    fun screen(app: String, screen: String) = "$app.$screen"

    const val WEB = "web"
    const val ANDROID = "android"

    /** At most this many screens, sections a screen, and characters in a key or id. */
    const val MAX_SCREENS = 100
    const val MAX_SECTIONS = 80
    const val MAX_ID = 64

    /** Applies [layout] to [defaults] (ids in the app's default order): the person's order first, then any new ones where they'd be. */
    fun <T : Any> arrange(defaults: List<T>, id: (T) -> String, layout: ScreenLayout?): List<T> {
        if (layout == null || layout.order.isEmpty()) return defaults
        val byId = defaults.associateBy(id)
        val placed = layout.order.mapNotNull { byId[it] }.toMutableList()
        // A section not in the saved order goes after the one before it in the defaults (or first).
        defaults.forEachIndexed { i, item ->
            if (id(item) in layout.order) return@forEachIndexed
            val before = defaults.subList(0, i).lastOrNull { prev -> placed.contains(prev) }
            placed.add(if (before == null) 0 else placed.indexOf(before) + 1, item)
        }
        return placed
    }

    /** [arrange], less what's hidden. */
    fun <T : Any> visible(defaults: List<T>, id: (T) -> String, layout: ScreenLayout?): List<T> =
        arrange(defaults, id, layout).filter { id(it) !in layout?.hidden.orEmpty() }
}

/** Today's cards, in the default order. An app shows the ones it has, for the features that are on. */
@Serializable
enum class TodaySection(val label: String) {
    SCORES("Readiness, sleep and activity scores"),
    /** What needs doing now: doses due, today's appointment, a routine, a bladder-training trip, a restock. */
    UP_NEXT("Up next"),
    INSIGHTS("For you"),
    QUICK("Quick log"),
    MEDS("Medications"),
    MIND("Mind"),
    CYCLE("Cycle"),
    CONTINENCE("Bladder & bowel"),
    SUPPLIES("Supplies running low"),
    NEURO("Autism & ADHD"),
    TRANSITION("Transition"),
    VITALS("Blood pressure & glucose"),
    DRINKS("Drinks"),
    SYMPTOMS("Symptoms"),
    HABITS("Habits"),
    HORMONES("Hormone therapy"),
    LIFE_STAGE("Life stage"),
    APPOINTMENTS("Appointments"),
    ACTIVITY("Activity"),
    SLEEP("Sleep"),
    HEART("Heart"),
    STRESS("Stress"),
    FOOD("Food"),
    WATER("Water"),
    WORKOUTS("Workouts"),
    BODY("Body"),
    /** The features with nothing logged yet today, as one card of short rows. */
    ALSO("Also today"),
}

/** Today's quick actions, in the default order. The first few for the features that are on show; the rest go under "More". */
@Serializable
enum class QuickAction(val label: String, val module: Module?) {
    FOOD("Log food", Module.NUTRITION),
    WATER("Water", Module.NUTRITION),
    MOOD("Mood", Module.MIND),
    PERIOD("Period", Module.CYCLE),
    BLADDER("Bladder & bowel", Module.CONTINENCE),
    DOSE("Medications", Module.MEDICATIONS),
    WEIGHT("Weight", Module.BODY),
    COFFEE("Coffee", Module.NUTRITION),
    DRINK("A drink", Module.NUTRITION),
    WORKOUT("Workout", Module.ACTIVITY),
    SLEEP("Add a night", Module.SLEEP),
    CHECK_IN("Check in", null),
    SYMPTOM("Symptom", Module.SYMPTOMS),
    BLOOD_PRESSURE("Blood pressure", Module.VITALS),
    GLUCOSE("Glucose", Module.VITALS),
    HABIT("Habits", Module.HABITS),
    ENERGY("Energy & focus", Module.NEURO),
    OVERLOAD("Too much", Module.NEURO),
    CALM("Calm", Module.MIND),
    MOMENT("Euphoria", Module.TRANSITION),
    PHOTO("Photo", Module.PHOTOS),
    MEASUREMENTS("Measurements", Module.BODY),
    APPOINTMENT("Appointment", Module.APPOINTMENTS),
    ;

    companion object {
        /** How many show in Today's quick row before "More", unless the person has arranged them. */
        const val SHOWN = 5
    }
}

/** Pages in the features list besides the features themselves. */
@Serializable
enum class NavPage(val label: String) {
    TODAY("Today"), TRENDS("Trends"), FOOD("Food"), REPORTS("Reports"), DEVICES("Devices"), CONDITIONS("What you're tracking"), ASSISTANT("Assistant"),
}
