package com.health.server.health

import com.health.api.DeviceChoices
import com.health.server.ApiError
import com.health.server.insights.Combine
import com.health.server.insights.DayService
import com.health.server.modules.UserSettingsStore

/**
 * Which device to use for a reading, where the person would rather not combine them
 * (GET/PUT /api/devices/choices). Kept sealed with the person's other settings.
 */
class DeviceChoicesService(private val settings: UserSettingsStore, private val records: Records, private val days: DayService) {
    suspend fun get(userId: String): DeviceChoices = settings.get(userId, KEY, DeviceChoices.serializer()) ?: DeviceChoices()

    suspend fun put(userId: String, input: DeviceChoices): DeviceChoices {
        val known = records.deviceRefs(userId).keys
        input.choices.forEach { c ->
            if (c.metric !in Combine.CHOOSABLE) throw ApiError.badRequest("${c.metric.label} comes from one device at a time already")
            if (c.deviceId != null && c.deviceId !in known) throw ApiError.badRequest("No such device")
        }
        if (input.sleepDeviceId != null && input.sleepDeviceId !in known) throw ApiError.badRequest("No such device")
        // One device per reading (the last given wins); no device means combining, which needs no entry.
        val clean = DeviceChoices(
            choices = input.choices.associateBy { it.metric }.values.filter { it.deviceId != null }.sortedBy { it.metric.ordinal },
            sleepDeviceId = input.sleepDeviceId,
        )
        val before = get(userId)
        if (clean == before) return before
        val saved = settings.put(userId, KEY, clean, DeviceChoices.serializer())
        // Every day is worked out from the choices: work them out again.
        days.changedAll(userId)
        return saved
    }

    /** A device was forgotten: choices of it go back to combining. */
    suspend fun deviceDeleted(userId: String, deviceId: String) {
        val before = get(userId)
        val after = DeviceChoices(before.choices.filter { it.deviceId != deviceId }, before.sleepDeviceId.takeIf { it != deviceId })
        if (after != before) settings.put(userId, KEY, after, DeviceChoices.serializer())
    }

    companion object {
        const val KEY = "devices.choices"
    }
}
