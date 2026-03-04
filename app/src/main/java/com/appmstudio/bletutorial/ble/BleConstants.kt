package com.appmstudio.bletutorial.ble

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("0000181A-0000-1000-8000-00805F9B34FB")
    val CHAR_READ_UUID: UUID = UUID.fromString("00002A6E-0000-1000-8000-00805F9B34FB")
    val CHAR_WRITE_UUID: UUID = UUID.fromString("00002A7E-0000-1000-8000-00805F9B34FB")
    val CHAR_NOTIFY_UUID: UUID = UUID.fromString("00002A6E-0000-1000-8000-00805F9B34FB")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val NOTIFICATION_CHANNEL_ID = "ble_connection_channel"
    const val NOTIFICATION_ID = 1001
}
