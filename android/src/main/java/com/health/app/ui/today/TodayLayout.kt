package com.health.app.ui.today

import com.health.api.Layouts
import com.health.api.Module
import com.health.api.QuickAction
import com.health.api.ScreenLayout
import com.health.api.TodaySection

/**
 * Which of Today's sections show, in what order, and the + sheet's grid, in plain Kotlin so it's
 * tested without a phone. The ids are the shared [TodaySection] and [QuickAction] names: arranging
 * Today here arranges it on the web too (docs/API.md, "Arranging screens"). Today's other
 * TodaySection ids (the features' old cards, Quick log, Also today) stay saved, unused here.
 */
object TodayLayout {
    /** Today's sections in the default order: the scores, Right now, Your day and For you. A health alert's card is always above them. */
    val SECTIONS = listOf(TodaySection.SCORES, TodaySection.UP_NEXT, TodaySection.DAY, TodaySection.INSIGHTS)

    /** Whether [section] is on Today with [modules] on: the scores need a device's features; the rest are everyone's. */
    fun applies(section: TodaySection, modules: Set<Module>): Boolean = when (section) {
        TodaySection.SCORES -> Module.ACTIVITY in modules || Module.SLEEP in modules
        TodaySection.UP_NEXT, TodaySection.DAY, TodaySection.INSIGHTS -> true
        else -> false
    }

    /** Today's sections for the features that are on, in the default order: what the Arrange sheet lists. */
    fun sections(modules: Set<Module>): List<TodaySection> = SECTIONS.filter { applies(it, modules) }

    /** The sections to draw, in the person's order, without those they've hidden. */
    fun visible(modules: Set<Module>, layout: ScreenLayout?): List<TodaySection> = Layouts.visible(sections(modules), { it.name }, layout)

    /** A section's name on Today and in the Arrange sheet: Up next is called Right now. */
    fun label(section: TodaySection): String = when (section) {
        TodaySection.UP_NEXT -> "Right now"
        TodaySection.SCORES -> "Scores"
        else -> section.label
    }

    /**
     * The ways to log this app offers with [modules] on, in the default order: Water only while
     * water is shown, Measurements only with their fields.
     */
    fun quickActions(modules: Set<Module>, water: Boolean = true, measurements: Boolean = false): List<QuickAction> =
        QuickAction.entries.filter { a ->
            (a.module == null || a.module in modules) && when (a) {
                QuickAction.WATER -> water
                QuickAction.MEASUREMENTS -> measurements
                else -> true
            }
        }

    /** The + sheet's grid: every way to log for the features on, in the person's order (today.quick). None is left out. */
    fun grid(available: List<QuickAction>, layout: ScreenLayout?): List<QuickAction> = Layouts.arrange(available, { it.name }, layout)
}
