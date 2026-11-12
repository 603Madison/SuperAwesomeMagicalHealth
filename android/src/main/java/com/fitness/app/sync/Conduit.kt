package com.fitness.app.sync

import com.fitness.api.IngestBatch

/**
 * A way data reaches the app from a wearable: a ring over Bluetooth, Health Connect, a
 * Gadgetbridge export. The app is the conduit from it to the server.
 */
interface Conduit {
    /** Stable, for remembering where each one got to: "colmi", "health_connect", "gadgetbridge". */
    val id: String

    val title: String

    /** Whether it's set up (a ring paired, a file chosen, permissions granted). */
    suspend fun ready(): Boolean

    /**
     * What's new since [since] (epoch ms; null the first time), as batches for the server, and
     * where to start next time. Overlapping what was sent before is fine: the server replaces it.
     */
    suspend fun collect(since: Long?): Collected
}

data class Collected(val batches: List<IngestBatch>, val cursor: Long, val note: String? = null)

/** How many readings a batch carries, so the server's limit (20,000) is never reached. */
const val MAX_SAMPLES_PER_BATCH = 5_000

/** [this] in parts of at most [max] samples; sleep and workouts go with the first. */
fun IngestBatch.chunked(max: Int = MAX_SAMPLES_PER_BATCH): List<IngestBatch> {
    if (samples.size <= max) return listOf(this)
    return samples.chunked(max).mapIndexed { i, part ->
        if (i == 0) copy(samples = part) else IngestBatch(device = device, samples = part)
    }
}

val IngestBatch.isEmpty: Boolean get() = samples.isEmpty() && sleep.isEmpty() && workouts.isEmpty()
