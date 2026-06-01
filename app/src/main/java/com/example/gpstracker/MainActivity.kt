package com.example.gpstracker

import android.Manifest
import android.app.AlertDialog  // ← Fixed: use android.app, not androidx.appcompat
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager  // ← Added: missing import
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings  // ← Added: for ACTION_APPLICATION_DETAILS_SETTINGS
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.gpstracker.service.LocationTrackerService
import com.example.gpstracker.ui.theme.GpsTrackerTheme
import java.io.File
import android.util.Log                 // ← Fix: missing Log import
class MainActivity : ComponentActivity() {

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (!perms.entries.all { it.value }) {
            Toast.makeText(this, "Permissions required for tracking", Toast.LENGTH_LONG).show()
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

    /**
     * Android 15/16-safe sequential permission request
     */
    private fun requestPermissionsSequentially(onAllGranted: () -> Unit) {
        val basePerms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            basePerms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingBase = basePerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED  // ← Fixed import
        }

        if (missingBase.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Toast.makeText(this, "This app needs background location to track when screen is off", Toast.LENGTH_LONG).show()
            }
            permissionsLauncher.launch(missingBase.toTypedArray())
            return
        }

        // Request background location SEPARATELY (Android 11+, enforced on 15/16)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED  // ← Fixed import
            ) {
                // Use android.app.AlertDialog (not androidx.appcompat)
                AlertDialog.Builder(this)  // ← Fixed: no appcompat dependency needed
                    .setTitle("Allow background location?")
                    .setMessage("To continue tracking when the app is closed or screen is off, please allow \"Allow all the time\" location access.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        // Fixed: use Settings.ACTION_APPLICATION_DETAILS_SETTINGS + setData()
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {  // ← Fixed import + usage
                            data = Uri.fromParts("package", packageName, null)  // ← Fixed: use data =
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
        }

        onAllGranted()
    }

    private fun startService() {
        requestPermissionsSequentially {
            ContextCompat.startForegroundService(
                this,
                Intent(this, LocationTrackerService::class.java).apply {
                    action = LocationTrackerService.ACTION_START
                }
            )
        }
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
        Log.d("MainActivity", "exportTrack called")

        // First, try the automatic method
        val gpxFile = com.example.gpstracker.util.GpxExporter.findLatestGpx(this)

        if (gpxFile != null && gpxFile.exists()) {
            Log.d("MainActivity", "Found GPX automatically: ${gpxFile.absolutePath}")
            shareGpxFile(gpxFile)
            return
        }

        Log.w("MainActivity", "Auto-search failed, trying manual directory scan...")

        // Fallback: manually scan both directories
        val candidates = mutableListOf<File>()

        // Check external files dir
        getExternalFilesDir(null)?.let { extDir ->
            if (extDir.exists()) {
                extDir.listFiles { f -> f.name.endsWith(".gpx") }?.let { files ->
                    candidates.addAll(files)
                    Log.d("MainActivity", "Found ${files.size} GPX files in external: ${extDir.absolutePath}")
                }
            }
        }

        // Check internal files dir
        if (filesDir.exists()) {
            filesDir.listFiles { f -> f.name.endsWith(".gpx") }?.let { files ->
                candidates.addAll(files)
                Log.d("MainActivity", "Found ${files.size} GPX files in internal: ${filesDir.absolutePath}")
            }
        }

        if (candidates.isEmpty()) {
            // Show detailed debug dialog
            val debugInfo = buildString {
                append("No GPX file found after scanning.\n\n")
                append("Directories checked:\n")
                append("• External: ${getExternalFilesDir(null)?.absolutePath}\n")
                append("• Internal: ${filesDir.absolutePath}\n")
                append("\nPossible causes:\n")
                append("1. You didn't stop recording first\n")
                append("2. Service crashed before saving\n")
                append("3. Android 16 blocked the write\n\n")
                append("Check logcat for 'GpxExporter' or 'LocationTrackerService' tags.")
            }
            AlertDialog.Builder(this)
                .setTitle("Export Failed")
                .setMessage(debugInfo)
                .setPositiveButton("OK", null)
                .setNeutralButton("View Logcat") { _, _ ->
                    // Optional: open a logcat viewer app if installed
                    try {
                        startActivity(Intent("android.intent.action.VIEW").apply {
                            data = Uri.parse("logcat://")
                        })
                    } catch (_: Exception) {}
                }
                .show()
            return
        }

        // Multiple files found: let user pick
        if (candidates.size > 1) {
            candidates.sortByDescending { it.lastModified() }
            val fileNames = candidates.map { "${it.name} (${it.length() / 1024} KB)" }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Select GPX File")
                .setItems(fileNames) { _, which ->
                    shareGpxFile(candidates[which])
                }
                .show()
        } else {
            // Single file found
            shareGpxFile(candidates.first())
        }
    }

    /**
     * Helper to share a GPX file via FileProvider
     */
    private fun shareGpxFile(file: File) {
        try {
            Log.d("MainActivity", "Sharing GPX: ${file.absolutePath}")
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(Intent.createChooser(shareIntent, "Export GPX"))
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to share GPX", e)
            Toast.makeText(this, "Share error: ${e.message}", Toast.LENGTH_LONG).show()
        }
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