// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.usb

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tf.monochrome.android.audio.dsp.DspChain
import tf.monochrome.android.audio.dsp.DspSeed
import tf.monochrome.android.audio.dsp.seed
import tf.monochrome.android.audio.dsp.spatial.SpatialPlacementStore
import tf.monochrome.android.audio.replaygain.ReplayGainMode
import tf.monochrome.android.audio.replaygain.ReplayGainSettings
import tf.monochrome.android.audio.resample.FloatSonicAudioProcessor
import tf.monochrome.android.audio.resample.VariRateAudioProcessor
import tf.monochrome.android.data.preferences.PreferencesManager

/**
 * Trypt Mania's way onto the exclusive USB DAC, and through the DSP engine.
 *
 * The mode's players (`GlyphAudioTransport`, `GlyphPreviewPlayer`) stay
 * ExoPlayers of their own, outside the shared player on purpose; what
 * changes is where their audio goes. Each gets a [LibusbAudioSink] from
 * [player], so with a DAC claimed the song plays through it, at the DAC
 * level the volume keys set, and without one the sink passes through to
 * Android's output as before.
 *
 * Each also gets a [DspChain] of its own, like a blend's outgoing tail: the
 * main player's stages are singletons wired into the one ExoPlayer, holding
 * one format and one stream's filter state, so a second stream cannot share
 * them. The copy runs the same engine (mixer, Oxford Inflator, Compressor
 * and Crossfeed, ReplayGain, AutoEQ, Parametric EQ) on the DAC and on
 * Android's output alike, and follows the app's settings live: PlaybackService
 * publishes a [DspSeed] whenever one lands on its singletons ([publishSeed]),
 * read only while a player is alive to take it ([LazySeed]).
 * The taps stay out (spectrum, BPM, dynamic range, Atmos, projectM): they
 * feed what the app shows about the song playing in the main player.
 *
 * The DAC has one stream, so the sink takes it from the main player as
 * [StreamClaimant.GAME] ([StreamLease]): the main player pauses while the
 * game holds it, and the next play on the main player takes it back.
 */
@Singleton
@UnstableApi
class UsbGameAudio @Inject constructor(
    private val driver: LibusbUacDriver,
    private val volume: BypassVolumeController,
    private val placement: SpatialPlacementStore,
    preferences: PreferencesManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val seeds = LazySeed<DspSeed>()
    /** The players alive, on the main thread, each with its own chain. */
    private val players = LinkedHashSet<GamePlayerAudio>()
    private var replayGain = ReplayGainSettings()

    init {
        scope.launch {
            preferences.replayGainSettings.collect { settings ->
                replayGain = settings
                players.forEach { it.applyReplayGain() }
            }
        }
    }

    /** Who holds the DAC's stream (see [StreamLease.holder]); the game is on the DAC while it is [StreamClaimant.GAME]. */
    val holder: StateFlow<StreamClaimant?> get() = driver.streamHolder

    /** True while Trypt Mania's audio is what the DAC plays. */
    fun gameOnDac(): Boolean = driver.holdsStream(StreamClaimant.GAME)

    /**
     * The main player's settings have changed; [seed] reads them as they
     * stand, and every game chain follows. Read now while a player is alive,
     * otherwise when the next one comes: the read is the engine's whole state,
     * serialised and parsed again, and nobody outside Trypt Mania needs it.
     * Main thread.
     */
    fun publishSeed(seed: () -> DspSeed) {
        val changed = seeds.publish(seed, listening = players.isNotEmpty()) ?: return
        players.forEach { it.apply(changed) }
    }

    /**
     * The publisher is going (PlaybackService's onDestroy): a change it has
     * not been asked for yet is read now, and its reader is let go, so this
     * singleton does not keep a destroyed service. Main thread.
     */
    fun retireSeed() {
        seeds.retire()
    }

    /** A player's audio: its renderers, its chain, and what the song and the speed tell it. Main thread. */
    fun player(context: Context): GamePlayerAudio = GamePlayerAudio(context).also { players.add(it) }

    /**
     * One game player's sink and chain. [renderers] goes to its ExoPlayer;
     * [songStarts] and [setPitchRatio] are called as the player prepares a
     * song and changes speed; [release] when the player is released, which
     * frees the chain's native engine.
     */
    inner class GamePlayerAudio internal constructor(context: Context) {
        val chain: DspChain = DspChain.createCopy(placement)
        private var pitchRatio = 1f
        private var songKey: String? = null

        init {
            chain.observeEngine(scope)
            seeds.current()?.let { apply(it) }
        }

        /**
         * The DSP runs in both places, as for the main player: in the sink's
         * own chain on the DAC, and in the delegate's processor chain on
         * Android's output, the same instances, which only one of the two
         * configures at a time (the sink's rule). Float samples and the guard
         * for decoders that lie about them come first on the DAC path; the
         * practice speed's stages last (the resampler for pitch that follows
         * speed, time-stretching for pitch that does not).
         */
        val renderers: RenderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val resampler = VariRateAudioProcessor()
                val timeStretch = FloatSonicAudioProcessor()
                val dsp = chain.processors.toList()
                return LibusbAudioSink(
                    delegate = DefaultAudioSink.Builder(context)
                        .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(*chain.processors))
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build(),
                    driver = driver,
                    volumeController = volume,
                    processors = listOf(FloatPcmGuard(), ToFloatPcmAudioProcessor()) + dsp + listOf(resampler, timeStretch),
                    resampler = resampler,
                    timeStretch = timeStretch,
                    claimant = StreamClaimant.GAME,
                    // The player's own volume (a preview's fade) under the DAC level,
                    // not the main player's gain, which is another song's.
                    ownsVolume = true,
                )
            }
        }

        internal fun apply(seed: DspSeed) {
            chain.seed(seed, pitchRatio)
            // The engine already up takes the state now; one not yet up takes
            // it when it comes up (observeEngine).
            chain.applyPendingDspState()
        }

        /**
         * A song starts: its ReplayGain by the app's mode. The game's songs
         * carry no gain tags the way the library's do, so a mode that is on
         * measures the song as it plays and brings it to the target, as the
         * main player does for an untagged song; off plays it as mastered.
         */
        fun songStarts(key: String) {
            songKey = key
            applyReplayGain()
        }

        internal fun applyReplayGain() {
            val key = songKey ?: return
            if (replayGain.mode == ReplayGainMode.OFF) chain.replayGain.setGain(1f)
            else chain.replayGain.follow(replayGain.targetLufs, key)
        }

        /**
         * The practice speed with the pitch following it: AutoEQ pre-warps
         * its bands against the pitch, so a song at 0.8× is corrected where
         * its frequencies land, not where they were.
         */
        fun setPitchRatio(ratio: Float) {
            pitchRatio = ratio
            chain.autoEq.setPitchRatio(ratio)
        }

        /** Frees the chain's native engine; the player is released with it. Main thread. */
        fun release() {
            players.remove(this)
            chain.release()
        }
    }
}

/**
 * A value worked out only when someone is there to take it: the game's
 * [DspSeed], which PlaybackService publishes on every EQ, knob and engine
 * change, on the main thread, whether or not Trypt Mania is open.
 *
 * [publish] keeps the reader. While somebody listens it reads at once and
 * hands on what changed, every change as before (an equal value is no
 * change, as a StateFlow would have it); otherwise it only notes that the
 * last value is stale. [current], for a newcomer, reads once if it is.
 * Main thread, like its callers; not thread-safe.
 */
internal class LazySeed<T : Any> {
    private var source: (() -> T)? = null
    private var latest: T? = null
    private var stale = false

    /** A change; when [listening], the value read now if it differs from the last, else null (read later, or nothing new). */
    fun publish(source: () -> T, listening: Boolean): T? {
        this.source = source
        if (!listening) {
            stale = true
            return null
        }
        stale = false
        val value = source()
        if (value == latest) return null
        latest = value
        return value
    }

    /** The value as it stands, read now if a change came while nobody listened; null before any. */
    fun current(): T? {
        if (stale) {
            stale = false
            latest = source?.invoke()
        }
        return latest
    }

    /** Reads what is stale and lets go of the reader; the last value stays for [current]. */
    fun retire() {
        current()
        source = null
    }
}
