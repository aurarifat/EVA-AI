package com.example.eva.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "eva_settings")

enum class AiProviderType(val displayName: String) {
    OMNI_ROUTE("OmniRoute"),
    OPEN_ROUTER("OpenRouter"),
    GEMINI("Google Gemini"),
    CUSTOM_OPENAI("Custom OpenAI")
}

data class EvaSettings(
    val activeProvider: AiProviderType = AiProviderType.OMNI_ROUTE,
    val omniRouteUrl: String = "http://10.0.2.2:20128/v1",
    val omniRouteModel: String = "eva-v1",
    val openRouterUrl: String = "https://openrouter.ai/api/v1",
    val openRouterModel: String = "google/gemini-2.5-flash",
    val geminiModel: String = "gemini-2.5-flash",
    val customOpenAiUrl: String = "https://api.openai.com/v1",
    val customOpenAiModel: String = "gpt-4o-mini",
    val fallbackEnabled: Boolean = true,
    val fallbackProvider: AiProviderType = AiProviderType.OPEN_ROUTER,
    val voiceSpeed: Float = 1.0f,
    val voicePitch: Float = 1.05f,
    val voiceLanguage: String = "en-US",
    val autoSpeak: Boolean = true,
    val isSilentMode: Boolean = false,
    val silentUntilMillis: Long = 0L,
    val isSleepMode: Boolean = false,
    val firstRunCompleted: Boolean = false,

    // Phase 1: Coordinate & Debug Overlay
    val debugTapCrosshair: Boolean = false,

    // Phase 4: Core AI Configuration
    val isAgentMode: Boolean = true, // Chat mode (false) vs Agent mode (true)
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val maxSteps: Int = 10,
    val disableMaxSteps: Boolean = false,
    val useScreenCompression: Boolean = true,
    val sendSystemPrompt: Boolean = true,

    // Phase 7: Telegram Integration
    val telegramEnabled: Boolean = false,
    val telegramPairedChatId: Long? = null,
    val telegramPairedUsername: String? = null,

    // Wake Word ("Hi EVA")
    val wakeWordEnabled: Boolean = false,
    val wakeWordSensitivity: Float = 0.6f,
    val wakeWordChimeEnabled: Boolean = true,
    val wakeWordHandsFreeSpeech: Boolean = true
)

class EvaPreferences(private val context: Context) {

    private object Keys {
        val ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
        val OMNI_ROUTE_URL = stringPreferencesKey("omni_route_url")
        val OMNI_ROUTE_MODEL = stringPreferencesKey("omni_route_model")
        val OPEN_ROUTER_URL = stringPreferencesKey("open_router_url")
        val OPEN_ROUTER_MODEL = stringPreferencesKey("open_router_model")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val CUSTOM_OPENAI_URL = stringPreferencesKey("custom_openai_url")
        val CUSTOM_OPENAI_MODEL = stringPreferencesKey("custom_openai_model")
        val FALLBACK_ENABLED = booleanPreferencesKey("fallback_enabled")
        val FALLBACK_PROVIDER = stringPreferencesKey("fallback_provider")
        val VOICE_SPEED = floatPreferencesKey("voice_speed")
        val VOICE_PITCH = floatPreferencesKey("voice_pitch")
        val VOICE_LANGUAGE = stringPreferencesKey("voice_language")
        val AUTO_SPEAK = booleanPreferencesKey("auto_speak")
        val IS_SILENT_MODE = booleanPreferencesKey("is_silent_mode")
        val SILENT_UNTIL = longPreferencesKey("silent_until")
        val IS_SLEEP_MODE = booleanPreferencesKey("is_sleep_mode")
        val FIRST_RUN_COMPLETED = booleanPreferencesKey("first_run_completed")

        // New settings
        val DEBUG_TAP_CROSSHAIR = booleanPreferencesKey("debug_tap_crosshair")
        val IS_AGENT_MODE = booleanPreferencesKey("is_agent_mode")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val MAX_STEPS = intPreferencesKey("max_steps")
        val DISABLE_MAX_STEPS = booleanPreferencesKey("disable_max_steps")
        val USE_SCREEN_COMPRESSION = booleanPreferencesKey("use_screen_compression")
        val SEND_SYSTEM_PROMPT = booleanPreferencesKey("send_system_prompt")
        val TELEGRAM_ENABLED = booleanPreferencesKey("telegram_enabled")
        val TELEGRAM_PAIRED_CHAT_ID = longPreferencesKey("telegram_paired_chat_id")
        val TELEGRAM_PAIRED_USERNAME = stringPreferencesKey("telegram_paired_username")
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val WAKE_WORD_SENSITIVITY = floatPreferencesKey("wake_word_sensitivity")
        val WAKE_WORD_CHIME_ENABLED = booleanPreferencesKey("wake_word_chime_enabled")
        val WAKE_WORD_HANDS_FREE_SPEECH = booleanPreferencesKey("wake_word_hands_free_speech")
    }

    val settingsFlow: Flow<EvaSettings> = context.dataStore.data.map { prefs ->
        val providerStr = prefs[Keys.ACTIVE_PROVIDER] ?: AiProviderType.OMNI_ROUTE.name
        val fallbackStr = prefs[Keys.FALLBACK_PROVIDER] ?: AiProviderType.OPEN_ROUTER.name
        val activeProvider = runCatching { AiProviderType.valueOf(providerStr) }.getOrDefault(AiProviderType.OMNI_ROUTE)
        val fallbackProvider = runCatching { AiProviderType.valueOf(fallbackStr) }.getOrDefault(AiProviderType.OPEN_ROUTER)

        EvaSettings(
            activeProvider = activeProvider,
            omniRouteUrl = prefs[Keys.OMNI_ROUTE_URL] ?: "http://10.0.2.2:20128/v1",
            omniRouteModel = prefs[Keys.OMNI_ROUTE_MODEL] ?: "eva-v1",
            openRouterUrl = prefs[Keys.OPEN_ROUTER_URL] ?: "https://openrouter.ai/api/v1",
            openRouterModel = prefs[Keys.OPEN_ROUTER_MODEL] ?: "google/gemini-2.5-flash",
            geminiModel = prefs[Keys.GEMINI_MODEL] ?: "gemini-2.5-flash",
            customOpenAiUrl = prefs[Keys.CUSTOM_OPENAI_URL] ?: "https://api.openai.com/v1",
            customOpenAiModel = prefs[Keys.CUSTOM_OPENAI_MODEL] ?: "gpt-4o-mini",
            fallbackEnabled = prefs[Keys.FALLBACK_ENABLED] ?: true,
            fallbackProvider = fallbackProvider,
            voiceSpeed = prefs[Keys.VOICE_SPEED] ?: 1.0f,
            voicePitch = prefs[Keys.VOICE_PITCH] ?: 1.05f,
            voiceLanguage = prefs[Keys.VOICE_LANGUAGE] ?: "en-US",
            autoSpeak = prefs[Keys.AUTO_SPEAK] ?: true,
            isSilentMode = prefs[Keys.IS_SILENT_MODE] ?: false,
            silentUntilMillis = prefs[Keys.SILENT_UNTIL] ?: 0L,
            isSleepMode = prefs[Keys.IS_SLEEP_MODE] ?: false,
            firstRunCompleted = prefs[Keys.FIRST_RUN_COMPLETED] ?: false,

            debugTapCrosshair = prefs[Keys.DEBUG_TAP_CROSSHAIR] ?: false,
            isAgentMode = prefs[Keys.IS_AGENT_MODE] ?: true,
            temperature = prefs[Keys.TEMPERATURE] ?: 0.7f,
            maxTokens = prefs[Keys.MAX_TOKENS] ?: 2048,
            maxSteps = prefs[Keys.MAX_STEPS] ?: 10,
            disableMaxSteps = prefs[Keys.DISABLE_MAX_STEPS] ?: false,
            useScreenCompression = prefs[Keys.USE_SCREEN_COMPRESSION] ?: true,
            sendSystemPrompt = prefs[Keys.SEND_SYSTEM_PROMPT] ?: true,
            telegramEnabled = prefs[Keys.TELEGRAM_ENABLED] ?: false,
            telegramPairedChatId = prefs[Keys.TELEGRAM_PAIRED_CHAT_ID],
            telegramPairedUsername = prefs[Keys.TELEGRAM_PAIRED_USERNAME],
            wakeWordEnabled = prefs[Keys.WAKE_WORD_ENABLED] ?: false,
            wakeWordSensitivity = prefs[Keys.WAKE_WORD_SENSITIVITY] ?: 0.6f,
            wakeWordChimeEnabled = prefs[Keys.WAKE_WORD_CHIME_ENABLED] ?: true,
            wakeWordHandsFreeSpeech = prefs[Keys.WAKE_WORD_HANDS_FREE_SPEECH] ?: true
        )
    }

    suspend fun setActiveProvider(provider: AiProviderType) {
        context.dataStore.edit { it[Keys.ACTIVE_PROVIDER] = provider.name }
    }

    suspend fun updateOmniRoute(url: String, model: String) {
        context.dataStore.edit {
            it[Keys.OMNI_ROUTE_URL] = url
            it[Keys.OMNI_ROUTE_MODEL] = model
        }
    }

    suspend fun updateOpenRouter(url: String, model: String) {
        context.dataStore.edit {
            it[Keys.OPEN_ROUTER_URL] = url
            it[Keys.OPEN_ROUTER_MODEL] = model
        }
    }

    suspend fun updateCustomOpenAi(url: String, model: String) {
        context.dataStore.edit {
            it[Keys.CUSTOM_OPENAI_URL] = url
            it[Keys.CUSTOM_OPENAI_MODEL] = model
        }
    }

    suspend fun updateGemini(model: String) {
        context.dataStore.edit { it[Keys.GEMINI_MODEL] = model }
    }

    suspend fun updateVoiceSettings(speed: Float, pitch: Float, language: String, autoSpeak: Boolean) {
        context.dataStore.edit {
            it[Keys.VOICE_SPEED] = speed
            it[Keys.VOICE_PITCH] = pitch
            it[Keys.VOICE_LANGUAGE] = language
            it[Keys.AUTO_SPEAK] = autoSpeak
        }
    }

    suspend fun setSilentMode(enabled: Boolean, untilMillis: Long = 0L) {
        context.dataStore.edit {
            it[Keys.IS_SILENT_MODE] = enabled
            it[Keys.SILENT_UNTIL] = untilMillis
        }
    }

    suspend fun setSleepMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.IS_SLEEP_MODE] = enabled }
    }

    suspend fun setFirstRunCompleted(completed: Boolean) {
        context.dataStore.edit { it[Keys.FIRST_RUN_COMPLETED] = completed }
    }

    suspend fun setDebugTapCrosshair(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEBUG_TAP_CROSSHAIR] = enabled }
    }

    suspend fun setIsAgentMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.IS_AGENT_MODE] = enabled }
    }

    suspend fun updateModelParams(temperature: Float, maxTokens: Int, maxSteps: Int, disableMaxSteps: Boolean) {
        context.dataStore.edit {
            it[Keys.TEMPERATURE] = temperature
            it[Keys.MAX_TOKENS] = maxTokens
            it[Keys.MAX_STEPS] = maxSteps
            it[Keys.DISABLE_MAX_STEPS] = disableMaxSteps
        }
    }

    suspend fun setUseScreenCompression(enabled: Boolean) {
        context.dataStore.edit { it[Keys.USE_SCREEN_COMPRESSION] = enabled }
    }

    suspend fun setSendSystemPrompt(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SEND_SYSTEM_PROMPT] = enabled }
    }

    suspend fun setTelegramEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TELEGRAM_ENABLED] = enabled }
    }

    suspend fun setTelegramPairedChatId(chatId: Long?) {
        context.dataStore.edit {
            if (chatId == null) {
                it.remove(Keys.TELEGRAM_PAIRED_CHAT_ID)
            } else {
                it[Keys.TELEGRAM_PAIRED_CHAT_ID] = chatId
            }
        }
    }

    suspend fun setTelegramPairedUser(chatId: Long?, username: String?) {
        context.dataStore.edit {
            if (chatId == null) {
                it.remove(Keys.TELEGRAM_PAIRED_CHAT_ID)
                it.remove(Keys.TELEGRAM_PAIRED_USERNAME)
            } else {
                it[Keys.TELEGRAM_PAIRED_CHAT_ID] = chatId
                if (!username.isNullOrBlank()) {
                    it[Keys.TELEGRAM_PAIRED_USERNAME] = username
                } else {
                    it.remove(Keys.TELEGRAM_PAIRED_USERNAME)
                }
            }
        }
    }

    suspend fun clearTelegramPairing() {
        context.dataStore.edit {
            it.remove(Keys.TELEGRAM_PAIRED_CHAT_ID)
            it.remove(Keys.TELEGRAM_PAIRED_USERNAME)
        }
    }

    suspend fun setWakeWordEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WAKE_WORD_ENABLED] = enabled }
    }

    suspend fun setWakeWordSensitivity(sensitivity: Float) {
        context.dataStore.edit { it[Keys.WAKE_WORD_SENSITIVITY] = sensitivity.coerceIn(0.1f, 1.0f) }
    }

    suspend fun setWakeWordChimeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WAKE_WORD_CHIME_ENABLED] = enabled }
    }

    suspend fun setWakeWordHandsFreeSpeech(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WAKE_WORD_HANDS_FREE_SPEECH] = enabled }
    }
}
