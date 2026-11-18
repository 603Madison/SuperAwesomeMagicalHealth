package com.health.server.food

import com.health.api.DataPath
import com.health.api.Food
import com.health.server.ApiError
import com.health.server.Services
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

/**
 * Food from outside the server, for a search or a barcode. USDA FoodData Central is the source
 * (everyday foods, then packaged ones): its copy on this server where the admin keeps one, else its
 * online service with the admin's key. Open Food Facts is a fallback the admin may switch on: asked
 * only when USDA can't help (no key, paused, failing, or too little found), and in English. Each is
 * asked only where the server has it on and the person hasn't paused it. Logs never carry the
 * words searched.
 */
class FoodFinder(private val services: Services) {
    /** What the food databases found, after the person's own foods; [problem] says why there's nothing to find, in plain words. */
    data class Found(val online: List<Food>, val problem: String? = null)

    /** One source's answer: its foods, or that it failed. */
    private class Answer(val foods: List<Food>, val failed: Boolean = false)

    /** Why USDA can't be asked for someone. */
    private enum class NoUsda { OFF, NO_KEY, PAUSED, NOT_READY, NO_PACKAGED }

    /** How USDA is asked for someone: the copy on this server, or the online service with the key. */
    private sealed interface Usda {
        data object Local : Usda
        data class Online(val key: String) : Usda
    }

    /**
     * How USDA may be asked for [userId] (null, with why not): the copy on this server where it's
     * chosen and ready, else the online service with a key (also while the copy isn't ready yet).
     * The third part says the copy is chosen but not here yet.
     */
    private suspend fun usda(userId: String): Triple<Usda?, NoUsda?, Boolean> {
        val server = services.settings.get()
        if (!server.usdaFoods) return Triple(null, NoUsda.OFF, false)
        if (!services.privacy.allows(userId, DataPath.USDA_FOODS)) return Triple(null, NoUsda.PAUSED, false)
        val waiting = server.usdaLocal.use && !services.usdaLocal.ready
        if (server.usdaLocal.use && !waiting) return Triple(Usda.Local, null, false)
        val key = services.settings.usdaApiKey().ifBlank { return Triple(null, if (waiting) NoUsda.NOT_READY else NoUsda.NO_KEY, waiting) }
        return Triple(Usda.Online(key), null, waiting)
    }

    suspend fun search(userId: String, text: String, mine: List<Food> = emptyList()): Found = coroutineScope {
        val query = text.trim()
        if (query.length < 2) return@coroutineScope Found(emptyList())
        val (usda, why, waiting) = usda(userId)
        val fallback = services.foodDatabaseFor(userId)
        val answers = mutableListOf<Answer>()
        when (usda) {
            // On this server: nothing leaves it.
            Usda.Local -> {
                answers += ask("USDA's food list on this server") { services.usdaLocal.search(query, UsdaKind.EVERYDAY, UsdaFoods.Kind.EVERYDAY.pageSize) }
                if (services.usdaLocal.packaged()) answers += ask("USDA's food list on this server") { services.usdaLocal.search(query, UsdaKind.PACKAGED, UsdaFoods.Kind.PACKAGED.pageSize) }
            }
            is Usda.Online -> {
                val everyday = async { ask("USDA FoodData Central everyday") { services.usdaFoods.search(query, UsdaFoods.Kind.EVERYDAY, usda.key) } }
                val packaged = async { ask("USDA FoodData Central packaged") { services.usdaFoods.search(query, UsdaFoods.Kind.PACKAGED, usda.key) } }
                answers += everyday.await()
                answers += packaged.await()
            }
            null -> Unit
        }
        // Open Food Facts only fills in where USDA found too little (or couldn't be asked).
        if (fallback && merge(mine, answers.map { it.foods }).size < FILL_BELOW) {
            answers += ask("Open Food Facts") { services.foodDatabase.search(query) }
        }
        val problem = when {
            answers.isEmpty() -> why?.let(::searchMessage)
            answers.all { it.failed } -> PROBLEM
            waiting -> NOT_READY_YET
            else -> null
        }
        Found(merge(mine, answers.map { it.foods }), problem)
    }

    /**
     * A packaged food by its barcode: USDA's packaged foods (an exact match only; on this server
     * where its copy is kept there, with packaged foods), then Open Food Facts where it's switched
     * on. Null when neither knows it; an [ApiError] (409) when neither can be asked, saying why.
     */
    suspend fun barcode(userId: String, code: String): Food? {
        val (found, reason, _) = usda(userId)
        // A copy of everyday foods only has no barcodes.
        val noPackaged = found == Usda.Local && !services.usdaLocal.packaged()
        val usda = if (noPackaged) null else found
        val why = if (noPackaged) NoUsda.NO_PACKAGED else reason
        when (usda) {
            Usda.Local -> ask("USDA's food list on this server") { listOfNotNull(services.usdaLocal.barcode(code)) }.foods.firstOrNull()?.let { return it }
            is Usda.Online -> ask("USDA FoodData Central barcode") { listOfNotNull(services.usdaFoods.barcode(code, usda.key)) }.foods.firstOrNull()?.let { return it }
            null -> Unit
        }
        if (services.foodDatabaseFor(userId)) {
            return ask("Open Food Facts barcode") { listOfNotNull(services.foodDatabase.barcode(code)) }.foods.firstOrNull()
        }
        if (usda == null && why != null) throw ApiError.conflict(barcodeMessage(why))
        return null
    }

    private suspend fun ask(source: String, fetch: suspend () -> List<Food>): Answer = try {
        Answer(fetch())
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        // Only the status or the kind of failure: never the words searched or the barcode.
        log.warn("{} lookup failed: {}", source, (e as? com.health.server.util.HttpException)?.let { "HTTP ${it.code}" } ?: e.javaClass.simpleName)
        Answer(emptyList(), failed = true)
    }

    companion object {
        private val log = LoggerFactory.getLogger("samh.food")

        /** Fewer foods than this from USDA, and Open Food Facts (where it's on) fills in. */
        const val FILL_BELOW = 5
        const val PROBLEM = "The food database didn't answer; try again in a moment"

        /** Said beside what was found elsewhere while the copy on this server is on its way. */
        const val NOT_READY_YET = "USDA's food list isn't on this server yet, so these come from elsewhere for now."

        private fun searchMessage(why: NoUsda): String = when (why) {
            NoUsda.NO_KEY -> "Food search needs a free USDA key from your server's admin. Your own foods and Mealie still work."
            NoUsda.OFF -> "Food search is turned off on this server. Your own foods and Mealie still work."
            NoUsda.PAUSED -> "USDA food search is paused in Where your data goes. Your own foods and Mealie still work."
            NoUsda.NOT_READY, NoUsda.NO_PACKAGED -> "USDA's food list isn't on this server yet. Your own foods and Mealie still work."
        }

        private fun barcodeMessage(why: NoUsda): String = when (why) {
            NoUsda.NO_KEY -> "Scanning barcodes needs a free USDA key from your server's admin. You can still add the food yourself."
            NoUsda.OFF -> "Barcode lookups are turned off on this server. You can still add the food yourself."
            NoUsda.PAUSED -> "USDA food search is paused in Where your data goes. You can still add the food yourself."
            NoUsda.NOT_READY -> "USDA's food list isn't on this server yet. You can still add the food yourself."
            NoUsda.NO_PACKAGED -> "Scanning barcodes needs USDA's packaged foods on this server; your admin can add them. You can still add the food yourself."
        }

        /**
         * [groups] in order, each food once: one with the barcode of a food before it (leading zeros
         * aside) or the same name and brand (in any case) is left out, as are copies of the
         * person's own foods ([mine], which come first and aren't in the answer).
         */
        fun merge(mine: List<Food>, groups: List<List<Food>>): List<Food> {
            val barcodes = HashSet<String>()
            val names = HashSet<String>()
            fun seen(f: Food): Boolean {
                val code = f.barcode?.trimStart('0')?.takeIf { it.isNotEmpty() }
                val name = f.name.trim().lowercase() + "\u0000" + f.brand?.trim()?.lowercase().orEmpty()
                val known = (code != null && code in barcodes) || name in names
                code?.let(barcodes::add)
                names += name
                return known
            }
            mine.forEach(::seen)
            return groups.flatten().filterNot(::seen)
        }
    }
}
