package com.example.eva.ai

import android.content.Context
import com.example.eva.data.prefs.AiProviderType
import com.example.eva.data.prefs.EvaPreferences
import com.example.eva.data.prefs.EvaSettings
import com.example.eva.data.prefs.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class AiRepository(
    private val context: Context,
    private val preferences: EvaPreferences,
    private val keyStore: SecureKeyStore
) {

    private var cachedSettings: EvaSettings? = null

    suspend fun getSettings(): EvaSettings {
        val s = preferences.settingsFlow.first()
        cachedSettings = s
        return s
    }

    fun getProvider(type: AiProviderType, settings: EvaSettings): AiProvider {
        return when (type) {
            AiProviderType.OMNI_ROUTE -> {
                OpenAiCompatibleProvider(
                    providerType = AiProviderType.OMNI_ROUTE,
                    getBaseUrl = { settings.omniRouteUrl },
                    getApiKey = { keyStore.getKey(AiProviderType.OMNI_ROUTE) },
                    getModel = { settings.omniRouteModel }
                )
            }
            AiProviderType.OPEN_ROUTER -> {
                OpenAiCompatibleProvider(
                    providerType = AiProviderType.OPEN_ROUTER,
                    getBaseUrl = { settings.openRouterUrl },
                    getApiKey = { keyStore.getKey(AiProviderType.OPEN_ROUTER) },
                    getModel = { settings.openRouterModel },
                    extraHeaders = mapOf(
                        "HTTP-Referer" to "https://eva.assistant.local",
                        "X-Title" to "EVA Virtual Assistant"
                    )
                )
            }
            AiProviderType.GEMINI -> {
                GeminiProvider(
                    getApiKey = { keyStore.getKey(AiProviderType.GEMINI) },
                    getModel = { settings.geminiModel }
                )
            }
            AiProviderType.CUSTOM_OPENAI -> {
                OpenAiCompatibleProvider(
                    providerType = AiProviderType.CUSTOM_OPENAI,
                    getBaseUrl = { settings.customOpenAiUrl },
                    getApiKey = { keyStore.getKey(AiProviderType.CUSTOM_OPENAI) },
                    getModel = { settings.customOpenAiModel }
                )
            }
        }
    }

    suspend fun executeAiRequest(
        messages: List<ChatMessage>,
        toolsPrompt: String? = null
    ): AiResponse = withContext(Dispatchers.IO) {
        val settings = getSettings()
        val primaryProvider = getProvider(settings.activeProvider, settings)

        val primaryResponse = primaryProvider.generateResponse(messages, toolsPrompt)
        if (primaryResponse.isSuccess) {
            return@withContext primaryResponse
        }

        // If primary failed and fallback is enabled, try fallback
        if (settings.fallbackEnabled && settings.fallbackProvider != settings.activeProvider) {
            val fallbackProvider = getProvider(settings.fallbackProvider, settings)
            val fallbackResponse = fallbackProvider.generateResponse(messages, toolsPrompt)
            if (fallbackResponse.isSuccess) {
                return@withContext fallbackResponse.copy(
                    content = "[Using ${settings.fallbackProvider.displayName} fallback]\n" + fallbackResponse.content
                )
            }
        }

        return@withContext primaryResponse
    }

    suspend fun testProvider(type: AiProviderType): ProviderTestResult = withContext(Dispatchers.IO) {
        val settings = getSettings()
        val provider = getProvider(type, settings)
        provider.testConnection()
    }

    suspend fun switchProvider(type: AiProviderType) {
        preferences.setActiveProvider(type)
    }
}
