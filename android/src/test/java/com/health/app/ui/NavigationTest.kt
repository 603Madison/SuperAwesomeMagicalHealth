package com.health.app.ui

import com.health.app.ui.settings.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's map: five fixed tabs, and which tab every page sits under. */
class NavigationTest {
    @Test
    fun `five tabs, always the same`() {
        assertEquals(listOf("today", "recent", "health", "trends", "more"), TABS.map { it.route })
        assertEquals(listOf("Today", "24 hours", "Health", "Trends", "More"), TABS.map { it.label })
    }

    @Test
    fun `each page lights its tab`() {
        // The tabs' own pages.
        listOf("today", "recent", "health", "trends", "more").forEach { assertEquals(it, tabOf(it)) }
        // More: records, devices, the assistant and Settings (and the pages within them).
        listOf(
            "alerts", "digest", "reports", "devices", "devices/compare", "devices/recent?device={device}", "devices/strap", "devices/monitor",
            "assistant", "assistant/audit", "settings/you", "settings/tracking", "settings/conditions", "settings/privacy", "settings/digest",
        ).forEach { assertEquals(it, "more", tabOf(it)) }
        // Health: every feature and its pages.
        listOf("food", "food/add/{date}/{meal}", "mind", "mind/journal", "cycle?log={log}", "cycle/settings", "continence", "supplies/{area}", "vitals", "measurements", "photos")
            .forEach { assertEquals(it, "health", tabOf(it)) }
        assertNull(tabOf(null))
    }

    @Test
    fun `links to a tab's own page switch tabs, the rest open on top`() {
        assertTrue(isTab("recent"))
        assertTrue(isTab("trends"))
        assertFalse(isTab("devices/recent?device=ring"))
        assertFalse(isTab("food"))
        // Settings was a tab: an older link lands on More.
        assertEquals("more", current("settings"))
        assertEquals("settings/you", current("settings/you"))
    }

    @Test
    fun `settings pages each have their own route`() {
        val routes = SettingsPage.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        routes.forEach { assertTrue(it, it.startsWith("settings/")) }
        // Names said the same everywhere.
        assertEquals(
            listOf("You & goals", "What you're tracking", "Reminders & sharing", "Connections & privacy", "Appearance", "Account"),
            SettingsPage.entries.map { it.title },
        )
    }
}
