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
    private val store = SavedMonitorStore(context)
    private var savedMonitor = store.load()
    private var target: SensorDevice? = null
    private var active = false
    private var retryIndex = 0
    private val mutableState = MutableStateFlow(SensorState(savedMonitorName = savedMonitor?.name))
    override val state = mutableState.asStateFlow()
    private var scanCallback: ScanCallback? = null
    private var stopScan: (() -> Unit)? = null
    private var gatt: BluetoothGatt? = null
    private val retry = Runnable { savedMonitor?.let { open(it, reconnecting = true) } }
    private val timeout = Runnable {
        val reason = if (mutableState.value.bpm == null) "The monitor did not respond" else "No recent heart-rate readings"
        fail(reason, ConnectionState.DISCONNECTED)
    }
    private val scanTimeout = Runnable {
        endScan()
        val current = mutableState.value
        mutableState.value = current.copy(
            connection = if (current.devices.isEmpty()) ConnectionState.DISCONNECTED else ConnectionState.SCANNING,
            busy = false,
            status = if (current.devices.isEmpty()) "No heart-rate monitors found" else "Select your heart-rate monitor",
        )
    }

    fun updateAvailability(inForeground: Boolean, permitted: Boolean, enabled: Boolean) {
        val ready = inForeground && permitted && enabled
        if (!ready) {
            active = false
            stop()
            if (inForeground) {
                val status = if (permitted) "Turn on Bluetooth to connect" else "Allow Nearby Devices access to connect"
                val connection = if (permitted) ConnectionState.BLUETOOTH_DISABLED else ConnectionState.PERMISSION_REQUIRED
                publish(SensorState(connection = connection, status = status))
            }
        } else if (!active) {
            active = true
            reconnect()
        }
    }

    fun reconnect() {
        if (!active) return
        val monitor = savedMonitor ?: run {
            publish(SensorState())
            return
        }
        stop()
        open(monitor, reconnecting = true)
    }

    override fun scan() {
        if (!active) return
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
                    publish(SensorState(
                        connection = ConnectionState.SCANNING,
                        status = "Select your heart-rate monitor",
                        devices = devices.sortedByDescending { it.rssi ?: Int.MIN_VALUE },
                        busy = true,
                    ))
                }

                override fun onScanFailed(errorCode: Int) {
                    handler.post {
                        if (scanCallback === this) fail("Bluetooth scan failed. Scan again to retry.")
                    }
                }
            }
            scanCallback = callback
            stopScan = { scanner.stopScan(callback) }
            publish(SensorState(
                connection = ConnectionState.SCANNING,
                status = if (pairedMonitors.isEmpty()) "Scanning nearby monitors" else "Select your heart-rate monitor",
                devices = pairedMonitors,
                busy = true,
            ))
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
        if (!active) return
        val monitor = mutableState.value.devices.firstOrNull { it.id == id } ?: return
        stop()
        open(monitor, reconnecting = false)
    }

    private fun open(monitor: SensorDevice, reconnecting: Boolean) {
        if (!active) return
        target = monitor
        try {
            val bluetooth = adapter
            if (bluetooth == null || !bluetooth.isEnabled) {
                fail("Turn on Bluetooth to connect")
                return
            }
            publish(SensorState(
                connection = if (reconnecting) ConnectionState.RECONNECTING else ConnectionState.CONNECTING,
                status = if (reconnecting) "Connecting to ${monitor.name}…" else "Connecting to monitor…",
                busy = true,
            ))
            gatt = bluetooth.getRemoteDevice(monitor.id).connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) fail("Could not connect to the monitor") else armTimeout(15_000)
        } catch (_: SecurityException) {
            fail("Bluetooth permission is required")
        } catch (_: IllegalArgumentException) {
            fail("Invalid monitor")
        }
    }

    override fun stop() {
        handler.removeCallbacks(retry)
        retryIndex = 0
        target = null
        releaseConnection()
        publish(SensorState())
    }

    private fun releaseConnection() {
        endScan()
        handler.removeCallbacks(timeout)
        val previous = gatt
        gatt = null
        try {
            previous?.close()
        } catch (_: SecurityException) {
            // Bluetooth permission was revoked.
        }
    }

    private fun publish(state: SensorState) {
        mutableState.value = state.copy(savedMonitorName = savedMonitor?.name)
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

    private fun fail(message: String, connection: ConnectionState = ConnectionState.ERROR) {
        releaseConnection()
        val monitor = savedMonitor
        if (active && monitor != null && monitor.id == target?.id) {
            publish(SensorState(connection = ConnectionState.RECONNECTING, status = "$message. Retrying…"))
            handler.postDelayed(retry, RETRY_DELAYS[retryIndex])
            retryIndex = (retryIndex + 1).coerceAtMost(RETRY_DELAYS.lastIndex)
        } else {
            publish(SensorState(connection = connection, status = message))
        }
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
                    fail("Monitor disconnected", ConnectionState.DISCONNECTED)
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
                    publish(mutableState.value.copy(status = "Waiting for first reading"))
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
            val monitor = target ?: return@onCurrent
            if (savedMonitor?.id != monitor.id || savedMonitor?.name != monitor.name) {
                savedMonitor = monitor
                store.save(monitor)
            }
            retryIndex = 0
            publish(SensorState(connection = ConnectionState.CONNECTED, status = "Live from monitor", bpm = bpm))
            armTimeout(FRESHNESS_MS)
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
        const val FRESHNESS_MS = 5_000L
        val RETRY_DELAYS = longArrayOf(2_000, 5_000, 10_000, 20_000, 30_000)
        val SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
