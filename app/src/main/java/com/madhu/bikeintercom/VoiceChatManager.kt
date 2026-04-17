package com.madhu.bikeintercom

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

class VoiceChatManager {

    private val sampleRate = 16000
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
    private val bufferSize = minBufferSize

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var isRunning = false
    private var isMicMuted = false

    private val sockets = CopyOnWriteArrayList<Socket>()
    private val outputStreams = CopyOnWriteArrayList<DataOutputStream>()
    
    var onLocationReceived: ((Double, Double) -> Unit)? = null
    var onBatteryReceived: ((Int) -> Unit)? = null

    private val TYPE_AUDIO = 0.toByte()
    private val TYPE_LOCATION = 1.toByte()
    private val TYPE_BATTERY = 2.toByte()

    fun addSocket(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            sockets.add(socket)
            val outputStream = DataOutputStream(socket.getOutputStream())
            outputStreams.add(outputStream)
            
            // Start a receiver thread for this specific socket
            startReceiverThread(socket)
        } catch (e: IOException) {
            Log.e("VoiceChatManager", "Error adding socket", e)
        }
    }

    private fun startReceiverThread(socket: Socket) {
        Thread {
            val inputStream = DataInputStream(socket.getInputStream())
            val receiveBuffer = ByteArray(bufferSize * 2)
            try {
                while (isRunning && !socket.isClosed) {
                    val type = try { inputStream.readByte() } catch (e: Exception) { -1 }
                    if (type == (-1).toByte()) break
                    
                    when (type) {
                        TYPE_AUDIO -> {
                            val length = inputStream.readInt()
                            if (length > 0 && length <= receiveBuffer.size) {
                                inputStream.readFully(receiveBuffer, 0, length)
                                
                                // Broadcast this audio to all OTHER connected riders
                                broadcastToOthers(socket, TYPE_AUDIO, receiveBuffer, length)
                                
                                // Also play it locally
                                playAudioLocally(receiveBuffer, length)
                            }
                        }
                        TYPE_LOCATION -> {
                            val lat = inputStream.readDouble()
                            val lng = inputStream.readDouble()
                            onLocationReceived?.invoke(lat, lng)
                        }
                        TYPE_BATTERY -> {
                            val level = inputStream.readInt()
                            onBatteryReceived?.invoke(level)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VoiceChatManager", "Receiver thread error", e)
            } finally {
                removeSocket(socket)
            }
        }.start()
    }

    private fun broadcastToOthers(senderSocket: Socket, type: Byte, data: ByteArray, length: Int) {
        for (i in sockets.indices) {
            val socket = sockets[i]
            if (socket != senderSocket && !socket.isClosed) {
                try {
                    val os = outputStreams[i]
                    synchronized(os) {
                        os.writeByte(type.toInt())
                        if (type == TYPE_AUDIO) {
                            os.writeInt(length)
                            os.write(data, 0, length)
                        } else if (type == TYPE_BATTERY) {
                            // length is repurposed as level for battery
                            os.writeInt(length)
                        }
                        os.flush()
                    }
                } catch (e: IOException) {
                    removeSocket(socket)
                }
            }
        }
    }

    private fun playAudioLocally(receiveBuffer: ByteArray, length: Int) {
        // Apply Digital Gain (Volume Boost)
        for (i in 0 until length - 1 step 2) {
            val low = receiveBuffer[i].toInt() and 0xFF
            val high = receiveBuffer[i+1].toInt()
            var sample = ((high shl 8) or low).toShort().toInt()
            sample = (sample * 2.5f).toInt()
            if (sample > 32767) sample = 32767
            if (sample < -32768) sample = -32768
            receiveBuffer[i] = (sample and 0xFF).toByte()
            receiveBuffer[i+1] = (sample shr 8).toByte()
        }
        track?.write(receiveBuffer, 0, length)
    }

    @SuppressLint("MissingPermission")
    fun startCommunication() {
        if (isRunning) return
        isRunning = true

        // Thread for Playing
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfigOut)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()

        // Thread for Recording and Sending (Local Mic)
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfigIn,
                audioFormat,
                bufferSize
            )
            
            recorder?.audioSessionId?.let { sessionId ->
                if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId)?.setEnabled(true)
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.setEnabled(true)
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.setEnabled(true)
            }
            
            val data = ByteArray(bufferSize)
            recorder?.startRecording()
            
            try {
                while (isRunning) {
                    val read = recorder?.read(data, 0, bufferSize) ?: 0
                    if (read > 0 && !isMicMuted) {
                        var max = 0
                        for (i in 0 until read - 1 step 2) {
                            val sample = ((data[i+1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort()
                            val amplitude = abs(sample.toInt())
                            if (amplitude > max) max = amplitude
                        }
                        
                        if (max > 600) {
                            // Send local mic audio to EVERYONE
                            for (os in outputStreams) {
                                try {
                                    synchronized(os) {
                                        os.writeByte(TYPE_AUDIO.toInt())
                                        os.writeInt(read)
                                        os.write(data, 0, read)
                                        os.flush()
                                    }
                                } catch (e: IOException) {}
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e("VoiceChatManager", "Recording error", e)
            } finally {
                stopRecording()
            }
        }.start()
    }

    fun sendLocation(lat: Double, lng: Double) {
        for (os in outputStreams) {
            try {
                synchronized(os) {
                    os.writeByte(TYPE_LOCATION.toInt())
                    os.writeDouble(lat)
                    os.writeDouble(lng)
                    os.flush()
                }
            } catch (e: Exception) {}
        }
    }

    fun sendBatteryLevel(level: Int) {
        for (os in outputStreams) {
            try {
                synchronized(os) {
                    os.writeByte(TYPE_BATTERY.toInt())
                    os.writeInt(level)
                    os.flush()
                }
            } catch (e: Exception) {}
        }
    }

    private fun removeSocket(socket: Socket) {
        val index = sockets.indexOf(socket)
        if (index != -1) {
            sockets.removeAt(index)
            outputStreams.removeAt(index)
        }
        try { socket.close() } catch (e: IOException) {}
    }

    fun stopCommunication() {
        isRunning = false
        for (socket in sockets) removeSocket(socket)
        stopRecording()
        stopPlaying()
    }

    fun setMicMuted(muted: Boolean) { isMicMuted = muted }

    private fun stopRecording() {
        try { recorder?.stop(); recorder?.release(); recorder = null } catch (e: Exception) {}
    }

    private fun stopPlaying() {
        try { track?.stop(); track?.release(); track = null } catch (e: Exception) {}
    }
}
