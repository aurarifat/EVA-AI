package com.example.eva.agent

import android.content.Context
import android.util.Log
import com.example.eva.ai.AiRepository
import com.example.eva.ai.ChatMessage
import com.example.eva.context.CommandDispatcher
import com.example.eva.context.CommandResult
import com.example.eva.context.ContextManager
import com.example.eva.context.MultiStepEngine
import com.example.eva.data.prefs.AiProviderType
import com.example.eva.data.prefs.EvaPreferences
import com.example.eva.data.prefs.SecureKeyStore
import com.example.eva.screen.ScreenBroadcastManager
import com.example.eva.shizuku.DeviceScreenAutomation
import com.example.eva.shizuku.AdbCapabilityManager
import com.example.eva.shizuku.ShizukuManager
import com.example.eva.tools.AppLauncherTools
import com.example.eva.tools.ContactTools
import com.example.eva.tools.DeviceTools
import com.example.eva.tools.MediaAudioTools
import com.example.eva.tools.NetworkTools
import com.example.eva.tools.PdfAssistant
import com.example.eva.tools.ToolRegistry
import com.example.eva.voice.EvaTextToSpeech
import com.example.eva.voice.VoicePersonality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class OrchestratorMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user", "eva", "system"
    val content: String,
    val toolName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

enum class OrchestratorVisualState(val label: String) {
    IDLE("Ready"),
    LISTENING("Listening..."),
    THINKING("Thinking..."),
    EXECUTING("Executing..."),
    WAITING("Waiting for command"),
    PAUSED("Paused")
}

/**
 * Shared Persistent Agent Orchestration Layer.
 * Guarantees persistent session memory, conversation history, task tracking,
 * and sequential multi-step command execution across all agent modes and AI providers.
 */
class EvaAgentOrchestrator private constructor(private val context: Context) {

    companion object {
        private const val TAG = "EvaAgentOrchestrator"

        @Volatile
        private var instance: EvaAgentOrchestrator? = null

        fun getInstance(context: Context): EvaAgentOrchestrator {
            return instance ?: synchronized(this) {
                instance ?: EvaAgentOrchestrator(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Dependencies
    val preferences = EvaPreferences(context)
    val keyStore = SecureKeyStore(context)
    val shizukuManager = ShizukuManager(context)
    val adbCapabilityManager = AdbCapabilityManager(context, shizukuManager)
    val contextManager = ContextManager()
    val multiStepEngine = MultiStepEngine()
    val aiRepository = AiRepository(context, preferences, keyStore)
    val broadcastManager = ScreenBroadcastManager.getInstance(context)
    private val tts = EvaTextToSpeech(context)

    val toolRegistry = ToolRegistry(
        context = context,
        deviceTools = DeviceTools(context),
        appLauncherTools = AppLauncherTools(context),
        networkTools = NetworkTools(context),
        mediaAudioTools = MediaAudioTools(context),
        contactTools = ContactTools(context),
        pdfAssistant = PdfAssistant(context),
        shizukuManager = shizukuManager,
        adbCapabilityManager = adbCapabilityManager,
        contextManager = contextManager,
        preferences = preferences
    )

    // State flows
    private val _activeMode = MutableStateFlow(EvaAgentMode.AUTONOMOUS_AGENT)
    val activeMode: StateFlow<EvaAgentMode> = _activeMode.asStateFlow()

    private val _visualState = MutableStateFlow(OrchestratorVisualState.IDLE)
    val visualState: StateFlow<OrchestratorVisualState> = _visualState.asStateFlow()

    private val _conversationHistory = MutableStateFlow<List<OrchestratorMessage>>(emptyList())
    val conversationHistory: StateFlow<List<OrchestratorMessage>> = _conversationHistory.asStateFlow()

    private val _currentTask = MutableStateFlow<String?>("Ready for your command")
    val currentTask: StateFlow<String?> = _currentTask.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _pendingConfirmationAction = MutableStateFlow<Pair<String, () -> Unit>?>(null)
    val pendingConfirmationAction: StateFlow<Pair<String, () -> Unit>?> = _pendingConfirmationAction.asStateFlow()

    val isSpeaking: StateFlow<Boolean> = tts.isSpeaking

    private val _lastSpokenText = MutableStateFlow("")
    val lastSpokenText: StateFlow<String> = _lastSpokenText.asStateFlow()

    fun speakReply(text: String, onDone: (() -> Unit)? = null) {
        _lastSpokenText.value = text
        tts.speak(text, onDone)
    }

    // Sequential Queue for multi-step task execution
    private val commandChannel = Channel<String>(capacity = 10)
    private var isQueueProcessing = false

    private val commandDispatcher = CommandDispatcher(
        context = context,
        toolRegistry = toolRegistry,
        contextManager = contextManager,
        multiStepEngine = multiStepEngine,
        aiRepository = aiRepository,
        preferences = preferences
    )

    init {
        // Load initial agent mode from preferences
        scope.launch(Dispatchers.IO) {
            val settings = preferences.settingsFlow.first()
            _activeMode.value = if (settings.isAgentMode) EvaAgentMode.AUTONOMOUS_AGENT else EvaAgentMode.CHAT
        }

        // Start sequential command worker loop
        startSequentialCommandWorker()
    }

    fun switchAgentMode(newMode: EvaAgentMode) {
        _activeMode.value = newMode
        scope.launch(Dispatchers.IO) {
            preferences.setIsAgentMode(newMode == EvaAgentMode.AUTONOMOUS_AGENT || newMode == EvaAgentMode.SCREEN_VISION)
        }
        val notice = OrchestratorMessage(
            role = "system",
            content = "Switched to ${newMode.displayName}. Session and conversation history preserved."
        )
        _conversationHistory.value = _conversationHistory.value + notice
        _currentTask.value = "Mode: ${newMode.shortName}"
        Log.i(TAG, "Switched agent mode to ${newMode.id}, preserving session.")
    }

    fun pauseSession() {
        _isPaused.value = true
        _visualState.value = OrchestratorVisualState.PAUSED
        _currentTask.value = "Session Paused. Tap Resume to continue."
        tts.stop()
    }

    fun resumeSession() {
        _isPaused.value = false
        _visualState.value = OrchestratorVisualState.WAITING
        _currentTask.value = "Waiting for your command"
    }

    fun clearSessionHistory() {
        _conversationHistory.value = emptyList()
        _currentTask.value = "Conversation history cleared."
        _visualState.value = OrchestratorVisualState.IDLE
    }

    /**
     * Enqueues a command for sequential processing.
     */
    fun enqueueCommand(command: String) {
        if (command.isBlank()) return
        scope.launch {
            commandChannel.send(command.trim())
        }
    }

    private fun startSequentialCommandWorker() {
        scope.launch(Dispatchers.IO) {
            for (rawCommand in commandChannel) {
                processSingleCommandSequentially(rawCommand)
            }
        }
    }

    /**
     * Executes commands one by one while keeping the persistent session active.
     */
    private suspend fun processSingleCommandSequentially(command: String) = withContext(Dispatchers.Main) {
        if (_isPaused.value && !command.equals("resume", ignoreCase = true) && !command.equals("continue", ignoreCase = true)) {
            val pauseMsg = OrchestratorMessage(
                role = "eva",
                content = "Session is paused. Say 'resume' to continue."
            )
            _conversationHistory.value = _conversationHistory.value + pauseMsg
            speakReply("Session is paused. Say resume to continue.")
            return@withContext
        }

        // Add user message to conversation history
        val userMsg = OrchestratorMessage(role = "user", content = command)
        _conversationHistory.value = _conversationHistory.value + userMsg

        _visualState.value = OrchestratorVisualState.THINKING
        _currentTask.value = "Processing: \"$command\""

        val lower = command.lowercase().trim()

        // 1. Session control keywords
        if (lower == "wait" || lower == "hold on" || lower == "pause") {
            _visualState.value = OrchestratorVisualState.WAITING
            _currentTask.value = "Waiting for your next command"
            val reply = "Waiting. Tell me what you would like to do next."
            addEvaReply(reply)
            speakReply(reply)
            return@withContext
        }

        if (lower == "resume" || lower == "continue") {
            resumeSession()
            val reply = "Resumed. I'm ready for your next command."
            addEvaReply(reply)
            speakReply(reply)
            return@withContext
        }

        if (lower == "now go back" || lower == "go back" || lower == "back") {
            _visualState.value = OrchestratorVisualState.EXECUTING
            toolRegistry.backendSelector.executeSafeAction("press_back") { it.pressBack() }
            val reply = "Went back."
            addEvaReply(reply)
            speakReply(reply)
            _visualState.value = OrchestratorVisualState.WAITING
            return@withContext
        }

        // 2. Sensitive Action Confirmation Check
        if (isSensitiveCommand(lower)) {
            _visualState.value = OrchestratorVisualState.WAITING
            _pendingConfirmationAction.value = Pair(command) {
                scope.launch(Dispatchers.IO) {
                    executeResolvedCommand(command)
                }
            }
            val confirmPrompt = "Please confirm: do you want me to proceed with '$command'?"
            addEvaReply(confirmPrompt)
            speakReply(confirmPrompt)
            return@withContext
        }

        // 3. Vision Mode / Screen Broadcast Frame Evaluation
        val mode = _activeMode.value
        val isVisionQuery = mode == EvaAgentMode.SCREEN_VISION ||
                lower.contains("what is on screen") ||
                lower.contains("look at my screen") ||
                lower.contains("what do you see") ||
                lower.contains("read this screen")

        if (isVisionQuery && broadcastManager.isBroadcasting.value) {
            val base64Frame = broadcastManager.captureCurrentFrameBase64()
            if (base64Frame != null) {
                _visualState.value = OrchestratorVisualState.THINKING
                _currentTask.value = "Analyzing screen frame with Vision AI..."
                val visionPrompt = "User command: $command\nInspect the captured screen frame and answer accurately and concisely in 1-2 sentences."
                val visionResponse = aiRepository.executeAiRequest(
                    messages = listOf(ChatMessage("user", visionPrompt, base64Frame)),
                    toolsPrompt = VoicePersonality.getSystemPrompt()
                )
                val reply = visionResponse.content.replace("💛", "").trim()
                addEvaReply(reply, toolName = "screen_vision")
                speakReply(reply)
                _visualState.value = OrchestratorVisualState.WAITING
                _currentTask.value = "Waiting for next command"
                return@withContext
            }
        }

        // 4. Default dispatch via CommandDispatcher
        _visualState.value = OrchestratorVisualState.EXECUTING
        executeResolvedCommand(command)
    }

    private suspend fun executeResolvedCommand(command: String) {
        val result: CommandResult = withContext(Dispatchers.IO) {
            commandDispatcher.processCommand(command) { step, status ->
                scope.launch(Dispatchers.Main) {
                    _currentTask.value = step
                }
            }
        }

        withContext(Dispatchers.Main) {
            val cleanReply = result.spokenResponse.replace("💛", "").trim()
            addEvaReply(cleanReply, toolName = result.toolName)
            speakReply(cleanReply)

            _visualState.value = OrchestratorVisualState.WAITING
            _currentTask.value = if (result.toolName != null) {
                "Completed: ${result.toolName} • Waiting for next command"
            } else {
                "Waiting for next command"
            }
        }
    }

    fun confirmPendingAction() {
        val pending = _pendingConfirmationAction.value
        _pendingConfirmationAction.value = null
        pending?.second?.invoke()
    }

    fun cancelPendingAction() {
        _pendingConfirmationAction.value = null
        addEvaReply("Action cancelled.")
        speakReply("Action cancelled.")
        _visualState.value = OrchestratorVisualState.WAITING
    }

    private fun addEvaReply(content: String, toolName: String? = null) {
        val replyMsg = OrchestratorMessage(
            role = "eva",
            content = content,
            toolName = toolName
        )
        _conversationHistory.value = _conversationHistory.value + replyMsg
    }

    private fun isSensitiveCommand(lower: String): Boolean {
        return lower.startsWith("delete ") ||
                lower.startsWith("clear all") ||
                lower.contains("format") ||
                lower.contains("factory reset") ||
                lower.startsWith("uninstall ")
    }

    fun release() {
        tts.shutdown()
        broadcastManager.stopBroadcast()
    }
}
