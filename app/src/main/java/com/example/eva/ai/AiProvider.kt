package com.example.eva.ai

import com.example.eva.data.prefs.AiProviderType

data class ChatMessage(
    val role: String, // "system", "user", "assistant"
    val content: String
)

data class AiResponse(
    val content: String,
    val toolCall: AiToolCall? = null,
    val providerUsed: AiProviderType,
    val latencyMs: Long = 0L,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null
)

data class AiToolCall(
    val toolName: String,
    val action: String,
    val parameters: Map<String, String> = emptyMap()
)

data class ProviderTestResult(
    val isSuccess: Boolean,
    val message: String,
    val latencyMs: Long = 0L,
    val httpCode: Int? = null
)

interface AiProvider {
    val providerType: AiProviderType
    suspend fun generateResponse(
        messages: List<ChatMessage>,
        toolsPrompt: String? = null
    ): AiResponse

    suspend fun testConnection(): ProviderTestResult
}
