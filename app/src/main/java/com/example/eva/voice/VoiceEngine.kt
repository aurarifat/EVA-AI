package com.example.eva.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class VoiceState {
    IDLE,
    LISTENING,
    THINKING,
    EXECUTING,
    SPEAKING,
    SLEEPING,
    ERROR
}

class EvaSpeechRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "EvaSpeechRecognizer"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _speechText = MutableStateFlow("")
    val speechText: StateFlow<String> = _speechText.asStateFlow()

    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    fun isAvailable(): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Exception) {
            false
        }
    }

    fun startListening(
        language: String = "en-US",
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        this.onResultCallback = onResult
        this.onErrorCallback = onError

        mainHandler.post {
            try {
                // Ensure any previous session is cleaned up
                cleanupRecognizer()

                val recognizer = createRecognizerInstance()
                if (recognizer == null) {
                    _voiceState.value = VoiceState.ERROR
                    onError("Speech recognition service is not available on this device. You can type commands in the Quick Dock.")
                    return@post
                }
                speechRecognizer = recognizer

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "SpeechRecognizer: onReadyForSpeech")
                        _voiceState.value = VoiceState.LISTENING
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "SpeechRecognizer: onBeginningOfSpeech")
                        _voiceState.value = VoiceState.LISTENING
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        // Normalize RMS dB (typical speech range -2dB to 10dB)
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        _rmsLevel.value = normalized
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "SpeechRecognizer: onEndOfSpeech")
                        _voiceState.value = VoiceState.THINKING
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "SpeechRecognizer error: $error")
                        val message = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording issue. Please check microphone."
                            SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client error. Tap mic to retry."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                            SpeechRecognizer.ERROR_NETWORK -> "Network issue during speech recognition."
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network timeout."
                            SpeechRecognizer.ERROR_NO_MATCH -> "Didn't hear you clearly. Tap mic or type below."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognizer was busy. Tap to retry."
                            SpeechRecognizer.ERROR_SERVER -> "Speech server error. Please retry."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Tap mic to speak."
                            else -> "Speech recognition error ($error)"
                        }
                        _voiceState.value = VoiceState.IDLE
                        _rmsLevel.value = 0f
                        cleanupRecognizer()
                        onErrorCallback?.invoke(message)
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull()?.trim() ?: ""
                        Log.d(TAG, "SpeechRecognizer onResults: '$spokenText'")
                        _voiceState.value = VoiceState.IDLE
                        _rmsLevel.value = 0f
                        _speechText.value = spokenText
                        cleanupRecognizer()
                        if (spokenText.isNotBlank()) {
                            onResultCallback?.invoke(spokenText)
                        } else {
                            onErrorCallback?.invoke("Didn't catch any words. Tap mic to speak again.")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull() ?: ""
                        if (partial.isNotBlank()) {
                            _speechText.value = partial
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                }

                _voiceState.value = VoiceState.LISTENING
                recognizer.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognition: ${e.message}", e)
                _voiceState.value = VoiceState.ERROR
                _rmsLevel.value = 0f
                cleanupRecognizer()
                onError("Could not start speech recognition: ${e.localizedMessage}")
            }
        }
    }

    private fun createRecognizerInstance(): SpeechRecognizer? {
        // Try on-device recognizer first on Android 13+ (API 33+) if available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    val onDevice = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    if (onDevice != null) return onDevice
                }
            } catch (e: Exception) {
                Log.w(TAG, "On-device speech recognizer init failed: ${e.message}")
            }
        }

        // Standard speech recognizer
        return try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            Log.w(TAG, "Standard SpeechRecognizer creation failed: ${e.message}")
            null
        }
    }

    fun stopListening() {
        mainHandler.post {
            cleanupRecognizer()
            _rmsLevel.value = 0f
            if (_voiceState.value == VoiceState.LISTENING) {
                _voiceState.value = VoiceState.IDLE
            }
        }
    }

    private fun cleanupRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    fun setState(state: VoiceState) {
        _voiceState.value = state
    }
}

class EvaTextToSpeech(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val utteranceCallbacks = ConcurrentHashMap<String, () -> Unit>()

    init {
        mainHandler.post {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isInitialized = true
                    tts?.language = Locale.US
                    tts?.setPitch(1.05f) // Warm, gentle slightly elevated pitch
                    tts?.setSpeechRate(1.0f)

                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                            if (utteranceId != null) {
                                utteranceCallbacks.remove(utteranceId)?.invoke()
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                            if (utteranceId != null) {
                                utteranceCallbacks.remove(utteranceId)?.invoke()
                            }
                        }
                    })
                }
            }
        }
    }

    fun configure(speed: Float, pitch: Float, languageTag: String) {
        mainHandler.post {
            if (!isInitialized) return@post
            tts?.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
            tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
            val locale = when {
                languageTag.startsWith("bn", ignoreCase = true) -> Locale("bn", "BD")
                else -> Locale.forLanguageTag(languageTag)
            }
            val res = tts?.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.US
            }
        }
    }

    companion object {
        fun sanitizeForSpeech(raw: String): String {
            if (raw.isBlank()) return ""
            return raw
                // Strip emoji unicode blocks, pictographs, decorative symbols (e.g. 💛, ✨, 🛠️, etc.)
                .replace(Regex("[\\p{So}\\p{Cn}\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u26FF\\u2700-\\u27BF]"), "")
                .replace("💛", "")
                .replace("✨", "")
                .replace("❤️", "")
                .replace("🛠️", "")
                // Strip markdown formatting symbols like **, ##, ```, and bullet points
                .replace(Regex("[*#`_~•\\[\\](){}]"), " ")
                // Collapse duplicate whitespace
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val cleanSpeech = sanitizeForSpeech(text)
        if (cleanSpeech.isBlank()) {
            onDone?.invoke()
            return
        }

        mainHandler.post {
            if (!isInitialized) {
                onDone?.invoke()
                return@post
            }
            val utteranceId = "eva_speech_${System.currentTimeMillis()}"
            if (onDone != null) {
                utteranceCallbacks[utteranceId] = onDone
            }
            tts?.speak(cleanSpeech, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    fun stop() {
        mainHandler.post {
            tts?.stop()
            _isSpeaking.value = false
            utteranceCallbacks.clear()
        }
    }

    fun shutdown() {
        mainHandler.post {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
            _isSpeaking.value = false
            utteranceCallbacks.clear()
        }
    }
}
