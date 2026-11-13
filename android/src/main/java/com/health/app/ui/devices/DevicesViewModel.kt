package com.health.app.ui.devices

import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.api.Device
import com.health.app.Container
import com.health.app.devices.colmi.ColmiConduit
import com.health.app.devices.colmi.RingReadingsState
import com.health.app.devices.gadgetbridge.GadgetbridgeConduit
import com.health.app.devices.healthconnect.HealthConnectConduit
import com.health.app.net.choosing
import com.health.app.net.choosingSleep
import com.health.app.net.deviceChoices
import com.health.app.net.saveDeviceChoices
import com.health.app.sync.ConduitStatus
import com.health.app.sync.PairedRing
import com.health.app.ui.components.Sources
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Health Connect as the phone has it. */
enum class HealthConnectState { NOT_INSTALLED, NEEDS_UPDATE, OFF, NEEDS_PERMISSION, ON }

data class DevicesState(
    val ring: PairedRing? = null,
    val healthConnect: HealthConnectState = HealthConnectState.OFF,
    val gadgetbridgeFile: String? = null,
    val statuses: Map<String, ConduitStatus> = emptyMap(),
    /** The source syncing now ("" for the outbox), or null. */
    val running: String? = null,
    val serverDevices: List<Device> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val outbox: Int = 0,
    /** Which readings the paired ring gives, and when each last came in. */
    val ringReadings: RingReadingsState = RingReadingsState(),
    /** Readings more than one device measures, and which the person uses (empty until loaded, or if the server can't say). */
    val overlaps: List<Overlap> = emptyList(),
)

class DevicesViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(DevicesState())
    val state: StateFlow<DevicesState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(c.syncStore.ring, c.syncStore.statuses, c.sync.running, c.syncStore.gadgetbridgeUri, c.colmi.readings.state) { ring, statuses, running, gb, readings ->
                _state.value.copy(
                    ring = ring, statuses = statuses, running = running, gadgetbridgeFile = gb?.let { c.gadgetbridge.fileName() ?: "Export file" }, outbox = c.outbox.size,
                    ringReadings = readings,
                )
            }.collect { _state.value = it }
        }
        viewModelScope.launch { c.syncStore.healthConnectOn.collect { refreshHealthConnect() } }
        viewModelScope.launch { c.revision.drop(1).collect { loadServer() } }
        loadServer()
    }

    fun loadServer() {
        viewModelScope.launch {
            try {
                val devices = c.api.devices()
                _state.update { it.copy(serverDevices = devices, loading = false, error = null) }
                loadOverlaps(devices)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your devices") }
            }
        }
    }

    /**
     * Where devices overlap: from the last two days (today alone can be thin early in the
     * morning) and the person's choices. Always the person's own ([Container.api]). A server that
     * can't say (or isn't reached) leaves the section out.
     */
    private suspend fun loadOverlaps(devices: List<Device>) {
        val choices = runCatching { c.api.deviceChoices() }.getOrNull() ?: return _state.update { it.copy(overlaps = emptyList()) }
        val today = LocalDate.now(c.zone())
        val days = listOf(today, today.minusDays(1)).mapNotNull { runCatching { c.api.day(it.toString()) }.getOrNull() }
        _state.update { it.copy(overlaps = Overlaps.of(days, choices, devices)) }
    }

    /** Uses only [deviceId] (named [device]) for [overlap]'s reading, or (null) combines them again. */
    fun choose(overlap: Overlap, deviceId: String?) {
        val device = overlap.devices.firstOrNull { it.id == deviceId }?.name
        _state.update { s -> s.copy(overlaps = s.overlaps.map { if (it.metric == overlap.metric) it.copy(chosen = deviceId) else it }) }
        viewModelScope.launch {
            try {
                val current = c.api.deviceChoices()
                c.api.saveDeviceChoices(overlap.metric?.let { current.choosing(it, deviceId) } ?: current.choosingSleep(deviceId))
                c.tell(Sources.chosen(overlap.reading, device))
                // Today and Trends reload; so does this page (loadServer, on the revision).
                c.dataChanged()
            } catch (e: Exception) {
                c.tell(e.message ?: "Couldn't save that")
                loadServer()
            }
        }
    }

    fun refreshHealthConnect() {
        viewModelScope.launch {
            val hc = c.healthConnect
            val state = when (hc.availability()) {
                HealthConnectClient.SDK_UNAVAILABLE -> HealthConnectState.NOT_INSTALLED
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectState.NEEDS_UPDATE
                else -> when {
                    !c.syncStore.healthConnectOn.value -> HealthConnectState.OFF
                    hc.granted().none { it.contains("READ_") } -> HealthConnectState.NEEDS_PERMISSION
                    else -> HealthConnectState.ON
                }
            }
            _state.update { it.copy(healthConnect = state) }
        }
    }

    fun healthConnectPermissions(): Set<String> = c.healthConnect.permissions()

    /** After Health Connect's permission screen: on if anything was allowed. */
    fun healthConnectGranted(granted: Set<String>) {
        val any = granted.any { it.contains("READ_") }
        c.syncStore.setHealthConnectOn(any)
        if (any) c.syncNow(HealthConnectConduit.ID) else c.tell("Health Connect wasn't allowed to share anything")
        refreshHealthConnect()
    }

    fun healthConnectOff() {
        c.syncStore.setHealthConnectOn(false)
        c.syncStore.clearStatus(HealthConnectConduit.ID)
        refreshHealthConnect()
    }

    fun chooseGadgetbridge(uri: Uri) {
        c.gadgetbridge.choose(uri)
        c.syncNow(GadgetbridgeConduit.ID)
    }

    fun removeGadgetbridge() = c.gadgetbridge.remove()

    fun forgetRing() = c.colmi.forget()

    fun sync(id: String? = null) = c.syncNow(id)

    fun rename(device: Device, name: String) {
        viewModelScope.launch {
            runCatching { c.api.renameDevice(device.id, name) }.onSuccess { loadServer() }.onFailure { c.tell(it.message ?: "Couldn't rename it") }
        }
    }

    fun forget(device: Device, deleteData: Boolean) {
        viewModelScope.launch {
            runCatching { c.api.forgetDevice(device.id, deleteData) }
                .onSuccess {
                    c.tell(if (deleteData) "Forgot ${device.name} and its data" else "Forgot ${device.name}")
                    if (device.kind == com.health.api.DeviceKind.COLMI_RING && c.syncStore.ring.value?.address == device.identifier) {
                        c.colmi.forget()
                    }
                    loadServer()
                    c.dataChanged()
                }
                .onFailure { c.tell(it.message ?: "Couldn't forget it") }
        }
    }

    fun pairRing(address: String, name: String) {
        c.colmi.pair(address, name)
        c.syncNow(ColmiConduit.ID)
    }
}
