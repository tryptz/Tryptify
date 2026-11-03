// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from NewPipe (TeamNewPipe/NewPipe), GPL-3.0-or-later:
// app/src/main/java/org/schabi/newpipe/util/potoken/PoTokenWebView.kt (master, 00acf2f3).
package tf.monochrome.android.data.youtube.potoken

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.annotation.MainThread
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.downloader.Downloader
import java.time.Instant

/**
 * Runs Google's BotGuard in an off-screen WebView and mints PO tokens with
 * it, as YouTube's own website does.
 *
 * Making one goes: load `po_token.html` (BotGuard's glue, no network of its
 * own), fetch the challenge from BotGuard's Create endpoint, run it, trade
 * its answer at GenerateIT for an integrity token, keep that in the page.
 * Each token after that is one call into the page.
 *
 * The WebView is made, driven and destroyed on the main thread only; the
 * page calls back on WebView's own binder thread, and the HTTP goes on the
 * IO dispatcher through the extractor's [Downloader]. NewPipe chains these
 * with RxJava; here a [CoroutineScope] on the main thread carries them, and
 * [CompletableDeferred]s stand for its emitters.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class PoTokenWebView @MainThread private constructor(
    context: Context,
    private val downloader: Downloader,
) : PoTokenGenerator {
    private val webView = WebView(context)

    /** Initialisation's own work (the asset read, the two requests); cancelled by [close]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Done once the integrity token is in the page; failed by any error before. */
    private val initialized = CompletableDeferred<Unit>()

    private val poTokenRequests = mutableListOf<Pair<String, CompletableDeferred<String>>>()

    /** Expired until GenerateIT says otherwise. */
    @Volatile
    private var expiration: Instant = Instant.MIN

    @Volatile
    private var closed = false

    //region Initialization
    init {
        val webViewSettings = webView.settings
        webViewSettings.javaScriptEnabled = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(webViewSettings, false)
        }
        webViewSettings.userAgentString = USER_AGENT
        // The page needs no network: BotGuard's code comes in the challenge.
        webViewSettings.blockNetworkLoads = true

        // How the page hands back what its asynchronous functions return.
        webView.addJavascriptInterface(this, JS_INTERFACE)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().contains("Uncaught")) {
                    // Everything that can fail in the page is inside a
                    // try/catch, so an uncaught error is almost surely a
                    // syntax error: a WebView too old for the page's
                    // JavaScript, which no retry fixes.
                    val fmt = "\"${m.message()}\", source: ${m.sourceId()} (${m.lineNumber()})"
                    val exception = BadWebViewException(fmt)
                    Log.e(TAG, "This WebView implementation is broken: $fmt")

                    // Before closing, which would fail them with a plain
                    // "closed" and hide that this WebView is beyond use.
                    popAllPoTokenRequests().forEach { (_, request) -> request.completeExceptionally(exception) }
                    onInitializationErrorCloseAndCancel(exception)
                }
                return super.onConsoleMessage(m)
            }
        }
    }

    /**
     * Starts initialisation, right after construction: loads the page, which
     * calls [downloadAndRunBotguard] once it is ready.
     */
    @MainThread
    private fun loadHtmlAndObtainBotguard(context: Context) {
        initializationStep {
            val html = withContext(Dispatchers.IO) {
                context.assets.open(HTML_ASSET).bufferedReader().use { it.readText() }
            }
            webView.loadDataWithBaseURL(
                "https://www.youtube.com",
                html.replaceFirst(
                    "</script>",
                    // calls downloadAndRunBotguard() when the page has finished loading
                    "\n$JS_INTERFACE.downloadAndRunBotguard()</script>",
                ),
                "text/html",
                "utf-8",
                null,
            )
        }
    }

    /** Called by the page once loaded: fetches BotGuard's challenge and runs it. */
    @JavascriptInterface
    fun downloadAndRunBotguard() {
        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/Create",
            "[ \"$REQUEST_KEY\" ]",
        ) { responseBody ->
            val parsedChallengeData = parseChallengeData(responseBody)
            webView.evaluateJavascript(
                """try {
                    data = $parsedChallengeData
                    runBotGuard(data).then(function (result) {
                        this.webPoSignalOutput = result.webPoSignalOutput
                        $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                    }, function (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    })
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                }""",
                null,
            )
        }
    }

    /** Called by the page when running BotGuard failed. */
    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        Log.w(TAG, "Initialization error from JavaScript: $error")
        onInitializationErrorCloseAndCancel(buildExceptionForJsError(error))
    }

    /**
     * Called by the page with BotGuard's answer: trades it for the integrity
     * token, which then lives in the page.
     */
    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/GenerateIT",
            "[ ${jsStringLiteral(REQUEST_KEY)}, ${jsStringLiteral(botguardResponse)} ]",
        ) { responseBody ->
            val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(responseBody)
            expiration = integrityTokenExpiry(Instant.now(), expirationTimeInSeconds)

            webView.evaluateJavascript("this.integrityToken = $integrityToken") {
                Log.d(TAG, "BotGuard ready, integrity token lasts ${expirationTimeInSeconds}s")
                initialized.complete(Unit)
            }
        }
    }
    //endregion

    //region Obtaining poTokens
    override suspend fun generatePoToken(identifier: String): String {
        val request = CompletableDeferred<String>()
        addPoTokenRequest(identifier, request)
        try {
            withContext(Dispatchers.Main) {
                if (closed) throw PoTokenException("Generator closed")
                webView.evaluateJavascript(
                    """try {
                        identifier = ${jsStringLiteral(identifier)}
                        u8Identifier = ${stringToU8(identifier)}
                        poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                        poTokenU8String = ""
                        for (i = 0; i < poTokenU8.length; i++) {
                            if (i != 0) poTokenU8String += ","
                            poTokenU8String += poTokenU8[i]
                        }
                        $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                    } catch (error) {
                        $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                    }""",
                    null,
                )
            }
            return request.await()
        } finally {
            // Gone already once the page answered; still here after a
            // timeout or a cancel, which must not leave it behind.
            removePoTokenRequest(request)
        }
    }

    /** Called by the page when `obtainPoToken` failed for [identifier]. */
    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        Log.w(TAG, "obtainPoToken error from JavaScript: $error")
        popPoTokenRequest(identifier)?.completeExceptionally(buildExceptionForJsError(error))
    }

    /** Called by the page with the token minted for [identifier], as `Uint8Array` text. */
    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val poToken = try {
            u8ToBase64(poTokenU8)
        } catch (t: Throwable) {
            popPoTokenRequest(identifier)?.completeExceptionally(t)
            return
        }
        popPoTokenRequest(identifier)?.complete(poToken)
    }

    override fun isExpired(): Boolean = Instant.now().isAfter(expiration)
    //endregion

    //region Handling multiple requests

    /** Several tokens may be asked for at once; each answer goes to its own request. */
    private fun addPoTokenRequest(identifier: String, request: CompletableDeferred<String>) {
        synchronized(poTokenRequests) {
            poTokenRequests.add(identifier to request)
        }
    }

    /** Takes the oldest request for [identifier] out, to be answered at once. */
    private fun popPoTokenRequest(identifier: String): CompletableDeferred<String>? =
        synchronized(poTokenRequests) {
            poTokenRequests.indexOfFirst { it.first == identifier }.takeIf { it >= 0 }?.let {
                poTokenRequests.removeAt(it).second
            }
        }

    private fun removePoTokenRequest(request: CompletableDeferred<String>) {
        synchronized(poTokenRequests) {
            poTokenRequests.removeAll { it.second === request }
        }
    }

    /** Takes every request out, to be failed at once. */
    private fun popAllPoTokenRequests(): List<Pair<String, CompletableDeferred<String>>> =
        synchronized(poTokenRequests) {
            val result = poTokenRequests.toList()
            poTokenRequests.clear()
            result
        }
    //endregion

    //region Utils

    /**
     * POSTs [data] to one of BotGuard's endpoints with the headers its web
     * client sends, then hands the body to [handleResponseBody] on the main
     * thread. Initialisation only: any failure, or an answer other than 200,
     * ends it.
     */
    private fun makeBotguardServiceRequest(
        url: String,
        data: String,
        handleResponseBody: (String) -> Unit,
    ) {
        initializationStep {
            val response = runInterruptible(Dispatchers.IO) {
                downloader.post(
                    url,
                    mapOf(
                        // replace the downloader's user agent
                        "User-Agent" to listOf(USER_AGENT),
                        "Accept" to listOf("application/json"),
                        "Content-Type" to listOf("application/json+protobuf"),
                        "x-goog-api-key" to listOf(GOOGLE_API_KEY),
                        "x-user-agent" to listOf("grpc-web-javascript/0.1"),
                    ),
                    data.toByteArray(),
                )
            }
            val httpCode = response.responseCode()
            if (httpCode != 200) throw PoTokenException("Invalid response code: $httpCode")
            handleResponseBody(response.responseBody())
        }
    }

    /** Runs one step of initialisation on the main thread; a failure ends it. */
    private fun initializationStep(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onInitializationErrorCloseAndCancel(t)
            }
        }
    }

    /** Fails initialisation with [error] and releases everything. */
    private fun onInitializationErrorCloseAndCancel(error: Throwable) {
        initialized.completeExceptionally(error)
        close()
    }

    /**
     * Releases the WebView and stops initialisation. NewPipe's caller posts
     * this to the main thread itself; here it does so on its own, so it can
     * be called from anywhere, more than once.
     */
    override fun close() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            closeOnMainThread()
        } else {
            Handler(Looper.getMainLooper()).post(::closeOnMainThread)
        }
    }

    @MainThread
    private fun closeOnMainThread() {
        if (closed) return
        closed = true
        scope.cancel()
        val error = PoTokenException("Generator closed")
        initialized.completeExceptionally(error)
        popAllPoTokenRequests().forEach { (_, request) -> request.completeExceptionally(error) }

        webView.clearHistory()
        // clears RAM cache and disk cache (globally for all WebViews)
        webView.clearCache(true)

        // ensures that the WebView isn't doing anything when destroying it
        webView.loadUrl("about:blank")

        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    }
    //endregion

    companion object : PoTokenGenerator.Factory {
        private const val TAG = "PoTokenWebView"

        // Public API key used by BotGuard, which has been got by looking at BotGuard requests
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw" // NOSONAR
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS_INTERFACE = "PoTokenWebView"
        private const val HTML_ASSET = "po_token.html"

        /**
         * Whether the phone has a WebView at all (some builds and profiles
         * ship none, and making one would throw).
         */
        fun isSupported(): Boolean = try {
            CookieManager.getInstance()
            true
        } catch (t: Throwable) {
            false
        }

        /**
         * A generator once BotGuard has run, or the error that stopped it.
         * Cancelling (the provider's timeout) destroys the half-made WebView.
         */
        override suspend fun newPoTokenGenerator(context: Context, downloader: Downloader): PoTokenGenerator {
            var made: PoTokenWebView? = null
            try {
                val generator = withContext(Dispatchers.Main) {
                    PoTokenWebView(context, downloader).also {
                        made = it
                        it.loadHtmlAndObtainBotguard(context)
                    }
                }
                generator.initialized.await()
                return generator
            } catch (t: Throwable) {
                made?.close()
                throw t
            }
        }
    }
}
