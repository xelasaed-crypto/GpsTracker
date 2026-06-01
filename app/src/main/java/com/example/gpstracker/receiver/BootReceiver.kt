package com.example.gpstracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.gpstracker.service.LocationTrackerService
import android.Manifest
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON"
            )) {
            val prefs = context.getSharedPreferences("gps_tracker_prefs", Context.MODE_PRIVATE)
            val wasRecording = prefs.getBoolean("is_recording", false)

            if (wasRecording) {
                // Verify permissions survive reboot (required on Android 10+)
                val hasLoc = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (hasLoc) {
                    // FIXED: Explicit intent creation without apply{} to avoid inference issues
                    val serviceIntent = Intent(context, LocationTrackerService::class.java)
                    serviceIntent.action = LocationTrackerService.ACTION_RESUME
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            }
        }
    }
}