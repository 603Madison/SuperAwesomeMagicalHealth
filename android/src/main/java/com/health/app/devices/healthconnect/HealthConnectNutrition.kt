package com.health.app.devices.healthconnect

import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import com.health.api.Micronutrient
import com.health.api.Nutrients
import java.time.Instant
import java.time.ZoneOffset

/**
 * Food's nutrients as Health Connect's [NutritionRecord] and back: calories and macros, fibre,
 * sugar, saturated fat and sodium, and every [Micronutrient] Health Connect has a field for (all
 * but omega-3). Nothing missing is written as 0, and nothing missing is read as 0. Pure, so it
 * can be tested.
 *
 * Writing is how food reaches Samsung Health, MyFitnessPal and the rest (Settings › Export).
 * Reading ([nutrients]) is ready for the day the Health Connect conduit reads food, which it
 * doesn't yet.
 */
object HealthConnectNutrition {
    /** Health Connect's field for each micronutrient (omega-3 has none). */
    private val FIELDS: Map<Micronutrient, (NutritionRecord) -> Mass?> = mapOf(
        Micronutrient.TRANS_FAT to { it.transFat },
        Micronutrient.MONO_FAT to { it.monounsaturatedFat },
        Micronutrient.POLY_FAT to { it.polyunsaturatedFat },
        Micronutrient.CHOLESTEROL to { it.cholesterol },
        Micronutrient.POTASSIUM to { it.potassium },
        Micronutrient.CALCIUM to { it.calcium },
        Micronutrient.IRON to { it.iron },
        Micronutrient.MAGNESIUM to { it.magnesium },
        Micronutrient.ZINC to { it.zinc },
        Micronutrient.PHOSPHORUS to { it.phosphorus },
        Micronutrient.SELENIUM to { it.selenium },
        Micronutrient.IODINE to { it.iodine },
        Micronutrient.VITAMIN_A to { it.vitaminA },
        Micronutrient.VITAMIN_C to { it.vitaminC },
        Micronutrient.VITAMIN_D to { it.vitaminD },
        Micronutrient.VITAMIN_E to { it.vitaminE },
        Micronutrient.VITAMIN_K to { it.vitaminK },
        Micronutrient.THIAMIN to { it.thiamin },
        Micronutrient.RIBOFLAVIN to { it.riboflavin },
        Micronutrient.NIACIN to { it.niacin },
        Micronutrient.VITAMIN_B6 to { it.vitaminB6 },
        Micronutrient.FOLATE to { it.folate },
        Micronutrient.VITAMIN_B12 to { it.vitaminB12 },
    )

    /** The micronutrients that travel through Health Connect. */
    val CARRIED: Set<Micronutrient> get() = FIELDS.keys

    /** An amount in a nutrient's unit ("g", "mg" or "µg") as Health Connect's mass. */
    fun mass(unit: String, value: Double): Mass = when (unit) {
        "g" -> Mass.grams(value)
        "mg" -> Mass.milligrams(value)
        else -> Mass.micrograms(value)
    }

    /** Health Connect's mass in a nutrient's unit. */
    fun value(unit: String, mass: Mass): Double = when (unit) {
        "g" -> mass.inGrams
        "mg" -> mass.inMilligrams
        else -> mass.inMicrograms
    }

    /** Health Connect takes at most 100 g of a vitamin or mineral in one record. */
    private fun micro(n: Nutrients, m: Micronutrient): Mass? =
        n.micros[m]?.takeIf { it.isFinite() }?.let { mass(m.unit, it.coerceIn(0.0, value(m.unit, Mass.grams(100.0)))) }

    private fun grams(v: Double) = Mass.grams(v.coerceIn(0.0, 99_999.0))

    /** [n] as a record from [start] to [end], named [name], for [mealType]. */
    fun record(
        n: Nutrients, start: Instant, startOffset: ZoneOffset?, end: Instant, endOffset: ZoneOffset?, metadata: Metadata, name: String, mealType: Int,
    ): NutritionRecord = NutritionRecord(
        startTime = start, startZoneOffset = startOffset, endTime = end, endZoneOffset = endOffset,
        metadata = metadata,
        energy = Energy.kilocalories(n.calories.coerceIn(0.0, 99_999.0)),
        protein = grams(n.protein),
        totalCarbohydrate = grams(n.carbs),
        totalFat = grams(n.fat),
        dietaryFiber = n.fiber?.let(::grams),
        sugar = n.sugar?.let(::grams),
        saturatedFat = n.saturatedFat?.let(::grams),
        sodium = n.sodiumMg?.let { Mass.milligrams(it.coerceIn(0.0, 99_999_000.0)) },
        transFat = micro(n, Micronutrient.TRANS_FAT),
        monounsaturatedFat = micro(n, Micronutrient.MONO_FAT),
        polyunsaturatedFat = micro(n, Micronutrient.POLY_FAT),
        cholesterol = micro(n, Micronutrient.CHOLESTEROL),
        potassium = micro(n, Micronutrient.POTASSIUM),
        calcium = micro(n, Micronutrient.CALCIUM),
        iron = micro(n, Micronutrient.IRON),
        magnesium = micro(n, Micronutrient.MAGNESIUM),
        zinc = micro(n, Micronutrient.ZINC),
        phosphorus = micro(n, Micronutrient.PHOSPHORUS),
        selenium = micro(n, Micronutrient.SELENIUM),
        iodine = micro(n, Micronutrient.IODINE),
        vitaminA = micro(n, Micronutrient.VITAMIN_A),
        vitaminC = micro(n, Micronutrient.VITAMIN_C),
        vitaminD = micro(n, Micronutrient.VITAMIN_D),
        vitaminE = micro(n, Micronutrient.VITAMIN_E),
        vitaminK = micro(n, Micronutrient.VITAMIN_K),
        thiamin = micro(n, Micronutrient.THIAMIN),
        riboflavin = micro(n, Micronutrient.RIBOFLAVIN),
        niacin = micro(n, Micronutrient.NIACIN),
        vitaminB6 = micro(n, Micronutrient.VITAMIN_B6),
        folate = micro(n, Micronutrient.FOLATE),
        vitaminB12 = micro(n, Micronutrient.VITAMIN_B12),
        name = name,
        mealType = mealType,
    )

    /** What a record holds, in the app's terms: what it leaves out stays unknown. */
    fun nutrients(r: NutritionRecord): Nutrients = Nutrients(
        calories = r.energy?.inKilocalories ?: 0.0,
        protein = r.protein?.inGrams ?: 0.0,
        carbs = r.totalCarbohydrate?.inGrams ?: 0.0,
        fat = r.totalFat?.inGrams ?: 0.0,
        fiber = r.dietaryFiber?.inGrams,
        sugar = r.sugar?.inGrams,
        saturatedFat = r.saturatedFat?.inGrams,
        sodiumMg = r.sodium?.inMilligrams,
        micros = FIELDS.mapNotNull { (m, field) -> field(r)?.let { m to value(m.unit, it) } }.toMap(),
    )
}
