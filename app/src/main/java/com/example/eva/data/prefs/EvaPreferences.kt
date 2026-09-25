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
    val voiceLanguage: String = "en-US", // "en-US", "bn-BD", etc.
    val autoSpeak: Boolean = true,
    val isSilentMode: Boolean = false,
    val silentUntilMillis: Long = 0L,
    val isSleepMode: Boolean = false,
    val firstRunCompleted: Boolean = false
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
            firstRunCompleted = prefs[Keys.FIRST_RUN_COMPLETED] ?: false
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
}
