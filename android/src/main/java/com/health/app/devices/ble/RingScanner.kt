package com.health.app.devices.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.health.app.devices.colmi.ColmiProtocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A ring found nearby. */
data class FoundRing(val address: String, val name: String, val rssi: Int)

/** Looks for smart rings nearby (Colmi and other QRing rings), by name or the service they offer. */
object RingScanner {
    @SuppressLint("MissingPermission")
    fun scan(context: Context): Flow<FoundRing> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(BleException(if (adapter == null) "This phone has no Bluetooth" else "Bluetooth is off"))
            return@callbackFlow
        }
        val ringService = ParcelUuid(ColmiProtocol.SERVICE)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                val offersService = result.scanRecord?.serviceUuids?.contains(ringService) == true
                if (offersService || ColmiProtocol.looksLikeRing(name)) {
                    trySend(FoundRing(result.device.address, name?.ifBlank { null } ?: "Smart ring", result.rssi))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleException("Couldn't look for rings ($errorCode)"))
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
