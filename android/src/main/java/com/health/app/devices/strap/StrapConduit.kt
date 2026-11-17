package com.health.app.devices.strap

import com.health.api.IngestBatch
import com.health.app.sync.Collected
import com.health.app.sync.Conduit
import com.health.app.sync.Outbox
import com.health.app.sync.SyncStore
import com.health.app.sync.chunked

/**
 * A heart rate strap's readings on their way to the server. They're gathered while recording
 * ([StrapRecorder]) and wait on the phone ([StrapStore]); a sync sends what's waiting, as the
 * strap (a [com.health.api.DeviceKind.HEART_RATE_STRAP]), and they're let go once the server (or
 * the outbox) has them.
 */
class StrapConduit(private val store: StrapStore, private val syncStore: SyncStore) : Conduit {
    override val id = ID
    override val title = "Heart rate strap"

    override suspend fun ready(): Boolean = store.waiting(syncStore.cursor(ID)).isNotEmpty()

    override suspend fun collect(since: Long?): Collected {
        val waiting = store.waiting(since)
        if (waiting.isEmpty()) return Collected(emptyList(), since)
        return Collected(batches(waiting), waiting.maxOf { it.seq })
    }

    private fun batches(waiting: List<PendingSample>): List<IngestBatch> =
        waiting.groupBy { it.address }.map { (address, list) -> IngestBatch(store.device(address), samples = list.map { it.sample }) }

    /**
     * Signing out: what's still waiting stays with [account] (in its outbox, sent once the same
     * person signs in again) when [keep], else it goes; and the strap is forgotten.
     */
    fun signedOut(account: String?, keep: Boolean, outbox: Outbox) {
        val waiting = store.waiting(syncStore.cursor(ID))
        if (keep && account != null) batches(waiting).flatMap { it.chunked() }.forEach { outbox.add(account, it) }
        store.clear()
    }

    companion object {
        const val ID = "heart_rate_strap"
    }
}
