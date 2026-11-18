package tf.monochrome.android.data.api

import java.net.HttpURLConnection
import java.net.URL

/**
 * One measurement file's answer, for the offline download: unlike the screen's
 * own fetches, which take any failure for "not there", it has to tell a file
 * that does not exist (skip it for good) from a server asking it to slow down
 * (wait and ask again) and from a dropped connection (try on the next run).
 */
sealed interface MeasurementFetch {
    data class Found(val text: String) : MeasurementFetch

    /** 404 or 410: the file is not published. */
    data object Missing : MeasurementFetch

    /** 429, 503, or GitHub's 403 for too many requests: ask again after [retryAfterMs]. */
    data class Throttled(val retryAfterMs: Long) : MeasurementFetch

    /** Anything else: no network, a timeout, another status. */
    data object Failed : MeasurementFetch
}

internal object MeasurementHttp {

    /** When a server asks to slow down without saying for how long. */
    const val DEFAULT_RETRY_AFTER_MS = 60_000L

    /**
     * Never `disconnect()`: a body read to the end and closed hands the
     * connection back for the next file, and disconnect would close it, so
     * every one of some 20,000 requests would open a new TLS connection.
     */
    fun get(url: String, accept: String? = null): MeasurementFetch = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            accept?.let { setRequestProperty("Accept", it) }
        }
        val code = connection.responseCode
        if (code != 200) {
            // Drain the error page so the connection can be reused too.
            runCatching { connection.errorStream?.use { it.readBytes() } }
        }
        classify(
            code = code,
            retryAfter = connection.getHeaderField("Retry-After"),
            rateLimitRemaining = connection.getHeaderField("X-RateLimit-Remaining"),
            body = { connection.inputStream.bufferedReader().use { it.readText() } },
        )
    } catch (_: Exception) {
        MeasurementFetch.Failed
    }

    /** The answer for a status and its headers; [body] is read only for a 200. */
    fun classify(
        code: Int,
        retryAfter: String?,
        rateLimitRemaining: String?,
        body: () -> String,
    ): MeasurementFetch = when {
        code == 200 -> MeasurementFetch.Found(body())
        code == 404 || code == 410 -> MeasurementFetch.Missing
        code == 429 || code == 503 || (code == 403 && rateLimitRemaining == "0") ->
            MeasurementFetch.Throttled(retryAfterMs(retryAfter))
        else -> MeasurementFetch.Failed
    }

    /** A Retry-After in seconds, kept between 5 seconds and 10 minutes. */
    fun retryAfterMs(header: String?): Long =
        header?.trim()?.toLongOrNull()?.let { (it * 1000).coerceIn(5_000L, 600_000L) }
            ?: DEFAULT_RETRY_AFTER_MS
}
