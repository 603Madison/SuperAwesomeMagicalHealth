package com.health.app.devices.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/** Something went wrong talking to a Bluetooth device; [message] is meant for people. */
class BleException(message: String) : IOException(message)

/** A notification from [characteristic]. */
class Notification(val characteristic: UUID, val value: ByteArray)

/**
 * A connection to a Bluetooth LE device, with its callbacks turned into suspending calls. One
 * operation runs at a time (Android's Bluetooth stack drops overlapping ones); notifications
 * arrive on [notifications].
 */
@SuppressLint("MissingPermission")
class GattConnection private constructor() {
    private lateinit var gatt: BluetoothGatt
    private val lock = Mutex()
    private var pending: CompletableDeferred<Pair<Int, ByteArray?>>? = null
    private val connected = CompletableDeferred<Unit>()
    private val discovered = CompletableDeferred<Unit>()

    @Volatile
    var isClosed = false
        private set

    val notifications = Channel<Notification>(Channel.UNLIMITED)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isClosed = true
                val error = BleException(if (status == 8) "The ring went out of range" else "The ring disconnected")
                connected.completeExceptionally(error)
                discovered.completeExceptionally(error)
                pending?.completeExceptionally(error)
                notifications.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) discovered.complete(Unit)
            else discovered.completeExceptionally(BleException("Couldn't read the ring's services"))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status to null)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status to null)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pending?.complete(status to value)
        }

        @Deprecated("Before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) pending?.complete(status to characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            notifications.trySend(Notification(characteristic.uuid, value.copyOf()))
        }

        @Deprecated("Before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                notifications.trySend(Notification(characteristic.uuid, characteristic.value?.copyOf() ?: ByteArray(0)))
            }
        }
    }

    fun hasService(service: UUID): Boolean = gatt.getService(service) != null

    private fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic =
        gatt.getService(service)?.getCharacteristic(characteristic) ?: throw BleException("The ring doesn't offer $characteristic")

    /** Turns notifications (or, with [indicate], indications) from [characteristic] on; both arrive on [notifications]. */
    suspend fun enableNotifications(service: UUID, characteristic: UUID, indicate: Boolean = false) {
        val char = characteristic(service, characteristic)
        if (!gatt.setCharacteristicNotification(char, true)) throw BleException("Couldn't listen to the ring")
        val descriptor = char.getDescriptor(CCCD) ?: return
        val enable = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        operation("listen") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, enable) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = enable
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
        val char = characteristic(service, characteristic)
        val type = if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        operation("write") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.writeType = type
                @Suppress("DEPRECATION")
                char.value = value
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        }
    }

    suspend fun read(service: UUID, characteristic: UUID): ByteArray? {
        val char = gatt.getService(service)?.getCharacteristic(characteristic) ?: return null
        return operation("read") { gatt.readCharacteristic(char) }
    }

    private suspend fun operation(what: String, start: () -> Boolean): ByteArray? = lock.withLock {
        if (isClosed) throw BleException("The ring disconnected")
        val done = CompletableDeferred<Pair<Int, ByteArray?>>()
        pending = done
        try {
            if (!start()) throw BleException("The ring didn't take the $what")
            val (status, value) = withTimeoutOrNull(OPERATION_TIMEOUT_MS) { done.await() } ?: throw BleException("The ring didn't answer")
            if (status != BluetoothGatt.GATT_SUCCESS) throw BleException("The ring refused the $what ($status)")
            value
        } finally {
            pending = null
        }
    }

    fun close() {
        if (!::gatt.isInitialized) return
        isClosed = true
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        notifications.close()
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val OPERATION_TIMEOUT_MS = 8_000L
        private const val CONNECT_TIMEOUT_MS = 25_000L

        /** Connects to the device at [address] and finds its services. */
        suspend fun connect(context: Context, address: String): GattConnection {
            val manager = context.getSystemService(BluetoothManager::class.java) ?: throw BleException("This phone has no Bluetooth")
            val adapter = manager.adapter ?: throw BleException("This phone has no Bluetooth")
            if (!adapter.isEnabled) throw BleException("Bluetooth is off")
            val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: throw BleException("That isn't a Bluetooth address")
            val connection = GattConnection()
            connection.gatt = device.connectGatt(context, false, connection.callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw BleException("Couldn't connect to the ring")
            try {
                withTimeout(CONNECT_TIMEOUT_MS) { connection.connected.await() }
                // A larger MTU isn't needed: the ring speaks in 16-byte packets.
                if (!connection.gatt.discoverServices()) throw BleException("Couldn't read the ring's services")
                withTimeout(CONNECT_TIMEOUT_MS) { connection.discovered.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                connection.close()
                throw BleException("Couldn't find the ring. Is it nearby, and charged?")
            } catch (e: Exception) {
                connection.close()
                throw e
            }
            return connection
        }
    }
}
