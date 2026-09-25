package com.example.eva.context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveAppContext(
    val appName: String,
    val packageName: String,
    val openedAt: Long = System.currentTimeMillis(),
    val supportedToggles: List<String> = listOf("protection", "enabled", "power", "playback")
)

data class SessionState(
    val activeApp: ActiveAppContext? = null,
    val previousApp: ActiveAppContext? = null,
    val activeTool: String? = null,
    val activeTask: String? = null,
    val taskState: String = "idle", // "idle", "active", "completed", "failed"
    val enabledState: Boolean = false,
    val lastAction: String? = null,
    val lastResult: String? = null,
    val lastSpokenCommand: String? = null,
    val lastEvaResponse: String? = null
)

class ContextManager {

    private val _sessionState = MutableStateFlow(SessionState())
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    fun updateActiveApp(appName: String, packageName: String) {
        val current = _sessionState.value
        _sessionState.value = current.copy(
            previousApp = current.activeApp,
            activeApp = ActiveAppContext(appName, packageName),
            activeTask = "app_session_${appName.lowercase()}",
            taskState = "active",
            lastAction = "launch_app_$appName"
        )
    }

    fun updateToolExecution(toolName: String, action: String, result: String, success: Boolean) {
        val current = _sessionState.value
        _sessionState.value = current.copy(
            activeTool = toolName,
            lastAction = action,
            lastResult = result,
            taskState = if (success) "completed" else "failed",
            enabledState = when {
                action.contains("on", ignoreCase = true) || action.contains("enable", ignoreCase = true) || action.contains("start", ignoreCase = true) -> true
                action.contains("off", ignoreCase = true) || action.contains("disable", ignoreCase = true) || action.contains("stop", ignoreCase = true) -> false
                else -> current.enabledState
            }
        )
    }

    fun setTask(taskName: String, state: String = "active") {
        _sessionState.value = _sessionState.value.copy(
            activeTask = taskName,
            taskState = state
        )
    }

    fun updateCommand(command: String, response: String) {
        _sessionState.value = _sessionState.value.copy(
            lastSpokenCommand = command,
            lastEvaResponse = response
        )
    }

    /**
     * Resolves pronouns and contextual references like "it", "that", "turn it on", "turn it off",
     * "open it", "start it", "stop it", "repeat that".
     */
    fun resolveContextualReference(command: String): ResolvedContextAction? {
        val lower = command.trim().lowercase()
        val current = _sessionState.value

        // Check for repeat
        if (lower == "repeat that" || lower == "repeat" || lower == "say again") {
            return ResolvedContextAction(
                actionType = "repeat",
                target = current.lastEvaResponse ?: "Nothing to repeat."
            )
        }

        // Toggle on: "turn it on", "enable it", "turn it on again", "switch it on", "start it"
        val isTurnOn = lower.contains("turn it on") || lower.contains("turn it on again") ||
                lower.contains("switch it on") || lower.contains("enable it") ||
                lower == "turn on" || lower == "start it" || lower == "power on"

        // Toggle off: "turn it off", "disable it", "switch it off", "stop it", "close it"
        val isTurnOff = lower.contains("turn it off") || lower.contains("switch it off") ||
                lower.contains("disable it") || lower == "turn off" ||
                lower == "stop it" || lower == "close it" || lower == "power off"

        if (isTurnOn || isTurnOff) {
            val desiredState = isTurnOn
            // Determine target: flashlight? activeApp? activeTool? screen recording?
            val activeTool = current.activeTool
            val activeApp = current.activeApp

            when {
                activeTool == "flashlight" -> {
                    return ResolvedContextAction(
                        actionType = "toggle_tool",
                        toolName = "flashlight",
                        target = if (desiredState) "enable" else "disable"
                    )
                }
                activeTool == "screen_recording" -> {
                    return ResolvedContextAction(
                        actionType = "toggle_tool",
                        toolName = "screen_recording",
                        target = if (desiredState) "start" else "stop"
                    )
                }
                activeTool == "voice_recording" -> {
                    return ResolvedContextAction(
                        actionType = "toggle_tool",
                        toolName = "voice_recording",
                        target = if (desiredState) "start" else "stop"
                    )
                }
                activeTool == "music" -> {
                    return ResolvedContextAction(
                        actionType = "toggle_tool",
                        toolName = "media_control",
                        target = if (desiredState) "play" else "pause"
                    )
                }
                activeApp != null -> {
                    // For apps like AdGuard, VPNs, or apps that user launched
                    return ResolvedContextAction(
                        actionType = "app_toggle",
                        toolName = "app_toggle",
                        target = activeApp.appName,
                        parameter = if (desiredState) "on" else "off"
                    )
                }
            }
        }

        // Check for "open the previous app" or "go back"
        if (lower.contains("previous app") && current.previousApp != null) {
            return ResolvedContextAction(
                actionType = "open_app",
                target = current.previousApp.appName,
                parameter = current.previousApp.packageName
            )
        }

        return null
    }

    fun clear() {
        _sessionState.value = SessionState()
    }
}

data class ResolvedContextAction(
    val actionType: String,
    val toolName: String? = null,
    val target: String = "",
    val parameter: String? = null
)
