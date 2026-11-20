package com.example.heartratemonitor.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("MissingPermission") // Entry points check runtime access. Callbacks can race revocation.
class BleHeartRateSensor(private val context: Context) : HeartRateSensor {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(SensorState())
    override val state = mutableState.asStateFlow()
    private var scanCallback: ScanCallback? = null
    private var stopScan: (() -> Unit)? = null
    private var gatt: BluetoothGatt? = null
    private val timeout = Runnable { fail("The sensor did not respond. Scan again to retry.") }
    private val scanTimeout = Runnable {
        endScan()
        val current = mutableState.value
        mutableState.value = current.copy(
            busy = false,
            status = if (current.devices.isEmpty()) "No heart-rate monitors found" else "Select your heart-rate monitor",
        )
    }

    override fun scan() {
        stop()
        try {
            val bluetooth = adapter
            val scanner = bluetooth?.bluetoothLeScanner
            if (scanner == null) {
                fail("Turn on Bluetooth to scan")
                return
            }
            val pairedMonitors = bluetooth.bondedDevices.mapNotNull { device ->
                val name = device.name ?: return@mapNotNull null
                if (isPolarH10(name)) SensorDevice(device.address, name, rssi = null) else null
            }
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    handler.post {
                        if (scanCallback !== this) return@post
                        try {
                            addScanResult(result)
                        } catch (_: SecurityException) {
                            fail("Bluetooth permission is required")
                        }
                    }
                }

                private fun addScanResult(result: ScanResult) {
                    val record = result.scanRecord
                    val name = record?.deviceName ?: result.device.name
                    val advertisesHeartRate = record?.serviceUuids?.contains(ParcelUuid(SERVICE)) == true
                    if (!advertisesHeartRate && !isPolarH10(name)) return

                    val device = SensorDevice(
                        result.device.address,
                        name ?: "Heart-rate monitor",
                        result.rssi,
                    )
                    val devices = mutableState.value.devices.filterNot { it.id == device.id } + device
                    mutableState.value = SensorState(
                        status = "Select your heart-rate monitor",
                        devices = devices.sortedByDescending { it.rssi ?: Int.MIN_VALUE },
                        busy = true,
                    )
                }

                override fun onScanFailed(errorCode: Int) {
                    handler.post {
                        if (scanCallback === this) fail("Bluetooth scan failed. Scan again to retry.")
                    }
                }
            }
            scanCallback = callback
            stopScan = { scanner.stopScan(callback) }
            mutableState.value = SensorState(
                status = if (pairedMonitors.isEmpty()) "Scanning nearby monitors" else "Select your heart-rate monitor",
                devices = pairedMonitors,
                busy = true,
            )
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
            handler.postDelayed(scanTimeout, 12_000)
        } catch (_: SecurityException) {
            fail("Bluetooth permission is required")
        } catch (_: IllegalStateException) {
            fail("Bluetooth is unavailable. Turn it on and scan again.")
        }
    }

    override fun connect(id: String) {
        stop()
        try {
            val bluetooth = adapter
            if (bluetooth == null || !bluetooth.isEnabled) {
                fail("Turn on Bluetooth to connect")
                return
            }
            mutableState.value = SensorState(status = "Connecting to monitor", busy = true)
            gatt = bluetooth.getRemoteDevice(id).connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) fail("Could not connect. Scan again to retry.") else armTimeout(15_000)
        } catch (_: SecurityException) {
            fail("Bluetooth permission is required")
        } catch (_: IllegalArgumentException) {
            fail("Invalid monitor. Scan again to retry.")
        }
    }

    override fun stop() {
        endScan()
        handler.removeCallbacks(timeout)
        val previous = gatt
        gatt = null
        try {
            previous?.close()
        } catch (_: SecurityException) {
            // Bluetooth permission was revoked.
        }
        mutableState.value = SensorState()
    }

    private fun endScan() {
        handler.removeCallbacks(scanTimeout)
        scanCallback = null
        val stop = stopScan
        stopScan = null
        try {
            stop?.invoke()
        } catch (_: SecurityException) {
            // Bluetooth permission was revoked.
        } catch (_: IllegalStateException) {
            // Bluetooth was turned off.
        }
    }

    private fun armTimeout(delayMs: Long) {
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, delayMs)
    }

    private fun fail(message: String) {
        stop()
        mutableState.value = SensorState(status = message)
    }

    private fun onCurrent(connection: BluetoothGatt, action: () -> Unit) {
        handler.post {
            if (gatt !== connection) return@post
            try {
                action()
            } catch (_: SecurityException) {
                fail("Bluetooth permission is required")
            }
        }
    }

    private val callbacks = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) =
            onCurrent(connection) {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!connection.discoverServices()) fail("Could not discover heart-rate services")
                } else if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("Monitor disconnected. Scan again to reconnect.")
                }
            }

        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) = onCurrent(connection) {
            val measurement = connection.getService(SERVICE)?.getCharacteristic(MEASUREMENT)
            val descriptor = measurement?.getDescriptor(CCCD)
            if (status != BluetoothGatt.GATT_SUCCESS || descriptor == null ||
                !connection.setCharacteristicNotification(measurement, true) ||
                !enableNotifications(connection, descriptor)
            ) {
                fail("Could not subscribe to heart-rate readings")
            }
        }

        override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            onCurrent(connection) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Monitor rejected heart-rate notifications")
                } else if (mutableState.value.bpm == null) {
                    mutableState.value = SensorState(status = "Waiting for first reading", busy = true)
                }
            }

        override fun onCharacteristicChanged(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == MEASUREMENT) receive(connection, value)
        }

        @Deprecated("Used by Android 12 and earlier")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == MEASUREMENT) characteristic.value?.let { receive(connection, it) }
        }
    }

    private fun receive(connection: BluetoothGatt, packet: ByteArray) {
        val bpm = HeartRateParser.parse(packet) ?: return
        onCurrent(connection) {
            mutableState.value = SensorState(status = "Live from monitor", bpm = bpm)
            armTimeout(5_000)
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean {
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            connection.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            connection.writeDescriptor(descriptor)
        }
    }

    private fun isPolarH10(name: String?): Boolean =
        name?.startsWith("Polar H10", ignoreCase = true) == true

    private companion object {
        val SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
