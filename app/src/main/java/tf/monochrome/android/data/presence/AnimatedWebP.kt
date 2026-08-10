package tf.monochrome.android.data.presence

import java.io.ByteArrayOutputStream

/**
 * Assemble an animated WebP out of still ones.
 *
 * Android can encode a single WebP — `Bitmap.compress(WEBP_LOSSY, …)` — and
 * cannot encode an animated anything. There is no platform API for it: the
 * `ImageDecoder` / `AnimatedImageDrawable` pair decodes animations and never
 * writes them, and `MediaCodec` produces video containers that Discord will not
 * render in an asset slot. That is the whole obstacle to drawing a spectrum over
 * the album art, because the art changes every track and so the image has to be
 * made on the device.
 *
 * The way round it is that WebP's animation support lives in the *container*,
 * not in the codec. An animated file is a RIFF wrapper holding a VP8X header, an
 * ANIM chunk, and one ANMF chunk per frame — and each ANMF simply contains the
 * ordinary compressed bitstream that a still WebP already carries. So the frames
 * can be encoded one at a time by the platform, and this stitches them together
 * without ever touching a pixel.
 *
 * Written against the format rather than a library because there is no library:
 * every animated-WebP encoder for Android is a native build of libwebp, which is
 * several megabytes of .so per ABI to write a header this file writes in a
 * hundred lines.
 *
 * Verified by round-tripping the output through a decoder before this was
 * committed — the structure is unforgiving and a wrong length field produces a
 * file that is silently skipped rather than rejected.
 */
object AnimatedWebP {

    /**
     * @param frames each a complete still WebP, as `Bitmap.compress` writes them
     * @param durationsMs how long each frame is shown; same length as [frames]
     * @param loopCount 0 means forever, which is what a badge wants
     */
    fun assemble(
        frames: List<ByteArray>,
        durationsMs: List<Int>,
        width: Int,
        height: Int,
        loopCount: Int = 0,
    ): ByteArray {
        require(frames.isNotEmpty()) { "an animation needs at least one frame" }
        require(frames.size == durationsMs.size) { "every frame needs a duration" }
        // The canvas fields are 24-bit and stored minus one, so this is the real
        // ceiling rather than an arbitrary guard.
        require(width in 1..(1 shl 24) && height in 1..(1 shl 24)) { "canvas out of range" }

        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))

        // VP8X: the extended header that says this file is more than one image.
        // Only the animation bit is set — alpha is declared per frame by the
        // frame's own bitstream, and claiming it here for frames that lack it
        // makes decoders read past the data.
        val vp8x = ByteArrayOutputStream()
        vp8x.write(byteArrayOf(FLAG_ANIMATION, 0, 0, 0))
        vp8x.write(uint24(width - 1))
        vp8x.write(uint24(height - 1))
        body.write(chunk("VP8X", vp8x.toByteArray()))

        // ANIM: background colour, then the loop count.
        val anim = ByteArrayOutputStream()
        anim.write(byteArrayOf(0, 0, 0, 0))
        anim.write(uint16(loopCount))
        body.write(chunk("ANIM", anim.toByteArray()))

        for ((index, frame) in frames.withIndex()) {
            val bitstream = codecChunk(frame)
                ?: error("frame $index is not a WebP this can read")
            val anmf = ByteArrayOutputStream()
            // Offsets are in units of two pixels and must be even; every frame
            // here covers the whole canvas, so both are zero.
            anmf.write(uint24(0))
            anmf.write(uint24(0))
            anmf.write(uint24(width - 1))
            anmf.write(uint24(height - 1))
            anmf.write(uint24(durationsMs[index].coerceAtLeast(1)))
            // Blend over the previous frame, dispose to nothing. Full-canvas
            // opaque frames make both choices moot, and these are the values a
            // decoder assumes when it has no reason to think otherwise.
            anmf.write(byteArrayOf(0))
            anmf.write(chunk(bitstream.first, bitstream.second))
            body.write(chunk("ANMF", anmf.toByteArray()))
        }

        val payload = body.toByteArray()
        val out = ByteArrayOutputStream(payload.size + 8)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(uint32(payload.size))
        out.write(payload)
        return out.toByteArray()
    }

    /**
     * Pull the compressed image out of a still WebP.
     *
     * A file from `Bitmap.compress` is a RIFF holding one `VP8 ` (lossy) or
     * `VP8L` (lossless) chunk, sometimes behind a VP8X of its own if the encoder
     * decided it needed one. Walking the chunk list rather than assuming a fixed
     * offset is what makes this survive either.
     */
    internal fun codecChunk(webp: ByteArray): Pair<String, ByteArray>? {
        if (webp.size < 12) return null
        if (String(webp, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(webp, 8, 4, Charsets.US_ASCII) != "WEBP") return null

        var pos = 12
        while (pos + 8 <= webp.size) {
            val fourcc = String(webp, pos, 4, Charsets.US_ASCII)
            val size = readUint32(webp, pos + 4)
            val start = pos + 8
            if (size < 0 || start + size > webp.size) return null
            if (fourcc == "VP8 " || fourcc == "VP8L") {
                return fourcc to webp.copyOfRange(start, start + size)
            }
            // Chunks are padded to an even length, and the pad byte is not
            // counted in the size field.
            pos = start + size + (size and 1)
        }
        return null
    }

    private fun chunk(fourcc: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 9)
        out.write(fourcc.toByteArray(Charsets.US_ASCII))
        out.write(uint32(payload.size))
        out.write(payload)
        if (payload.size and 1 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun uint16(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
    )

    private fun uint24(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
    )

    private fun uint32(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private fun readUint32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private const val FLAG_ANIMATION: Byte = 0x02
}
