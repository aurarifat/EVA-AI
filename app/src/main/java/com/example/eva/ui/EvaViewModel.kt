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
import com.example.eva.data.prefs.AiProviderType
import com.example.eva.data.prefs.EvaPreferences
import com.example.eva.data.prefs.EvaSettings
import com.example.eva.data.prefs.SecureKeyStore
import com.example.eva.shizuku.*
import com.example.eva.tools.*
import com.example.eva.voice.EvaSpeechRecognizer
import com.example.eva.voice.EvaTextToSpeech
import com.example.eva.voice.VoicePersonality
import com.example.eva.voice.VoiceState
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
        contextManager = contextManager
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

    private val _providerTestResult = MutableStateFlow<ProviderTestResult?>(null)
    val providerTestResult: StateFlow<ProviderTestResult?> = _providerTestResult.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    init {
        // Observe settings changes to reconfigure TTS
        viewModelScope.launch {
            settingsState.collect { s ->
                tts.configure(s.voiceSpeed, s.voicePitch, s.voiceLanguage)
            }
        }
        shizukuManager.checkStatus()
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

            val result = commandDispatcher.processCommand(command)

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
        speechRecognizer.stopListening()
        tts.shutdown()
        shizukuManager.cleanup()
        mediaAudioTools.cleanup()
        pdfAssistant.closeCurrentPdf()
    }
}
