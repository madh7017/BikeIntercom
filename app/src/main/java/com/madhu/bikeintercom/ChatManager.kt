package com.madhu.bikeintercom

import android.os.Handler
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Handles the actual data transfer between devices once a socket connection is established.
 */
class ChatManager(
    private val socket: Socket,
    private val handler: Handler
) : Thread() {

    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    init {
        try {
            inputStream = socket.getInputStream()
            outputStream = socket.getOutputStream()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun run() {
        val buffer = ByteArray(1024)
        var bytes: Int

        while (socket.isConnected) {
            try {
                bytes = inputStream?.read(buffer) ?: -1
                if (bytes > 0) {
                    // Send the received bytes to the UI thread via Handler
                    handler.obtainMessage(MESSAGE_READ, bytes, -1, buffer.copyOf(bytes)).sendToTarget()
                } else if (bytes == -1) {
                    break // Connection closed
                }
            } catch (e: IOException) {
                e.printStackTrace()
                break
            }
        }
        
        // Clean up when loop exits
        closeConnection()
    }

    /**
     * Sends data to the remote device.
     */
    fun write(bytes: ByteArray) {
        Thread {
            try {
                outputStream?.write(bytes)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }.start()
    }

    fun closeConnection() {
        try {
            socket.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    companion object {
        const val MESSAGE_READ = 1
    }
}
