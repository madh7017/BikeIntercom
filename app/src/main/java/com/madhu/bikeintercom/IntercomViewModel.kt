package com.madhu.bikeintercom

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

enum class ConnectionStatus(val label: String) {
    READY("READY TO RIDE"),
    SEARCHING("SCANNING RIDERS..."),
    PAIRING("PAIRING..."),
    CONNECTED("LIVE INTERCOM"),
    FAILED("CONNECTION FAILED"),
    DISCONNECTED("DISCONNECTED")
}

class IntercomViewModel : ViewModel() {
    var riderName by mutableStateOf("RIDER-ONE")
    var connectionStatus by mutableStateOf(ConnectionStatus.READY)
    var isVoiceActive by mutableStateOf(false)
    var isSearching by mutableStateOf(false)
    var selectedDeviceAddress by mutableStateOf<String?>(null)
    var showTutorial by mutableStateOf(false)
    
    // Distance tracking
    var currentDeviceDistance by mutableStateOf<String?>(null)
    
    val devices = mutableStateListOf<WifiP2pDevice>()

    fun updateDevices(newDevices: List<WifiP2pDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        isSearching = false
        if (devices.isEmpty() && connectionStatus == ConnectionStatus.SEARCHING) {
            connectionStatus = ConnectionStatus.READY
        }
    }

    fun setStatus(status: ConnectionStatus) {
        connectionStatus = status
        if (status == ConnectionStatus.SEARCHING) isSearching = true
        if (status != ConnectionStatus.SEARCHING) isSearching = false
        if (status == ConnectionStatus.CONNECTED) isVoiceActive = true
        if (status == ConnectionStatus.DISCONNECTED || status == ConnectionStatus.FAILED) isVoiceActive = false
    }
}
