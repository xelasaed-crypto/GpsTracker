package com.example.gpstracker.util

import com.example.gpstracker.model.GpsPoint

object InterpolationEngine {
    /**
     * Inserts synthetic points where gaps exceed [gapThresholdMs].
     * Uses linear time-based interpolation. Safe, fast, GPX-compatible.
     */
    fun interpolate(points: List<GpsPoint>, gapThresholdMs: Long = 15_000L): List<GpsPoint> {
        if (points.size < 2) return points

        val sorted = points.sortedBy { it.timestamp }
        val result = mutableListOf<GpsPoint>().apply { add(sorted.first()) }

        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val curr = sorted[i]
            val gap = curr.timestamp - prev.timestamp

            result.add(curr)

            if (gap > gapThresholdMs) {
                val steps = (gap / 5_000L).toInt().coerceAtLeast(2) // 1 point per ~5s
                for (step in 1 until steps) {
                    val ratio = step.toDouble() / steps
                    result.add(
                        GpsPoint(
                            latitude = prev.latitude + (curr.latitude - prev.latitude) * ratio,
                            longitude = prev.longitude + (curr.longitude - prev.longitude) * ratio,
                            altitude = if (prev.altitude != null && curr.altitude != null) {
                                prev.altitude + (curr.altitude - prev.altitude) * ratio
                            } else null,
                            timestamp = prev.timestamp + (gap * step / steps),
                            accuracy = (prev.accuracy + curr.accuracy) / 2,
                            isSynthetic = true
                        )
                    )
                }
            }
        }
        return result
    }
}