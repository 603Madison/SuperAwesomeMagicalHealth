package com.health.app.devices.colmi

import com.health.app.devices.ble.GattConnection
import com.health.app.devices.ble.Notification
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime

/** A conversation with a connected ring: one request at a time, each read until its reply is whole. */
class ColmiRing(private val gatt: GattConnection) {
    var hasBigData = false
        private set

    suspend fun start() {
        gatt.enableNotifications(ColmiProtocol.SERVICE, ColmiProtocol.NOTIFY)
        hasBigData = gatt.hasService(ColmiProtocol.BIG_SERVICE)
        if (hasBigData) runCatching { gatt.enableNotifications(ColmiProtocol.BIG_SERVICE, ColmiProtocol.BIG_NOTIFY) }.onFailure { hasBigData = false }
    }

    suspend fun setTime(now: LocalDateTime) {
        request(ColmiProtocol.setTime(now), ColmiProtocol.CMD_SET_TIME, timeoutMs = 3_000) { true }
    }

    suspend fun battery(): ColmiProtocol.Battery? {
        var result: ColmiProtocol.Battery? = null
        request(ColmiProtocol.battery(), ColmiProtocol.CMD_BATTERY) { p ->
            result = ColmiProtocol.parseBattery(p)
            true
        }
        return result
    }

    suspend fun firmware(): String? = runCatching {
        gatt.read(ColmiProtocol.DEVICE_INFO, ColmiProtocol.FIRMWARE_REVISION)?.toString(Charsets.UTF_8)?.trim()?.ifBlank { null }
    }.getOrNull()

    /** Has the ring take a heart-rate reading every [intervalMinutes] by itself. */
    suspend fun enableHeartRateLog(intervalMinutes: Int = 5) {
        request(ColmiProtocol.writeHeartRateSettings(true, intervalMinutes), ColmiProtocol.CMD_HEART_RATE_SETTINGS, timeoutMs = 3_000) { true }
    }

    suspend fun heartRates(date: LocalDate): HeartRateLog? {
        val parser = HeartRateLogParser()
        request(ColmiProtocol.heartRateLog(date), ColmiProtocol.CMD_HEART_RATE_LOG, timeoutMs = 10_000) { parser.feed(it) }
        return parser.result()
    }

    suspend fun steps(dayOffset: Int): List<StepSlot> {
        val parser = StepsParser()
        request(ColmiProtocol.steps(dayOffset), ColmiProtocol.CMD_STEPS, timeoutMs = 10_000) { parser.feed(it) }
        return parser.slots
    }

    /** The whole sleep reply from the big-data service, or null if the ring has none to give. */
    suspend fun sleep(): ByteArray? {
        if (!hasBigData) return null
        drain(gatt.notifications)
        val assembler = BigDataAssembler(ColmiProtocol.BIG_DATA_SLEEP)
        gatt.write(ColmiProtocol.BIG_SERVICE, ColmiProtocol.BIG_WRITE, ColmiProtocol.sleepRequest())
        return withTimeoutOrNull(15_000) {
            for (n in gatt.notifications) {
                if (n.characteristic != ColmiProtocol.BIG_NOTIFY) continue
                assembler.feed(n.value)?.let { return@withTimeoutOrNull it }
            }
            null
        }
    }

    /**
     * Sends [packet] and hands each reply with [command] to [take] until it says the reply is
     * complete, or [timeoutMs] passes (whatever arrived is kept: rings can be terse).
     */
    private suspend fun request(packet: ByteArray, command: Int, timeoutMs: Long = 6_000, take: (ByteArray) -> Boolean) {
        drain(gatt.notifications)
        gatt.write(ColmiProtocol.SERVICE, ColmiProtocol.WRITE, packet)
        withTimeoutOrNull(timeoutMs) {
            for (n in gatt.notifications) {
                if (n.characteristic != ColmiProtocol.NOTIFY || ColmiProtocol.command(n.value) != command) continue
                if (take(n.value)) return@withTimeoutOrNull
            }
        }
    }

    private fun drain(channel: ReceiveChannel<Notification>) {
        while (channel.tryReceive().isSuccess) Unit
    }
}
