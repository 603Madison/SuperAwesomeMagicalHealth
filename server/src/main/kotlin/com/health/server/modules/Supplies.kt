package com.health.server.modules

import com.health.api.ApiJson
import com.health.api.Supply
import com.health.api.SupplyAdjustment
import com.health.api.SupplyCategory
import com.health.api.SupplyReason
import com.health.api.SupplyStatus
import com.health.server.ApiError
import com.health.server.db.Database
import com.health.server.health.ProfileStore
import com.health.server.util.Random
import com.health.server.util.SecretBox
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pads, briefs, period products and the rest: kept sealed like medications (what someone keeps
 * on hand says a lot), counted down as the diary records them being used, and up when restocked.
 */
class SuppliesService(
    private val db: Database,
    private val box: SecretBox,
    private val entries: EntryStore,
    private val profiles: ProfileStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private fun context(userId: String, id: String) = "supply:$userId:$id"

    /** An adjustment as stored: [source] ties automatic ones to the diary entry that made them. */
    @Serializable
    private data class Logged(val adjustment: SupplyAdjustment, val source: String? = null)

    suspend fun list(userId: String, all: Boolean = false): List<Supply> = db.tx {
        query("SELECT id, data FROM supplies WHERE user_id = ?${if (all) "" else " AND active = 1"} ORDER BY created_at", userId) {
            it.string("id") to it.string("data")
        }
    }.mapNotNull { (id, data) -> open(userId, id, data) }

    suspend fun get(userId: String, id: String): Supply? = db.tx {
        one("SELECT data FROM supplies WHERE id = ? AND user_id = ?", id, userId) { it.string("data") }
    }?.let { open(userId, id, it) }

    suspend fun save(userId: String, input: Supply, id: String? = null): Supply {
        fun check(ok: Boolean, message: String) { if (!ok) throw ApiError.badRequest(message) }
        check(input.name.isNotBlank(), "Give it a name")
        check(input.onHand in 0..100_000, "On hand is 0 to 100,000")
        input.perPack?.let { check(it in 1..10_000, "A pack holds 1 to 10,000") }
        input.lowAt?.let { check(it in 0..10_000, "Warn at 0 to 10,000") }
        input.dryWeightG?.let { check(it > 0 && it <= 2000, "Dry weight is in grams, up to 2,000") }
        val clean = input.copy(
            name = input.name.trim().take(80),
            brand = input.brand?.trim()?.take(80)?.ifBlank { null },
            size = input.size?.trim()?.take(40)?.ifBlank { null },
            absorbency = input.absorbency?.trim()?.take(60)?.ifBlank { null },
            notes = input.notes?.trim()?.take(2000)?.ifBlank { null },
            id = null,
        )
        val supplyId = id ?: Random.id()
        val sealed = box.seal(ApiJson.encodeToString(Supply.serializer(), clean), context(userId, supplyId))
        db.tx {
            if (id != null) {
                if (update("UPDATE supplies SET data = ?, active = ?, updated_at = ? WHERE id = ? AND user_id = ?", sealed, clean.active, clock(), id, userId) == 0) {
                    throw ApiError.notFound("No such supply")
                }
            } else {
                update("INSERT INTO supplies (id, user_id, data, active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)", supplyId, userId, sealed, clean.active, clock(), clock())
            }
        }
        return clean.copy(id = supplyId)
    }

    suspend fun delete(userId: String, id: String) {
        if (db.tx { update("DELETE FROM supplies WHERE id = ? AND user_id = ?", id, userId) } == 0) throw ApiError.notFound("No such supply")
    }

    /** A change made by hand: restocked, counted (sets the count), or given away. */
    suspend fun adjust(userId: String, id: String, input: SupplyAdjustment): Supply {
        val supply = get(userId, id) ?: throw ApiError.notFound("No such supply")
        if (input.delta !in -100_000..100_000) throw ApiError.badRequest("That's a lot: up to 100,000")
        val at = input.at.takeIf { it > 0 } ?: clock()
        val (newCount, delta) = when (input.reason) {
            SupplyReason.COUNTED -> {
                if (input.delta < 0) throw ApiError.badRequest("A count can't be below 0")
                input.delta to (input.delta - supply.onHand)
            }
            SupplyReason.RESTOCKED -> (supply.onHand + input.delta.coerceAtLeast(0)) to input.delta.coerceAtLeast(0)
            SupplyReason.GAVE_AWAY, SupplyReason.USED -> {
                val used = -kotlin.math.abs(input.delta)
                (supply.onHand + used).coerceAtLeast(0) to used
            }
        }
        record(userId, SupplyAdjustment(at, delta, input.reason, input.note?.trim()?.take(500)?.ifBlank { null }, supplyId = id), null)
        return save(userId, supply.copy(onHand = newCount), id)
    }

    /**
     * Brings the counts in line with what a diary entry ([source]) used: [uses] is supply id to
     * how many. What the entry used before is given back first, so editing or deleting an entry
     * (an empty [uses]) puts things right.
     */
    suspend fun syncUse(userId: String, source: String, uses: Map<String, Int>, at: Long) {
        val zone = profiles.days(userId)
        val previous = entries.list(userId, EntryKind.SUPPLY_LOG, LocalDate.of(2000, 1, 1), LocalDate.of(2999, 1, 1), Logged.serializer())
            .filter { it.value.source == source }
        val before = previous.groupBy { it.value.adjustment.supplyId!! }.mapValues { (_, l) -> -l.sumOf { it.value.adjustment.delta } }
        if (before == uses.filterValues { it > 0 }) return
        previous.forEach { entries.delete(userId, EntryKind.SUPPLY_LOG, it.id) }
        (before.keys + uses.keys).forEach { supplyId ->
            val change = (before[supplyId] ?: 0) - (uses[supplyId] ?: 0)
            val supply = get(userId, supplyId) ?: return@forEach
            if (!supply.reusable && change != 0) save(userId, supply.copy(onHand = (supply.onHand + change).coerceAtLeast(0)), supplyId)
        }
        uses.filterValues { it > 0 }.forEach { (supplyId, count) ->
            if (get(userId, supplyId)?.reusable == false) {
                entries.put(userId, EntryKind.SUPPLY_LOG, null, zone.dateOf(at), at, Logged(SupplyAdjustment(at, -count, SupplyReason.USED, supplyId = supplyId), source), Logged.serializer())
            }
        }
    }

    suspend fun log(userId: String, id: String, days: Int): List<SupplyAdjustment> {
        val zone = profiles.days(userId)
        val to = zone.today(clock()).plusDays(1)
        val from = to.minusDays(days.coerceIn(1, 3650).toLong())
        return entries.list(userId, EntryKind.SUPPLY_LOG, from, to, Logged.serializer())
            .filter { it.value.adjustment.supplyId == id }
            .map { it.value.adjustment.copy(id = it.id) }
            .reversed()
    }

    /** Each active supply with how fast it's used and how long it'll last. */
    suspend fun status(userId: String): List<SupplyStatus> {
        val list = list(userId)
        if (list.isEmpty()) return emptyList()
        val zone = profiles.days(userId)
        val today = zone.today(clock())
        val logs = entries.list(userId, EntryKind.SUPPLY_LOG, today.minusDays(365), today.plusDays(1), Logged.serializer())
        return list.map { s ->
            val mine = logs.filter { it.value.adjustment.supplyId == s.id }
            val used14 = mine.filter { it.value.adjustment.reason == SupplyReason.USED && !it.date.isBefore(today.minusDays(13)) }
                .sumOf { -it.value.adjustment.delta }
            val perDay = (used14 / 14.0).takeIf { used14 > 0 }
            val daysLeft = perDay?.let { floor(s.onHand / it).toInt() }
            statusOf(s, perDay, daysLeft, mine.lastOrNull { it.value.adjustment.reason == SupplyReason.RESTOCKED }?.date?.toString())
        }
    }

    suspend fun low(userId: String, categories: Set<SupplyCategory>): List<SupplyStatus> =
        status(userId).filter { it.low && it.supply.kind.category in categories }

    private fun open(userId: String, id: String, data: String): Supply? =
        runCatching { ApiJson.decodeFromString(Supply.serializer(), box.open(data, context(userId, id))).copy(id = id) }.getOrNull()

    private suspend fun record(userId: String, adjustment: SupplyAdjustment, source: String?) {
        val zone = profiles.days(userId)
        entries.put(userId, EntryKind.SUPPLY_LOG, null, zone.dateOf(adjustment.at), adjustment.at, Logged(adjustment, source), Logged.serializer())
    }

    companion object {
        /** Low: at the person's own mark, or with about a week (or less) left; never for reusable things. */
        fun statusOf(s: Supply, perDay: Double?, daysLeft: Int?, lastRestocked: String?): SupplyStatus {
            val low = !s.reusable && when {
                s.lowAt != null -> s.onHand <= s.lowAt!!
                perDay != null -> s.onHand <= ceil(perDay * 7)
                else -> s.onHand == 0
            }
            return SupplyStatus(s, perDay?.let { (it * 10).toLong() / 10.0 }, daysLeft, low, lastRestocked)
        }
    }
}
