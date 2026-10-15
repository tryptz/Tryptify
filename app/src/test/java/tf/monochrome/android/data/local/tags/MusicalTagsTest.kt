package tf.monochrome.android.data.local.tags

import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.ID3v23Frame
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tempo, key and gain tags are read from every format's spelling of them, and junk is no value. */
class MusicalTagsTest {

    @Test
    fun `a tempo is the leading number, within a sane band`() {
        assertEquals(128f, MusicalTags.parseBpm("128"))
        assertEquals(127.53f, MusicalTags.parseBpm("127.53"))
        assertEquals(128.5f, MusicalTags.parseBpm("128,5"))
        assertEquals(174f, MusicalTags.parseBpm(" 174 BPM"))
        assertEquals(126.67f, MusicalTags.parseBpm("126.666666"))
        for (junk in listOf(null, "", "0", "-120", "N/A", "Allegro", "12", "999", "BPM 128")) {
            assertNull("$junk", MusicalTags.parseBpm(junk))
        }
    }

    @Test
    fun `a ReplayGain gain is dB with or without its unit`() {
        assertEquals(-6.54f, MusicalTags.parseGain("-6.54 dB"))
        assertEquals(2.1f, MusicalTags.parseGain("+2.10 dB"))
        assertEquals(-3.2f, MusicalTags.parseGain("−3,2 dB"))
        assertEquals(0f, MusicalTags.parseGain("0.00 dB"))
        for (junk in listOf(null, "", "dB", "loud", "-900 dB")) assertNull("$junk", MusicalTags.parseGain(junk))
    }

    @Test
    fun `an R128 gain is a 16-bit count of 256ths of a dB`() {
        assertEquals(-2304, MusicalTags.parseQ78("-2304"))
        assertEquals(512, MusicalTags.parseQ78(" 512 "))
        for (junk in listOf(null, "", "-9.0", "40000", "x")) assertNull("$junk", MusicalTags.parseQ78(junk))
    }

    @Test
    fun `fields by name give every value, the first valid spelling winning`() {
        val tags = MusicalTags.from(
            mapOf(
                "BPM" to "0", "TBPM" to "140", "INITIALKEY" to "8A", "KEY" to "nonsense",
                "REPLAYGAIN_TRACK_GAIN" to "-7.1 dB", "REPLAYGAIN_ALBUM_GAIN" to "-6.9 dB",
                "R128_TRACK_GAIN" to "-1024", "R128_ALBUM_GAIN" to "-900",
            ),
        )
        assertEquals(MusicalTags(140f, 8, -7.1f, -6.9f, -1024, -900), tags)
        assertEquals(MusicalTags(), MusicalTags.from(emptyMap()))
    }

    @Test
    fun `ID3v2_4 - TBPM, TKEY and TXXX ReplayGain frames`() {
        val tag = ID3v24Tag()
        tag.setField(FieldKey.BPM, "128")
        tag.setField(FieldKey.KEY, "F#m")
        tag.addField(ID3v24Frame("TXXX").apply { body = FrameBodyTXXX(0, "REPLAYGAIN_TRACK_GAIN", "-6.54 dB") })
        tag.addField(ID3v24Frame("TXXX").apply { body = FrameBodyTXXX(0, "replaygain_album_gain", "-5.98 dB") })
        assertEquals(MusicalTags(128f, 11, -6.54f, -5.98f), MusicalTags.from(tagTextFields(tag)))
    }

    @Test
    fun `ID3v2_3 - the same frames in the older version`() {
        val tag = ID3v23Tag()
        tag.setField(FieldKey.BPM, "90")
        tag.setField(FieldKey.KEY, "Bb")
        tag.addField(ID3v23Frame("TXXX").apply { body = FrameBodyTXXX(0, "REPLAYGAIN_ALBUM_GAIN", "+1.25 dB") })
        assertEquals(MusicalTags(bpm = 90f, camelotKey = 18, rgAlbumGain = 1.25f), MusicalTags.from(tagTextFields(tag)))
    }

    @Test
    fun `FLAC - INITIALKEY, which jaudiotagger's KEY does not map to`() {
        val comments = VorbisCommentTag.createNewTag()
        comments.addField("BPM", "174")
        comments.addField("INITIALKEY", "8A")
        comments.addField("REPLAYGAIN_ALBUM_GAIN", "-7.10 dB")
        val tag = FlacTag(comments, emptyList())
        assertEquals(MusicalTags(bpm = 174f, camelotKey = 8, rgAlbumGain = -7.1f), MusicalTags.from(tagTextFields(tag)))
    }

    @Test
    fun `MP4 - tmpo, the initialkey atom and a reverse-DNS ReplayGain atom`() {
        val tag = Mp4Tag()
        tag.setField(FieldKey.BPM, "126")
        tag.setField(FieldKey.KEY, "C")
        tag.addField(
            Mp4TagReverseDnsField("----:com.apple.iTunes:replaygain_track_gain", "com.apple.iTunes", "replaygain_track_gain", "-3.20 dB"),
        )
        assertEquals(MusicalTags(bpm = 126f, camelotKey = 20, rgTrackGain = -3.2f), MusicalTags.from(tagTextFields(tag)))
    }

    @Test
    fun `a tag with none of them reads as empty`() {
        val tag = ID3v24Tag()
        tag.setField(FieldKey.TITLE, "Song")
        assertEquals(MusicalTags(), MusicalTags.from(tagTextFields(tag)))
    }
}
