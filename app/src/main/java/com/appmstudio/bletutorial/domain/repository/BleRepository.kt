package com.appmstudio.bletutorial.domain.repository

import com.appmstudio.bletutorial.domain.model.BleDevice
import com.appmstudio.bletutorial.domain.model.BondState
import com.appmstudio.bletutorial.domain.model.ConnectionState
import kotlinx.coroutines.flow.Flow

interface BleRepository {
    val scannedDevices: Flow<List<BleDevice>>
    val connectionState: Flow<ConnectionState>
    val bondState: Flow<BondState>
    val mtu: Flow<Int>
    val notifications: Flow<ByteArray>

    fun startScan(nameFilter: String? = null)
    fun stopScan()

    fun connect(address: String)
    fun disconnect()

    suspend fun read()
    suspend fun write(payload: ByteArray): Boolean
    suspend fun setNotificationsEnabled(enabled: Boolean): Boolean
    suspend fun requestMtu(mtu: Int): Boolean
    fun bond()
}
