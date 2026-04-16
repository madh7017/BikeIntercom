package com.madhu.bikeintercom

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.os.BatteryManager
import kotlinx.coroutines.*
import java.net.Socket

class VoiceChatService : Service() {

    private var voiceChatManager: VoiceChatManager? = null
    private val binder = LocalBinder()
    private var audioManager: AudioManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var batteryJob: Job? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent?.action) {
                // Earphones unplugged, maybe pause or switch to earpiece
                audioManager?.isSpeakerphoneOn = false
            }
        }
    }
    
    var onLocationUpdate: ((Double, Double) -> Unit)? = null
    var onBatteryUpdate: ((Int) -> Unit)? = null

    companion object {
        const val CHANNEL_ID = "VoiceChatChannel"
        const val NOTIFICATION_ID = 1
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceChatService = this@VoiceChatService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun startVoiceChat(socket: Socket) {
        // Configure audio for intercom
        audioManager?.apply {
            mode = AudioManager.MODE_IN_COMMUNICATION
            startBluetoothSco()
            isBluetoothScoOn = true
            isSpeakerphoneOn = true // Fallback to speaker if no headset
        }

        voiceChatManager?.stopCommunication()
        voiceChatManager = VoiceChatManager(socket)
        
        voiceChatManager?.onLocationReceived = { lat, lng ->
            onLocationUpdate?.invoke(lat, lng)
        }

        voiceChatManager?.onBatteryReceived = { level ->
            onBatteryUpdate?.invoke(level)
        }
        
        voiceChatManager?.startCommunication()
        startBatteryBroadcasting()
        
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun sendLocation(lat: Double, lng: Double) {
        voiceChatManager?.sendLocation(lat, lng)
    }

    fun stopVoiceChat() {
        // Reset audio settings
        audioManager?.apply {
            isBluetoothScoOn = false
            stopBluetoothSco()
            mode = AudioManager.MODE_NORMAL
            isSpeakerphoneOn = false
        }

        voiceChatManager?.stopCommunication()
        voiceChatManager = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    fun setMicMuted(muted: Boolean) {
        voiceChatManager?.setMicMuted(muted)
    }

    private fun createNotification(): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ride Comms Active")
            .setContentText("Connected and streaming audio")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ride Intercom Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startBatteryBroadcasting() {
        batteryJob?.cancel()
        batteryJob = serviceScope.launch {
            while (isActive) {
                val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                voiceChatManager?.sendBatteryLevel(level)
                delay(300000) // 5 minutes
            }
        }
    }

    override fun onDestroy() {
        batteryJob?.cancel()
        voiceChatManager?.stopCommunication()
        try {
            unregisterReceiver(noisyReceiver)
        } catch (e: Exception) {}
        super.onDestroy()
    }
}
