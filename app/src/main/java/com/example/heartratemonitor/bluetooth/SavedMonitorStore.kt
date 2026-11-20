package com.example.heartratemonitor.bluetooth

import android.content.Context

internal class SavedMonitorStore(context: Context) {
    private val preferences = context.getSharedPreferences("sensor_setup", Context.MODE_PRIVATE)

    fun load(): SensorDevice? {
        val id = preferences.getString("id", null) ?: return null
        val name = preferences.getString("name", null) ?: return null
        return SensorDevice(id, name, rssi = null)
    }

    fun clear() { preferences.edit().remove("id").remove("name").apply() }

    fun save(device: SensorDevice) {
        preferences.edit().putString("id", device.id).putString("name", device.name).apply()
    }
}
