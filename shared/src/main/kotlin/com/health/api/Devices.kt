package com.health.api

import kotlinx.serialization.Serializable

/*
 * When more than one device measures the same thing (a ring and a watch, say), the server works out
 * its own number from all of them, and says how: each device's value, its weight, and the result.
 * The person can instead pick one device for a reading (GET/PUT /api/devices/choices).
 *
 * How each kind is combined (server/…/insights/Combine.kt):
 * - counts (steps, distance, calories, floors): for each 15 minutes, the device that counted most,
 *   so the same walk isn't counted twice and a device taken off doesn't lose the steps;
 * - heart rate: merged in time (each 5 minutes, the devices' average), so one that samples more
 *   often doesn't outweigh the other; resting, lowest and highest from the merged line;
 * - HRV, blood oxygen, breathing, skin temperature, stress: each device's own night (or day)
 *   value, weighted by how much it measured and by how well it suits that reading (a finger at
 *   night, a wrist in the day); where devices measure differently (HRV, skin temperature), each is
 *   compared with its own usual first, so a wrist and a finger can be combined fairly;
 * - sleep: the timing the devices agree on (bedtime and waking averaged where they're close),
 *   the stages from the one that tells them apart, minutes asleep from both.
 */

/** One device's part in a combined value. */
@Serializable
data class SourceValue(
    val deviceId: String,
    /** "Colmi R02", "Pebble Time 2". */
    val device: String,
    val kind: DeviceKind? = null,
    val value: Double,
    /** Its share of the result, 0–1 (1 for a device the person chose, 0 for one left out). */
    val weight: Double,
    /** How many readings it had. */
    val readings: Int = 0,
    /** Why it counted for less, or not at all: "only part of the night", "far from its usual". */
    val note: String? = null,
)

/** A number more than one device measured, and the app's own from them. */
@Serializable
data class CombinedValue(
    val metric: Metric,
    val value: Double,
    val sources: List<SourceValue>,
    /** In plain words: "The average of both, weighted to the ring at night." */
    val how: String,
    /** Set when the person chose one device for this reading. */
    val chosenDeviceId: String? = null,
)

/** A night more than one device recorded. */
@Serializable
data class CombinedSleep(
    val sources: List<SleepSource>,
    val how: String,
    /** The device whose stages are shown. */
    val stagesFrom: String? = null,
)

@Serializable
data class SleepSource(val deviceId: String, val device: String, val start: Long, val end: Long, val asleepMinutes: Int, val staged: Boolean)

/** Which device to use for a reading: null [deviceId] (or no entry) combines them all. */
@Serializable
data class MetricChoice(val metric: Metric, val deviceId: String? = null)

/** The person's choices (GET/PUT /api/devices/choices): one device per reading, and one for sleep ([sleepDeviceId]). */
@Serializable
data class DeviceChoices(val choices: List<MetricChoice> = emptyList(), val sleepDeviceId: String? = null)
