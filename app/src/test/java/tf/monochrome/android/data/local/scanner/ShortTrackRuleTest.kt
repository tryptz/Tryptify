package tf.monochrome.android.data.local.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a whole-device scan keeps of the short files Android calls music. An
 * album's intro and skits come in with it; a voice note or a sound on its own
 * stays out, as every short file did before the 30 s floor went.
 */
class ShortTrackRuleTest {

    private data class File(val path: String, val ms: Long)

    private fun keep(vararg files: File) =
        MediaStoreSource.keepShortOnlyInAlbums(files.toList(), { it.path }, { it.ms }).map { it.path }

    @Test
    fun `an album's short tracks come in with it`() {
        val kept = keep(
            File("/Music/Album/01 Intro.flac", 12_000),
            File("/Music/Album/02 Song.flac", 215_000),
            File("/Music/Album/03 Skit.flac", 9_000),
        )
        assertEquals(listOf("/Music/Album/01 Intro.flac", "/Music/Album/02 Song.flac", "/Music/Album/03 Skit.flac"), kept)
    }

    @Test
    fun `a short clip on its own stays out`() {
        val kept = keep(
            File("/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202642/PTT-1.opus", 7_000),
            File("/Download/beep.mp3", 1_200),
            File("/Music/Album/02 Song.flac", 215_000),
        )
        assertEquals(listOf("/Music/Album/02 Song.flac"), kept)
    }

    @Test
    fun `a long file is kept wherever it is`() {
        assertEquals(listOf("/Download/mix.mp3"), keep(File("/Download/mix.mp3", 3_600_000)))
    }

    @Test
    fun `a length the indexer doesn't know counts as short`() {
        assertEquals(emptyList<String>(), keep(File("/Download/unknown.ogg", 0)))
        assertEquals(
            listOf("/Music/A/unknown.ogg", "/Music/A/song.ogg"),
            keep(File("/Music/A/unknown.ogg", 0), File("/Music/A/song.ogg", 180_000)),
        )
    }

    @Test
    fun `a longer track in another folder doesn't vouch for a short one`() {
        // Same parent, different folder: /Music/A does not make /Music/B an album.
        val kept = keep(File("/Music/A/song.flac", 200_000), File("/Music/B/clip.flac", 5_000))
        assertEquals(listOf("/Music/A/song.flac"), kept)
    }
}
