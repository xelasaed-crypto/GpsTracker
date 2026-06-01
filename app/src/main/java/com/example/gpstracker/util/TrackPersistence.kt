package com.example.gpstracker.util

import android.content.Context
import com.example.gpstracker.model.GpsPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object TrackPersistence {
    private const val FILE_NAME = "current_track.json"

    fun savePoints(context: Context, points: List<GpsPoint>) {
        val file = File(context.filesDir, FILE_NAME)
        val jsonPoints = JSONArray()
        points.forEach { pt ->
            val obj = JSONObject().apply {
                put("lat", pt.latitude)
                put("lon", pt.longitude)
                pt.altitude?.let { put("alt", it) }
                put("ts", pt.timestamp)
                put("acc", pt.accuracy)
                put("synth", pt.isSynthetic)
            }
            jsonPoints.put(obj)
        }
        file.writeText(jsonPoints.toString())
    }

    fun loadPoints(context: Context): List<GpsPoint> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                GpsPoint(
                    latitude = obj.getDouble("lat"),
                    longitude = obj.getDouble("lon"),
                    altitude = if (obj.has("alt")) obj.getDouble("alt") else null,
                    timestamp = obj.getLong("ts"),
                    // FIXED: Use getDouble().toFloat() since JSONObject has no getFloat()
                    accuracy = obj.getDouble("acc").toFloat(),
                    isSynthetic = obj.optBoolean("synth", false)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearTrack(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }
}