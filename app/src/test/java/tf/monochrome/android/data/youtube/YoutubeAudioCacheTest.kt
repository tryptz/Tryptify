package tf.monochrome.android.data.youtube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * The cache YouTube songs are fetched into and played from: one fetch per
 * song however many ask, a fetch nobody waits for any more dropped, and the
 * folder held to its cap by what was played least recently, never by what
 * the player may still hold.
 */
class YoutubeAudioCacheTest {

    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_700_000_000_000L
    private val minute = 60_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("yt-cache").toFile()
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun cache(
        capBytes: Long = YoutubeAudioCache.CAP_BYTES,
        keepRecentMs: Long = 10 * minute,
        fetch: suspend (String, File) -> String,
    ) = YoutubeAudioCache(dir, scope, capBytes, keepRecentMs, clock = { now }, fetch = fetch)

    private fun bytes(size: Int) = ByteArray(size) { it.toByte() }

    @Test
    fun `two listeners of one song share one fetch`() = runBlocking {
        val fetches = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val cache = cache { _, part ->
            fetches.incrementAndGet()
            gate.await()
            part.writeBytes(bytes(100))
            "m4a"
        }
        val player = async(Dispatchers.Default) { cache.get("dQw4w9WgXcQ") }
        val download = async(Dispatchers.Default) { cache.get("dQw4w9WgXcQ") }
        withTimeout(5_000) { while (fetches.get() == 0) Thread.sleep(5) }
        gate.complete(Unit)
        val a = player.await()
        val b = download.await()

        assertEquals(1, fetches.get())
        assertEquals(a, b)
        assertEquals("dQw4w9WgXcQ.m4a", a.name)
        assertEquals(100L, a.length())
        assertEquals(listOf("dQw4w9WgXcQ.m4a"), dir.list()!!.toList())
    }

    @Test
    fun `a song already here is not fetched again, and counts as just played`() = runBlocking {
        val fetches = AtomicInteger()
        val cache = cache { _, part -> fetches.incrementAndGet(); part.writeBytes(bytes(10)); "webm" }
        val first = cache.get("aaaaaaaaaaa")
        now += 5 * minute
        val again = cache.get("aaaaaaaaaaa")
        assertEquals(1, fetches.get())
        assertEquals(first, again)
        assertEquals("aaaaaaaaaaa.webm", again.name)
        assertEquals(now, again.lastModified())
        assertEquals(again, cache.cached("aaaaaaaaaaa"))
    }

    @Test
    fun `a failed fetch leaves nothing behind, and the next ask tries again`() = runBlocking {
        val fetches = AtomicInteger()
        val cache = cache { _, part ->
            part.writeBytes(bytes(50))
            if (fetches.incrementAndGet() == 1) throw IOException("connection reset")
            "m4a"
        }
        try {
            cache.get("bbbbbbbbbbb")
            fail("the failure should reach the listener")
        } catch (e: IOException) {
            assertEquals("connection reset", e.message)
        }
        assertTrue(dir.list()!!.isEmpty())
        assertEquals(50L, cache.get("bbbbbbbbbbb").length())
        assertEquals(2, fetches.get())
    }

    @Test
    fun `a fetch nobody waits for any more is dropped`() = runBlocking {
        val started = CompletableDeferred<File>()
        val cancelled = CompletableDeferred<Unit>()
        val cache = cache { _, part ->
            part.writeBytes(bytes(10))
            started.complete(part)
            try {
                CompletableDeferred<Unit>().await()
            } catch (e: CancellationException) {
                cancelled.complete(Unit)
                throw e
            }
            "m4a"
        }
        val skipped = async(Dispatchers.Default) { cache.get("ccccccccccc") }
        val part = withTimeout(5_000) { started.await() }
        assertTrue(part.exists())
        skipped.cancel()
        withTimeout(5_000) { cancelled.await() }
        withTimeout(5_000) { while (part.exists()) Thread.sleep(5) }
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun `a song skipped and picked again is not lost to the dropped fetch's clean-up`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val firstMayEnd = CompletableDeferred<Unit>()
        val firstEnded = CompletableDeferred<Unit>()
        val fetches = AtomicInteger()
        val cache = cache { _, part ->
            if (fetches.incrementAndGet() == 1) {
                // Stuck in a piece that does not notice the skip until it lands.
                part.writeBytes(bytes(7))
                firstStarted.complete(Unit)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { firstMayEnd.await() }
                firstEnded.complete(Unit)
                kotlinx.coroutines.yield()
            }
            part.writeBytes(bytes(40))
            "m4a"
        }
        val skipped = async(Dispatchers.Default) { cache.get("fffffffffff") }
        withTimeout(5_000) { firstStarted.await() }
        skipped.cancelAndJoin()
        val again = withTimeout(5_000) { cache.get("fffffffffff") }
        firstMayEnd.complete(Unit)
        withTimeout(5_000) { firstEnded.await() }
        Thread.sleep(50)
        assertEquals(2, fetches.get())
        assertTrue(again.exists())
        assertEquals(40L, again.length())
        assertEquals(listOf("fffffffffff.m4a"), dir.list()!!.toList())
    }

    @Test
    fun `over the cap, the least recently played go first, never one played lately`() = runBlocking {
        val cache = cache(capBytes = 250, keepRecentMs = 10 * minute) { _, part -> part.writeBytes(bytes(100)); "m4a" }
        cache.get("song1111111")
        now += 20 * minute
        cache.get("song2222222")
        now += 20 * minute
        // Played again: now the most recent of the old ones.
        cache.get("song1111111")
        now += 20 * minute
        cache.get("song3333333")
        // 300 bytes over a 250 cap: song2 is the least recently played.
        assertFalse(File(dir, "song2222222.m4a").exists())
        assertTrue(File(dir, "song1111111.m4a").exists())
        assertTrue(File(dir, "song3333333.m4a").exists())

        // Everything played in the last ten minutes stays, even over the cap.
        now += minute
        cache.get("song4444444")
        now += minute
        cache.get("song5555555")
        assertFalse(File(dir, "song1111111.m4a").exists())
        assertTrue(File(dir, "song3333333.m4a").exists())
        assertTrue(File(dir, "song4444444.m4a").exists())
        assertTrue(File(dir, "song5555555.m4a").exists())
    }

    @Test
    fun `a part left by a fetch that died with the app is not kept`() = runBlocking {
        val stale = File(dir, "ddddddddddd.part").apply { writeBytes(bytes(30)) }
        val orphan = File(dir, "eeeeeeeeeee.part").apply { writeBytes(bytes(30)); setLastModified(now - 60 * minute) }
        val seen = mutableListOf<Long>()
        val cache = cache { _, part -> seen += part.length(); part.writeBytes(bytes(5)); "m4a" }
        assertEquals(5L, cache.get("ddddddddddd").length())
        // Not resumed from the stale part: its link is gone.
        assertEquals(listOf(0L), seen)
        assertFalse(stale.exists())
        assertFalse(orphan.exists())
    }
}
