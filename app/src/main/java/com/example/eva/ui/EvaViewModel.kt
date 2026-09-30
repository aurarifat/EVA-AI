package com.example.eva.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.eva.ai.AiRepository
import com.example.eva.ai.ProviderTestResult
import com.example.eva.context.CommandDispatcher
import com.example.eva.context.ContextManager
import com.example.eva.context.MultiStepEngine
import com.example.eva.context.SessionState
import com.example.eva.data.database.ConversationEntity
import com.example.eva.data.database.CommandHistoryEntity
import com.example.eva.data.database.EvaDatabase
import com.example.eva.data.database.ScheduledTaskEntity
import com.example.eva.data.database.TaskExecutionTraceEntity
import com.example.eva.data.prefs.AiProviderType
import com.example.eva.data.prefs.EvaPreferences
import com.example.eva.data.prefs.EvaSettings
import com.example.eva.data.prefs.SecureKeyStore
import com.example.eva.shizuku.*
import com.example.eva.tools.*
import com.example.eva.voice.EvaSpeechRecognizer
import com.example.eva.voice.EvaTextToSpeech
import com.example.eva.automation.ActiveAutomationBackendType
import com.example.eva.overlay.EvaOverlayService
import com.example.eva.voice.VoicePersonality
import com.example.eva.voice.VoiceState
import com.example.eva.voice.WakeWordFeedback
import com.example.eva.voice.WakeWordListener
import com.example.eva.voice.WakeWordDetectionService
import com.example.eva.telegram.TelegramBotInfo
import com.example.eva.telegram.TelegramBotManager
import com.example.eva.telegram.TelegramBotService
import com.example.eva.telegram.TelegramBotStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

class EvaViewModel(application: Application) : AndroidViewModel(application) {

    private val db = EvaDatabase.getInstance(application)
    val preferences = EvaPreferences(application)
    val keyStore = SecureKeyStore(application)

    val deviceTools = DeviceTools(application)
    val appLauncherTools = AppLauncherTools(application)
    val networkTools = NetworkTools(application)
    val mediaAudioTools = MediaAudioTools(application)
    val contactTools = ContactTools(application)
    val pdfAssistant = PdfAssistant(application)
    val shizukuManager = ShizukuManager(application)
    val adbCapabilityManager = AdbCapabilityManager(application, shizukuManager)

    val contextManager = ContextManager()
    val multiStepEngine = MultiStepEngine()
    val aiRepository = AiRepository(application, preferences, keyStore)

    val toolRegistry = ToolRegistry(
        context = application,
        deviceTools = deviceTools,
        appLauncherTools = appLauncherTools,
        networkTools = networkTools,
        mediaAudioTools = mediaAudioTools,
        contactTools = contactTools,
        pdfAssistant = pdfAssistant,
        shizukuManager = shizukuManager,
        adbCapabilityManager = adbCapabilityManager,
        contextManager = contextManager,
        preferences = preferences
    )

    val commandDispatcher = CommandDispatcher(
        context = application,
        toolRegistry = toolRegistry,
        contextManager = contextManager,
        multiStepEngine = multiStepEngine,
        aiRepository = aiRepository,
        preferences = preferences
    )

    val speechRecognizer = EvaSpeechRecognizer(application)
    val tts = EvaTextToSpeech(application)

    // UI States
    val voiceState = speechRecognizer.voiceState
    val rmsLevel = speechRecognizer.rmsLevel
    val speechText = speechRecognizer.speechText
    val isSpeaking = tts.isSpeaking

    val sessionState: StateFlow<SessionState> = contextManager.sessionState
    val currentMultiStepPlan = multiStepEngine.currentPlan
    val shizukuState: StateFlow<ShizukuInfo> = shizukuManager.shizukuState
    val wirelessSession: StateFlow<WirelessDebuggingSessionState> = shizukuManager.wirelessSession
    val shizukuLogs: StateFlow<List<ShizukuLogEntry>> = shizukuManager.logs
    val settingsState: StateFlow<EvaSettings> = preferences.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        EvaSettings()
    )

    val conversations: StateFlow<List<ConversationEntity>> = db.conversationDao().getAllMessages().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val commandHistory: StateFlow<List<CommandHistoryEntity>> = db.commandHistoryDao().getAllHistory().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val scheduledTasks: StateFlow<List<ScheduledTaskEntity>> = db.scheduledTaskDao().getTasks().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val taskTraces: StateFlow<List<TaskExecutionTraceEntity>> = db.taskTraceDao().getAllTraces().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    private val _providerTestResult = MutableStateFlow<ProviderTestResult?>(null)
    val providerTestResult: StateFlow<ProviderTestResult?> = _providerTestResult.asStateFlow()

    val telegramBotManager: TelegramBotManager = TelegramBotManager.getInstance(
        context = application,
        preferences = preferences,
        keyStore = keyStore,
        commandDispatcher = commandDispatcher
    )
    val telegramBotStatus: StateFlow<TelegramBotStatus> = telegramBotManager.botStatus
    val telegramBotInfo: StateFlow<TelegramBotInfo?> = telegramBotManager.botInfo

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private var inAppWakeWordListener: WakeWordListener? = null

    init {
        // Observe settings changes to reconfigure TTS and manage in-app wake word & Telegram service
        viewModelScope.launch {
            settingsState.collect { s ->
                tts.configure(s.voiceSpeed, s.voicePitch, s.voiceLanguage)
                if (s.wakeWordEnabled && EvaOverlayService.activeInstance == null) {
                    startInAppWakeWordListener()
                } else if (!s.wakeWordEnabled || EvaOverlayService.activeInstance != null) {
                    stopInAppWakeWordListener()
                }

                if (s.telegramEnabled && keyStore.hasTelegramToken()) {
                    TelegramBotService.startService(application)
                } else if (!s.telegramEnabled) {
                    TelegramBotService.stopService(application)
                }
            }
        }
        shizukuManager.checkStatus()
    }

    private fun startInAppWakeWordListener() {
        if (inAppWakeWordListener == null) {
            inAppWakeWordListener = WakeWordListener(
                context = getApplication(),
                onWakeWordDetected = {
                    handleInAppWakeWordTriggered()
                }
            )
        }
        inAppWakeWordListener?.startListening()
    }

    private fun stopInAppWakeWordListener() {
        inAppWakeWordListener?.stopListening()
        inAppWakeWordListener = null
    }

    private fun handleInAppWakeWordTriggered() {
        inAppWakeWordListener?.pauseListening()
        val s = settingsState.value
        WakeWordFeedback.triggerHaptic(getApplication())
        if (s.wakeWordChimeEnabled) {
            WakeWordFeedback.playChime(getApplication())
        }
        _statusBanner.value = "Hi! 💛 Listening for command..."

        if (s.wakeWordHandsFreeSpeech) {
            val readyPhrase = VoicePersonality.getWakeWordReadyPhrase()
            tts.speak(readyPhrase) {
                startVoiceListeningForCommand()
            }
        } else {
            startVoiceListeningForCommand()
        }
    }

    private fun startVoiceListeningForCommand() {
        viewModelScope.launch {
            val s = settingsState.value
            speechRecognizer.startListening(
                language = s.voiceLanguage,
                onResult = { spoken ->
                    processCommand(spoken)
                    if (s.wakeWordEnabled && EvaOverlayService.activeInstance == null) {
                        inAppWakeWordListener?.resumeListening()
                    }
                },
                onError = {
                    _statusBanner.value = null
                    if (s.wakeWordEnabled && EvaOverlayService.activeInstance == null) {
                        inAppWakeWordListener?.resumeListening()
                    }
                }
            )
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setWakeWordEnabled(enabled)
            val app = getApplication<Application>()
            if (enabled) {
                if (EvaOverlayService.activeInstance == null) {
                    WakeWordDetectionService.startService(app)
                }
            } else {
                WakeWordDetectionService.stopService(app)
            }
        }
    }

    fun setWakeWordSensitivity(sensitivity: Float) {
        viewModelScope.launch {
            preferences.setWakeWordSensitivity(sensitivity)
            inAppWakeWordListener?.updateSensitivity(sensitivity)
        }
    }

    fun setWakeWordChimeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setWakeWordChimeEnabled(enabled)
        }
    }

    fun setWakeWordHandsFreeSpeech(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setWakeWordHandsFreeSpeech(enabled)
        }
    }

    // Telegram Bot Remote Integration
    fun setTelegramEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setTelegramEnabled(enabled)
            val app = getApplication<Application>()
            if (enabled) {
                TelegramBotService.startService(app)
            } else {
                TelegramBotService.stopService(app)
            }
        }
    }

    fun saveTelegramToken(token: String) {
        keyStore.setTelegramToken(token.trim())
        viewModelScope.launch {
            val settings = settingsState.value
            if (settings.telegramEnabled) {
                TelegramBotService.startService(getApplication())
            }
        }
    }

    fun clearTelegramToken() {
        keyStore.clearTelegramToken()
        telegramBotManager.stopBot()
        TelegramBotService.stopService(getApplication())
    }

    fun unpairTelegramOwner() {
        viewModelScope.launch {
            telegramBotManager.unpairOwner()
        }
    }

    fun testTelegramToken(token: String, onResult: (Result<TelegramBotInfo>) -> Unit) {
        viewModelScope.launch {
            val result = telegramBotManager.testToken(token)
            onResult(result)
        }
    }

    fun startListening() {
        val s = settingsState.value
        if (s.isSleepMode) {
            _statusBanner.value = "EVA is asleep. Say 'wake up' to resume."
            return
        }
        tts.stop()
        speechRecognizer.startListening(
            language = s.voiceLanguage,
            onResult = { spoken ->
                processCommand(spoken)
            },
            onError = { err ->
                _statusBanner.value = err
            }
        )
    }

    fun stopListening() {
        speechRecognizer.stopListening()
    }

    fun processCommand(command: String) {
        if (command.isBlank()) return
        stopListening()
        speechRecognizer.setState(VoiceState.EXECUTING)

        viewModelScope.launch {
            // Save user message in conversation
            db.conversationDao().insertMessage(
                ConversationEntity(role = "user", content = command)
            )

            val result = commandDispatcher.processCommand(command) { stepDesc, status ->
                db.conversationDao().insertMessage(
                    ConversationEntity(
                        role = "tool",
                        content = stepDesc,
                        toolName = "agent_step",
                        toolStatus = status
                    )
                )
            }

            // Save EVA response
            db.conversationDao().insertMessage(
                ConversationEntity(
                    role = "eva",
                    content = result.spokenResponse,
                    toolName = result.toolName,
                    toolStatus = if (result.isSuccess) "success" else "error"
                )
            )

            // Save in command history
            db.commandHistoryDao().insertHistory(
                CommandHistoryEntity(
                    command = command,
                    response = result.spokenResponse,
                    toolName = result.toolName,
                    isSuccess = result.isSuccess
                )
            )

            val s = settingsState.value
            val isSilent = s.isSilentMode && (s.silentUntilMillis == 0L || System.currentTimeMillis() < s.silentUntilMillis)

            if (s.autoSpeak && !isSilent && !s.isSleepMode) {
                speechRecognizer.setState(VoiceState.SPEAKING)
                tts.speak(result.spokenResponse) {
                    speechRecognizer.setState(VoiceState.IDLE)
                }
            } else {
                speechRecognizer.setState(VoiceState.IDLE)
            }
        }
    }

    fun cancelCurrentTask() {
        commandDispatcher.cancelActiveTask()
        _statusBanner.value = "Active agent task cancelled."
    }

    fun clearTaskTraces() {
        viewModelScope.launch {
            db.taskTraceDao().clearAllTraces()
        }
    }

    fun stopSpeaking() {
        tts.stop()
        speechRecognizer.setState(VoiceState.IDLE)
    }

    fun repeatLastResponse() {
        val lastMsg = conversations.value.lastOrNull { it.role == "eva" }?.content
        if (lastMsg != null) {
            tts.speak(lastMsg)
        }
    }

    fun testProvider(type: AiProviderType) {
        viewModelScope.launch {
            _statusBanner.value = "Testing connection to ${type.displayName}..."
            val res = aiRepository.testProvider(type)
            _providerTestResult.value = res
            _statusBanner.value = res.message
        }
    }

    fun switchProvider(type: AiProviderType) {
        viewModelScope.launch {
            aiRepository.switchProvider(type)
            _statusBanner.value = "Active provider: ${type.displayName}"
        }
    }

    fun saveOmniRouteConfig(url: String, model: String, key: String) {
        viewModelScope.launch {
            preferences.updateOmniRoute(url, model)
            if (key.isNotBlank()) keyStore.setKey(AiProviderType.OMNI_ROUTE, key)
            _statusBanner.value = "OmniRoute configuration saved."
        }
    }

    fun saveOpenRouterConfig(url: String, model: String, key: String) {
        viewModelScope.launch {
            preferences.updateOpenRouter(url, model)
            if (key.isNotBlank()) keyStore.setKey(AiProviderType.OPEN_ROUTER, key)
            _statusBanner.value = "OpenRouter configuration saved."
        }
    }

    fun saveGeminiConfig(model: String, key: String) {
        viewModelScope.launch {
            preferences.updateGemini(model)
            if (key.isNotBlank()) keyStore.setKey(AiProviderType.GEMINI, key)
            _statusBanner.value = "Google Gemini configuration saved."
        }
    }

    fun updateVoiceConfig(speed: Float, pitch: Float, language: String, autoSpeak: Boolean) {
        viewModelScope.launch {
            preferences.updateVoiceSettings(speed, pitch, language, autoSpeak)
            _statusBanner.value = "Voice settings updated."
        }
    }

    fun getActiveBackendType(): ActiveAutomationBackendType {
        return toolRegistry.backendSelector.getActiveBackendType()
    }

    fun isAccessibilityActive(): Boolean {
        return toolRegistry.backendSelector.accessibilityBackend.isAvailable()
    }

    fun isShizukuActive(): Boolean {
        return toolRegistry.backendSelector.shizukuBackend.isAvailable()
    }

    fun setDebugTapCrosshair(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setDebugTapCrosshair(enabled)
            _statusBanner.value = if (enabled) "Debug tap crosshair enabled" else "Debug tap crosshair disabled"
        }
    }

    fun updateAgentMode(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setIsAgentMode(enabled)
            _statusBanner.value = if (enabled) "Agent Mode enabled (autonomous execution)" else "Chat Mode enabled"
        }
    }

    fun updateExecutionParams(
        temperature: Float,
        maxTokens: Int,
        maxSteps: Int,
        disableMaxSteps: Boolean,
        screenCompression: Boolean,
        sendSystemPrompt: Boolean
    ) {
        viewModelScope.launch {
            preferences.updateModelParams(temperature, maxTokens, maxSteps, disableMaxSteps)
            preferences.setUseScreenCompression(screenCompression)
            preferences.setSendSystemPrompt(sendSystemPrompt)
            _statusBanner.value = "Execution parameters saved."
        }
    }

    fun addTask(title: String, details: String, timeMillis: Long) {
        viewModelScope.launch {
            db.scheduledTaskDao().insertTask(
                ScheduledTaskEntity(title = title, details = details, timeMillis = timeMillis)
            )
            _statusBanner.value = "Task scheduled: $title"
        }
    }

    fun deleteTask(task: ScheduledTaskEntity) {
        viewModelScope.launch {
            db.scheduledTaskDao().deleteTask(task)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            db.commandHistoryDao().clearHistory()
            db.conversationDao().clearConversations()
            _statusBanner.value = "History cleared."
        }
    }

    fun refreshShizukuStatus() {
        shizukuManager.checkStatus()
    }

    fun requestShizukuAuthorization(): Boolean {
        return shizukuManager.requestAuthorization()
    }

    fun establishWirelessSession(customPort: Int? = null, onComplete: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch {
            _statusBanner.value = "Connecting to Wireless Debugging via Shizuku bridge..."
            val (success, msg) = shizukuManager.establishWirelessDebuggingSession(customPort)
            _statusBanner.value = msg
            onComplete?.invoke(success, msg)
        }
    }

    fun disconnectWirelessSession() {
        shizukuManager.disconnectWirelessDebuggingSession()
        _statusBanner.value = "Wireless Debugging session disconnected."
    }

    fun toggleNativeWirelessDebugging(enable: Boolean, onComplete: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch {
            val (success, msg) = shizukuManager.toggleNativeWirelessDebugging(enable)
            _statusBanner.value = msg
            onComplete?.invoke(success, msg)
        }
    }

    fun pingWirelessSession(onResult: (Boolean, Long) -> Unit) {
        viewModelScope.launch {
            val res = shizukuManager.pingActiveSession()
            onResult(res.first, res.second)
        }
    }

    fun autoDetectPort(onResult: (Int?) -> Unit) {
        viewModelScope.launch {
            val port = shizukuManager.autoDetectAdbPort()
            onResult(port)
        }
    }

    fun clearShizukuLogs() {
        shizukuManager.clearLogs()
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopInAppWakeWordListener()
        speechRecognizer.stopListening()
        tts.shutdown()
        shizukuManager.cleanup()
        mediaAudioTools.cleanup()
        pdfAssistant.closeCurrentPdf()
    }
}
