package tf.monochrome.android.data.import_

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Built from the shapes the real exporters emit, because every one of these was
 * a file that came back as "could not parse the CSV file" with nothing else said.
 */
class PlaylistCsvTest {

    private val bom = "﻿"

    // ── Exportify / Spotify ─────────────────────────────────────────────

    @Test
    fun `reads an Exportify export`() {
        val csv = """
            "Track URI","Track Name","Artist Name(s)","Album Name","Track Duration (ms)"
            "spotify:track:1","Bad Guy","Billie Eilish","When We All Fall Asleep","194087"
        """.trimIndent()
        val playlist = PlaylistCsv.parse(csv, "My Mix")
        assertEquals(1, playlist.tracks.size)
        assertEquals("Bad Guy", playlist.tracks[0].title)
        assertEquals("Billie Eilish", playlist.tracks[0].artist)
        assertEquals("When We All Fall Asleep", playlist.tracks[0].album)
        assertEquals(194087L, playlist.tracks[0].durationMs)
        assertEquals(CsvSource.SPOTIFY, playlist.source)
    }

    @Test
    fun `a byte order mark does not hide the title column`() {
        // Exportify writes one. Left in place it becomes part of the first
        // header, so the *title* column is the one that goes missing — on the
        // very export this parser was originally written for.
        val csv = "${bom}Track Name,Artist Name(s)\nBad Guy,Billie Eilish"
        val playlist = PlaylistCsv.parse(csv, "x")
        assertEquals("Bad Guy", playlist.tracks[0].title)
    }

    @Test
    fun `keeps multiple artists in one field`() {
        val csv = """
            Track Name,Artist Name(s)
            "Sunflower","Post Malone, Swae Lee"
        """.trimIndent()
        assertEquals("Post Malone, Swae Lee", PlaylistCsv.parse(csv, "x").tracks[0].artist)
    }

    // ── Apple Music ─────────────────────────────────────────────────────

    @Test
    fun `reads an Apple Music export decoded from UTF-16 tab separated`() {
        // The Music app writes UTF-16 with a mark and tab separators. Decoded as
        // UTF-8 the whole file is mojibake and not one header matches.
        val tsv = "Name\tArtist\tAlbum\tTotal Time\nAlways the Sun\tThe Stranglers\tDreamtime\t244000\n"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + tsv.toByteArray(Charsets.UTF_16LE)
        val playlist = PlaylistCsv.parse(PlaylistCsv.decode(bytes), "Dreamtime")
        assertEquals(1, playlist.tracks.size)
        assertEquals("Always the Sun", playlist.tracks[0].title)
        assertEquals("The Stranglers", playlist.tracks[0].artist)
        assertEquals(244000L, playlist.tracks[0].durationMs)
        assertEquals(CsvSource.APPLE_MUSIC, playlist.source)
    }

    @Test
    fun `an inch mark in an unquoted field is not an opening quote`() {
        // Tab-separated exports quote nothing, and this is a real title. Treated
        // as an opening quote it swallows the rest of the file into one field.
        val tsv = "Name\tArtist\nAlways the Sun — Original 7\" Edit\tThe Stranglers\nNext Song\tSomeone"
        val tracks = PlaylistCsv.parse(tsv, "x").tracks
        assertEquals(2, tracks.size)
        assertEquals("Always the Sun — Original 7\" Edit", tracks[0].title)
        assertEquals("The Stranglers", tracks[0].artist)
        assertEquals("Next Song", tracks[1].title)
    }

    @Test
    fun `decodes UTF-16 without a byte order mark`() {
        val tsv = "Name\tArtist\nBad Guy\tBillie Eilish\n"
        val decoded = PlaylistCsv.decode(tsv.toByteArray(Charsets.UTF_16LE))
        assertEquals("Bad Guy", PlaylistCsv.parse(decoded, "x").tracks[0].title)
    }

    @Test
    fun `decodes UTF-16 big endian`() {
        val tsv = "Name\tArtist\nBad Guy\tBillie Eilish\n"
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + tsv.toByteArray(Charsets.UTF_16BE)
        assertEquals("Bad Guy", PlaylistCsv.parse(PlaylistCsv.decode(bytes), "x").tracks[0].title)
    }

    @Test
    fun `plain UTF-8 survives the encoding probe`() {
        val csv = "Track Name,Artist Name(s)\nSüße Träume,Böhse Onkelz"
        val decoded = PlaylistCsv.decode(csv.toByteArray(Charsets.UTF_8))
        assertEquals("Süße Träume", PlaylistCsv.parse(decoded, "x").tracks[0].title)
    }

    // ── Header naming ───────────────────────────────────────────────────

    @Test
    fun `accepts the other exporters' column names`() {
        listOf(
            "Title,Artist\nA,B",
            "Name,Artist\nA,B",
            "Song Title,Artist Names\nA,B",
            "Track name,Artist name\nA,B",
            "TRACK NAME,ARTIST NAME(S)\nA,B",
        ).forEach { csv ->
            val track = PlaylistCsv.parse(csv, "x").tracks.single()
            assertEquals("header set <${csv.lineSequence().first()}>", "A", track.title)
            assertEquals("B", track.artist)
        }
    }

    @Test
    fun `album name is not mistaken for the track title`() {
        // "Album Name" contains "name"; a substring match hands back the album.
        val csv = "Album Name,Track Name,Artist Name(s)\nDreamtime,Always the Sun,The Stranglers"
        val track = PlaylistCsv.parse(csv, "x").tracks.single()
        assertEquals("Always the Sun", track.title)
        assertEquals("Dreamtime", track.album)
    }

    @Test
    fun `missing columns name what was wanted and what was there`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PlaylistCsv.parse("Foo,Bar\n1,2", "x")
        }
        val message = error.message.orEmpty()
        assertTrue(message, "track title" in message)
        assertTrue(message, "artist" in message)
        assertTrue("must list the columns it did find", "foo, bar" in message)
    }

    // ── Delimiters and quoting ──────────────────────────────────────────

    @Test
    fun `detects the delimiter`() {
        assertEquals(',', PlaylistCsv.detectDelimiter("Track Name,Artist Name(s),Album Name"))
        assertEquals('\t', PlaylistCsv.detectDelimiter("Name\tArtist\tAlbum"))
        assertEquals(';', PlaylistCsv.detectDelimiter("Name;Artist;Album"))
        // A quoted comma must not outvote the real separator.
        assertEquals('\t', PlaylistCsv.detectDelimiter("\"Artist, Composer\"\tName\tAlbum"))
        assertEquals(',', PlaylistCsv.detectDelimiter("OnlyOneColumn"))
    }

    @Test
    fun `reads a semicolon separated export`() {
        val csv = "Track Name;Artist Name(s);Album Name\nBad Guy;Billie Eilish;WWAFA"
        assertEquals("Bad Guy", PlaylistCsv.parse(csv, "x").tracks.single().title)
    }

    @Test
    fun `a quoted delimiter stays inside its field`() {
        val csv = "Track Name,Artist Name(s)\n\"Hello, Goodbye\",The Beatles"
        assertEquals("Hello, Goodbye", PlaylistCsv.parse(csv, "x").tracks.single().title)
    }

    @Test
    fun `a doubled quote inside quotes is one literal quote`() {
        val csv = "Track Name,Artist Name(s)\n\"He said \"\"hi\"\"\",Someone"
        assertEquals("He said \"hi\"", PlaylistCsv.parse(csv, "x").tracks.single().title)
    }

    @Test
    fun `a newline inside a quoted field does not split the row`() {
        val csv = "Track Name,Artist Name(s)\n\"Two\nLines\",Someone"
        val track = PlaylistCsv.parse(csv, "x").tracks.single()
        assertEquals("Two\nLines", track.title)
        assertEquals("Someone", track.artist)
    }

    @Test
    fun `handles CRLF and lone CR line endings`() {
        listOf("\r\n", "\r", "\n").forEach { eol ->
            val csv = "Track Name,Artist Name(s)${eol}A,B${eol}C,D$eol"
            val tracks = PlaylistCsv.parse(csv, "x").tracks
            assertEquals("line ending ${eol.map { it.code }}", 2, tracks.size)
            assertEquals("D", tracks[1].artist)
        }
    }

    @Test
    fun `blank rows are dropped`() {
        val csv = "Track Name,Artist Name(s)\nA,B\n\n\n,\nC,D\n"
        assertEquals(2, PlaylistCsv.parse(csv, "x").tracks.size)
    }

    @Test
    fun `rows with no title are dropped`() {
        val csv = "Track Name,Artist Name(s)\nA,B\n,Orphan Artist\nC,D"
        val tracks = PlaylistCsv.parse(csv, "x").tracks
        assertEquals(2, tracks.size)
        assertEquals(listOf("A", "C"), tracks.map { it.title })
    }

    @Test
    fun `a short row keeps the fields it has`() {
        val csv = "Track Name,Artist Name(s),Album Name\nA,B"
        val track = PlaylistCsv.parse(csv, "x").tracks.single()
        assertEquals("B", track.artist)
        assertEquals("", track.album)
    }

    // ── Durations ───────────────────────────────────────────────────────

    @Test
    fun `parses the duration spellings the exporters use`() {
        assertEquals(194087L, PlaylistCsv.parseDurationMs("194087", "track duration (ms)"))
        assertEquals(244000L, PlaylistCsv.parseDurationMs("244000", "total time"))
        assertEquals(225000L, PlaylistCsv.parseDurationMs("3:45", "duration"))
        assertEquals(3723000L, PlaylistCsv.parseDurationMs("1:02:03", "length"))
        // Bare seconds, from the exporters that write them.
        assertEquals(225000L, PlaylistCsv.parseDurationMs("225", "duration"))
        // Bare milliseconds, where the header says nothing.
        assertEquals(225000L, PlaylistCsv.parseDurationMs("225000", "duration"))
        assertEquals(0L, PlaylistCsv.parseDurationMs("", "duration"))
        assertEquals(0L, PlaylistCsv.parseDurationMs("unknown", "duration"))
        assertEquals(0L, PlaylistCsv.parseDurationMs("-5", "duration"))
    }

    @Test
    fun `a missing duration column is not an error`() {
        val track = PlaylistCsv.parse("Track Name,Artist Name(s)\nA,B", "x").tracks.single()
        assertEquals(0L, track.durationMs)
    }

    // ── Whole-file behaviour ────────────────────────────────────────────

    @Test
    fun `falls back to the file name for the playlist title`() {
        assertEquals("My Mix", PlaylistCsv.parse("Track Name,Artist Name(s)\nA,B", "My Mix").title)
        assertEquals(
            "Imported Playlist",
            PlaylistCsv.parse("Track Name,Artist Name(s)\nA,B", "").title,
        )
    }

    @Test
    fun `an empty file is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PlaylistCsv.parse("", "x") }
        assertThrows(IllegalArgumentException::class.java) { PlaylistCsv.parse("   \n\n", "x") }
    }

    @Test
    fun `a header with no rows under it is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PlaylistCsv.parse("Track Name,Artist Name(s)\n", "x")
        }
        assertTrue(error.message.orEmpty(), "no songs" in error.message.orEmpty())
    }
}
