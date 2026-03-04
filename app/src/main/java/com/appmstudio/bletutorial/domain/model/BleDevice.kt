package com.appmstudio.bletutorial.domain.model

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int
)
