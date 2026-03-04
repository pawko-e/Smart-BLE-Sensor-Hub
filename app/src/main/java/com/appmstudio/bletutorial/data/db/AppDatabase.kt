package com.appmstudio.bletutorial.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
@Database(
    entities = [SensorReadingEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sensorDao(): SensorDao
}
