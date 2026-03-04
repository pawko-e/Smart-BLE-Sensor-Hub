package com.appmstudio.bletutorial.data.repository

import com.appmstudio.bletutorial.data.db.SensorDao
import com.appmstudio.bletutorial.data.db.SensorReadingEntity
import com.appmstudio.bletutorial.domain.model.SensorReading
import com.appmstudio.bletutorial.domain.repository.SensorRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SensorRepositoryImpl(
    private val sensorDao: SensorDao
) : SensorRepository {
    override suspend fun insert(reading: SensorReading) {
        sensorDao.insert(
            SensorReadingEntity(
                deviceAddress = reading.deviceAddress,
                type = reading.type,
                value = reading.value,
                timestamp = reading.timestamp
            )
        )
    }

    override fun observe(deviceAddress: String, limit: Int): Flow<List<SensorReading>> {
        return sensorDao.observe(deviceAddress, limit)
            .map { list ->
                list.map {
                    SensorReading(
                        deviceAddress = it.deviceAddress,
                        type = it.type,
                        value = it.value,
                        timestamp = it.timestamp
                    )
                }
            }
    }
}
