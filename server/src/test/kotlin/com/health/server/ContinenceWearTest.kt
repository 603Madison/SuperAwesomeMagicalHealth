package com.health.server

import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceSettings
import com.health.api.Fullness
import com.health.api.Module
import com.health.api.Profile
import com.health.api.ReminderPrefs
import com.health.api.Supply
import com.health.api.SupplyKind
import com.health.api.WebhookEvent
import com.health.server.modules.ContinenceHooks
import com.health.server.modules.ContinenceService
import com.health.server.modules.Wear
import com.health.server.platform.Scheduler
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** What's being worn, a change as one step: stock, wear times, the wearing endpoint, product patterns, the reminder, and who sees it. */
class ContinenceWearTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 5, 20)
    private var now = at(today, 15)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun services(modules: Set<Module> = Module.DEFAULT + Module.CONTINENCE) = testServices(clock = { now }).also { s ->
        runBlocking {
            s.bootstrap()
            s.profiles.put(s.users.byUsername("admin")!!.id, Profile(timezone = zone.id, modules = modules))
        }
    }

    private fun Services.admin() = runBlocking { users.byUsername("admin")!!.id }
    private suspend fun Services.onHand(me: String, s: Supply) = supplies.get(me, s.id!!)!!.onHand

    private fun change(at: Long, off: Supply?, on: Supply?, fullness: Fullness? = null, nothingOn: Boolean = false) =
        ContinenceEntry(at, ContinenceKind.PAD_CHANGE, supplyId = off?.id, putOnSupplyId = on?.id, fullness = fullness, nothingOn = nothingOn)

    private suspend fun refused(block: suspend () -> Unit) {
        try {
            block()
            error("That was taken")
        } catch (e: ApiError) {
            assertEquals(HttpStatusCode.BadRequest, e.status)
        }
    }

    // ---- Stock ----

    @Test
    fun `a change counts what goes on, not what comes off, and older changes count as they always did`() = runBlocking {
        val s = services()
        val me = s.admin()
        val c = s.modules.continence
        val night = s.supplies.save(me, Supply("Night briefs", SupplyKind.BRIEF, onHand = 20))
        val day = s.supplies.save(me, Supply("Day pants", SupplyKind.PULL_UP, onHand = 30))

        // Putting night briefs on takes one off their stock.
        c.save(me, change(at(today.minusDays(1), 23, 20), null, night))
        assertEquals(19, s.onHand(me, night))
        // The morning change: night briefs off (counted when they went on), day pants on.
        val morning = c.save(me, change(at(today, 6, 30), night, day, Fullness.FULL))
        assertEquals(19, s.onHand(me, night))
        assertEquals(29, s.onHand(me, day))
        // Edited to another night brief: the day pants come back, and a night brief goes.
        c.save(me, morning.copy(putOnSupplyId = night.id), morning.id)
        assertEquals(18, s.onHand(me, night))
        assertEquals(30, s.onHand(me, day))
        // Two at once.
        c.save(me, morning.copy(putOnSupplyId = day.id, count = 2), morning.id)
        assertEquals(19, s.onHand(me, night))
        assertEquals(28, s.onHand(me, day))
        // Taken off with nothing on: nothing more is used.
        val off = c.save(me, change(at(today, 9), day, null, nothingOn = true))
        assertTrue(off.nothingOn)
        assertEquals(28, s.onHand(me, day))
        // Deleting a change gives back what it put on.
        c.delete(me, morning.id!!)
        assertEquals(30, s.onHand(me, day))
        c.delete(me, off.id!!)
        assertEquals(30, s.onHand(me, day))

        // A change from before what went on was recorded uses its product, as it did, and gives it back the same way.
        val old = c.save(me, ContinenceEntry(at(today, 10), ContinenceKind.PAD_CHANGE, supplyId = day.id, count = 2))
        assertNull(old.putOnSupplyId)
        assertEquals(28, s.onHand(me, day))
        c.save(me, old.copy(count = 1), old.id)
        assertEquals(29, s.onHand(me, day))
        c.delete(me, old.id!!)
        assertEquals(30, s.onHand(me, day))
        // A leak changed afterwards still takes one, as before.
        val leak = c.save(me, ContinenceEntry(at(today, 11), ContinenceKind.LEAK, supplyId = day.id, count = 1, putOnSupplyId = night.id, nothingOn = true))
        assertEquals(29, s.onHand(me, day))
        assertNull("only a change puts something on", leak.putOnSupplyId)
        assertFalse(leak.nothingOn)

        // Something on and nothing on at once, or a product that isn't there, is refused.
        refused { c.save(me, change(now, day, day, nothingOn = true)) }
        refused { c.save(me, ContinenceEntry(now, ContinenceKind.PAD_CHANGE, putOnSupplyId = "nope")) }
        assertEquals(19, s.onHand(me, night))
        assertEquals(29, s.onHand(me, day))
    }

    @Test
    fun `products a day count what went on`() {
        val pads = Supply("Pads", SupplyKind.PAD, id = "p")
        assertEquals(1, ContinenceService.productsUsed(change(0, null, pads)))
        assertEquals(1, ContinenceService.productsUsed(change(0, pads, pads)))
        assertEquals(0, ContinenceService.productsUsed(change(0, pads, null, nothingOn = true)))
        assertEquals(3, ContinenceService.productsUsed(ContinenceEntry(0, ContinenceKind.PAD_CHANGE, supplyId = "p", count = 3)))
        assertEquals(1, ContinenceService.productsUsed(ContinenceEntry(0, ContinenceKind.PAD_CHANGE)))
        assertEquals(0, ContinenceService.productsUsed(ContinenceEntry(0, ContinenceKind.LEAK, supplyId = "p")))
    }

    // ---- How long each was worn ----

    @Test
    fun `each change knows how long what came off was worn`() = runBlocking {
        val s = services()
        val me = s.admin()
        val c = s.modules.continence
        val night = s.supplies.save(me, Supply("Night briefs", SupplyKind.BRIEF, onHand = 20))
        val day = s.supplies.save(me, Supply("Day pants", SupplyKind.PULL_UP, onHand = 30))
        c.save(me, change(at(today.minusDays(1), 23, 20), null, night))
        val morning = c.save(me, change(at(today, 6, 30), night, day, Fullness.FULL))
        assertEquals("night briefs from 11:20 pm", 430, morning.wornMinutes)
        c.save(me, ContinenceEntry(at(today, 8), ContinenceKind.URINATION))
        c.save(me, change(at(today, 11), day, day))
        c.save(me, change(at(today, 14), day, null, nothingOn = true))
        c.save(me, change(at(today, 14, 30), null, day))

        val changes = c.list(me, 1).filter { it.kind == ContinenceKind.PAD_CHANGE }.sortedBy { it.at }
        assertEquals(listOf(430, 270, 180, null), changes.map { it.wornMinutes })
        // Yesterday's put-on is there to pair with, but only today was asked for.
        assertTrue(c.list(me, 1).all { it.date == today.toString() })

        // Pure: older changes pair too (each put on a fresh one of what it took off); a different product, or a gap of over a day, doesn't.
        val x = Supply("X", SupplyKind.PAD, id = "x")
        val y = Supply("Y", SupplyKind.PAD, id = "y")
        val old = { h: Int -> ContinenceEntry(at(today, h), ContinenceKind.PAD_CHANGE, supplyId = "x") }
        assertEquals(listOf(null, 240), Wear.withWorn(listOf(old(8), old(12))).map { it.wornMinutes })
        assertEquals(listOf(null, null), Wear.withWorn(listOf(change(at(today, 8), null, x), change(at(today, 12), y, x))).map { it.wornMinutes })
        assertNull(Wear.withWorn(listOf(change(at(today.minusDays(2), 8), null, x), change(at(today, 12), x, x)))[1].wornMinutes)
        // In any order.
        assertEquals(listOf(240, null), Wear.withWorn(listOf(change(at(today, 12), x, y), change(at(today, 8), null, x))).map { it.wornMinutes })
    }

    // ---- What's on now ----

    @Test
    fun `what is being worn, whether nothing is known, something is on, or nothing is on`() = serverTest(services()) { s ->
        val me = s.admin()
        val c = s.modules.continence
        val browser = adminBrowser()
        suspend fun wearing() = browser.get("/api/continence/wearing").obj()
        fun JsonObject.isNull(key: String) = this[key] == null || this[key] is JsonNull

        // Nothing known yet.
        assertTrue(wearing().isNull("since"))
        assertTrue(wearing().isNull("supplyId"))

        val night = s.supplies.save(me, Supply("Night briefs", SupplyKind.BRIEF, onHand = 40))
        val day = s.supplies.save(me, Supply("Day pants", SupplyKind.PULL_UP, onHand = 40))
        // Four nights of night briefs, seven hours each, then day pants: the usual for night briefs is seven hours.
        for (d in 4L downTo 1L) {
            c.save(me, change(at(today.minusDays(d + 1), 23), if (d == 4L) null else day, night))
            c.save(me, change(at(today.minusDays(d), 6), night, day, Fullness.WET))
        }
        c.save(me, change(at(today.minusDays(1), 23), day, night))
        c.save(me, change(at(today, 6), night, day))
        c.putSettings(me, ContinenceSettings(changeReminderHours = 6))
        // Night briefs on at 8 this morning; it's 3 in the afternoon.
        c.save(me, change(at(today, 8), day, night, Fullness.DAMP))
        val on = wearing()
        assertEquals(night.id, on.str("supplyId"))
        assertEquals("Night briefs", on.str("name"))
        assertEquals("BRIEF", on.str("kind"))
        assertEquals(at(today, 8), on["since"]!!.jsonPrimitive.long)
        assertEquals(420, on["minutes"]!!.jsonPrimitive.int)
        assertEquals(420, on["typicalMinutes"]!!.jsonPrimitive.int)
        assertEquals(at(today, 14), on["changeDueAt"]!!.jsonPrimitive.long)
        assertFalse(on["nothingOn"]?.jsonPrimitive?.boolean ?: false)

        // Taken off, nothing on for now.
        c.save(me, change(at(today, 14, 50), night, null, nothingOn = true))
        val off = wearing()
        assertTrue(off.isNull("supplyId"))
        assertTrue(off["nothingOn"]!!.jsonPrimitive.boolean)
        assertEquals(at(today, 14, 50), off["since"]!!.jsonPrimitive.long)
        assertTrue(off.isNull("changeDueAt"))

        // Today's bladder part says so too.
        val dayJson = browser.get("/api/day/$today").obj()
        val w = dayJson["modules"]!!.jsonObject["continence"]!!.jsonObject["wearing"]!!.jsonObject
        assertTrue(w["nothingOn"]!!.jsonPrimitive.boolean)

        // Over a day since the last change: nothing known.
        now = at(today.plusDays(1), 16)
        assertTrue(wearing().isNull("since"))
        now = at(today, 15)
    }

    private fun leak(at: Long, product: Supply? = null, fullness: Fullness? = null, through: Boolean = false, id: String? = null) =
        ContinenceEntry(at, ContinenceKind.LEAK, supplyId = product?.id, fullness = fullness, leakedThrough = through, id = id)

    @Test
    fun `accidents belong to what was on when they happened`() {
        val a = Supply("Night briefs", SupplyKind.BRIEF, id = "a")
        val b = Supply("Day pants", SupplyKind.PULL_UP, id = "b")
        val list = listOf(
            // Logged just before the brief it names went on: still that brief's.
            leak(at(today, 7, 45), a, Fullness.DAMP, id = "l1"),
            change(at(today, 7, 48), null, a).copy(id = "on-a"),
            leak(at(today, 9), null, Fullness.WET, id = "l2"),
            // Naming another product: not this wear's.
            leak(at(today, 9, 30), b, id = "l3"),
            // At the change's moment: what came off.
            leak(at(today, 10), null, Fullness.FULL, id = "l4"),
            change(at(today, 10), a, b, Fullness.SOAKED).copy(id = "a-b"),
            leak(at(today, 12), null, Fullness.DAMP, id = "l5"),
            leak(at(today, 13), b, Fullness.WET, through = true, id = "l6"),
            ContinenceEntry(at(today, 12, 30), ContinenceKind.URINATION, id = "t1"),
        )
        val spans = Wear.spans(list, at(today, 15))
        assertEquals(listOf("a", "b"), spans.map { it.supplyId })
        val (first, second) = spans
        assertEquals(listOf("l1", "l2", "l4"), first.accidents.map { it.id })
        assertEquals(Fullness.SOAKED, first.fullness)
        assertEquals(132, first.minutes(at(today, 15)))
        assertFalse(first.current)
        assertEquals(listOf("l5", "l6"), second.accidents.map { it.id })
        assertTrue(second.current)
        // While it's on, how full it is comes from the latest accident, and leaking through shows.
        assertEquals(Fullness.WET, second.fullness)
        assertTrue(second.leakedThrough)
        assertEquals(300, second.minutes(at(today, 15)))
    }

    @Test
    fun `a wear ends where the diary moves on, even without its removal`() {
        val a = Supply("Night briefs", SupplyKind.BRIEF, id = "a")
        val b = Supply("Day pants", SupplyKind.PULL_UP, id = "b")
        val list = listOf(
            change(at(today, 6), null, a).copy(id = "on-a"),
            leak(at(today, 7), id = "l1"),
            // Put on without taking anything off: the brief's wear ends here, unrecorded.
            change(at(today, 8), null, b).copy(id = "on-b"),
            leak(at(today, 9), id = "l2"),
            // Took off something that never went on in the diary: it gathers since the change before.
            change(at(today, 11), a, null, Fullness.WET, nothingOn = true).copy(id = "off-a"),
            leak(at(today, 12), id = "l3"),
        )
        val spans = Wear.spans(list, at(today, 15))
        assertEquals(3, spans.size)
        assertEquals(listOf("l1"), spans[0].accidents.map { it.id })
        assertNull(spans[0].off)
        assertEquals(at(today, 8), spans[0].end)
        // Pants: on at 8, never taken off in the diary, and a different product came off at 11.
        assertEquals("b", spans[1].supplyId)
        assertEquals(at(today, 11), spans[1].end)
        assertEquals("a", spans[2].supplyId)
        assertNull(spans[2].start)
        assertEquals(listOf("l2"), (spans[1].accidents + spans[2].accidents).map { it.id })
        // Nothing on: the last accident is on its own.
        assertTrue(spans.none { s -> s.accidents.any { it.id == "l3" } })
    }

    @Test
    fun `the diary groups each product with its accidents, and what's on has its state`() = serverTest(services()) { s ->
        val me = s.admin()
        val c = s.modules.continence
        val browser = adminBrowser()
        val night = s.supplies.save(me, Supply("Night briefs", SupplyKind.BRIEF, onHand = 20))
        val day = s.supplies.save(me, Supply("Day pants", SupplyKind.PULL_UP, onHand = 20))
        // Night briefs on last night, a leak before midnight and one in the night, changed this morning.
        c.save(me, change(at(today.minusDays(1), 22), null, night))
        c.save(me, leak(at(today.minusDays(1), 23, 30), night, Fullness.DAMP))
        c.save(me, leak(at(today, 3), null, Fullness.WET))
        c.save(me, change(at(today, 7), night, day, Fullness.FULL))
        c.save(me, ContinenceEntry(at(today, 9), ContinenceKind.URINATION, volumeMl = 300))
        c.save(me, leak(at(today, 11), day, Fullness.DAMP))
        c.save(me, leak(at(today, 13), day, Fullness.WET))

        val diary = browser.get("/api/continence/diary").obj()
        assertEquals(today.toString(), diary.str("date"))
        val items = diary["items"]!!.jsonArray.map { it.jsonObject }
        // Newest first: what's on now, the toilet trip, the night's briefs. Nothing else on its own.
        assertEquals(3, items.size)
        val on = items[0]["wear"]!!.jsonObject
        assertTrue(on["current"]!!.jsonPrimitive.boolean)
        assertEquals("Day pants", on.str("name"))
        assertEquals("WET", on.str("fullness"))
        assertEquals(2, on["accidents"]!!.jsonArray.size)
        assertEquals("URINATION", items[1]["entry"]!!.jsonObject.str("kind"))
        val off = items[2]["wear"]!!.jsonObject
        assertEquals("Night briefs", off.str("name"))
        assertEquals("FULL", off.str("fullness"))
        assertEquals("Day pants", off.str("nextName"))
        assertEquals(540, off["minutes"]!!.jsonPrimitive.int)
        // Both of the night's accidents, the one from before midnight too.
        assertEquals(listOf(at(today.minusDays(1), 23, 30), at(today, 3)), off["accidents"]!!.jsonArray.map { it.jsonObject["at"]!!.jsonPrimitive.long })

        // Yesterday: the night's briefs went on then, so they show there too.
        val yesterday = browser.get("/api/continence/diary?date=${today.minusDays(1)}").obj()["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Night briefs"), yesterday.map { it["wear"]!!.jsonObject.str("name") })

        // What's on now carries its state for the Wearing now card.
        val wearing = browser.get("/api/continence/wearing").obj()["wear"]!!.jsonObject
        assertEquals("WET", wearing.str("fullness"))
        assertEquals(2, wearing["accidents"]!!.jsonArray.size)
        assertEquals(at(today, 7), wearing["on"]!!.jsonObject["at"]!!.jsonPrimitive.long)
    }

    @Test
    fun `Today shows what's on even before anything's logged today`() = runBlocking {
        val s = services()
        val me = s.admin()
        val night = s.supplies.save(me, Supply("Night briefs", SupplyKind.BRIEF, onHand = 10))
        s.modules.continence.save(me, change(at(today.minusDays(1), 22, 30), null, night))
        now = at(today, 6)
        val d = s.days.summary(me, today.toString()).modules.continence!!
        assertEquals(0, d.voids)
        assertEquals("Night briefs", d.wearing!!.name)
        assertEquals(450, d.wearing!!.minutes)
        // Another day has no "now".
        assertNull(s.days.summary(me, today.minusDays(1).toString()).modules.continence!!.wearing)
        now = at(today, 15)
    }

    // ---- How each product goes ----

    @Test
    fun `each product has a pattern of how long it is worn, how full, leaks through, and nights`() = serverTest(services()) { s ->
        val me = s.admin()
        val c = s.modules.continence
        val night = s.supplies.save(me, Supply("Night briefs", SupplyKind.BRIEF, onHand = 100))
        val day = s.supplies.save(me, Supply("Day pants", SupplyKind.PULL_UP, onHand = 100))
        // Twelve nights: night briefs 10 pm to 5 am, day pants 5 am to 10 pm.
        for (i in 12L downTo 1L) {
            c.save(me, change(at(today.minusDays(i + 1), 22), if (i == 12L) null else day, night, Fullness.WET))
            val full = when (i) { 1L -> Fullness.LEAKED; 2L, 3L, 4L -> Fullness.SOAKED; else -> Fullness.FULL }
            c.save(me, change(at(today.minusDays(i), 5), night, day, full))
        }
        val stats = c.products(me, 90)
        assertEquals(listOf("Night briefs", "Day pants"), stats.map { it.name })
        val n = stats.first()
        assertEquals(12, n.changes)
        assertEquals(420, n.avgWornMinutes)
        assertEquals(Fullness.FULL, n.typicalFullness)
        assertEquals(1, n.leakedThrough)
        assertEquals(8, n.leakedThroughPct)
        assertEquals(1.0, n.nightShare, 0.001)
        assertEquals(SupplyKind.BRIEF, n.kind)
        assertEquals("Night briefs: usually 7 h, often full, leaked through 1 in 12, mostly at night.", Wear.describe(n))
        val d = stats[1]
        assertEquals(11, d.changes)
        assertEquals(17 * 60, d.avgWornMinutes)
        assertEquals(0.0, d.nightShare, 0.001)
        assertEquals("Day pants: usually 17 h, often wet, never leaked through.", Wear.describe(d))

        // The same from the API, and only for the days asked.
        val api = adminBrowser().get("/api/continence/products?days=90").json().jsonArray
        assertEquals(12, api[0].jsonObject["changes"]!!.jsonPrimitive.int)
        assertEquals(2, c.products(me, 3).first { it.supplyId == night.id }.changes)

        // The report and export carry them, with what went on and how long it was worn.
        val profile = s.profiles.get(me)
        val hooks = s.hooks.filterIsInstance<ContinenceHooks>().single()
        val report = hooks.report(me, profile, today.minusDays(30), today)!!
        assertTrue(report.summary.contains(Wear.describe(n)))
        assertEquals(listOf("Night briefs", "12", "7 h", "Full", "1 (8%)", "100%"), report.tables.first { it.title == "Products" }.rows.first())
        val export = hooks.export(me, profile, today.minusDays(30), today)
        val details = export["bladder-bowel-details.csv"]!!
        val header = details.first()
        assertTrue(header.containsAll(listOf("put_on", "worn_minutes")))
        val last = details.last()
        assertEquals(day.id, last[header.indexOf("put_on")])
        assertEquals("420", last[header.indexOf("worn_minutes")])
        assertEquals(listOf(night.id, "Night briefs"), export["bladder-bowel-products.csv"]!![1].take(2))
    }

    // ---- The reminder ----

    @Test
    fun `a change reminder comes when due, says only "Time for a check", and waits out the night`() = runBlocking {
        val s = services()
        val me = s.admin()
        val c = s.modules.continence
        val hooks = s.hooks.filterIsInstance<ContinenceHooks>().single()
        val profile = s.profiles.get(me)
        val day = s.supplies.save(me, Supply("Day pants", SupplyKind.PULL_UP, onHand = 30))
        val night = s.supplies.save(me, Supply("Night briefs", SupplyKind.BRIEF, onHand = 30))
        suspend fun dueIn(t: Long) = hooks.reminders(me, profile, t - Scheduler.WINDOW_BEFORE, t + Scheduler.WINDOW_AFTER).filter { it.event == WebhookEvent.CONTINENCE_CHANGE }

        c.save(me, change(at(today, 8), null, day))
        // Without hours chosen, no reminder.
        now = at(today, 14)
        assertTrue(dueIn(now).isEmpty())

        c.putSettings(me, ContinenceSettings(changeReminderHours = 6))
        now = at(today, 13)
        assertTrue("not before it's due", dueIn(now).isEmpty())
        now = at(today, 14)
        val r = dueIn(now).single()
        assertEquals(at(today, 14), r.at)
        assertEquals("continence:change:${at(today, 8)}", r.key)
        assertEquals(Module.CONTINENCE, r.event.module)
        // Never the product's name on a screen, whether or not names are shown.
        listOf(r.title, r.body, r.privateTitle, r.privateBody).forEach { assertFalse(it, it.contains("Day pants") || it.contains("pants", ignoreCase = true)) }
        for (names in listOf(false, true)) {
            val shown = Scheduler.message(r, ReminderPrefs(showNames = names))
            assertEquals("Time for a check", shown.title)
            assertFalse(shown.body.contains("pants", ignoreCase = true))
        }
        assertEquals("About 6 hours since your last change.", r.body)
        // A webhook that includes details may have the name.
        assertEquals("Day pants", r.detailedData["product"]!!.jsonPrimitive.content)
        assertNull(r.data["product"])
        // Through the scheduler's round, with the feature on.
        assertTrue(s.platform.scheduler.collect(me, profile, now - 60_000, now + 60_000).any { it.key == r.key })

        // Nothing on: nothing to remind about.
        c.save(me, change(at(today, 14, 10), day, null, nothingOn = true))
        now = at(today, 20, 10)
        assertTrue(dueIn(now).isEmpty())

        // Night briefs on at 10 pm with a four-hour reminder: 2 am is in the night (11 pm to 6 am), so it waits until 6.
        c.save(me, change(at(today, 22), null, night))
        c.putSettings(me, ContinenceSettings(changeReminderHours = 4))
        now = at(today.plusDays(1), 2)
        assertTrue(dueIn(now).isEmpty())
        assertEquals(at(today.plusDays(1), 6), c.wearing(me).changeDueAt)
        now = at(today.plusDays(1), 6)
        assertEquals(at(today.plusDays(1), 6), dueIn(now).single().at)
        // The person's own night hours, and reminders at night if they'd like them.
        c.putSettings(me, ContinenceSettings(changeReminderHours = 4, nightFrom = "21:30", nightTo = "07:15"))
        assertEquals(at(today.plusDays(1), 7, 15), c.wearing(me).changeDueAt)
        c.putSettings(me, ContinenceSettings(changeReminderHours = 4, changeReminderAtNight = true))
        now = at(today.plusDays(1), 2)
        assertEquals(at(today.plusDays(1), 2), dueIn(now).single().at)

        // Hours are 1 to 24.
        refused { c.putSettings(me, ContinenceSettings(changeReminderHours = 0)) }
        refused { c.putSettings(me, ContinenceSettings(changeReminderHours = 30)) }
        now = at(today, 15)
    }

    @Test
    fun `the night's end is worked out across midnight`() {
        val days = com.health.server.util.Days(zone)
        val nightFrom = 23 * 60
        val nightTo = 6 * 60
        // Due at 11:30 pm: moved to 6 the next morning.
        assertEquals(at(today.plusDays(1), 6), Wear.dueAt(at(today, 17, 30), 6, days, nightFrom, nightTo, atNight = false))
        // Due at 1 am: 6 that morning.
        assertEquals(at(today.plusDays(1), 6), Wear.dueAt(at(today, 21), 4, days, nightFrom, nightTo, atNight = false))
        // Due in the day: as it is.
        assertEquals(at(today, 14), Wear.dueAt(at(today, 8), 6, days, nightFrom, nightTo, atNight = false))
        // Night hours within a day (a night worker's 8 am to 4 pm).
        assertEquals(at(today, 16), Wear.dueAt(at(today, 6), 4, days, 8 * 60, 16 * 60, atNight = false))
    }

    // ---- Who sees it ----

    @Test
    fun `a share with bladder and bowel sees what is on and changes nothing, and the connector only with its scope`() = serverTest(services()) { s ->
        val me = s.admin()
        val night = s.supplies.save(me, Supply("Night briefs", SupplyKind.BRIEF, onHand = 10))
        s.modules.continence.save(me, change(at(today, 8), null, night))
        s.users.create("sam", "Sam", password = "sam-password-123")
        s.users.create("robin", "Robin", password = "robin-password-123")
        val admin = adminBrowser()
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/shares", """{"username":"sam","modules":["CONTINENCE"]}""").status)
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/shares", """{"username":"robin","modules":["MIND"]}""").status)
        suspend fun login(user: String, password: String): HttpClient = browser().also { it.post("/api/auth/login") { json("""{"username":"$user","password":"$password"}""") } }
        val sam = login("sam", "sam-password-123")
        val robin = login("robin", "robin-password-123")

        val seen = sam.get("/api/continence/wearing") { header("X-SAMH-View", me) }
        assertEquals(HttpStatusCode.OK, seen.status)
        assertEquals("Night briefs", seen.obj().str("name"))
        assertEquals(HttpStatusCode.OK, sam.get("/api/continence/products?days=90") { header("X-SAMH-View", me) }.status)
        val samsView = sam.get("/api/day/$today") { header("X-SAMH-View", me) }.obj()
        assertEquals("Night briefs", samsView["modules"]!!.jsonObject["continence"]!!.jsonObject["wearing"]!!.jsonObject.str("name"))
        // Read-only: no change through a share.
        val tried = sam.post("/api/continence/entries") {
            header("X-SAMH-View", me); csrf()
            json("""{"at":$now,"kind":"PAD_CHANGE","supplyId":"${night.id}","nothingOn":true}""")
        }
        assertEquals(HttpStatusCode.Forbidden, tried.status)
        assertEquals("Night briefs", s.modules.continence.wearing(me).name)
        // A share without bladder and bowel sees none of it.
        assertEquals(HttpStatusCode.Forbidden, robin.get("/api/continence/wearing") { header("X-SAMH-View", me) }.status)
        val robinsView = robin.get("/api/day/$today") { header("X-SAMH-View", me) }.obj()
        assertTrue(robinsView["modules"]!!.jsonObject["continence"].let { it == null || it is JsonNull })

        // The AI connector: only a link scoped to bladder and bowel reads it.
        suspend fun rpc(url: String, method: String, params: String = "{}") =
            createClient { }.post(url) { json("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""") }.obj()["result"]!!.jsonObject
        fun JsonObject.text() = this["content"]!!.jsonArray[0].jsonObject.str("text")
        val plain = admin.postJson("/api/me/connectors", "{}").obj().str("url").substringAfter("localhost")
        val scoped = admin.postJson("/api/me/connectors", """{"modules":["CONTINENCE"]}""").obj().str("url").substringAfter("localhost")
        val plainTools = rpc(plain, "tools/list")["tools"]!!.jsonArray.map { it.jsonObject.str("name") }
        assertFalse("continence" in plainTools)
        assertFalse(rpc(plain, "tools/call", """{"name":"day","arguments":{}}""").text().contains("Night briefs"))
        val text = rpc(scoped, "tools/call", """{"name":"continence","arguments":{"days":7}}""").text()
        assertTrue(text, text.contains("Wearing now: Night briefs (brief), on since"))
        assertTrue(rpc(scoped, "tools/call", """{"name":"day","arguments":{}}""").text().contains("wearing Night briefs for 7 h"))
    }
}
