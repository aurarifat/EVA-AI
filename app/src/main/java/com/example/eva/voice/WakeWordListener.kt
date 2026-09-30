package com.example.eva.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Haptic and auditory feedback utilities for wake word triggers.
 */
object WakeWordFeedback {
    private const val TAG = "WakeWordFeedback"

    fun triggerHaptic(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(75, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib?.vibrate(VibrationEffect.createOneShot(75, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib?.vibrate(75)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic trigger failed: ${e.message}")
        }
    }

    fun playChime(context: Context) {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    toneGen.release()
                } catch (_: Exception) {}
            }, 300)
        } catch (e: Exception) {
            Log.w(TAG, "Auditory chime failed: ${e.message}")
        }
    }
}

/**
 * Lightweight, privacy-preserving continuous wake word listener for "Hi EVA".
 * Runs short-cycle speech recognition loops without buffering or storing audio.
 * Automatically pauses during active phone calls or when the mic is busy.
 */
class WakeWordListener(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {

    companion object {
        private const val TAG = "WakeWordListener"

        /**
         * Fuzzy matches common variations and mis-transcriptions of "Hi EVA".
         * Case-insensitive, strips punctuation, and handles near-matches like "hey eva", "hi eeva", etc.
         */
        fun matchesWakeWord(rawTranscript: String): Boolean {
            val clean = rawTranscript.lowercase()
                .replace(Regex("""[^\p{L}\p{Nd}\s]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

            if (clean.isBlank()) return false

            // Standard variations and common mis-transcriptions
            val phraseTargets = listOf(
                "hi eva", "hey eva", "hello eva", "ok eva", "okay eva",
                "hi eeva", "hey eeva", "hello eeva", "ok eeva", "okay eeva",
                "hi ava", "hey ava", "hi iva", "hey iva",
                "hi ever", "hey ever", "hi eve", "hey eve",
                "high eva", "high eeva", "hi eva ai", "hey eva ai",
                "hi either", "hi ether", "hi ai eva"
            )

            for (target in phraseTargets) {
                if (clean == target || clean.startsWith("$target ") || clean.endsWith(" $target") || clean.contains(" $target ")) {
                    return true
                }
            }

            // Standalone wake word when spoken clearly
            val tokens = clean.split(" ")
            if (tokens.size == 1 && (tokens[0] == "eva" || tokens[0] == "eeva")) {
                return true
            }

            // Flexible regex: (hi|hey|hello|ok|okay|high|yo) followed immediately by eva/eeva/ava
            val regex = Regex("""\b(hi|hey|hello|ok|okay|high|yo)\s+(eva|eeva|ava|ever|iva)\b""", RegexOption.IGNORE_CASE)
            return regex.containsMatchIn(clean)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var speechRecognizer: SpeechRecognizer? = null
    private var localDetector: LocalWakeWordDetector? = null
    private var isRunning = false
    private var isPaused = false

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    fun updateSensitivity(sensitivity: Float) {
        localDetector?.updateSensitivity(sensitivity)
    }

    fun startListening() {
        mainHandler.post {
            if (isRunning) return@post
            Log.d(TAG, "Starting wake word listening loop with local acoustic model for 'Hi EVA'")
            isRunning = true
            isPaused = false
            startLocalDetector()
            startRecognitionCycle()
        }
    }

    fun stopListening() {
        mainHandler.post {
            Log.d(TAG, "Stopping wake word listening loop")
            isRunning = false
            isPaused = false
            localDetector?.stop()
            localDetector = null
            cleanupRecognizer()
            _isListening.value = false
        }
    }

    fun pauseListening() {
        mainHandler.post {
            if (!isRunning || isPaused) return@post
            Log.d(TAG, "Pausing wake word listening loop")
            isPaused = true
            localDetector?.pause()
            cleanupRecognizer()
            _isListening.value = false
        }
    }

    fun resumeListening() {
        mainHandler.post {
            if (!isRunning || !isPaused) return@post
            Log.d(TAG, "Resuming wake word listening loop")
            isPaused = false
            localDetector?.resume()
            startRecognitionCycle()
        }
    }

    fun destroy() {
        mainHandler.post {
            isRunning = false
            isPaused = false
            localDetector?.destroy()
            localDetector = null
            cleanupRecognizer()
            _isListening.value = false
        }
    }

    private fun startLocalDetector() {
        try {
            if (localDetector == null) {
                localDetector = LocalWakeWordDetector(
                    context = context,
                    sensitivity = 0.6f,
                    onWakeWordDetected = { keyword, confidence ->
                        Log.i(TAG, "Local acoustic model detected wake word: $keyword (conf: $confidence)")
                        mainHandler.post {
                            if (isRunning && !isPaused) {
                                cleanupRecognizer()
                                _isListening.value = false
                                onWakeWordDetected()
                            }
                        }
                    }
                )
            }
            localDetector?.start()
        } catch (e: Exception) {
            Log.w(TAG, "Could not start local wake word detector: ${e.message}")
        }
    }

    /**
     * Checks if a phone call is active or audio mode is in communication.
     */
    private fun isPhoneCallOrMicInUse(): Boolean {
        val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        return mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_RINGTONE
    }

    private fun startRecognitionCycle() {
        if (!isRunning || isPaused) return

        // Privacy & battery guard: pause if in phone call
        if (isPhoneCallOrMicInUse()) {
            Log.d(TAG, "Microphone is in call/communication mode. Deferring wake word cycle.")
            mainHandler.postDelayed({ startRecognitionCycle() }, 2500L)
            return
        }

        try {
            cleanupRecognizer()

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.w(TAG, "SpeechRecognizer not available on this device")
                _isListening.value = false
                return
            }

            val recognizer = createRecognizerInstance() ?: run {
                Log.w(TAG, "Failed to instantiate SpeechRecognizer")
                _isListening.value = false
                return
            }
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                }

                override fun onBeginningOfSpeech() {
                    _isListening.value = true
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {
                    // Privacy requirement: audio buffer is never stored or recorded
                }

                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    cleanupRecognizer()

                    if (!isRunning || isPaused) return

                    // Backoff delay depending on error type
                    val retryDelay = when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        SpeechRecognizer.ERROR_AUDIO -> 1800L
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 2500L
                        else -> 350L
                    }

                    mainHandler.postDelayed({ startRecognitionCycle() }, retryDelay)
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val matchedText = matches?.firstOrNull { matchesWakeWord(it) }

                    cleanupRecognizer()

                    if (matchedText != null) {
                        Log.d(TAG, "Wake word matched in results: '$matchedText'")
                        onWakeWordDetected()
                    } else if (isRunning && !isPaused) {
                        // Quick turnaround to next short listening cycle
                        mainHandler.postDelayed({ startRecognitionCycle() }, 200L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val matchedText = matches?.firstOrNull { matchesWakeWord(it) }

                    if (matchedText != null) {
                        Log.d(TAG, "Wake word matched in partial results: '$matchedText'")
                        cleanupRecognizer()
                        _isListening.value = false
                        onWakeWordDetected()
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Short detection cycle for responsiveness and battery conservation
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            }

            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting wake word cycle: ${e.message}", e)
            _isListening.value = false
            cleanupRecognizer()
            if (isRunning && !isPaused) {
                mainHandler.postDelayed({ startRecognitionCycle() }, 2000L)
            }
        }
    }

    private fun createRecognizerInstance(): SpeechRecognizer? {
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

        return try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            Log.w(TAG, "SpeechRecognizer creation failed: ${e.message}")
            null
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
}
