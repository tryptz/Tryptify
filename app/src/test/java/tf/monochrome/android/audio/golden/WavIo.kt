package tf.monochrome.android.audio.golden

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/** A decoded WAV: interleaved float samples in [-1, 1], plus how it was stored. */
data class WavData(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    val isFloat: Boolean,
) {
    val frames: Int get() = if (channels == 0) 0 else samples.size / channels

    // Value semantics, because the array field makes the generated ones wrong:
    // two identical fixtures would compare unequal on identity alone.
    override fun equals(other: Any?): Boolean =
        this === other || (other is WavData &&
            sampleRate == other.sampleRate && channels == other.channels &&
            bitDepth == other.bitDepth && isFloat == other.isFloat &&
            samples.contentEquals(other.samples))

    override fun hashCode(): Int =
        ((((samples.contentHashCode() * 31 + sampleRate) * 31 + channels) * 31) +
            bitDepth) * 31 + isFloat.hashCode()
}

/**
 * A RIFF/WAVE reader and writer, for fixtures that can be listened to.
 *
 * A golden fixture kept as a Kotlin array is only inspectable by the test that
 * failed. Kept as a `.wav`, it opens in any editor, which is what turns "the
 * resampler regressed by 3 LSB" into a file whose defect can be seen and heard.
 * [write] is equally the way a failing buffer gets out of a test run and into
 * something that can be looked at.
 *
 * Deliberately small: canonical PCM, 16/24/32-bit integer and 32-bit float,
 * mono or stereo. It reads what this project's fixtures and the usual editors
 * write, including the extensible header those editors emit above 16-bit, and
 * refuses everything else loudly rather than guessing at a layout.
 */
object WavIo {

    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    fun read(file: File): WavData = decode(file.readBytes())

    fun write(file: File, data: WavData) {
        file.parentFile?.mkdirs()
        file.writeBytes(encode(data))
    }

    fun decode(bytes: ByteArray): WavData {
        require(bytes.size >= 12) { "not a WAV: only ${bytes.size} bytes" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(tag(buf, 0) == "RIFF") { "not a WAV: no RIFF tag" }
        require(tag(buf, 8) == "WAVE") { "not a WAV: RIFF form is not WAVE" }

        var format = -1
        var channels = 0
        var sampleRate = 0
        var bitDepth = 0
        var dataAt = -1
        var dataSize = 0

        // Walk the chunks rather than assuming fmt-then-data: real files carry
        // LIST, fact and JUNK chunks in between, and a reader that assumes the
        // canonical order reads metadata as audio.
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = tag(buf, pos)
            val size = buf.getInt(pos + 4)
            require(size >= 0) { "chunk '$id' declares $size bytes" }
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "fmt chunk is $size bytes, needs at least 16" }
                    format = buf.getShort(body).toInt() and 0xFFFF
                    channels = buf.getShort(body + 2).toInt() and 0xFFFF
                    sampleRate = buf.getInt(body + 4)
                    bitDepth = buf.getShort(body + 14).toInt() and 0xFFFF
                    if (format == FORMAT_EXTENSIBLE) {
                        // The real format is the first two bytes of the
                        // subformat GUID, 24 bytes into the extension.
                        require(size >= 40) { "extensible fmt chunk is only $size bytes" }
                        format = buf.getShort(body + 24).toInt() and 0xFFFF
                    }
                }
                "data" -> {
                    dataAt = body
                    // A streamed file can declare more than it holds; trust the
                    // bytes that are actually there.
                    dataSize = minOf(size, bytes.size - body)
                }
            }
            // Chunks are word-aligned: an odd size carries a pad byte that is
            // not counted in the size field.
            pos = body + size + (size and 1)
        }

        require(dataAt >= 0) { "WAV has no data chunk" }
        require(channels > 0) { "WAV declares $channels channels" }
        require(sampleRate > 0) { "WAV declares a sample rate of $sampleRate" }
        val isFloat = when (format) {
            FORMAT_FLOAT -> true
            FORMAT_PCM -> false
            else -> throw IllegalArgumentException(
                "unsupported WAV format $format (only PCM and IEEE float are read)",
            )
        }
        val samples = when {
            isFloat && bitDepth == 32 -> FloatArray(dataSize / 4) { buf.getFloat(dataAt + it * 4) }
            !isFloat && bitDepth == 16 ->
                FloatArray(dataSize / 2) { buf.getShort(dataAt + it * 2) / 32768f }
            !isFloat && bitDepth == 24 -> FloatArray(dataSize / 3) {
                val at = dataAt + it * 3
                val v = (bytes[at].toInt() and 0xFF) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                    (bytes[at + 2].toInt() shl 16) // sign-extends the top byte
                v / 8388608f
            }
            !isFloat && bitDepth == 32 ->
                FloatArray(dataSize / 4) { (buf.getInt(dataAt + it * 4) / 2147483648.0).toFloat() }
            else -> throw IllegalArgumentException(
                "unsupported WAV depth: $bitDepth-bit ${if (isFloat) "float" else "integer"}",
            )
        }
        return WavData(samples, sampleRate, channels, bitDepth, isFloat)
    }

    fun encode(data: WavData): ByteArray {
        require(data.channels > 0) { "channels must be positive" }
        require(data.sampleRate > 0) { "sampleRate must be positive" }
        val bytesPerSample = when {
            data.isFloat -> {
                require(data.bitDepth == 32) { "only 32-bit float is written" }
                4
            }
            data.bitDepth == 16 -> 2
            data.bitDepth == 24 -> 3
            data.bitDepth == 32 -> 4
            else -> throw IllegalArgumentException("unsupported depth ${data.bitDepth}")
        }
        val dataSize = data.samples.size * bytesPerSample
        val out = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(36 + dataSize)
        out.put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(16)
        out.putShort((if (data.isFloat) FORMAT_FLOAT else FORMAT_PCM).toShort())
        out.putShort(data.channels.toShort())
        out.putInt(data.sampleRate)
        out.putInt(data.sampleRate * data.channels * bytesPerSample)
        out.putShort((data.channels * bytesPerSample).toShort())
        out.putShort(data.bitDepth.toShort())
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(dataSize)
        for (v in data.samples) {
            when {
                data.isFloat -> out.putFloat(v)
                data.bitDepth == 16 -> out.putShort(quantize(v, 32768.0, -32768, 32767).toShort())
                data.bitDepth == 24 -> {
                    val q = quantize(v, 8388608.0, -8388608, 8388607)
                    out.put((q and 0xFF).toByte())
                    out.put(((q shr 8) and 0xFF).toByte())
                    out.put(((q shr 16) and 0xFF).toByte())
                }
                else -> out.putInt(quantize(v, 2147483648.0, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
            }
        }
        return out.array()
    }

    /**
     * Round-to-nearest, then clamp.
     *
     * Full scale is asymmetric in two's complement — 32767 up, -32768 down — so
     * a +1.0 sample scaled by 32768 lands one past the top and has to be pulled
     * back. Scaling by 32767 instead would avoid the clamp and quietly attenuate
     * every sample, which is the wrong trade in a harness whose job is to
     * measure attenuation.
     */
    private fun quantize(v: Float, scale: Double, min: Long, max: Long): Long =
        (v.toDouble() * scale).roundToLong().coerceIn(min, max)

    private fun tag(buf: ByteBuffer, at: Int): String {
        val chars = CharArray(4) { (buf.get(at + it).toInt() and 0xFF).toChar() }
        return String(chars)
    }
}
