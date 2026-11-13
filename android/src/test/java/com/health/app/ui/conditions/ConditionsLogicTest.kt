package com.health.app.ui.conditions

import com.health.api.ConditionDefinition
import com.health.api.ConditionKind
import com.health.api.FieldChoices
import com.health.api.FieldLayout
import com.health.api.Fields
import com.health.api.Module
import com.health.api.MyCondition
import com.health.api.MyConditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionsLogicTest {
    private val oab = ConditionDefinition(
        "overactive-bladder", "Overactive bladder", ConditionKind.CONDITION, "Bladder and bowel", "Sudden urges.",
        setOf(Module.CONTINENCE), setOf(Fields.CONTINENCE_BLADDER_TRAINING, Fields.CONTINENCE_URGENCY), aliases = listOf("OAB", "urge incontinence"),
    )
    private val crohns = ConditionDefinition("crohns", "Crohn’s disease", ConditionKind.CONDITION, "Long-term conditions", "Inflammation.", setOf(Module.SYMPTOMS, Module.CONTINENCE))
    private val endo = ConditionDefinition(
        "endometriosis", "Endometriosis", ConditionKind.CONDITION, "Periods and hormones", "Pain.",
        setOf(Module.CYCLE), setOf(Fields.CYCLE_PAIN_AREAS), aliases = listOf("endo"),
    )
    private val sleep = ConditionDefinition("better-sleep", "Sleep better", ConditionKind.GOAL, "Goals", "Rest.", setOf(Module.SLEEP))
    private val catalog = listOf(oab, crohns, endo, sleep)

    @Test
    fun `search finds names and other names, without minding case, accents or punctuation`() {
        assertEquals(listOf(oab), ConditionsLogic.search(catalog, "oab"))
        assertEquals(listOf(oab), ConditionsLogic.search(catalog, "Urge incont"))
        assertEquals(listOf(crohns), ConditionsLogic.search(catalog, "crohns"))
        assertEquals(listOf(endo), ConditionsLogic.search(catalog, "ENDO"))
        assertEquals(catalog, ConditionsLogic.search(catalog, "  "))
        assertTrue(ConditionsLogic.search(catalog, "nothing like this").isEmpty())
        // Every word must match somewhere.
        assertEquals(listOf(oab), ConditionsLogic.search(catalog, "bladder urge"))
    }

    @Test
    fun `groups keep the catalog's order`() {
        assertEquals(listOf("Bladder and bowel", "Long-term conditions", "Periods and hormones", "Goals"), ConditionsLogic.grouped(catalog).map { it.first })
    }

    @Test
    fun `picking and unpicking, and changing a pick`() {
        val one = ConditionsLogic.toggle(MyConditions(), oab)
        assertEquals(listOf(MyCondition("overactive-bladder", "Overactive bladder", ConditionKind.CONDITION)), one.conditions)
        assertTrue(ConditionsLogic.toggle(one, oab).conditions.isEmpty())
        val withStatus = ConditionsLogic.put(one, one.conditions.single().copy(since = "2024-05", notes = "Worse in winter"))
        assertEquals("2024-05", withStatus.conditions.single().since)
        assertTrue(ConditionsLogic.remove(withStatus, "overactive-bladder").conditions.isEmpty())
    }

    @Test
    fun `own conditions get unique ids`() {
        assertEquals("custom:long-covid", ConditionsLogic.customId("Long Covid!", emptySet()))
        assertEquals("custom:long-covid-2", ConditionsLogic.customId("long covid", setOf("custom:long-covid")))
        assertEquals("custom:mine", ConditionsLogic.customId("???", emptySet()))
    }

    @Test
    fun `since is a year and month, or a date, or nothing`() {
        assertTrue(ConditionsLogic.validSince(""))
        assertTrue(ConditionsLogic.validSince("2024-05"))
        assertTrue(ConditionsLogic.validSince("2024-05-31"))
        assertFalse(ConditionsLogic.validSince("2024-13"))
        assertFalse(ConditionsLogic.validSince("May 2024"))
        assertFalse(ConditionsLogic.validSince("2024-5"))
    }

    @Test
    fun `saving says which features it turns on`() {
        val mine = MyConditions(listOf(MyCondition("crohns", "Crohn’s disease"), MyCondition("custom:x", "Mine", modules = setOf(Module.HABITS))))
        assertEquals(listOf(Module.CONTINENCE, Module.HABITS, Module.SYMPTOMS), ConditionsLogic.turnsOn(mine, catalog, Module.DEFAULT))
        assertEquals(listOf(Module.HABITS), ConditionsLogic.turnsOn(mine, catalog, Module.DEFAULT + Module.CONTINENCE + Module.SYMPTOMS))
        assertEquals("Bladder & bowel, Cycle and Habits", ConditionsLogic.sayList(listOf("Bladder & bowel", "Cycle", "Habits")))
    }

    @Test
    fun `every field is on the fields page, under its feature`() {
        val groups = ConditionsLogic.fieldGroups()
        assertEquals(Fields.ALL.size, groups.sumOf { it.second.size })
        assertEquals(Module.CONTINENCE, groups.first().first)
        assertTrue(groups.first { it.first == Module.CYCLE }.second.any { it.first == Fields.CYCLE_PRODUCTS })
    }

    @Test
    fun `a field's switch keeps only what differs from what conditions bring`() {
        val brought = ConditionsLogic.brought(MyConditions(listOf(MyCondition(oab.id, oab.name))), catalog)
        assertTrue(Fields.CONTINENCE_BLADDER_TRAINING in brought)
        assertTrue(Fields.CONTINENCE_URGENCY in brought)
        // Hiding a field a condition brings records it as hidden; showing it again just takes that away.
        val hidden = ConditionsLogic.choose(FieldChoices(), Fields.CONTINENCE_BLADDER_TRAINING, false, brought)
        assertEquals(FieldChoices(hide = setOf(Fields.CONTINENCE_BLADDER_TRAINING)), hidden)
        assertEquals(FieldChoices(), ConditionsLogic.choose(hidden, Fields.CONTINENCE_BLADDER_TRAINING, true, brought))
        // A field nothing brings is shown by the person's own choice, and turning it off forgets it.
        val shown = ConditionsLogic.choose(FieldChoices(), Fields.CONTINENCE_PAD_WEIGHT, true, brought)
        assertEquals(FieldChoices(show = setOf(Fields.CONTINENCE_PAD_WEIGHT)), shown)
        assertEquals(FieldChoices(), ConditionsLogic.choose(shown, Fields.CONTINENCE_PAD_WEIGHT, false, brought))
    }

    @Test
    fun `each field says why it shows`() {
        val layout = FieldLayout(
            effective = setOf(Fields.CONTINENCE_URGENCY, Fields.CONTINENCE_BLADDER_TRAINING, Fields.CONTINENCE_PAD_WEIGHT),
            show = setOf(Fields.CONTINENCE_PAD_WEIGHT), hide = setOf(Fields.CONTINENCE_BRISTOL),
            because = mapOf(Fields.CONTINENCE_URGENCY to "default", Fields.CONTINENCE_BLADDER_TRAINING to "Overactive bladder", Fields.CONTINENCE_PAD_WEIGHT to "you"),
        )
        assertEquals("On for everyone", ConditionsLogic.reason(layout, Fields.CONTINENCE_URGENCY))
        assertEquals("Because of Overactive bladder", ConditionsLogic.reason(layout, Fields.CONTINENCE_BLADDER_TRAINING))
        assertEquals("You turned this on", ConditionsLogic.reason(layout, Fields.CONTINENCE_PAD_WEIGHT))
        assertEquals("You turned this off", ConditionsLogic.reason(layout, Fields.CONTINENCE_BRISTOL))
        assertEquals("Off", ConditionsLogic.reason(layout, Fields.CYCLE_SEX))
        assertEquals("On for everyone", ConditionsLogic.reason(null, Fields.CYCLE_FLOW))
    }
}
