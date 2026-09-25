package com.example.eva.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.eva.ai.AiRepository
import com.example.eva.context.CommandDispatcher
import com.example.eva.context.ContextManager
import com.example.eva.context.MultiStepEngine
import com.example.eva.data.database.EvaDatabase
import com.example.eva.data.prefs.EvaPreferences
import com.example.eva.data.prefs.SecureKeyStore
import com.example.eva.shizuku.AdbCapabilityManager
import com.example.eva.shizuku.DeviceScreenAutomation
import com.example.eva.shizuku.ShizukuManager
import com.example.eva.tools.*
import com.example.eva.voice.EvaSpeechRecognizer
import com.example.eva.voice.EvaTextToSpeech
import com.example.eva.voice.VoiceState
import kotlinx.coroutines.*
import kotlin.math.abs

class EvaOverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    private lateinit var shizukuManager: ShizukuManager
    private lateinit var screenAutomation: DeviceScreenAutomation
    private lateinit var speechRecognizer: EvaSpeechRecognizer
    private lateinit var tts: EvaTextToSpeech
    private lateinit var commandDispatcher: CommandDispatcher

    private lateinit var statusText: TextView
    private lateinit var bubbleIcon: ImageView
    private lateinit var statusBadge: LinearLayout
    private lateinit var quickActionsRow: LinearLayout

    private var isExpanded = false
    private var isProcessing = false

    companion object {
        const val ACTION_START_OVERLAY = "com.example.eva.action.START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.example.eva.action.STOP_OVERLAY"
        private const val NOTIFICATION_CHANNEL_ID = "eva_overlay_channel"
        private const val NOTIFICATION_ID = 2001

        fun isOverlayPermissionGranted(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true
        }

        fun startOverlay(context: Context) {
            val intent = Intent(context, EvaOverlayService::class.java).apply {
                action = ACTION_START_OVERLAY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopOverlay(context: Context) {
            val intent = Intent(context, EvaOverlayService::class.java).apply {
                action = ACTION_STOP_OVERLAY
            }
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        initDependencies()
        createNotificationChannel()
        startForegroundNotification()

        if (isOverlayPermissionGranted(this)) {
            showFloatingBubble()
        } else {
            stopSelf()
        }
    }

    private fun initDependencies() {
        val app = applicationContext
        val preferences = EvaPreferences(app)
        val keyStore = SecureKeyStore(app)
        shizukuManager = ShizukuManager(app)
        screenAutomation = DeviceScreenAutomation(app, shizukuManager)
        val adbCap = AdbCapabilityManager(app, shizukuManager)

        val deviceTools = DeviceTools(app)
        val appLauncher = AppLauncherTools(app)
        val networkTools = NetworkTools(app)
        val mediaTools = MediaAudioTools(app)
        val contactTools = ContactTools(app)
        val pdfTools = PdfAssistant(app)
        val contextManager = ContextManager()
        val multiStepEngine = MultiStepEngine()
        val aiRepo = AiRepository(app, preferences, keyStore)

        val registry = ToolRegistry(
            context = app,
            deviceTools = deviceTools,
            appLauncherTools = appLauncher,
            networkTools = networkTools,
            mediaAudioTools = mediaTools,
            contactTools = contactTools,
            pdfAssistant = pdfTools,
            shizukuManager = shizukuManager,
            adbCapabilityManager = adbCap,
            contextManager = contextManager
        )

        commandDispatcher = CommandDispatcher(
            context = app,
            toolRegistry = registry,
            contextManager = contextManager,
            multiStepEngine = multiStepEngine,
            aiRepository = aiRepo,
            preferences = preferences
        )

        speechRecognizer = EvaSpeechRecognizer(app)
        tts = EvaTextToSpeech(app)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "EVA Assistant Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active floating EVA display overlay"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("EVA Display Overlay Active")
            .setContentText("Tap the floating bubble to talk with EVA")
            .setSmallIcon(com.example.R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun showFloatingBubble() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // FLAG_NOT_FOCUSABLE ensures clicks outside the bubble NEVER dismiss it and pass to underlying apps
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 360
        }
        windowLayoutParams = params

        // Build elegant programmatic floating layout
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }

        // Main Bubble Row (Logo + Status Pill)
        val bubbleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6, 6, 12, 6)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 100f
                setColor(Color.parseColor("#E60D1117")) // Deep obsidian with glass translucency
                setStroke(2, Color.parseColor("#44F6D860")) // Subtle gold border
            }
            elevation = 16f
        }

        // Premium White Logo Icon Container
        val logoContainer = FrameLayout(this).apply {
            val size = (52 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#161B22"))
                setStroke(3, Color.WHITE) // Glowing premium white border
            }
            setPadding(6, 6, 6, 6)
        }

        bubbleIcon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setImageResource(com.example.R.drawable.ic_eva_white_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        logoContainer.addView(bubbleIcon)

        // Status Badge & Text
        statusBadge = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 2, 8, 2)
        }

        statusText = TextView(this).apply {
            text = "EVA Ready"
            setTextColor(Color.WHITE)
            textSize = 12f
            setShadowLayer(4f, 0f, 0f, Color.parseColor("#88F6D860"))
        }

        val hintText = TextView(this).apply {
            text = "Tap to speak • Shizuku active"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 9.5f
        }

        statusBadge.addView(statusText)
        statusBadge.addView(hintText)

        bubbleRow.addView(logoContainer)
        bubbleRow.addView(statusBadge)
        rootLayout.addView(bubbleRow)

        // Quick Actions Row (Expandable)
        quickActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(8, 8, 8, 4)

            // Button: Close Ads
            val btnCloseAds = createQuickButton("Close Ads") {
                executeDirectAction("close ads")
            }
            // Button: Protection ON
            val btnProtection = createQuickButton("Protection ON") {
                executeDirectAction("turn the protection on")
            }
            // Button: Close Overlay
            val btnExit = createQuickButton("✕ Close") {
                stopSelf()
            }

            addView(btnCloseAds)
            addView(btnProtection)
            addView(btnExit)
        }
        rootLayout.addView(quickActionsRow)

        // Setup Touch Listener for Dragging & Clicking
        setupTouchListener(rootLayout, bubbleRow)

        overlayView = rootLayout
        windowManager?.addView(overlayView, windowLayoutParams)
    }

    private fun createQuickButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#F6D860"))
            textSize = 10f
            setPadding(16, 8, 16, 8)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 30f
                setColor(Color.parseColor("#CC1E232A"))
                setStroke(1, Color.parseColor("#55F6D860"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setupTouchListener(root: View, bubbleRow: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        bubbleRow.setOnTouchListener { _, event ->
            val lp = windowLayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isClick = false
                    }
                    lp.x = initialX + dx
                    lp.y = initialY + dy
                    windowManager?.updateViewLayout(overlayView, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        onBubbleClicked()
                    }
                    true
                }
                else -> false
            }
        }

        // Long press toggles quick action chips
        bubbleRow.setOnLongClickListener {
            isExpanded = !isExpanded
            quickActionsRow.visibility = if (isExpanded) View.VISIBLE else View.GONE
            true
        }
    }

    private fun onBubbleClicked() {
        if (isProcessing) return

        if (speechRecognizer.voiceState.value == VoiceState.LISTENING) {
            speechRecognizer.stopListening()
            updateStatusText("Ready", "#FFFFFF")
        } else {
            startListeningSession()
        }
    }

    private fun startListeningSession() {
        tts.stop()
        updateStatusText("Listening...", "#00E5FF")

        speechRecognizer.startListening(
            language = "en-US",
            onResult = { spoken ->
                processOverlayCommand(spoken)
            },
            onError = { err ->
                updateStatusText("EVA Ready", "#FFFFFF")
            }
        )
    }

    private fun processOverlayCommand(rawCommand: String) {
        if (rawCommand.isBlank()) return
        isProcessing = true
        updateStatusText("Thinking...", "#F6D860")

        serviceScope.launch {
            val cmd = rawCommand.trim()
            val lower = cmd.lowercase()

            // 1. Dedicated Device Access & Screen Automation via Shizuku
            val responseText: String = when {
                // "open Adguard" or "launch Adguard"
                lower.contains("open adguard") || lower.contains("launch adguard") -> {
                    updateStatusText("Opening AdGuard...", "#00E5FF")
                    val (ok, msg) = screenAutomation.launchApp("AdGuard")
                    if (ok) "Opened AdGuard. I am waiting for your next command." else msg
                }
                // "close ads", "close ad", "skip ad"
                lower.contains("close ad") || lower.contains("close ads") || lower.contains("skip ad") -> {
                    updateStatusText("Closing ads...", "#FF9100")
                    val (ok, msg) = screenAutomation.closeAds()
                    if (ok) "Closed the ad. Waiting for your next command." else msg
                }
                // "turn the protection on", "turn on protection", "enable protection"
                lower.contains("protection on") || lower.contains("turn on protection") || lower.contains("turn the protection on") || lower.contains("enable protection") -> {
                    updateStatusText("Turning on protection...", "#00E676")
                    val (ok, msg) = screenAutomation.turnProtectionOn()
                    if (ok) "Protection has been turned on. Waiting for your next command." else msg
                }
                // Click / Tap element
                lower.startsWith("click ") || lower.startsWith("tap ") -> {
                    val query = cmd.substringAfter(" ").trim()
                    updateStatusText("Tapping '$query'...", "#00E5FF")
                    val (ok, msg) = screenAutomation.clickByText(query)
                    msg
                }
                // Gestures: scroll / slide
                lower.contains("scroll down") -> {
                    screenAutomation.scrollDown()
                    "Scrolled down."
                }
                lower.contains("scroll up") -> {
                    screenAutomation.scrollUp()
                    "Scrolled up."
                }
                lower.contains("slide left") || lower.contains("swipe left") -> {
                    screenAutomation.slideLeft()
                    "Slid left."
                }
                lower.contains("slide right") || lower.contains("swipe right") -> {
                    screenAutomation.slideRight()
                    "Slid right."
                }
                // Close overlay
                lower.contains("close overlay") || lower.contains("exit overlay") -> {
                    stopSelf()
                    return@launch
                }
                // General assistant command
                else -> {
                    val res = commandDispatcher.processCommand(cmd)
                    res.spokenResponse
                }
            }

            // Speak the response and keep the overlay active
            updateStatusText("Speaking...", "#00E676")
            tts.speak(responseText) {
                // Return to Ready state and wait for next commands
                serviceScope.launch(Dispatchers.Main) {
                    updateStatusText("EVA Ready", "#FFFFFF")
                    isProcessing = false
                }
            }

            // Fallback timeout in case TTS callback doesn't fire
            delay(3500)
            if (isProcessing) {
                updateStatusText("EVA Ready", "#FFFFFF")
                isProcessing = false
            }
        }
    }

    private fun executeDirectAction(command: String) {
        processOverlayCommand(command)
    }

    private fun updateStatusText(text: String, colorHex: String) {
        serviceScope.launch(Dispatchers.Main) {
            statusText.text = text
            statusText.setTextColor(Color.parseColor(colorHex))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        speechRecognizer.stopListening()
        tts.shutdown()
        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
        }
    }
}
