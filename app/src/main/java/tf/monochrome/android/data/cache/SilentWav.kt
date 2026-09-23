package tf.monochrome.android.data.cache

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Byte layout of a silent PCM WAV, generated on demand rather than stored.
 *
 * 44.1 kHz / 16-bit / stereo is deliberate even though every sample is zero:
 * it is the one format every stage downstream already handles — the DSP chain,
 * the resampler and a bit-perfect USB DAC alike. A cheaper 8 kHz or 8-bit
 * stream would be smaller, but nothing here is stored, and an unusual rate is
 * exactly what a USB DAC may refuse to open.
 *
 * Pure JVM so the header and the offset arithmetic can be unit tested.
 */
object SilentWav {

    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2
    const val BITS_PER_SAMPLE = 16
    const val HEADER_SIZE = 44

    private const val BLOCK_ALIGN = CHANNELS * BITS_PER_SAMPLE / 8
    private const val BYTE_RATE = SAMPLE_RATE * BLOCK_ALIGN

    // RIFF sizes are unsigned 32-bit. Six hours of 44.1/16/2 is ~3.8 GB, just
    // under the 4 GiB ceiling, and far longer than any track.
    private const val MAX_DURATION_MS = 6L * 60 * 60 * 1000

    /** Size of the PCM payload for [durationMs], whole frames only. */
    fun dataSize(durationMs: Long): Long {
        val clamped = durationMs.coerceIn(0L, MAX_DURATION_MS)
        val frames = clamped * SAMPLE_RATE / 1000
        return frames * BLOCK_ALIGN
    }

    /** Header plus payload: the byte length of the whole file. */
    fun totalSize(durationMs: Long): Long = HEADER_SIZE + dataSize(durationMs)

    /** The canonical 44-byte RIFF/WAVE header for a payload of [dataSize] bytes. */
    fun header(dataSize: Long): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt((36 + dataSize).toInt())          // RIFF chunk size (uint32; fits by the clamp above)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)                                // fmt chunk size for PCM
        buf.putShort(1)                               // audio format: PCM
        buf.putShort(CHANNELS.toShort())
        buf.putInt(SAMPLE_RATE)
        buf.putInt(BYTE_RATE)
        buf.putShort(BLOCK_ALIGN.toShort())
        buf.putShort(BITS_PER_SAMPLE.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize.toInt())
        return buf.array()
    }

    /**
     * Writes [length] bytes of the file, starting at file offset [position],
     * into [target] at [offset]. The header bytes come from [header]; every
     * byte after it is silence (zero).
     */
    fun fill(header: ByteArray, position: Long, target: ByteArray, offset: Int, length: Int) {
        var written = 0
        if (position < HEADER_SIZE) {
            val fromHeader = minOf(length.toLong(), HEADER_SIZE - position).toInt()
            System.arraycopy(header, position.toInt(), target, offset, fromHeader)
            written = fromHeader
        }
        if (written < length) {
            java.util.Arrays.fill(target, offset + written, offset + length, 0.toByte())
        }
    }
}
