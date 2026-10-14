// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes an arbitrary audio file to float PCM, for importing samples the user
 * already has.
 *
 * Captures never come through here — they are already PCM by the time the tap
 * sees them — so this exists purely so "add a sample from a file" accepts an
 * MP3 or an M4A rather than only WAV. It is a plain MediaExtractor +
 * MediaCodec loop rather than anything from Media3: the app's ExoPlayer
 * instance is busy playing music, and borrowing it to decode a file in the
 * background would mean either a second player or interfering with the first.
 *
 * Bounded on purpose. [maxFrames] stops a two-hour podcast from being pulled
 * into a float array; the sampler's job is one-shots and short loops.
 */
object PcmDecoder {

    private const val TIMEOUT_US = 10_000L

    /** What [stream] found before the first block: the output's shape and the file's length. */
    class StreamFormat(
        val sampleRate: Int,
        val channels: Int,
        /** The track's declared length, or 0 when the container does not say. */
        val durationUs: Long,
    ) {
        /** [durationUs] in frames at [sampleRate]; 0 when unknown. */
        val estimatedFrames: Long
            get() = if (durationUs <= 0L) 0L else durationUs * sampleRate / 1_000_000L
    }

    /**
     * Where [stream] hands each decoded block, its channels already split.
     *
     * The arrays are reused for the next block: whatever is kept has to be
     * copied out. [right] is null for a mono file. Channels beyond the front
     * pair are dropped, as in [WavCodec].
     */
    fun interface BlockSink {
        fun onBlock(left: FloatArray, right: FloatArray?, frames: Int)
    }

    /**
     * Reads [uri] to at most [maxFrames] frames of float PCM.
     *
     * @throws IOException when the file has no audio track or the platform
     *   decoder refuses it — the caller surfaces that as "couldn't read this
     *   file" rather than crashing the import.
     */
    fun decode(
        context: Context,
        uri: Uri,
        maxFrames: Int = 48_000 * 60,
    ): WavCodec.Audio {
        // Filled straight from the blocks. This used to keep every decoded
        // block, interleaved, and only then split them into left and right —
        // two whole copies of the file alive at once, which for a long song is
        // most of the heap.
        var left = FloatArray(0)
        var right: FloatArray? = null
        var frames = 0
        var sampleRate = 48_000
        stream(
            context = context,
            uri = uri,
            maxFrames = maxFrames.toLong(),
            onFormat = { format ->
                sampleRate = format.sampleRate
                // Sized from the declared length where there is one, so a
                // well-formed file is never copied to grow.
                val expected = format.estimatedFrames.coerceIn(0L, maxFrames.toLong()).toInt()
                left = FloatArray(expected)
                right = if (format.channels >= 2) FloatArray(expected) else null
            },
        ) { blockLeft, blockRight, count ->
            if (frames + count > left.size) {
                val grown = maxOf(frames + count, left.size + left.size / 2, 4096).coerceAtMost(maxFrames)
                left = left.copyOf(grown)
                right = right?.copyOf(grown)
            }
            System.arraycopy(blockLeft, 0, left, frames, count)
            val r = right
            if (r != null && blockRight != null) System.arraycopy(blockRight, 0, r, frames, count)
            frames += count
        }
        if (frames <= 0) throw IOException("decoder produced no audio")
        if (frames < left.size) {
            left = left.copyOf(frames)
            right = right?.copyOf(frames)
        }
        return WavCodec.Audio(left, right, sampleRate)
    }

    /**
     * Decodes [uri] a block at a time into [sink], holding no more of the file
     * than one block.
     *
     * For anything that only needs to look at the audio once — a waveform, a
     * rhythm envelope — this is the way to read a whole song: memory stays the
     * size of a block whatever the song's length, where [decode] needs the
     * whole song at once. Stops after [maxFrames], and as soon as [isActive]
     * says the caller has gone, between blocks: a decode the user has moved on
     * from is not left running to the end in the background.
     *
     * @return the number of frames delivered.
     * @throws IOException as [decode] does.
     */
    fun stream(
        context: Context,
        uri: Uri,
        maxFrames: Long = Long.MAX_VALUE,
        isActive: () -> Boolean = { true },
        onFormat: (StreamFormat) -> Unit = {},
        sink: BlockSink,
    ): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw IOException("no audio track in $uri")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("track has no MIME type")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return drain(extractor, codec, durationUs, maxFrames, isActive, onFormat, sink)
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun drain(
        extractor: MediaExtractor,
        codec: MediaCodec,
        durationUs: Long,
        maxFrames: Long,
        isActive: () -> Boolean,
        onFormat: (StreamFormat) -> Unit,
        sink: BlockSink,
    ): Long {
        val info = MediaCodec.BufferInfo()
        var announced = false
        var delivered = 0L
        var inputDone = false
        // One block's worth, reused for every block.
        var left = FloatArray(0)
        var right = FloatArray(0)

        while (isActive()) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex)
                    val read = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                    if (read < 0) {
                        codec.queueInputBuffer(
                            inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, read, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outputIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    var ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (buffer != null && info.size > 0) {
                        val output = codec.outputFormat
                        val sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        if (!announced) {
                            onFormat(StreamFormat(sampleRate, channels, durationUs))
                            announced = true
                        }
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val isFloat = output.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                            android.media.AudioFormat.ENCODING_PCM_FLOAT
                        buffer.order(ByteOrder.nativeOrder())
                        val bytesPerSample = if (isFloat) 4 else 2
                        var frames = buffer.remaining() / (bytesPerSample * channels)
                        if (delivered + frames > maxFrames) frames = (maxFrames - delivered).toInt()
                        if (frames > left.size) {
                            left = FloatArray(frames)
                            right = FloatArray(frames)
                        }
                        split(buffer, isFloat, channels, frames, left, right)
                        if (frames > 0) sink.onBlock(left, if (channels >= 2) right else null, frames)
                        delivered += frames
                        if (delivered >= maxFrames) ended = true
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (ended) break
                }

                // TRY_AGAIN_LATER with input already finished means the decoder
                // has nothing more coming; without this the loop would spin.
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone -> {
                    if (delivered > 0) break
                }
            }
        }
        return delivered
    }

    /**
     * One output buffer's samples into [left] and [right], read in place.
     *
     * Platform decoders emit 16-bit PCM by default and float only when asked
     * and supported, so both have to be handled. Channels past the front pair
     * are skipped.
     */
    private fun split(
        buffer: ByteBuffer,
        isFloat: Boolean,
        channels: Int,
        frames: Int,
        left: FloatArray,
        right: FloatArray,
    ) {
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                val sample = if (isFloat) buffer.float else buffer.short / 32768f
                when (channel) {
                    0 -> left[frame] = sample
                    1 -> right[frame] = sample
                }
            }
        }
    }
}
