package com.example.eva.overlay

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
 */
data class BubbleOverlayUiState(
    val mode: BubbleMode = BubbleMode.IDLE,
    val statusText: String = "EVA Ready",
    val recognizedText: String = "",
    val spokenText: String = "",
    val rmsLevel: Float = 0f,
    val isExpanded: Boolean = false,
    val isProcessing: Boolean = false,
    val isShizukuActive: Boolean = false
)
