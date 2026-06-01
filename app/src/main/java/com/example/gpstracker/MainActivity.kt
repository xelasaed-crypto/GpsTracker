package com.example.gpstracker

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.gpstracker.service.LocationTrackerService
import com.example.gpstracker.ui.theme.GpsTrackerTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (!perms.entries.all { it.value }) {
            Toast.makeText(this, "Permissions required for tracking", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsLauncher.launch(perms.toTypedArray())
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
        requestPermissions()
        ContextCompat.startForegroundService(this, Intent(this, LocationTrackerService::class.java).apply {
            action = LocationTrackerService.ACTION_START
        })
    }

    private fun stopService() {
        ContextCompat.startForegroundService(this, Intent(this, LocationTrackerService::class.java).apply {
            action = LocationTrackerService.ACTION_STOP
        })
    }

    private fun exportTrack() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val gpx = dir.listFiles { f -> f.name.endsWith(".gpx") }?.sortedByDescending { it.lastModified() }
        if (gpx.isNullOrEmpty()) {
            Toast.makeText(this, "No tracks found", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", gpx.first())
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }, "Export GPX"))
    }
}

@Composable
private fun TrackerScreen(onStart: () -> Unit, onStop: () -> Unit, onExport: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("gps_tracker_prefs", android.content.Context.MODE_PRIVATE) }
    var isRecording by remember { mutableStateOf(prefs.getBoolean("is_recording", false)) }

    DisposableEffect(Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "is_recording") isRecording = prefs.getBoolean(key, false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // FIXED: Correct parameter order for Column
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Simple GPS Tracker", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))
        Text("Status: ${if (isRecording) "Recording..." else "Idle"}", style = MaterialTheme.typography.bodyLarge)
        if (isRecording) Text("Continues if screen off or device reboots", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(32.dp))

        Button(onStart, enabled = !isRecording) { Text("Start Recording") }
        Spacer(Modifier.height(16.dp))
        Button(onStop, enabled = isRecording) { Text("Stop Recording") }
        Spacer(Modifier.height(16.dp))
        Button(onExport, enabled = true) { Text("Export Track (.gpx)") }
    }
}