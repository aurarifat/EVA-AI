package com.example.eva.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.R
import com.example.eva.ai.AiRepository
import com.example.eva.context.CommandDispatcher
import com.example.eva.context.ContextManager
import com.example.eva.context.MultiStepEngine
import com.example.eva.data.prefs.EvaPreferences
import com.example.eva.data.prefs.SecureKeyStore
import com.example.eva.shizuku.AdbCapabilityManager
import com.example.eva.shizuku.DeviceScreenAutomation
import com.example.eva.shizuku.ShizukuManager
import com.example.eva.tools.AppLauncherTools
import com.example.eva.tools.CalculatorTool
import com.example.eva.tools.ContactTools
import com.example.eva.tools.DeviceTools
import com.example.eva.tools.MediaAudioTools
import com.example.eva.tools.NetworkTools
import com.example.eva.tools.PdfAssistant
import com.example.eva.tools.QrTools
import com.example.eva.tools.ToolRegistry
import com.example.eva.voice.EvaSpeechRecognizer
import com.example.eva.voice.EvaTextToSpeech
import com.example.eva.voice.VoicePersonality
import com.example.eva.voice.VoiceState
import com.example.eva.voice.WakeWordFeedback
import com.example.eva.voice.WakeWordListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Foreground Service hosting the Jetpack Compose Eva Bubble overlay via WindowManager.
 * Employs OverlayLifecycleOwner to eliminate any ViewTree crashes in ComposeView.
 * Provides explicit state management for IDLE, LISTENING, and SPEAKING modes.
 */
class EvaOverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    // State management for Compose overlay
    private val _uiState = MutableStateFlow(BubbleOverlayUiState())
    val uiState = _uiState.asStateFlow()

    // Core capabilities & tools
    private lateinit var orchestrator: com.example.eva.agent.EvaAgentOrchestrator
    private lateinit var broadcastManager: com.example.eva.screen.ScreenBroadcastManager
    private lateinit var shizukuManager: ShizukuManager
    private lateinit var screenAutomation: DeviceScreenAutomation
    private lateinit var speechRecognizer: EvaSpeechRecognizer
    private lateinit var tts: EvaTextToSpeech
    private lateinit var commandDispatcher: CommandDispatcher
    private lateinit var preferences: EvaPreferences

    // Wake Word Listener ("Hi EVA")
    private var wakeWordListener: WakeWordListener? = null
    private var isWakeWordActive = false
    private var followUpTimeoutJob: Job? = null

    companion object {
        private const val TAG = "EvaOverlayService"
        const val ACTION_START_OVERLAY = "com.example.eva.action.START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.example.eva.action.STOP_OVERLAY"
        const val ACTION_STOP_BROADCAST = "com.example.eva.action.STOP_BROADCAST"
        private const val NOTIFICATION_CHANNEL_ID = "eva_overlay_channel"
        private const val NOTIFICATION_ID = 2001

        fun isOverlayPermissionGranted(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true
        }

        fun openOverlaySettings(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(fallback)
                }
            }
        }

        fun startOverlay(context: Context) {
            val intent = Intent(context, EvaOverlayService::class.java).apply {
                action = ACTION_START_OVERLAY
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting overlay service: ${e.message}", e)
            }
        }

        var activeInstance: EvaOverlayService? = null
            private set

        fun showTapCrosshair(x: Int, y: Int) {
            val inst = activeInstance
            if (inst != null) {
                inst.serviceScope.launch(Dispatchers.Main) {
                    inst.displayTapCrosshair(x, y)
                }
            }
        }

        fun stopOverlay(context: Context) {
            val intent = Intent(context, EvaOverlayService::class.java).apply {
                action = ACTION_STOP_OVERLAY
            }
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping overlay service: ${e.message}", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        try {
            createNotificationChannel()
            initDependencies()
            startForegroundNotification(isWakeWordActive)

            if (isOverlayPermissionGranted(this)) {
                setupComposeOverlay()
            } else {
                Log.d(TAG, "Overlay permission not granted; running in background voice/wake-word service mode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during onCreate: ${e.message}", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_OVERLAY -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_BROADCAST -> {
                if (::broadcastManager.isInitialized) {
                    broadcastManager.stopBroadcast()
                }
                startForegroundNotification()
            }
            else -> {
                // Ensure foreground notification stays refreshed
                startForegroundNotification()
            }
        }
        return START_STICKY
    }

    private fun initDependencies() {
        val app = applicationContext
        val preferences = EvaPreferences(app)
        this.preferences = preferences
        val keyStore = SecureKeyStore(app)
        shizukuManager = ShizukuManager(app)
        screenAutomation = DeviceScreenAutomation(app, shizukuManager)
        val adbCap = AdbCapabilityManager(app, shizukuManager)

        orchestrator = com.example.eva.agent.EvaAgentOrchestrator.getInstance(app)
        broadcastManager = com.example.eva.screen.ScreenBroadcastManager.getInstance(app)
        broadcastManager.onBroadcastStateChanged = {
            startForegroundNotification()
        }

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
            contextManager = contextManager,
            preferences = preferences
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

        // Observe Orchestrator shared agent states
        serviceScope.launch {
            orchestrator.activeMode.collect { mode ->
                _uiState.update { it.copy(agentMode = mode) }
            }
        }
        serviceScope.launch {
            orchestrator.conversationHistory.collect { history ->
                _uiState.update { it.copy(conversationHistory = history) }
            }
        }
        serviceScope.launch {
            orchestrator.currentTask.collect { task ->
                _uiState.update { it.copy(currentTask = task) }
            }
        }
        serviceScope.launch {
            orchestrator.visualState.collect { vs ->
                _uiState.update {
                    it.copy(
                        statusText = vs.label,
                        isProcessing = vs == com.example.eva.agent.OrchestratorVisualState.THINKING ||
                                vs == com.example.eva.agent.OrchestratorVisualState.EXECUTING
                    )
                }
            }
        }
        serviceScope.launch {
            orchestrator.pendingConfirmationAction.collect { pending ->
                _uiState.update { it.copy(pendingConfirmation = pending?.first) }
            }
        }
        serviceScope.launch {
            orchestrator.isPaused.collect { paused ->
                _uiState.update { it.copy(isSessionPaused = paused) }
            }
        }
        serviceScope.launch {
            orchestrator.isSpeaking.collect { speaking ->
                _uiState.update {
                    it.copy(
                        mode = if (speaking) BubbleMode.SPEAKING else if (it.mode == BubbleMode.SPEAKING) BubbleMode.IDLE else it.mode
                    )
                }
            }
        }
        serviceScope.launch {
            orchestrator.lastSpokenText.collect { spoken ->
                if (spoken.isNotBlank()) {
                    _uiState.update { it.copy(spokenText = spoken) }
                }
            }
        }

        // Observe Screen Broadcast States
        serviceScope.launch {
            broadcastManager.isBroadcasting.collect { broadcasting ->
                _uiState.update { it.copy(isScreenBroadcasting = broadcasting) }
                startForegroundNotification()
            }
        }
        serviceScope.launch {
            broadcastManager.isPaused.collect { isPaused ->
                _uiState.update { it.copy(isBroadcastPaused = isPaused) }
            }
        }
        serviceScope.launch {
            broadcastManager.latestThumbnail.collect { thumb ->
                _uiState.update { it.copy(latestThumbnail = thumb) }
            }
        }

        // Observe Shizuku connection state
        serviceScope.launch {
            shizukuManager.shizukuState.collect { info ->
                _uiState.update { it.copy(isShizukuActive = info.isAuthorized) }
            }
        }

        // Observe voice recognizer RMS level
        serviceScope.launch {
            speechRecognizer.rmsLevel.collect { rms ->
                _uiState.update { it.copy(rmsLevel = rms) }
            }
        }

        // Observe speech recognition partial text
        serviceScope.launch {
            speechRecognizer.speechText.collect { text ->
                if (_uiState.value.mode == BubbleMode.LISTENING && text.isNotBlank()) {
                    _uiState.update { it.copy(recognizedText = text) }
                }
            }
        }

        // Observe Wake Word configuration ("Hi EVA")
        serviceScope.launch {
            preferences.settingsFlow.collect { s ->
                val wakeEnabled = s.wakeWordEnabled
                if (wakeEnabled != isWakeWordActive) {
                    isWakeWordActive = wakeEnabled
                    if (wakeEnabled) {
                        startWakeWordEngine()
                    } else {
                        stopWakeWordEngine()
                    }
                    startForegroundNotification(isWakeWordActive)
                }
                wakeWordListener?.updateSensitivity(s.wakeWordSensitivity)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "EVA Assistant Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the persistent EVA Display Overlay active across apps"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(wakeWordActive: Boolean = isWakeWordActive) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_MANUAL_OPEN, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isBroadcasting = ::broadcastManager.isInitialized && broadcastManager.isBroadcasting.value
        val title = when {
            isBroadcasting -> "EVA Live Screen Broadcast Active"
            wakeWordActive -> "EVA AI Assistant Active"
            else -> "EVA Display Overlay Active"
        }
        val content = when {
            isBroadcasting -> "Screen broadcasting to EVA Vision Agent • Tap to view"
            wakeWordActive -> "EVA AI is listening for 'Hi EVA'"
            else -> "Persistent assistant bubble is active across apps. Tap to interact."
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isBroadcasting) {
            val stopBroadcastIntent = Intent(this, EvaOverlayService::class.java).apply {
                action = ACTION_STOP_BROADCAST
            }
            val stopBroadcastPending = PendingIntent.getService(
                this,
                101,
                stopBroadcastIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(R.drawable.ic_launcher_foreground, "Stop Screen Share", stopBroadcastPending)
        } else {
            builder.addAction(R.drawable.ic_launcher_foreground, "Open EVA App", pendingIntent)
        }

        val notification: Notification = builder.build()

        try {
            val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (hasMicPermission && wakeWordActive) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (isBroadcasting) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var fgsType = 0
                if (hasMicPermission && wakeWordActive) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (isBroadcasting && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                if (fgsType != 0) {
                    startForeground(NOTIFICATION_ID, notification, fgsType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground with type failed, falling back: ${e.message}")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Fatal startForeground error: ${fallbackEx.message}", fallbackEx)
            }
        }
    }

    private fun setupComposeOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // FLAG_NOT_FOCUSABLE is default: background app touches pass through seamlessly
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 350
        }
        windowLayoutParams = params

        // Initialize custom lifecycle owner for Jetpack Compose hosting
        val owner = OverlayLifecycleOwner()
        owner.onCreate()
        lifecycleOwner = owner

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)

            setContent {
                val state by uiState.collectAsState()

                EvaBubbleOverlayContent(
                    state = state,
                    onDragDelta = { dx, dy -> handleDrag(dx, dy) },
                    onBubbleClick = { handleBubbleClick() },
                    onStartVoice = { startListeningMode() },
                    onStopVoice = { stopListeningMode() },
                    onToggleExpand = {
                        val nextExpanded = !_uiState.value.isExpanded
                        _uiState.update { it.copy(isExpanded = nextExpanded) }
                        if (!nextExpanded) setFocusable(false)
                    },
                    onGoHome = { toggleToHomeScreen() },
                    onQuickAction = { action -> executeAction(action) },
                    onOpenApp = { openFullApp() },
                    onCloseOverlay = { stopSelf() },
                    onRequestInputFocus = { focusable -> setFocusable(focusable) },
                    onSwitchAgentMode = { mode -> orchestrator.switchAgentMode(mode) },
                    onToggleBroadcast = {
                        if (broadcastManager.isBroadcasting.value) {
                            broadcastManager.stopBroadcast()
                        } else {
                            com.example.eva.screen.ScreenCapturePermissionActivity.launch(this@EvaOverlayService)
                        }
                    },
                    onPauseBroadcast = {
                        if (broadcastManager.isPaused.value) {
                            broadcastManager.resumeBroadcast()
                        } else {
                            broadcastManager.pauseBroadcast()
                        }
                    },
                    onPauseSession = {
                        if (_uiState.value.isSessionPaused) {
                            orchestrator.resumeSession()
                        } else {
                            orchestrator.pauseSession()
                        }
                    },
                    onConfirmAction = { orchestrator.confirmPendingAction() },
                    onCancelAction = { orchestrator.cancelPendingAction() },
                    onClearHistory = { orchestrator.clearSessionHistory() }
                )
            }
        }
        composeView = view

        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach ComposeView to WindowManager: ${e.message}", e)
        }
    }

    private fun setFocusable(focusable: Boolean) {
        val params = windowLayoutParams ?: return
        val wm = windowManager ?: return
        val view = composeView ?: return

        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "Error updating focusable layout: ${e.message}")
        }
    }

    private var overlayX = 20f
    private var overlayY = 350f

    private fun handleDrag(dx: Float, dy: Float) {
        val params = windowLayoutParams ?: return
        val wm = windowManager ?: return
        val view = composeView ?: return

        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - 70).coerceAtLeast(100).toFloat()
        val maxY = (metrics.heightPixels - 120).coerceAtLeast(100).toFloat()

        overlayX = (overlayX + dx).coerceIn(0f, maxX)
        overlayY = (overlayY + dy).coerceIn(30f, maxY)
        params.x = overlayX.toInt()
        params.y = overlayY.toInt()

        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "Error updating overlay layout: ${e.message}")
        }
    }

    private fun handleBubbleClick() {
        val currentState = _uiState.value

        if (currentState.isProcessing) return

        when (currentState.mode) {
            BubbleMode.IDLE -> {
                // Tapping the bubble toggles the quick dock right on the current screen
                val nextExpanded = !currentState.isExpanded
                _uiState.update { it.copy(isExpanded = nextExpanded) }
                if (!nextExpanded) setFocusable(false)
            }
            BubbleMode.LISTENING -> {
                stopListeningMode()
            }
            BubbleMode.SPEAKING -> {
                tts.stop()
                _uiState.update {
                    it.copy(
                        mode = BubbleMode.IDLE,
                        statusText = "EVA Ready"
                    )
                }
            }
        }
    }

    private fun startWakeWordEngine() {
        if (wakeWordListener == null) {
            wakeWordListener = WakeWordListener(
                context = applicationContext,
                onWakeWordDetected = {
                    handleWakeWordTriggered()
                }
            )
        }
        wakeWordListener?.startListening()
        _uiState.update {
            it.copy(
                statusText = if (it.mode == BubbleMode.IDLE) "EVA AI is listening for 'Hi EVA'" else it.statusText
            )
        }
    }

    private fun stopWakeWordEngine() {
        wakeWordListener?.stopListening()
        wakeWordListener = null
        _uiState.update {
            it.copy(
                statusText = if (it.mode == BubbleMode.IDLE) "EVA Ready" else it.statusText
            )
        }
    }

    private fun handleWakeWordTriggered() {
        Log.d(TAG, "Wake word detected in EvaOverlayService! Triggering warm response flow.")
        wakeWordListener?.pauseListening()

        // Haptic and chime
        WakeWordFeedback.triggerHaptic(this)
        WakeWordFeedback.playChime(this)

        // Animate / highlight bubble
        _uiState.update {
            it.copy(
                isWakeWordHighlight = true,
                mode = BubbleMode.SPEAKING,
                statusText = "Hi! Ready"
            )
        }

        serviceScope.launch(Dispatchers.Main) {
            delay(2200)
            _uiState.update { it.copy(isWakeWordHighlight = false) }
        }

        // Pick warm rotating ready-phrase
        val readyPhrase = VoicePersonality.getWakeWordReadyPhrase()
        tts.speak(readyPhrase) {
            serviceScope.launch(Dispatchers.Main) {
                startFollowUpCommandListening()
            }
        }
    }

    private fun startFollowUpCommandListening() {
        _uiState.update {
            it.copy(
                mode = BubbleMode.LISTENING,
                statusText = "Listening for command...",
                recognizedText = ""
            )
        }

        // 7.5 seconds quiet timeout: quietly return to wake-word listening if no command
        followUpTimeoutJob?.cancel()
        followUpTimeoutJob = serviceScope.launch {
            delay(7500)
            if (_uiState.value.mode == BubbleMode.LISTENING) {
                Log.d(TAG, "Follow-up command timed out quietly; returning to wake word")
                speechRecognizer.stopListening()
                returnToWakeWordMode()
            }
        }

        speechRecognizer.startListening(
            language = "en-US",
            onResult = { spoken ->
                followUpTimeoutJob?.cancel()
                processCommand(spoken)
            },
            onError = { err ->
                followUpTimeoutJob?.cancel()
                Log.d(TAG, "No follow-up speech detected ($err); quietly returning to wake word")
                returnToWakeWordMode()
            }
        )
    }

    private fun returnToWakeWordMode() {
        followUpTimeoutJob?.cancel()
        _uiState.update {
            it.copy(
                mode = BubbleMode.IDLE,
                statusText = if (isWakeWordActive) "EVA AI is listening for 'Hi EVA'" else "EVA Ready",
                isWakeWordHighlight = false,
                recognizedText = "",
                spokenText = "",
                isProcessing = false
            )
        }
        if (isWakeWordActive) {
            wakeWordListener?.resumeListening()
        }
    }

    private fun stopListeningMode() {
        followUpTimeoutJob?.cancel()
        speechRecognizer.stopListening()
        returnToWakeWordMode()
    }

    private fun startListeningMode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _uiState.update {
                it.copy(
                    mode = BubbleMode.IDLE,
                    statusText = "Mic permission required. Open EVA app to allow.",
                    recognizedText = "",
                    isExpanded = true
                )
            }
            return
        }

        tts.stop()
        _uiState.update {
            it.copy(
                mode = BubbleMode.LISTENING,
                statusText = "Listening... Speak your command",
                recognizedText = "",
                spokenText = ""
            )
        }

        try {
            speechRecognizer.startListening(
                language = "en-US",
                onResult = { spoken ->
                    processCommand(spoken)
                },
                onError = { err ->
                    _uiState.update {
                        it.copy(
                            mode = BubbleMode.IDLE,
                            statusText = err,
                            recognizedText = ""
                        )
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognizer: ${e.message}", e)
            _uiState.update {
                it.copy(
                    mode = BubbleMode.IDLE,
                    statusText = "Voice error: ${e.localizedMessage}"
                )
            }
        }
    }

    private fun processCommand(rawCommand: String) {
        if (rawCommand.isBlank()) {
            _uiState.update { it.copy(mode = BubbleMode.IDLE, statusText = "EVA Ready") }
            return
        }

        val cmd = rawCommand.trim()
        val lower = cmd.lowercase()

        _uiState.update {
            it.copy(
                mode = BubbleMode.IDLE,
                isProcessing = true,
                statusText = "Thinking...",
                recognizedText = cmd
            )
        }

        serviceScope.launch {
            when {
                // Toggle Home Screen
                lower == "home" || lower == "go home" || lower.contains("toggle home") || lower.contains("home screen") || lower == "toggle to home" -> {
                    toggleToHomeScreen()
                }
                // Open full EVA application
                lower.contains("open app") || lower.contains("open eva") || lower.contains("open settings") -> {
                    openFullApp()
                }
                // Close overlay
                lower.contains("close overlay") || lower.contains("exit overlay") || lower.contains("hide overlay") -> {
                    stopSelf()
                }
                // All other commands, queries, tasks routed to shared persistent orchestrator
                else -> {
                    orchestrator.enqueueCommand(cmd)
                }
            }
        }
    }

    private fun toggleToHomeScreen() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
            _uiState.update { it.copy(statusText = "Home Screen", mode = BubbleMode.IDLE) }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling to home screen: ${e.message}", e)
        }
    }

    private fun openFullApp() {
        try {
            val appIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_MANUAL_OPEN, true)
            }
            startActivity(appIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening full EVA app: ${e.message}", e)
        }
    }

    private fun executeAction(command: String) {
        setFocusable(false)
        processCommand(command)
    }

    fun displayTapCrosshair(targetX: Int, targetY: Int) {
        val wm = windowManager ?: return
        try {
            val density = resources.displayMetrics.density
            val size = (36 * density).toInt()
            val crosshairView = android.widget.ImageView(this).apply {
                val circle = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x55FFD54F.toInt())
                    setStroke((2.5f * density).toInt(), 0xFFFFD54F.toInt())
                }
                background = circle
            }

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                size,
                size,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = targetX - (size / 2)
                y = targetY - (size / 2)
            }

            wm.addView(crosshairView, params)

            serviceScope.launch(Dispatchers.Main) {
                delay(650)
                try {
                    wm.removeView(crosshairView)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error displaying tap crosshair: ${e.message}")
        }
    }

    override fun onDestroy() {
        if (activeInstance == this) {
            activeInstance = null
        }
        super.onDestroy()
        try {
            serviceScope.cancel()
            followUpTimeoutJob?.cancel()
            wakeWordListener?.destroy()
            wakeWordListener = null
            speechRecognizer.stopListening()
            tts.shutdown()

            lifecycleOwner?.onDestroy()
            lifecycleOwner = null

            composeView?.let { view ->
                windowManager?.removeView(view)
                composeView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during onDestroy: ${e.message}", e)
        }
    }
}
