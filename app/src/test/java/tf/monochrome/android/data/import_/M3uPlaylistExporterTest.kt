package tf.monochrome.android.data.import_

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exporting a playlist as an M3U: which files it may list, and what the file is called. */
class M3uPlaylistExporterTest {

    @Test
    fun `files in shared storage are listed`() {
        assertTrue(M3uPlaylistExporter.isSharedFile("/storage/emulated/0/Music/Queen/11 Bohemian Rhapsody.flac"))
        assertTrue(M3uPlaylistExporter.isSharedFile("/storage/1A2B-3C4D/Music/a.mp3"))
        assertTrue(M3uPlaylistExporter.isSharedFile("/storage/emulated/0/Download/Tryptify/a.flac"))
        assertTrue(M3uPlaylistExporter.isSharedFile("/sdcard/Music/a.mp3"))
    }

    @Test
    fun `files only this app can read are not`() {
        // A download in the app's own storage.
        assertFalse(M3uPlaylistExporter.isSharedFile("/storage/emulated/0/Android/data/tf.monochrome.android/files/downloads/a.flac"))
        assertFalse(M3uPlaylistExporter.isSharedFile("/storage/1A2B-3C4D/Android/data/tf.monochrome.android/files/a.flac"))
        assertFalse(M3uPlaylistExporter.isSharedFile("/storage/emulated/0/Android/obb/x/a.flac"))
        assertFalse(M3uPlaylistExporter.isSharedFile("/data/user/0/tf.monochrome.android/cache/youtube/a.m4a"))
        assertFalse(M3uPlaylistExporter.isSharedFile("/data/data/tf.monochrome.android/files/a.flac"))
    }

    @Test
    fun `an address is not a file`() {
        // A download saved through the system's folder picker.
        assertFalse(M3uPlaylistExporter.isSharedFile("content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fa.flac"))
        assertFalse(M3uPlaylistExporter.isSharedFile("https://example.com/a.mp3"))
        assertFalse(M3uPlaylistExporter.isSharedFile("Music/a.mp3"))
        assertFalse(M3uPlaylistExporter.isSharedFile(""))
    }

    @Test
    fun `the file is named after the playlist`() {
        assertEquals("Road trip.m3u", M3uPlaylistExporter.fileNameFor("Road trip"))
        assertEquals("夜のドライブ.m3u", M3uPlaylistExporter.fileNameFor("夜のドライブ"))
        assertEquals("Déjà vu & more.m3u", M3uPlaylistExporter.fileNameFor("  Déjà vu & more  "))
    }

    @Test
    fun `what a file name cannot hold is replaced`() {
        assertEquals("AC_DC _ live_.m3u", M3uPlaylistExporter.fileNameFor("AC/DC : live?"))
        assertEquals("a_b_c_d_e_f_g.m3u", M3uPlaylistExporter.fileNameFor("a\\b*c\"d<e>f|g"))
        assertEquals("two_lines.m3u", M3uPlaylistExporter.fileNameFor("two\nlines"))
        // Dots at the ends would hide the file or double its extension's dot.
        assertEquals("hidden.m3u", M3uPlaylistExporter.fileNameFor("..hidden.."))
    }

    @Test
    fun `a playlist with no usable name still gets a file name`() {
        assertEquals("playlist.m3u", M3uPlaylistExporter.fileNameFor(""))
        assertEquals("playlist.m3u", M3uPlaylistExporter.fileNameFor("   "))
        assertEquals("playlist.m3u", M3uPlaylistExporter.fileNameFor("..."))
    }

    @Test
    fun `a very long name is cut`() {
        val name = M3uPlaylistExporter.fileNameFor("x".repeat(500))
        assertEquals("x".repeat(80) + ".m3u", name)
    }
}
