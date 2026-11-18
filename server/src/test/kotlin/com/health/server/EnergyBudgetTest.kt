package com.health.server

import com.health.api.EnergyDay
import com.health.api.EnergySpend
import com.health.api.FieldChoices
import com.health.api.Fields
import com.health.api.Module
import com.health.api.NowKind
import com.health.api.TimelineKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Autism & ADHD's energy budget is on until the person turns it off; then it's gone from everywhere. */
class EnergyBudgetTest {
    @Test
    fun `turning the energy budget off takes it out of Today, the feed and the timeline`() = serverTest { s ->
        runBlocking {
            val me = s.users.byUsername("admin")!!.id
            s.profiles.put(me, s.profiles.get(me).let { it.copy(modules = it.modules + Module.NEURO) })
            val zone = s.profiles.days(me)
            val today = zone.today(s.clock())
            s.neuro.saveEnergy(me, today.toString(), EnergyDay(today.toString(), startWith = 10, spends = listOf(EnergySpend("work", 3, s.clock())), socialBattery = 6))
            val profile = s.profiles.get(me)

            // On by default for anyone with Autism & ADHD.
            assertTrue(s.conditions.shows(me, Fields.NEURO_ENERGY))
            assertEquals(Module.NEURO, Fields.ALL.getValue(Fields.NEURO_ENERGY).first)
            assertEquals(7, s.neuro.today(me, profile, today).first.energyLeft)
            assertEquals(listOf(NowKind.ENERGY_TODAY), s.neuro.now(me, profile, s.clock()).map { it.kind })

            s.conditions.putChoices(me, FieldChoices(hide = setOf(Fields.NEURO_ENERGY)))
            assertFalse(s.conditions.shows(me, Fields.NEURO_ENERGY))
            val (neuroToday, insights) = s.neuro.today(me, profile, today)
            assertNull(neuroToday.energyLeft)
            // The social battery is its own thing, and stays.
            assertEquals(6, neuroToday.socialBattery)
            assertTrue(insights.none { it.second.title == "Running low on energy" })
            assertTrue(s.neuro.now(me, profile, s.clock()).isEmpty())
            val energy = s.neuro.timeline(me, profile, today, today).single { it.kind == TimelineKind.ENERGY }
            assertFalse(energy.detail!!, energy.detail!!.contains("left") || energy.detail!!.contains("Started"))
        }
    }
}
