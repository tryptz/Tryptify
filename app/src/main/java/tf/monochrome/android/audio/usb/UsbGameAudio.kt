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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
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
 * publishes a [DspSeed] whenever one lands on its singletons ([publishSeed]).
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
    private val _seed = MutableStateFlow<DspSeed?>(null)
    /** The players alive, on the main thread, each with its own chain. */
    private val players = LinkedHashSet<GamePlayerAudio>()
    private var replayGain = ReplayGainSettings()

    init {
        scope.launch { _seed.filterNotNull().collect { seed -> players.forEach { it.apply(seed) } } }
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

    /** The main player's settings as they stand; every game chain follows. Any thread. */
    fun publishSeed(seed: DspSeed) {
        _seed.value = seed
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
            _seed.value?.let { apply(it) }
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
