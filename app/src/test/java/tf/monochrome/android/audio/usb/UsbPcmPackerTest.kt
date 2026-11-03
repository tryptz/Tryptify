package tf.monochrome.android.audio.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * [UsbPcmPacker] against the loops it replaced in LibusbAudioSink, which read
 * and wrote the buffers a byte at a time. Those are copied below as they were,
 * and every case must come out byte for byte the same: these are the bytes
 * the DAC plays, and at unity gain they are the song's own, bit-perfect.
 */
class UsbPcmPackerTest {

    private val gains = listOf(
        // start, target: settled at unity, settled below it, silence, ramps both ways.
        1f to 1f, 0.5f to 0.5f, 0.25f to 0.25f, 0f to 0f,
        0f to 1f, 1f to 0f, 0.3f to 0.9f, 0.9f to 0.3f, 0f to 0.0625f,
    )

    @Test
    fun `integer gain matches the old loop at every width`() {
        val rnd = Random(7)
        for (bytesPerSample in 2..4) for (channels in listOf(1, 2, 3, 8)) for ((start, target) in gains) {
            for (rate in listOf(44_100, 192_000)) {
                val frames = 1 + rnd.nextInt(700)
                val offset = rnd.nextInt(5) * channels * bytesPerSample
                val src = directOf(randomBytes(rnd, offset + frames * channels * bytesPerSample + 7), offset)
                val rise = GainRamp.risePerFrame(target, rate)
                val fall = GainRamp.fallPerFrame(rate)
                val label = "${bytesPerSample}B ${channels}ch $start->$target @$rate"

                val expected = oldApplyGainPcmInt(src, frames, channels, bytesPerSample, start, target, rise, fall)
                val out = scratch(frames * channels * bytesPerSample)
                val got = UsbPcmPacker().scaleInt(src, frames, channels, bytesPerSample, start, target, rise, fall, out)

                assertEquals(label, offset, src.position())
                assertArrayEquals(label, expected, bytesOf(got))
            }
        }
    }

    @Test
    fun `integer gain covers the extremes of each width`() {
        for (bytesPerSample in 2..4) {
            val bits = bytesPerSample * 8
            val values = longArrayOf(
                -(1L shl (bits - 1)), -(1L shl (bits - 1)) + 1, -1, 0, 1,
                (1L shl (bits - 1)) - 2, (1L shl (bits - 1)) - 1,
            )
            val raw = ByteArray(values.size * bytesPerSample)
            values.forEachIndexed { i, v ->
                for (b in 0 until bytesPerSample) raw[i * bytesPerSample + b] = (v shr (8 * b)).toByte()
            }
            for ((start, target) in gains + listOf(1f to 1.5f, 2f to 2f)) {
                val src = directOf(raw, 0)
                val expected = oldApplyGainPcmInt(src, values.size, 1, bytesPerSample, start, target, 1e-3f, 1e-2f)
                val got = UsbPcmPacker().scaleInt(
                    src, values.size, 1, bytesPerSample, start, target, 1e-3f, 1e-2f,
                    scratch(raw.size),
                )
                assertArrayEquals("${bytesPerSample}B $start->$target", expected, bytesOf(got))
            }
        }
    }

    @Test
    fun `float packing matches the old loop, tail or not`() {
        val rnd = Random(11)
        for (bytesPerSample in 2..4) for (channels in listOf(1, 2, 6)) for ((start, target) in gains) {
            for (withTail in listOf(false, true)) for (order in listOf(ByteOrder.nativeOrder(), ByteOrder.BIG_ENDIAN)) {
                val frames = 1 + rnd.nextInt(600)
                val samples = frames * channels
                val offset = rnd.nextInt(4) * channels * 4
                val src = ByteBuffer.allocateDirect(offset + samples * 4 + 8).order(order)
                for (i in 0 until samples) src.putFloat(offset + i * 4, floatSample(rnd))
                src.position(offset)
                src.limit(offset + samples * 4)
                val tail = if (withTail) FloatArray(samples + 3) { floatSample(rnd) } else null
                val tailGain = if (withTail) rnd.nextFloat() else 0f
                val rise = GainRamp.risePerFrame(target, 48_000)
                val fall = GainRamp.fallPerFrame(48_000)
                val label = "${bytesPerSample}B ${channels}ch $start->$target tail=$withTail $order"

                val (expected, expectedNotAudio) = oldPackForUsb(
                    src, frames, channels, bytesPerSample, false, start, target, rise, fall, tail, tailGain,
                )
                val out = scratch(samples * bytesPerSample)
                val notAudio = UsbPcmPacker().pack(
                    src, frames, channels, bytesPerSample, false, start, target, rise, fall, tail, tailGain, out,
                )

                assertEquals(label, offset, src.position())
                assertArrayEquals(label, expected, bytesOf(out))
                assertEquals(label, expectedNotAudio, notAudio)
            }
        }
    }

    @Test
    fun `16-bit widening matches the old loop, tail or not`() {
        val rnd = Random(13)
        for (bytesPerSample in 3..4) for (channels in listOf(1, 2)) for ((start, target) in gains) {
            for (withTail in listOf(false, true)) {
                val frames = 1 + rnd.nextInt(600)
                val samples = frames * channels
                val offset = rnd.nextInt(4) * channels * 2
                // Not native order on purpose: the old loop read 16-bit by hand.
                val src = directOf(randomBytes(rnd, offset + samples * 2 + 5), offset).order(ByteOrder.BIG_ENDIAN)
                val tail = if (withTail) FloatArray(samples) { floatSample(rnd) } else null
                val tailGain = if (withTail) rnd.nextFloat() else 0f
                val rise = GainRamp.risePerFrame(target, 96_000)
                val fall = GainRamp.fallPerFrame(96_000)
                val label = "${bytesPerSample}B ${channels}ch $start->$target tail=$withTail"

                val (expected, expectedNotAudio) = oldPackForUsb(
                    src, frames, channels, bytesPerSample, true, start, target, rise, fall, tail, tailGain,
                )
                val out = scratch(samples * bytesPerSample)
                val notAudio = UsbPcmPacker().pack(
                    src, frames, channels, bytesPerSample, true, start, target, rise, fall, tail, tailGain, out,
                )

                assertEquals(label, offset, src.position())
                assertArrayEquals(label, expected, bytesOf(out))
                assertEquals(label, expectedNotAudio, notAudio)
            }
        }
    }

    @Test
    fun `one packer reused across sizes keeps no stale bytes`() {
        val rnd = Random(17)
        val packer = UsbPcmPacker()
        for (frames in listOf(900, 3, 450, 1, 1200, 7)) {
            val src = directOf(randomBytes(rnd, frames * 2 * 3), 0)
            val expected = oldApplyGainPcmInt(src, frames, 2, 3, 0.7f, 0.7f, 0f, 0f)
            val got = packer.scaleInt(src, frames, 2, 3, 0.7f, 0.7f, 0f, 0f, scratch(frames * 2 * 3 + 64))
            assertEquals(frames * 2 * 3, got.limit())
            assertArrayEquals("$frames frames", expected, bytesOf(got))
        }
    }

    // --- the loops as they were in LibusbAudioSink ---------------------------

    private fun oldApplyGainPcmInt(
        src: ByteBuffer,
        frames: Int,
        outChannels: Int,
        bytesPerSample: Int,
        startGain: Float,
        targetGain: Float,
        rise: Float,
        fall: Float,
    ): ByteArray {
        val samples = frames * outChannels
        val scratch = ByteBuffer.allocateDirect(samples * bytesPerSample).order(ByteOrder.nativeOrder())
        val srcPos = src.position()
        val bits = bytesPerSample * 8
        val max = (1L shl (bits - 1)) - 1
        val min = -(1L shl (bits - 1))

        var gain = startGain.toDouble()
        var channel = 0
        var frame = 0
        var o = 0
        for (i in 0 until samples) {
            val at = srcPos + o
            var raw = 0L
            for (b in 0 until bytesPerSample) {
                raw = raw or ((src.get(at + b).toLong() and 0xFF) shl (8 * b))
            }
            val sample = (raw shl (64 - bits)) shr (64 - bits)
            val scaled = Math.round(sample * gain).coerceIn(min, max)
            for (b in 0 until bytesPerSample) {
                scratch.put(o + b, (scaled shr (8 * b)).toByte())
            }
            o += bytesPerSample
            if (++channel == outChannels) {
                channel = 0
                frame++
                gain = GainRamp.after(startGain, targetGain, frame, rise, fall).toDouble()
            }
        }
        scratch.position(0)
        scratch.limit(samples * bytesPerSample)
        return bytesOf(scratch)
    }

    private fun oldPackForUsb(
        src: ByteBuffer,
        frames: Int,
        outChannels: Int,
        bytesPerSample: Int,
        widen16: Boolean,
        startGain: Float,
        targetGain: Float,
        rise: Float,
        fall: Float,
        tailScratch: FloatArray?,
        tailGain: Float,
    ): Pair<ByteArray, Boolean> {
        val samples = frames * outChannels
        val out = ByteBuffer.allocateDirect(samples * bytesPerSample).order(ByteOrder.nativeOrder())
        val srcPos = src.position()
        val mixing = tailScratch != null
        var notAudio = false

        var o = 0
        var gain = startGain
        var channel = 0
        var frame = 0
        for (i in 0 until samples) {
            val rawTail = if (mixing) tailScratch!![i] else 0f
            if (mixing && !looksLikeAudio(rawTail, SOURCE_HEADROOM)) notAudio = true
            val tail = if (mixing) safeSourceSample(rawTail) * tailGain else 0f
            val sample = if (widen16) {
                val at = srcPos + (i shl 1)
                val s16 = (src.get(at).toInt() and 0xFF) or (src.get(at + 1).toInt() shl 8)
                int16ToSubslotSample(s16, gain, tail, bytesPerSample)
            } else {
                val raw = src.getFloat(srcPos + (i shl 2))
                if (!looksLikeAudio(raw, SOURCE_HEADROOM)) notAudio = true
                floatToSubslotSample(safeSourceSample(raw) * gain + tail, bytesPerSample)
            }
            out.put(o, sample.toByte())
            if (bytesPerSample > 1) out.put(o + 1, (sample shr 8).toByte())
            if (bytesPerSample > 2) out.put(o + 2, (sample shr 16).toByte())
            if (bytesPerSample > 3) out.put(o + 3, (sample shr 24).toByte())
            o += bytesPerSample
            if (++channel == outChannels) {
                channel = 0
                frame++
                gain = GainRamp.after(startGain, targetGain, frame, rise, fall)
            }
        }
        out.position(0)
        out.limit(samples * bytesPerSample)
        return bytesOf(out) to notAudio
    }

    // --- helpers -------------------------------------------------------------

    private fun randomBytes(rnd: Random, n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    /** Mostly music, some full scale and past it, the odd NaN or infinity. */
    private fun floatSample(rnd: Random): Float = when (rnd.nextInt(40)) {
        0 -> Float.NaN
        1 -> Float.POSITIVE_INFINITY
        2 -> Float.NEGATIVE_INFINITY
        3 -> 1e30f
        4 -> 1f
        5 -> -1f
        6 -> rnd.nextFloat() * 6f - 3f
        else -> rnd.nextFloat() * 2f - 1f
    }

    private fun directOf(bytes: ByteArray, position: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        b.put(bytes)
        b.position(position)
        return b
    }

    private fun scratch(bytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    /** Position to limit, without moving [b]. */
    private fun bytesOf(b: ByteBuffer): ByteArray {
        val d = b.duplicate()
        val out = ByteArray(d.remaining())
        d.get(out)
        return out
    }
}
