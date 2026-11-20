package com.example.heartratemonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.collectAsState
import com.example.heartratemonitor.bluetooth.BleHeartRateSensor
import com.example.heartratemonitor.bluetooth.ConnectionState
import com.example.heartratemonitor.bluetooth.HeartRateSensor
import com.example.heartratemonitor.bluetooth.SensorState
import com.example.heartratemonitor.bluetooth.SimulatedHeartRateSensor
import com.example.heartratemonitor.ui.dashboard.DashboardScreen
import com.example.heartratemonitor.ui.dashboard.ChartPoint
import com.example.heartratemonitor.ui.dashboard.CHART_WINDOW_MS
import com.example.heartratemonitor.ui.theme.HeartRateMonitorTheme
import com.example.heartratemonitor.workout.WorkoutFileStore
import com.example.heartratemonitor.workout.WorkoutSessions
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[SensorViewModel::class.java] }
    private val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    private val bluetoothChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) refreshBluetooth()
        }
    }

    private var scanRequested = false
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPermissions()) continueBluetoothAction()
        else model.message = "Allow Bluetooth access in app settings, then scan again."
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) continueBluetoothAction()
        else model.message = "Turn on Bluetooth to scan for your monitor."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanRequested = savedInstanceState?.getBoolean("scanRequested") ?: false
        setContent {
            HeartRateMonitorTheme(darkTheme = true, dynamicColor = false) {
                val sensor = model.sensor
                val state by sensor.state.collectAsState()
                DashboardScreen(
                    model = model,
                    state = state,
                    message = model.message,
                    simulated = model.simulated,
                    scan = ::scan,
                    reconnect = ::reconnect,
                    connect = sensor::connect,
                    toggleSimulator = {
                        model.toggleSimulator()
                        refreshBluetooth()
                    },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("scanRequested", scanRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, bluetoothChanges, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        refreshBluetooth()
    }

    override fun onResume() {
        super.onResume()
        refreshBluetooth()
    }

    override fun onStop() {
        unregisterReceiver(bluetoothChanges)
        if (!isChangingConfigurations) {
            model.workouts.checkpoint()
            model.updateBluetooth(false, false, false)
        }
        super.onStop()
    }

    @SuppressLint("MissingPermission")
    private fun refreshBluetooth() {
        val permitted = hasPermissions()
        val enabled = permitted && getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        model.updateBluetooth(true, permitted, enabled)
    }

    private fun reconnect() {
        scanRequested = false
        beginBluetoothAction()
    }

    private fun hasPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun scan() {
        scanRequested = true
        beginBluetoothAction()
    }

    private fun beginBluetoothAction() {
        model.message = null
        if (hasPermissions()) continueBluetoothAction() else permissionRequest.launch(permissions)
    }

    @SuppressLint("MissingPermission")
    private fun continueBluetoothAction() {
        if (model.simulated || !hasPermissions()) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> model.message = "Bluetooth is unavailable on this device."
            !adapter.isEnabled -> enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> {
                if (scanRequested) {
                    refreshBluetooth()
                    model.sensor.scan()
                } else {
                    model.reconnect()
                    refreshBluetooth()
                }
            }
        }
    }
}

class SensorViewModel(application: Application) : AndroidViewModel(application) {
    private val bluetooth = BleHeartRateSensor(application)
    private val simulator by lazy { SimulatedHeartRateSensor() }
    private val settings = application.getSharedPreferences("dashboard_settings", Context.MODE_PRIVATE)
    private val chartScope = CoroutineScope(Dispatchers.Main.immediate)
    private var chartJob: Job? = null
    private var chartGap = true
    val workouts = WorkoutSessions(WorkoutFileStore(File(application.filesDir, "workouts")), chartScope)
    var configuredMaxHr by mutableStateOf(settings.getInt("max_hr", 0).takeIf { it > 0 })
        private set
    var chartPoints by mutableStateOf(emptyList<ChartPoint>())
        private set
    var simulated by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    val sensor: HeartRateSensor get() = if (simulated) simulator else bluetooth

    init {
        observeChart()
    }

    fun setMaxHeartRate(bpm: Int) {
        require(bpm in 1..999)
        settings.edit().putInt("max_hr", bpm).apply()
        configuredMaxHr = bpm
    }

    private fun observeChart() {
        chartJob?.cancel()
        chartGap = true
        workouts.sensorGap()
        chartPoints = emptyList()
        chartJob = chartScope.launch {
            sensor.state.collect { sample ->
                val bpm = sample.bpm
                val time = sample.readingAtMs
                if (bpm == null || time == null) {
                    chartGap = true
                    workouts.sensorGap()
                } else if (chartPoints.lastOrNull()?.atMs != time) {
                    workouts.addReading(time, bpm)
                    chartPoints = chartPoints.dropWhile { time - it.atMs > CHART_WINDOW_MS } +
                        ChartPoint(time, bpm, chartGap)
                    chartGap = false
                }
            }
        }
    }

    fun updateBluetooth(inForeground: Boolean, permitted: Boolean, enabled: Boolean) {
        if (simulated) return
        bluetooth.updateAvailability(inForeground, permitted, enabled)
        if (inForeground && permitted && enabled) message = null
    }

    fun reconnect() = bluetooth.reconnect()
    fun simulateDropout() { if (simulated) simulator.simulateDropout() }

    fun toggleSimulator() {
        if (!BuildConfig.DEBUG) return
        if (simulated) simulator.stop() else bluetooth.updateAvailability(false, false, false)
        message = null
        simulated = !simulated
        if (simulated) sensor.connect("simulated")
        observeChart()
    }

    override fun onCleared() {
        chartScope.cancel()
        sensor.stop()
    }
}

