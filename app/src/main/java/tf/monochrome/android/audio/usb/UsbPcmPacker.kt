package tf.monochrome.android.audio.usb

import java.nio.ByteBuffer

/**
 * The exclusive USB path's two per-sample loops: the level on integer PCM at
 * the DAC's own depth ([scaleInt]), and float or 16-bit PCM packed into the
 * DAC's subslots with the level and a crossfade tail ([pack]).
 *
 * Both used to read and write the direct buffers one byte at a time, each a
 * bounds-checked ByteBuffer call: up to eight per sample, ~384k samples a
 * second at 192 kHz stereo, for the whole of playback. Now integer input
 * comes in with one bulk copy, the output is built in a plain array and goes
 * out with one bulk put, and only float input is still read per sample (one
 * absolute getFloat, in the buffer's own byte order as before). The bytes
 * that reach the DAC are the same, bit for bit; UsbPcmPackerTest holds both
 * loops to the byte-at-a-time versions they replace.
 *
 * One per sink, used on the audio thread under its write lock. Allocates
 * only when a buffer is bigger than any before it.
 */
internal class UsbPcmPacker {
    private var inBytes = ByteArray(0)
    private var outBytes = ByteArray(0)

    /**
     * [frames] frames of little-endian integer PCM in [bytesPerSample]-byte
     * samples (1 to 4) from [src]'s position, with the gain ramped from
     * [startGain] toward [targetGain] a frame at a time, into [out] from 0.
     * Rounded, not truncated, which would bias every sample toward zero;
     * computed in double, which holds a 32-bit sample exactly where a float
     * does not. [src]'s position is left where it was. Returns [out], its
     * position 0 and its limit the bytes written.
     */
    fun scaleInt(
        src: ByteBuffer,
        frames: Int,
        channels: Int,
        bytesPerSample: Int,
        startGain: Float,
        targetGain: Float,
        rise: Float,
        fall: Float,
        out: ByteBuffer,
    ): ByteBuffer {
        require(bytesPerSample in 1..4) { "bytesPerSample $bytesPerSample" }
        val samples = frames * channels
        val n = samples * bytesPerSample
        val input = readInto(src, n)
        val output = outArray(n)
        val bits = bytesPerSample * 8
        val max = (1L shl (bits - 1)) - 1
        val min = -(1L shl (bits - 1))

        var gain = startGain.toDouble()
        var channel = 0
        var frame = 0
        var o = 0
        for (i in 0 until samples) {
            // Little-endian, the top byte left signed so the sample comes
            // out sign-extended from its own width.
            val sample = when (bytesPerSample) {
                2 -> (input[o].toInt() and 0xFF) or (input[o + 1].toInt() shl 8)
                3 -> (input[o].toInt() and 0xFF) or
                    ((input[o + 1].toInt() and 0xFF) shl 8) or
                    (input[o + 2].toInt() shl 16)
                4 -> (input[o].toInt() and 0xFF) or
                    ((input[o + 1].toInt() and 0xFF) shl 8) or
                    ((input[o + 2].toInt() and 0xFF) shl 16) or
                    (input[o + 3].toInt() shl 24)
                else -> input[o].toInt()
            }
            // Within the sample's own range, so it fits an Int.
            val scaled = Math.round(sample * gain).coerceIn(min, max).toInt()
            output[o] = scaled.toByte()
            if (bytesPerSample > 1) output[o + 1] = (scaled shr 8).toByte()
            if (bytesPerSample > 2) output[o + 2] = (scaled shr 16).toByte()
            if (bytesPerSample > 3) output[o + 3] = (scaled shr 24).toByte()
            o += bytesPerSample
            if (++channel == channels) {
                channel = 0
                frame++
                gain = GainRamp.after(startGain, targetGain, frame, rise, fall).toDouble()
            }
        }
        return publish(output, n, out)
    }

    /**
     * [frames] frames of native-order float PCM — or little-endian 16-bit,
     * with [widen16] — from [src]'s position, packed into [bytesPerSample]-byte
     * subslots (see [floatToSubslotSample] and [int16ToSubslotSample]) with the
     * gain ramped as in [scaleInt] and, when [tail] is given, a crossfade tail
     * mixed in at [tailGain]: one float per sample, indexed like the output.
     * Every sample is bounded before its level ([safeSourceSample]). Writes
     * into [out] from 0, its position 0 and its limit the bytes written, and
     * leaves [src]'s position where it was. True if any sample, the tail's
     * included, was not audio ([looksLikeAudio]).
     */
    fun pack(
        src: ByteBuffer,
        frames: Int,
        channels: Int,
        bytesPerSample: Int,
        widen16: Boolean,
        startGain: Float,
        targetGain: Float,
        rise: Float,
        fall: Float,
        tail: FloatArray?,
        tailGain: Float,
        out: ByteBuffer,
    ): Boolean {
        val samples = frames * channels
        val n = samples * bytesPerSample
        val output = outArray(n)
        val srcPos = src.position()
        val input = if (widen16) readInto(src, samples * 2) else inBytes
        val mixing = tail != null
        var notAudio = false

        var gain = startGain
        var channel = 0
        var frame = 0
        var o = 0
        for (i in 0 until samples) {
            val rawTail = if (tail != null) tail[i] else 0f
            if (mixing && !looksLikeAudio(rawTail, SOURCE_HEADROOM)) notAudio = true
            val mixed = if (mixing) safeSourceSample(rawTail) * tailGain else 0f
            val sample = if (widen16) {
                val at = i shl 1
                val s16 = (input[at].toInt() and 0xFF) or (input[at + 1].toInt() shl 8)
                int16ToSubslotSample(s16, gain, mixed, bytesPerSample)
            } else {
                val raw = src.getFloat(srcPos + (i shl 2))
                if (!looksLikeAudio(raw, SOURCE_HEADROOM)) notAudio = true
                floatToSubslotSample(safeSourceSample(raw) * gain + mixed, bytesPerSample)
            }
            output[o] = sample.toByte()
            if (bytesPerSample > 1) output[o + 1] = (sample shr 8).toByte()
            if (bytesPerSample > 2) output[o + 2] = (sample shr 16).toByte()
            if (bytesPerSample > 3) output[o + 3] = (sample shr 24).toByte()
            o += bytesPerSample
            // One gain per frame, so every channel of a frame moves together.
            if (++channel == channels) {
                channel = 0
                frame++
                gain = GainRamp.after(startGain, targetGain, frame, rise, fall)
            }
        }
        publish(output, n, out)
        return notAudio
    }

    /** [bytes] bytes from [src]'s position, in one bulk copy; the position is put back. */
    private fun readInto(src: ByteBuffer, bytes: Int): ByteArray {
        if (inBytes.size < bytes) inBytes = ByteArray(bytes)
        val pos = src.position()
        src.get(inBytes, 0, bytes)
        src.position(pos)
        return inBytes
    }

    private fun outArray(bytes: Int): ByteArray {
        if (outBytes.size < bytes) outBytes = ByteArray(bytes)
        return outBytes
    }

    private fun publish(output: ByteArray, bytes: Int, out: ByteBuffer): ByteBuffer {
        out.clear()
        out.put(output, 0, bytes)
        out.position(0)
        out.limit(bytes)
        return out
    }
}
