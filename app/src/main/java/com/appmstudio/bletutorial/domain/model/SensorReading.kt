package com.appmstudio.bletutorial.domain.model

data class SensorReading(
    val deviceAddress: String,
    val type: String,
    val value: Float,
    val timestamp: Long
)
