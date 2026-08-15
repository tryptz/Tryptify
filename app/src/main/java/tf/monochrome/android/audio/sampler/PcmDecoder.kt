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

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return drain(extractor, codec, maxFrames)
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
        maxFrames: Int,
    ): WavCodec.Audio {
        val info = MediaCodec.BufferInfo()
        var sampleRate = 48000
        var channels = 1
        var sawOutputFormat = false

        // Accumulate into growable lists of chunks rather than one array we
        // keep reallocating: the final length is not known until the decoder
        // is done, and repeatedly copying a multi-megabyte array is the
        // difference between an import that feels instant and one that does
        // not.
        val chunks = ArrayList<FloatArray>()
        var totalSamples = 0
        var inputDone = false

        while (true) {
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
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val output = codec.outputFormat
                    sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    sawOutputFormat = true
                }

                outputIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        chunks.add(toFloats(buffer, codec.outputFormat))
                        totalSamples += chunks.last().size
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (ended || totalSamples >= maxFrames * channels) break
                }

                // TRY_AGAIN_LATER with input already finished means the decoder
                // has nothing more coming; without this the loop would spin.
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone -> {
                    if (chunks.isNotEmpty()) break
                }
            }
        }

        if (!sawOutputFormat) {
            runCatching {
                sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }

        return deinterleave(chunks, totalSamples, channels.coerceAtLeast(1), sampleRate, maxFrames)
    }

    /**
     * Platform decoders emit 16-bit PCM by default and float only when asked
     * and supported, so both have to be handled.
     */
    private fun toFloats(buffer: ByteBuffer, format: MediaFormat): FloatArray {
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            android.media.AudioFormat.ENCODING_PCM_16BIT
        }
        buffer.order(ByteOrder.nativeOrder())

        return when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                val out = FloatArray(buffer.remaining() / 4)
                for (i in out.indices) out[i] = buffer.float
                out
            }

            else -> {
                val out = FloatArray(buffer.remaining() / 2)
                for (i in out.indices) out[i] = buffer.short / 32768f
                out
            }
        }
    }

    private fun deinterleave(
        chunks: List<FloatArray>,
        totalSamples: Int,
        channels: Int,
        sampleRate: Int,
        maxFrames: Int,
    ): WavCodec.Audio {
        val frames = (totalSamples / channels).coerceAtMost(maxFrames)
        if (frames <= 0) throw IOException("decoder produced no audio")

        val left = FloatArray(frames)
        val right = if (channels >= 2) FloatArray(frames) else null

        var frame = 0
        var withinFrame = 0
        outer@ for (chunk in chunks) {
            var i = 0
            while (i < chunk.size) {
                if (frame >= frames) break@outer
                when (withinFrame) {
                    0 -> left[frame] = chunk[i]
                    1 -> right?.set(frame, chunk[i])
                    // Channels beyond the front pair are dropped; see WavCodec
                    // for why folding a surround file is not worth doing here.
                    else -> Unit
                }
                withinFrame += 1
                if (withinFrame >= channels) {
                    withinFrame = 0
                    frame += 1
                }
                i += 1
            }
        }

        return WavCodec.Audio(left, right, sampleRate)
    }
}
