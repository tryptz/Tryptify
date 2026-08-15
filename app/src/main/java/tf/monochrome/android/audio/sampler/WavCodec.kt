// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A small, exact WAV reader and writer for the sampler's own store.
 *
 * Saved samples are the user's files — they should open in any editor, and
 * they should still open in five years — so the store is plain RIFF/WAVE
 * rather than a private container. 24-bit is the write format: 16-bit throws
 * away detail that a pitched-down hit exposes as noise, and 32-bit float
 * doubles the file size for headroom a trimmed one-shot does not have.
 *
 * Reading accepts what other tools write: 8/16/24/32-bit PCM and 32/64-bit
 * IEEE float, any channel count (folded to at most stereo), with the chunk
 * walk skipping anything it does not recognise. That last part matters more
 * than it sounds — LIST/INFO chunks between `fmt ` and `data` are common, and
 * a reader that assumes `data` comes first fails on half the files people
 * actually have.
 *
 * Everything here is pure JVM, which is what lets `WavCodecTest` verify the
 * round trip without a device.
 */
object WavCodec {

    private const val RIFF = 0x46464952 // "RIFF" little-endian
    private const val WAVE = 0x45564157 // "WAVE"
    private const val FMT = 0x20746D66  // "fmt "
    private const val DATA = 0x61746164 // "data"

    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    /** Guard against a malformed header claiming an absurd allocation. */
    const val MAX_FRAMES = 48_000 * 60 * 10

    data class Audio(
        val left: FloatArray,
        val right: FloatArray?,
        val sampleRate: Int,
    ) {
        val frames: Int get() = left.size
        val channelCount: Int get() = if (right == null) 1 else 2

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    // ── writing ─────────────────────────────────────────────────────────

    /**
     * Writes [left]/[right] as 24-bit PCM.
     *
     * Samples are clamped rather than wrapped. A clipped hit sounding a bit
     * hard is a recoverable mistake; the same hit wrapping to the opposite
     * polarity is a burst of noise that sounds like a broken app.
     */
    fun write(target: File, left: FloatArray, right: FloatArray?, sampleRate: Int) {
        target.parentFile?.mkdirs()
        target.outputStream().buffered(1 shl 16).use { write(it, left, right, sampleRate) }
    }

    fun write(out: OutputStream, left: FloatArray, right: FloatArray?, sampleRate: Int) {
        val channels = if (right == null) 1 else 2
        val frames = if (right == null) left.size else minOf(left.size, right.size)
        val bytesPerSample = 3
        val dataBytes = frames * channels * bytesPerSample
        val byteRate = sampleRate * channels * bytesPerSample
        val blockAlign = channels * bytesPerSample

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(RIFF)
        header.putInt(36 + dataBytes)
        header.putInt(WAVE)
        header.putInt(FMT)
        header.putInt(16)                       // PCM fmt chunk size
        header.putShort(FORMAT_PCM.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort((bytesPerSample * 8).toShort())
        header.putInt(DATA)
        header.putInt(dataBytes)
        out.write(header.array())

        val chunk = ByteArray(CHUNK_FRAMES * 2 * bytesPerSample)
        var written = 0
        while (written < frames) {
            val n = minOf(CHUNK_FRAMES, frames - written)
            var p = 0
            for (i in 0 until n) {
                p = put24(chunk, p, left[written + i])
                if (right != null) p = put24(chunk, p, right[written + i])
            }
            out.write(chunk, 0, p)
            written += n
        }
        out.flush()
    }

    private fun put24(target: ByteArray, offset: Int, value: Float): Int {
        val clamped = when {
            value > 1f -> 1f
            value < -1f -> -1f
            else -> value
        }
        val v = (clamped * 8_388_607f).toInt()
        target[offset] = (v and 0xFF).toByte()
        target[offset + 1] = ((v shr 8) and 0xFF).toByte()
        target[offset + 2] = ((v shr 16) and 0xFF).toByte()
        return offset + 3
    }

    // ── reading ─────────────────────────────────────────────────────────

    fun read(source: File, maxFrames: Int = MAX_FRAMES): Audio =
        source.inputStream().buffered(1 shl 16).use { read(it, maxFrames) }

    fun read(input: InputStream, maxFrames: Int = MAX_FRAMES): Audio {
        val riff = ByteArray(12)
        input.readFullyOrThrow(riff, "truncated RIFF header")
        val head = ByteBuffer.wrap(riff).order(ByteOrder.LITTLE_ENDIAN)
        if (head.getInt(0) != RIFF || head.getInt(8) != WAVE) {
            throw IOException("not a RIFF/WAVE file")
        }

        var format = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0

        val chunkHeader = ByteArray(8)
        while (true) {
            if (!input.readFully(chunkHeader)) throw IOException("no data chunk")
            val header = ByteBuffer.wrap(chunkHeader).order(ByteOrder.LITTLE_ENDIAN)
            val id = header.getInt(0)
            // Chunk sizes are unsigned in the spec; a >2 GB WAV is not
            // something this store will ever produce, but reading one as a
            // negative Int would loop forever rather than fail.
            val size = header.getInt(4).toLong() and 0xFFFFFFFFL

            when (id) {
                FMT -> {
                    val fmt = ByteArray(size.toInt().coerceAtLeast(16))
                    input.readFullyOrThrow(fmt, "truncated fmt chunk")
                    val f = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    format = f.getShort(0).toInt() and 0xFFFF
                    channels = f.getShort(2).toInt() and 0xFFFF
                    sampleRate = f.getInt(4)
                    bitsPerSample = f.getShort(14).toInt() and 0xFFFF
                    if (format == FORMAT_EXTENSIBLE && fmt.size >= 26) {
                        // WAVE_FORMAT_EXTENSIBLE hides the real format in the
                        // first two bytes of its GUID.
                        format = f.getShort(24).toInt() and 0xFFFF
                    }
                }

                DATA -> {
                    if (channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0) {
                        throw IOException("data chunk before fmt chunk")
                    }
                    return readSamples(
                        input, format, channels, sampleRate, bitsPerSample, size, maxFrames,
                    )
                }

                else -> input.skipFullyOrThrow(size + (size and 1L))
            }
        }
    }

    private fun readSamples(
        input: InputStream,
        format: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        dataBytes: Long,
        maxFrames: Int,
    ): Audio {
        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample <= 0) throw IOException("unsupported bit depth $bitsPerSample")
        val frameBytes = bytesPerSample * channels
        val available = (dataBytes / frameBytes).toInt()
        val frames = available.coerceAtMost(maxFrames)
        if (frames <= 0) throw IOException("empty data chunk")

        val left = FloatArray(frames)
        // Multichannel is folded to stereo by taking the first two channels.
        // A 5.1 file dropped into the sampler is a mistake rather than a use
        // case; keeping the front pair is the least surprising rescue.
        val stereo = channels >= 2
        val right = if (stereo) FloatArray(frames) else null

        val buffer = ByteArray(CHUNK_FRAMES * frameBytes)
        var done = 0
        while (done < frames) {
            val n = minOf(CHUNK_FRAMES, frames - done)
            val want = n * frameBytes
            val got = input.readUpTo(buffer, want)
            // Decode whatever whole frames arrived, not only whole chunks. A
            // file cut short — a save interrupted by a crash, a partial copy —
            // otherwise loses everything back to the last 4096-frame boundary,
            // which for a one-shot shorter than that means the reader returns
            // nothing at all and the sample looks corrupt rather than short.
            val usable = got / frameBytes
            for (i in 0 until usable) {
                val base = i * frameBytes
                left[done + i] = decode(buffer, base, format, bitsPerSample)
                if (right != null) {
                    right[done + i] = decode(buffer, base + bytesPerSample, format, bitsPerSample)
                }
            }
            done += usable
            if (got < want) break
        }

        return if (done == frames) {
            Audio(left, right, sampleRate)
        } else {
            Audio(left.copyOf(done), right?.copyOf(done), sampleRate)
        }
    }

    private fun decode(bytes: ByteArray, offset: Int, format: Int, bits: Int): Float = when {
        format == FORMAT_FLOAT && bits == 32 -> {
            java.lang.Float.intBitsToFloat(readLeInt(bytes, offset))
        }

        format == FORMAT_FLOAT && bits == 64 -> {
            var raw = 0L
            for (i in 7 downTo 0) raw = (raw shl 8) or (bytes[offset + i].toLong() and 0xFF)
            java.lang.Double.longBitsToDouble(raw).toFloat()
        }

        // 8-bit PCM is unsigned, uniquely among the depths.
        bits == 8 -> ((bytes[offset].toInt() and 0xFF) - 128) / 128f

        bits == 16 -> {
            val v = (bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8)
            v.toShort() / 32768f
        }

        bits == 24 -> {
            var v = (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16)
            if (v and 0x800000 != 0) v = v or -0x1000000  // sign-extend
            v / 8_388_608f
        }

        bits == 32 -> readLeInt(bytes, offset) / 2_147_483_648f

        else -> 0f
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            (bytes[offset + 3].toInt() shl 24)

    // ── stream helpers ──────────────────────────────────────────────────

    /**
     * Reads up to [count] bytes, returning how many actually arrived.
     *
     * `read` is allowed to return fewer bytes than asked for without being at
     * the end of the stream, so a single call is never enough; and the count
     * matters rather than a boolean, because a short read still carries usable
     * audio.
     */
    private fun InputStream.readUpTo(target: ByteArray, count: Int): Int {
        var read = 0
        while (read < count) {
            val n = read(target, read, count - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private fun InputStream.readFully(target: ByteArray, count: Int = target.size): Boolean =
        readUpTo(target, count) == count

    private fun InputStream.readFullyOrThrow(target: ByteArray, message: String) {
        if (!readFully(target)) throw IOException(message)
    }

    private fun InputStream.skipFullyOrThrow(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                // skip() is allowed to return 0 without being at EOF, so fall
                // back to reading rather than spinning.
                if (read() < 0) throw IOException("truncated chunk")
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private const val CHUNK_FRAMES = 4096
}
