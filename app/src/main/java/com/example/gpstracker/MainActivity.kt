package com.example.gpstracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.gpstracker.service.LocationTrackerService
import com.example.gpstracker.ui.theme.GpsTrackerTheme
import java.io.File
import android.os.PowerManager
import android.provider.Settings

class MainActivity : ComponentActivity() {

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val allGranted = perms.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GpsTrackerTheme {
                Surface {
                    TrackerScreen(
                        onStart = { startService() },
                        onStop = { stopService() },
                        onExport = { exportTrack() }
                    )
                }
            }
        }
    }

    private fun startService() {
        requestAllPermissions()
        requestBackgroundLocation()
        ContextCompat.startForegroundService(
            this,
            Intent(this, LocationTrackerService::class.java).apply {
                action = LocationTrackerService.ACTION_START
            }
        )
    }

    private fun stopService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, LocationTrackerService::class.java).apply {
                action = LocationTrackerService.ACTION_STOP
            }
        )
    }

    private fun exportTrack() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val gpxFiles = dir.listFiles { f -> f.name.endsWith(".gpx") }?.sortedByDescending { it.lastModified() }
        if (gpxFiles.isNullOrEmpty()) {
            Toast.makeText(this, "No tracks found", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", gpxFiles.first())
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(Intent.createChooser(shareIntent, "Export GPX"))
    }

    private fun ensureBatteryOptimizationDisabled() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback for devices that block this intent
                Toast.makeText(this, "Please disable battery optimization for this app in Settings", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TrackerScreen(onStart: () -> Unit, onStop: () -> Unit, onExport: () -> Unit) {
    var isRecording by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Simple GPS Tracker", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))
        Text("Status: ${if (isRecording) "Recording..." else "Idle"}", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(32.dp))

        Button(onClick = { onStart(); isRecording = true }, enabled = !isRecording) {
            Text("Start Recording")
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onStop(); isRecording = false }, enabled = isRecording) {
            Text("Stop Recording")
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onExport, enabled = true) {
            Text("Export Track (.gpx)")
        }
    }
}