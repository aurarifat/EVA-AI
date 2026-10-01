package com.example.eva.agent

/**
 * Universal Agent Modes supported by EVA AI.
 * Shared across the floating bubble, main activity, voice engine, and background services.
 */
enum class EvaAgentMode(
    val id: String,
    val displayName: String,
    val shortName: String,
    val description: String,
    val supportsVision: Boolean,
    val supportsDirectActions: Boolean,
    val supportsAutonomousLoop: Boolean,
    val supportsContinuousVoice: Boolean
) {
    AUTONOMOUS_AGENT(
        id = "autonomous_agent",
        displayName = "Autonomous Agent",
        shortName = "Agent",
        description = "Multi-step screen automation, app navigation, clicking, typing, and goal execution",
        supportsVision = true,
        supportsDirectActions = true,
        supportsAutonomousLoop = true,
        supportsContinuousVoice = true
    ),
    CHAT(
        id = "chat",
        displayName = "Conversational Chat",
        shortName = "Chat",
        description = "Natural conversational dialogue, writing, reasoning, and companion support without device actions",
        supportsVision = false,
        supportsDirectActions = false,
        supportsAutonomousLoop = false,
        supportsContinuousVoice = false
    ),
    VOICE_ASSISTANT(
        id = "voice_assistant",
        displayName = "Voice Companion",
        shortName = "Voice",
        description = "Low-latency continuous speech interaction, wake-word aware, hands-free voice dialogue",
        supportsVision = false,
        supportsDirectActions = true,
        supportsAutonomousLoop = false,
        supportsContinuousVoice = true
    ),
    FAST_TOOL(
        id = "fast_tool",
        displayName = "Direct Tool Mode",
        shortName = "Tools",
        description = "Zero-latency direct device controls: flashlight, volume, apps, calls, battery, and settings",
        supportsVision = false,
        supportsDirectActions = true,
        supportsAutonomousLoop = false,
        supportsContinuousVoice = false
    ),
    SCREEN_VISION(
        id = "screen_vision",
        displayName = "Live Screen Vision",
        shortName = "Vision",
        description = "Live MediaProjection screen broadcast with real-time visual analysis of the current screen",
        supportsVision = true,
        supportsDirectActions = true,
        supportsAutonomousLoop = true,
        supportsContinuousVoice = true
    );

    companion object {
        fun fromId(id: String): EvaAgentMode {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTONOMOUS_AGENT
        }
    }
}
