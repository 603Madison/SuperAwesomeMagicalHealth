package com.health.app.ui.today

import com.health.api.AppPage
import com.health.api.DaySummary
import com.health.api.HealthAlert
import com.health.api.Insight
import com.health.api.Module
import com.health.api.NowAction
import com.health.api.NowItem
import com.health.api.NowKind
import com.health.api.StageSpan
import com.health.api.TimelineItem
import com.health.api.TimelineKind
import com.health.api.TodaySection
import com.health.app.ui.Format
import kotlin.math.roundToInt

/*
 * Today's two lists come from the server's day feed (GET /api/feed): Right now (only what's live,
 * each with one action) and Your day (one timeline, newest first, the day's untimed things last).
 * What they say is the server's, as it comes; here is what's the app's: which action does what,
 * where a tap goes, what a screen reader hears, and the scores' sentence and For you.
 */

object TodayRows {
    /** "Good morning", "Good afternoon", "Good evening". */
    fun greeting(hour: Int): String = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }

    // ---- Right now ----

    /** A health alert that could need care now (the server ranks those highest): it keeps the danger colours. */
    const val URGENT = 100

    fun urgent(item: NowItem): Boolean = item.kind == NowKind.HEALTH_ALERT && item.priority >= URGENT

    /**
     * Right now as Today shows it: the feed's live things, most important first, less the health
     * alert that already has the card at the top ([card]).
     */
    fun now(items: List<NowItem>, card: HealthAlert? = null): List<NowItem> =
        items.filterNot { card != null && it.kind == NowKind.HEALTH_ALERT && (it.action?.ids?.get("alertId") == card.id || it.id == "alert:${card.id}") }
            .sortedByDescending { it.priority }

    /** What the + sheet offers from Right now: the things to log there and then (a change, a dose, energy, voice practice). */
    fun logNow(items: List<NowItem>): List<NowItem> =
        items.filter { it.action?.action in setOf(NowAction.CONTINENCE_CHANGE, NowAction.DOSE_TAKE, NowAction.ENERGY_ADD, NowAction.VOICE_START) }
            .sortedByDescending { it.priority }

    /** What an action's button says to a screen reader: "Take Estradiol", "Log a change", "Log food". */
    fun spoken(item: NowItem): String {
        val action = item.action ?: return item.title
        return when (action.action) {
            NowAction.FOOD_ADD -> "Log food"
            NowAction.ENERGY_ADD -> if (item.module == Module.NEURO) "Check in on your energy and focus" else "Log your energy"
            NowAction.VOICE_START -> "Start voice practice"
            NowAction.CONTINENCE_CHANGE -> "Log a change"
            else -> "${action.label} ${item.title}"
        }
    }

    /** One of an action's ids, by name (the server's names first, then the plain "id"). */
    fun id(action: NowAction, vararg names: String): String? = names.firstNotNullOfOrNull { action.ids[it]?.takeIf { v -> v.isNotBlank() } }

    /**
     * Whether [item]'s action is the quick energy and focus check-in, done in its sheet: energy with
     * Autism & ADHD on and its check-ins on ([checkIns]). Mind's own energy budget opens its page.
     */
    fun checksIn(item: NowItem, modules: Set<Module>, checkIns: Boolean): Boolean =
        item.action?.action == NowAction.ENERGY_ADD && checkIns && Module.NEURO in modules

    /**
     * Where an action that opens a page goes: food for its meal, Mind's energy budget, the next voice
     * lesson, an appointment, the alerts; for one this app doesn't know, the item's page. Null for
     * the actions done in place (a change, a dose; energy and focus is its sheet, [checksIn]).
     */
    fun route(item: NowItem, modules: Set<Module>, today: String): String? {
        val action = item.action
        return when (action?.action) {
            NowAction.CONTINENCE_CHANGE, NowAction.DOSE_TAKE -> null
            NowAction.FOOD_ADD -> "food/add/${id(action, "date") ?: today}/${id(action, "meal") ?: com.health.api.Meal.SNACK.name}"
            // Autism & ADHD has energy and focus (its page, where the check-ins are off); without it, Mind's budget.
            NowAction.ENERGY_ADD -> if (Module.NEURO in modules) "neuro" else "neuro/energy"
            NowAction.VOICE_START -> id(action, "lessonId")?.let { "transition/voice-lesson?id=$it" } ?: "transition/voice-training"
            NowAction.APPOINTMENT_OPEN -> id(action, "appointmentId", "id")?.let { "appointments/edit?id=$it" } ?: "appointments"
            NowAction.ALERT_OPEN -> "alerts"
            else -> item.page?.let { InsightLinks.target(it, modules).route }
        }
    }

    // ---- Your day ----

    /** The "Now" line at the top of today's timeline: "Heart rate 72 · 6,240 steps so far"; null with neither. */
    fun nowLine(summary: DaySummary?, now: Long): String? {
        if (summary == null) return null
        val heart = summary.heartRate.lastOrNull()?.takeIf { now - it.t <= 60 * 60_000L }?.let { "Heart rate ${it.v.roundToInt()}" }
        val steps = summary.steps.takeIf { it > 0 }?.let { "${Format.number(it)} steps so far" }
        return listOf(heart, steps).filterNotNull().joinToString(" · ").ifEmpty { null }
    }

    /**
     * Where a tap on a timeline item goes: its page, opened at the entry where the app can ([TimelineItem.ref]:
     * an appointment, the cycle's day); null where there's nowhere to go. [measurements]: whether
     * body measurements have their page.
     */
    fun route(item: TimelineItem, modules: Set<Module>, measurements: Boolean = false): String? {
        val ref = item.ref?.takeIf { it.isNotBlank() }
        return when {
            item.kind == TimelineKind.SLEEP || item.kind == TimelineKind.NAP -> "recent"
            item.kind == TimelineKind.HEALTH_ALERT -> "alerts"
            item.kind == TimelineKind.MEASUREMENT && measurements -> "measurements"
            item.kind == TimelineKind.VOICE_PRACTICE -> "transition/voice-training"
            item.page == AppPage.APPOINTMENTS && ref != null -> "appointments/edit?id=$ref"
            item.page == AppPage.CYCLE && ref != null && Regex("""\d{4}-\d{2}-\d{2}""").matches(ref) -> "cycle?log=$ref"
            item.page == AppPage.TODAY -> null
            else -> item.page?.let { InsightLinks.target(it, modules, measurements).route }
        }
    }

    /** A night's stages, to draw under its row: the day's night, where it's the one the item is about. */
    fun stages(item: TimelineItem, summary: DaySummary?): List<StageSpan> {
        if (item.kind != TimelineKind.SLEEP) return emptyList()
        val night = summary?.sleepSummary ?: return emptyList()
        return if (night.start < (item.end ?: item.at) && item.at < night.end) night.stages else emptyList()
    }

    // ---- The scores' sentence, and For you ----

    /**
     * The one plain sentence under the scores: the day's readiness insight where there is one,
     * else a gentle word from readiness (or sleep). Never alarming; null without scores.
     */
    fun scoresLine(summary: DaySummary): String? {
        scoresInsight(summary)?.let { return it.body }
        val score = summary.readiness?.score ?: summary.sleep?.score ?: return null
        return when {
            score >= 85 -> "Well rested and ready for the day."
            score >= 70 -> "A good day to keep things steady."
            score >= 60 -> "A fair start. Take the day at your own pace."
            else -> "Your body's asking for an easier day. Go gently."
        }
    }

    /** The insight the scores' sentence comes from: the day's readiness one (it opens Today). */
    fun scoresInsight(summary: DaySummary): Insight? = summary.insights.firstOrNull { it.link == AppPage.TODAY }

    /** The sections Right now is showing for ([Insight.shownBy]): For you leaves out what would repeat them. */
    fun showing(now: List<NowItem>): Set<TodaySection> = buildSet {
        if (now.any { it.kind == NowKind.DOSE_DUE || it.kind == NowKind.DOSE_OVERDUE }) add(TodaySection.MEDS)
        if (now.any { it.kind == NowKind.APPOINTMENT_SOON }) add(TodaySection.APPOINTMENTS)
    }

    /**
     * For you: at most [max] of the day's insights for the features on, never a health alert (the
     * card at the top and Right now have those), never the one under the scores ([scores]: they're
     * showing), nor one repeating what Right now shows ([showing]).
     */
    fun forYou(summary: DaySummary, modules: Set<Module>, showing: Set<TodaySection>, scores: Boolean, max: Int = 2): List<Insight> {
        val underScores = if (scores) scoresInsight(summary) else null
        return InsightLinks.shown(summary.insights, modules, showing).filter { it.link != AppPage.ALERTS && it != underScores }.take(max)
    }

    /** The meal it likely is at [hour], for the + sheet's suggestion: "Breakfast", "Afternoon snack". */
    fun mealTitle(hour: Int): String = when (hour) {
        in 4..10 -> "Breakfast"
        in 11..14 -> "Lunch"
        in 15..16 -> "Afternoon snack"
        in 17..21 -> "Dinner"
        else -> "A snack"
    }
}
