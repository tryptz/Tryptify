package tf.monochrome.android.data.import_

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What exportify.net posts to the app when Export is tapped inside it, built
 * the way its exportify.js builds it: the header it writes, quoted fields,
 * a byte-order mark, and "Export All" as a ZIP of one CSV per playlist.
 */
class ExportifyFilesTest {

    private val header = "Track URI,ISRC,Track Name,Album Name,Artist Name(s),Release Date,Duration (ms)," +
        "Popularity,Explicit,Added By,Added At,Genres,Record Label,Danceability,Energy,Key,Loudness,Mode," +
        "Speechiness,Acousticness,Instrumentalness,Liveness,Valence,Tempo,Time Signature\n"

    private fun row(title: String, album: String, artists: String, ms: Int) =
        "\"spotify:track:0000000000000000000000\",\"USX\",\"$title\",\"$album\",\"$artists\",\"2001-01-01\"," +
            "$ms,50,false,\"\",\"2024-01-01T00:00:00Z\",\"pop\",\"Label\",0.5,0.5,1,-5,1,0.05,0.1,0,0.1,0.5,120,4\n"

    private val csv = header +
        row("Hung Up", "Confessions on a Dance Floor", "Madonna", 337000) +
        row("The Sweet Escape", "The Sweet Escape", "Gwen Stefani,Akon", 246000)

    private fun dataUrl(bytes: ByteArray, type: String) =
        "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)

    private fun csvBytes(text: String) = "﻿$text".toByteArray(Charsets.UTF_8)

    @Test
    fun `one playlist keeps its own name, not the file's`() {
        val message = ExportifyFiles.Message(
            fileName = "Road_Trip_'26.csv",
            displayName = "Road Trip '26",
            dataUrl = dataUrl(csvBytes(csv), "text/csv;charset=utf-8"),
        )
        val playlists = ExportifyFiles.playlists(message)
        assertEquals(1, playlists.size)
        assertEquals("Road Trip '26", playlists[0].title)
        val parsed = PlaylistCsv.parse(playlists[0].csv, fallbackTitle = playlists[0].title)
        assertEquals(2, parsed.tracks.size)
        assertEquals("Hung Up", parsed.tracks[0].title)
        assertEquals("Madonna", parsed.tracks[0].artist)
        assertEquals("Confessions on a Dance Floor", parsed.tracks[0].album)
        assertEquals(337_000L, parsed.tracks[0].durationMs)
    }

    @Test
    fun `without the row's name, the file name is read back`() {
        assertEquals("Road Trip 2", ExportifyFiles.titleFrom("Road_Trip_2.csv"))
        // Trailing underscores mark a duplicate name in an Export All ZIP.
        assertEquals("Mix", ExportifyFiles.titleFrom("Mix__.csv"))
        assertEquals("Spotify playlist", ExportifyFiles.titleFrom("_.csv"))
    }

    @Test
    fun `Export All brings every playlist in the ZIP`() {
        val zipped = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                for (name in listOf("Liked_Songs.csv", "Chill_Evening.csv", "Chill_Evening_.csv")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(csv.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("readme.txt"))
                zip.write("not a playlist".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val playlists = ExportifyFiles.playlists(
            ExportifyFiles.Message(fileName = "spotify_playlists.zip", dataUrl = dataUrl(zipped, "application/zip")),
        )
        assertEquals(listOf("Liked Songs", "Chill Evening", "Chill Evening"), playlists.map { it.title })
        assertTrue(playlists.all { PlaylistCsv.parse(it.csv, it.title).tracks.size == 2 })
    }

    @Test
    fun `the message the page posts is read, and anything else is refused`() {
        val posted = """{"fileName":"A.csv","displayName":"A","dataUrl":"data:text/csv;base64,QQ=="}"""
        assertEquals("A.csv", ExportifyFiles.parseMessage(posted)!!.fileName)
        assertNull(ExportifyFiles.parseMessage("not json"))
        assertNull(ExportifyFiles.bytes("https://exportify.net/x.csv"))
        assertNull(ExportifyFiles.bytes("data:text/csv,plain"))
        val notPlaylist = runCatching {
            ExportifyFiles.playlists(ExportifyFiles.Message("photo.png", null, dataUrl(byteArrayOf(1), "image/png")))
        }.exceptionOrNull()
        assertTrue(notPlaylist is IllegalArgumentException)
    }

    @Test
    fun `an entry that inflates past the cap is refused as it inflates`() {
        // A few KB of download that inflates to more than one playlist may be:
        // read whole before the check, this was an out-of-memory crash.
        val zipped = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("Huge.csv"))
                val zeros = ByteArray(64 * 1024)
                repeat((PlaylistCsv.MAX_BYTES / zeros.size) + 2) { zip.write(zeros) }
                zip.closeEntry()
            }
        }.toByteArray()
        assertTrue("compressed to ${zipped.size} bytes", zipped.size < 100_000)
        val refused = runCatching {
            ExportifyFiles.playlists(
                ExportifyFiles.Message(fileName = "spotify_playlists.zip", dataUrl = dataUrl(zipped, "application/zip")),
            )
        }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
        assertEquals("Exportify's file is too large to import.", refused?.message)
    }

    @Test
    fun `a bounded read stops past its limit and keeps what fits`() {
        assertEquals(5, ExportifyFiles.readAtMost(java.io.ByteArrayInputStream(ByteArray(5)), 5)?.size)
        assertNull(ExportifyFiles.readAtMost(java.io.ByteArrayInputStream(ByteArray(6)), 5))
        assertEquals(0, ExportifyFiles.readAtMost(java.io.ByteArrayInputStream(ByteArray(0)), 0)?.size)
    }
}
