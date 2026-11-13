package com.health.server.insights

import com.health.api.EnergyProfile
import com.health.api.ExtraNutrient
import com.health.api.MicroAim
import com.health.api.MicroTarget
import com.health.api.Micronutrient
import com.health.api.NutrientTargetSetting
import com.health.api.NutrientTargets
import com.health.api.NutritionTarget
import com.health.api.Profile
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Daily reference intakes for vitamins, minerals, fibre and the finer fats, and limits for sodium,
 * sugar, saturated fat, trans fat and cholesterol: for adults, by age, with the female or male
 * values the person chose for their calorie formula ([EnergyProfile], never inferred; "average"
 * takes the mean of the two).
 *
 * Where the numbers come from:
 * - Vitamins, minerals, fibre and omega-3 (as ALA): the US National Academies' Dietary Reference
 *   Intakes, the RDA (or the AI where there's no RDA) and the tolerable upper intake level (UL),
 *   as the NIH Office of Dietary Supplements lists them
 *   (https://ods.od.nih.gov/HealthInformation/nutrientrecommendations.aspx; the DRI tables:
 *   https://www.ncbi.nlm.nih.gov/books/NBK545442/).
 * - Potassium and sodium: the same, as revised in 2019 (https://www.ncbi.nlm.nih.gov/books/NBK538102/);
 *   sodium's limit is its Chronic Disease Risk Reduction intake, 2,300 mg.
 * - Saturated fat: under 10% of the day's calories (Dietary Guidelines for Americans, 2020–2025).
 * - Trans fat: under 1% of the day's calories (WHO).
 * - Cholesterol: the US FDA's Daily Value, 300 mg (21 CFR 101.9).
 * - Sugar: food labels give total sugars, which have no DRI; the EU's reference intake for them,
 *   90 g (Regulation (EU) No 1169/2011, Annex XIII).
 * - Pregnancy raises folate to 600 µg, iron to 27 mg and iodine to 220 µg (the DRIs again).
 *
 * Upper limits are left out where the UL is for supplements and fortified foods only (magnesium,
 * niacin, folate, vitamin E), or for preformed vitamin A only (labels give all vitamin A, most of
 * which in vegetables doesn't count towards it).
 */
object ReferenceIntakes {
    const val SOURCE = "US National Academies' Dietary Reference Intakes (via the NIH Office of Dietary Supplements); " +
        "saturated fat from the Dietary Guidelines for Americans, trans fat from the WHO, cholesterol from the FDA's Daily Value, " +
        "and sugar from the EU's reference intake"

    /** A reference amount for female and male bodies, and the upper limit where one applies to food. */
    class Reference(val female: Double, val male: Double, val upper: Double? = null) {
        fun amount(profile: EnergyProfile): Double = when (profile) {
            EnergyProfile.FEMALE -> female
            EnergyProfile.MALE -> male
            EnergyProfile.AVERAGE -> (female + male) / 2
        }
    }

    /** The age the reference intakes are for: adults, so younger people get the 19–30 values; 30 without a birth year. */
    fun age(profile: Profile, date: LocalDate): Int = profile.birthYear?.let { (date.year - it).coerceIn(19, 110) } ?: 30

    /** The reference for [micro] at [age], or null where there isn't one (monounsaturated and polyunsaturated fat; trans fat and cholesterol are limits, see [limit]). */
    fun reference(micro: Micronutrient, age: Int): Reference? = when (micro) {
        Micronutrient.POTASSIUM -> Reference(2600.0, 3400.0)
        Micronutrient.CALCIUM -> when {
            age >= 71 -> Reference(1200.0, 1200.0, 2000.0)
            age >= 51 -> Reference(1200.0, 1000.0, 2000.0)
            else -> Reference(1000.0, 1000.0, 2500.0)
        }
        Micronutrient.IRON -> if (age >= 51) Reference(8.0, 8.0, 45.0) else Reference(18.0, 8.0, 45.0)
        Micronutrient.MAGNESIUM -> if (age <= 30) Reference(310.0, 400.0) else Reference(320.0, 420.0)
        Micronutrient.ZINC -> Reference(8.0, 11.0, 40.0)
        Micronutrient.PHOSPHORUS -> Reference(700.0, 700.0, if (age >= 71) 3000.0 else 4000.0)
        Micronutrient.SELENIUM -> Reference(55.0, 55.0, 400.0)
        Micronutrient.IODINE -> Reference(150.0, 150.0, 1100.0)
        Micronutrient.VITAMIN_A -> Reference(700.0, 900.0)
        Micronutrient.VITAMIN_C -> Reference(75.0, 90.0, 2000.0)
        Micronutrient.VITAMIN_D -> Reference(if (age >= 71) 20.0 else 15.0, if (age >= 71) 20.0 else 15.0, 100.0)
        Micronutrient.VITAMIN_E -> Reference(15.0, 15.0)
        Micronutrient.VITAMIN_K -> Reference(90.0, 120.0)
        Micronutrient.THIAMIN -> Reference(1.1, 1.2)
        Micronutrient.RIBOFLAVIN -> Reference(1.1, 1.3)
        Micronutrient.NIACIN -> Reference(14.0, 16.0)
        Micronutrient.VITAMIN_B6 -> if (age >= 51) Reference(1.5, 1.7, 100.0) else Reference(1.3, 1.3, 100.0)
        Micronutrient.FOLATE -> Reference(400.0, 400.0)
        Micronutrient.VITAMIN_B12 -> Reference(2.4, 2.4)
        Micronutrient.OMEGA_3 -> Reference(1.1, 1.6)
        Micronutrient.MONO_FAT, Micronutrient.POLY_FAT, Micronutrient.TRANS_FAT, Micronutrient.CHOLESTEROL -> null
    }

    /** Fibre's adequate intake: 25 g and 38 g to 50, 21 g and 30 g after. */
    fun fiber(age: Int): Reference = if (age >= 51) Reference(21.0, 30.0) else Reference(25.0, 38.0)

    const val SODIUM_MG = 2300.0
    const val SUGAR_G = 90.0
    const val CHOLESTEROL_MG = 300.0

    /** What pregnancy raises (to at least this much), whatever the body. */
    val PREGNANCY: Map<Micronutrient, Double> = mapOf(Micronutrient.FOLATE to 600.0, Micronutrient.IRON to 27.0, Micronutrient.IODINE to 220.0)

    /** A limit worked out from the day's calories, or a fixed one: for the nutrients to stay under. */
    private fun limit(micro: Micronutrient, calories: Int): Double? = when (micro) {
        Micronutrient.TRANS_FAT -> round1(calories * 0.01 / 9)
        Micronutrient.CHOLESTEROL -> CHOLESTEROL_MG
        else -> null
    }

    /** One nutrient's reference for the person: its amount and upper limit, before their own. */
    private class Ref(val amount: Double?, val upper: Double?)

    private fun ref(micro: Micronutrient, profile: EnergyProfile, age: Int, calories: Int, pregnant: Boolean): Ref {
        val r = reference(micro, age)
        var amount = r?.amount(profile) ?: limit(micro, calories)
        if (pregnant) PREGNANCY[micro]?.let { p -> amount = maxOf(amount ?: 0.0, p) }
        return Ref(amount, r?.upper)
    }

    private fun extra(e: ExtraNutrient, profile: EnergyProfile, age: Int, calories: Int): Double = when (e) {
        ExtraNutrient.FIBER -> fiber(age).amount(profile)
        ExtraNutrient.SUGAR -> SUGAR_G
        ExtraNutrient.SATURATED_FAT -> round1(calories * 0.10 / 9)
        ExtraNutrient.SODIUM -> SODIUM_MG
    }

    /** Where the targets come from, in a few words: "Adult reference intake, female values". */
    fun basis(profile: EnergyProfile, age: Int, pregnant: Boolean): String {
        val who = when {
            age >= 71 -> "Reference intake for 71 and over"
            age >= 51 -> "Reference intake for 51 to 70"
            else -> "Adult reference intake"
        }
        val values = when (profile) {
            EnergyProfile.FEMALE -> "female values"
            EnergyProfile.MALE -> "male values"
            EnergyProfile.AVERAGE -> "the average of female and male values"
        }
        return "$who, $values" + if (pregnant) ", with more folate, iron and iodine for pregnancy" else ""
    }

    /**
     * [target] with the day's nutrient targets: the reference intakes for the person, or their own
     * ([own], by [ExtraNutrient] or [Micronutrient] name), which come back with `custom`.
     */
    fun apply(target: NutritionTarget, profile: Profile, date: LocalDate, pregnant: Boolean, own: Map<String, Double>): NutritionTarget {
        val age = age(profile, date)
        val chosen = profile.energyProfile
        val micros = Micronutrient.entries.mapNotNull { m ->
            val r = ref(m, chosen, age, target.calories, pregnant)
            val mine = own[m.name]
            when {
                mine != null -> MicroTarget(m, mine, r.upper, custom = true)
                r.amount != null -> MicroTarget(m, round2(r.amount), r.upper)
                else -> null
            }
        }
        fun pick(e: ExtraNutrient) = own[e.name] ?: extra(e, chosen, age, target.calories)
        return target.copy(
            micros = micros,
            fiberG = pick(ExtraNutrient.FIBER),
            sugarG = pick(ExtraNutrient.SUGAR),
            saturatedFatG = pick(ExtraNutrient.SATURATED_FAT),
            sodiumMg = pick(ExtraNutrient.SODIUM),
            microBasis = basis(chosen, age, pregnant),
        )
    }

    /** The settings' list: each nutrient's reference for the person, its upper limit, and their own target. */
    fun settings(profile: Profile, date: LocalDate, calories: Int, pregnant: Boolean, own: Map<String, Double>): NutrientTargets {
        val age = age(profile, date)
        val chosen = profile.energyProfile
        val extras = ExtraNutrient.entries.map { e -> NutrientTargetSetting(e.name, extra(e, chosen, age, calories), null, own[e.name]) }
        val micros = Micronutrient.entries.map { m ->
            val r = ref(m, chosen, age, calories, pregnant)
            NutrientTargetSetting(m.name, r.amount?.let(::round2), r.upper, own[m.name])
        }
        return NutrientTargets(extras + micros, basis(chosen, age, pregnant), SOURCE)
    }

    /** Whether a nutrient's [key] names one ([ExtraNutrient] or [Micronutrient]), and its unit. */
    fun unitOf(key: String): String? =
        ExtraNutrient.entries.firstOrNull { it.name == key }?.unit ?: Micronutrient.entries.firstOrNull { it.name == key }?.unit

    fun aimOf(key: String): MicroAim? =
        ExtraNutrient.entries.firstOrNull { it.name == key }?.aim ?: Micronutrient.entries.firstOrNull { it.name == key }?.aim

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0
}
