package com.health.app.devices.colmi

import com.health.app.attempt
import com.health.app.devices.ble.GattConnection
import com.health.app.devices.ble.Notification
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime

/** How a ring answered a request. */
@Serializable
enum class Answer {
    /** The whole reply came. */
    COMPLETE,

    /** Some of it came before the time ran out (rings can be terse): what came is kept. */
    PARTIAL,

    /** The ring said it can't (the reply's error flag). */
    REFUSED,

    /** Nothing came back in time: older rings and firmwares don't answer what they don't do. */
    SILENT,
    ;

    /** Whether the ring said anything at all. */
    val answered: Boolean get() = this == COMPLETE || this == PARTIAL
}

/** What a request for a log brought: how the ring answered, and the log, if it had one. */
data class Reply<T>(val answer: Answer, val value: T?)

/** A conversation with a connected ring: one request at a time, each read until its reply is whole. */
class ColmiRing(private val gatt: GattConnection) {
    var hasBigData = false
        private set

    suspend fun start() {
        gatt.enableNotifications(ColmiProtocol.SERVICE, ColmiProtocol.NOTIFY)
        hasBigData = gatt.hasService(ColmiProtocol.BIG_SERVICE)
        if (hasBigData) attempt { gatt.enableNotifications(ColmiProtocol.BIG_SERVICE, ColmiProtocol.BIG_NOTIFY) }.onFailure { hasBigData = false }
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

    suspend fun firmware(): String? = attempt {
        gatt.read(ColmiProtocol.DEVICE_INFO, ColmiProtocol.FIRMWARE_REVISION)?.toString(Charsets.UTF_8)?.trim()?.ifBlank { null }
    }.getOrNull()

    /** Has the ring take a heart-rate reading every [intervalMinutes] by itself. */
    suspend fun enableHeartRateLog(intervalMinutes: Int = 5) {
        request(ColmiProtocol.writeHeartRateSettings(true, intervalMinutes), ColmiProtocol.CMD_HEART_RATE_SETTINGS, timeoutMs = 3_000) { true }
    }

    /** Turns on one of the ring's own measurements ([packet] from ColmiProtocol.write…Settings). */
    suspend fun enableMeasurement(packet: ByteArray): Answer =
        request(packet, ColmiProtocol.command(packet), timeoutMs = 3_000) { true }

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

    /** Today's stress log, a reading each half hour. */
    suspend fun stress(): Reply<HalfHourLog> = halfHourLog(ColmiProtocol.stressLog(), ColmiProtocol.CMD_STRESS_LOG)

    /** The HRV log of the day [daysAgo] days ago, a reading each half hour. */
    suspend fun hrv(daysAgo: Int): Reply<HalfHourLog> = halfHourLog(ColmiProtocol.hrvLog(daysAgo), ColmiProtocol.CMD_HRV_LOG)

    private suspend fun halfHourLog(packet: ByteArray, command: Int): Reply<HalfHourLog> {
        val parser = HalfHourLogParser(command)
        val answer = request(packet, command, timeoutMs = 10_000, firstReplyMs = FIRST_REPLY_MS) { parser.feed(it) }
        return Reply(answer, parser.result())
    }

    /** The whole sleep reply from the big-data service, or null if the ring has none to give. */
    suspend fun sleep(): ByteArray? = bigData(ColmiProtocol.BIG_DATA_SLEEP, ColmiProtocol.sleepRequest(), firstReplyMs = 15_000).value

    /** The blood oxygen the ring holds (the last week or so), as one reply. */
    suspend fun spo2(): Reply<ByteArray> = bigData(ColmiProtocol.BIG_DATA_SPO2, ColmiProtocol.spo2Request())

    /** The skin temperature the ring holds, as one reply (only some rings keep it: ColmiProtocol.measuresTemperature). */
    suspend fun temperature(): Reply<ByteArray> = bigData(ColmiProtocol.BIG_DATA_TEMPERATURE, ColmiProtocol.temperatureRequest())

    /** Sends a big-data [request] and puts the [type] reply together from its notifications. */
    private suspend fun bigData(type: Int, request: ByteArray, firstReplyMs: Long = FIRST_REPLY_MS, timeoutMs: Long = 15_000): Reply<ByteArray> {
        if (!hasBigData) return Reply(Answer.SILENT, null)
        drain(gatt.notifications)
        val assembler = BigDataAssembler(type)
        gatt.write(ColmiProtocol.BIG_SERVICE, ColmiProtocol.BIG_WRITE, request)
        val whole = withTimeoutOrNull(timeoutMs) {
            val started = System.currentTimeMillis()
            var reply: ByteArray? = null
            while (reply == null) {
                val left = if (assembler.started) Long.MAX_VALUE else firstReplyMs - (System.currentTimeMillis() - started)
                if (left <= 0) break
                // Null when the time's up, or the ring went away (the channel closes).
                val n = withTimeoutOrNull(left) { gatt.notifications.receiveCatching().getOrNull() } ?: break
                if (n.characteristic == ColmiProtocol.BIG_NOTIFY) reply = assembler.feed(n.value)
            }
            reply
        }
        return when {
            whole != null -> Reply(Answer.COMPLETE, whole)
            assembler.started -> Reply(Answer.PARTIAL, null)
            else -> Reply(Answer.SILENT, null)
        }
    }

    /**
     * Sends [packet] and hands each reply with [command] to [take] until it says the reply is
     * complete, or [timeoutMs] passes (whatever arrived is kept: rings can be terse). A ring that
     * says nothing within [firstReplyMs] isn't waited for any longer, and one that answers with
     * the error flag is taken at its word.
     */
    private suspend fun request(
        packet: ByteArray,
        command: Int,
        timeoutMs: Long = 6_000,
        firstReplyMs: Long = timeoutMs,
        take: (ByteArray) -> Boolean,
    ): Answer {
        drain(gatt.notifications)
        gatt.write(ColmiProtocol.SERVICE, ColmiProtocol.WRITE, packet)
        var heard = false
        var answer = Answer.SILENT
        withTimeoutOrNull(timeoutMs) {
            val started = System.currentTimeMillis()
            while (true) {
                val left = if (heard) Long.MAX_VALUE else firstReplyMs - (System.currentTimeMillis() - started)
                if (left <= 0) return@withTimeoutOrNull
                // Null when the time's up, or the ring went away (the channel closes).
                val n = withTimeoutOrNull(left) { gatt.notifications.receiveCatching().getOrNull() } ?: return@withTimeoutOrNull
                if (n.characteristic != ColmiProtocol.NOTIFY) continue
                if (ColmiProtocol.isRefusal(n.value, command)) {
                    answer = Answer.REFUSED
                    return@withTimeoutOrNull
                }
                if (ColmiProtocol.command(n.value) != command) continue
                heard = true
                if (take(n.value)) {
                    answer = Answer.COMPLETE
                    return@withTimeoutOrNull
                }
            }
        }
        return if (answer == Answer.SILENT && heard) Answer.PARTIAL else answer
    }

    private fun drain(channel: ReceiveChannel<Notification>) {
        while (channel.tryReceive().isSuccess) Unit
    }

    private companion object {
        /** How long a ring has to start answering a reading it may not have. */
        const val FIRST_REPLY_MS = 5_000L
    }
}
