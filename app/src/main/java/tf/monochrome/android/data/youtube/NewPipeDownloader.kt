package tf.monochrome.android.data.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * NewPipeExtractor's HTTP, on OkHttp: every request it makes to YouTube
 * (the search, the player answers, the player's script) goes out from the
 * phone, on the listener's own network, which is the whole point of
 * extracting here.
 *
 * Shaped like NewPipe's own DownloaderImpl: a browser User-Agent unless the
 * extractor names one, and a 429 thrown as the captcha it is, which
 * [YoutubeMapping.errorOf] reads as YouTube turning this network away. No
 * Accept-Encoding is set, so OkHttp asks for gzip and unpacks it itself.
 */
internal class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val data = request.dataToSend()
        val method = request.httpMethod()
        // OkHttp refuses a POST without a body, and a POST the extractor
        // sends no data with means an empty one.
        val body = data?.toRequestBody() ?: if (method in BODY_METHODS) ByteArray(0).toRequestBody() else null
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(method, body)
            .header("User-Agent", YoutubeMapping.BROWSER_USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }

    companion object {
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")

        /**
         * The client YouTube is reached with: the app's rule for plain http
         * on every hop (YouTube is https throughout, so it never bites), and
         * NewPipe's 30 s read timeout.
         */
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                tf.monochrome.android.data.net.CleartextPolicy.require(chain.request().url.toString())
                chain.proceed(chain.request())
            }
            .build()
    }
}
