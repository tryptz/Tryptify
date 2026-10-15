package tf.monochrome.android.data.local.galaxy

import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.android.data.local.db.LocalTrackEntity

/** Which of a library row's fields its moon's label is made from. */
class MoonLabelTest {

    private fun row(
        path: String,
        title: String? = null,
        artist: String? = null,
        albumArtist: String? = null,
        track: Int? = null,
        disc: Int? = 1,
    ) = LocalTrackEntity(
        filePath = path,
        fileSizeBytes = 1,
        lastModified = 0,
        title = title,
        artist = artist,
        albumArtist = albumArtist,
        trackNumber = track,
        discNumber = disc,
        codec = "FLAC",
        sampleRate = 44100,
        bitRate = 900,
        channels = 2,
        durationSeconds = 200,
    )

    @Test
    fun `a title tag holding the file's name is checked against the track's number and artist`() {
        val tagged = row("/m/x.flac", title = "03 Bioweapon, Audiofreq, Code Black - A.A.C.S.", artist = "Bioweapon", track = 3)
        assertEquals("A.A.C.S.", tagged.moonLabel())
        // The album artist names it as well as the track artist does.
        assertEquals("Waves", row("/m/y.flac", title = "Bioweapon - Waves", albumArtist = "Bioweapon", track = 1).moonLabel())
    }

    @Test
    fun `a file name's numbering is not taken off twice`() {
        // No title: displayTitle has taken "03 - " off already, and the "3 - "
        // left is the title's own, not track 3's number.
        assertEquals("3 - Song", row("/m/03 - 3 - Song.flac", track = 3).moonLabel())
        // The same name stored as the title (the scan setting that names
        // tracks by their files) is checked: its numbering is still on it.
        assertEquals("3 - Song", row("/m/a.flac", title = "03 - 3 - Song", track = 3).moonLabel())
    }
}
