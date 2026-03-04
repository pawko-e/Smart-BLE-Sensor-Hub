package com.appmstudio.bletutorial.di

import android.content.Context
import androidx.room.Room
import com.appmstudio.bletutorial.ble.BleRepositoryImpl
import com.appmstudio.bletutorial.data.db.AppDatabase
import com.appmstudio.bletutorial.data.db.SensorDao
import com.appmstudio.bletutorial.data.repository.SensorRepositoryImpl
import com.appmstudio.bletutorial.domain.repository.BleRepository
import com.appmstudio.bletutorial.domain.repository.SensorRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "ble_sensors.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideSensorDao(database: AppDatabase): SensorDao = database.sensorDao()

    @Provides
    @Singleton
    fun provideSensorRepository(sensorDao: SensorDao): SensorRepository {
        return SensorRepositoryImpl(sensorDao)
    }

    @Provides
    @Singleton
    fun provideBleRepository(@ApplicationContext context: Context): BleRepository {
        return BleRepositoryImpl(context)
    }
}
