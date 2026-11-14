package com.health.app.sync

import android.content.Context
import androidx.core.content.edit
import com.health.api.ApiJson
import com.health.app.attempt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** How a data source's last sync went. */
@Serializable
data class ConduitStatus(
    val lastSyncAt: Long? = null,
    val ok: Boolean = true,
    val message: String? = null,
    val samples: Int = 0,
    val sleep: Int = 0,
    val workouts: Int = 0,
    /** Batches waiting in the outbox for the server. */
    val queued: Int = 0,
)

/** A ring the app syncs over Bluetooth. */
@Serializable
data class PairedRing(val address: String, val name: String, val battery: Int? = null, val firmware: String? = null)

/**
 * What the phone remembers about its data sources: where each got to, how its last sync went,
 * the paired ring, the Gadgetbridge file, whether Health Connect is on.
 */
class SyncStore(context: Context) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    private val _statuses = MutableStateFlow(readStatuses())
    val statuses: StateFlow<Map<String, ConduitStatus>> = _statuses.asStateFlow()

    private val _ring = MutableStateFlow(readRing())
    val ring: StateFlow<PairedRing?> = _ring.asStateFlow()

    private val _gadgetbridge = MutableStateFlow(prefs.getString("gadgetbridge.uri", null))
    val gadgetbridgeUri: StateFlow<String?> = _gadgetbridge.asStateFlow()

    private val _healthConnect = MutableStateFlow(prefs.getBoolean("health_connect.on", false))
    val healthConnectOn: StateFlow<Boolean> = _healthConnect.asStateFlow()

    fun cursor(id: String): Long? = prefs.getLong("cursor.$id", -1).takeIf { it >= 0 }

    fun setCursor(id: String, cursor: Long?) = prefs.edit {
        if (cursor == null) remove("cursor.$id") else putLong("cursor.$id", cursor)
    }

    fun setStatus(id: String, status: ConduitStatus) {
        prefs.edit { putString("status.$id", ApiJson.encodeToString(ConduitStatus.serializer(), status)) }
        _statuses.value = _statuses.value + (id to status)
    }

    fun clearStatus(id: String) {
        prefs.edit { remove("status.$id") }
        _statuses.value = _statuses.value - id
    }

    fun setRing(ring: PairedRing?) {
        prefs.edit {
            if (ring == null) remove("ring") else putString("ring", ApiJson.encodeToString(PairedRing.serializer(), ring))
        }
        _ring.value = ring
    }

    fun setGadgetbridgeUri(uri: String?) {
        prefs.edit { if (uri == null) remove("gadgetbridge.uri") else putString("gadgetbridge.uri", uri) }
        _gadgetbridge.value = uri
    }

    fun setHealthConnectOn(on: Boolean) {
        prefs.edit { putBoolean("health_connect.on", on) }
        _healthConnect.value = on
    }

    /** Forgets everything (signing out). */
    fun clear() {
        prefs.edit { clear() }
        _statuses.value = emptyMap()
        _ring.value = null
        _gadgetbridge.value = null
        _healthConnect.value = false
    }

    private fun readStatuses(): Map<String, ConduitStatus> = prefs.all.keys.filter { it.startsWith("status.") }.mapNotNull { key ->
        val json = prefs.getString(key, null) ?: return@mapNotNull null
        attempt { key.removePrefix("status.") to ApiJson.decodeFromString(ConduitStatus.serializer(), json) }.getOrNull()
    }.toMap()

    private fun readRing(): PairedRing? =
        prefs.getString("ring", null)?.let { attempt { ApiJson.decodeFromString(PairedRing.serializer(), it) }.getOrNull() }
}
