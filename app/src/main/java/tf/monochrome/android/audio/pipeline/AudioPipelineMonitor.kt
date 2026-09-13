package tf.monochrome.android.audio.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two facts only the playback service can see.
 *
 * `PlayerViewModel` reaches the player through a `MediaController`, and a
 * MediaController carries neither `Format` nor any decoder identity — so the
 * stream's real bitrate, its PCM depth, and the name of the codec doing the
 * work are invisible to every screen in the app. The service can see all
 * three, through an `AnalyticsListener`.
 *
 * The service runs in this process (no `android:process` in the manifest), so
 * a singleton is the whole bridge, in the same shape as `PlaybackStateRepository`
 * and `SpectrumAnalyzerTap` already use for the same boundary.
 *
 * Written from the player's application thread, read from composition.
 */
@Singleton
class AudioPipelineMonitor @Inject constructor() {

    private val _stream = MutableStateFlow<DecodedStream?>(null)
    val stream: StateFlow<DecodedStream?> = _stream.asStateFlow()

    private val _decoderName = MutableStateFlow<String?>(null)
    val decoderName: StateFlow<String?> = _decoderName.asStateFlow()

    fun onStreamFormat(stream: DecodedStream) {
        _stream.value = stream
    }

    fun onDecoderInitialized(name: String) {
        _decoderName.value = name.takeIf { it.isNotBlank() }
    }

    /**
     * Forget the decoder but keep the format.
     *
     * A decoder is released between tracks and re-initialised for the next
     * one, and the gap is real: for that moment the app genuinely does not
     * know what will decode the next track, and the panel should say so
     * rather than keep the last track's answer on screen. The format is left
     * because Media3 reports the new one before the old decoder goes.
     */
    fun onDecoderReleased() {
        _decoderName.value = null
    }

    /** Playback stopped. Nothing is flowing, so nothing is known. */
    fun onIdle() {
        _stream.value = null
        _decoderName.value = null
    }
}
