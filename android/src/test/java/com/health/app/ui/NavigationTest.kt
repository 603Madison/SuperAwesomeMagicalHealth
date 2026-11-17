package com.health.app.ui

import com.health.app.ui.body.BodySpan
import com.health.app.ui.settings.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's map: four fixed tabs and the + between them, and which tab every page sits under. */
class NavigationTest {
    @Test
    fun `four tabs, always the same, the + between Body and Care`() {
        assertEquals(listOf("today", "body", "care", "you"), TABS.map { it.route })
        assertEquals(listOf("Today", "Body", "Care", "You"), TABS.map { it.label })
    }

    @Test
    fun `each page lights its tab`() {
        // The tabs' own pages.
        listOf("today", "body", "care", "you").forEach { assertEquals(it, tabOf(it)) }
        // Body: Health alerts and comparing devices.
        listOf("alerts", "devices/compare").forEach { assertEquals(it, "body", tabOf(it)) }
        // You: devices, your records, the assistant and Settings (and the pages within them).
        listOf(
            "devices", "devices/recent?device={device}", "devices/strap", "devices/monitor", "digest", "reports",
            "assistant", "assistant/audit", "settings/you", "settings/tracking", "settings/conditions", "settings/privacy", "settings/digest",
        ).forEach { assertEquals(it, "you", tabOf(it)) }
        // Care: every feature and its pages.
        listOf("food", "food/add/{date}/{meal}", "mind", "mind/journal", "cycle?log={log}", "cycle/settings", "continence", "supplies/{area}", "vitals", "measurements", "photos", "hormones")
            .forEach { assertEquals(it, "care", tabOf(it)) }
        assertNull(tabOf(null))
    }

    @Test
    fun `links to a tab's own page switch tabs, the rest open on top`() {
        assertTrue(isTab("body"))
        assertTrue(isTab("you"))
        assertFalse(isTab("devices/recent?device=ring"))
        assertFalse(isTab("food"))
    }

    @Test
    fun `older links land where their pages are now`() {
        // The last 24 hours and Trends are Body, at the matching span.
        assertEquals("body", current("recent"))
        assertEquals(BodySpan.DAY, spanOf("recent"))
        assertEquals("body", current("trends"))
        assertEquals(BodySpan.WEEK, spanOf("trends"))
        assertNull(spanOf("body"))
        // Health is Care; More and Settings are You; Appearance and Account are one page.
        assertEquals("care", current("health"))
        assertEquals("you", current("more"))
        assertEquals("you", current("settings"))
        assertEquals(SettingsPage.LOOK.route, current("settings/account"))
        assertEquals("settings/you", current("settings/you"))
        assertEquals("devices/recent?device=ring", current("devices/recent?device=ring"))
    }

    @Test
    fun `settings are five pages, each with its own route and one name`() {
        val routes = SettingsPage.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        routes.forEach { assertTrue(it, it.startsWith("settings/")) }
        assertEquals(
            listOf("What you track", "Reminders, alerts and sharing", "Profile and goals", "Connections", "Look and account"),
            SettingsPage.entries.map { it.title },
        )
    }
}
