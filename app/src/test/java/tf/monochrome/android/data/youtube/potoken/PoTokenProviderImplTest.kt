package tf.monochrome.android.data.youtube.potoken

import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The provider's rules, with a stand-in for the WebView: the Android, iOS
 * and embedded clients never get a token (that null keeps NewPipeExtractor
 * v0.26.5 on the clients that work), a WEB token is made once and reused
 * until it expires, and every way of failing ends as null, never as an
 * exception into extraction. Under Robolectric only for android.util.Log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class PoTokenProviderImplTest {

    private class FakeGenerator(
        private val mint: (String) -> String = { "pot-$it" },
    ) : PoTokenGenerator {
        var expired = false
        var closed = false
        val minted = mutableListOf<String>()

        override suspend fun generatePoToken(identifier: String): String {
            check(!closed) { "minted after close" }
            minted += identifier
            return mint(identifier)
        }

        override fun isExpired() = expired
        override fun close() {
            closed = true
        }
    }

    private class Harness(
        var webViewSupported: Boolean = true,
        var onMainThread: Boolean = false,
        var visitorData: () -> String = { "visitor-1" },
        var newGenerator: suspend () -> PoTokenGenerator = { FakeGenerator() },
    ) {
        var now = 0L
        var generatorsMade = 0
        var visitorDataFetched = 0
        val made = mutableListOf<PoTokenGenerator>()

        val provider = PoTokenProviderImpl(
            newGenerator = {
                generatorsMade++
                newGenerator().also { made += it }
            },
            fetchVisitorData = {
                visitorDataFetched++
                visitorData()
            },
            webViewSupported = { webViewSupported },
            onMainThread = { onMainThread },
            clock = { now },
            initTimeoutMs = 200,
            tokenTimeoutMs = 200,
        )
    }

    @Test
    fun `the Android, iOS and embedded clients never get a token, so v0_26_5 keeps its reel client`() {
        val h = Harness()
        assertNull(h.provider.getAndroidClientPoToken("dQw4w9WgXcQ"))
        assertNull(h.provider.getIosClientPoToken("dQw4w9WgXcQ"))
        assertNull(h.provider.getWebEmbedClientPoToken("dQw4w9WgXcQ"))
        assertEquals("no WebView is touched for them", 0, h.generatorsMade)
        assertEquals(0, h.visitorDataFetched)
    }

    @Test
    fun `a WEB token carries the visitor data and its streaming token, minted first`() {
        val h = Harness()
        val result = h.provider.getWebClientPoToken("video-1")!!
        assertEquals("visitor-1", result.visitorData)
        assertEquals("pot-video-1", result.playerRequestPoToken)
        assertEquals("pot-visitor-1", result.streamingDataPoToken)
        assertEquals(listOf("visitor-1", "video-1"), (h.made.single() as FakeGenerator).minted)
    }

    @Test
    fun `one generator and one visitor data serve every token until it expires`() {
        val h = Harness()
        h.provider.getWebClientPoToken("video-1")
        val second = h.provider.getWebClientPoToken("video-2")!!
        assertEquals("pot-video-2", second.playerRequestPoToken)
        assertEquals(1, h.generatorsMade)
        assertEquals(1, h.visitorDataFetched)

        val first = h.made.single() as FakeGenerator
        first.expired = true
        h.visitorData = { "visitor-2" }
        val third = h.provider.getWebClientPoToken("video-3")!!
        assertEquals(2, h.generatorsMade)
        assertTrue("the expired one is released", first.closed)
        assertEquals("visitor-2", third.visitorData)
        assertEquals("pot-visitor-2", third.streamingDataPoToken)
    }

    @Test
    fun `a generator that stops minting is replaced once, as when the WebView lost its page`() {
        var broken = false
        val h = Harness()
        h.newGenerator = {
            FakeGenerator { if (broken && it == "video-2") throw PoTokenException("lost") else "pot-$it" }
        }
        h.provider.getWebClientPoToken("video-1")
        broken = true
        h.newGenerator = { FakeGenerator() }
        val result = h.provider.getWebClientPoToken("video-2")!!
        assertEquals("pot-video-2", result.playerRequestPoToken)
        assertEquals(2, h.generatorsMade)
        assertTrue((h.made.first() as FakeGenerator).closed)
    }

    @Test
    fun `a new generator that cannot mint gives null, without a second try`() {
        val h = Harness(newGenerator = { FakeGenerator { if (it == "video-1") throw PoTokenException("no") else "pot-$it" } })
        assertNull(h.provider.getWebClientPoToken("video-1"))
        assertEquals(1, h.generatorsMade)
    }

    @Test
    fun `no WebView, or a call from the main thread, gives null without trying`() {
        val none = Harness(webViewSupported = false)
        assertNull(none.provider.getWebClientPoToken("video-1"))
        assertEquals(0, none.generatorsMade)

        val main = Harness(onMainThread = true)
        assertNull(main.provider.getWebClientPoToken("video-1"))
        assertEquals(0, main.generatorsMade)
    }

    @Test
    fun `a failure gives null, and tokens are left alone for a while after`() {
        val h = Harness(newGenerator = { throw PoTokenException("Invalid response code: 403") })
        assertNull(h.provider.getWebClientPoToken("video-1"))
        assertNull(h.provider.getWebClientPoToken("video-2"))
        assertEquals("the second song does not wait on another try", 1, h.generatorsMade)

        h.now += PoTokenProviderImpl.FAILURE_COOLDOWN_MS
        h.newGenerator = { FakeGenerator() }
        assertNotNull(h.provider.getWebClientPoToken("video-3"))
        assertEquals(2, h.generatorsMade)
    }

    @Test
    fun `a broken WebView gives null and is never tried again`() {
        val h = Harness(newGenerator = { throw BadWebViewException("SyntaxError: Unexpected token") })
        assertNull(h.provider.getWebClientPoToken("video-1"))
        h.now += 10 * PoTokenProviderImpl.FAILURE_COOLDOWN_MS
        h.newGenerator = { FakeGenerator() }
        assertNull(h.provider.getWebClientPoToken("video-2"))
        assertEquals(1, h.generatorsMade)
    }

    @Test
    fun `BotGuard that never answers times out into null`() {
        val h = Harness(newGenerator = { awaitCancellation() })
        val started = System.nanoTime()
        assertNull(h.provider.getWebClientPoToken("video-1"))
        assertTrue("gave up at the timeout", System.nanoTime() - started < 5_000_000_000L)
    }

    @Test
    fun `a token that never comes times out into null`() {
        val h = Harness(newGenerator = {
            object : PoTokenGenerator {
                override suspend fun generatePoToken(identifier: String): String = awaitCancellation()
                override fun isExpired() = false
                override fun close() = Unit
            }
        })
        assertNull(h.provider.getWebClientPoToken("video-1"))
    }

    @Test
    fun `visitor data that cannot be fetched gives null`() {
        val h = Harness(visitorData = { throw IOException("offline") })
        assertNull(h.provider.getWebClientPoToken("video-1"))
        assertEquals(0, h.generatorsMade)
    }

    @Test
    fun `a streaming token that fails leaves nothing half made for the next call`() {
        var failStreaming = true
        val h = Harness(newGenerator = {
            FakeGenerator { if (failStreaming && it == "visitor-1") throw PoTokenException("no") else "pot-$it" }
        })
        assertNull(h.provider.getWebClientPoToken("video-1"))
        assertTrue("its generator is released", (h.made.single() as FakeGenerator).closed)

        failStreaming = false
        h.now += PoTokenProviderImpl.FAILURE_COOLDOWN_MS
        val result = h.provider.getWebClientPoToken("video-2")!!
        assertEquals("pot-visitor-1", result.streamingDataPoToken)
        assertEquals(2, h.generatorsMade)
    }

    @Test
    fun `an interrupted extraction gives null, keeps the interrupt, and starts no cool-down`() {
        val h = Harness(visitorData = { throw InterruptedException() })
        assertNull(h.provider.getWebClientPoToken("video-1"))
        assertTrue(Thread.interrupted())

        h.visitorData = { "visitor-1" }
        assertNotNull(h.provider.getWebClientPoToken("video-2"))
        assertFalse(Thread.currentThread().isInterrupted)
    }
}
