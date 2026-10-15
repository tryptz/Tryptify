package tf.monochrome.android.data.local.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The comment header of an Ogg Vorbis or Opus stream is found and read, across
 * pages, past cover art and other streams, without reading the audio.
 */
class OggCommentsTest {

    @Test
    fun `Vorbis comments, names in upper case`() {
        val ogg = stream(vorbisHead(), comments(vorbis = true, "bpm=128", "INITIALKEY=8A", "replaygain_track_gain=-6.54 dB"))
        val read = OggComments.read(ByteArrayInputStream(ogg))!!
        assertEquals("128", read["BPM"])
        assertEquals("8A", read["INITIALKEY"])
        assertEquals("-6.54 dB", read["REPLAYGAIN_TRACK_GAIN"])
        assertEquals(MusicalTags(bpm = 128f, camelotKey = 8, rgTrackGain = -6.54f), MusicalTags.from(read))
    }

    @Test
    fun `Opus comments, with its own R128 gains`() {
        val ogg = stream(opusHead(), comments(vorbis = false, "R128_TRACK_GAIN=-2304", "R128_ALBUM_GAIN=-2048", "BPM=174"))
        val tags = MusicalTags.from(OggComments.read(ByteArrayInputStream(ogg))!!)
        assertEquals(MusicalTags(bpm = 174f, r128TrackGain = -2304, r128AlbumGain = -2048), tags)
    }

    @Test
    fun `a comment header spanning pages, cover art first, is still read and the art not kept`() {
        val art = "METADATA_BLOCK_PICTURE=" + "A".repeat(300_000)
        val ogg = stream(opusHead(), comments(vorbis = false, art, "BPM=140", "INITIALKEY=Am"), segmentsPerPage = 7)
        val read = OggComments.read(ByteArrayInputStream(ogg))!!
        assertEquals("140", read["BPM"])
        assertEquals("Am", read["INITIALKEY"])
        assertFalse(read.containsKey("METADATA_BLOCK_PICTURE"))
    }

    @Test
    fun `a packet whose length is a multiple of 255 ends on an empty segment`() {
        // Pad one comment so the whole packet is exactly 2 * 255 bytes.
        val base = comments(vorbis = true, "BPM=120", "X=")
        val padded = comments(vorbis = true, "BPM=120", "X=" + "y".repeat(510 - base.size))
        assertEquals(510, padded.size)
        val read = OggComments.read(ByteArrayInputStream(stream(vorbisHead(), padded, comments(vorbis = true, "BPM=99"))))!!
        assertEquals("120", read["BPM"])
    }

    @Test
    fun `pages of another stream in between are skipped`() {
        val out = ByteArrayOutputStream()
        out.write(page(serial = 1, flags = 2, packetSegments(vorbisHead()), vorbisHead()))
        out.write(page(serial = 9, flags = 2, packetSegments(ByteArray(40)), ByteArray(40)))
        val c = comments(vorbis = true, "BPM=101")
        out.write(page(serial = 1, flags = 0, packetSegments(c), c))
        assertEquals("101", OggComments.read(ByteArrayInputStream(out.toByteArray()))!!["BPM"])
    }

    @Test
    fun `what is not an Ogg Vorbis or Opus stream is null`() {
        assertNull(OggComments.read(ByteArrayInputStream("fLaC and some bytes".toByteArray())))
        assertNull(OggComments.read(ByteArrayInputStream(ByteArray(0))))
        // An Ogg stream whose second packet is something else (FLAC-in-Ogg, say).
        assertNull(OggComments.read(ByteArrayInputStream(stream(vorbisHead(), "\u007fFLAC....".toByteArray()))))
    }

    @Test
    fun `a stream cut off mid-header keeps what it read and does not throw`() {
        val ogg = stream(vorbisHead(), comments(vorbis = true, "BPM=128", "INITIALKEY=" + "z".repeat(3000)))
        val cut = ogg.copyOf(ogg.size - 1000)
        assertEquals("128", OggComments.read(ByteArrayInputStream(cut))!!["BPM"])
    }

    @Test
    fun `read from a file`() {
        val file = File.createTempFile("tags", ".opus")
        try {
            file.writeBytes(stream(opusHead(), comments(vorbis = false, "BPM=88")))
            assertEquals("88", OggComments.read(file)!!["BPM"])
            assertNull(OggComments.read(File(file.path + ".missing")))
        } finally {
            file.delete()
        }
    }

    // ── Building Ogg bytes ─────────────────────────────────────────────

    private fun vorbisHead() = byteArrayOf(1) + "vorbis".toByteArray() + ByteArray(23)
    private fun opusHead() = "OpusHead".toByteArray() + ByteArray(11)

    private fun comments(vorbis: Boolean, vararg entries: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(if (vorbis) byteArrayOf(3) + "vorbis".toByteArray() else "OpusTags".toByteArray())
        val vendor = "test vendor".toByteArray()
        out.write(u32(vendor.size)); out.write(vendor)
        out.write(u32(entries.size))
        for (e in entries) {
            val b = e.toByteArray()
            out.write(u32(b.size)); out.write(b)
        }
        if (vorbis) out.write(1) // framing bit
        return out.toByteArray()
    }

    /** The packets laid out on pages of at most [segmentsPerPage] segments each. */
    private fun stream(vararg packets: ByteArray, segmentsPerPage: Int = 255): ByteArray {
        val segments = ArrayList<Int>()
        val body = ByteArrayOutputStream()
        for (p in packets) {
            segments += packetSegments(p)
            body.write(p)
        }
        val bytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        var at = 0
        var seg = 0
        var continued = false
        while (seg < segments.size) {
            val take = segments.subList(seg, minOf(seg + segmentsPerPage, segments.size))
            val size = take.sum()
            out.write(page(1, (if (continued) 1 else 0) or (if (seg == 0) 2 else 0), take, bytes.copyOfRange(at, at + size)))
            continued = take.last() == 255
            at += size
            seg += take.size
        }
        return out.toByteArray()
    }

    private fun packetSegments(p: ByteArray): List<Int> = List(p.size / 255) { 255 } + (p.size % 255)

    private fun page(serial: Int, flags: Int, segments: List<Int>, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray())
        out.write(0)
        out.write(flags)
        out.write(ByteArray(8))
        out.write(u32(serial))
        out.write(u32(0))
        out.write(ByteArray(4))
        out.write(segments.size)
        segments.forEach { out.write(it) }
        out.write(body)
        return out.toByteArray()
    }

    private fun u32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
