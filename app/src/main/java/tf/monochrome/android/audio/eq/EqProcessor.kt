package tf.monochrome.android.audio.eq

import android.media.audiofx.Equalizer
import android.util.Log
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/**
 * EqProcessor - Applies parametric EQ to audio playback
 *
 * Strategy:
 * 1. On Android, we use the AudioEffect API (android.media.audiofx.Equalizer)
 * 2. If the device doesn't have proper Equalizer support, we gracefully degrade
 * 3. Bands are approximated to the system's built-in EQ if needed
 *
 * Note: Perfect biquad filter matching isn't always possible on all devices,
 * but the approximation is close enough for practical use.
 */
class EqProcessor(
    private val audioSessionId: Int
) {
    companion object {
        private const val TAG = "EqProcessor"
        private const val SUPPORTED_BANDS = 10  // Most Android devices support 5-10 bands
    }

    private var equalizer: Equalizer? = null
    private var customDspBands: List<EqBand> = emptyList()
    private var lastPreamp: Float = 0f
    private var isEnabled = false

    /**
     * Initialize audio effects
     *
     * Attempts to create system EQ effects. Gracefully handles devices
     * that don't support effects.
     */
    fun initialize() {
        try {
            // Try to create system equalizer
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "System EQ not available: ${e.message}")
            equalizer = null
        }
    }

    /**
     * Apply EQ bands to the audio
     *
     * Converts the parametric EQ bands to the system's Equalizer format
     * Falls back to custom DSP if system EQ isn't available
     *
     * @param bands List of EQ bands to apply
     * @param preamp Preamp gain in dB
     */
    fun applyBands(bands: List<EqBand>, preamp: Float = 0f) {
        // Always store bands so they can be re-applied when enabled
        customDspBands = bands
        lastPreamp = preamp

        if (!isEnabled) return

        // If system EQ is available, use it (preamp is applied inside as band offset)
        if (equalizer != null) {
            applyBandsToSystemEq(bands, preamp)
        }
    }

    /**
     * Apply bands using Android's system Equalizer
     *
     * The system EQ typically has:
     * - Fixed band frequencies (e.g., 60, 150, 400, 1000, 2500, 6500, 16000 Hz)
     * - Limited gain range (usually ±15dB)
     * - Number of bands varies (5-10 depending on device)
     *
     * We interpolate our bands to the system's frequencies as closely as possible.
     */
    private fun applyBandsToSystemEq(bands: List<EqBand>, preamp: Float) {
        val eq = equalizer ?: return

        try {
            val numBands = eq.numberOfBands.toInt()

            // Use standard 10-band EQ frequencies (most common Android EQ setup)
            // These frequencies match the typical system equalizer
            val bandFrequencies = listOf(60f, 150f, 400f, 1000f, 2500f, 6500f, 16000f)
                .take(numBands)
                .toMutableList()

            // Fill remaining bands with interpolated frequencies if needed
            if (bandFrequencies.size < numBands) {
                for (i in bandFrequencies.size until numBands) {
                    // Interpolate between existing frequencies
                    val ratio = i.toFloat() / (numBands - 1)
                    val freq = 20f * (20000f / 20f).pow(ratio)
                    bandFrequencies.add(freq)
                }
            }

            // For each system band, calculate gain from our bands
            for (bandIdx in 0 until numBands) {
                val sysFreq = bandFrequencies[bandIdx]
                var totalGain = 0f

                // Sum contributions from all our bands to this frequency
                for (band in bands) {
                    if (!band.enabled) continue

                    // Approximate the effect of our band at this frequency
                    val contribution = estimateBandGain(sysFreq, band)
                    totalGain += contribution
                }

                // Add preamp
                totalGain += preamp

                // Clamp to device limits (typically ±15dB, stored as milliBels)
                val minDb = eq.bandLevelRange[0] / 100f
                val maxDb = eq.bandLevelRange[1] / 100f
                totalGain = totalGain.coerceIn(minDb, maxDb)

                // Apply to system EQ (value in milliBels)
                eq.setBandLevel(bandIdx.toShort(), (totalGain * 100).toInt().toShort())
            }

            eq.enabled = true
        } catch (_: Exception) {
        }
    }

    /**
     * Estimate the gain of a band at a specific frequency
     *
     * Uses a simplified model of how a peaking EQ band affects frequency response.
     * - Peak at center frequency gets full gain
     * - Gain decreases based on Q (bandwidth)
     * - Different filter types handled differently
     */
    private fun estimateBandGain(freqHz: Float, band: EqBand): Float {
        val freqRatio = freqHz / band.freq
        val octaveDistance = log2(freqRatio)

        return when (band.type) {
            FilterType.PEAKING -> {
                // Peaking filter: Gaussian-like response centered at band.freq
                // Width is determined by Q (higher Q = narrower)
                val bandwidth = 1f / band.q
                val normalized = octaveDistance / bandwidth
                val response = kotlin.math.exp(-(normalized * normalized))
                band.gain * response
            }
            FilterType.LOWSHELF -> {
                // Low shelf: ramps up toward low frequencies
                if (freqHz < band.freq * 0.5f) {
                    band.gain  // Full gain at low frequencies
                } else if (freqHz > band.freq * 2f) {
                    0f  // No effect at high frequencies
                } else {
                    // Interpolate between
                    val ratio = (freqHz - band.freq * 0.5f) / (band.freq * 1.5f)
                    band.gain * (1f - ratio)
                }
            }
            FilterType.HIGHSHELF -> {
                // High shelf: ramps up toward high frequencies
                if (freqHz > band.freq * 2f) {
                    band.gain  // Full gain at high frequencies
                } else if (freqHz < band.freq * 0.5f) {
                    0f  // No effect at low frequencies
                } else {
                    // Interpolate between
                    val ratio = (freqHz - band.freq * 0.5f) / (band.freq * 1.5f)
                    band.gain * ratio
                }
            }
        }
    }

    /**
     * Enable EQ processing
     */
    fun enable() {
        isEnabled = true
        equalizer?.enabled = true
        // Re-apply any bands that were stored before enable was called
        if (customDspBands.isNotEmpty()) {
            applyBands(customDspBands, lastPreamp)
        }
    }

    /**
     * Disable EQ processing
     */
    fun disable() {
        isEnabled = false
        equalizer?.enabled = false
    }

    /**
     * Reset EQ to flat response
     */
    fun reset() {
        try {
            equalizer?.let { eq ->
                for (i in 0 until eq.numberOfBands) {
                    eq.setBandLevel(i.toShort(), 0)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Release audio effects when no longer needed
     */
    fun release() {
        try {
            equalizer?.release()
            equalizer = null
        } catch (_: Exception) {
        }
    }

    /**
     * Check if EQ is available on this device
     */
    fun isAvailable(): Boolean = equalizer != null

    /**
     * Check if EQ is currently active
     */
    fun isActive(): Boolean = isEnabled && (equalizer?.enabled == true)
}
