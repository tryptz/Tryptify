package tf.monochrome.android.data.scxyt

import kotlinx.serialization.json.Json
import java.io.File
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
    /** One request, its answer read whole. Throws only when nothing answered. */
    interface Transport {
        suspend fun get(url: String, token: String, timeoutMs: Long): Reply

        /**
         * A POST with [body] streamed from the file as [contentType] (no body
         * when null). Sent again whole on a refused token, so it must not be
         * a stream that reads only once.
         */
        suspend fun post(url: String, token: String, body: File?, contentType: String?, timeoutMs: Long): Reply
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

    /**
     * Has the server make the preview Discord plays when [id]'s tryptify.com
     * link is posted, and returns the signature that goes in the link. A
     * YouTube song's [audio] is its whole file from the phone's cache, sent
     * as the body with its [mime]: YouTube turns the server away, so it
     * cannot fetch the song itself. A SoundCloud song has no body; the server
     * fetches it, and answers with its soundcloud.com page as well.
     *
     * A file larger than the server takes is refused here, before 40 MB go
     * up only to be turned away.
     */
    suspend fun share(id: String, audio: File? = null, mime: String? = null): ScxytShare {
        if (audio != null && audio.length() > SHARE_MAX_BYTES) {
            throw ScxytError.TooLong("${audio.length()} bytes, more than the server takes")
        }
        val url = shareUrl(baseUrl, id)
        val data = decode<ScxytShared>(send { token -> transport.post(url, token, audio, mime, SHARE_TIMEOUT_MS) })
        if (data.s.isBlank()) throw ScxytError.Failed("no_signature")
        return ScxytShare(id = data.id.ifBlank { id }, s = data.s, page = data.page?.takeIf { it.isNotBlank() })
    }

    private suspend fun call(url: String, timeoutMs: Long): String = send { token -> transport.get(url, token, timeoutMs) }

    private suspend fun send(request: suspend (token: String) -> Reply): String {
        val token = bearer(null)
        var reply = request(token)
        if (reply.status == 401) reply = request(bearer(token))
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

        /**
         * Sending a YouTube song up and the server encoding its 30-second
         * preview: a few seconds on a good network, far more than the
         * engine's own 30 s on a slow uplink.
         */
        const val SHARE_TIMEOUT_MS = 90_000L

        /** The largest song the server's share takes (its SHARE_UPLOAD_MB, 40 by default). */
        const val SHARE_MAX_BYTES = 40L shl 20

        fun searchUrl(base: String, query: String, source: ScxytSource, limit: Int, offset: Int): String =
            "${base.trimEnd('/')}/api/v1/search?q=${encode(query)}&source=${source.wire}&limit=$limit&offset=$offset"

        fun prepareUrl(base: String, id: String): String = "${base.trimEnd('/')}/api/v1/prepare?id=${encode(id)}"

        fun shareUrl(base: String, id: String): String = "${base.trimEnd('/')}/api/v1/share?id=${encode(id)}"

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
