// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Just enough PNG for the skin tests: 8-bit RGBA, not interlaced, which is
 * what tools/stepmania_skin writes. Unit tests compile against android.jar,
 * which has no javax.imageio, and BitmapFactory needs a device; anything else
 * is refused rather than misread.
 */
internal object TestPng {

    class Image(val width: Int, val height: Int, val argb: IntArray)

    fun read(file: File): Image {
        val input = DataInputStream(file.inputStream().buffered())
        val signature = ByteArray(8).also { input.readFully(it) }
        require(signature.contentEquals(SIGNATURE)) { "$file is not a PNG" }
        var width = 0
        var height = 0
        val data = ByteArrayOutputStream()
        input.use {
            while (true) {
                val length = it.readInt()
                val type = String(ByteArray(4).also(it::readFully), Charsets.US_ASCII)
                val body = ByteArray(length).also(it::readFully)
                it.readInt() // CRC
                when (type) {
                    "IHDR" -> {
                        val header = DataInputStream(body.inputStream())
                        width = header.readInt()
                        height = header.readInt()
                        val depth = header.readUnsignedByte()
                        val colour = header.readUnsignedByte()
                        header.readUnsignedByte()
                        header.readUnsignedByte()
                        val interlace = header.readUnsignedByte()
                        require(depth == 8 && colour == 6 && interlace == 0) {
                            "$file: only 8-bit RGBA, not interlaced (got $depth/$colour/$interlace)"
                        }
                    }
                    "IDAT" -> data.write(body)
                    "IEND" -> break
                }
            }
        }
        val stride = width * 4
        val raw = ByteArray((stride + 1) * height)
        Inflater().apply { setInput(data.toByteArray()) }.let { inflater ->
            var read = 0
            while (read < raw.size && !inflater.finished()) read += inflater.inflate(raw, read, raw.size - read)
            inflater.end()
        }
        val pixels = ByteArray(stride * height)
        for (y in 0 until height) {
            val filter = raw[y * (stride + 1)].toInt()
            for (x in 0 until stride) {
                val value = raw[y * (stride + 1) + 1 + x].toInt() and 0xFF
                val left = if (x >= 4) pixels[y * stride + x - 4].toInt() and 0xFF else 0
                val up = if (y > 0) pixels[(y - 1) * stride + x].toInt() and 0xFF else 0
                val upLeft = if (x >= 4 && y > 0) pixels[(y - 1) * stride + x - 4].toInt() and 0xFF else 0
                val predicted = when (filter) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> paeth(left, up, upLeft)
                    else -> error("bad filter $filter")
                }
                pixels[y * stride + x] = (value + predicted).toByte()
            }
        }
        val argb = IntArray(width * height) { i ->
            val r = pixels[i * 4].toInt() and 0xFF
            val g = pixels[i * 4 + 1].toInt() and 0xFF
            val b = pixels[i * 4 + 2].toInt() and 0xFF
            val a = pixels[i * 4 + 3].toInt() and 0xFF
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Image(width, height, argb)
    }

    fun write(file: File, image: Image) {
        val stride = image.width * 4
        val raw = ByteArray((stride + 1) * image.height)
        for (y in 0 until image.height) {
            raw[y * (stride + 1)] = 0
            for (x in 0 until image.width) {
                val c = image.argb[y * image.width + x]
                val o = y * (stride + 1) + 1 + x * 4
                raw[o] = (c shr 16).toByte()
                raw[o + 1] = (c shr 8).toByte()
                raw[o + 2] = c.toByte()
                raw[o + 3] = (c ushr 24).toByte()
            }
        }
        val deflater = Deflater().apply { setInput(raw); finish() }
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()

        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.write(SIGNATURE)
            val header = ByteArrayOutputStream().also { h ->
                DataOutputStream(h).apply {
                    writeInt(image.width)
                    writeInt(image.height)
                    writeByte(8)
                    writeByte(6)
                    writeByte(0)
                    writeByte(0)
                    writeByte(0)
                }
            }.toByteArray()
            chunk(out, "IHDR", header)
            chunk(out, "IDAT", compressed.toByteArray())
            chunk(out, "IEND", ByteArray(0))
        }
    }

    private fun chunk(out: DataOutputStream, type: String, body: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(body.size)
        out.write(typeBytes)
        out.write(body)
        out.writeInt(CRC32().apply { update(typeBytes); update(body) }.value.toInt())
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
}
