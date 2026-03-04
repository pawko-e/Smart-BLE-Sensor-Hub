package com.appmstudio.bletutorial.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SensorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: SensorReadingEntity)

    @Query("SELECT * FROM sensor_readings WHERE deviceAddress = :deviceAddress ORDER BY timestamp DESC LIMIT :limit")
    fun observe(deviceAddress: String, limit: Int): Flow<List<SensorReadingEntity>>
}
