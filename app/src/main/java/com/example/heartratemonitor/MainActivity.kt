package com.example.heartratemonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.collectAsState
import com.example.heartratemonitor.bluetooth.BleHeartRateSensor
import com.example.heartratemonitor.bluetooth.HeartRateSensor
import com.example.heartratemonitor.bluetooth.SensorState
import com.example.heartratemonitor.bluetooth.SimulatedHeartRateSensor
import com.example.heartratemonitor.ui.theme.HeartRateMonitorTheme

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[SensorViewModel::class.java] }
    private val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPermissions()) scanWithBluetooth()
        else model.message = "Allow Bluetooth access in app settings, then scan again."
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) scanWithBluetooth()
        else model.message = "Turn on Bluetooth to scan for your monitor."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HeartRateMonitorTheme(darkTheme = true) {
                val sensor = model.sensor
                val state by sensor.state.collectAsState()
                SensorScreen(
                    state = state,
                    message = model.message,
                    simulated = model.simulated,
                    scan = ::scan,
                    connect = sensor::connect,
                    toggleSimulator = model::toggleSimulator,
                )
            }
        }
    }

    private fun hasPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun scan() {
        model.message = null
        when {
            model.simulated -> model.sensor.scan()
            hasPermissions() -> scanWithBluetooth()
            else -> permissionRequest.launch(permissions)
        }
    }

    @SuppressLint("MissingPermission")
    private fun scanWithBluetooth() {
        if (model.simulated || !hasPermissions()) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> model.message = "Bluetooth is unavailable on this device."
            adapter.isEnabled -> model.sensor.scan()
            else -> enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }
}

class SensorViewModel(application: Application) : AndroidViewModel(application) {
    private val bluetooth = BleHeartRateSensor(application)
    private val simulator by lazy { SimulatedHeartRateSensor() }
    var simulated by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    val sensor: HeartRateSensor get() = if (simulated) simulator else bluetooth

    fun toggleSimulator() {
        if (!BuildConfig.DEBUG) return
        sensor.stop()
        message = null
        simulated = !simulated
        if (simulated) sensor.connect("simulated")
    }

    override fun onCleared() {
        sensor.stop()
    }
}

@Composable
private fun SensorScreen(
    state: SensorState,
    message: String?,
    simulated: Boolean,
    scan: () -> Unit,
    connect: (String) -> Unit,
    toggleSimulator: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Home Gym", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Wear your Polar H10 and moisten the electrodes before scanning.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text("${state.bpm ?: "—"} BPM", style = MaterialTheme.typography.displayLarge)
            Text(message ?: state.status, style = MaterialTheme.typography.titleMedium)
            if (!simulated) {
                Button(onClick = scan, enabled = !state.busy) { Text("Scan for monitors") }
            }
            state.devices.forEach { device ->
                OutlinedButton(onClick = { connect(device.id) }) {
                    Text(device.rssi?.let { "${device.name} · $it dBm" } ?: "${device.name} · Paired")
                }
            }
            if (BuildConfig.DEBUG) {
                OutlinedButton(onClick = toggleSimulator) {
                    Text(if (simulated) "Use Bluetooth" else "Use simulator")
                }
            }
        }
    }
}

