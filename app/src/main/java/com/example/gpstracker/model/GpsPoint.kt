package com.example.gpstracker.model

data class GpsPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val timestamp: Long, // milliseconds since epoch
    val accuracy: Float,
    val isSynthetic: Boolean = false
)