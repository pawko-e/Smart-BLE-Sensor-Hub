package com.appmstudio.bletutorial.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "sensor_readings")
data class SensorReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val type: String,
    val value: Float,
    val timestamp: Long
)
