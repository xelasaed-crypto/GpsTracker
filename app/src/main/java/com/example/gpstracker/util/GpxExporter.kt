package com.example.gpstracker.util

import android.content.Context
import android.util.Log
import com.example.gpstracker.model.GpsPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object GpxExporter {
    private const val TAG = "GpxExporter"
    private val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Exports points to a GPX file and returns the absolute file path.
     * Returns null if export fails.
     */
    fun exportToFile(context: Context, points: List<GpsPoint>): String? {
        return try {
            // Use app-specific external directory (no storage permissions needed)
            val dir = context.getExternalFilesDir(null) ?: context.filesDir

            // Ensure directory exists
            if (!dir.exists()) dir.mkdirs()

            val fileName = "track_${System.currentTimeMillis()}.gpx"
            val file = File(dir, fileName)

            Log.d(TAG, "Exporting GPX to: ${file.absolutePath}")

            file.bufferedWriter().use { writer ->
                writer.write("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="SimpleGPSTracker" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <trkseg>
""".trimIndent())

                points.forEach { pt ->
                    val alt = pt.altitude?.let { "<ele>$it</ele>" } ?: ""
                    val time = sdf.format(pt.timestamp)
                    writer.write(
                        "      <trkpt lat=\"${pt.latitude}\" lon=\"${pt.longitude}\">" +
                                "$alt<time>$time</time></trkpt>\n"
                    )
                }

                writer.write("    </trkseg>\n  </trk>\n</gpx>")
            }

            Log.d(TAG, "GPX export successful: ${file.length()} bytes")
            file.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "GPX export failed", e)
            null
        }
    }

    /**
     * Helper to find the most recent GPX file in app storage
     */
    fun findLatestGpx(context: Context): File? {
        val dirs = listOfNotNull(
            context.getExternalFilesDir(null),
            context.filesDir
        )

        for (dir in dirs) {
            val gpxFiles = dir.listFiles { f -> f.name.endsWith(".gpx") }
                ?.sortedByDescending { it.lastModified() }
            if (!gpxFiles.isNullOrEmpty()) {
                Log.d(TAG, "Found latest GPX: ${gpxFiles.first().absolutePath}")
                return gpxFiles.first()
            }
        }
        Log.w(TAG, "No GPX files found in app storage")
        return null
    }
}