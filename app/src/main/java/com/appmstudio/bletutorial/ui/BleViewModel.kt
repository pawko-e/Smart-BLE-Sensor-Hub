package com.appmstudio.bletutorial.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appmstudio.bletutorial.domain.model.BleDevice
import com.appmstudio.bletutorial.domain.model.BondState
import com.appmstudio.bletutorial.domain.model.ConnectionState
import com.appmstudio.bletutorial.domain.model.SensorReading
import com.appmstudio.bletutorial.domain.repository.BleRepository
import com.appmstudio.bletutorial.domain.repository.SensorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

@HiltViewModel
class BleViewModel @Inject constructor(
    private val bleRepository: BleRepository,
    private val sensorRepository: SensorRepository
) : ViewModel() {

    data class UiState(
        val devices: List<BleDevice> = emptyList(),
        val isScanning: Boolean = false,
        val selectedDevice: BleDevice? = null,
        val connectionState: ConnectionState = ConnectionState.Disconnected,
        val bondState: BondState = BondState.NotBonded,
        val mtu: Int = 23,
        val latestReading: SensorReading? = null,
        val history: List<SensorReading> = emptyList(),
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var historyJob: Job? = null

    init {
        viewModelScope.launch {
            bleRepository.scannedDevices.collect { devices ->
                _state.update { it.copy(devices = devices) }
            }
        }
        viewModelScope.launch {
            bleRepository.connectionState.collect { connection ->
                val message = if (connection is ConnectionState.Error) connection.message else null
                _state.update { it.copy(connectionState = connection, errorMessage = message) }
            }
        }
        viewModelScope.launch {
            bleRepository.bondState.collect { bond ->
                _state.update { it.copy(bondState = bond) }
            }
        }
        viewModelScope.launch {
            bleRepository.mtu.collect { mtu ->
                _state.update { it.copy(mtu = mtu) }
            }
        }
        viewModelScope.launch {
            bleRepository.notifications.collect { payload ->
                val device = _state.value.selectedDevice ?: return@collect
                val value = decodeToFloat(payload)
                val reading = SensorReading(
                    deviceAddress = device.address,
                    type = "notify",
                    value = value,
                    timestamp = System.currentTimeMillis()
                )
                sensorRepository.insert(reading)
                _state.update { it.copy(latestReading = reading) }
            }
        }
    }

    fun startScan(nameFilter: String?) {
        bleRepository.startScan(nameFilter)
        _state.update { it.copy(isScanning = true) }
    }

    fun stopScan() {
        bleRepository.stopScan()
        _state.update { it.copy(isScanning = false) }
    }

    fun connect(device: BleDevice) {
        _state.update { it.copy(selectedDevice = device, isScanning = false) }
        bleRepository.connect(device.address)
        observeHistory(device.address)
    }

    fun disconnect() {
        bleRepository.disconnect()
        _state.update { it.copy(selectedDevice = null, history = emptyList()) }
    }

    fun readNow() {
        viewModelScope.launch { bleRepository.read() }
    }

    fun writeNow(payload: ByteArray) {
        viewModelScope.launch { bleRepository.write(payload) }
    }

    fun setNotifications(enabled: Boolean) {
        viewModelScope.launch { bleRepository.setNotificationsEnabled(enabled) }
    }

    fun requestMtu(mtu: Int) {
        viewModelScope.launch { bleRepository.requestMtu(mtu) }
    }

    fun bond() {
        bleRepository.bond()
    }

    private fun observeHistory(address: String) {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            sensorRepository.observe(address, 200).collect { history ->
                _state.update { it.copy(history = history) }
            }
        }
    }

    private fun decodeToFloat(payload: ByteArray): Float {
        return when {
            payload.size >= 4 -> ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).float
            payload.size >= 2 -> ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).short.toFloat()
            payload.isNotEmpty() -> payload[0].toInt().toFloat()
            else -> 0f
        }
    }
}
