package com.example.eva.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.eva.data.prefs.EvaPreferences
import com.example.eva.overlay.EvaOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Dedicated Android Foreground Service that runs the lightweight local wake-word detection model.
 * Enables hands-free interaction for EVA anywhere on the device without requiring open UI.
 */
class WakeWordDetectionService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        const val NOTIFICATION_CHANNEL_ID = "eva_wakeword_channel"
        const val NOTIFICATION_ID = 2002

        const val ACTION_START = "com.example.eva.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.example.eva.action.STOP_WAKE_WORD"
        const val EXTRA_TRIGGERED_FROM_WAKE = "com.example.eva.EXTRA_TRIGGERED_FROM_WAKE"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, WakeWordDetectionService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WakeWordDetectionService: ${e.message}", e)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, WakeWordDetectionService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop WakeWordDetectionService: ${e.message}", e)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var preferences: EvaPreferences
    private var localDetector: LocalWakeWordDetector? = null
    private var tts: EvaTextToSpeech? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        preferences = EvaPreferences(applicationContext)
        tts = EvaTextToSpeech(applicationContext)

        createNotificationChannel()
        startForegroundServiceNotification()

        initializeLocalDetector()

        // Observe settings changes (sensitivity & wake word enabled toggle)
        serviceScope.launch {
            preferences.settingsFlow.collectLatest { settings ->
                tts?.configure(settings.voiceSpeed, settings.voicePitch, settings.voiceLanguage)
                if (!settings.wakeWordEnabled) {
                    Log.i(TAG, "Wake word disabled in settings, stopping WakeWordDetectionService")
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundServiceNotification()
                localDetector?.start()
            }
        }
        return START_STICKY
    }

    private fun initializeLocalDetector() {
        localDetector?.stop()
        localDetector = LocalWakeWordDetector(
            context = applicationContext,
            sensitivity = 0.6f,
            onWakeWordDetected = { keyword, confidence ->
                handleWakeWordDetected(keyword, confidence)
            }
        ).apply {
            start()
        }
        Log.i(TAG, "Local lightweight wake word detection model initialized and running")
    }

    private fun handleWakeWordDetected(keyword: String, confidence: Float) {
        Log.i(TAG, ">>> Local Wake Word '$keyword' detected (conf: $confidence)! Triggering hands-free listening <<<")

        // 1. Sensory feedback
        WakeWordFeedback.triggerHaptic(applicationContext)
        WakeWordFeedback.playChime(applicationContext)

        // 2. Pause detector while processing/listening
        localDetector?.pause()

        // 3. Delegate to active overlay if running
        val overlay = EvaOverlayService.activeInstance
        if (overlay != null) {
            Log.d(TAG, "Delegating wake-word activation to active EvaOverlayService")
            // The overlay will handle speech response & voice listening
            serviceScope.launch {
                localDetector?.resume()
            }
            return
        }

        // 4. Standalone hands-free trigger: Speak prompt & launch hands-free interaction
        val readyPhrase = VoicePersonality.getWakeWordReadyPhrase()
        tts?.speak(readyPhrase) {
            serviceScope.launch(Dispatchers.Main) {
                // Open MainActivity with EXTRA_TRIGGERED_FROM_WAKE to immediately open voice listening
                val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
                    putExtra(EXTRA_TRIGGERED_FROM_WAKE, true)
                    putExtra(MainActivity.EXTRA_MANUAL_OPEN, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                try {
                    startActivity(openIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch MainActivity from wake-word: ${e.message}")
                }

                // Resume detection after a short cooldown
                kotlinx.coroutines.delay(4000L)
                localDetector?.resume()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "EVA Wake Word Detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps EVA's lightweight local wake-word detector active for hands-free 'Hi EVA' commands"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification() {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_MANUAL_OPEN, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, WakeWordDetectionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("EVA Hands-Free Wake Word Active")
            .setContentText("Local lightweight acoustic model listening for 'Hi EVA'")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Open EVA", pendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Turn Off", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            val hasMicPermission = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val fgsType = if (hasMicPermission) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (hasMicPermission) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed with types: ${e.message}, falling back")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Fatal startForeground error in WakeWordDetectionService: ${fallbackEx.message}")
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        try {
            serviceScope.cancel()
            localDetector?.destroy()
            localDetector = null
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e(TAG, "Error in WakeWordDetectionService onDestroy: ${e.message}")
        }
    }
}
