// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from NewPipe (TeamNewPipe/NewPipe), GPL-3.0-or-later:
// app/src/main/java/org/schabi/newpipe/util/potoken/PoTokenProviderImpl.kt (master, 00acf2f3).
package tf.monochrome.android.data.youtube.potoken

import android.content.Context
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.InterruptedIOException

/**
 * PO tokens for YouTube's WEB client, made by BotGuard in a WebView
 * ([PoTokenWebView]), handed to NewPipeExtractor through
 * `YoutubeStreamExtractor.setPoTokenProvider`.
 *
 * Only the WEB client's tokens can be made this way; the Android and iOS
 * apps' come from Google Play's DroidGuard and Apple's attestation, which no
 * WebView can stand in for. So those, and the embedded player's, are always
 * null, exactly as in NewPipe's app. That null is what keeps extraction as it
 * was: on NewPipeExtractor v0.26.5 a token for the Android client would
 * switch it from the "reel" client, which needs none and is what works, to
 * the plain Android one, and a BotGuard token there would only get refused.
 * v0.26.5 never asks for a WEB token at all (only for Android's, and iOS's
 * when its client is switched on, which it is not here), so this stands
 * ready for an extractor that does, and changes nothing until then.
 *
 * A token that cannot be made is null, never an exception: no WebView, a
 * WebView too old or broken, a BotGuard or JavaScript error, a network error
 * or a timeout all leave the extractor to carry on without one, as it does
 * when no provider is set.
 *
 * The extractor calls this from its own (IO) threads and blocks on it, as it
 * did on NewPipe's `blockingGet()`; the WebView's work goes to the main
 * thread inside [PoTokenWebView]. Called from the main thread, which that
 * work needs free, it answers null rather than wait on itself.
 */
internal class PoTokenProviderImpl(
    private val newGenerator: suspend () -> PoTokenGenerator,
    private val fetchVisitorData: () -> String,
    webViewSupported: () -> Boolean,
    private val onMainThread: () -> Boolean,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val initTimeoutMs: Long = INIT_TIMEOUT_MS,
    private val tokenTimeoutMs: Long = TOKEN_TIMEOUT_MS,
) : PoTokenProvider {

    private val webViewSupported by lazy(webViewSupported)

    /** Set once BotGuard showed this phone's WebView cannot run it; never tried again. */
    @Volatile
    private var webViewBadImpl = false

    /**
     * After a failure, tokens are not tried again before this ([clock]):
     * otherwise a phone where they cannot be made (offline BotGuard, a
     * timeout) would wait out the timeout on every song.
     */
    @Volatile
    private var retryAfter = Long.MIN_VALUE

    private val lock = Any()
    private var webPoTokenVisitorData: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenGenerator? = null

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!webViewSupported || webViewBadImpl || onMainThread() || clock() < retryAfter) {
            return null
        }

        return try {
            getWebClientPoToken(videoId = videoId, failed = null, isRetry = false)
        } catch (e: VirtualMachineError) {
            throw e
        } catch (t: Throwable) {
            when (t) {
                is BadWebViewException -> {
                    Log.e(TAG, "Could not obtain poToken because WebView is broken", t)
                    webViewBadImpl = true
                }
                // The extraction was cancelled (the listener moved on), which
                // says nothing about whether tokens can be made.
                is InterruptedException, is InterruptedIOException -> Thread.currentThread().interrupt()
                else -> {
                    Log.w(TAG, "Could not obtain poToken, going on without one", t)
                    retryAfter = clock() + FAILURE_COOLDOWN_MS
                }
            }
            null
        }
    }

    /**
     * @param failed the generator that just failed to mint a token, to be
     * replaced; NewPipe forces a new one outright, which would also throw
     * away one another thread had only just made.
     * @param isRetry whether this is the one retry after such a failure.
     */
    private fun getWebClientPoToken(videoId: String, failed: PoTokenGenerator?, isRetry: Boolean): PoTokenResult {
        // just a helper class since Kotlin does not have builtin support for 4-tuples
        data class Quadruple<T1, T2, T3, T4>(val t1: T1, val t2: T2, val t3: T3, val t4: T4)

        val (poTokenGenerator, visitorData, streamingPot, hasBeenRecreated) = synchronized(lock) {
            val current = webPoTokenGenerator
            val shouldRecreate = current == null || current === failed || current.isExpired()

            if (shouldRecreate) {
                webPoTokenGenerator = null
                webPoTokenVisitorData = null
                webPoTokenStreamingPot = null
                current?.close()

                val visitorData = fetchVisitorData()
                val generator = blocking(initTimeoutMs) { newGenerator() }
                // The streaming poToken needs to be generated exactly once before generating
                // any other (player) tokens. Kept only together with its generator, so a
                // failure here leaves nothing half made for the next call to trust.
                val streamingPot = try {
                    blocking(tokenTimeoutMs) { generator.generatePoToken(visitorData) }
                } catch (t: Throwable) {
                    generator.close()
                    throw t
                }
                webPoTokenGenerator = generator
                webPoTokenVisitorData = visitorData
                webPoTokenStreamingPot = streamingPot
            }

            Quadruple(webPoTokenGenerator!!, webPoTokenVisitorData!!, webPoTokenStreamingPot!!, shouldRecreate)
        }

        val playerPot = try {
            // Not under the lock: a generator can mint several tokens in parallel. What
            // matters is that the visitor data's streaming token came first.
            blocking(tokenTimeoutMs) { poTokenGenerator.generatePoToken(videoId) }
        } catch (t: Throwable) {
            if (hasBeenRecreated || isRetry || t is InterruptedException) {
                // the generator has just been made (maybe already on a retry), so
                // there is likely nothing more to try
                throw t
            }
            // Retry with a new generator: the WebView's content may have been
            // lost while the app was in the background.
            Log.w(TAG, "Failed to obtain poToken, retrying", t)
            return getWebClientPoToken(videoId = videoId, failed = poTokenGenerator, isRetry = true)
        }

        return PoTokenResult(visitorData, playerPot, streamingPot)
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null

    /** [block] on this thread, given up after [timeoutMs]. */
    private fun <T> blocking(timeoutMs: Long, block: suspend () -> T): T =
        runBlocking { withTimeout(timeoutMs) { block() } }

    companion object {
        private const val TAG = "PoTokenProvider"

        /**
         * BotGuard's own setup waits up to 10 s, after two requests to
         * YouTube; past this, a token is not worth the wait for a song.
         */
        const val INIT_TIMEOUT_MS = 20_000L

        /** Minting a token is one call into the page, normally milliseconds. */
        const val TOKEN_TIMEOUT_MS = 10_000L

        const val FAILURE_COOLDOWN_MS = 5 * 60_000L

        /**
         * The provider YouTube extraction uses: [downloader] is the
         * extractor's own, so BotGuard's requests go out like every other
         * request to YouTube.
         */
        fun create(context: Context, downloader: Downloader): PoTokenProviderImpl {
            val appContext = context.applicationContext
            return PoTokenProviderImpl(
                newGenerator = { PoTokenWebView.newPoTokenGenerator(appContext, downloader) },
                fetchVisitorData = ::webVisitorData,
                webViewSupported = PoTokenWebView::isSupported,
                onMainThread = { Looper.myLooper() == Looper.getMainLooper() },
            )
        }

        /** Visitor data for a signed-out WEB client, which the streaming token is minted for. */
        private fun webVisitorData(): String {
            val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofWebClient()
            innertubeClientRequestInfo.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
            return YoutubeParsingHelper.getVisitorDataFromInnertube(
                innertubeClientRequestInfo,
                NewPipe.getPreferredLocalization(),
                NewPipe.getPreferredContentCountry(),
                YoutubeParsingHelper.getYouTubeHeaders(),
                YoutubeParsingHelper.YOUTUBEI_V1_URL,
                null,
                false,
            )
        }
    }
}
