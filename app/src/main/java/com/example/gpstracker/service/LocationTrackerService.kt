package com.example.gpstracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.gpstracker.MainActivity
import com.example.gpstracker.model.GpsPoint
import com.example.gpstracker.util.GpxExporter
import com.example.gpstracker.util.InterpolationEngine
import com.example.gpstracker.util.TrackPersistence
import com.google.android.gms.location.*
import java.io.File
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
        private const val TAG = "LocationTrackerService"
    }

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val points = CopyOnWriteArrayList<GpsPoint>()
    // Initialize with empty callback to avoid UninitializedPropertyAccessException
    private var locationCallback: LocationCallback? = null
    private lateinit var prefs: SharedPreferences
    private val saveExecutor = Executors.newSingleThreadScheduledExecutor()

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()

        val notification = buildNotification()

        try {
            // ANDROID 16 FIX: Explicitly pass foregroundServiceType to startForeground
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+: Must pass type parameter
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                // API 26-33: Use ServiceCompat with type parameter
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            }
            Log.d(TAG, "Foreground service started with LOCATION type")
        } catch (e: Exception) {
            Log.e(TAG, "Foreground start failed", e)
            stopSelf()
            return
        }

        points.addAll(TrackPersistence.loadPoints(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> {
                if (hasAllPermissions()) {
                    startTracking()
                } else {
                    Log.w(TAG, "Permissions missing, stopping")
                    stopSelf()
                }
            }
            ACTION_STOP -> stopAndExport()
        }
        return START_STICKY
    }

    private fun hasAllPermissions(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else PackageManager.PERMISSION_GRANTED
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
        } else PackageManager.PERMISSION_GRANTED

        return (fine == PackageManager.PERMISSION_GRANTED) &&
                (coarse == PackageManager.PERMISSION_GRANTED) &&
                (background == PackageManager.PERMISSION_GRANTED) &&
                (notifications == PackageManager.PERMISSION_GRANTED)
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

        try {
            fusedClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
            Log.d(TAG, "Location updates active")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location request denied", e)
            stopAndExport()
        }
    }

    private fun startPeriodicSave() {
        saveExecutor.scheduleAtFixedRate({
            try {
                TrackPersistence.savePoints(this, points.toList())
            } catch (e: Exception) {
                Log.e(TAG, "Save failed", e)
            }
        }, 10, 10, TimeUnit.SECONDS)
    }

    private fun stopAndExport() {
        Log.d(TAG, "stopAndExport called: ${points.size} points collected")

        // Safe removal: check if callback was initialized
        locationCallback?.let { callback ->
            try {
                fusedClient.removeLocationUpdates(callback)
                Log.d(TAG, "Location updates removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing location updates", e)
            }
        }

        saveExecutor.shutdownNow()
        TrackPersistence.savePoints(this, points.toList())
        Log.d(TAG, "Raw points saved to persistence")

        if (points.isEmpty()) {
            Log.w(TAG, "No points to export")
        } else {
            val interpolated = InterpolationEngine.interpolate(points.toList())
            Log.d(TAG, "Interpolated to ${interpolated.size} points")

            val resultPath = GpxExporter.exportToFile(this, interpolated)
            if (resultPath != null) {
                Log.d(TAG, "✓ GPX export SUCCESS: $resultPath")
                val verifyFile = File(resultPath)
                Log.d(TAG, "  File exists: ${verifyFile.exists()}, size: ${verifyFile.length()} bytes")
            } else {
                Log.e(TAG, "✗ GPX export FAILED (returned null)")
            }
        }

        TrackPersistence.clearTrack(this)
        prefs.edit().putBoolean(KEY_IS_RECORDING, false).apply()

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            Log.d(TAG, "Foreground service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground", e)
        }
        stopSelf()
        Log.d(TAG, "Service self-stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "GPS Tracking", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracks your location in the background"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Tracking Active")
            .setContentText("Tap to open app")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { TrackPersistence.savePoints(this, points.toList()) } catch (_: Exception) {}
        saveExecutor.shutdownNow()
        super.onDestroy()
    }
}