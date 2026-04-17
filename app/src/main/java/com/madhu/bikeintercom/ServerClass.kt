package com.madhu.bikeintercom

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

class ServerClass(private val onSocketAccepted: (Socket) -> Unit) : Thread() {

    private var serverSocket: ServerSocket? = null

    override fun run() {
        try {
            serverSocket = ServerSocket(8888)
            while (!isInterrupted) {
                val socket = serverSocket?.accept()
                if (socket != null) {
                    onSocketAccepted(socket)
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            close()
        }
    }

    fun close() {
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
