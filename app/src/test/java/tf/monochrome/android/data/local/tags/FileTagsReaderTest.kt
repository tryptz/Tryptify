package tf.monochrome.android.data.local.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A real file read back: its stream, and its tags by name. */
class FileTagsReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    /** One second of 16-bit stereo silence at 44.1 kHz. */
    private fun wav(name: String): File {
        val rate = 44100
        val data = ByteArray(rate * 4)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(rate)
            putInt(rate * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }
        val file = tmp.newFile(name)
        DataOutputStream(FileOutputStream(file)).use { it.write(header.array()); it.write(data) }
        return file
    }

    @Test
    fun `a file's stream and tags, by name`() {
        val file = wav("song.wav")
        val audio = org.jaudiotagger.audio.AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        tag.setField(org.jaudiotagger.tag.FieldKey.TITLE, "A.A.C.S.")
        tag.setField(org.jaudiotagger.tag.FieldKey.ARTIST, "Bioweapon")
        audio.commit()

        val read = FileTagsReader.read(file.path)
        assertNotNull(read)
        read!!
        assertEquals(44100, read.sampleRateHz)
        assertEquals(16, read.bitsPerSample)
        assertEquals(1.0, read.lengthSeconds!!, 0.05)
        assertEquals(file.length(), read.sizeBytes)
        assertTrue(read.tags.toString(), "Title" to "A.A.C.S." in read.tags)
        assertTrue(read.tags.toString(), "Artist" to "Bioweapon" in read.tags)
    }

    @Test
    fun `a cached stream with no extension is known by its first bytes`() {
        val wav = wav("cached.wav")
        val bin = File(tmp.root, "1234_LOSSLESS.bin")
        wav.copyTo(bin)
        val read = FileTagsReader.read(bin.path)
        assertNotNull(read)
        assertEquals(44100, read!!.sampleRateHz)
    }

    @Test
    fun `no file, nothing, and a file nothing can open, its size`() {
        assertNull(FileTagsReader.read(File(tmp.root, "missing.flac").path))
        val junk = tmp.newFile("junk.xyz").apply { writeBytes(ByteArray(10)) }
        val read = FileTagsReader.read(junk.path)
        assertNotNull(read)
        assertEquals(10L, read!!.sizeBytes)
        assertTrue(read.tags.isEmpty())
    }

    @Test
    fun `a stream's format from its first bytes`() {
        fun file(name: String, bytes: ByteArray) = tmp.newFile(name).apply { writeBytes(bytes + ByteArray(16)) }
        assertEquals("flac", FileTagsReader.sniff(file("a.bin", "fLaC".toByteArray())))
        assertEquals("mp3", FileTagsReader.sniff(file("b.bin", "ID3".toByteArray())))
        assertEquals("mp3", FileTagsReader.sniff(file("c.bin", byteArrayOf(0xFF.toByte(), 0xFB.toByte()))))
        assertEquals("m4a", FileTagsReader.sniff(file("d.bin", byteArrayOf(0, 0, 0, 32) + "ftypM4A ".toByteArray())))
        assertEquals("ogg", FileTagsReader.sniff(file("e.bin", "OggS".toByteArray())))
        assertNull(FileTagsReader.sniff(file("f.bin", "nope".toByteArray())))
    }

    @Test
    fun `tag names read as words`() {
        assertEquals("Album artist", FileTagsReader.readableName("ALBUM_ARTIST"))
        assertEquals("Replaygain track gain", FileTagsReader.readableName("REPLAYGAIN_TRACK_GAIN"))
    }
}
