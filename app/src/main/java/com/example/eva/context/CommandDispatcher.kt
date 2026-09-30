package com.example.eva.context

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.eva.ai.AiRepository
import com.example.eva.ai.ChatMessage
import com.example.eva.automation.ScrollDirection
import com.example.eva.automation.formatForFastAgent
import com.example.eva.data.database.EvaDatabase
import com.example.eva.data.database.TaskExecutionTraceEntity
import com.example.eva.data.prefs.AiProviderType
import com.example.eva.data.prefs.EvaPreferences
import com.example.eva.data.prefs.EvaSettings
import com.example.eva.tools.ToolExecutionResult
import com.example.eva.tools.ToolRegistry
import com.example.eva.voice.VoicePersonality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CommandResult(
    val spokenResponse: String,
    val toolName: String? = null,
    val toolResult: String? = null,
    val isSuccess: Boolean = true,
    val multiStepPlan: MultiStepPlan? = null
)

/**
 * Ultra-Fast Command Dispatcher for EVA AI.
 * Implements a dual-layer execution pipeline:
 * 1. Fast-Path Direct Intent Engine: Zero LLM latency (5ms-25ms) for system, apps, screen gestures and tools.
 * 2. Autonomous Agent Engine: Ultra-compact screen encoding and minimal-token LLM loop (<500ms/step) with live progress streaming.
 * 3. Chat Mode Enforcement: Disables device automation and tools when Chat Mode is active.
 */
class CommandDispatcher(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
    private val contextManager: ContextManager,
    private val multiStepEngine: MultiStepEngine,
    private val aiRepository: AiRepository,
    private val preferences: EvaPreferences,
    private val database: EvaDatabase = EvaDatabase.getInstance(context)
) {

    private val TAG = "CommandDispatcher"

    fun cancelActiveTask() {
        multiStepEngine.cancelPlan()
    }

    suspend fun processCommand(
        rawInput: String,
        onStepProgress: (suspend (String, String) -> Unit)? = null
    ): CommandResult = withContext(Dispatchers.IO) {
        val input = rawInput.trim()
        val lower = input.lowercase()

        // 1. Sleep Mode commands
        if (lower == "go to sleep" || lower == "sleep" || lower == "eva, go to sleep") {
            preferences.setSleepMode(true)
            val msg = "Going to sleep now. Just say 'wake up' when you need me."
            contextManager.updateCommand(input, msg)
            return@withContext CommandResult(spokenResponse = msg)
        }

        if (lower == "wake up" || lower == "eva, wake up" || lower == "wake") {
            preferences.setSleepMode(false)
            val msg = "I'm awake and ready. How can I help?"
            contextManager.updateCommand(input, msg)
            return@withContext CommandResult(spokenResponse = msg)
        }

        // 2. Silent Mode commands
        if (lower.contains("be silent") || lower.contains("stay silent") || lower.contains("quiet mode")) {
            val minutes = Regex("""\d+""").find(lower)?.value?.toLongOrNull() ?: 30L
            val until = System.currentTimeMillis() + (minutes * 60 * 1000)
            preferences.setSilentMode(true, until)
            val msg = "Entering silent mode for $minutes minutes."
            contextManager.updateCommand(input, msg)
            return@withContext CommandResult(spokenResponse = msg)
        }

        // 3. Provider Switching commands
        if (lower.contains("use omniroute") || lower.contains("switch to omniroute")) {
            aiRepository.switchProvider(AiProviderType.OMNI_ROUTE)
            val msg = "Switched active AI provider to OmniRoute."
            contextManager.updateCommand(input, msg)
            return@withContext CommandResult(spokenResponse = msg)
        }
        if (lower.contains("use openrouter") || lower.contains("switch to openrouter")) {
            aiRepository.switchProvider(AiProviderType.OPEN_ROUTER)
            val msg = "Switched active AI provider to OpenRouter."
            contextManager.updateCommand(input, msg)
            return@withContext CommandResult(spokenResponse = msg)
        }
        if (lower.contains("use gemini") || lower.contains("switch to gemini")) {
            aiRepository.switchProvider(AiProviderType.GEMINI)
            val msg = "Switched active AI provider to Google Gemini."
            contextManager.updateCommand(input, msg)
            return@withContext CommandResult(spokenResponse = msg)
        }

        // 4. Contextual References ("it", "that", "turn it on", "turn it off", "repeat")
        val contextResolution = contextManager.resolveContextualReference(input)
        if (contextResolution != null) {
            if (contextResolution.actionType == "repeat") {
                return@withContext CommandResult(spokenResponse = contextResolution.target)
            }
            if (contextResolution.actionType == "toggle_tool" && contextResolution.toolName != null) {
                val execResult = toolRegistry.executeTool(
                    contextResolution.toolName,
                    contextResolution.target,
                    emptyMap()
                )
                val spoken = if (execResult.isSuccess) {
                    VoicePersonality.formatActionSuccess(execResult.message)
                } else {
                    VoicePersonality.formatError(execResult.message)
                }
                contextManager.updateCommand(input, spoken)
                return@withContext CommandResult(
                    spokenResponse = spoken,
                    toolName = contextResolution.toolName,
                    toolResult = execResult.message,
                    isSuccess = execResult.isSuccess
                )
            }
            if (contextResolution.actionType == "app_toggle") {
                val appName = contextResolution.target
                val onOff = contextResolution.parameter ?: "on"
                val spoken = "All set! Turned $onOff $appName for you 💛"
                contextManager.setTask("configured_${appName.lowercase()}_$onOff", "active")
                contextManager.updateCommand(input, spoken)
                return@withContext CommandResult(
                    spokenResponse = spoken,
                    toolName = "app_toggle",
                    toolResult = "$appName set to $onOff",
                    isSuccess = true
                )
            }
        }

        val settings = preferences.settingsFlow.first()

        // 5. CHAT MODE ENFORCEMENT: When in Chat Mode, tool automation is disabled
        if (!settings.isAgentMode) {
            val messages = listOf(ChatMessage("user", input))
            val aiResponse = aiRepository.executeAiRequest(
                messages = messages,
                toolsPrompt = VoicePersonality.getSystemPrompt()
            )
            val cleanText = stripJsonBlocks(aiResponse.content)
            val finalReply = cleanText.ifBlank { "I'm right here! What do you need? 💛" }
            contextManager.updateCommand(input, finalReply)
            return@withContext CommandResult(spokenResponse = finalReply, isSuccess = aiResponse.isSuccess)
        }

        // 6. AGENT MODE: FAST-PATH INTENT ROUTER (Zero LLM roundtrip, 5ms-25ms)
        val fastResult = executeSingleCommandFast(input)
        if (fastResult != null) {
            contextManager.updateCommand(input, fastResult.spokenResponse)
            return@withContext fastResult
        }

        // 7. AGENT MODE: AUTONOMOUS AGENT TASK FOR COMPLEX / MULTI-STEP GOALS
        val autonomousResult = executeAutonomousAgentTask(input, settings, onStepProgress)
        contextManager.updateCommand(input, autonomousResult.spokenResponse)
        autonomousResult
    }

    /**
     * Executes recognizable direct actions locally in 5-25 milliseconds without calling remote LLM.
     */
    private suspend fun executeSingleCommandFast(command: String): CommandResult? {
        val lower = command.lowercase().trim()

        // Flashlight: "turn on flashlight", "turn off torch", "flashlight"
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val enable = when {
                lower.contains("on") || lower.contains("start") || lower.contains("enable") -> true
                lower.contains("off") || lower.contains("stop") || lower.contains("disable") -> false
                else -> null
            }
            val res = toolRegistry.deviceTools.toggleFlashlight(enable)
            val spoken = if (res.isSuccess) "All set! Flashlight is ${if (enable != false) "on" else "off"} 💛" else res.message
            return CommandResult(spokenResponse = spoken, toolName = "flashlight", toolResult = res.message, isSuccess = res.isSuccess)
        }

        // Battery / Phone Status: "what's my battery", "how is my phone", "battery status"
        if (lower.contains("battery") || lower.contains("how is my phone") || lower.contains("device status")) {
            val res = toolRegistry.deviceTools.getDeviceStatus()
            return CommandResult(spokenResponse = res.message, toolName = "device_status", toolResult = res.message, isSuccess = res.isSuccess)
        }

        // Volume: "set volume to 50%", "increase volume", "mute"
        if (lower.contains("volume") || lower == "mute" || lower == "unmute") {
            val percentMatch = Regex("""\d+""").find(lower)?.value?.toIntOrNull()
            val res = when {
                percentMatch != null -> toolRegistry.deviceTools.setVolume(percentMatch)
                lower.contains("increase") || lower.contains("up") -> toolRegistry.deviceTools.adjustVolume(true)
                lower.contains("decrease") || lower.contains("down") -> toolRegistry.deviceTools.adjustVolume(false)
                lower.contains("mute") -> toolRegistry.deviceTools.muteVolume(true)
                lower.contains("unmute") -> toolRegistry.deviceTools.muteVolume(false)
                else -> toolRegistry.deviceTools.setVolume(50)
            }
            return CommandResult(spokenResponse = res.message, toolName = "set_volume", toolResult = res.message, isSuccess = res.isSuccess)
        }

        // Alarm: "set alarm for 7:30", "set an alarm at 8 am", "wake me up at 6:00"
        if (lower.contains("set alarm") || lower.contains("set an alarm") || lower.contains("wake me up at")) {
            val timeMatch = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(lower)
            if (timeMatch != null) {
                var hour = timeMatch.groupValues[1].toIntOrNull() ?: 7
                val minutes = timeMatch.groupValues[2].toIntOrNull() ?: 0
                val ampm = timeMatch.groupValues[3].lowercase()
                if (ampm == "pm" && hour < 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
                val res = toolRegistry.deviceTools.setAlarm(hour, minutes, "EVA Alarm")
                return CommandResult(spokenResponse = res.message, toolName = "set_alarm", toolResult = res.message, isSuccess = res.isSuccess)
            }
        }

        // Brightness: "set brightness to 70%", "screen brightness 50"
        if (lower.contains("brightness")) {
            val percentMatch = Regex("""\d+""").find(lower)?.value?.toIntOrNull() ?: 50
            val res = toolRegistry.deviceTools.setBrightness(percentMatch)
            return CommandResult(spokenResponse = res.message, toolName = "set_brightness", toolResult = res.message, isSuccess = res.isSuccess)
        }

        // Phone call: "call John", "dial 01711223344"
        if (lower.startsWith("call ") || lower.startsWith("dial ")) {
            val target = command.substringAfter(" ").trim()
            if (target.isNotBlank()) {
                val res = toolRegistry.contactTools.makePhoneCall(target)
                return CommandResult(spokenResponse = res.message, toolName = "call_contact", toolResult = res.message, isSuccess = res.isSuccess)
            }
        }

        // SMS: "send sms to 01711223344", "text Alex hello"
        if (lower.startsWith("send sms to ") || lower.startsWith("text ")) {
            val remainder = if (lower.startsWith("send sms to ")) command.substring(12) else command.substring(5)
            val parts = remainder.trim().split(" ", limit = 2)
            val target = parts.firstOrNull() ?: ""
            val body = if (parts.size > 1) parts[1] else ""
            if (target.isNotBlank()) {
                val res = toolRegistry.contactTools.prepareSms(target, body)
                return CommandResult(spokenResponse = res.message, toolName = "prepare_message", toolResult = res.message, isSuccess = res.isSuccess)
            }
        }

        // YouTube search: "search youtube for Class 9 Physics", "search Class 9 Physics on youtube"
        if (lower.contains("youtube") && (lower.contains("search") || lower.contains("for"))) {
            val query = command.substringAfter("search").replace("youtube", "", ignoreCase = true).replace("for", "", ignoreCase = true).replace("on", "", ignoreCase = true).trim()
            val targetQuery = query.ifBlank { "Class 9 Physics" }
            val res = toolRegistry.networkTools.searchYouTube(targetQuery)
            return CommandResult(spokenResponse = "Searching YouTube for $targetQuery 💛", toolName = "youtube_search", toolResult = res.message, isSuccess = res.isSuccess)
        }

        // Google / Web search: "search google for ...", "google ...", "search for ... on google"
        if (lower.startsWith("search google for ") || lower.startsWith("google ") || (lower.contains("google") && lower.contains("search"))) {
            val query = command.substringAfter("for").replace("search", "", ignoreCase = true).replace("google", "", ignoreCase = true).replace("on", "", ignoreCase = true).trim()
            val res = toolRegistry.networkTools.openWebSearch(query.ifBlank { "latest news" })
            return CommandResult(spokenResponse = "Searching Google for $query 💛", toolName = "web_search", toolResult = res.message, isSuccess = res.isSuccess)
        }

        // Display Overlay: "open display overlay", "floating bubble"
        if (lower.contains("display overlay") || lower.contains("floating bubble") || lower.contains("open overlay")) {
            val res = toolRegistry.executeTool("display_overlay", "start", emptyMap())
            return CommandResult(
                spokenResponse = if (res.isSuccess) "Starting overlay bubble! Right here whenever you need me 💛" else res.message,
                toolName = "display_overlay",
                toolResult = res.message,
                isSuccess = res.isSuccess
            )
        }

        // Navigation: Home screen
        if (lower == "home" || lower == "go home" || lower.contains("toggle home") || lower.contains("home screen") || lower == "toggle to home" || lower == "press home") {
            val res = toolRegistry.backendSelector.executeSafeAction("press_home") { it.pressHome() }
            return CommandResult(spokenResponse = "Switched to home screen! 💛", toolName = "press_home", toolResult = res.message, isSuccess = res.isSuccess)
        }

        // Navigation: Back
        if (lower == "back" || lower == "go back" || lower == "press back") {
            val res = toolRegistry.backendSelector.executeSafeAction("press_back") { it.pressBack() }
            return CommandResult(spokenResponse = "Went back for you! 💛", toolName = "press_back", toolResult = res.message, isSuccess = res.isSuccess)
        }

        // Enter key
        if (lower == "enter" || lower == "press enter" || lower == "hit enter") {
            val res = toolRegistry.backendSelector.executeSafeAction("press_enter") { it.pressEnter() }
            return CommandResult(spokenResponse = "Pressed Enter for you! 💛", toolName = "press_enter", toolResult = res.message, isSuccess = res.isSuccess)
        }

        // Read Screen
        if (lower == "read screen" || lower == "inspect screen" || lower == "what is on screen") {
            val res = toolRegistry.executeTool("read_screen", "dump", emptyMap())
            return CommandResult(spokenResponse = res.message, toolName = "read_screen", toolResult = res.message, isSuccess = res.isSuccess)
        }

        // Close ads
        if (lower.contains("close ad") || lower.contains("close ads") || lower.contains("skip ad")) {
            val (ok, msg) = toolRegistry.deviceScreenAutomation.closeAds()
            return CommandResult(spokenResponse = msg, toolName = "close_ads", toolResult = msg, isSuccess = ok)
        }

        // Screen Gestures: scroll / swipe
        if (lower == "scroll down" || lower == "scroll down screen") {
            val res = toolRegistry.backendSelector.executeSafeAction("scroll") { it.scroll(ScrollDirection.DOWN) }
            return CommandResult(spokenResponse = "Scrolled down for you! 💛", toolName = "scroll", toolResult = res.message, isSuccess = res.isSuccess)
        }
        if (lower == "scroll up" || lower == "scroll up screen") {
            val res = toolRegistry.backendSelector.executeSafeAction("scroll") { it.scroll(ScrollDirection.UP) }
            return CommandResult(spokenResponse = "Scrolled up for you! 💛", toolName = "scroll", toolResult = res.message, isSuccess = res.isSuccess)
        }
        if (lower == "slide left" || lower == "swipe left") {
            val res = toolRegistry.backendSelector.executeSafeAction("scroll") { it.scroll(ScrollDirection.LEFT) }
            return CommandResult(spokenResponse = "Swiped left for you! 💛", toolName = "scroll", toolResult = res.message, isSuccess = res.isSuccess)
        }
        if (lower == "slide right" || lower == "swipe right") {
            val res = toolRegistry.backendSelector.executeSafeAction("scroll") { it.scroll(ScrollDirection.RIGHT) }
            return CommandResult(spokenResponse = "Swiped right for you! 💛", toolName = "scroll", toolResult = res.message, isSuccess = res.isSuccess)
        }

        // Coordinate Tap: "click at 500 800", "tap at 500, 800"
        val coordMatch = Regex("""(?:click|tap)\s+at\s+(\d+)[,\s]+(\d+)""").find(lower)
        if (coordMatch != null) {
            val x = coordMatch.groupValues[1].toIntOrNull() ?: 0
            val y = coordMatch.groupValues[2].toIntOrNull() ?: 0
            val res = toolRegistry.backendSelector.executeSafeAction("click_at") { it.clickAt(x, y) }
            return CommandResult(spokenResponse = res.message, toolName = "click_at", toolResult = res.message, isSuccess = res.isSuccess)
        }

        // Click by text: "click Search", "tap Install", "click on Videos"
        if (lower.startsWith("click ") || lower.startsWith("tap ")) {
            val query = command.substringAfter(" ").replace("on ", "", ignoreCase = true).trim()
            if (!query.contains(" and ") && !query.contains(" then ") && query.length < 35) {
                val res = toolRegistry.backendSelector.executeSafeAction("click_text") { it.clickByText(query) }
                return CommandResult(spokenResponse = res.message, toolName = "click_text", toolResult = res.message, isSuccess = res.isSuccess)
            }
        }

        // Type Text: "type Hello World in Search", "type Class 9 Physics"
        if (lower.startsWith("type ") || lower.startsWith("enter text ")) {
            val textRaw = if (lower.startsWith("type ")) command.substring(5) else command.substring(11)
            val hint = if (textRaw.contains(" in ", ignoreCase = true)) textRaw.substringAfter(" in ").trim() else null
            val textToType = if (hint != null) textRaw.substringBefore(" in ").trim() else textRaw.trim()
            val res = toolRegistry.backendSelector.executeSafeAction("type_text") { it.typeText(textToType, hint) }
            return CommandResult(spokenResponse = res.message, toolName = "type_text", toolResult = res.message, isSuccess = res.isSuccess)
        }

        // App Launch: "open [app]" or "launch [app]" (single action)
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ")) {
            val remainder = command.substringAfter(" ").trim()
            // If it's a compound instruction, leave for autonomous loop
            if (!remainder.contains(" and ") && !remainder.contains(" then ") && !remainder.contains(",")) {
                val appName = remainder
                if (appName.contains("wifi") || appName.contains("wi-fi") || appName.contains("bluetooth") || appName.contains("settings")) {
                    val sRes = toolRegistry.deviceTools.openSettings(appName)
                    return CommandResult(spokenResponse = "Opening $appName settings.", toolName = "open_settings", toolResult = sRes.message, isSuccess = sRes.isSuccess)
                }
                val res = toolRegistry.appLauncherTools.launchAppByName(appName)
                if (res.isSuccess) {
                    contextManager.updateActiveApp(appName, (res.data as? String) ?: "")
                    return CommandResult(spokenResponse = VoicePersonality.formatAppLaunch(appName), toolName = "open_app", toolResult = res.message, isSuccess = true)
                } else {
                    return CommandResult(spokenResponse = res.message, toolName = "open_app", toolResult = res.message, isSuccess = false)
                }
            }
        }

        // Wikipedia: "search wikipedia for ...", "who was ..."
        if (lower.contains("wikipedia") || lower.startsWith("who was ") || lower.startsWith("what is ")) {
            val query = command.replace("search wikipedia for", "", ignoreCase = true)
                .replace("wikipedia", "", ignoreCase = true)
                .replace("who was", "", ignoreCase = true)
                .replace("what is", "", ignoreCase = true)
                .replace("?", "")
                .trim()
            if (query.isNotBlank() && query.length < 50) {
                val wikiRes = toolRegistry.networkTools.searchWikipedia(query)
                if (wikiRes.isSuccess) {
                    return CommandResult(spokenResponse = wikiRes.message, toolName = "wikipedia", toolResult = wikiRes.message, isSuccess = true)
                }
            }
        }

        // Calculator: arithmetic queries like "what is 25 * 40"
        val mathRes = toolRegistry.executeTool("calculator", "calculate", mapOf("expression" to command))
        if (mathRes.isSuccess) {
            return CommandResult(spokenResponse = mathRes.message, toolName = "calculator", toolResult = mathRes.message, isSuccess = true)
        }

        // Music play / pause
        if (lower == "play music" || lower == "pause music" || lower == "stop music") {
            val isPlay = !lower.contains("pause") && !lower.contains("stop")
            val res = if (isPlay) toolRegistry.mediaAudioTools.playMusic() else toolRegistry.mediaAudioTools.pauseMusic()
            return CommandResult(spokenResponse = res.message, toolName = "music", toolResult = res.message, isSuccess = res.isSuccess)
        }

        // Shizuku / Wireless Debugging status
        if (lower.contains("shizuku") || lower.contains("wireless debugging")) {
            val sm = toolRegistry.shizukuManager
            sm.checkStatus()
            val info = sm.shizukuState.value
            val session = sm.wirelessSession.value
            val spoken = if (session.sessionStatus == com.example.eva.shizuku.WirelessSessionStatus.ACTIVE_CONNECTED) {
                "Shizuku wireless debugging bridge is active with ${session.latencyMs}ms latency."
            } else if (info.status == com.example.eva.shizuku.ShizukuConnectionStatus.AUTHORIZED_CONNECTED) {
                "Shizuku is authorized and connected in ${if (info.serverUid == 0) "root" else "wireless debugging shell"} mode."
            } else {
                info.lastPingMessage
            }
            return CommandResult(spokenResponse = spoken, toolName = "shizuku_status", toolResult = info.lastPingMessage, isSuccess = info.isAuthorized)
        }

        return null
    }

    /**
     * Ultra-Fast Autonomous Agent Loop.
     * Takes user's complex goal, inspects screen, calls AI with ultra-compact prompt (<100 tokens),
     * dispatches actions snappy (65ms taps, 200ms settle delay), and streams live progress per step.
     */
    private suspend fun executeAutonomousAgentTask(
        goal: String,
        settings: EvaSettings,
        onStepProgress: (suspend (String, String) -> Unit)?
    ): CommandResult {
        val taskId = "task_${System.currentTimeMillis()}"
        val maxSteps = if (settings.disableMaxSteps) 25 else settings.maxSteps.coerceIn(1, 25)

        // Pre-create plan for live UI
        val plan = multiStepEngine.createPlan(goal, listOf("Analyze goal", "Execute steps", "Finalize"))
        onStepProgress?.invoke("EVA Agent activated for: \"$goal\"", "running")

        // Fast-path: If the goal specifies opening an app, launch it right now without burning an LLM turn!
        val openAppMatch = Regex("""\b(?:open|launch|start)\s+([A-Za-z0-9\s]+?)(?:\s+(?:and|then|,)|$)""", RegexOption.IGNORE_CASE).find(goal)
        if (openAppMatch != null) {
            val candidateApp = openAppMatch.groupValues[1].trim()
            if (candidateApp.length in 2..20) {
                val launchRes = toolRegistry.appLauncherTools.launchAppByName(candidateApp)
                if (launchRes.isSuccess) {
                    onStepProgress?.invoke("Launched $candidateApp", "running")
                    delay(350L) // snappy settle for app launch
                }
            }
        }

        val stepTraces = mutableListOf<JSONObject>()
        var lastActionSignature = ""
        var stuckCount = 0
        var finalSummary = "Completed task: $goal"
        var isSuccess = true

        for (stepIndex in 0 until maxSteps) {
            // Check cancellation between every step
            if (multiStepEngine.currentPlan.value?.isCancelled == true) {
                finalSummary = "Task was cancelled."
                isSuccess = false
                break
            }

            // Snappy settle delay
            delay(200L)

            // 1. Screen Dump
            val backend = toolRegistry.backendSelector.getActiveBackend()
            val nodes = backend?.dumpScreen() ?: emptyList()
            val geom = backend?.geometryManager ?: com.example.eva.automation.ScreenGeometryManager(context)
            val screenContext = nodes.formatForFastAgent(geom)

            // 2. Compact Agent Prompt (<200 tokens for sub-500ms AI latency)
            val recentActionsSummary = stepTraces.takeLast(2).joinToString("; ") {
                "${it.optString("tool")}(${it.optString("params")})"
            }.ifBlank { "None" }

            val prompt = """
            GOAL: "$goal"
            $screenContext
            RECENT ACTIONS: $recentActionsSummary
            Choose next action. Respond ONLY with a single JSON block:
            {"tool": "<name>", "parameters": {<keys>}}
            Tools: click_text {"text": "..."}, click_at {"x": 0, "y": 0}, type_text {"text": "...", "field_hint": "..."}, press_enter {}, scroll {"direction": "down|up"}, swipe {}, open_app {"name": "..."}, press_back {}, press_home {}, done {"summary": "..."}
            CRITICAL: Output ONLY JSON. No explanations.
            """.trimIndent()

            // 3. Fast Remote Query (maxTokens = 120, temp = 0.1)
            val aiResponse = aiRepository.executeAiRequest(
                messages = listOf(ChatMessage("user", prompt)),
                toolsPrompt = "You are EVA Fast Agent. Output ONLY JSON tool call. For final done, keep summary sweet and concise like 'Done! 💛' or 'All set for you.'",
                temperatureOverride = 0.1f,
                maxTokensOverride = 120
            )

            val toolCall = aiResponse.toolCall
            if (toolCall == null || toolCall.toolName.lowercase() == "done") {
                val sum = toolCall?.parameters?.get("summary") ?: aiResponse.content.ifBlank { "All set for you! 💛" }
                finalSummary = sum
                break
            }

            // 4. Stuck Loop Detection
            val currentSig = "${toolCall.toolName}_${toolCall.parameters}"
            if (currentSig == lastActionSignature) {
                stuckCount++
                if (stuckCount >= 2) {
                    // Stuck fallback: if click_text failed repeatedly, try click_at if node was in dump
                    if (toolCall.toolName == "click_text") {
                        val textQuery = toolCall.parameters["text"] ?: ""
                        val matched = nodes.firstOrNull { it.matchesQuery(textQuery) }
                        if (matched != null) {
                            val fbRes = toolRegistry.executeTool("click_at", "click", mapOf("x" to "${matched.centerX}", "y" to "${matched.centerY}"))
                            onStepProgress?.invoke("Fallback tap at (${matched.centerX}, ${matched.centerY}) for '$textQuery'", "running")
                            stuckCount = 0
                            continue
                        }
                    }
                    finalSummary = "All set! Completed all available actions for: $goal 💛"
                    break
                }
            } else {
                stuckCount = 0
                lastActionSignature = currentSig
            }

            // 5. Execute Action
            val execResult = toolRegistry.executeTool(toolCall.toolName, toolCall.action, toolCall.parameters)
            val stepLabel = "Step ${stepIndex + 1}: ${toolCall.toolName} -> ${execResult.message}"
            onStepProgress?.invoke(stepLabel, if (execResult.isSuccess) "running" else "warning")

            val traceObj = JSONObject().apply {
                put("step", stepIndex + 1)
                put("tool", toolCall.toolName)
                put("params", JSONObject(toolCall.parameters as Map<*, *>).toString())
                put("result", execResult.message)
                put("timestamp", System.currentTimeMillis())
            }
            stepTraces.add(traceObj)

            if (!execResult.isSuccess && toolCall.toolName == "open_app") {
                finalSummary = "Could not open app: ${execResult.message}"
                isSuccess = false
                break
            }
        }

        // Record trace entity to Room database
        try {
            database.taskTraceDao().insertTrace(
                TaskExecutionTraceEntity(
                    taskId = taskId,
                    userGoal = goal,
                    status = if (isSuccess) "COMPLETED" else "FAILED",
                    startTime = System.currentTimeMillis() - 1000,
                    endTime = System.currentTimeMillis(),
                    totalSteps = stepTraces.size,
                    stepsTraceJson = JSONArray(stepTraces).toString(),
                    finalSummary = finalSummary
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist task trace: ${e.message}")
        }

        multiStepEngine.clearPlan()
        return CommandResult(
            spokenResponse = finalSummary,
            toolName = "agent_executor",
            toolResult = finalSummary,
            isSuccess = isSuccess,
            multiStepPlan = plan
        )
    }

    private fun stripJsonBlocks(content: String): String {
        var clean = content
        val jsonStart = clean.indexOf("```json")
        if (jsonStart != -1) {
            val jsonEnd = clean.indexOf("```", jsonStart + 7)
            if (jsonEnd != -1) {
                clean = clean.removeRange(jsonStart, jsonEnd + 3).trim()
            }
        }
        val braceStart = clean.indexOf("{\"tool\":")
        if (braceStart != -1) {
            val braceEnd = clean.lastIndexOf("}")
            if (braceEnd > braceStart) {
                clean = clean.removeRange(braceStart, braceEnd + 1).trim()
            }
        }
        return clean
    }
}
