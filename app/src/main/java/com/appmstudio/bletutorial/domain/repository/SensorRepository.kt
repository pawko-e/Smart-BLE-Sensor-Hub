package com.appmstudio.bletutorial.domain.repository

import com.appmstudio.bletutorial.domain.model.SensorReading
import kotlinx.coroutines.flow.Flow

interface SensorRepository {
    suspend fun insert(reading: SensorReading)
    fun observe(deviceAddress: String, limit: Int): Flow<List<SensorReading>>
}
