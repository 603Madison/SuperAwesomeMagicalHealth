package com.health.app.ui.food

import com.health.api.AiStatus
import com.health.api.EstimateConfidence
import com.health.api.Food
import com.health.api.FoodEntryInput
import com.health.api.FoodPhotoEstimate
import com.health.api.FoodPhotoItem
import com.health.api.Meal
import com.health.api.Module
import com.health.api.Nutrients
import com.health.app.net.ServerException
import com.health.app.ui.Format
import kotlin.math.roundToInt

/** Whether the add-food screen offers a photo: the button, or one line pointing to Settings. */
enum class PhotoOffer { READY, SETTINGS }

/**
 * One food or drink on the review: the estimate as it came ([item]) and what the person made of
 * it. [amount] is grams (or ml), except with their own food that has no weight per serving, when
 * it's servings of that food.
 */
data class PhotoRow(
    val key: Int,
    val item: FoodPhotoItem,
    val name: String,
    val amount: String,
    /** For a row they added themselves: its calories. */
    val calories: String = "",
    val useMine: Boolean = false,
    val added: Boolean = false,
)

/** Food photo estimates' words and sums in plain Kotlin (the screens are in FoodPhoto.kt). */
object FoodPhotoText {
    fun notice(provider: String) =
        "The photo is sent to $provider to estimate what's in it. It isn't kept on this server or on $provider's side by this app, " +
            "and you can check and change everything before it's logged."

    const val ESTIMATES = "These are estimates from a photo, so they can be off. Check the amounts before you log them."
    const val ESTIMATES_PLAIN = "These are estimates from a photo, so they can be off. Check the foods and amounts before you log them."
    const val SETTINGS_LINE = "To estimate a meal from a photo, let the assistant see Food in Settings › AI assistant."

    /** Small print on the review: a photo shows fibre and the like, never vitamins. */
    const val NO_VITAMINS = "Vitamins and minerals can't be judged from a photo."

    /** On, said yes to, with Food allowed: the button. On, but not that: the line. Otherwise (or while viewing a share) nothing. */
    fun offer(status: AiStatus?, viewing: Boolean): PhotoOffer? = when {
        viewing || status == null || !status.available -> null
        status.consent?.acknowledged != true || Module.NUTRITION !in status.consent!!.modules -> PhotoOffer.SETTINGS
        else -> PhotoOffer.READY
    }

    fun rows(estimate: FoodPhotoEstimate): List<PhotoRow> =
        estimate.items.mapIndexed { i, item -> PhotoRow(i, item, item.name, amountText(item.grams)) }

    /** A row of their own, for something the photo missed. */
    fun added(key: Int): PhotoRow = PhotoRow(key, FoodPhotoItem("", 100.0, Nutrients(), EstimateConfidence.LOW), "", "100", added = true)

    private fun amountText(v: Double) = if (v % 1.0 == 0.0 || v >= 10) v.roundToInt().toString() else Format.compact(v)

    private fun amount(row: PhotoRow): Double = Format.parse(row.amount)?.takeIf { it.isFinite() && it > 0 } ?: 0.0

    /** The estimate's nutrients for [grams], in proportion to what it estimated for its own portion. */
    fun scaled(item: FoodPhotoItem, grams: Double): Nutrients = if (item.grams > 0) item.nutrients * (grams / item.grams) else Nutrients()

    /** How many servings of their own food a row is. */
    fun servings(row: PhotoRow, mine: Food): Double = mine.servingGrams?.takeIf { it > 0 }?.let { amount(row) / it } ?: amount(row)

    /** What a row holds now: their own food's, or the estimate at the row's amount. */
    fun nutrients(row: PhotoRow): Nutrients {
        val mine = row.item.match?.takeIf { row.useMine }
        return when {
            mine != null -> mine.nutrients * servings(row, mine)
            row.added -> Nutrients(calories = Format.parse(row.calories)?.coerceAtLeast(0.0) ?: 0.0)
            else -> scaled(row.item, amount(row))
        }
    }

    /** The rows that can be logged: named, with an amount. */
    fun ready(rows: List<PhotoRow>): List<PhotoRow> = rows.filter { it.name.isNotBlank() && amount(it) > 0 }

    fun total(rows: List<PhotoRow>): Nutrients = ready(rows).fold(Nutrients()) { sum, r -> sum + nutrients(r) }

    /** Switching to their own food (or back): the amount in its terms. */
    fun useMine(row: PhotoRow, on: Boolean): PhotoRow {
        val mine = row.item.match ?: return row
        val amount = when {
            on && mine.servingGrams == null -> "1"
            on -> row.amount
            else -> amountText(row.item.grams)
        }
        return row.copy(useMine = on, amount = amount)
    }

    /** What the amount is in: "g", "ml", or "× 1 bowl" for their own food without a weight. */
    fun unit(row: PhotoRow): String {
        val mine = row.item.match?.takeIf { row.useMine }
        return when {
            mine != null && mine.servingGrams == null -> "× ${mine.serving}"
            mine != null -> "g"
            else -> row.item.unit
        }
    }

    /** "A fair guess: assumed cooked weight". */
    fun confidence(item: FoodPhotoItem): String = item.confidence.label + if (item.assumption.isNotBlank()) ": ${item.assumption}" else ""

    /** "480 kcal · P 24 g · C 62 g · F 14 g". */
    fun numbers(n: Nutrients): String =
        "${Format.number(n.calories)} kcal · P ${Format.number(n.protein)} g · C ${Format.number(n.carbs)} g · F ${Format.number(n.fat)} g"

    /** How a row is logged: as their own food, or as the estimate with its portion as the serving. */
    fun input(row: PhotoRow, date: String, meal: Meal): FoodEntryInput {
        val mine = row.item.match?.takeIf { row.useMine }
        return if (mine != null) {
            FoodEntryInput(date, meal, servings(row, mine), food = mine)
        } else {
            FoodEntryInput(date, meal, 1.0, name = row.name.trim(), nutrients = nutrients(row), serving = "about ${amountText(amount(row))} ${row.item.unit}")
        }
    }

    /** A failed photo, in plain words. */
    fun error(e: Throwable): String {
        val code = (e as? ServerException)?.code
        val message = e.message?.takeIf { it.isNotBlank() }
        return when (code) {
            0 -> "Can't reach your server right now. Check your connection and try again."
            409 -> "The assistant isn't turned on on this server."
            403 -> message ?: SETTINGS_LINE
            429 -> message ?: "That's all the assistant can do for today. It's back tomorrow."
            413 -> "That photo is too big. Try another one."
            in 500..599 -> message ?: "The assistant couldn't look just now. Try again in a minute."
            null -> e.message ?: "That photo didn't work. Try another one."
            else -> message ?: "That photo didn't work. Try another one."
        }
    }
}
