package com.example.eva.agent

import com.example.eva.data.prefs.AiProviderType

/**
 * Capability Matrix and Safety Checker for EVA Agent Modes and AI Providers.
 */
object AgentModeCapability {

    data class CapabilityInfo(
        val mode: EvaAgentMode,
        val provider: AiProviderType,
        val hasVision: Boolean,
        val hasToolCalling: Boolean,
        val hasContinuousVoice: Boolean,
        val requiresAccessibility: Boolean,
        val requiresMediaProjection: Boolean,
        val description: String
    )

    /**
     * Determines if a specific AI provider model configuration natively supports visual image processing.
     */
    fun isProviderVisionCapable(provider: AiProviderType, modelName: String): Boolean {
        return when (provider) {
            AiProviderType.GEMINI -> true // All Gemini 1.5/2.5 models support vision
            AiProviderType.OPEN_ROUTER -> {
                val lower = modelName.lowercase()
                lower.contains("vision") || lower.contains("flash") || lower.contains("4o") ||
                        lower.contains("gemini") || lower.contains("claude-3") || lower.contains("vl")
            }
            AiProviderType.CUSTOM_OPENAI -> {
                val lower = modelName.lowercase()
                lower.contains("gpt-4o") || lower.contains("vision") || lower.contains("4-turbo")
            }
            AiProviderType.OMNI_ROUTE -> {
                val lower = modelName.lowercase()
                lower.contains("vision") || lower.contains("eva-v") || lower.contains("auto") || lower.contains("4o")
            }
        }
    }

    /**
     * Returns the full capability profile for an agent mode with the given provider.
     */
    fun getCapabilityProfile(
        mode: EvaAgentMode,
        provider: AiProviderType,
        modelName: String
    ): CapabilityInfo {
        val visionSupported = mode.supportsVision && isProviderVisionCapable(provider, modelName)

        return CapabilityInfo(
            mode = mode,
            provider = provider,
            hasVision = visionSupported,
            hasToolCalling = mode.supportsDirectActions,
            hasContinuousVoice = mode.supportsContinuousVoice,
            requiresAccessibility = mode.supportsAutonomousLoop,
            requiresMediaProjection = mode == EvaAgentMode.SCREEN_VISION,
            description = when (mode) {
                EvaAgentMode.AUTONOMOUS_AGENT -> "Full autonomous agent: screen reading, gestures, and multi-step tasks."
                EvaAgentMode.CHAT -> "Pure conversation: friendly dialogue, answers, and advice without device clicks."
                EvaAgentMode.VOICE_ASSISTANT -> "Hands-free voice companion: rapid spoken responses."
                EvaAgentMode.FAST_TOOL -> "Direct device actions: instant system controls and app launching."
                EvaAgentMode.SCREEN_VISION -> if (visionSupported) {
                    "Live screen broadcasting with vision AI analysis."
                } else {
                    "Live screen broadcasting with text/OCR fallback."
                }
            }
        )
    }

    /**
     * Generates a comprehensive compatibility report across all agent modes.
     */
    fun generateCompatibilityReport(activeProvider: AiProviderType, activeModel: String): String {
        return buildString {
            appendLine("EVA MULTI-AGENT COMPATIBILITY REPORT")
            appendLine("Active AI Provider: ${activeProvider.displayName} ($activeModel)")
            appendLine("--------------------------------------------------")
            EvaAgentMode.values().forEach { mode ->
                val cap = getCapabilityProfile(mode, activeProvider, activeModel)
                appendLine("[${mode.shortName.uppercase()}] ${mode.displayName}")
                appendLine("  • Vision Supported: ${if (cap.hasVision) "Yes (Direct Frame Broadcast)" else "No (Accessibility Dump/OCR Fallback)"}")
                appendLine("  • Device Actions: ${if (cap.hasToolCalling) "Enabled" else "Disabled (Chat-Only)"}")
                appendLine("  • Voice Streaming: ${if (cap.hasContinuousVoice) "Full Continuous" else "Push-to-Talk"}")
                appendLine("  • Scope: ${cap.description}")
            }
        }
    }
}
