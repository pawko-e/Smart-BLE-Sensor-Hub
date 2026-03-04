package com.appmstudio.bletutorial.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.appmstudio.bletutorial.domain.model.BleDevice
import com.appmstudio.bletutorial.domain.model.BondState
import com.appmstudio.bletutorial.domain.model.ConnectionState
import com.appmstudio.bletutorial.domain.repository.BleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import javax.inject.Inject

@SuppressLint("MissingPermission")
class BleRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : BleRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    override val scannedDevices: Flow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()

    private val _bondState = MutableStateFlow<BondState>(BondState.NotBonded)
    override val bondState: Flow<BondState> = _bondState.asStateFlow()

    private val _mtu = MutableStateFlow(23)
    override val mtu: Flow<Int> = _mtu.asStateFlow()

    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val notifications: Flow<ByteArray> = _notifications.asSharedFlow()

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager?.adapter
    private val scanner = adapter?.bluetoothLeScanner

    private var scanCallback: ScanCallback? = null
    private val scanResults = ConcurrentHashMap<String, BleDevice>()
    private val deviceNameCache = ConcurrentHashMap<String, String>()

    private var gatt: BluetoothGatt? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private val operationChannel = Channel<GattOperation>(Channel.UNLIMITED)
    private var operationJob: Job? = null
    private var activeOperation: GattOperation? = null

    private var lastDeviceAddress: String? = null
    private var shouldReconnect: Boolean = false
    private var reconnectJob: Job? = null

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            _bondState.value = when (state) {
                BluetoothDevice.BOND_BONDED -> BondState.Bonded
                BluetoothDevice.BOND_BONDING -> BondState.Bonding
                else -> BondState.NotBonded
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        startOperationProcessor()
    }

    override fun startScan(nameFilter: String?) {
        if (scanner == null) {
            _connectionState.value = ConnectionState.Error("Bluetooth scanner unavailable")
            return
        }
        scanResults.clear()
        val normalizedNameFilter = nameFilter?.trim()?.takeIf { it.isNotEmpty() }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = resolveDeviceName(result, scanResults[device.address])
                if (name != UNKNOWN_DEVICE_NAME) {
                    cacheResolvedName(device.address, name)
                }
                if (normalizedNameFilter != null && !name.contains(normalizedNameFilter, ignoreCase = true)) {
                    return
                }
                val bleDevice = BleDevice(name, device.address, result.rssi)
                scanResults[device.address] = bleDevice
                _scannedDevices.value = scanResults.values.sortedByDescending { it.rssi }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                _connectionState.value = ConnectionState.Error("Scan failed: $errorCode")
            }
        }
        scanner.startScan(emptyList(), settings, scanCallback)
    }

    override fun stopScan() {
        val callback = scanCallback ?: return
        scanner?.stopScan(callback)
        scanCallback = null
    }

    override fun connect(address: String) {
        if (adapter == null) {
            _connectionState.value = ConnectionState.Error("Bluetooth adapter unavailable")
            return
        }
        stopScan()
        lastDeviceAddress = address
        shouldReconnect = true
        connectInternal(address)
    }

    private fun connectInternal(address: String) {
        val device = adapter?.getRemoteDevice(address)
        if (device == null) {
            _connectionState.value = ConnectionState.Error("Device not found")
            return
        }
        _bondState.value = when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> BondState.Bonded
            BluetoothDevice.BOND_BONDING -> BondState.Bonding
            else -> BondState.NotBonded
        }
        _connectionState.value = ConnectionState.Connecting
        gatt?.close()
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    override fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun read() {
        val characteristic = readCharacteristic ?: return
        operationChannel.send(GattOperation.Read(characteristic))
    }

    override suspend fun write(payload: ByteArray): Boolean {
        val characteristic = writeCharacteristic ?: return false
        return GattOperation.Write(characteristic, payload).also { operationChannel.send(it) }.awaitResult()
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean): Boolean {
        val characteristic = notifyCharacteristic ?: return false
        return GattOperation.EnableNotify(characteristic, enabled).also { operationChannel.send(it) }.awaitResult()
    }

    override suspend fun requestMtu(mtu: Int): Boolean {
        return GattOperation.RequestMtu(mtu).also { operationChannel.send(it) }.awaitResult()
    }

    override fun bond() {
        val address = lastDeviceAddress ?: return
        val device = adapter?.getRemoteDevice(address) ?: return
        device.createBond()
    }

    private fun startOperationProcessor() {
        if (operationJob != null) return
        operationJob = scope.launch {
            for (op in operationChannel) {
                val gattInstance = gatt
                if (gattInstance == null) {
                    op.completeFailure()
                    continue
                }
                activeOperation = op
                val started = when (op) {
                    is GattOperation.Read -> gattInstance.readCharacteristic(op.characteristic)
                    is GattOperation.Write -> {
                        op.characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        op.characteristic.value = op.payload
                        gattInstance.writeCharacteristic(op.characteristic)
                    }
                    is GattOperation.EnableNotify -> setNotify(gattInstance, op.characteristic, op.enable)
                    is GattOperation.RequestMtu -> gattInstance.requestMtu(op.mtu)
                }
                if (!started) {
                    op.completeFailure()
                    activeOperation = null
                    continue
                }
                val result = withTimeoutOrNull(10_000) { op.await() }
                if (result == null) {
                    op.completeFailure()
                    _connectionState.value = ConnectionState.Error("Operation timeout: ${op.description}")
                }
                activeOperation = null
            }
        }
    }

    private fun setNotify(
        gattInstance: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean
    ): Boolean {
        val success = gattInstance.setCharacteristicNotification(characteristic, enabled)
        val descriptor = characteristic.getDescriptor(BleConstants.CCCD_UUID)
        if (descriptor != null) {
            val enableValue = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            descriptor.value = if (enabled) enableValue else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            return success && gattInstance.writeDescriptor(descriptor)
        }
        return success
    }

    private fun onDisconnected(status: Int) {
        _connectionState.value = ConnectionState.Disconnected
        if (shouldReconnect && lastDeviceAddress != null) {
            scheduleReconnect()
        } else if (status != BluetoothGatt.GATT_SUCCESS) {
            _connectionState.value = ConnectionState.Error("Disconnected: $status")
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var delayMs = 1_000L
            while (shouldReconnect) {
                val address = lastDeviceAddress ?: break
                if (_connectionState.value is ConnectionState.Connected) break
                connectInternal(address)
                delayMs = (delayMs * 2).coerceAtMost(60_000L)
                delay(delayMs)
            }
        }
    }

    private fun mapCharacteristics(services: List<BluetoothGattService>) {
        val serviceByUuid = services.firstOrNull { it.uuid == BleConstants.SERVICE_UUID }
        val allChars = services.flatMap { it.characteristics }
        readCharacteristic = serviceByUuid?.getCharacteristic(BleConstants.CHAR_READ_UUID)
            ?: allChars.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
        writeCharacteristic = serviceByUuid?.getCharacteristic(BleConstants.CHAR_WRITE_UUID)
            ?: allChars.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                    it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            }
        notifyCharacteristic = serviceByUuid?.getCharacteristic(BleConstants.CHAR_NOTIFY_UUID)
            ?: allChars.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                    it.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            }
    }

    private fun resolveDeviceName(result: ScanResult, previous: BleDevice?): String {
        val discoveredName = sequenceOf(
            result.scanRecord?.deviceName,
            parseNameFromAdvertising(result.scanRecord?.bytes),
            result.device?.name
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
        if (!discoveredName.isNullOrEmpty()) return discoveredName

        val previousKnownName = previous?.name?.takeUnless { it == UNKNOWN_DEVICE_NAME }
        if (!previousKnownName.isNullOrBlank()) return previousKnownName

        val cachedName = deviceNameCache[result.device?.address]
        if (!cachedName.isNullOrBlank()) return cachedName

        val bondedName = adapter?.bondedDevices
            ?.firstOrNull { it.address == result.device?.address }
            ?.name
            ?.trim()
        if (!bondedName.isNullOrBlank()) return bondedName

        return UNKNOWN_DEVICE_NAME
    }

    private fun cacheResolvedName(address: String, name: String) {
        deviceNameCache[address] = name
        val existing = scanResults[address] ?: return
        if (existing.name == name) return
        scanResults[address] = existing.copy(name = name)
        _scannedDevices.value = scanResults.values.sortedByDescending { it.rssi }
    }

    private fun parseNameFromAdvertising(payload: ByteArray?): String? {
        if (payload == null || payload.isEmpty()) return null
        var index = 0
        while (index < payload.size) {
            val length = payload[index].toInt() and 0xFF
            if (length == 0) break
            val fieldStart = index + 1
            val fieldTypeIndex = fieldStart
            val fieldDataStart = fieldStart + 1
            val fieldDataEndExclusive = fieldStart + length
            if (fieldDataEndExclusive > payload.size) break
            val fieldType = payload[fieldTypeIndex].toInt() and 0xFF
            if (fieldType == 0x08 || fieldType == 0x09) {
                val size = fieldDataEndExclusive - fieldDataStart
                if (size > 0) {
                    return String(payload, fieldDataStart, size, StandardCharsets.UTF_8).trim()
                        .takeIf { it.isNotEmpty() }
                }
            }
            index += (length + 1)
        }
        return null
    }

    private fun readDeviceNameFromGatt(gatt: BluetoothGatt) {
        val genericAccess = gatt.getService(GENERIC_ACCESS_SERVICE_UUID) ?: return
        val deviceNameCharacteristic = genericAccess.getCharacteristic(DEVICE_NAME_CHAR_UUID) ?: return
        gatt.readCharacteristic(deviceNameCharacteristic)
    }

    private companion object {
        private const val UNKNOWN_DEVICE_NAME = "Unknown"
        private val GENERIC_ACCESS_SERVICE_UUID: UUID =
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        private val DEVICE_NAME_CHAR_UUID: UUID =
            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.Error("GATT error: $status")
                onDisconnected(status)
                return
            }
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                _connectionState.value = ConnectionState.Connected
                gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                onDisconnected(status)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mapCharacteristics(gatt.services)
                readDeviceNameFromGatt(gatt)
            } else {
                _connectionState.value = ConnectionState.Error("Service discovery failed: $status")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == DEVICE_NAME_CHAR_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                val payload = characteristic.value ?: ByteArray(0)
                val resolvedName = String(payload, StandardCharsets.UTF_8).trim()
                if (resolvedName.isNotEmpty()) {
                    cacheResolvedName(gatt.device.address, resolvedName)
                }
            }
            val op = activeOperation
            if (op is GattOperation.Read) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val payload = characteristic.value ?: ByteArray(0)
                    _notifications.tryEmit(payload)
                    op.complete(payload)
                } else {
                    op.completeFailure()
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val op = activeOperation
            if (op is GattOperation.Write) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    op.complete(true)
                } else {
                    op.complete(false)
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val op = activeOperation
            if (op is GattOperation.EnableNotify) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    op.complete(true)
                } else {
                    op.complete(false)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _mtu.value = mtu
            }
            val op = activeOperation
            if (op is GattOperation.RequestMtu) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    op.complete(true)
                } else {
                    op.complete(false)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            characteristic.value?.let { _notifications.tryEmit(it) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            _notifications.tryEmit(value)
        }
    }

    private sealed class GattOperation(val description: String) {
        abstract suspend fun await(): Any?
        abstract fun completeFailure()

        class Read(val characteristic: BluetoothGattCharacteristic) : GattOperation("read") {
            private val result = CompletableDeferred<ByteArray?>()

            suspend fun awaitResult(): Boolean {
                return await() != null
            }

            fun complete(value: ByteArray) {
                result.complete(value)
            }

            override suspend fun await(): ByteArray? = result.await()

            override fun completeFailure() {
                result.complete(null)
            }
        }

        class Write(
            val characteristic: BluetoothGattCharacteristic,
            val payload: ByteArray
        ) : GattOperation("write") {
            private val result = CompletableDeferred<Boolean>()

            suspend fun awaitResult(): Boolean = await() as Boolean

            fun complete(value: Boolean) {
                result.complete(value)
            }

            override suspend fun await(): Boolean = result.await()

            override fun completeFailure() {
                result.complete(false)
            }
        }

        class EnableNotify(
            val characteristic: BluetoothGattCharacteristic,
            val enable: Boolean
        ) : GattOperation("notify") {
            private val result = CompletableDeferred<Boolean>()

            suspend fun awaitResult(): Boolean = await() as Boolean

            fun complete(value: Boolean) {
                result.complete(value)
            }

            override suspend fun await(): Any = result.await()

            override fun completeFailure() {
                result.complete(false)
            }
        }

        class RequestMtu(val mtu: Int) : GattOperation("mtu") {
            private val result = CompletableDeferred<Boolean>()

            suspend fun awaitResult(): Boolean = await() as Boolean

            fun complete(value: Boolean) {
                result.complete(value)
            }

            override suspend fun await(): Any = result.await()

            override fun completeFailure() {
                result.complete(false)
            }
        }
    }
}
