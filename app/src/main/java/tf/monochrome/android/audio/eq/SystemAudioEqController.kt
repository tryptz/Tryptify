package tf.monochrome.android.audio.eq

import android.app.NotificationManager
import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.ToneControls
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
 * Each ear has its own curve where the listener has one (2-channel mode with a
 * right-ear correction saved): DynamicsProcessing's pre-EQ is per channel, and
 * the right one used to be given the left ear's curve. The legacy Equalizer
 * has one curve for both and plays the left.
 *
 * Quality: uses DynamicsProcessing's float pre-EQ (no MBC, no makeup/output
 * gain) with VARIANT_FAVOR_FREQUENCY_RESOLUTION for the most accurate EQ
 * shape. Its limiter is in the path only while the curve rises above full
 * scale ([SystemEqCurve.Plan.limiter]: a manual preamp left too high over a
 * boost), both ears linked so the image does not move; otherwise that would
 * clip in whatever app is playing. When the combined curve is completely flat (no active AutoEQ
 * band and a 0 preamp), the effect is fully released so audio passes through
 * BIT-PERFECT — untouched, no filter bank in the path — and re-attaches the
 * instant any correction or tone knob is non-zero.
 *
 * While the master toggle is on, an ongoing "System-wide EQ active" notification
 * is shown (Wavelet-style) so the effect is discoverable. Settings changes retune
 * the already-attached effect in place rather than tearing it down and
 * re-attaching it, so adjusting the EQ or tone doesn't skip the audio; identical
 * config emissions (DataStore re-fires on every unrelated write) are dropped
 * outright before they ever reach the effect.
 *
 * Whether a session-0 effect actually reaches every stream is up to the device's
 * audio HAL; on some OEM ROMs it silently does nothing. Every native call is
 * guarded — DynamicsProcessing (API 28+) is tried first, the legacy Equalizer is
 * the fallback, and any failure just leaves the effect inactive instead of
 * crashing. The effect lives for as long as this app's process does, so while
 * the switch is on [SystemEqService] stands in the foreground to keep the
 * process: closed, with another app playing, the correction used to stop
 * whenever Android wanted the memory. A start Android refuses (the app is in
 * the background) is asked again when the app is next opened.
 */
@Singleton
class SystemAudioEqController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: PreferencesManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    // Whether the limiter could be put in this device's effect. Some refuse a
    // config with one; the equalizer still plays there, without it.
    private var limiterAvailable = true

    // The switch as last seen, and whether the keep-alive service is as it
    // should be for it. Main thread.
    private var wantService = false
    private var serviceSynced = true

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

    /** Begin observing the enable flag + AutoEQ profile + tone shelves. Idempotent. */
    @OptIn(FlowPreview::class)
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        scope.launch {
            // Seven sources: past combine's typed overloads, so by position.
            combine(
                preferences.systemWideAutoEqEnabled,
                preferences.eqBandsJson,
                preferences.eqPreamp,
                preferences.systemToneControls,
                preferences.eqAutoPreamp,
                preferences.eqBandsRJson,
                preferences.eqStereoMode,
            ) { v ->
                Cfg(
                    enabled = v[0] as Boolean,
                    bandsJson = v[1] as String?,
                    preamp = v[2] as Double,
                    tone = v[3] as ToneControls,
                    autoPreamp = v[4] as Boolean,
                    bandsRJson = v[5] as String?,
                    stereo = v[6] as Boolean,
                )
            }
                // DataStore re-emits its whole Preferences on EVERY write to ANY
                // key, so without this dedup, changing an unrelated setting would
                // re-run applyGlobal and re-attach the session-0 effect — an
                // audible skip across all audio on every settings change. Dropping
                // no-op emissions means we only touch the effect when the AutoEQ
                // curve, tone, or the master toggle actually changed.
                .distinctUntilChanged()
                // Coalesce rapid tone-knob drags so the global effect isn't
                // re-tuned dozens of times a second.
                .debounce(70L)
                .collectLatest { cfg ->
                    if (cfg.enabled) applyGlobal(cfg) else release()
                    // Live notification mirrors the master toggle so the user can
                    // see at a glance that system-wide EQ is engaged.
                    updateNotification(cfg.enabled)
                    withContext(Dispatchers.Main) { keepAlive(cfg.enabled) }
                }
        }
        // A service start refused in the background is asked again when the
        // app comes to the front.
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (!serviceSynced) keepAlive(wantService)
                }
            })
        }
    }

    /** Starts or stops [SystemEqService] with the switch. Main thread. */
    private fun keepAlive(on: Boolean) {
        wantService = on
        serviceSynced = SystemEqService.sync(context, on)
    }

    private data class Cfg(
        val enabled: Boolean,
        val bandsJson: String?,
        val preamp: Double,
        val tone: ToneControls,
        val autoPreamp: Boolean,
        val bandsRJson: String?,
        val stereo: Boolean,
    )

    private fun applyGlobal(cfg: Cfg) {
        // The saved correction for each ear and the tone shelves, as gains at
        // the effect's bands; null when nothing would change the sound.
        val plan = SystemEqCurve.plan(
            correctionL = decodeBands(cfg.bandsJson),
            correctionR = decodeBands(cfg.bandsRJson),
            stereo = cfg.stereo,
            tone = cfg.tone,
            manualPreampDb = cfg.preamp.toFloat(),
            autoPreamp = cfg.autoPreamp,
        )
        synchronized(lock) {
            // Bit-perfect passthrough when there is genuinely nothing to apply:
            // the effect is not attached at all, so the audio is left untouched
            // (no filter bank, no float round-trip). It re-attaches the instant
            // any AutoEQ band or tone knob becomes non-zero.
            if (plan == null) {
                releaseLocked()
                _active.value = false
                return
            }
            val primary = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    applyDynamicsLocked(plan)
                } else {
                    applyEqualizerLocked(plan)
                }
            }.onFailure { Log.w(TAG, "system-wide EQ primary attach failed: ${it.message}") }
                .getOrDefault(false)

            // If DynamicsProcessing wouldn't attach, try the legacy Equalizer.
            val attached = if (!primary && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { applyEqualizerLocked(plan) }
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
    private fun applyDynamicsLocked(plan: SystemEqCurve.Plan): Boolean {
        val n = SystemEqCurve.BAND_FREQS.size
        val channels = 2
        // Reuse the already-attached effect and only re-write its band gains.
        // Releasing and re-creating a session-0 effect on every knob move makes
        // the audio HAL re-route the output mix — an audible skip/pop across all
        // audio. Live parameter updates on the existing instance are seamless.
        val dp = dynamics ?: run {
            releaseLocked()
            fun build(limiter: Boolean) = DynamicsProcessing(
                GLOBAL_PRIORITY,
                GLOBAL_SESSION,
                DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    channels,
                    /* preEqInUse = */ true, /* preEqBandCount = */ n,
                    /* mbcInUse = */ false, /* mbcBandCount = */ 0,
                    /* postEqInUse = */ false, /* postEqBandCount = */ 0,
                    /* limiterInUse = */ limiter,
                ).build(),
            )
            // In the config from the start, so it can be switched in later
            // without building the effect again; a device that refuses it
            // gets the equalizer alone.
            if (limiterAvailable) {
                runCatching { build(limiter = true) }.getOrElse {
                    Log.w(TAG, "no limiter on this device's effect: ${it.message}")
                    limiterAvailable = false
                    build(limiter = false)
                }
            } else {
                build(limiter = false)
            }
        }
        for (ch in 0 until channels) {
            val gains = if (ch == 0) plan.left else plan.right
            val eq = dp.getPreEqByChannelIndex(ch)
            eq.isEnabled = true
            for (b in 0 until n) {
                val band = eq.getBand(b)
                band.isEnabled = true
                band.cutoffFrequency = SystemEqCurve.BAND_FREQS[b]
                band.gain = gains[b]
                eq.setBand(b, band)
            }
            dp.setPreEqByChannelIndex(ch, eq)
            if (limiterAvailable) {
                runCatching {
                    val limiter = dp.getLimiterByChannelIndex(ch)
                    limiter.isEnabled = plan.limiter
                    // One group: both ears are turned down together.
                    limiter.linkGroup = LIMITER_LINK_GROUP
                    limiter.attackTime = LIMITER_ATTACK_MS
                    limiter.releaseTime = LIMITER_RELEASE_MS
                    limiter.ratio = LIMITER_RATIO
                    limiter.threshold = LIMITER_THRESHOLD_DB
                    limiter.postGain = 0f
                    dp.setLimiterByChannelIndex(ch, limiter)
                }.onFailure {
                    Log.w(TAG, "limiter refused on channel $ch: ${it.message}")
                    limiterAvailable = false
                }
            }
        }
        dp.setEnabled(true)
        dynamics = dp
        return dp.enabled
    }

    /**
     * API 26–27 (or DynamicsProcessing unavailable): legacy N-band Equalizer.
     * One curve for both ears, at its own centres: the left ear's is played.
     */
    private fun applyEqualizerLocked(plan: SystemEqCurve.Plan): Boolean {
        // Same rationale as the DynamicsProcessing path: reuse the attached
        // Equalizer and only re-set its band levels so a settings change doesn't
        // tear down and re-attach the session-0 effect (which skips the audio).
        val eq = equalizer ?: Equalizer(GLOBAL_PRIORITY, GLOBAL_SESSION)
        val range = eq.bandLevelRange // [min, max] in millibels
        val minLevel = range[0].toInt()
        val maxLevel = range[1].toInt()
        val count = eq.numberOfBands.toInt()
        for (b in 0 until count) {
            // getCenterFreq is in milliHertz.
            val fc = eq.getCenterFreq(b.toShort()) / 1000f
            val millibels = (gainNear(fc, plan.left) * 100f).toInt().coerceIn(minLevel, maxLevel)
            eq.setBandLevel(b.toShort(), millibels.toShort())
        }
        eq.setEnabled(true)
        equalizer = eq
        return eq.enabled
    }

    /** The plan's gain at the band nearest [freqHz], an octave being the same step anywhere. */
    private fun gainNear(freqHz: Float, gains: FloatArray): Float {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in SystemEqCurve.BAND_FREQS.indices) {
            val d = kotlin.math.abs(kotlin.math.ln(SystemEqCurve.BAND_FREQS[i] / freqHz.coerceAtLeast(1f)))
            if (d < bestDistance) { bestDistance = d; best = i }
        }
        return gains[best]
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

    // ── Live notification ───────────────────────────────────────────────────

    /**
     * Post an ongoing, non-dismissible notification while system-wide EQ is on
     * (Wavelet-style), or cancel it when off. Best-effort — if the user has
     * revoked POST_NOTIFICATIONS the notify() simply no-ops.
     */
    private fun updateNotification(enabled: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (!enabled) {
            runCatching { manager.cancel(SystemEqNotice.NOTIFICATION_ID) }
            return
        }
        runCatching { manager.notify(SystemEqNotice.NOTIFICATION_ID, SystemEqNotice.build(context)) }
    }

    private companion object {
        const val TAG = "SystemAudioEq"
        const val GLOBAL_SESSION = 0 // 0 = global output mix (affects all apps)
        const val GLOBAL_PRIORITY = 0

        // The limiter, when the curve calls for one: Android's own defaults
        // for attack, release and ratio, a dB under full scale.
        const val LIMITER_LINK_GROUP = 0
        const val LIMITER_ATTACK_MS = 1f
        const val LIMITER_RELEASE_MS = 60f
        const val LIMITER_RATIO = 10f
        const val LIMITER_THRESHOLD_DB = -1f
    }
}
