package com.example.gpstracker.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.gpstracker.R
import com.example.gpstracker.model.GpsPoint
import com.example.gpstracker.util.GpxExporter
import com.example.gpstracker.util.InterpolationEngine
import com.google.android.gms.location.*
import java.util.concurrent.CopyOnWriteArrayList

class LocationTrackerService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        private const val CHANNEL_ID = "gps_tracker_channel"
        private const val NOTIFICATION_ID = 1
    }

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    // Thread-safe list for location updates
    private val points = CopyOnWriteArrayList<GpsPoint>()
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Tracking Active")
            .setContentText("Recording your track")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopAndExport()
        }
        return START_STICKY
    }

    private fun startTracking() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    points.add(
                        GpsPoint(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            altitude = loc.altitude.takeIf { it != 0.0 },
                            timestamp = loc.time,
                            accuracy = loc.accuracy
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

    private fun stopAndExport() {
        fusedClient.removeLocationUpdates(locationCallback)

        val interpolated = InterpolationEngine.interpolate(points.toList())
        val file = GpxExporter.exportToFile(this, interpolated)

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracks location in background"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}