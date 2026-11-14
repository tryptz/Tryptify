package tf.monochrome.android.audio.atmos

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tapped period when the player selects tracks a second time on a playing
 * song (turning an Atmos setting on or off does). The period under the tap
 * casts the streams it is asked to keep to its own class, as Media3's
 * ProgressiveMediaPeriod does: handed the tap's wrappers it threw, and the
 * song started over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AtmosTapPeriodTest {

    private class OwnStream : SampleStream {
        override fun isReady() = true
        override fun maybeThrowError() = Unit
        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int) = C.RESULT_NOTHING_READ
        override fun skipData(positionUs: Long) = 0
    }

    /** Keeps a stream it is told to keep (and checks it is its own), makes one otherwise. */
    private class Period : MediaPeriod {
        var made = 0
        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long,
        ): Long {
            for (i in streams.indices) {
                val kept = streams[i]
                if (kept != null) kept as OwnStream  // ProgressiveMediaPeriod's cast
                if (selections[i] == null || !mayRetainStreamFlags[i]) streams[i] = null
                if (streams[i] == null && selections[i] != null) {
                    streams[i] = OwnStream()
                    streamResetFlags[i] = true
                    made++
                }
            }
            return positionUs
        }

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) = Unit
        override fun maybeThrowPrepareError() = Unit
        override fun getTrackGroups(): TrackGroupArray = TrackGroupArray.EMPTY
        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit
        override fun readDiscontinuity() = C.TIME_UNSET
        override fun seekToUs(positionUs: Long) = positionUs
        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters) = positionUs
        override fun getBufferedPositionUs() = 0L
        override fun getNextLoadPositionUs() = 0L
        override fun continueLoading(loadingInfo: LoadingInfo) = false
        override fun isLoading() = false
        override fun reevaluateBuffer(positionUs: Long) = Unit
    }

    private fun selection(mime: String): ExoTrackSelection =
        FixedTrackSelection(TrackGroup(Format.Builder().setSampleMimeType(mime).setChannelCount(6).build()), 0)

    @Test
    fun `tracks selected again on a playing song keep their stream and its tap`() {
        val inner = Period()
        val tap = TapMediaPeriod(inner, AtmosFrameBuffer())
        val selections = arrayOf<ExoTrackSelection?>(selection(MimeTypes.AUDIO_E_AC3_JOC))
        val streams = arrayOfNulls<SampleStream>(1)
        tap.selectTracks(selections, booleanArrayOf(false), streams, BooleanArray(1), 0L)
        val first = streams[0]
        assertTrue(first is TapSampleStream)

        // The audio track taken out (an Atmos setting changed)...
        tap.selectTracks(arrayOfNulls(1), booleanArrayOf(true), streams, BooleanArray(1), 0L)
        assertNull(streams[0])
        // ...and put back: a new stream, tapped.
        tap.selectTracks(selections, booleanArrayOf(true), streams, BooleanArray(1), 0L)
        assertTrue(streams[0] is TapSampleStream)
        assertEquals(2, inner.made)

        // Selected again with nothing changed: the same wrapper, the same stream under it.
        val kept = streams[0]
        tap.selectTracks(selections, booleanArrayOf(true), streams, BooleanArray(1), 0L)
        assertSame(kept, streams[0])
        assertEquals(2, inner.made)
    }

    @Test
    fun `only E-AC-3 is tapped`() {
        val tap = TapMediaPeriod(Period(), AtmosFrameBuffer())
        val streams = arrayOfNulls<SampleStream>(2)
        tap.selectTracks(
            arrayOf(selection(MimeTypes.AUDIO_FLAC), selection(MimeTypes.AUDIO_E_AC3)),
            BooleanArray(2), streams, BooleanArray(2), 0L,
        )
        assertTrue(streams[0] is OwnStream)
        assertTrue(streams[1] is TapSampleStream)
    }
}
