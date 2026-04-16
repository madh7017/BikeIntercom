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
import java.io.IOException
import java.net.Socket
import kotlin.math.abs

class VoiceChatManager(private val socket: Socket) {

    private val sampleRate = 16000 // Increased to 16kHz for better clarity (Wideband)
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
    private val bufferSize = minBufferSize // Use min buffer size for lowest possible latency

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var isRunning = false
    private var isMicMuted = false

    private var inputStream: java.io.DataInputStream? = null
    private var outputStream: java.io.DataOutputStream? = null
    
    var onLocationReceived: ((Double, Double) -> Unit)? = null

    init {
        try {
            socket.tcpNoDelay = true
            inputStream = java.io.DataInputStream(socket.getInputStream())
            outputStream = java.io.DataOutputStream(socket.getOutputStream())
        } catch (e: IOException) {
            Log.e("VoiceChatManager", "Error getting streams", e)
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    private val TYPE_AUDIO = 0.toByte()
    private val TYPE_LOCATION = 1.toByte()

    @SuppressLint("MissingPermission")
    fun startCommunication() {
        if (isRunning) return
        isRunning = true

        // Thread for Recording and Sending
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfigIn,
                audioFormat,
                bufferSize
            )
            
            // Enable hardware audio enhancements
            recorder?.audioSessionId?.let { sessionId ->
                if (AutomaticGainControl.isAvailable()) {
                    AutomaticGainControl.create(sessionId)?.setEnabled(true)
                }
                if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(sessionId)?.setEnabled(true)
                }
                if (AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(sessionId)?.setEnabled(true)
                }
            }
            
            val data = ByteArray(bufferSize)
            recorder?.startRecording()
            
            try {
                while (isRunning && !socket.isClosed) {
                    val read = recorder?.read(data, 0, bufferSize) ?: 0
                    if (read > 0 && !isMicMuted) {
                        // Check for voice activity (Simple Thresholding)
                        var max = 0
                        for (i in 0 until read - 1 step 2) {
                            val sample = ((data[i+1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort()
                            val amplitude = abs(sample.toInt())
                            if (amplitude > max) max = amplitude
                        }
                        
                        // Noise Gate: Only send if sound is loud enough
                        if (max > 600) { // Slightly lower threshold for helmet use
                            synchronized(outputStream!!) {
                                outputStream?.writeByte(TYPE_AUDIO.toInt())
                                outputStream?.writeInt(read)
                                outputStream?.write(data, 0, read)
                                outputStream?.flush()
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e("VoiceChatManager", "Recording error", e)
                FirebaseCrashlytics.getInstance().recordException(e)
            } finally {
                stopRecording()
            }
        }.start()

        // Thread for Receiving and Playing
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
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

            val receiveBuffer = ByteArray(bufferSize * 2)
            try {
                while (isRunning && !socket.isClosed) {
                    val type = inputStream?.readByte() ?: -1
                    if (type == TYPE_AUDIO) {
                        val length = inputStream?.readInt() ?: 0
                        if (length > 0 && length <= receiveBuffer.size) {
                            inputStream?.readFully(receiveBuffer, 0, length)
                            
                            // Apply Digital Gain (Volume Boost)
                            for (i in 0 until length - 1 step 2) {
                                val low = receiveBuffer[i].toInt() and 0xFF
                                val high = receiveBuffer[i+1].toInt()
                                var sample = ((high shl 8) or low).toShort().toInt()
                                
                                // Apply a 2.5x gain boost to help with low volume on some devices
                                sample = (sample * 2.5f).toInt()
                                
                                // Clip to valid short range
                                if (sample > 32767) sample = 32767
                                if (sample < -32768) sample = -32768
                                
                                receiveBuffer[i] = (sample and 0xFF).toByte()
                                receiveBuffer[i+1] = (sample shr 8).toByte()
                            }
                            
                            track?.write(receiveBuffer, 0, length)
                        } else if (length > receiveBuffer.size) {
                            inputStream?.skipBytes(length)
                        }
                    } else if (type == TYPE_LOCATION) {
                        val lat = inputStream?.readDouble() ?: 0.0
                        val lng = inputStream?.readDouble() ?: 0.0
                        onLocationReceived?.invoke(lat, lng)
                    }
                }
            } catch (e: Exception) {
                Log.e("VoiceChatManager", "Receiving error", e)
                FirebaseCrashlytics.getInstance().recordException(e)
            } finally {
                stopPlaying()
            }
        }.start()
    }

    fun sendLocation(lat: Double, lng: Double) {
        Thread {
            try {
                outputStream?.let {
                    synchronized(it) {
                        it.writeByte(TYPE_LOCATION.toInt())
                        it.writeDouble(lat)
                        it.writeDouble(lng)
                        it.flush()
                    }
                }
            } catch (e: Exception) {
                Log.e("VoiceChatManager", "Failed to send location")
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }.start()
    }

    fun stopCommunication() {
        isRunning = false
        stopRecording()
        stopPlaying()
        try {
            socket.close()
        } catch (e: IOException) {}
    }

    fun setMicMuted(muted: Boolean) {
        isMicMuted = muted
    }

    private fun stopRecording() {
        try {
            recorder?.stop()
            recorder?.release()
            recorder = null
        } catch (e: Exception) {}
    }

    private fun stopPlaying() {
        try {
            track?.stop()
            track?.release()
            track = null
        } catch (e: Exception) {}
    }
}
