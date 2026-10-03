package com.example.eva.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local Wake-Word Detection Engine.
 * Streams raw microphone PCM audio into [LocalWakeWordModel] completely offline without any network traffic.
 * Features acoustic self-suppression (muting during assistant speech), low power consumption, and zero cloud latency.
 */
class LocalWakeWordDetector(
    private val context: Context,
    var sensitivity: Float = 0.6f,
    private val onWakeWordDetected: (keyword: String, confidence: Float) -> Unit
) {
    companion object {
        private const val TAG = "LocalWakeWordDetector"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val model = LocalWakeWordModel(sensitivity)

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    private var muteRunnable: Runnable? = null

    private var recordingThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    fun updateSensitivity(newSensitivity: Float) {
        this.sensitivity = newSensitivity.coerceIn(0.2f, 0.85f)
        model.sensitivity = this.sensitivity
    }

    /**
     * Temporarily mutes wake-word detection for [durationMs] (e.g. while EVA is speaking or chiming).
     */
    fun mute(durationMs: Long = 3000L) {
        isMuted.set(true)
        model.reset()
        muteRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            isMuted.set(false)
            model.reset()
        }
        muteRunnable = runnable
        mainHandler.postDelayed(runnable, durationMs)
    }

    fun unmute() {
        muteRunnable?.let { mainHandler.removeCallbacks(it) }
        isMuted.set(false)
        model.reset()
    }

    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.d(TAG, "LocalWakeWordDetector already running")
            return
        }
        isPaused.set(false)
        isMuted.set(false)
        model.reset()
        startAudioCaptureThread()
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }
        isPaused.set(false)
        unmute()
        stopAudioCaptureThread()
        _isListening.value = false
        _rmsLevel.value = 0f
    }

    fun pause() {
        if (!isRunning.get() || isPaused.getAndSet(true)) return
        Log.d(TAG, "LocalWakeWordDetector paused")
        stopAudioCaptureThread()
        _isListening.value = false
        _rmsLevel.value = 0f
    }

    fun resume() {
        if (!isRunning.get() || !isPaused.getAndSet(false)) return
        Log.d(TAG, "LocalWakeWordDetector resumed")
        model.reset()
        startAudioCaptureThread()
    }

    fun destroy() {
        stop()
    }

    private fun isPhoneCallOrMicBusy(): Boolean {
        val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        return mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_RINGTONE
    }

    private fun startAudioCaptureThread() {
        stopAudioCaptureThread()

        if (isPhoneCallOrMicBusy()) {
            Log.d(TAG, "Phone call or mic communication in progress, deferring audio capture")
            mainHandler.postDelayed({
                if (isRunning.get() && !isPaused.get()) {
                    startAudioCaptureThread()
                }
            }, 2500L)
            return
        }

        // Check permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission missing; cannot start local wake word detector")
            _isListening.value = false
            return
        }

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufSize <= 0) {
            Log.e(TAG, "Invalid AudioRecord minBufferSize: $minBufSize")
            return
        }
        val bufferSize = (minBufSize * 2).coerceAtLeast(LocalWakeWordModel.FRAME_SIZE * 4)

        try {
            val audioSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            } else {
                MediaRecorder.AudioSource.MIC
            }

            val record = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord failed to initialize (state: ${record.state})")
                record.release()
                return
            }

            audioRecord = record
            record.startRecording()
            _isListening.value = true

            recordingThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

                val hopSize = LocalWakeWordModel.HOP_SIZE
                val frameSize = LocalWakeWordModel.FRAME_SIZE
                val slidingWindow = ShortArray(frameSize)
                val readBuffer = ShortArray(hopSize)
                var lastUiRmsTime = 0L

                while (isRunning.get() && !isPaused.get()) {
                    val readSamples = record.read(readBuffer, 0, readBuffer.size)
                    if (readSamples <= 0) {
                        Thread.sleep(16)
                        continue
                    }

                    // Shift sliding window by hopSize and append newly read samples
                    System.arraycopy(slidingWindow, hopSize, slidingWindow, 0, frameSize - hopSize)
                    System.arraycopy(readBuffer, 0, slidingWindow, frameSize - hopSize, hopSize)

                    // If muted (EVA speaking, chiming, etc.), skip detection to avoid echo loops
                    if (isMuted.get()) {
                        continue
                    }

                    // Extract acoustic features
                    val features = model.extractFeatures(slidingWindow)

                    // Throttle UI RMS updates to ~20Hz
                    val now = System.currentTimeMillis()
                    if (now - lastUiRmsTime > 50L) {
                        _rmsLevel.value = (features.rms * 8f).coerceIn(0f, 1f)
                        lastUiRmsTime = now
                    }

                    // Feed frame to local keyword spotting state machine
                    val (isDetected, confidence) = model.processFrame(features)
                    if (isDetected) {
                        Log.i(TAG, ">>> Local Model Wake Word Fired! Confidence: $confidence <<<")
                        // Mute temporarily to avoid self-triggering on chime/response
                        mute(3200L)
                        mainHandler.post {
                            onWakeWordDetected("Hey EVA", confidence)
                        }
                    }
                }

                try {
                    record.stop()
                    record.release()
                } catch (_: Exception) {}
                _isListening.value = false
                _rmsLevel.value = 0f
            }, "EvaLocalWakeWordThread").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord capture thread: ${e.message}", e)
            _isListening.value = false
        }
    }

    private fun stopAudioCaptureThread() {
        val thread = recordingThread
        recordingThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        thread?.interrupt()
    }
}
