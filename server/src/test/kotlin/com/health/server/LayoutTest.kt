package com.health.server

import com.health.api.Layouts
import com.health.api.ScreenLayout
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arranging screens: the order and what's hidden, kept per person. */
class LayoutTest {
    @Test
    fun `a saved order comes first, and sections it doesn't name go where they'd be by default`() {
        val defaults = listOf("scores", "quick", "meds", "mind", "food", "sleep")
        assertEquals(defaults, Layouts.arrange(defaults, { it }, null))
        // The person moved food to the top before "mind" and "sleep" existed: each new one follows
        // the section before it by default (mind after meds, sleep after food).
        val saved = ScreenLayout(order = listOf("food", "scores", "quick", "meds"), hidden = setOf("quick"))
        assertEquals(listOf("food", "sleep", "scores", "quick", "meds", "mind"), Layouts.arrange(defaults, { it }, saved))
        assertEquals(listOf("food", "sleep", "scores", "meds", "mind"), Layouts.visible(defaults, { it }, saved))
        // A section the app no longer has is ignored; a new first section goes first.
        assertEquals(listOf("new", "food", "scores"), Layouts.arrange(listOf("new", "scores", "food"), { it }, ScreenLayout(listOf("gone", "food", "scores"))))
    }

    @Test
    fun `each screen's arrangement is saved, cleaned and reset`() = serverTest { _ ->
        val admin = adminBrowser()
        assertTrue(admin.get("/api/layout").obj()["screens"]!!.jsonObject.isEmpty())

        val today = admin.putJson("/api/layout/today", """{"order":["FOOD","SCORES","FOOD","MEDS"],"hidden":["STRESS"]}""")
        assertEquals(HttpStatusCode.OK, today.status)
        val saved = admin.putJson("/api/layout/web.food", """{"order":["water","diary"],"hidden":[]}""").obj()["screens"]!!.jsonObject
        assertEquals(listOf("FOOD", "SCORES", "MEDS"), saved["today"]!!.jsonObject["order"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(setOf("today", "web.food"), saved.keys)

        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/layout/web food", """{"order":["a"]}""").status)
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/layout/today", """{"order":["<script>"]}""").status)
        val tooMany = (1..81).joinToString(",") { "\"s$it\"" }
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/layout/today", """{"order":[$tooMany]}""").status)

        // Putting a screen back as it was forgets it.
        val reset = admin.delete("/api/layout/web.food") { csrf() }
        assertEquals(HttpStatusCode.OK, reset.status)
        assertFalse("web.food" in admin.get("/api/layout").obj()["screens"]!!.jsonObject)

        // Someone viewing a share keeps their own arrangement: the header isn't allowed here.
        assertEquals(HttpStatusCode.Forbidden, admin.get("/api/layout") { header("X-SAMH-View", "someone") }.status)
    }

    @Test
    fun `people in eating disorder recovery see no amounts of food`() = serverTest { _ ->
        val admin = adminBrowser()
        assertTrue(admin.get("/api/fields").obj()["foodNumbers"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(HttpStatusCode.OK, admin.putJson("/api/conditions", """{"conditions":[{"id":"eating-disorder","name":"Eating disorder recovery"}]}""").status)
        assertFalse(admin.get("/api/fields").obj()["foodNumbers"]!!.jsonPrimitive.content.toBoolean())
    }
}
