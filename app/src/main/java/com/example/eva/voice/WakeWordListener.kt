package com.example.eva.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Haptic and auditory feedback utilities for wake word triggers.
 * Provides a gentle, soft, elegant synthesized harmonic wake chime instead of harsh beeps.
 */
object WakeWordFeedback {
    private const val TAG = "WakeWordFeedback"

    fun triggerHaptic(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib?.vibrate(45)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic trigger failed: ${e.message}")
        }
    }

    /**
     * Plays a pleasant, gentle ascending two-tone wake chime (D5: 587Hz -> A5: 880Hz)
     * rendered smoothly into PCM with exponential decay. Soft, modern, and pleasant.
     */
    fun playChime(context: Context) {
        Thread {
            try {
                val sampleRate = 22050
                val durationMs = 230
                val numSamples = (sampleRate * durationMs) / 1000
                val buffer = ShortArray(numSamples)

                val f1 = 587.33 // D5
                val f2 = 880.00 // A5
                val overlapStart = (sampleRate * 0.065).toInt()

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    var sampleVal = 0.0

                    // First note (D5) with cosine attack and quick decay
                    if (i < overlapStart + (sampleRate * 0.045)) {
                        val env1 = if (t < 0.012) {
                            (1.0 - cos(Math.PI * t / 0.012)) / 2.0
                        } else {
                            exp(-38.0 * (t - 0.012))
                        }
                        val wave1 = sin(2.0 * Math.PI * f1 * t) +
                                0.12 * sin(4.0 * Math.PI * f1 * t)
                        sampleVal += wave1 * env1
                    }

                    // Second note (A5) with sweet bell-like decay
                    if (i >= overlapStart) {
                        val t2 = (i - overlapStart).toDouble() / sampleRate
                        val env2 = if (t2 < 0.015) {
                            (1.0 - cos(Math.PI * t2 / 0.015)) / 2.0
                        } else {
                            exp(-18.0 * (t2 - 0.015))
                        }
                        val wave2 = sin(2.0 * Math.PI * f2 * t2) +
                                0.10 * sin(4.0 * Math.PI * f2 * t2)
                        sampleVal += wave2 * env2
                    }

                    // Scale to comfortable ~26% volume (no harsh clipping or loud beeps)
                    val pcm = (sampleVal * 8500.0).toInt().coerceIn(-32767, 32767).toShort()
                    buffer[i] = pcm
                }

                val audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(buffer.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        buffer.size * 2,
                        AudioTrack.MODE_STATIC
                    )
                }

                audioTrack.write(buffer, 0, buffer.size)
                audioTrack.play()
                Thread.sleep(durationMs.toLong() + 40L)
                try {
                    audioTrack.stop()
                    audioTrack.release()
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack chime failed, falling back: ${e.message}")
                try {
                    val fallback = ToneGenerator(AudioManager.STREAM_MUSIC, 25)
                    fallback.startTone(ToneGenerator.TONE_PROP_PROMPT, 60)
                    Handler(Looper.getMainLooper()).postDelayed({
                        try { fallback.release() } catch (_: Exception) {}
                    }, 180)
                } catch (_: Exception) {}
            }
        }.apply {
            name = "EvaChimeThread"
            isDaemon = true
            start()
        }
    }
}

/**
 * Lightweight, privacy-preserving continuous wake word listener for "Hey EVA" and "Hi EVA".
 * Powered 100% by the local on-device [LocalWakeWordDetector] and [LocalWakeWordModel].
 *
 * NOTE: Never uses Android's SpeechRecognizer in a background loop, which eliminates OS beeps,
 * audio focus conflicts, and false trigger loops.
 */
class WakeWordListener(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    companion object {
        private const val TAG = "WakeWordListener"

        /**
         * Fallback text matcher used if needed by text/transcript inputs.
         */
        fun matchesWakeWord(rawTranscript: String): Boolean {
            val clean = rawTranscript.lowercase()
                .replace(Regex("""[^\p{L}\p{Nd}\s]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

            if (clean.isBlank()) return false

            val phraseTargets = listOf(
                "hey eva", "hi eva", "hello eva", "ok eva", "okay eva",
                "hey eeva", "hi eeva", "hello eeva", "ok eeva",
                "hey ava", "hi ava", "hey ever", "hi ever"
            )

            for (target in phraseTargets) {
                if (clean == target || clean.startsWith("$target ") || clean.endsWith(" $target") || clean.contains(" $target ")) {
                    return true
                }
            }

            val regex = Regex("""\b(hey|hi|hello|ok|okay)\s+(eva|eeva|ava|ever)\b""", RegexOption.IGNORE_CASE)
            return regex.containsMatchIn(clean)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var localDetector: LocalWakeWordDetector? = null
    private var currentSensitivity = 0.6f

    private var isRunning = false
    private var isPaused = false

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    fun updateSensitivity(sensitivity: Float) {
        this.currentSensitivity = sensitivity
        localDetector?.updateSensitivity(sensitivity)
    }

    fun mute(durationMs: Long = 3000L) {
        localDetector?.mute(durationMs)
    }

    fun unmute() {
        localDetector?.unmute()
    }

    fun startListening() {
        mainHandler.post {
            if (isRunning) return@post
            Log.d(TAG, "Starting wake word listening loop with local acoustic model for 'Hey EVA'")
            isRunning = true
            isPaused = false
            startLocalDetector()
            _isListening.value = true
        }
    }

    fun stopListening() {
        mainHandler.post {
            Log.d(TAG, "Stopping wake word listening loop")
            isRunning = false
            isPaused = false
            localDetector?.stop()
            localDetector = null
            _isListening.value = false
        }
    }

    fun pauseListening() {
        mainHandler.post {
            if (!isRunning || isPaused) return@post
            Log.d(TAG, "Pausing wake word listening loop")
            isPaused = true
            localDetector?.pause()
            _isListening.value = false
        }
    }

    fun resumeListening() {
        mainHandler.post {
            if (!isRunning || !isPaused) return@post
            Log.d(TAG, "Resuming wake word listening loop")
            isPaused = false
            localDetector?.resume()
            _isListening.value = true
        }
    }

    fun destroy() {
        mainHandler.post {
            isRunning = false
            isPaused = false
            localDetector?.destroy()
            localDetector = null
            _isListening.value = false
        }
    }

    private fun startLocalDetector() {
        try {
            if (localDetector == null) {
                localDetector = LocalWakeWordDetector(
                    context = context,
                    sensitivity = currentSensitivity,
                    onWakeWordDetected = { keyword, confidence ->
                        Log.i(TAG, "Local acoustic model detected wake word: $keyword (conf: $confidence)")
                        mainHandler.post {
                            if (isRunning && !isPaused) {
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
}
