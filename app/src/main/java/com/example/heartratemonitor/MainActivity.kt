package com.example.heartratemonitor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import com.example.heartratemonitor.ui.dashboard.DashboardScreen
import com.example.heartratemonitor.ui.theme.HeartRateMonitorTheme
import com.example.heartratemonitor.workout.WorkoutPhase

class MainActivity : ComponentActivity() {
    private val model get() = (application as HomeGymApplication).sensorController
    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (!allowed && Build.VERSION.SDK_INT >= 33 &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            showSystemNotificationSettings()
        }
    }

    private var scanRequested = false
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasMonitorPermissions()) continueBluetoothAction()
        else model.message = "Allow Bluetooth access in app settings, then scan again."
        if (hasMonitorPermissions() && model.workouts.phase == WorkoutPhase.ACTIVE) {
            requestBackgroundTracking()
        }
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
                    onTrackingRequested = ::requestBackgroundTracking,
                    openNotificationSettings = ::openNotificationSettings,
                    toggleSimulator = model::toggleSimulator,
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
        model.setDashboardVisible(true)
    }

    override fun onResume() {
        super.onResume()
        model.refreshBluetooth()
        requestBackgroundTracking()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            model.workouts.checkpoint()
            model.setDashboardVisible(false)
        }
        super.onStop()
    }

    private fun requestBackgroundTracking() {
        if (model.workouts.phase != WorkoutPhase.ACTIVE || model.trackingInBackground) return
        if (Build.VERSION.SDK_INT >= 34 && !hasMonitorPermissions()) {
            model.reportTrackingUnavailable()
            return
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, WorkoutTrackingService::class.java))
        } catch (failure: RuntimeException) {
            if (BuildConfig.DEBUG) Log.w("WorkoutTracking", "Foreground service start failed", failure)
            model.reportTrackingUnavailable()
        }
    }

    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showSystemNotificationSettings()
        }
    }

    private fun showSystemNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        }
        startActivity(intent)
    }

    private fun reconnect() {
        scanRequested = false
        beginBluetoothAction()
    }

    private fun scan() {
        scanRequested = true
        beginBluetoothAction()
    }

    private fun beginBluetoothAction() {
        model.message = null
        if (hasMonitorPermissions()) continueBluetoothAction() else permissionRequest.launch(monitorPermissions())
    }

    @SuppressLint("MissingPermission")
    private fun continueBluetoothAction() {
        if (model.simulated || !hasMonitorPermissions()) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> model.message = "Bluetooth is unavailable on this device."
            !adapter.isEnabled -> enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> {
                if (scanRequested) {
                    model.refreshBluetooth()
                    model.sensor.scan()
                } else {
                    model.reconnect()
                    model.refreshBluetooth()
                }
            }
        }
    }
}

