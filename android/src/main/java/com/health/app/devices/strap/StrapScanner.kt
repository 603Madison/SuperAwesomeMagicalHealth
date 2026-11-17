package com.health.app.devices.strap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.health.app.attempt
import com.health.app.devices.ble.BleException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A heart rate strap found nearby. */
data class FoundStrap(val address: String, val name: String, val rssi: Int)

/** Looks for heart rate straps nearby: anything offering the standard Heart Rate service (0x180D). */
object StrapScanner {
    @SuppressLint("MissingPermission") // the Devices page asks first (ColmiConduit.hasPermission)
    fun scan(context: Context): Flow<FoundStrap> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(BleException(if (adapter == null) "This phone has no Bluetooth" else "Bluetooth is off"))
            return@callbackFlow
        }
        val heartRate = ParcelUuid(StrapProtocol.HEART_RATE_SERVICE)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: attempt { result.device.name }.getOrNull()
                val offersService = result.scanRecord?.serviceUuids?.contains(heartRate) == true
                if (offersService || StrapProtocol.looksLikeStrap(name)) {
                    trySend(FoundStrap(result.device.address, name?.ifBlank { null } ?: StrapStore.DEFAULT_NAME, result.rssi))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleException("Couldn't look for straps ($errorCode)"))
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        awaitClose { attempt { scanner.stopScan(callback) } }
    }
}
