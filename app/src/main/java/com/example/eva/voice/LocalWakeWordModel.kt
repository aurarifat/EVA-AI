package com.example.eva.voice

import android.util.Log
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight acoustic feature representations computed per 32ms audio frame (512 samples at 16kHz).
 */
data class AudioFrameFeatures(
    val rms: Float,
    val zcr: Float,
    val isVoiced: Boolean,
    val bandEnergies: FloatArray, // 7 frequency bands
    val upperResonanceRatio: Float,
    val fricativeRatio: Float
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
 * High-performance, low-latency, 100% on-device wake-word detection model for "Hey EVA" and "Hi EVA".
 *
 * Designed specifically to prevent false triggers and annoying feedback loops:
 * 1. Adaptive ambient noise floor tracking (VAD + dynamic SNR gating).
 * 2. Multi-stage temporal sequence matcher enforcing the phonetic structure:
 *    [Hey / Hi] -> [Inter-word dip] -> [E- vowel [iː]] -> [V- fricative trough] -> [A- vowel] -> [Closure]
 * 3. Strict physical envelope geometry check (Peak 1 -> Peak 2 -> Trough -> Peak 3).
 * 4. Temporal duration constraint (400ms to 1150ms).
 * 5. Refractory suppression window after successful triggers.
 */
class LocalWakeWordModel(
    var sensitivity: Float = 0.6f // 0.25 (strictest) to 0.85 (most sensitive)
) {
    companion object {
        private const val TAG = "LocalWakeWordModel"
        const val SAMPLE_RATE = 16000
        const val FRAME_SIZE = 512 // 32ms at 16kHz
        const val HOP_SIZE = 256   // 16ms step (62.5 fps)

        // 7 Frequency band bins in 16kHz spectrum (Nyquist = 8000Hz, bin = 16000/512 = 31.25Hz)
        private val BAND_BINS = arrayOf(
            3..11,   // Band 0: ~93 - 344 Hz (Fundamental pitch F0 / Voicing band)
            12..24,  // Band 1: ~375 - 750 Hz (First formant F1 lower / mid)
            25..45,  // Band 2: ~781 - 1406 Hz (First formant upper / F2 transition)
            46..68,  // Band 3: ~1437 - 2125 Hz (Second formant F2 core for [eɪ] / [aɪ])
            69..100, // Band 4: ~2156 - 3125 Hz (Upper formant F2/F3 for [iː])
            101..140,// Band 5: ~3156 - 4375 Hz (Upper resonance / sibilance)
            141..224 // Band 6: ~4406 - 7000 Hz (Fricatives / [v] turbulence & high breath)
        )
    }

    // Circular window of recent frames (~1.3 seconds = 80 frames)
    private val maxHistoryFrames = 80
    private val frameHistory = ArrayDeque<AudioFrameFeatures>(maxHistoryFrames)

    // Adaptive ambient noise floor (RMS)
    private var ambientNoiseRms = 0.008f

    // Temporal State Machine:
    // 0 = Idle / Calibrating
    // 1 = In "Hey" / "Hi" (onset diphthong)
    // 2 = In "E" ([iː] vowel of EVA)
    // 3 = In "-V-" (voiced fricative constriction / trough)
    // 4 = In "-A" (open vowel termination)
    private var currentState = 0
    private var stateStartFrame = 0
    private var phraseStartFrame = 0
    private var totalFramesProcessed = 0
    private var lastDetectionTimestamp = 0L

    // Envelope tracking across the current phrase candidate
    private var peakHeyRms = 0f
    private var peakERms = 0f
    private var troughVRms = 1f
    private var peakARms = 0f

    fun reset() {
        frameHistory.clear()
        currentState = 0
        stateStartFrame = 0
        phraseStartFrame = 0
        peakHeyRms = 0f
        peakERms = 0f
        troughVRms = 1f
        peakARms = 0f
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

        val rms = (sqrt(sumSquares / samples.size) / 32768.0).toFloat()
        val zcr = zeroCrossings.toFloat() / samples.size

        // Multi-band energy estimation using optimized Goertzel harmonic filters
        val bandEnergies = FloatArray(BAND_BINS.size)
        var totalSpectralEnergy = 0.0001f

        for (b in BAND_BINS.indices) {
            val range = BAND_BINS[b]
            var bandSum = 0.0f
            val step = if (range.count() > 8) 2 else 1
            for (bin in range step step) {
                val omega = (2.0 * Math.PI * bin) / samples.size
                val cosine = cos(omega)
                var s1 = 0.0
                var s2 = 0.0
                for (n in samples.indices) {
                    val x = samples[n].toDouble()
                    val s0 = x + 2.0 * cosine * s1 - s2
                    s2 = s1
                    s1 = s0
                }
                val power = (s1 * s1 + s2 * s2 - 2.0 * cosine * s1 * s2).toFloat()
                bandSum += sqrt(power.coerceAtLeast(0f))
            }
            bandEnergies[b] = bandSum
            totalSpectralEnergy += bandSum
        }

        // Normalize spectral band energies to sum = 1.0
        for (b in bandEnergies.indices) {
            bandEnergies[b] = (bandEnergies[b] / totalSpectralEnergy).coerceIn(0f, 1f)
        }

        val upperResonance = bandEnergies[4] + bandEnergies[5]
        val fricativeEnergy = bandEnergies[5] + bandEnergies[6]
        val lowPitch = bandEnergies[0] + bandEnergies[1]

        // True voicing requires sufficient energy, low ZCR, and fundamental pitch presence
        val isVoiced = rms > 0.014f && zcr < 0.32f && lowPitch > 0.28f

        return AudioFrameFeatures(
            rms = rms,
            zcr = zcr,
            isVoiced = isVoiced,
            bandEnergies = bandEnergies,
            upperResonanceRatio = upperResonance,
            fricativeRatio = fricativeEnergy
        )
    }

    /**
     * Processes a single audio frame and returns (isDetected, confidence).
     */
    fun processFrame(features: AudioFrameFeatures): Pair<Boolean, Float> {
        totalFramesProcessed++

        if (frameHistory.size >= maxHistoryFrames) {
            frameHistory.removeFirst()
        }
        frameHistory.addLast(features)

        val now = System.currentTimeMillis()

        // 1. Refractory Cooldown: Prevent immediate re-triggering (3.0 seconds)
        if (now - lastDetectionTimestamp < 3000L) {
            if (currentState != 0) reset()
            return Pair(false, 0f)
        }

        // 2. Ambient Noise Floor Tracking (Exponential Moving Average during non-speech)
        if (!features.isVoiced && features.rms < 0.025f) {
            ambientNoiseRms = (0.990f * ambientNoiseRms + 0.010f * features.rms).coerceIn(0.003f, 0.035f)
        }

        val snr = features.rms / (ambientNoiseRms + 0.0001f)
        val minSpeechRms = (0.018f - (sensitivity * 0.006f)).coerceAtLeast(0.012f)

        val bands = features.bandEnergies
        val b0 = bands[0] // Pitch
        val b1 = bands[1] // F1 low
        val b2 = bands[2] // Mid
        val b3 = bands[3] // F2 core
        val b4 = bands[4] // F2/F3 high [iː]
        val b5 = bands[5] // Upper resonance
        val b6 = bands[6] // High fricative

        // 3. Temporal State Machine for "Hey EVA" / "Hi EVA"
        when (currentState) {
            0 -> {
                // IDLE: Looking for Onset of "Hey" or "Hi" ([heɪ] / [haɪ])
                // Must be well above background noise floor (SNR >= 2.0) with clean voiced vowel energy
                val isHeyOnset = (snr >= 2.0f && features.rms >= minSpeechRms) &&
                        features.isVoiced &&
                        (b1 + b2 + b3 > 0.38f) &&
                        features.zcr in 0.04f..0.38f

                if (isHeyOnset) {
                    currentState = 1
                    phraseStartFrame = totalFramesProcessed
                    stateStartFrame = totalFramesProcessed
                    peakHeyRms = features.rms
                    peakERms = 0f
                    troughVRms = 1f
                    peakARms = 0f
                }
            }

            1 -> {
                // IN "HEY" / "HI": Duration must be between 5 and 24 frames (~80ms - 380ms)
                val framesInHey = totalFramesProcessed - stateStartFrame
                if (features.rms > peakHeyRms) peakHeyRms = features.rms

                if (framesInHey in 5..24) {
                    // Transition to "E" ([iː]) in "EVA":
                    // [iː] features extreme upper resonance (b4 is high) and low F1 (b0 is voiced)
                    val isEVowel = (b4 > 0.20f || (b4 / (b2 + 0.01f) > 0.75f)) &&
                            features.isVoiced &&
                            features.zcr < 0.24f

                    if (isEVowel) {
                        currentState = 2
                        stateStartFrame = totalFramesProcessed
                        peakERms = features.rms
                    }
                } else if (framesInHey > 26) {
                    currentState = 0 // Syllable too long or abandoned
                }
            }

            2 -> {
                // IN "E" ([iː]): Duration between 4 and 20 frames (~64ms - 320ms)
                val framesInE = totalFramesProcessed - stateStartFrame
                if (features.rms > peakERms) peakERms = features.rms

                if (framesInE in 4..20) {
                    // Transition to "-V-" in "EVA":
                    // Lower lip contacts upper teeth: RMS MUST drop, and fricative energy / ZCR rises
                    val isVDip = (features.rms < peakERms * 0.82f) &&
                            (b5 + b6 > 0.18f || features.zcr > 0.16f || features.rms < 0.024f)

                    if (isVDip) {
                        currentState = 3
                        stateStartFrame = totalFramesProcessed
                        troughVRms = features.rms
                    }
                } else if (framesInE > 22) {
                    currentState = 0
                }
            }

            3 -> {
                // IN "-V-" (Trough): Duration between 2 and 9 frames (~32ms - 144ms)
                val framesInV = totalFramesProcessed - stateStartFrame
                if (features.rms < troughVRms) troughVRms = features.rms

                if (framesInV in 2..9) {
                    // Transition to "-A" in "EVA":
                    // Vocal tract opens back up: RMS MUST rise noticeably above the "-V-" trough
                    val isAVowel = (features.rms > troughVRms * 1.18f) &&
                            (b1 + b2 > 0.34f) &&
                            features.isVoiced &&
                            features.zcr < 0.26f

                    if (isAVowel) {
                        currentState = 4
                        stateStartFrame = totalFramesProcessed
                        peakARms = features.rms
                    }
                } else if (framesInV > 10) {
                    currentState = 0 // Too long of a silence/pause
                }
            }

            4 -> {
                // IN "-A" (Terminal Vowel): Duration between 4 and 18 frames (~64ms - 288ms)
                val framesInA = totalFramesProcessed - stateStartFrame
                if (features.rms > peakARms) peakARms = features.rms

                // Trigger verification on vowel terminal drop or after sufficient stable vowel
                val isTerminalClosure = framesInA >= 5 &&
                        (features.rms < peakARms * 0.70f || features.rms < ambientNoiseRms * 1.8f || framesInA >= 10)

                if (isTerminalClosure) {
                    val totalPhraseFrames = totalFramesProcessed - phraseStartFrame

                    // 4. Verification Check 1: Total Duration Constraint (400ms to 1150ms)
                    if (totalPhraseFrames in 25..72) {
                        // 5. Verification Check 2: Physical Envelope Modulation Check
                        val hasValidModulation = peakERms > ambientNoiseRms * 2.4f &&
                                troughVRms < peakERms * 0.82f &&
                                peakARms > troughVRms * 1.16f

                        if (hasValidModulation) {
                            val confidence = computeHolisticConfidence(totalPhraseFrames)
                            val requiredScore = (0.74f - (sensitivity * 0.20f)).coerceIn(0.55f, 0.82f)

                            if (confidence >= requiredScore) {
                                Log.i(TAG, ">>> Verified 'Hey EVA' detected! (Confidence: $confidence >= $requiredScore, frames: $totalPhraseFrames) <<<")
                                lastDetectionTimestamp = now
                                reset()
                                return Pair(true, confidence)
                            } else {
                                Log.d(TAG, "Rejected candidate: confidence $confidence below threshold $requiredScore")
                            }
                        }
                    }
                    currentState = 0
                } else if (framesInA > 20) {
                    currentState = 0
                }
            }
        }

        return Pair(false, 0f)
    }

    /**
     * Holistic multi-layer scoring across the collected sliding window buffer.
     */
    private fun computeHolisticConfidence(phraseFrames: Int): Float {
        val windowSize = phraseFrames.coerceIn(24, frameHistory.size)
        val candidateFrames = frameHistory.takeLast(windowSize)
        if (candidateFrames.isEmpty()) return 0.5f

        var maxRms = 0f
        var highEResonanceCount = 0
        var fricativeTransitionCount = 0
        var openVowelCount = 0
        var voicedCount = 0

        for (f in candidateFrames) {
            if (f.rms > maxRms) maxRms = f.rms
            if (f.isVoiced) voicedCount++
            // Check for [iː] marker
            if (f.bandEnergies[4] > 0.18f && f.upperResonanceRatio > 0.28f) highEResonanceCount++
            // Check for [v] fricative marker
            if (f.fricativeRatio > 0.18f || f.zcr > 0.18f) fricativeTransitionCount++
            // Check for [a]/[e] open vowel marker
            if (f.bandEnergies[1] + f.bandEnergies[2] > 0.35f) openVowelCount++
        }

        val voicedRatio = (voicedCount.toFloat() / windowSize).coerceIn(0f, 1f)
        val eScore = (highEResonanceCount.toFloat() / (windowSize * 0.20f)).coerceIn(0f, 1f)
        val vScore = (fricativeTransitionCount.toFloat() / (windowSize * 0.15f)).coerceIn(0f, 1f)
        val aScore = (openVowelCount.toFloat() / (windowSize * 0.25f)).coerceIn(0f, 1f)
        val envelopeScore = ((peakERms - troughVRms) / (peakERms + 0.001f)).coerceIn(0f, 1f)

        val totalScore = (voicedRatio * 0.20f) +
                (eScore * 0.30f) +
                (vScore * 0.20f) +
                (aScore * 0.15f) +
                (envelopeScore * 0.15f)

        return totalScore.coerceIn(0.1f, 1.0f)
    }
}
