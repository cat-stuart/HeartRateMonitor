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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.heartratemonitor.bluetooth.BleHeartRateSensor
import com.example.heartratemonitor.bluetooth.ConnectionState
import com.example.heartratemonitor.bluetooth.HeartRateSensor
import com.example.heartratemonitor.bluetooth.SimulatedHeartRateSensor
import com.example.heartratemonitor.ui.dashboard.CHART_WINDOW_MS
import com.example.heartratemonitor.ui.dashboard.ChartPoint
import com.example.heartratemonitor.workout.WorkoutFileStore
import com.example.heartratemonitor.workout.WorkoutSessions
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class HomeGymApplication : Application() {
    val sensorController by lazy { SensorController(this) }
}

internal fun monitorPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

internal fun Context.hasMonitorPermissions(): Boolean = monitorPermissions().all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

class SensorController(private val application: Application) {
    private val bluetooth = BleHeartRateSensor(application)
    private val simulator by lazy { SimulatedHeartRateSensor() }
    private val settings = application.getSharedPreferences("dashboard_settings", Context.MODE_PRIVATE)
    private var dashboardVisible = false
    var setupIntroduced by mutableStateOf(settings.getBoolean("setup_introduced", false))
        private set
    var lastMonitorReadingEpochMs by mutableStateOf<Long?>(null)
        private set
    var monitorPermissionsGranted by mutableStateOf(false)
        private set
    var trackingInBackground by mutableStateOf(false)
        private set
    var trackingError by mutableStateOf<String?>(null)
        private set
    var keepScreenAwake by mutableStateOf(settings.getBoolean("keep_screen_awake", true))
        private set
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
        ContextCompat.registerReceiver(
            application,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) refreshBluetooth()
                }
            },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
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
                    if (!simulated) lastMonitorReadingEpochMs = System.currentTimeMillis()
                    workouts.addReading(time, bpm)
                    chartPoints = chartPoints.dropWhile { time - it.atMs > CHART_WINDOW_MS } +
                        ChartPoint(time, bpm, chartGap)
                    chartGap = false
                }
            }
        }
    }

    fun updateKeepScreenAwake(enabled: Boolean) {
        settings.edit().putBoolean("keep_screen_awake", enabled).apply()
        keepScreenAwake = enabled
    }

    fun setDashboardVisible(visible: Boolean) {
        dashboardVisible = visible
        refreshBluetooth()
    }

    fun setBackgroundTracking(enabled: Boolean) {
        trackingInBackground = enabled
        if (enabled) trackingError = null
        refreshBluetooth()
    }

    fun reportTrackingUnavailable() {
        trackingError = "Background tracking unavailable. Keep the app visible to record heart rate."
    }

    @SuppressLint("MissingPermission")
    fun refreshBluetooth() {
        val inUse = dashboardVisible || trackingInBackground
        monitorPermissionsGranted = application.hasMonitorPermissions()
        if (simulated) {
            if (inUse && simulator.state.value.connection == ConnectionState.NOT_CONFIGURED) {
                simulator.connect("simulated")
            } else if (!inUse) {
                simulator.stop()
            }
            return
        }
        val permitted = monitorPermissionsGranted
        val enabled = permitted && application.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        bluetooth.updateAvailability(inUse, permitted, enabled)
        if (inUse && permitted && enabled) message = null
    }

    fun acknowledgeSetup() {
        settings.edit().putBoolean("setup_introduced", true).apply()
        setupIntroduced = true
    }

    fun forgetMonitor() {
        bluetooth.forgetMonitor()
        lastMonitorReadingEpochMs = null
        refreshBluetooth()
    }

    fun reconnect() = bluetooth.reconnect()
    fun simulateDropout() { if (simulated) simulator.simulateDropout() }

    fun toggleSimulator() {
        if (!BuildConfig.DEBUG) return
        if (simulated) simulator.stop() else bluetooth.updateAvailability(false, false, false)
        message = null
        simulated = !simulated
        refreshBluetooth()
        observeChart()
    }

}
