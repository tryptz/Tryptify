// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.share

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.glyph.multiplayer.RoomProtocol

class ShareSizeTest {
    private val dir: File = Files.createTempDirectory("share-size").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Makes a "copy" of [bytes] bytes, or fails, and counts its calls. */
    private inner class FakeShrinker(
        override val available: Boolean = true,
        private val bytes: Long? = 40_000_000,
        private val throws: Boolean = false,
    ) : SongShrinker {
        var calls = 0
        var made: File? = null

        override suspend fun shrink(source: String, onProgress: (Float) -> Unit): File? {
            calls++
            if (throws) throw IllegalStateException("no decoder")
            onProgress(0.5f)
            val size = bytes ?: return null
            return File.createTempFile("copy", ".m4a", dir).also { f ->
                java.io.RandomAccessFile(f, "rw").use { it.setLength(size) }
                made = f
            }
        }
    }

    @Test
    fun `the limit is the server's, else 99 MB`() {
        assertEquals(99_000_000L, ShareSize.limit(null))
        assertEquals(99_000_000L, ShareSize.limit(0))
        assertEquals(50_000_000L, ShareSize.limit(50_000_000))
    }

    @Test
    fun `at a party the limit is also what is left of its room`() {
        val gib = 1024L * 1024 * 1024
        assertEquals(99_000_000L, ShareSize.limit(99_000_000, gib, 0))
        assertEquals(30_000_000L, ShareSize.limit(99_000_000, gib, gib - 30_000_000))
        assertEquals(0L, ShareSize.limit(99_000_000, gib, gib + 5))
        // No party limit said: only the song's.
        assertEquals(99_000_000L, ShareSize.limit(99_000_000, 0, 500))
    }

    @Test
    fun `only a song over the limit is made smaller`() {
        assertFalse(ShareSize.shouldShrink(99_000_000, 99_000_000, canShrink = true))
        assertFalse(ShareSize.shouldShrink(5_000_000, 99_000_000, canShrink = true))
        assertTrue(ShareSize.shouldShrink(99_000_001, 99_000_000, canShrink = true))
        assertTrue(ShareSize.shouldShrink(250_000_000, 99_000_000, canShrink = true))
    }

    @Test
    fun `nothing is made smaller without an encoder, an unknown size, or any room`() {
        assertFalse(ShareSize.shouldShrink(250_000_000, 99_000_000, canShrink = false))
        assertFalse(ShareSize.shouldShrink(null, 99_000_000, canShrink = true))
        assertFalse(ShareSize.shouldShrink(0, 99_000_000, canShrink = true))
        assertFalse(ShareSize.shouldShrink(250_000_000, 0, canShrink = true))
    }

    @Test
    fun `a song whose 320 kbps copy would still be too big is not tried`() {
        // 320 kbps is 40 kB a second: 99 MB holds about 40 minutes.
        val tenMinutes = 10 * 60_000L
        val twoHours = 120 * 60_000L
        assertTrue(ShareSize.shouldShrink(250_000_000, 99_000_000, canShrink = true, durationMs = tenMinutes))
        assertFalse(ShareSize.shouldShrink(900_000_000, 99_000_000, canShrink = true, durationMs = twoHours))
        // A length not known is no reason not to try.
        assertTrue(ShareSize.shouldShrink(250_000_000, 99_000_000, canShrink = true, durationMs = 0))
        assertTrue(ShareSize.estimate(tenMinutes) in 24_000_000L..25_000_000L)
    }

    @Test
    fun `a song that fits goes up as it is, untouched`() = runBlocking {
        val shrinker = FakeShrinker()
        val prepared = ShareSize.prepare(shrinker, "file:///song.flac", 80_000_000, "flac", 99_000_000)
        assertNull(prepared.shrunk)
        assertEquals("flac", prepared.ext)
        assertEquals(0, shrinker.calls)
    }

    @Test
    fun `a song too big goes up as its m4a copy, deleted after`() = runBlocking {
        val shrinker = FakeShrinker(bytes = 40_000_000)
        var progress = 0f
        val prepared = ShareSize.prepare(shrinker, "file:///song.flac", 180_000_000, "flac", 99_000_000) { progress = it }
        val copy = prepared.shrunk!!
        assertSame(shrinker.made, copy)
        assertEquals("m4a", prepared.ext)
        assertEquals(ShareSize.SHRUNK_EXT, RoomProtocol.songExt(copy.name))
        assertEquals(0.5f, progress)
        assertTrue(copy.isFile)
        prepared.discard()
        assertFalse(copy.exists())
    }

    @Test
    fun `a copy still too big is deleted, and the original goes up as before`() = runBlocking {
        val shrinker = FakeShrinker(bytes = 120_000_000)
        val prepared = ShareSize.prepare(shrinker, "file:///mix.flac", 600_000_000, "flac", 99_000_000)
        assertNull(prepared.shrunk)
        assertEquals("flac", prepared.ext)
        assertFalse(shrinker.made!!.exists())
    }

    @Test
    fun `a failed or impossible copy leaves the original`() = runBlocking {
        val failed = ShareSize.prepare(FakeShrinker(bytes = null), "file:///a.wav", 300_000_000, "wav", 99_000_000)
        assertNull(failed.shrunk)
        assertEquals("wav", failed.ext)

        val threw = ShareSize.prepare(FakeShrinker(throws = true), "file:///a.aiff", 300_000_000, "aiff", 99_000_000)
        assertNull(threw.shrunk)
        assertEquals("aiff", threw.ext)

        val old = FakeShrinker(available = false)
        val oldPhone = ShareSize.prepare(old, "file:///a.flac", 300_000_000, "flac", 99_000_000)
        assertNull(oldPhone.shrunk)
        assertEquals(0, old.calls)
    }

    @Test
    fun `an empty copy is no copy`() = runBlocking {
        val shrinker = FakeShrinker(bytes = 0)
        val prepared = ShareSize.prepare(shrinker, "file:///a.flac", 300_000_000, "flac", 99_000_000)
        assertNull(prepared.shrunk)
        assertFalse(shrinker.made!!.exists())
    }

    @Test
    fun `the downmix keeps every side from clipping and drops the LFE`() {
        for (n in 3..10) {
            val m = ShareSize.stereoDownmix(n)
            assertEquals(n * 2, m.size)
            val left = (0 until n).sumOf { m[it * 2].toDouble() }
            val right = (0 until n).sumOf { m[it * 2 + 1].toDouble() }
            assertTrue("$n channels: left $left", left <= 1.0001)
            assertTrue("$n channels: right $right", right <= 1.0001)
            assertTrue(m.all { it >= 0f })
        }
        // 5.1: front left only left, front right only right, centre both, LFE none.
        val m = ShareSize.stereoDownmix(6)
        assertEquals(0f, m[1])
        assertEquals(0f, m[2])
        assertEquals(m[4], m[5])
        assertEquals(0f, m[6])
        assertEquals(0f, m[7])
    }

    @Test
    fun `the server's limit is read from its info`() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(
            99_000_000L,
            RoomProtocol.maxSongBytes(json, """{"success":true,"data":{"service":"x","maxSongBytes":99000000,"partyQuotaBytes":1073741824}}"""),
        )
        assertNull(RoomProtocol.maxSongBytes(json, """{"success":true,"data":{"service":"x"}}"""))
        assertNull(RoomProtocol.maxSongBytes(json, "not json"))
        assertNull(RoomProtocol.maxSongBytes(json, """{"data":{"maxSongBytes":0}}"""))
    }
}
