package com.example.heartratemonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import com.example.heartratemonitor.workout.WorkoutPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class WorkoutTrackingService : Service() {
    private val model get() = (application as HomeGymApplication).sensorController
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var phaseWatch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (failure: RuntimeException) {
            if (BuildConfig.DEBUG) Log.w("WorkoutTracking", "Foreground promotion failed", failure)
            model.reportTrackingUnavailable()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (model.workouts.phase == WorkoutPhase.ACTIVE) {
            model.setBackgroundTracking(true)
        }
        phaseWatch?.cancel()
        phaseWatch = scope.launch {
            snapshotFlow { model.workouts.phase }.collect { phase ->
                if (phase != WorkoutPhase.ACTIVE) {
                    model.setBackgroundTracking(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        model.setBackgroundTracking(false)
        super.onDestroy()
    }

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Workout tracking", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openDashboard = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_workout_tracking)
            .setContentTitle("Workout in progress")
            .setContentText("Tap to return to ${getString(R.string.app_name)}.")
            .setContentIntent(openDashboard)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "active_workout"
        const val NOTIFICATION_ID = 101
    }
}
