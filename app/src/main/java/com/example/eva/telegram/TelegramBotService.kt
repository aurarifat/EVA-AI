package com.example.eva.telegram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.eva.ai.AiRepository
import com.example.eva.context.CommandDispatcher
import com.example.eva.context.ContextManager
import com.example.eva.context.MultiStepEngine
import com.example.eva.data.prefs.EvaPreferences
import com.example.eva.data.prefs.SecureKeyStore
import com.example.eva.shizuku.AdbCapabilityManager
import com.example.eva.shizuku.ShizukuManager
import com.example.eva.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Foreground Service that runs the Telegram Bot polling loop in the background.
 * Ensures hands-free remote device control via Telegram even when the app is minimized.
 */
class TelegramBotService : Service() {

    companion object {
        private const val TAG = "TelegramBotService"
        const val NOTIFICATION_CHANNEL_ID = "eva_telegram_bot_channel"
        const val NOTIFICATION_ID = 2003

        const val ACTION_START = "com.example.eva.action.START_TELEGRAM_BOT"
        const val ACTION_STOP = "com.example.eva.action.STOP_TELEGRAM_BOT"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, TelegramBotService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start TelegramBotService: ${e.message}", e)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, TelegramBotService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop TelegramBotService: ${e.message}", e)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var botManager: TelegramBotManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForegroundInternal()
        initBotManager()
    }

    private fun initBotManager() {
        val app = applicationContext
        val preferences = EvaPreferences(app)
        val keyStore = SecureKeyStore(app)
        val contextManager = ContextManager()
        val multiStepEngine = MultiStepEngine()
        val aiRepository = AiRepository(app, preferences, keyStore)
        val shizukuManager = ShizukuManager(app)
        val adbManager = AdbCapabilityManager(app, shizukuManager)

        val toolRegistry = ToolRegistry(
            context = app,
            deviceTools = DeviceTools(app),
            appLauncherTools = AppLauncherTools(app),
            networkTools = NetworkTools(app),
            mediaAudioTools = MediaAudioTools(app),
            contactTools = ContactTools(app),
            pdfAssistant = PdfAssistant(app),
            shizukuManager = shizukuManager,
            adbCapabilityManager = adbManager,
            contextManager = contextManager,
            preferences = preferences
        )

        val commandDispatcher = CommandDispatcher(
            context = app,
            toolRegistry = toolRegistry,
            contextManager = contextManager,
            multiStepEngine = multiStepEngine,
            aiRepository = aiRepository,
            preferences = preferences
        )

        botManager = TelegramBotManager.getInstance(
            context = app,
            preferences = preferences,
            keyStore = keyStore,
            commandDispatcher = commandDispatcher
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            val preferences = EvaPreferences(applicationContext)
            val settings = preferences.settingsFlow.first()
            if (!settings.telegramEnabled) {
                Log.i(TAG, "Telegram disabled in preferences, stopping TelegramBotService")
                stopSelf()
                return@launch
            }

            botManager?.startBot()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "EVA Telegram Remote Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Telegram bot remote control active in background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundInternal() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            201,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TelegramBotService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            202,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("EVA Telegram Bot")
            .setContentText("Remote assistant active • Listening for owner commands")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error in TelegramBotService: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (ignored: Exception) {}
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        try {
            serviceScope.cancel()
            botManager?.stopBot()
        } catch (e: Exception) {
            Log.e(TAG, "Error in TelegramBotService onDestroy: ${e.message}")
        }
    }
}
