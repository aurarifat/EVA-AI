package com.example.eva.overlay

import android.graphics.Bitmap
import com.example.eva.agent.EvaAgentMode
import com.example.eva.agent.OrchestratorMessage
import com.example.eva.data.prefs.AiProviderType

/**
 * High-level operative modes for the persistent Eva Bubble overlay.
 */
enum class BubbleMode {
    IDLE,
    LISTENING,
    SPEAKING
}

/**
 * UI State for the persistent Compose Eva Bubble overlay.
 * Represents persistent multi-agent sessions, screen broadcast status, and dock UI.
 */
data class BubbleOverlayUiState(
    val mode: BubbleMode = BubbleMode.IDLE,
    val statusText: String = "EVA Ready",
    val recognizedText: String = "",
    val spokenText: String = "",
    val rmsLevel: Float = 0f,
    val isExpanded: Boolean = false,
    val isProcessing: Boolean = false,
    val isShizukuActive: Boolean = false,
    val isWakeWordHighlight: Boolean = false,
    val agentMode: EvaAgentMode = EvaAgentMode.AUTONOMOUS_AGENT,
    val activeProvider: AiProviderType = AiProviderType.OMNI_ROUTE,
    val isScreenBroadcasting: Boolean = false,
    val isBroadcastPaused: Boolean = false,
    val latestThumbnail: Bitmap? = null,
    val conversationHistory: List<OrchestratorMessage> = emptyList(),
    val currentTask: String? = "Ready for command",
    val isSessionPaused: Boolean = false,
    val pendingConfirmation: String? = null,
    val bubbleAlpha: Float = 0.95f
)
