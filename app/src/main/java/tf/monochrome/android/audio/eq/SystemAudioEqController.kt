package tf.monochrome.android.audio.eq

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.EqBand
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wavelet / Poweramp-style SYSTEM-WIDE EQ. Attaches a graphic-EQ audio effect to
 * the device's global output mix (audio session 0) so the AutoEQ correction is
 * applied to ALL audio on the device, not just this app's own playback.
 *
 * The per-band gains are sampled from the SAME parametric AutoEQ correction the
 * in-app engine produces — [AutoEqEngine.calculateBiquadResponse] summed over the
 * user's saved correction bands plus preamp — so the system-wide curve tracks the
 * selected headphone profile. Because a global effect can only be a graphic EQ,
 * this is a many-band approximation of the exact parametric curve.
 *
 * Whether a session-0 effect actually reaches every stream is up to the device's
 * audio HAL; on some OEM ROMs it silently does nothing. Every native call is
 * guarded — DynamicsProcessing (API 28+) is tried first, the legacy Equalizer is
 * the fallback, and any failure just leaves the effect inactive instead of
 * crashing. Note: the effect lives for as long as this app's process does; a
 * fully always-on effect (persisting after the process is reclaimed) would need a
 * dedicated foreground service.
 */
@Singleton
class SystemAudioEqController @Inject constructor(
    private val preferences: PreferencesManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    private var dynamics: DynamicsProcessing? = null
    private var equalizer: Equalizer? = null
    private var started = false

    /**
     * True while a global effect is actually attached. Best-effort — the HAL may
     * still not route all streams through it, but a false here means we couldn't
     * even attach (device unsupported). Drives the UI's on/off reflection.
     */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Begin observing the enable flag + AutoEQ profile. Idempotent. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        scope.launch {
            combine(
                preferences.systemWideAutoEqEnabled,
                preferences.eqBandsJson,
                preferences.eqPreamp,
            ) { enabled, bandsJson, preamp -> Cfg(enabled, bandsJson, preamp) }
                .collectLatest { cfg ->
                    if (cfg.enabled) applyGlobal(cfg.bandsJson, cfg.preamp) else release()
                }
        }
    }

    private data class Cfg(val enabled: Boolean, val bandsJson: String?, val preamp: Double)

    private fun applyGlobal(bandsJson: String?, preamp: Double) {
        val bands = decodeBands(bandsJson)
        synchronized(lock) {
            val primary = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    applyDynamicsLocked(bands, preamp.toFloat())
                } else {
                    applyEqualizerLocked(bands, preamp.toFloat())
                }
            }.onFailure { Log.w(TAG, "system-wide EQ primary attach failed: ${it.message}") }
                .getOrDefault(false)

            // If DynamicsProcessing wouldn't attach, try the legacy Equalizer.
            val attached = if (!primary && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { applyEqualizerLocked(bands, preamp.toFloat()) }
                    .onFailure { Log.w(TAG, "system-wide EQ fallback attach failed: ${it.message}") }
                    .getOrDefault(false)
            } else {
                primary
            }
            _active.value = attached
        }
    }

    /** API 28+: a multi-band DynamicsProcessing pre-EQ on the global output mix. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyDynamicsLocked(bands: List<EqBand>, preamp: Float): Boolean {
        releaseLocked()
        val n = BAND_FREQS.size
        val channels = 2
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            channels,
            /* preEqInUse = */ true, /* preEqBandCount = */ n,
            /* mbcInUse = */ false, /* mbcBandCount = */ 0,
            /* postEqInUse = */ false, /* postEqBandCount = */ 0,
            /* limiterInUse = */ false,
        ).build()
        val dp = DynamicsProcessing(GLOBAL_PRIORITY, GLOBAL_SESSION, config)
        for (ch in 0 until channels) {
            val eq = dp.getPreEqByChannelIndex(ch)
            eq.isEnabled = true
            for (b in 0 until n) {
                val band = eq.getBand(b)
                band.isEnabled = true
                band.cutoffFrequency = BAND_FREQS[b]
                band.gain = gainAt(BAND_FREQS[b], bands, preamp)
                eq.setBand(b, band)
            }
            dp.setPreEqByChannelIndex(ch, eq)
        }
        dp.setEnabled(true)
        dynamics = dp
        return dp.enabled
    }

    /** API 26–27 (or DynamicsProcessing unavailable): legacy N-band Equalizer. */
    private fun applyEqualizerLocked(bands: List<EqBand>, preamp: Float): Boolean {
        releaseLocked()
        val eq = Equalizer(GLOBAL_PRIORITY, GLOBAL_SESSION)
        val range = eq.bandLevelRange // [min, max] in millibels
        val minLevel = range[0].toInt()
        val maxLevel = range[1].toInt()
        val count = eq.numberOfBands.toInt()
        for (b in 0 until count) {
            // getCenterFreq is in milliHertz.
            val fc = eq.getCenterFreq(b.toShort()) / 1000f
            val millibels = (gainAt(fc, bands, preamp) * 100f).toInt().coerceIn(minLevel, maxLevel)
            eq.setBandLevel(b.toShort(), millibels.toShort())
        }
        eq.setEnabled(true)
        equalizer = eq
        return eq.enabled
    }

    /** Total AutoEQ correction (dB) at [freqHz]: preamp + every saved band's response. */
    private fun gainAt(freqHz: Float, bands: List<EqBand>, preamp: Float): Float {
        var g = preamp
        for (band in bands) g += AutoEqEngine.calculateBiquadResponse(freqHz, band)
        return g.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    }

    private fun decodeBands(bandsJson: String?): List<EqBand> =
        if (bandsJson.isNullOrEmpty()) emptyList()
        else runCatching { json.decodeFromString<List<EqBand>>(bandsJson) }.getOrDefault(emptyList())

    private fun release() {
        synchronized(lock) {
            releaseLocked()
            _active.value = false
        }
    }

    private fun releaseLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { dynamics?.setEnabled(false) }
            runCatching { dynamics?.release() }
        }
        dynamics = null
        runCatching { equalizer?.setEnabled(false) }
        runCatching { equalizer?.release() }
        equalizer = null
    }

    private companion object {
        const val TAG = "SystemAudioEq"
        const val GLOBAL_SESSION = 0 // 0 = global output mix (affects all apps)
        const val GLOBAL_PRIORITY = 0
        const val MIN_GAIN_DB = -24f
        const val MAX_GAIN_DB = 24f
        // Log-spaced correction bands across the audible range.
        val BAND_FREQS = floatArrayOf(
            30f, 45f, 65f, 95f, 140f, 210f, 320f, 480f, 720f, 1100f,
            1650f, 2500f, 3800f, 5700f, 8500f, 12500f, 18000f,
        )
    }
}
