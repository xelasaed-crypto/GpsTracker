package com.example.gpstracker.util

import android.content.Context
import com.example.gpstracker.model.GpsPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object GpxExporter {
    private val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun exportToFile(context: Context, points: List<GpsPoint>): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "track_${System.currentTimeMillis()}.gpx")

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
        return file
    }
}