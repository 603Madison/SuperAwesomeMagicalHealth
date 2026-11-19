package com.health.app.ui.settings

import com.health.api.DataDestination
import com.health.api.DataMap
import com.health.api.DataPath
import com.health.api.DataPathState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhereDataGoesTest {
    private fun way(path: DataPath, state: DataPathState, count: Int? = null, pausable: Boolean = true, setUp: String? = null, name: String = path.name) =
        DataDestination(path, name, "Someone", what = "Something", whenSent = "Sometimes", state = state, count = count, pausable = pausable, setUp = setUp)

    @Test
    fun `only what's set up and can be paused has a switch`() {
        assertTrue(WhereDataGoes.switchable(way(DataPath.MEALIE, DataPathState.ON)))
        assertTrue(WhereDataGoes.switchable(way(DataPath.MEALIE, DataPathState.PAUSED)))
        assertFalse(WhereDataGoes.switchable(way(DataPath.MEALIE, DataPathState.NOT_SET_UP)))
        assertFalse(WhereDataGoes.switchable(way(DataPath.FOOD_DATABASE, DataPathState.OFF_ON_SERVER)))
        // Signing in is how she gets in: never a switch.
        assertFalse(WhereDataGoes.switchable(way(DataPath.SIGN_IN, DataPathState.ON, pausable = false)))
    }

    @Test
    fun `how each stands, in a line`() {
        assertEquals("On · 2 links", WhereDataGoes.stateLine(way(DataPath.AI_CONNECTORS, DataPathState.ON, count = 2)))
        assertEquals("On · 1 webhook", WhereDataGoes.stateLine(way(DataPath.WEBHOOKS, DataPathState.ON, count = 1)))
        assertEquals("On", WhereDataGoes.stateLine(way(DataPath.WEATHER, DataPathState.ON)))
        assertEquals("On", WhereDataGoes.stateLine(way(DataPath.SHARES, DataPathState.ON, count = 0)))
        assertEquals("Paused: nothing goes · 3 browsers kept", WhereDataGoes.stateLine(way(DataPath.BROWSER_PUSH, DataPathState.PAUSED, count = 3)))
        assertEquals("Paused: nothing goes", WhereDataGoes.stateLine(way(DataPath.MEALIE, DataPathState.PAUSED)))
        assertEquals("Not set up", WhereDataGoes.stateLine(way(DataPath.SHARES, DataPathState.NOT_SET_UP, count = 0)))
        assertEquals("Turned off on this server", WhereDataGoes.stateLine(way(DataPath.FOOD_DATABASE, DataPathState.OFF_ON_SERVER)))
        assertEquals("Always on: it's how you sign in", WhereDataGoes.stateLine(way(DataPath.SIGN_IN, DataPathState.ON, pausable = false)))
        assertEquals("1 share", WhereDataGoes.countText(way(DataPath.SHARES, DataPathState.ON, count = 1)))
        assertEquals("2 keys", WhereDataGoes.countText(way(DataPath.HOME_ASSISTANT, DataPathState.ON, count = 2)))
    }

    @Test
    fun `each place it's set up is a page in the app, where there is one`() {
        assertEquals("settings/mealie", WhereDataGoes.link("settings.connections")?.route)
        assertEquals("settings/ai", WhereDataGoes.link("settings.ai")?.route)
        assertEquals("settings/integrations", WhereDataGoes.link("settings.integrations")?.route)
        assertEquals("settings/digest", WhereDataGoes.link("settings.digest")?.route)
        assertEquals("settings/sharing", WhereDataGoes.link("settings.sharing")?.route)
        assertEquals("symptoms", WhereDataGoes.link("settings.profile")?.route)
        // Connector links and browser notifications are made on the server's web page: no link.
        assertNull(WhereDataGoes.link("settings.connector"))
        assertNull(WhereDataGoes.link("settings.notifications"))
        assertNull(WhereDataGoes.link(null))
        assertNull(WhereDataGoes.link("settings.something-new"))
    }

    @Test
    fun `pausing shows at once, and only where there's a switch`() {
        val map = DataMap(
            listOf(way(DataPath.MEALIE, DataPathState.ON), way(DataPath.SHARES, DataPathState.NOT_SET_UP), way(DataPath.SIGN_IN, DataPathState.ON, pausable = false)),
            "Everything else stays.",
        )
        val paused = WhereDataGoes.pausing(map, DataPath.MEALIE, paused = true)
        assertEquals(DataPathState.PAUSED, paused.destinations[0].state)
        assertEquals(DataPathState.ON, WhereDataGoes.pausing(paused, DataPath.MEALIE, paused = false).destinations[0].state)
        assertEquals(map, WhereDataGoes.pausing(map, DataPath.SHARES, paused = true))
        assertEquals(map, WhereDataGoes.pausing(map, DataPath.SIGN_IN, paused = true))
    }

    @Test
    fun `what's on now, in a line`() {
        fun map(vararg ways: DataDestination) = DataMap(ways.toList(), "")
        // Nothing listed: nothing set up sends anything off the server.
        assertEquals("Nothing leaves this server right now.", WhereDataGoes.summary(map()))
        assertEquals("Nothing leaves this server right now", WhereDataGoes.youLine(map()))
        assertEquals("Nothing is on now: nothing about you leaves your server.", WhereDataGoes.summary(map(way(DataPath.MEALIE, DataPathState.PAUSED))))
        assertEquals("On now: Open Food Facts.", WhereDataGoes.summary(map(way(DataPath.FOOD_DATABASE, DataPathState.ON, name = "Open Food Facts"))))
        assertEquals(
            "On now: The assistant and the other one.",
            WhereDataGoes.summary(map(way(DataPath.AI_ASSISTANT, DataPathState.ON, name = "The assistant"), way(DataPath.SHARES, DataPathState.ON, name = "The other one"))),
        )
        assertEquals(
            "On now: Open Food Facts, Open-Meteo and Signing in.",
            WhereDataGoes.summary(
                map(
                    way(DataPath.FOOD_DATABASE, DataPathState.ON, name = "Open Food Facts"), way(DataPath.WEATHER, DataPathState.ON, name = "Open-Meteo"),
                    way(DataPath.MEALIE, DataPathState.PAUSED), way(DataPath.SIGN_IN, DataPathState.ON, pausable = false, name = "Signing in"),
                ),
            ),
        )
    }

    @Test
    fun `this phone's part says what's read and what's written back`() {
        assertEquals("Nothing is written back to it.", WhereDataGoes.healthConnectWrites(emptyList()))
        assertTrue(WhereDataGoes.healthConnectWrites(listOf("Weight", "Workouts")).startsWith("Written back to it, as you chose in Send to other apps: weight and workouts."))
        assertEquals("weight, water and drinks and workouts", WhereDataGoes.list(listOf("weight", "water and drinks", "workouts")))
        assertTrue(WhereDataGoes.healthConnectRead(true).startsWith("Read on this phone"))
        assertTrue(WhereDataGoes.gadgetbridge("Gadgetbridge.db").contains("(Gadgetbridge.db). Nothing is written to it."))
        assertEquals("Mealie is paused in Where your data goes", WhereDataGoes.paused("Mealie"))
    }
}
