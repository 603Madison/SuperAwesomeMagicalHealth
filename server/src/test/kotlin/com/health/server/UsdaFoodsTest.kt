package com.health.server

import com.health.api.Food
import com.health.api.FoodSource
import com.health.api.Micronutrient
import com.health.api.Nutrients
import com.health.server.food.FoodFinder
import com.health.server.food.OpenFoodFacts
import com.health.server.food.UsdaFoods
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** USDA FoodData Central's answers as foods (from real search answers), and Open Food Facts in English. */
class UsdaFoodsTest {
    private fun fixture(name: String): List<JsonObject> =
        Json.parseToJsonElement(UsdaFoodsTest::class.java.getResource("/usda/$name")!!.readText()).jsonObject["foods"]!!.jsonArray.map { it.jsonObject }

    private val branded = fixture("branded-greek-yogurt.json").associateBy { it["gtinUpc"].toString().trim('"') }
    private val everyday = fixture("everyday-banana.json").associateBy { it["description"].toString().trim('"') }

    @Test
    fun `a packaged food is per serving from its label, with a readable name and brand`() {
        val chobani = UsdaFoods.toFood(branded.getValue("894700010038"))!!
        assertEquals("Greek Yogurt", chobani.name)
        assertEquals("Chobani", chobani.brand)
        assertEquals("1 container (150 g)", chobani.serving)
        assertEquals(150.0, chobani.servingGrams!!, 0.0)
        assertEquals("894700010038", chobani.barcode)
        assertEquals(FoodSource.USDA, chobani.source)
        // 80 kcal, 8.67 g protein and 43 mg sodium per 100 g.
        assertEquals(120.0, chobani.nutrients.calories, 0.05)
        assertEquals(13.0, chobani.nutrients.protein, 0.05)
        assertEquals(16.0, chobani.nutrients.carbs, 0.11)
        assertEquals(64.5, chobani.nutrients.sodiumMg!!, 0.05)
        assertEquals(0.0, chobani.nutrients.saturatedFat!!, 0.0)
        assertEquals(150.0, chobani.nutrients.micros.getValue(Micronutrient.CALCIUM), 0.5)
        assertEquals(4.5, chobani.nutrients.micros.getValue(Micronutrient.CHOLESTEROL), 0.01)
        assertEquals(0.0, chobani.nutrients.micros.getValue(Micronutrient.TRANS_FAT), 0.0)
        // Vitamin A in international units can't be told in RAE: left out.
        assertFalse(Micronutrient.VITAMIN_A in chobani.nutrients.micros)

        // No brand name: the brand's owner, in capitals made readable.
        val joes = UsdaFoods.toFood(branded.getValue("00772914"))!!
        assertEquals("Trader Joe's", joes.brand)
        assertEquals("1 container (227 g)", joes.serving)
        assertEquals(119 * 2.27, joes.nutrients.micros.getValue(Micronutrient.POTASSIUM), 0.6)

        // Household words as they are; vitamin D from international units (40 IU is 1 µg).
        assertEquals("2 Tbsp (30 g)", UsdaFoods.toFood(branded.getValue("031200037206"))!!.serving)
        val olympiana = UsdaFoods.toFood(branded.getValue("680569649836"))!!
        assertEquals(150 / 40.0 * 1.7, olympiana.nutrients.micros.getValue(Micronutrient.VITAMIN_D), 0.01)
    }

    @Test
    fun `everyday foods keep their names, and a survey food's first real portion is its serving`() {
        val raw = UsdaFoods.toFood(everyday.getValue("Banana, raw"))!!
        assertEquals("Banana, raw", raw.name)
        assertNull(raw.brand)
        assertNull(raw.barcode)
        assertEquals("1 banana (126 g)", raw.serving)
        assertEquals(122.2, raw.nutrients.calories, 0.05)
        assertEquals(28.6, raw.nutrients.carbs, 0.05)
        assertEquals(2.1, raw.nutrients.fiber!!, 0.05)
        assertEquals(19.9, raw.nutrients.sugar!!, 0.05)
        val m = raw.nutrients.micros
        assertEquals(411.0, m.getValue(Micronutrient.POTASSIUM), 0.5)
        assertEquals(1.26, m.getValue(Micronutrient.VITAMIN_A), 0.005) // µg RAE
        assertEquals(18.9, m.getValue(Micronutrient.FOLATE), 0.05) // µg DFE
        assertEquals(0.267, m.getValue(Micronutrient.VITAMIN_B6), 0.001)
        assertEquals(0.126, m.getValue(Micronutrient.VITAMIN_K), 0.001)
        assertEquals(0.0403, m.getValue(Micronutrient.MONO_FAT), 0.0005)
        assertEquals(35.3, m.getValue(Micronutrient.MAGNESIUM), 0.05)
        assertFalse(Micronutrient.OMEGA_3 in m)

        // "Quantity not specified" isn't a portion; the first by rank is.
        assertEquals("1 banana (140 g)", UsdaFoods.toFood(everyday.getValue("Banana, baked"))!!.serving)
        assertEquals("1 slice (2 g)", UsdaFoods.toFood(everyday.getValue("Banana chips"))!!.serving)
        // No portions (SR Legacy in a search): per 100 g.
        val powder = UsdaFoods.toFood(everyday.getValue("Bananas, dehydrated, or banana powder"))!!
        assertEquals("100 g", powder.serving)
        assertEquals(346.0, powder.nutrients.calories, 0.0)
        assertEquals(12.0, powder.nutrients.micros.getValue(Micronutrient.VITAMIN_A), 0.0)
    }

    @Test
    fun `Foundation foods without energy in kilocalories take the Atwater figures`() {
        val food = UsdaFoods.toFood(
            Json.parseToJsonElement(
                """{"description":"Hummus, commercial","dataType":"Foundation","foodNutrients":[
                    {"nutrientNumber":"957","unitName":"KCAL","value":229},{"nutrientNumber":"958","unitName":"KCAL","value":222},
                    {"nutrientNumber":"203","unitName":"G","value":7.35},{"nutrientNumber":"205.2","unitName":"G","value":14.9},
                    {"nutrientNumber":"269.3","unitName":"G","value":0.34},{"nutrientNumber":"304","unitName":"MG","value":71.1},
                    {"nutrientNumber":"314","unitName":"UG","value":3.0}]}""",
            ).jsonObject,
        )!!
        assertEquals(222.0, food.nutrients.calories, 0.0)
        assertEquals(14.9, food.nutrients.carbs, 0.0)
        assertEquals(0.3, food.nutrients.sugar!!, 0.0)
        assertEquals(3.0, food.nutrients.micros.getValue(Micronutrient.IODINE), 0.0)
        assertEquals("100 g", food.serving)
        // Nothing to count: not a food here.
        assertNull(UsdaFoods.toFood(Json.parseToJsonElement("""{"description":"Water","dataType":"Branded","foodNutrients":[]}""").jsonObject))
    }

    @Test
    fun `names in capitals read naturally`() {
        assertEquals("Greek Yogurt, Plain with Honey", UsdaFoods.readable("GREEK YOGURT, PLAIN WITH HONEY"))
        assertEquals("Trader Joe's", UsdaFoods.readable("TRADER JOE'S"))
        assertEquals("And Then Some 12oz (Multi-Pack)", UsdaFoods.readable("AND THEN SOME 12OZ (MULTI-PACK)"))
        assertEquals("Chobani, Inc.", UsdaFoods.readable("Chobani, Inc."))
        assertEquals("Banana, raw", UsdaFoods.readable("Banana, raw"))
    }

    @Test
    fun `a barcode is searched for in each of its forms`() {
        assertEquals(listOf("0894700010038", "894700010038", "00894700010038"), UsdaFoods.barcodeForms("0894700010038"))
        assertEquals(listOf("894700010038", "0894700010038", "00894700010038"), UsdaFoods.barcodeForms("894700010038"))
        assertEquals(listOf("00772914", "000000772914", "0000000772914", "00000000772914"), UsdaFoods.barcodeForms("00772914"))
        assertEquals(listOf("10894700010035"), UsdaFoods.barcodeForms("10894700010035"))
    }

    @Test
    fun `Open Food Facts searches show only English names`() {
        fun off(json: String, english: Boolean = true) = OpenFoodFacts.toFood(Json.parseToJsonElement(json).jsonObject, englishOnly = english)
        val n = """"nutriments":{"energy-kcal_100g":60}"""
        assertEquals("Kefir", off("""{"lang":"ru","product_name":"Кефир","product_name_en":"Kefir",$n}""")!!.name)
        assertEquals("Oat milk", off("""{"lang":"en","product_name":"Oat milk",$n}""")!!.name)
        assertNull(off("""{"lang":"ru","product_name":"Кефир",$n}"""))
        assertNull(off("""{"product_name":"Kefir",$n}"""))
        // A barcode is the product itself: its own name when there's no English one.
        assertEquals("Кефир", off("""{"lang":"ru","product_name":"Кефир",$n}""", english = false)!!.name)
    }

    @Test
    fun `merged, each food once, after the person's own`() {
        fun food(name: String, brand: String? = null, barcode: String? = null, source: FoodSource = FoodSource.USDA) = Food(name, Nutrients(100.0), brand = brand, barcode = barcode, source = source)
        val mine = listOf(food("My granola", barcode = "123456789012", source = FoodSource.CUSTOM))
        val merged = FoodFinder.merge(
            mine,
            listOf(
                listOf(food("Banana, raw")),
                listOf(food("Greek Yogurt", "Chobani", "894700010038"), food("GREEK YOGURT", "CHOBANI", "894700010441"), food("Granola", "Acme", "0123456789012")),
                listOf(food("Nonfat Greek yogurt", "Chobani", "0894700010038", FoodSource.OPEN_FOOD_FACTS), food("banana, RAW", source = FoodSource.OPEN_FOOD_FACTS), food("Skyr", "Siggi's", "1", FoodSource.OPEN_FOOD_FACTS)),
            ),
        )
        assertEquals(listOf("Banana, raw", "Greek Yogurt", "Skyr"), merged.map { it.name })
    }
}
