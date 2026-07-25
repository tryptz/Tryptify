package tf.monochrome.android.audio.atmos

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * A [MediaSource.Factory] that wraps a delegate factory and taps every raw
 * E-AC-3 audio access unit, recording it into an [AtmosFrameBuffer] before
 * NextLib's FfmpegAudioRenderer decodes it. This is how the Atmos side-data
 * reaches the [AtmosAudioProcessor]: the renderer keeps decoding the core to bed
 * PCM as usual, while the tap preserves the raw frames (which carry the JOC/OAMD
 * the decoder discards) keyed by presentation time.
 *
 * Wire it on the player:
 * ```
 * ExoPlayer.Builder(context, renderersFactory)
 *     .setMediaSourceFactory(
 *         AtmosTapMediaSourceFactory(DefaultMediaSourceFactory(context), atmosFrameBuffer))
 * ```
 * Inert for non-E-AC-3 tracks (only E-AC-3 sample streams are wrapped).
 */
@UnstableApi
class AtmosTapMediaSourceFactory(
    private val delegate: MediaSource.Factory,
    private val frameBuffer: AtmosFrameBuffer,
) : MediaSource.Factory {

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        delegate.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        delegate.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        TapMediaSource(delegate.createMediaSource(mediaItem), frameBuffer)

    /**
     * Wraps an already-built [MediaSource] with the same tap. Used for the
     * playback paths that construct a source directly (DASH / progressive) and
     * therefore never go through [createMediaSource].
     */
    fun wrap(source: MediaSource): MediaSource = TapMediaSource(source, frameBuffer)
}

@UnstableApi
private class TapMediaSource(
    child: MediaSource,
    private val frameBuffer: AtmosFrameBuffer,
) : WrappingMediaSource(child) {

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = TapMediaPeriod(
        mediaSource.createPeriod(id, allocator, startPositionUs), frameBuffer)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        mediaSource.releasePeriod((mediaPeriod as TapMediaPeriod).delegate)
    }
}

/** Forwards every [MediaPeriod] call, wrapping E-AC-3 sample streams with a tap. */
@UnstableApi
private class TapMediaPeriod(
    val delegate: MediaPeriod,
    private val frameBuffer: AtmosFrameBuffer,
) : MediaPeriod, MediaPeriod.Callback {

    private var callback: MediaPeriod.Callback? = null

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        delegate.prepare(this, positionUs)
    }

    // MediaPeriod.Callback (delegate -> us): re-emit with `this` so the player
    // keeps seeing the wrapper, not the delegate period.
    override fun onPrepared(mediaPeriod: MediaPeriod) {
        callback?.onPrepared(this)
    }

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        callback?.onContinueLoadingRequested(this)
    }

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        val result = delegate.selectTracks(
            selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs)
        for (i in streams.indices) {
            val stream = streams[i]
            if (stream != null && stream !is TapSampleStream && isEac3(selections[i])) {
                streams[i] = TapSampleStream(stream, frameBuffer)
            }
        }
        return result
    }

    override fun maybeThrowPrepareError() = delegate.maybeThrowPrepareError()
    override fun getTrackGroups(): TrackGroupArray = delegate.trackGroups
    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) =
        delegate.discardBuffer(positionUs, toKeyframe)
    override fun readDiscontinuity(): Long = delegate.readDiscontinuity()
    override fun seekToUs(positionUs: Long): Long = delegate.seekToUs(positionUs)
    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        delegate.getAdjustedSeekPositionUs(positionUs, seekParameters)
    override fun getBufferedPositionUs(): Long = delegate.bufferedPositionUs
    override fun getNextLoadPositionUs(): Long = delegate.nextLoadPositionUs
    override fun continueLoading(loadingInfo: LoadingInfo): Boolean =
        delegate.continueLoading(loadingInfo)
    override fun isLoading(): Boolean = delegate.isLoading
    override fun reevaluateBuffer(positionUs: Long) = delegate.reevaluateBuffer(positionUs)

    private fun isEac3(selection: ExoTrackSelection?): Boolean {
        val mime = selection?.selectedFormat?.sampleMimeType ?: return false
        return mime == MimeTypes.AUDIO_E_AC3 || mime == MimeTypes.AUDIO_E_AC3_JOC
    }
}

/** Copies each decoded-through sample's raw bytes into the frame buffer. */
@UnstableApi
private class TapSampleStream(
    val delegate: SampleStream,
    private val frameBuffer: AtmosFrameBuffer,
) : SampleStream {

    override fun isReady(): Boolean = delegate.isReady()
    override fun maybeThrowError() = delegate.maybeThrowError()
    override fun skipData(positionUs: Long): Int = delegate.skipData(positionUs)

    override fun readData(
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: Int,
    ): Int {
        val result = delegate.readData(formatHolder, buffer, readFlags)
        if (result == C.RESULT_BUFFER_READ &&
            readFlags and SampleStream.FLAG_PEEK == 0 &&
            readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA == 0 &&
            !buffer.isEndOfStream
        ) {
            val data = buffer.data
            if (data != null) {
                // Read via a duplicate so the renderer's view is untouched.
                //
                // The buffer is still in WRITE state here — readData has filled
                // it but the renderer flips it only after we return. So position
                // is the sample size and limit is the capacity; reading
                // remaining() would yield the uninitialized tail (measured: a
                // constant 94 bytes of slack on CBR E-AC-3), not the frame.
                // Flip the duplicate to span [0, bytesWritten). Guard the
                // already-flipped case so we never produce an empty capture.
                val dup = data.duplicate()
                if (dup.position() > 0) dup.flip()
                val bytes = ByteArray(dup.remaining())
                dup.get(bytes)
                if (bytes.isNotEmpty()) {
                    frameBuffer.put(buffer.timeUs, bytes)
                    if (!logged) {
                        logged = true
                        android.util.Log.i(
                            "AtmosTap",
                            "capturing E-AC-3 frames (first ${bytes.size} bytes @ ${buffer.timeUs}us)" +
                                " buffer@${System.identityHashCode(frameBuffer)}")
                    }
                }
            }
        }
        return result
    }

    private var logged = false
}
