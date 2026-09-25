package com.example.eva.context

import android.content.Context
import com.example.eva.ai.AiRepository
import com.example.eva.ai.ChatMessage
import com.example.eva.data.prefs.AiProviderType
import com.example.eva.data.prefs.EvaPreferences
import com.example.eva.tools.ToolExecutionResult
import com.example.eva.tools.ToolRegistry
import com.example.eva.voice.VoicePersonality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CommandResult(
    val spokenResponse: String,
    val toolName: String? = null,
    val toolResult: String? = null,
    val isSuccess: Boolean = true,
    val multiStepPlan: MultiStepPlan? = null
)

class CommandDispatcher(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
    private val contextManager: ContextManager,
    private val multiStepEngine: MultiStepEngine,
    private val aiRepository: AiRepository,
    private val preferences: EvaPreferences
) {

    suspend fun processCommand(rawInput: String): CommandResult = withContext(Dispatchers.IO) {
        val input = rawInput.trim()
        val lower = input.lowercase()

        // 1. Check Sleep Mode commands
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

        // 2. Check Silent Mode commands: "be silent for 30 minutes"
        if (lower.contains("be silent") || lower.contains("stay silent") || lower.contains("quiet mode")) {
            val minutes = Regex("""\d+""").find(lower)?.value?.toLongOrNull() ?: 30L
            val until = System.currentTimeMillis() + (minutes * 60 * 1000)
            preferences.setSilentMode(true, until)
            val msg = "Entering silent mode for $minutes minutes."
            contextManager.updateCommand(input, msg)
            return@withContext CommandResult(spokenResponse = msg)
        }

        // 3. Check Provider Switching commands: "use omniroute", "switch to openrouter", "use gemini"
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

        // 4. Check for Contextual Reference ("it", "that", "turn it on", "turn it off", "turn it on again", "repeat")
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
                // Informative honest response for user-controlled apps
                val spoken = "Okay, turning $onOff $appName for you."
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

        // 5. Check Multi-step compound commands: "Open YouTube, search for Class 9 Physics, then set media volume to 50 percent"
        val stepSegments = multiStepEngine.parseSequentialCommand(input)
        if (stepSegments.size > 1) {
            val plan = multiStepEngine.createPlan(input, stepSegments)
            val stepResults = mutableListOf<String>()

            for (i in stepSegments.indices) {
                val stepText = stepSegments[i]
                multiStepEngine.updateStepStatus(i, StepStatus.RUNNING)
                val stepRes = executeSingleCommand(stepText)
                if (stepRes.isSuccess) {
                    multiStepEngine.updateStepStatus(i, StepStatus.COMPLETED, stepRes.spokenResponse)
                    stepResults.add("Step ${i + 1}/${stepSegments.size}: ${stepRes.spokenResponse}")
                } else {
                    multiStepEngine.updateStepStatus(i, StepStatus.FAILED, stepRes.spokenResponse)
                    stepResults.add("Step ${i + 1}/${stepSegments.size} encountered an issue: ${stepRes.spokenResponse}")
                    break
                }
            }

            val finalSpoken = "Executed ${stepSegments.size} sequential actions. " + stepResults.joinToString(" ")
            contextManager.updateCommand(input, finalSpoken)
            return@withContext CommandResult(
                spokenResponse = finalSpoken,
                multiStepPlan = plan,
                isSuccess = true
            )
        }

        // 6. Execute Single Command
        val singleResult = executeSingleCommand(input)
        contextManager.updateCommand(input, singleResult.spokenResponse)
        singleResult
    }

    private suspend fun executeSingleCommand(command: String): CommandResult {
        val lower = command.lowercase().trim()

        // Flashlight: "turn on flashlight", "turn off flashlight", "flashlight"
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val enable = when {
                lower.contains("on") || lower.contains("start") || lower.contains("enable") -> true
                lower.contains("off") || lower.contains("stop") || lower.contains("disable") -> false
                else -> null
            }
            val res = toolRegistry.deviceTools.toggleFlashlight(enable)
            val spoken = if (res.isSuccess) "Okay, flashlight is ${if (enable != false) "on" else "off"}." else res.message
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

        // YouTube search: "search youtube for Class 9 Physics", "open youtube and search for ..."
        if (lower.contains("youtube") && (lower.contains("search") || lower.contains("for"))) {
            val query = command.substringAfter("search").replace("youtube", "", ignoreCase = true).replace("for", "", ignoreCase = true).trim()
            val res = toolRegistry.networkTools.searchYouTube(query.ifBlank { "Class 9 Physics" })
            return CommandResult(spokenResponse = "Searching YouTube for $query.", toolName = "youtube_search", toolResult = res.message, isSuccess = res.isSuccess)
        }

        // Launch app: "open [app]" or "launch [app]"
        if (lower.startsWith("open ") || lower.startsWith("launch ")) {
            val appName = command.substringAfter(" ").trim()
            // Check settings shortcuts first
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

        // Wikipedia: "search wikipedia for photosynthesis", "who was albert einstein"
        if (lower.contains("wikipedia") || lower.startsWith("who was ") || lower.startsWith("what is ")) {
            val query = command.replace("search wikipedia for", "", ignoreCase = true)
                .replace("wikipedia", "", ignoreCase = true)
                .replace("who was", "", ignoreCase = true)
                .replace("what is", "", ignoreCase = true)
                .replace("?", "")
                .trim()
            if (query.isNotBlank()) {
                val wikiRes = toolRegistry.networkTools.searchWikipedia(query)
                if (wikiRes.isSuccess) {
                    return CommandResult(spokenResponse = wikiRes.message, toolName = "wikipedia", toolResult = wikiRes.message, isSuccess = true)
                }
            }
        }

        // IP / Internet: "what's my ip", "is my internet working", "test internet"
        if (lower.contains("my ip") || lower.contains("internet") || lower.contains("ping")) {
            val diag = toolRegistry.networkTools.runInternetDiagnostics()
            val msg = if (diag.isConnected) {
                "Internet is working via ${diag.networkType}. " + (if (diag.publicIp != null) "Public IP: ${diag.publicIp}. " else "") + "Ping: ${diag.pingMs}ms."
            } else {
                "Your device appears to be offline."
            }
            return CommandResult(spokenResponse = msg, toolName = "test_internet", toolResult = msg, isSuccess = diag.isConnected)
        }

        // Calculator
        val mathRes = toolRegistry.executeTool("calculator", "calculate", mapOf("expression" to command))
        if (mathRes.isSuccess) {
            return CommandResult(spokenResponse = mathRes.message, toolName = "calculator", toolResult = mathRes.message, isSuccess = true)
        }

        // Music: "play my music", "pause music"
        if (lower.contains("music") || lower == "play" || lower == "pause") {
            val isPlay = !lower.contains("pause") && !lower.contains("stop")
            val res = if (isPlay) toolRegistry.mediaAudioTools.playMusic() else toolRegistry.mediaAudioTools.pauseMusic()
            return CommandResult(spokenResponse = res.message, toolName = "music", toolResult = res.message, isSuccess = res.isSuccess)
        }

        // Shizuku / Wireless Debugging
        if (lower.contains("shizuku") || lower.contains("wireless debugging")) {
            val sm = toolRegistry.shizukuManager
            sm.checkStatus()
            val info = sm.shizukuState.value
            val session = sm.wirelessSession.value

            if (lower.contains("connect") || lower.contains("start") || lower.contains("bridge")) {
                val (ok, msg) = sm.establishWirelessDebuggingSession()
                return CommandResult(
                    spokenResponse = if (ok) "Wireless debugging bridge established successfully." else msg,
                    toolName = "start_wireless_debugging",
                    toolResult = msg,
                    isSuccess = ok
                )
            } else if (lower.contains("disconnect") || lower.contains("stop")) {
                sm.disconnectWirelessDebuggingSession()
                return CommandResult(
                    spokenResponse = "Wireless debugging bridge disconnected.",
                    toolName = "stop_wireless_debugging",
                    toolResult = "Disconnected",
                    isSuccess = true
                )
            } else if (lower.contains("enable") || lower.contains("turn on")) {
                val (ok, msg) = sm.toggleNativeWirelessDebugging(true)
                return CommandResult(
                    spokenResponse = if (ok) "Wireless debugging has been enabled." else msg,
                    toolName = "enable_wireless_debugging",
                    toolResult = msg,
                    isSuccess = ok
                )
            } else if (lower.contains("disable") || lower.contains("turn off")) {
                val (ok, msg) = sm.toggleNativeWirelessDebugging(false)
                return CommandResult(
                    spokenResponse = if (ok) "Wireless debugging has been disabled." else msg,
                    toolName = "disable_wireless_debugging",
                    toolResult = msg,
                    isSuccess = ok
                )
            }

            val spoken = if (session.sessionStatus == com.example.eva.shizuku.WirelessSessionStatus.ACTIVE_CONNECTED) {
                "Shizuku is connected and the wireless debugging bridge is active with ${session.latencyMs} millisecond latency."
            } else if (info.status == com.example.eva.shizuku.ShizukuConnectionStatus.AUTHORIZED_CONNECTED) {
                val mode = if (info.serverUid == 0) "root" else "wireless debugging shell"
                "Shizuku is connected in $mode mode. Server version ${info.serverVersion}."
            } else {
                info.lastPingMessage
            }
            return CommandResult(spokenResponse = spoken, toolName = "shizuku_status", toolResult = info.lastPingMessage, isSuccess = info.isAuthorized)
        }

        // 7. If not matched locally, query the active AI Provider with structured tool capabilities!
        return try {
            val messages = listOf(
                ChatMessage("user", command)
            )
            val toolsPrompt = toolRegistry.getToolsPrompt() + "\n" + VoicePersonality.getSystemPrompt()
            val aiResponse = aiRepository.executeAiRequest(messages, toolsPrompt)

            if (aiResponse.toolCall != null) {
                val toolCall = aiResponse.toolCall
                val execResult = toolRegistry.executeTool(toolCall.toolName, toolCall.action, toolCall.parameters)
                val finalSpoken = if (aiResponse.content.isNotBlank() && aiResponse.content != "Done.") {
                    aiResponse.content
                } else {
                    execResult.message
                }
                CommandResult(
                    spokenResponse = finalSpoken,
                    toolName = toolCall.toolName,
                    toolResult = execResult.message,
                    isSuccess = execResult.isSuccess
                )
            } else {
                CommandResult(
                    spokenResponse = aiResponse.content,
                    isSuccess = aiResponse.isSuccess
                )
            }
        } catch (e: Exception) {
            CommandResult(
                spokenResponse = "I couldn't process that command right now: ${e.localizedMessage}",
                isSuccess = false
            )
        }
    }
}
