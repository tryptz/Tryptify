package tf.monochrome.android.data.subsonic

import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * How this app signs in to a Subsonic server.
 *
 * [TOKEN] is the API's own scheme since 1.13.0: `t = md5(password + salt)` and
 * the salt, so the password itself is never stored or sent. Navidrome, Gonic,
 * Airsonic-Advanced and current Ampache all take it. [PASSWORD] is the older
 * `p=enc:<hex>`, kept for the servers that refuse a token: one older than
 * 1.13.0 (error 30), or a Navidrome user signed in through LDAP (error 41).
 * It is only ever used after the server has said so.
 */
@Serializable
enum class SubsonicAuthMode { TOKEN, PASSWORD }

/**
 * The one Subsonic-compatible server the listener connected under
 * Settings › Connections.
 *
 * Holds what every request needs and nothing more. In [SubsonicAuthMode.TOKEN]
 * that is the salt and token; the password the listener typed is dropped once
 * the token is made. A fixed salt also keeps every cover-art URL the same from
 * one launch to the next, which is what lets Coil's disk cache find them again.
 *
 * Device-only: stored outside the synced settings, like the Discord token.
 */
@Serializable
data class SubsonicServer(
    val url: String,
    val username: String,
    val authMode: SubsonicAuthMode = SubsonicAuthMode.TOKEN,
    val salt: String = "",
    val token: String = "",
    /** Hex of the password, only in [SubsonicAuthMode.PASSWORD]. */
    val hexPassword: String = "",
    /** The server's own name for itself when it says (OpenSubsonic `type`: "navidrome", "gonic"…). */
    val serverType: String? = null,
    val serverVersion: String? = null,
) {
    /** The address without its scheme, for showing in Settings. */
    val displayHost: String get() = url.substringAfter("://")
}

/** A `status: failed` answer. [code] is the Subsonic error code. */
class SubsonicException(val code: Int?, message: String?) : Exception(message ?: "Subsonic error $code")

object Subsonic {
    const val CLIENT_NAME = "Tryptify"

    /** Token auth needs 1.13.0; getAlbumList2, search3 and the ID3 browse need 1.8.0. */
    const val TOKEN_API_VERSION = "1.13.0"
    const val PASSWORD_API_VERSION = "1.8.0"

    // Subsonic error codes this app acts on.
    const val ERROR_SERVER_TOO_OLD = 30
    const val ERROR_WRONG_CREDENTIALS = 40
    const val ERROR_TOKEN_NOT_SUPPORTED = 41
    const val ERROR_AUTH_NOT_SUPPORTED = 42

    /** True when [e] means "this server wants the plain password instead of a token". */
    fun wantsPassword(e: SubsonicException): Boolean =
        e.code == ERROR_TOKEN_NOT_SUPPORTED || e.code == ERROR_AUTH_NOT_SUPPORTED || e.code == ERROR_SERVER_TOO_OLD

    fun newSalt(random: SecureRandom = SecureRandom()): String =
        ByteArray(8).also(random::nextBytes).toHex()

    fun token(password: String, salt: String): String =
        MessageDigest.getInstance("MD5").digest((password + salt).toByteArray(Charsets.UTF_8)).toHex()

    fun tokenServer(url: String, username: String, password: String, salt: String = newSalt()) =
        SubsonicServer(url = url, username = username, salt = salt, token = token(password, salt))

    fun passwordServer(url: String, username: String, password: String) = SubsonicServer(
        url = url,
        username = username,
        authMode = SubsonicAuthMode.PASSWORD,
        hexPassword = password.toByteArray(Charsets.UTF_8).toHex(),
    )

    /**
     * What the listener typed, as a base URL, or null when it is not one.
     *
     * Forgiving on purpose, because the address is usually copied from the
     * browser tab the server's own web page is open in:
     * - no scheme gets `http://` for an address that is plainly at home (an IP,
     *   `localhost`, a `.local`/`.lan` name, or one with a port) and `https://`
     *   otherwise;
     * - a trailing `/rest` (the API folder), `/app` (Navidrome's web app) or a
     *   page name such as `/index.view` or `/login` is dropped, while a real
     *   base path such as `/airsonic` is kept;
     * - `#/album/…` and `?…` are dropped.
     */
    fun normalizeUrl(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if ("://" !in s) s = (if (looksAtHome(s)) "http://" else "https://") + s
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        var path = uri.rawPath.orEmpty().trimEnd('/')
        while (true) {
            val last = path.substringAfterLast('/')
            val drop = last.lowercase() in setOf("rest", "app", "index", "login") ||
                last.endsWith(".view") || last.endsWith(".html") || last.endsWith(".jsp")
            if (!drop || path.isEmpty()) break
            path = path.substringBeforeLast('/')
        }
        return "$scheme://$host$port$path"
    }

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    private fun looksAtHome(noScheme: String): Boolean {
        val authority = noScheme.substringBefore('/')
        val host = authority.substringBefore(':').lowercase()
        return host == "localhost" || IPV4.matches(host) || authority.contains(':') ||
            listOf(".local", ".lan", ".home", ".internal", ".home.arpa").any { host.endsWith(it) }
    }

    /** The sign-in part of every request, already encoded. */
    fun authQuery(server: SubsonicServer): String {
        val params = buildList {
            add("u" to server.username)
            when (server.authMode) {
                SubsonicAuthMode.TOKEN -> {
                    add("t" to server.token)
                    add("s" to server.salt)
                    add("v" to TOKEN_API_VERSION)
                }
                SubsonicAuthMode.PASSWORD -> {
                    add("p" to "enc:${server.hexPassword}")
                    add("v" to PASSWORD_API_VERSION)
                }
            }
            add("c" to CLIENT_NAME)
            add("f" to "json")
        }
        return params.toQuery()
    }

    /** `<base>/rest/<method>.view?<auth>&<params>`. */
    fun endpoint(server: SubsonicServer, method: String, params: List<Pair<String, String>> = emptyList()): String {
        val extra = if (params.isEmpty()) "" else "&" + params.toQuery()
        return "${server.url}/rest/$method.view?${authQuery(server)}$extra"
    }

    /**
     * scrobble's parameters. `submission=false` is "now playing"; `true` counts
     * the play, stamped with [timeMillis] (when it started) so the server's
     * history and any Last.fm relay it runs date it right.
     */
    fun scrobbleParams(songId: String, submission: Boolean, timeMillis: Long? = null): List<Pair<String, String>> =
        buildList {
            add("id" to songId)
            add("submission" to submission.toString())
            if (submission && timeMillis != null) add("time" to timeMillis.toString())
        }

    /**
     * The song's stream. With no [maxBitRateKbps] (0), the original file as the
     * server has it; with one, an MP3 at that rate at most, which every
     * Subsonic server can transcode to. A file already under the cap is sent
     * as it is.
     */
    fun streamUrl(server: SubsonicServer, songId: String, maxBitRateKbps: Int = 0): String =
        endpoint(
            server,
            "stream",
            buildList {
                add("id" to songId)
                if (maxBitRateKbps > 0) {
                    add("maxBitRate" to maxBitRateKbps.toString())
                    add("format" to "mp3")
                }
            },
        )

    /** The mobile-data choices, kbps: the original file (0), then the MP3 caps. */
    val MOBILE_BITRATES = listOf(0, 320, 192, 128)

    fun coverArtUrl(server: SubsonicServer, coverArtId: String?, size: Int = COVER_SIZE): String? =
        coverArtId?.takeIf { it.isNotBlank() }
            ?.let { endpoint(server, "getCoverArt", listOf("id" to it, "size" to size.toString())) }

    const val COVER_SCHEME = "subsonic-cover"
    const val COVER_SIZE = 600

    /**
     * What a song, album or artist carries as its artwork: `subsonic-cover://<id>`.
     *
     * Never the real getCoverArt URL, because that URL carries the sign-in (the
     * token, or in [SubsonicAuthMode.PASSWORD] the password itself), and
     * artwork travels: to the media session other apps can read, the widget's
     * stored snapshot, Discord presence, listening-party guests and the synced
     * history. Only this app's own image loader turns the reference into a URL,
     * at load time (SubsonicCoverMapper), and the notification gets the
     * picture's bytes (StreamResolver).
     */
    fun coverArtRef(coverArtId: String?): String? =
        coverArtId?.takeIf { it.isNotBlank() }?.let { "$COVER_SCHEME://${URLEncoder.encode(it, "UTF-8")}" }

    /** The cover id inside a [coverArtRef], or null when [ref] is not one. */
    fun coverArtId(ref: String?): String? {
        if (ref == null || !ref.startsWith("$COVER_SCHEME://")) return null
        return java.net.URLDecoder.decode(ref.removePrefix("$COVER_SCHEME://"), "UTF-8").takeIf { it.isNotBlank() }
    }

    private fun List<Pair<String, String>>.toQuery(): String =
        joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
