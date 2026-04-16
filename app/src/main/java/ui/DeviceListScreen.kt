package com.madhu.bikeintercom.ui

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DeviceListScreen(
    devices: List<WifiP2pDevice>,
    onClick: (WifiP2pDevice) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        Text(text = "Nearby Devices")

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn {
            items(devices) { device ->
                Text(
                    text = device.deviceName ?: "Unknown Device",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .clickable { onClick(device) }
                )
            }
        }
    }
}