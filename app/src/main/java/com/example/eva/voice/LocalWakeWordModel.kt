package com.example.eva.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Lightweight acoustic feature representations computed per 32ms audio frame (512 samples at 16kHz).
 */
data class AudioFrameFeatures(
    val rms: Float,
    val zcr: Float,
    val isVoiced: Boolean,
    val bandEnergies: FloatArray, // 8 frequency bands
    val highFrequencyRatio: Float,
    val midFrequencyRatio: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioFrameFeatures
        return rms == other.rms && zcr == other.zcr && bandEnergies.contentEquals(other.bandEnergies)
    }

    override fun hashCode(): Int {
        var result = rms.hashCode()
        result = 31 * result + zcr.hashCode()
        result = 31 * result + bandEnergies.contentHashCode()
        return result
    }
}

/**
 * Lightweight local acoustic keyword spotting model for "Hi EVA" / "Hey EVA".
 * Runs 100% on-device with zero network latency, zero cloud dependencies, and zero disk recording.
 *
 * Employs a multi-state temporal acoustic sequence matcher:
 * 1. Background/Silence baseline calibration
 * 2. Onset diphthong: "Hi" / "Hey" ([haɪ] / [heɪ])
 * 3. Diphthong resolution & vowel: "E" ([iː])
 * 4. Voiced labiodental fricative: "V" ([v])
 * 5. Open vowel terminal: "A" ([ə] / [ɑː])
 */
class LocalWakeWordModel(
    var sensitivity: Float = 0.6f // 0.2 (strict) to 0.9 (sensitive)
) {
    companion object {
        private const val TAG = "LocalWakeWordModel"
        const val SAMPLE_RATE = 16000
        const val FRAME_SIZE = 512 // 32ms
        const val HOP_SIZE = 256   // 16ms overlap

        // 8 Frequency band limits in 16kHz spectrum (Nyquist = 8000Hz, bin = 16000/512 = 31.25Hz)
        private val BAND_BINS = arrayOf(
            3..9,    // Band 0: ~93 - 281 Hz (fundamental pitch)
            10..19,  // Band 1: ~312 - 593 Hz (F1 lower harmonics)
            20..35,  // Band 2: ~625 - 1093 Hz (F1 / F2 vowel transition)
            36..55,  // Band 3: ~1125 - 1718 Hz (F2 vowel core)
            56..80,  // Band 4: ~1750 - 2500 Hz (F2/F3 upper resonance for [iː])
            81..115, // Band 5: ~2531 - 3593 Hz (F3 / sibilant transition)
            116..160,// Band 6: ~3625 - 5000 Hz (Fricative /v/ turbulent energy)
            161..230 // Band 7: ~5031 - 7187 Hz (High-frequency breath / fricative)
        )
    }

    // Circular window of recent frame features (~1.2 seconds of speech = 75 frames at 16ms hop)
    private val maxHistoryFrames = 75
    private val frameHistory = ArrayDeque<AudioFrameFeatures>(maxHistoryFrames)

    // State machine tracking temporal progression for "Hi EVA"
    private var currentState = 0 // 0=Idle, 1="Hi/Hey", 2="E", 3="V", 4="A"
    private var stateStartFrame = 0
    private var totalFramesProcessed = 0
    private var lastDetectionTimestamp = 0L

    fun reset() {
        frameHistory.clear()
        currentState = 0
        stateStartFrame = 0
        totalFramesProcessed = 0
    }

    /**
     * Extracts spectral & temporal features from a 512-sample PCM buffer.
     */
    fun extractFeatures(samples: ShortArray): AudioFrameFeatures {
        var sumSquares = 0.0
        var zeroCrossings = 0
        var prevSign = samples[0] >= 0

        for (i in samples.indices) {
            val s = samples[i].toInt()
            sumSquares += s * s
            val curSign = s >= 0
            if (curSign != prevSign) {
                zeroCrossings++
                prevSign = curSign
            }
        }

        val rms = sqrt(sumSquares / samples.size).toFloat() / 32768.0f
        val zcr = zeroCrossings.toFloat() / samples.size

        // Simplified 8-band energy filterbank using fast Goertzel/energy estimation
        val bandEnergies = FloatArray(BAND_BINS.size)
        var totalSpectralEnergy = 0.0001f

        for (b in BAND_BINS.indices) {
            val range = BAND_BINS[b]
            var bandSum = 0.0f
            // Fast harmonic energy approximation across band bins
            val step = if (range.count() > 8) 2 else 1
            for (bin in range step step) {
                val omega = (2.0 * Math.PI * bin) / samples.size
                var s1 = 0.0
                var s2 = 0.0
                for (n in samples.indices) {
                    val x = samples[n].toDouble()
                    val s0 = x + 2.0 * kotlin.math.cos(omega) * s1 - s2
                    s2 = s1
                    s1 = s0
                }
                val power = (s1 * s1 + s2 * s2 - 2.0 * kotlin.math.cos(omega) * s1 * s2).toFloat()
                bandSum += sqrt(power.coerceAtLeast(0f))
            }
            bandEnergies[b] = bandSum
            totalSpectralEnergy += bandSum
        }

        // Normalize band energies
        for (b in bandEnergies.indices) {
            bandEnergies[b] = (bandEnergies[b] / totalSpectralEnergy).coerceIn(0f, 1f)
        }

        val highFreqEnergy = bandEnergies[5] + bandEnergies[6] + bandEnergies[7]
        val midFreqEnergy = bandEnergies[2] + bandEnergies[3] + bandEnergies[4]
        val isVoiced = rms > 0.015f && zcr < 0.35f && (bandEnergies[0] + bandEnergies[1] + bandEnergies[2] > 0.30f)

        return AudioFrameFeatures(
            rms = rms,
            zcr = zcr,
            isVoiced = isVoiced,
            bandEnergies = bandEnergies,
            highFrequencyRatio = highFreqEnergy,
            midFrequencyRatio = midFreqEnergy
        )
    }

    /**
     * Processes a single audio frame and checks if "Hi EVA" acoustic pattern is detected.
     * Returns a pair of (isDetected: Boolean, confidence: Float).
     */
    fun processFrame(features: AudioFrameFeatures): Pair<Boolean, Float> {
        totalFramesProcessed++

        if (frameHistory.size >= maxHistoryFrames) {
            frameHistory.removeFirst()
        }
        frameHistory.addLast(features)

        // Ignore frames below minimum speech energy (silence / ambient hum)
        val silenceThreshold = 0.012f - (sensitivity * 0.005f)
        if (features.rms < silenceThreshold) {
            // If in active state, allow short silence (inter-syllable gap up to 12 frames ~ 190ms)
            if (currentState > 0 && (totalFramesProcessed - stateStartFrame) > 16) {
                currentState = 0
            }
            return Pair(false, 0f)
        }

        val now = System.currentTimeMillis()
        if (now - lastDetectionTimestamp < 1500L) {
            // Debounce / refractory period after recent detection
            return Pair(false, 0f)
        }

        // State Machine Pattern Progression for "Hi EVA"
        val bands = features.bandEnergies
        val e0_1 = bands[0] + bands[1]          // Low fundamental
        val e2_4 = bands[2] + bands[3] + bands[4]// Vowel formants
        val e5_6 = bands[5] + bands[6]          // Fricative /v/ / high resonance

        when (currentState) {
            0 -> { // Idle: Look for Onset of "Hi" or "Hey" ([haɪ] / [heɪ])
                // "Hi" has strong rise in mid-band formant energy + voicing or aspiration
                val isHiVowel = features.rms > (0.020f - sensitivity * 0.008f) &&
                        e2_4 > 0.35f &&
                        features.zcr in 0.04f..0.45f

                if (isHiVowel) {
                    currentState = 1
                    stateStartFrame = totalFramesProcessed
                }
            }

            1 -> { // In "Hi": Look for transition to "E" ([iː]) in "EVA"
                val framesInHi = totalFramesProcessed - stateStartFrame
                if (framesInHi in 4..28) {
                    // "E" sound has prominent high F2 resonance (band 4) and clean voicing (low ZCR)
                    val isEVowel = features.isVoiced && (bands[3] + bands[4] > 0.40f) && features.zcr < 0.28f
                    if (isEVowel) {
                        currentState = 2
                        stateStartFrame = totalFramesProcessed
                    }
                } else if (framesInHi > 32) {
                    currentState = 0 // Timed out waiting for "E"
                }
            }

            2 -> { // In "E": Look for transition to "V" ([v]) in "EVA"
                val framesInE = totalFramesProcessed - stateStartFrame
                if (framesInE in 3..24) {
                    // "V" sound has a drop in open vowel energy + characteristic fricative / high band presence
                    val isVFricative = (e5_6 > 0.26f || features.zcr > 0.22f) && features.rms > 0.015f
                    if (isVFricative) {
                        currentState = 3
                        stateStartFrame = totalFramesProcessed
                    }
                } else if (framesInE > 28) {
                    currentState = 0
                }
            }

            3 -> { // In "V": Look for resolution to "A" ([ə] / [ɑː])
                val framesInV = totalFramesProcessed - stateStartFrame
                if (framesInV in 2..20) {
                    // "A" sound restores low-mid open vowel energy (band 1 + 2 + 3) with low ZCR
                    val isAVowel = features.isVoiced && (e0_1 + bands[2] > 0.45f) && features.zcr < 0.25f
                    if (isAVowel) {
                        currentState = 4
                        stateStartFrame = totalFramesProcessed
                    }
                } else if (framesInV > 24) {
                    currentState = 0
                }
            }

            4 -> { // In "A": Confirm terminal closure of "Hi EVA"
                val framesInA = totalFramesProcessed - stateStartFrame
                if (framesInA in 2..18) {
                    // Full trajectory detected! Compute holistic sequence confidence score
                    val confidence = calculateTrajectoryConfidence()
                    val requiredConfidence = (0.75f - (sensitivity * 0.25f)).coerceIn(0.40f, 0.85f)

                    if (confidence >= requiredConfidence) {
                        lastDetectionTimestamp = now
                        reset()
                        Log.i(TAG, "Wake word 'Hi EVA' matched with local acoustic model (confidence: $confidence >= $requiredConfidence)")
                        return Pair(true, confidence)
                    }
                } else if (framesInA > 22) {
                    currentState = 0
                }
            }
        }

        return Pair(false, 0f)
    }

    /**
     * Holistic verification across the collected sliding window buffer to minimize false positives.
     */
    private fun calculateTrajectoryConfidence(): Float {
        if (frameHistory.size < 25) return 0.5f

        var maxRms = 0f
        var voicedCount = 0
        var vowelCoreCount = 0
        var fricativePresence = 0

        val recentFrames = frameHistory.takeLast(45)
        for (f in recentFrames) {
            if (f.rms > maxRms) maxRms = f.rms
            if (f.isVoiced) voicedCount++
            if (f.bandEnergies[3] + f.bandEnergies[4] > 0.35f) vowelCoreCount++
            if (f.highFrequencyRatio > 0.24f) fricativePresence++
        }

        val energyScore = (maxRms / 0.15f).coerceIn(0f, 1f) * 0.25f
        val voicedScore = (voicedCount.toFloat() / recentFrames.size).coerceIn(0f, 1f) * 0.30f
        val vowelScore = (vowelCoreCount.toFloat() / (recentFrames.size * 0.5f)).coerceIn(0f, 1f) * 0.25f
        val fricativeScore = (fricativePresence.toFloat() / (recentFrames.size * 0.3f)).coerceIn(0f, 1f) * 0.20f

        val totalScore = energyScore + voicedScore + vowelScore + fricativeScore
        return totalScore.coerceIn(0.1f, 1.0f)
    }
}
