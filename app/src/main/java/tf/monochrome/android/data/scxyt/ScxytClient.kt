package tf.monochrome.android.data.scxyt

import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * trypt-hifi-scxyt's API: what to ask it, and what its answers mean.
 *
 * Every call carries the listener's Supabase session from [bearer]. A 401 asks
 * [bearer] for a newer token, once, and sends the call again; [bearer] is
 * handed the token that was refused, so calls that fail together refresh the
 * session once (CloudRepository.bearer). Every failure is thrown as the
 * [ScxytError] it means.
 *
 * The HTTP itself is [transport], so the app's half of the contract can be
 * tested without a network.
 */
class ScxytClient(
    private val baseUrl: String,
    private val json: Json,
    private val transport: Transport,
    private val bearer: suspend (stale: String?) -> String,
) {
    /** One GET, read whole. Throws only when nothing answered. */
    fun interface Transport {
        suspend fun get(url: String, token: String, timeoutMs: Long): Reply
    }

    class Reply(val status: Int, val body: String)

    suspend fun search(query: String, source: ScxytSource, limit: Int, offset: Int = 0): List<ScxytTrack> =
        decode<ScxytSearchData>(call(searchUrl(baseUrl, query, source, limit, offset), SEARCH_TIMEOUT_MS)).items

    /**
     * Has the server fetch [id] and returns where to play it from. Takes a few
     * seconds, up to about a minute for a long song the server has not seen.
     */
    suspend fun prepare(id: String): ScxytStream {
        val data = decode<ScxytPrepared>(call(prepareUrl(baseUrl, id), PREPARE_TIMEOUT_MS))
        val link = absoluteLink(baseUrl, data.link) ?: throw ScxytError.Failed("no_link")
        return ScxytStream(id = data.id.ifBlank { id }, format = data.format, mime = data.mime, size = data.size, link = link)
    }

    private suspend fun call(url: String, timeoutMs: Long): String {
        val token = bearer(null)
        var reply = transport.get(url, token, timeoutMs)
        if (reply.status == 401) reply = transport.get(url, bearer(token), timeoutMs)
        if (reply.status !in 200..299) throw ScxytError.of(reply.status, reply.body)
        return reply.body
    }

    private inline fun <reified T> decode(body: String): T {
        val envelope = try {
            json.decodeFromString<ScxytEnvelope<T>>(body)
        } catch (e: IllegalArgumentException) {
            // Not the server's JSON: a proxy's error page, a captive portal.
            throw ScxytError.Failed("bad_response")
        }
        if (!envelope.success) throw ScxytError.of(200, body)
        return envelope.data ?: throw ScxytError.Failed("bad_response")
    }

    companion object {
        /** The search answers in about two seconds; the engine's own 30 s is plenty. */
        const val SEARCH_TIMEOUT_MS = 30_000L

        /** A first fetch of a long song can take a minute on the server; a player's own timeout is far shorter. */
        const val PREPARE_TIMEOUT_MS = 120_000L

        fun searchUrl(base: String, query: String, source: ScxytSource, limit: Int, offset: Int): String =
            "${base.trimEnd('/')}/api/v1/search?q=${encode(query)}&source=${source.wire}&limit=$limit&offset=$offset"

        fun prepareUrl(base: String, id: String): String = "${base.trimEnd('/')}/api/v1/prepare?id=${encode(id)}"

        /**
         * The server's [link] (`/api/v1/stream?id=…&exp=…&sig=…`) as an address
         * the player can open. Joined to [base] rather than resolved against
         * its origin, as every route here is, so a server behind a path still
         * works. A link that already names its scheme is taken as it is (the
         * app's cleartext rule still judges it when it is opened); null for
         * no link.
         */
        fun absoluteLink(base: String, link: String): String? {
            val l = link.trim()
            if (l.isEmpty()) return null
            if (l.startsWith("https://") || l.startsWith("http://")) return l
            return base.trimEnd('/') + "/" + l.trimStart('/')
        }

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
