package com.appmstudio.bletutorial.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.appmstudio.bletutorial.ble.BleForegroundService
import com.appmstudio.bletutorial.domain.model.BleDevice
import com.appmstudio.bletutorial.domain.model.BondState
import com.appmstudio.bletutorial.domain.model.ConnectionState
import com.appmstudio.bletutorial.domain.model.SensorReading

@Composable
fun BleScreen(
    paddingValues: PaddingValues,
    preview: Boolean = false
) {
    if (preview) {
        BleScreenContent(
            paddingValues = paddingValues,
            state = BleViewModel.UiState(
                devices = listOf(
                    BleViewModel.ScannedDeviceItem(
                        device = BleDevice("Demo Sensor", "AA:BB:CC:DD:EE:FF", -42),
                        status = BleViewModel.ScanStatus.Live,
                        seenAgoMs = 300
                    ),
                    BleViewModel.ScannedDeviceItem(
                        device = BleDevice("Weather Tag", "11:22:33:44:55:66", -60),
                        status = BleViewModel.ScanStatus.Stale,
                        seenAgoMs = 6_200
                    )
                ),
                selectedDevice = BleDevice("Demo Sensor", "AA:BB:CC:DD:EE:FF", -42),
                connectionState = ConnectionState.Connected,
                bondState = BondState.Bonded,
                mtu = 247,
                latestReading = SensorReading("AA:BB", "notify", 23.4f, System.currentTimeMillis()),
                history = List(10) { index ->
                    SensorReading("AA:BB", "notify", 20f + index, System.currentTimeMillis() - index * 60000L)
                }
            ),
            onStartScan = {},
            onStopScan = {},
            onConnect = {},
            onRead = {},
            onWrite = {},
            onSetNotify = {},
            onRequestMtu = {},
            onBond = {},
            onDisconnect = {}
        )
    } else {
        val viewModel: BleViewModel = hiltViewModel()
        val state by viewModel.state.collectAsState()
        val context = LocalContext.current

        val permissions = remember {
            if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        var pendingFilter by remember { mutableStateOf<String?>(null) }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result.values.all { it }) {
                viewModel.startScan(pendingFilter)
            }
        }

        LaunchedEffect(state.connectionState) {
            val connected = state.connectionState is ConnectionState.Connected
            val serviceIntent = Intent(context, BleForegroundService::class.java)
            if (connected) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.stopService(serviceIntent)
            }
        }

        BleScreenContent(
            paddingValues = paddingValues,
            state = state,
            onStartScan = { filter ->
                pendingFilter = filter
                permissionLauncher.launch(permissions)
            },
            onStopScan = { viewModel.stopScan() },
            onConnect = { viewModel.connect(it) },
            onRead = { viewModel.readNow() },
            onWrite = { viewModel.writeNow(it) },
            onSetNotify = { viewModel.setNotifications(it) },
            onRequestMtu = { viewModel.requestMtu(247) },
            onBond = { viewModel.bond() },
            onDisconnect = { viewModel.disconnect() }
        )
    }
}

@Composable
private fun BleScreenContent(
    paddingValues: PaddingValues,
    state: BleViewModel.UiState,
    onStartScan: (String?) -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onRead: () -> Unit,
    onWrite: (ByteArray) -> Unit,
    onSetNotify: (Boolean) -> Unit,
    onRequestMtu: () -> Unit,
    onBond: () -> Unit,
    onDisconnect: () -> Unit
) {
    var nameFilter by remember { mutableStateOf("") }
    var writePayload by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .padding(paddingValues)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Smart Sensor Hub", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Discovery", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = nameFilter,
                    onValueChange = { nameFilter = it },
                    label = { Text("Name filter (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onStartScan(nameFilter.ifBlank { null }) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state.isScanning) "Scanning..." else "Start scan")
                    }
                    TextButton(
                        onClick = onStopScan,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Stop")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Devices", fontWeight = FontWeight.SemiBold)
                if (state.devices.isEmpty()) {
                    Text("No devices yet")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(220.dp)
                    ) {
                        items(
                            items = state.devices,
                            key = { it.device.address }
                        ) { scanned ->
                            DeviceRow(scanned = scanned, onConnect = { onConnect(scanned.device) })
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connection", fontWeight = FontWeight.SemiBold)
                Text("State: ${state.connectionState}")
                Text("MTU: ${state.mtu}")
                Text("Bond: ${state.bondState}")
                state.errorMessage?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                state.selectedDevice?.let {
                    Text("Device: ${it.name} (${it.address})")
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRead, modifier = Modifier.fillMaxWidth()) { Text("Read") }
                    Button(
                        onClick = { onSetNotify(true) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Enable notify") }
                    Button(
                        onClick = { onSetNotify(false) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Disable notify") }
                    Button(onClick = onRequestMtu, modifier = Modifier.fillMaxWidth()) { Text("Request MTU 247") }
                    Button(onClick = onBond, modifier = Modifier.fillMaxWidth()) { Text("Bond") }
                    TextButton(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Disconnect") }
                }
                Divider()
                OutlinedTextField(
                    value = writePayload,
                    onValueChange = { writePayload = it },
                    label = { Text("Write payload") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { onWrite(writePayload.toByteArray()) }) {
                    Text("Write")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Live data", fontWeight = FontWeight.SemiBold)
                Text("Latest: ${state.latestReading?.value ?: "--"}")
                LineChart(readings = state.history)
            }
        }
    }
}

@Composable
private fun DeviceRow(scanned: BleViewModel.ScannedDeviceItem, onConnect: () -> Unit) {
    val statusColor = when (scanned.status) {
        BleViewModel.ScanStatus.New -> MaterialTheme.colorScheme.primary
        BleViewModel.ScanStatus.Live -> Color(0xFF2E7D32)
        BleViewModel.ScanStatus.Stale -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = when (scanned.status) {
        BleViewModel.ScanStatus.New -> "New"
        BleViewModel.ScanStatus.Live -> "Live"
        BleViewModel.ScanStatus.Stale -> "Stale"
    }
    val seenSeconds = scanned.seenAgoMs / 1000

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (scanned.status == BleViewModel.ScanStatus.Stale) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column {
                Text(scanned.device.name, fontWeight = FontWeight.SemiBold)
                Text(scanned.device.address, style = MaterialTheme.typography.bodySmall)
                Text("RSSI: ${scanned.device.rssi}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Status: $statusLabel${if (seenSeconds > 0) " ($seenSeconds s ago)" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
            }
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Connect") }
        }
    }
}

@Composable
private fun LineChart(readings: List<SensorReading>) {
    if (readings.isEmpty()) {
        Text("No history yet", style = MaterialTheme.typography.bodySmall)
        return
    }
    val values = readings.map { it.value }.sorted()
    val min = values.first()
    val max = values.last()
    val range = (max - min).takeIf { it > 0f } ?: 1f

    Canvas(modifier = Modifier
        .fillMaxWidth()
        .height(160.dp)) {
        val width = size.width
        val height = size.height
        val points = readings.sortedBy { it.timestamp }.mapIndexed { index, reading ->
            val x = width * (index.toFloat() / (readings.size - 1).coerceAtLeast(1))
            val y = height - ((reading.value - min) / range) * height
            Offset(x, y)
        }
        val path = Path()
        points.forEachIndexed { index, offset ->
            if (index == 0) {
                path.moveTo(offset.x, offset.y)
            } else {
                path.lineTo(offset.x, offset.y)
            }
        }
        drawPath(
            path = path,
            color = Color(0xFF0D6EFD),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
        )
        points.forEach {
            drawCircle(color = Color(0xFF0D6EFD), radius = 6f, center = it)
        }
    }
    Spacer(modifier = Modifier.size(4.dp))
}
