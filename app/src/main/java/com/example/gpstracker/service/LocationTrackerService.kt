package com.example.gpstracker.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.gpstracker.model.GpsPoint
import com.example.gpstracker.util.GpxExporter
import com.example.gpstracker.util.InterpolationEngine
import com.example.gpstracker.util.TrackPersistence
import com.google.android.gms.location.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocationTrackerService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_RESUME = "RESUME"
        private const val CHANNEL_ID = "gps_tracker_channel"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "gps_tracker_prefs"
        private const val KEY_IS_RECORDING = "is_recording"
    }

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val points = CopyOnWriteArrayList<GpsPoint>()
    private lateinit var locationCallback: LocationCallback
    private lateinit var prefs: SharedPreferences
    private val saveExecutor = Executors.newSingleThreadScheduledExecutor()

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), 0)

        // CRASH RECOVERY: Load previously saved points
        points.addAll(TrackPersistence.loadPoints(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> startTracking()
            ACTION_STOP -> stopAndExport()
        }
        return START_STICKY
    }

    private fun startTracking() {
        prefs.edit().putBoolean(KEY_IS_RECORDING, true).apply()
        startPeriodicSave()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    points.add(
                        GpsPoint(
                            loc.latitude, loc.longitude,
                            loc.altitude.takeIf { it != 0.0 },
                            loc.time, loc.accuracy
                        )
                    )
                }
            }
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .setWaitForAccurateLocation(true)
            .build()

        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun startPeriodicSave() {
        saveExecutor.scheduleAtFixedRate({
            TrackPersistence.savePoints(this@LocationTrackerService, points.toList())
        }, 10, 10, TimeUnit.SECONDS)
    }

    private fun stopAndExport() {
        fusedClient.removeLocationUpdates(locationCallback)
        saveExecutor.shutdownNow()

        TrackPersistence.savePoints(this, points.toList())
        val interpolated = InterpolationEngine.interpolate(points.toList())
        GpxExporter.exportToFile(this, interpolated)

        TrackPersistence.clearTrack(this)
        prefs.edit().putBoolean(KEY_IS_RECORDING, false).apply()

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "GPS Tracking", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("GPS Tracking Active")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // FINAL SAFETY: Save if killed by system/crash
        TrackPersistence.savePoints(this, points.toList())
        saveExecutor.shutdownNow()
        super.onDestroy()
    }
}