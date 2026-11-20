package com.example.heartratemonitor.bluetooth

import kotlinx.coroutines.flow.StateFlow

data class SensorDevice(val id: String, val name: String, val rssi: Int?)

enum class ConnectionState {
    NOT_CONFIGURED, PERMISSION_REQUIRED, BLUETOOTH_DISABLED, SCANNING,
    CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR,
}

data class SensorState(
    val connection: ConnectionState = ConnectionState.NOT_CONFIGURED,
    val savedMonitorName: String? = null,
    val status: String = "Ready to scan",
    val devices: List<SensorDevice> = emptyList(),
    val bpm: Int? = null,
    val busy: Boolean = false,
)

interface HeartRateSensor {
    val state: StateFlow<SensorState>
    fun scan()
    fun connect(id: String)
    fun stop()
}
