package com.health.app.ui.devices

import com.health.api.DaySummary
import com.health.api.Device
import com.health.api.DeviceChoices
import com.health.api.Metric
import com.health.app.net.chosenFor

/** One device that measures a reading. */
data class OverlapDevice(val id: String, val name: String)

/** A reading more than one device measures ([metric], or sleep when null), and what the person uses for it. */
data class Overlap(
    val metric: Metric?,
    val devices: List<OverlapDevice>,
    /** The device chosen; null combines them. */
    val chosen: String? = null,
) {
    val label: String get() = metric?.label ?: "Sleep"

    /** The reading inside a sentence: "heart rate variability", "sleep". */
    val reading: String get() = label.replaceFirstChar { it.lowercase() }

    /** "Combined", or "Only" and the chosen device's name. */
    val current: String get() = chosen?.let { id -> "Only ${devices.firstOrNull { it.id == id }?.name ?: "one device"}" } ?: "Combined"
}

object Overlaps {
    /**
     * The readings that more than one device measured in [days] (the last day or two), in the
     * readings' own order with sleep last, each with the person's [choices]. A reading the person
     * chose a device for stays listed, so the choice can be undone, even when the devices haven't
     * overlapped lately; [known] names devices the days don't mention.
     */
    fun of(days: List<DaySummary>, choices: DeviceChoices, known: List<Device>): List<Overlap> {
        val names = known.associate { it.id to it.name }
        val byMetric = sortedMapOf<Metric, LinkedHashMap<String, String>>()
        for (value in days.flatMap { it.combined }) {
            val devices = byMetric.getOrPut(value.metric) { LinkedHashMap() }
            value.sources.forEach { devices.putIfAbsent(it.deviceId, it.device) }
        }
        for (choice in choices.choices) {
            val id = choice.deviceId ?: continue
            byMetric.getOrPut(choice.metric) { LinkedHashMap() }.putIfAbsent(id, names[id] ?: "A device you chose")
        }
        val readings = byMetric.mapNotNull { (metric, devices) ->
            val chosen = choices.chosenFor(metric)
            if (devices.size < 2 && chosen == null) null else Overlap(metric, devices.map { OverlapDevice(it.key, it.value) }, chosen)
        }
        val sleepDevices = LinkedHashMap<String, String>()
        days.flatMap { day -> listOfNotNull(day.sleepSummary?.combined) + day.naps.mapNotNull { it.combined } }
            .flatMap { it.sources }
            .forEach { sleepDevices.putIfAbsent(it.deviceId, it.device) }
        choices.sleepDeviceId?.let { sleepDevices.putIfAbsent(it, names[it] ?: "A device you chose") }
        val sleep = if (sleepDevices.size < 2 && choices.sleepDeviceId == null) null else Overlap(null, sleepDevices.map { OverlapDevice(it.key, it.value) }, choices.sleepDeviceId)
        return readings + listOfNotNull(sleep)
    }
}
