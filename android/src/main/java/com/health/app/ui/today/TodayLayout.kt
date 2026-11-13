package com.health.app.ui.today

import com.health.api.Layouts
import com.health.api.Module
import com.health.api.QuickAction
import com.health.api.ScreenLayout
import com.health.api.TodaySection

/**
 * Which of Today's cards and quick actions show, and in what order, in plain Kotlin so it's tested
 * without a phone. The ids are the shared [TodaySection] and [QuickAction] names: arranging Today
 * here arranges it on the web too (docs/API.md, "Arranging screens").
 */
object TodayLayout {
    /** Whether [section] has a card here with [modules] on (and water, a field that can be hidden). */
    fun applies(section: TodaySection, modules: Set<Module>, water: Boolean = true): Boolean {
        fun on(vararg any: Module) = any.any { it in modules }
        return when (section) {
            TodaySection.SCORES, TodaySection.HEART -> on(Module.ACTIVITY, Module.SLEEP)
            TodaySection.INSIGHTS, TodaySection.QUICK -> true
            TodaySection.MEDS -> on(Module.MEDICATIONS)
            TodaySection.MIND -> on(Module.MIND)
            TodaySection.CYCLE, TodaySection.LIFE_STAGE -> on(Module.CYCLE)
            TodaySection.CONTINENCE -> on(Module.CONTINENCE)
            // Bladder and bowel products, and period products.
            TodaySection.SUPPLIES -> on(Module.CONTINENCE, Module.CYCLE)
            // With Mind alone, its energy budget.
            TodaySection.NEURO -> on(Module.NEURO, Module.MIND)
            TodaySection.TRANSITION -> on(Module.TRANSITION)
            TodaySection.VITALS -> on(Module.VITALS)
            TodaySection.DRINKS, TodaySection.FOOD -> on(Module.NUTRITION)
            TodaySection.WATER -> on(Module.NUTRITION) && water
            TodaySection.SYMPTOMS -> on(Module.SYMPTOMS)
            TodaySection.HABITS -> on(Module.HABITS)
            TodaySection.HORMONES -> on(Module.HORMONES)
            TodaySection.APPOINTMENTS -> on(Module.APPOINTMENTS)
            TodaySection.ACTIVITY, TodaySection.STRESS, TodaySection.WORKOUTS -> on(Module.ACTIVITY)
            TodaySection.SLEEP -> on(Module.SLEEP)
            TodaySection.BODY -> on(Module.BODY)
        }
    }

    /** Today's sections for the features that are on, in the default order: what the Arrange sheet lists. */
    fun sections(modules: Set<Module>, water: Boolean = true): List<TodaySection> = TodaySection.entries.filter { applies(it, modules, water) }

    /** The sections to draw, in the person's order, without those they've hidden. */
    fun visible(modules: Set<Module>, water: Boolean, layout: ScreenLayout?): List<TodaySection> =
        Layouts.visible(sections(modules, water), { it.name }, layout)

    /** A section's name in the Arrange sheet. */
    fun label(section: TodaySection, modules: Set<Module>): String =
        if (section == TodaySection.NEURO && Module.NEURO !in modules) "Energy" else section.label

    /**
     * The quick actions this app offers with [modules] on, in the default order: Water only while
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

    /** Under "More" until the person arranges them: all but the first [QuickAction.SHOWN]. */
    fun defaultUnderMore(available: List<QuickAction>): Set<String> = available.drop(QuickAction.SHOWN).map { it.name }.toSet()

    /** The quick actions on Today, and those under "More". */
    data class QuickRow(val shown: List<QuickAction>, val more: List<QuickAction>)

    /**
     * Splits [available] into what shows and what's under "More": in the person's order, those
     * they've hidden under More; until they've arranged them, the first [QuickAction.SHOWN] show.
     */
    fun quickRow(available: List<QuickAction>, layout: ScreenLayout?): QuickRow {
        val ordered = Layouts.arrange(available, { it.name }, layout)
        val under = layout?.hidden ?: defaultUnderMore(available)
        return QuickRow(ordered.filter { it.name !in under }, ordered.filter { it.name in under })
    }
}
