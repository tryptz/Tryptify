package tf.monochrome.android.data.youtube.potoken

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import java.io.IOException

/**
 * The provider set in the real NewPipeExtractor v0.26.5: extraction's first
 * player request is the one it makes with no provider, YouTube's Android
 * "reel" client without a token, which is what works on phones today. The
 * extractor talks to a recorder that answers the visitor-data call and
 * stops at the player request; no network.
 */
class PoTokenWiringTest {

    @After
    fun unset() {
        YoutubeStreamExtractor.setPoTokenProvider(null)
    }

    private class Recorder : Downloader() {
        val requests = mutableListOf<Request>()

        override fun execute(request: Request): Response {
            requests += request
            if ("/visitor_id" in request.url()) {
                return Response(200, "OK", emptyMap(), VISITOR_RESPONSE, request.url())
            }
            throw IOException("stopped at ${request.url()}")
        }
    }

    /** Which of its methods the extractor asked, around the real provider. */
    private class Spy(private val inner: PoTokenProvider) : PoTokenProvider {
        val asked = mutableListOf<String>()

        override fun getWebClientPoToken(videoId: String): PoTokenResult? =
            inner.getWebClientPoToken(videoId).also { asked += "web" }

        override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? =
            inner.getWebEmbedClientPoToken(videoId).also { asked += "embed" }

        override fun getAndroidClientPoToken(videoId: String): PoTokenResult? =
            inner.getAndroidClientPoToken(videoId).also { asked += "android" }

        override fun getIosClientPoToken(videoId: String): PoTokenResult? =
            inner.getIosClientPoToken(videoId).also { asked += "ios" }
    }

    /** The player request extraction of a video makes first, with [provider] set. */
    private fun playerRequest(provider: PoTokenProvider?): Request {
        val recorder = Recorder()
        NewPipe.init(recorder)
        YoutubeStreamExtractor.setPoTokenProvider(provider)
        try {
            ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=dQw4w9WgXcQ").fetchPage()
            fail("the recorder stops every player request")
        } catch (e: IOException) {
            // expected: stopped at the player request
        }
        return recorder.requests.last()
    }

    @Test
    fun `with the provider set, extraction still asks the Android reel client first, without a token`() {
        val without = playerRequest(null)

        var webViewWork = 0
        val provider = Spy(
            PoTokenProviderImpl(
                newGenerator = { webViewWork++; error("no WebView in a test") },
                fetchVisitorData = { webViewWork++; "unused" },
                webViewSupported = { true },
                onMainThread = { false },
            ),
        )
        val with = playerRequest(provider)

        assertTrue(with.url(), "/reel/reel_item_watch" in with.url())
        // The rest of the address after t= is a random value and the id.
        assertEquals(without.url().substringBefore("&t="), with.url().substringBefore("&t="))
        assertFalse(String(with.dataToSend()!!).contains("poToken"))
        assertEquals("v0.26.5 asks only for the Android client's token", listOf("android"), provider.asked)
        assertEquals(0, webViewWork)
    }

    private companion object {
        // Long enough for the extractor's "JSON response is too short" check.
        const val VISITOR_RESPONSE =
            """{"responseContext":{"visitorData":"CgtWaXNpdG9yRGF0YQ%3D%3D","serviceTrackingParams":[]}}"""
    }
}
