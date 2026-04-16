package com.madhu.bikeintercom

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class ClientClass(
    private val hostAddress: String,
    private val onConnected: (Socket) -> Unit
) : Thread() {

    private var socket: Socket? = null

    override fun run() {
        try {
            socket = Socket()
            socket?.connect(InetSocketAddress(hostAddress, 8888), 5000)
            socket?.let {
                if (it.isConnected) {
                    onConnected(it)
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
