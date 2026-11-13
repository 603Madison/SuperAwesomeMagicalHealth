package com.health.app.ui.settings

import com.health.api.FieldChoices
import com.health.api.FieldLayout
import com.health.api.Fields
import com.health.app.Container
import com.health.app.net.fields
import com.health.app.net.saveFieldChoices
import com.health.app.ui.conditions.ConditionsLogic

/**
 * The Water switch in Settings › Features. Water is a field ([Fields.NUTRITION_WATER]), shown to
 * everyone with Food on until they hide it; hidden, Today, Food and Settings leave water out, and
 * the phone stops writing it to Health Connect. Drinks still count caffeine and alcohol.
 */
object WaterSetting {
    /** Whether water shows, from the person's fields (shown until they've loaded). */
    fun isOn(layout: FieldLayout?): Boolean = layout?.effective?.contains(Fields.NUTRITION_WATER) ?: true

    /** The choices that show or hide water, keeping every other choice the person has made. */
    fun choices(layout: FieldLayout, on: Boolean): FieldChoices =
        ConditionsLogic.choose(FieldChoices(layout.show, layout.hide), Fields.NUTRITION_WATER, on, Fields.DEFAULTS)

    /** Saves it to the person's own fields (read fresh, so nothing else changes), then reloads what screens show. */
    suspend fun save(c: Container, on: Boolean) {
        c.api.saveFieldChoices(choices(c.api.fields(), on))
        c.refreshFields()
        c.dataChanged()
    }
}
