package com.health.server

import com.health.api.Fields
import com.health.api.Food
import com.health.api.FoodSource
import com.health.api.MicroTarget
import com.health.api.Micronutrient
import com.health.api.Nutrients
import com.health.api.NutritionTarget
import com.health.server.food.GradeGoals
import com.health.server.food.Grades
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradesTest {
    private val target = NutritionTarget(
        calories = 2000, protein = 100, carbs = 250, fat = 70, waterMl = 2000, basis = "Your goal",
        fiberG = 30.0, sugarG = 50.0, saturatedFatG = 20.0, sodiumMg = 2300.0,
        micros = listOf(MicroTarget(Micronutrient.IRON, 14.0), MicroTarget(Micronutrient.CALCIUM, 1000.0), MicroTarget(Micronutrient.CHOLESTEROL, 300.0)),
    )

    private fun food(grams: Double?, n: Nutrients, name: String = "Food") = Food(name, n, "1 serving", grams, source = FoodSource.USDA)

    // Per 100 g, as on a label.
    private val oats = food(40.0, Nutrients(calories = 152.0, protein = 5.3, carbs = 27.0, fat = 2.6, fiber = 4.0, sugar = 0.4, saturatedFat = 0.5, sodiumMg = 2.0))
    private val cola = food(330.0, Nutrients(calories = 139.0, carbs = 35.0, sugar = 35.0, saturatedFat = 0.0, sodiumMg = 33.0))
    private val crisps = food(30.0, Nutrients(calories = 160.0, protein = 2.0, carbs = 15.0, fat = 10.0, fiber = 1.2, sugar = 0.2, saturatedFat = 1.0, sodiumMg = 170.0))
    private val chicken = food(120.0, Nutrients(calories = 198.0, protein = 37.0, fat = 4.3, saturatedFat = 1.2, sodiumMg = 89.0, sugar = 0.0))

    @Test
    fun `Nutri-Score points follow its thresholds`() {
        assertEquals(0, Grades.points(3.4, doubleArrayOf(3.4, 6.8)))
        assertEquals(1, Grades.points(3.5, doubleArrayOf(3.4, 6.8)))
        assertEquals(0, Grades.points(null, doubleArrayOf(1.0)))
        // Cola per 100 g: 42 kcal (176 kJ: none), 10.6 g sugars (3 points).
        val p = Grades.profile(cola.nutrients * (100.0 / 330.0))
        assertEquals(0, p.energy)
        assertEquals(3, p.sugars)
        assertEquals(listOf("A", "B", "C", "D", "F"), listOf(-3, 2, 10, 18, 19).map(Grades::letter))
    }

    @Test
    fun `a whole food grades well, a sugary drink and salty crisps less so`() {
        assertEquals("A", Grades.grade(oats)!!.letter)
        assertTrue(Grades.grade(chicken)!!.letter == "A")
        val crispGrade = Grades.grade(crisps)!!
        assertTrue(crispGrade.letter in setOf("D", "F"))
        assertTrue(crispGrade.reasons.any { it.contains("sodium", ignoreCase = true) || it == "Energy-dense" })
        assertTrue(crispGrade.reasons.size in 1..3)
    }

    @Test
    fun `conditions weigh the matching nutrient, and say so`() {
        val soup = food(250.0, Nutrients(calories = 90.0, protein = 4.0, carbs = 12.0, fat = 2.5, fiber = 2.0, sugar = 3.0, saturatedFat = 0.8, sodiumMg = 900.0))
        val plain = Grades.grade(soup, GradeGoals(target))!!
        val pressure = Grades.grade(soup, GradeGoals(target, setOf("hypertension")))!!
        assertTrue(pressure.letter >= plain.letter)
        assertTrue(pressure.reasons.contains("High in sodium for your blood pressure goal"))
        val diabetes = Grades.grade(cola, GradeGoals(target, setOf("type-2-diabetes")))!!
        assertTrue(diabetes.reasons.contains("High in sugars for your blood sugar goal"))
        assertTrue(diabetes.letter >= Grades.grade(cola, GradeGoals(target))!!.letter)
        val butter = food(14.0, Nutrients(calories = 100.0, fat = 11.0, saturatedFat = 7.0, sodiumMg = 90.0, sugar = 0.0))
        assertTrue(Grades.grade(butter, GradeGoals(target, setOf("high-cholesterol")))!!.reasons.contains("High in saturated fat for your heart goal"))
    }

    @Test
    fun `targets count, protein for a goal and micronutrients aimed for`() {
        val goal = Grades.grade(chicken, GradeGoals(target, setOf("build-muscle")))!!
        assertEquals("A", goal.letter)
        assertTrue(goal.reasons.contains("Good protein for your goal"))
        val spinach = food(100.0, Nutrients(calories = 23.0, protein = 2.9, carbs = 3.6, fat = 0.4, fiber = 2.2, sugar = 0.4, sodiumMg = 79.0, micros = mapOf(Micronutrient.IRON to 3.6, Micronutrient.CALCIUM to 99.0)))
        assertTrue(Grades.grade(spinach, GradeGoals(target))!!.reasons.any { it.startsWith("Good source of iron") })
    }

    @Test
    fun `a weight goal moves energy-dense foods`() {
        val nuts = food(30.0, Nutrients(calories = 180.0, protein = 6.0, carbs = 6.0, fat = 16.0, fiber = 3.0, sugar = 1.0, saturatedFat = 1.5, sodiumMg = 1.0))
        val losing = Grades.grade(nuts, GradeGoals(target, weeklyChangeKg = -0.5))!!
        val gaining = Grades.grade(nuts, GradeGoals(target, weeklyChangeKg = 0.3))!!
        assertTrue(gaining.letter <= losing.letter)
        assertTrue(gaining.reasons.contains("Energy-dense, which suits your goal"))
    }

    @Test
    fun `reasons are neutral, and too little to go on gives no grade`() {
        val words = listOf(oats, cola, crisps, chicken).flatMap { f -> listOf(GradeGoals(), GradeGoals(target, setOf("type-1-diabetes", "hypertension", "lose-weight"))).flatMap { Grades.grade(f, it)!!.reasons } }
        listOf("bad", "unhealthy", "avoid", "junk", "should", "guilt", "cheat").forEach { w -> assertFalse(words.any { it.lowercase().contains(w) }) }
        assertNull(Grades.grade(food(null, Nutrients())))
        // Without a weight it's judged per calorie; with no sugars, fat or sodium known, never better than B.
        val recipe = Grades.grade(food(null, Nutrients(calories = 400.0, protein = 30.0, carbs = 40.0, fat = 12.0)))!!
        assertTrue(recipe.letter >= "B")
        assertTrue(recipe.reasons.contains("Sugars, fat and sodium aren't known"))
    }

    @Test
    fun `foods come graded for the person, until grades are turned off`() = serverTest { _ ->
        val admin = adminBrowser()
        val saved = admin.postJson("/api/foods", """{"name":"Oats","serving":"40 g","servingGrams":40,"nutrients":{"calories":152,"protein":5.3,"carbs":27,"fat":2.6,"fiber":4,"sugar":0.4,"saturatedFat":0.5,"sodiumMg":2}}""").obj()
        assertEquals("A", saved["grade"]!!.jsonObject.str("letter"))
        val recent = admin.get("/api/foods/recent").json().jsonArray
        assertTrue(recent[0].jsonObject["grade"]!!.jsonObject["reasons"]!!.jsonArray.isNotEmpty())
        // A grade sent back is never kept.
        val again = admin.putJson("/api/foods/${saved.str("id")}", saved.toString().replace("\"letter\":\"A\"", "\"letter\":\"F\"")).obj()
        assertEquals("A", again["grade"]!!.jsonObject.str("letter"))

        admin.putJson("/api/fields", """{"hide":["${Fields.FOOD_GRADES}"]}""")
        val off = admin.get("/api/foods/recent").json().jsonArray[0].jsonObject
        assertTrue(off["grade"] == null || off["grade"] is JsonNull)
        admin.putJson("/api/fields", """{"hide":[]}""")
        // And never in eating disorder recovery, where amounts of food don't show.
        admin.putJson("/api/conditions", """{"conditions":[{"id":"eating-disorder","name":"x"}]}""")
        val recovery = admin.get("/api/foods/recent").json().jsonArray[0].jsonObject
        assertTrue(recovery["grade"] == null || recovery["grade"] is JsonNull)
    }

    @Test
    fun `clearing foods leaves the diary as it was`() = serverTest { _ ->
        val admin = adminBrowser()
        val food = admin.postJson("/api/foods", """{"name":"Old food","nutrients":{"calories":200,"protein":10,"carbs":20,"fat":8}}""").obj()
        admin.postJson("/api/foods", """{"name":"Another","nutrients":{"calories":50}}""")
        admin.postJson("/api/food/entries", """{"date":"2026-02-01","meal":"LUNCH","servings":2,"food":$food}""")
        assertEquals(HttpStatusCode.OK, admin.delete("/api/foods/${food.str("id")}") { csrf() }.status)
        assertEquals(1, admin.get("/api/foods/recent").json().jsonArray.size)
        val cleared = admin.delete("/api/foods") { csrf() }.obj()
        assertEquals(1, cleared["removed"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, admin.get("/api/foods/recent").json().jsonArray.size)
        val entry = admin.get("/api/food/2026-02-01").obj()["entries"]!!.jsonArray.single().jsonObject
        assertEquals("Old food", entry.str("name"))
        assertEquals(400.0, entry["nutrients"]!!.jsonObject.str("calories").toDouble(), 0.01)
        assertTrue(entry["foodId"] == null || entry["foodId"] is JsonNull)
    }

    @Test
    fun `a shopping list to add to, tick off and clear, never shared`() = serverTest { _ ->
        val admin = adminBrowser()
        admin.postJson("/api/shopping", """{"name":"Bread"}""")
        val list = admin.postJson("/api/shopping", """{"food":{"name":"Oats","servingGrams":40,"nutrients":{"calories":152,"protein":5.3,"carbs":27,"fat":2.6,"fiber":4,"sugar":0.4,"saturatedFat":0.5,"sodiumMg":2},"source":"USDA"}}""").obj()
        val items = list["items"]!!.jsonArray
        assertEquals(listOf("Bread", "Oats"), items.map { it.jsonObject.str("name") })
        assertEquals("A", items[1].jsonObject["food"]!!.jsonObject["grade"]!!.jsonObject.str("letter"))
        assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/shopping", """{"name":"  "}""").status)
        val bread = items[0].jsonObject.str("id")
        val ticked = admin.patchJson("/api/shopping/$bread", """{"checked":true}""").obj()
        assertEquals("true", ticked["items"]!!.jsonArray[0].jsonObject.str("checked"))
        val left = admin.delete("/api/shopping/bought") { csrf() }.obj()["items"]!!.jsonArray
        assertEquals(listOf("Oats"), left.map { it.jsonObject.str("name") })
        assertEquals(0, admin.delete("/api/shopping/${left[0].jsonObject.str("id")}") { csrf() }.obj()["items"]!!.jsonArray.size)
        assertEquals(com.health.server.care.SharedView.Need.Never, com.health.server.care.SharedView.requirement("/api/shopping", null))
        assertEquals(com.health.server.care.SharedView.Need.Never, com.health.server.care.SharedView.requirement("/api/foods", null))
    }
}
