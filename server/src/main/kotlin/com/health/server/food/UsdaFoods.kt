package com.health.server.food

import com.health.api.ApiJson
import com.health.api.Food
import com.health.api.FoodSource
import com.health.api.Micronutrient
import com.health.api.Nutrients
import com.health.server.Services
import com.health.server.db.Database
import com.health.server.util.fetchString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.roundToInt

/**
 * USDA FoodData Central (fdc.nal.usda.gov, public domain): everyday foods ("Banana, raw", from
 * Foundation, SR Legacy and the survey foods, FNDDS) and US packaged foods (Branded, with their
 * barcodes). Asked with the server's api.data.gov key, sent in a header; only the words searched
 * (or a barcode) go, never who's asking. Answers are kept a week under "usda1:"; a failed request
 * throws and isn't kept.
 */
class UsdaFoods(
    private val http: OkHttpClient,
    db: Database,
    clock: () -> Long = System::currentTimeMillis,
    private val url: String = SEARCH_URL,
) {
    /** The two kinds asked for in a search: everyday foods first, then packaged ones. */
    enum class Kind(val dataTypes: String, val pageSize: Int) {
        EVERYDAY("Foundation,SR Legacy,Survey (FNDDS)", 8),
        PACKAGED("Branded", 15),
    }

    private val lookups = FoodLookups(db, clock)

    suspend fun search(text: String, kind: Kind, key: String): List<Food> {
        val query = text.trim().take(100)
        if (query.length < 2 || key.isBlank()) return emptyList()
        return lookups.cached("usda1:${kind.name.lowercase()}:${query.lowercase()}") {
            ask(query, kind.dataTypes, kind.pageSize, key).distinctBy { it.barcode ?: (it.name.lowercase() to it.brand?.lowercase()) }
        }
    }

    /**
     * A packaged food by its barcode, searched for among the Branded foods by [barcodeForms] (as
     * scanned, then as a 12-digit UPC-A, 13-digit EAN and 14-digit GTIN) until one matches. Kept
     * only on an exact match of `gtinUpc`, digits only and leading zeros aside. Finding nothing is
     * kept a day.
     */
    suspend fun barcode(code: String, key: String): Food? {
        val bare = code.filter { it.isDigit() }.trimStart('0')
        if (bare.length < 6 || bare.length > 14 || key.isBlank()) return null
        return lookups.cached("usda1:barcode:$bare", briefMisses = true) {
            for (query in barcodeForms(code)) {
                ask(query, Kind.PACKAGED.dataTypes, 10, key).firstOrNull { it.barcode?.trimStart('0') == bare }?.let { return@cached listOf(it) }
            }
            emptyList()
        }.firstOrNull()
    }

    private suspend fun ask(query: String, dataTypes: String, pageSize: Int, key: String): List<Food> {
        val address = url.toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("dataType", dataTypes)
            .addQueryParameter("pageSize", pageSize.toString())
            .build()
        val request = Request.Builder().url(address)
            .header("User-Agent", "SuperAwesomeMagicalHealth/${Services.version} (self-hosted server)")
            .header("Accept", "application/json")
            // api.data.gov takes the key in this header as well as in the address: kept out of addresses (and so out of any log of them).
            .header("X-Api-Key", key)
            .build()
        val json = ApiJson.parseToJsonElement(http.fetchString(request)).jsonObject
        return (json["foods"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::toFood) }
    }

    companion object {
        const val SEARCH_URL = "https://api.nal.usda.gov/fdc/v1/foods/search"

        /**
         * The ways the same barcode can be written, to search for in turn: as scanned, then the
         * 12-digit UPC-A, 13-digit EAN and 14-digit GTIN forms its digits fit (FoodData Central keeps
         * whichever the maker gave). "0894700010038" → 0894700010038, 894700010038, 00894700010038.
         */
        fun barcodeForms(code: String): List<String> {
            val digits = code.filter { it.isDigit() }
            val bare = digits.trimStart('0')
            if (bare.isEmpty()) return emptyList()
            return (listOf(digits) + listOf(12, 13, 14).filter { bare.length <= it }.map { bare.padStart(it, '0') }).distinct()
        }

        /** FoodData Central's nutrient numbers for each of the app's vitamins, minerals and finer fats (omega-3 has no one number). */
        val MICROS: Map<String, Micronutrient> = mapOf(
            "605" to Micronutrient.TRANS_FAT,
            "645" to Micronutrient.MONO_FAT,
            "646" to Micronutrient.POLY_FAT,
            "601" to Micronutrient.CHOLESTEROL,
            "306" to Micronutrient.POTASSIUM,
            "301" to Micronutrient.CALCIUM,
            "303" to Micronutrient.IRON,
            "304" to Micronutrient.MAGNESIUM,
            "309" to Micronutrient.ZINC,
            "305" to Micronutrient.PHOSPHORUS,
            "317" to Micronutrient.SELENIUM,
            "314" to Micronutrient.IODINE,
            "320" to Micronutrient.VITAMIN_A, // RAE
            "401" to Micronutrient.VITAMIN_C,
            "328" to Micronutrient.VITAMIN_D, // D2 + D3
            "323" to Micronutrient.VITAMIN_E, // alpha-tocopherol
            "430" to Micronutrient.VITAMIN_K, // phylloquinone
            "404" to Micronutrient.THIAMIN,
            "405" to Micronutrient.RIBOFLAVIN,
            "406" to Micronutrient.NIACIN,
            "415" to Micronutrient.VITAMIN_B6,
            "435" to Micronutrient.FOLATE, // DFE
            "418" to Micronutrient.VITAMIN_B12,
        )

        /**
         * A food from a search: nutrients are per 100 g there, and become per serving where one is
         * known (a packaged food's label, or a survey food's first real portion), else per 100 g.
         */
        fun toFood(item: JsonObject): Food? {
            val description = item.text("description")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val branded = item.text("dataType") == "Branded"
            val values = nutrientValues(item)
            fun get(vararg numbers: String) = numbers.firstNotNullOfOrNull { values[it] }
            val kcal = get("208", "958", "957") ?: values["268"]?.let { it / 4.184 } ?: return null
            if (kcal < 0 || kcal > 900) return null
            val per100 = Nutrients(
                calories = kcal,
                protein = get("203") ?: 0.0,
                carbs = get("205", "205.2") ?: 0.0,
                fat = get("204") ?: 0.0,
                fiber = get("291"),
                sugar = get("269", "269.3"),
                saturatedFat = get("606"),
                sodiumMg = get("307"),
                micros = micros(item),
            )
            val (serving, grams) = (if (branded) labelServing(item) else portion(item)) ?: ("100 g" to 100.0)
            val name = if (branded) readable(description) else description
            val brand = if (branded) (item.text("brandName") ?: item.text("brandOwner"))?.trim()?.takeIf { it.isNotEmpty() }?.let(::readable) else null
            return Food(
                name = name.take(160),
                nutrients = OpenFoodFacts.round(per100 * (grams / 100.0)),
                serving = serving,
                servingGrams = grams,
                brand = brand?.take(80),
                barcode = if (branded) item.text("gtinUpc")?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() } else null,
                source = FoodSource.USDA,
            )
        }

        /** Each nutrient's amount per 100 g, by number, in FoodData Central's own unit (only the first of a number counts). */
        private fun nutrientValues(item: JsonObject): Map<String, Double> {
            val out = LinkedHashMap<String, Double>()
            for (n in (item["foodNutrients"] as? JsonArray).orEmpty()) {
                val o = n as? JsonObject ?: continue
                val number = o.text("nutrientNumber") ?: continue
                val unit = o.text("unitName")?.uppercase()
                // Energy only in kilocalories (kilojoules have a number of their own, 268).
                if (number in ENERGY && unit != "KCAL") continue
                val value = o.number("value")?.takeIf { it >= 0 } ?: continue
                out.putIfAbsent(number, value)
            }
            return out
        }

        /**
         * The vitamins, minerals and finer fats, each from FoodData Central's unit (G, MG or UG) into
         * the app's own. Vitamin D from a label's international units (324) where there's no µg (328).
         */
        fun micros(item: JsonObject): Map<Micronutrient, Double> {
            val out = LinkedHashMap<Micronutrient, Double>()
            var vitaminDIu: Double? = null
            for (n in (item["foodNutrients"] as? JsonArray).orEmpty()) {
                val o = n as? JsonObject ?: continue
                val number = o.text("nutrientNumber") ?: continue
                val value = o.number("value")?.takeIf { it >= 0 } ?: continue
                val unit = o.text("unitName")?.uppercase()
                if (number == "324" && unit == "IU") {
                    if (vitaminDIu == null) vitaminDIu = value
                    continue
                }
                val micro = MICROS[number] ?: continue
                val grams = value * when (unit) {
                    "G" -> 1.0
                    "MG" -> 0.001
                    "UG", "MCG" -> 0.000_001
                    else -> continue
                }
                // More than the whole 100 g can't be right.
                if (grams <= 100) out.putIfAbsent(micro, grams * micro.fromGrams)
            }
            // 40 IU is 1 µg.
            vitaminDIu?.let { out.putIfAbsent(Micronutrient.VITAMIN_D, it / 40.0) }
            return out
        }

        /** A packaged food's serving from its label: "1 container (150 g)", or "30 g" without words for it. */
        private fun labelServing(item: JsonObject): Pair<String, Double>? {
            val size = item.number("servingSize")?.takeIf { it > 0 && it < 2000 } ?: return null
            val unit = when (item.text("servingSizeUnit")?.trim()?.lowercase()) {
                "g", "gm", "grm" -> "g"
                // A drink's millilitres are taken as grams.
                "ml", "mlt" -> "ml"
                else -> return null
            }
            val amount = "${amount(size)} $unit"
            val words = item.text("householdServingFullText")?.trim()?.takeIf { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }
                ?.let { if (it.none(Char::isLowerCase)) it.lowercase() else it }
            val label = if (words == null || words.length > 40 || words.equals(amount, ignoreCase = true)) amount else "$words ($amount)"
            return label to size
        }

        /** A survey food's first real portion, by its rank: "1 banana (126 g)". "Quantity not specified" isn't one. */
        private fun portion(item: JsonObject): Pair<String, Double>? =
            (item["foodMeasures"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .sortedBy { it.number("rank") ?: Double.MAX_VALUE }
                .firstNotNullOfOrNull { m ->
                    val text = m.text("disseminationText")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Quantity not specified", ignoreCase = true) }
                    val grams = m.number("gramWeight")?.takeIf { it > 0 && it < 2000 }
                    if (text == null || grams == null || text.length > 40) null else "$text (${amount(grams)} g)" to grams
                }

        private fun amount(v: Double): String = if (v == Math.floor(v)) v.roundToInt().toString() else ((v * 10).roundToInt() / 10.0).toString()

        /** Words in capitals read naturally ("GREEK YOGURT, PLAIN" is "Greek Yogurt, Plain"); anything with small letters is left as it is. */
        fun readable(text: String): String {
            if (text.any(Char::isLowerCase) || text.count(Char::isLetter) < 2) return text
            val out = StringBuilder(text.length)
            var start = true
            var word = 0
            for (i in text.indices) {
                val c = text[i]
                if (c.isLetter()) {
                    if (start) {
                        // Small words stay small, unless one starts the name.
                        val rest = text.substring(i).takeWhile { it.isLetter() }.lowercase()
                        out.append(if (word > 0 && rest in SMALL_WORDS) c.lowercaseChar() else c.uppercaseChar())
                        word++
                    } else {
                        out.append(c.lowercaseChar())
                    }
                    start = false
                } else {
                    out.append(c)
                    // A new word after a space or a mark; not after an apostrophe ("Joe's") or a digit ("12oz").
                    start = !c.isDigit() && c != '\'' && c != '’'
                }
            }
            return out.toString()
        }

        private val SMALL_WORDS = setOf("a", "an", "and", "or", "of", "with", "in", "on", "the", "for", "to", "by", "from")
        private val ENERGY = setOf("208", "957", "958")

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }?.takeIf { it.isFinite() }
    }
}
