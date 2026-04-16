package com.madhu.bikeintercom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pManager

class WiFiDirectReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val activity: MainActivity
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    // Wi-Fi P2P is not enabled
                    activity.runOnUiThread {
                        // You might want to show a toast or update UI
                    }
                }
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                manager.requestPeers(channel) { peers ->
                    activity.updateDeviceList(peers.deviceList.toList())
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)

                if (networkInfo?.isConnected == true) {
                    manager.requestConnectionInfo(channel) { info ->
                        if (info.groupFormed) {
                            val hostAddress = info.groupOwnerAddress?.hostAddress
                            if (info.isGroupOwner) {
                                // Group Owner acts as Server
                                ServerClass { socket ->
                                    activity.startVoiceChat(socket)
                                }.start()
                            } else if (hostAddress != null) {
                                // Client connects to Group Owner
                                ClientClass(hostAddress) { socket ->
                                    activity.startVoiceChat(socket)
                                }.start()
                            }
                        }
                    }
                }
            }
        }
    }
}
