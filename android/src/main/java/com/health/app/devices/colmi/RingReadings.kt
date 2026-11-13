package com.health.app.devices.colmi

import android.content.Context
import androidx.core.content.edit
import com.health.api.ApiJson
import com.health.api.Metric
import com.health.api.Sample
import com.health.api.SleepSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** What a ring can bring in, in the order the Devices page lists them. */
enum class RingReading(val label: String, val metrics: Set<Metric>) {
    HEART_RATE("Heart rate", setOf(Metric.HEART_RATE)),
    ACTIVITY("Steps and activity", setOf(Metric.STEPS, Metric.DISTANCE, Metric.ACTIVE_CALORIES)),
    SLEEP("Sleep", emptySet()),
    SPO2("Blood oxygen", setOf(Metric.SPO2)),
    HRV("Heart rate variability", setOf(Metric.HRV)),
    STRESS("Stress", setOf(Metric.STRESS)),
    SKIN_TEMPERATURE("Skin temperature", setOf(Metric.SKIN_TEMPERATURE)),
}

/** How one reading has gone with the paired ring. */
@Serializable
data class ReadingStatus(
    /** The newest reading that has come in (epoch ms; for activity and sleep, where it ended). */
    val latest: Long? = null,
    /** When readings of it last came in (the sync that brought them). */
    val arrivedAt: Long? = null,
    /** How the ring answered the last time it was asked (only kept for the readings rings may not have). */
    val answer: Answer? = null,
    /** False when this ring doesn't keep it, so it isn't asked for (skin temperature on most rings). */
    val offered: Boolean = true,
)

/** What the app remembers about the paired ring's readings. */
@Serializable
data class RingReadingsState(
    /** The ring they're about. */
    val address: String? = null,
    /** Which of the ring's own measurements the app has turned on ([RingReadingsState.AUTO_MEASURE] once all are). */
    val autoMeasure: Int = 0,
    val readings: Map<RingReading, ReadingStatus> = emptyMap(),
) {
    /** Whether the ring's own measurements still need turning on (a new ring, or an app that now turns on more). */
    fun needsAutoMeasure(address: String): Boolean = this.address != address || autoMeasure < AUTO_MEASURE

    /**
     * After a sync of the ring at [address]: each reading's newest time from what came in, how the
     * ring answered ([answers]), and which readings it keeps ([offered]).
     */
    fun after(
        address: String,
        samples: List<Sample>,
        sleep: List<SleepSession>,
        answers: Map<RingReading, Answer>,
        offered: Set<RingReading>,
        now: Long,
    ): RingReadingsState {
        val before = if (this.address == address) readings else emptyMap()
        val next = RingReading.entries.associateWith { reading ->
            val old = before[reading] ?: ReadingStatus()
            val newest = if (reading == RingReading.SLEEP) {
                sleep.maxOfOrNull { it.end }
            } else {
                samples.filter { it.metric in reading.metrics }.maxOfOrNull { it.end ?: it.start }
            }
            ReadingStatus(
                latest = listOfNotNull(old.latest, newest).maxOrNull(),
                arrivedAt = if (newest != null) now else old.arrivedAt,
                answer = answers[reading] ?: old.answer,
                offered = reading in offered,
            )
        }
        return copy(address = address, readings = next, autoMeasure = if (this.address == address) autoMeasure else 0)
    }

    companion object {
        /** Raised when the app turns on more of the ring's own measurements, so paired rings get them too. */
        const val AUTO_MEASURE = 2
    }
}

/** Keeps [RingReadingsState] on the phone (its own preferences, so the sync store stays as it is). */
class RingReadingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("colmi-readings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<RingReadingsState> = _state.asStateFlow()

    fun set(state: RingReadingsState) {
        prefs.edit { putString(KEY, ApiJson.encodeToString(RingReadingsState.serializer(), state)) }
        _state.value = state
    }

    fun clear() {
        prefs.edit { remove(KEY) }
        _state.value = RingReadingsState()
    }

    private fun read(): RingReadingsState =
        prefs.getString(KEY, null)?.let { runCatching { ApiJson.decodeFromString(RingReadingsState.serializer(), it) }.getOrNull() } ?: RingReadingsState()

    private companion object {
        const val KEY = "state"
    }
}
