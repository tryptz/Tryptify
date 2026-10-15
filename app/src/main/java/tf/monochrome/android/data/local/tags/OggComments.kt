package tf.monochrome.android.data.local.tags

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The comments of an Ogg Vorbis or Opus file, by name in upper case: "BPM",
 * "INITIALKEY", "R128_TRACK_GAIN", …
 *
 * jaudiotagger has no Opus reader at all, and its Ogg one also walks to the
 * end of the file for the length. This reads only the stream's first pages: the
 * identification header, then the comment header (the second packet, "\u0003vorbis"
 * or "OpusTags"). Comments longer than [MAX_VALUE_BYTES], embedded cover art
 * mostly, are skipped without being held in memory.
 */
internal object OggComments {

    fun read(file: File): Map<String, String>? = try {
        BufferedInputStream(FileInputStream(file), BUFFER_BYTES).use { read(it) }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** The comments in [input], or null when it is not an Ogg Vorbis or Opus stream. */
    fun read(input: InputStream): Map<String, String>? {
        val packets = Packets(input)
        if (!packets.skipPacket()) return null
        packets.nextPacket()

        val head = packets.readBytes(7) ?: return null
        val vorbis = head[0] == 3.toByte() && String(head, 1, 6, Charsets.US_ASCII) == "vorbis"
        if (!vorbis) {
            val last = packets.readBytes(1) ?: return null
            if (String(head + last, Charsets.US_ASCII) != "OpusTags") return null
        }

        val vendor = packets.readU32() ?: return null
        if (!packets.skip(vendor)) return null
        val count = packets.readU32() ?: return null

        val comments = LinkedHashMap<String, String>()
        var i = 0L
        while (i < count && i < MAX_COMMENTS) {
            val length = packets.readU32() ?: break
            if (length > MAX_VALUE_BYTES) {
                if (!packets.skip(length)) break
            } else {
                val bytes = packets.readBytes(length.toInt()) ?: break
                val text = String(bytes, Charsets.UTF_8)
                val eq = text.indexOf('=')
                if (eq > 0) comments.putIfAbsent(text.substring(0, eq).trim().uppercase(), text.substring(eq + 1))
            }
            i++
        }
        return comments
    }

    /**
     * The packets of the first logical stream, read as a byte stream that ends
     * at each packet's end. A packet is a run of page segments ending with one
     * shorter than 255 bytes, and it may continue across pages.
     */
    private class Packets(private val input: InputStream) {
        private var serial: Int? = null
        private val lacing = IntArray(255)
        private var segments = 0
        private var segment = 0
        private var remaining = 0
        private var endsPacket = false
        private var packetDone = false
        private var pages = 0

        fun nextPacket() {
            packetDone = false
        }

        fun skipPacket(): Boolean {
            while (more()) {
                if (!skipRaw(remaining.toLong())) return false
                remaining = 0
            }
            return packetDone
        }

        fun readU32(): Long? {
            val b = readBytes(4) ?: return null
            return (b[0].toLong() and 0xFF) or
                ((b[1].toLong() and 0xFF) shl 8) or
                ((b[2].toLong() and 0xFF) shl 16) or
                ((b[3].toLong() and 0xFF) shl 24)
        }

        fun readBytes(n: Int): ByteArray? {
            val out = ByteArray(n)
            var at = 0
            while (at < n) {
                if (!more()) return null
                val got = input.read(out, at, minOf(n - at, remaining))
                if (got <= 0) return null
                at += got
                remaining -= got
            }
            return out
        }

        fun skip(n: Long): Boolean {
            var left = n
            while (left > 0) {
                if (!more()) return false
                val step = minOf(left, remaining.toLong())
                if (!skipRaw(step)) return false
                remaining -= step.toInt()
                left -= step
            }
            return true
        }

        /** Whether the current packet has a byte left, loading segments and pages as needed. */
        private fun more(): Boolean {
            while (remaining == 0) {
                if (packetDone) return false
                if (endsPacket) {
                    endsPacket = false
                    packetDone = true
                    return false
                }
                if (segment >= segments && !loadPage()) return false
                val length = lacing[segment++]
                remaining = length
                endsPacket = length < 255
            }
            return true
        }

        private fun loadPage(): Boolean {
            while (pages++ < MAX_PAGES) {
                val header = ByteArray(PAGE_HEADER_BYTES)
                if (!readRaw(header)) return false
                if (String(header, 0, 4, Charsets.US_ASCII) != "OggS") return false
                val pageSerial = (header[14].toInt() and 0xFF) or
                    ((header[15].toInt() and 0xFF) shl 8) or
                    ((header[16].toInt() and 0xFF) shl 16) or
                    ((header[17].toInt() and 0xFF) shl 24)
                val count = header[26].toInt() and 0xFF
                var body = 0L
                for (k in 0 until count) {
                    val v = input.read()
                    if (v < 0) return false
                    lacing[k] = v
                    body += v
                }
                if (serial == null) serial = pageSerial
                if (pageSerial != serial) {
                    // Another logical stream multiplexed in: not ours.
                    if (!skipRaw(body)) return false
                    continue
                }
                segments = count
                segment = 0
                return true
            }
            return false
        }

        private fun readRaw(into: ByteArray): Boolean {
            var at = 0
            while (at < into.size) {
                val got = input.read(into, at, into.size - at)
                if (got <= 0) return false
                at += got
            }
            return true
        }

        /** Skips [n] bytes of the file itself; the packet bookkeeping is the caller's. */
        private fun skipRaw(n: Long): Boolean {
            var left = n
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) {
                    left -= skipped
                } else {
                    if (input.read() < 0) return false
                    left--
                }
            }
            return true
        }
    }

    private const val BUFFER_BYTES = 16 * 1024
    private const val PAGE_HEADER_BYTES = 27
    private const val MAX_VALUE_BYTES = 4096L
    private const val MAX_COMMENTS = 4096L

    // A comment header with several megabytes of cover art spans a few hundred
    // pages; anything longer is not a tag block worth reading.
    private const val MAX_PAGES = 2048
}
