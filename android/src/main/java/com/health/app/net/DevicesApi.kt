package com.health.app.net

import com.health.api.ApiJson
import com.health.api.DeviceChoices
import com.health.api.Metric
import com.health.api.MetricChoice
import kotlinx.serialization.builtins.serializer

/*
 * Where more than one device measures something, the server works out its own number from them;
 * the person can instead pick one device for a reading, and one for sleep (GET/PUT
 * /api/devices/choices, shared/…/Devices.kt). These are always the person's own choices: call them
 * on Container.api, never the view-aware Container.ui, so they're never sent with X-SAMH-View.
 */

suspend fun ServerApi.deviceChoices(): DeviceChoices = get("${base()}/api/devices/choices", DeviceChoices.serializer())

/** Saves [choices] (whatever the server answers with, they're what's kept). */
suspend fun ServerApi.saveDeviceChoices(choices: DeviceChoices): DeviceChoices {
    send("PUT", "${base()}/api/devices/choices", ApiJson.encodeToString(DeviceChoices.serializer(), choices), Unit.serializer())
    return choices
}

/** These choices with [metric] from [deviceId] only, or (null) combined again. */
fun DeviceChoices.choosing(metric: Metric, deviceId: String?): DeviceChoices {
    val others = choices.filter { it.metric != metric }
    return copy(choices = if (deviceId == null) others else others + MetricChoice(metric, deviceId))
}

/** These choices with sleep from [deviceId] only, or (null) combined again. */
fun DeviceChoices.choosingSleep(deviceId: String?): DeviceChoices = copy(sleepDeviceId = deviceId)

/** The device chosen for [metric], or null where they're combined. */
fun DeviceChoices.chosenFor(metric: Metric): String? = choices.firstOrNull { it.metric == metric }?.deviceId
