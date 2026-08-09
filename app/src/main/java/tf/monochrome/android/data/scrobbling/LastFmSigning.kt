package tf.monochrome.android.data.scrobbling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * The signed half of the Last.fm API: the request signature, the authorisation
 * URL, and the two shapes `auth.getSession` can answer with.
 *
 * Pure functions in their own file rather than methods on the service, for two
 * reasons. The signature is the part that silently breaks — a wrong one is not
 * an error you can see, it is a scrobble Last.fm quietly discards, which is
 * exactly how this app spent its whole history signing with the literal string
 * `"dummy_secret"` and looking like it worked. And it is now needed in two
 * places: scrobbling signs `track.scrobble`, and authorisation signs
 * `auth.getSession`. One implementation, tested against a fixed vector, is the
 * only version of that worth having.
 */
object LastFmSigning {

    const val AUTH_URL = "https://www.last.fm/api/auth/"
    const val API_URL = "https://ws.audioscrobbler.com/2.0/"

    /**
     * Where Last.fm sends the browser back to once the listener has said yes.
     *
     * A custom scheme rather than an https URL: there is no server in this app
     * to receive a redirect, and the token in the callback is single-use and
     * useless without the shared secret that only this install holds. The same
     * scheme the Spotify flow already claims, under its own host.
     */
    const val CALLBACK_URL = "tryptify://lastfm-callback"

    /**
     * `api_sig`: every parameter, sorted by name, key and value run together
     * with no separators, then the shared secret, then MD5.
     *
     * Two exclusions matter and neither is obvious from the signature line
     * alone. `format` is not signed — it is a transport choice, and Last.fm
     * strips it before checking. And `api_sig` is not signed either, for the
     * obvious reason. Passing a map that already contains one produces a
     * signature that can never verify, so both are dropped here rather than
     * being left as a rule the callers have to remember.
     */
    fun sign(params: Map<String, String>, sharedSecret: String): String {
        val payload = params
            .filterKeys { it != "format" && it != "api_sig" }
            .toSortedMap()
            .entries
            .joinToString("") { "${it.key}${it.value}" }
        return md5(payload + sharedSecret)
    }

    fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * The page the listener is sent to.
     *
     * `cb` is what makes this a callback flow at all. Without it Last.fm shows
     * a page telling the user to return to the application by hand, which is
     * the flow this replaces — and the reason connecting used to require
     * pasting a session key nothing in the app could produce.
     */
    fun authorizeUrl(apiKey: String, callback: String = CALLBACK_URL): String =
        "$AUTH_URL?api_key=$apiKey&cb=$callback"

    /** The parameters of the `auth.getSession` call, unsigned. */
    fun sessionParams(apiKey: String, token: String): Map<String, String> = mapOf(
        "method" to "auth.getSession",
        "api_key" to apiKey,
        "token" to token,
    )

    /** What Last.fm said when asked to trade a token for a session. */
    sealed interface SessionResult {
        data class Granted(val sessionKey: String, val username: String) : SessionResult
        data class Refused(val code: Int?, val message: String) : SessionResult
    }

    /**
     * Read the `auth.getSession` response.
     *
     * Last.fm answers a failure with HTTP 403 *and* a JSON body carrying a
     * numbered error, and the number is the only thing that distinguishes "your
     * token expired, try again" from "your API key is wrong, trying again will
     * never work". Both were previously the same silent nothing, so the code is
     * kept and turned into a sentence at the call site.
     */
    fun parseSession(body: String): SessionResult {
        val root = runCatching { lastFmJson.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return SessionResult.Refused(null, "Last.fm sent a reply this app couldn't read.")

        root["session"]?.let { session ->
            val obj = runCatching { session.jsonObject }.getOrNull()
            val key = obj?.get("key")?.jsonPrimitive?.contentOrNull()
            val name = obj?.get("name")?.jsonPrimitive?.contentOrNull()
            if (!key.isNullOrBlank()) {
                return SessionResult.Granted(key, name.orEmpty())
            }
        }

        val code = root["error"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull()
        val message = root["message"]?.jsonPrimitive?.contentOrNull()
        return SessionResult.Refused(code, message ?: "Last.fm refused the connection.")
    }

    /** A sentence for a numbered Last.fm error, in the terms of what to do next. */
    fun explain(code: Int?, message: String): String = when (code) {
        4 -> "Last.fm rejected the authorisation. It expires quickly — try connecting again."
        10 -> "That API key isn't valid. Check the key and secret in Your API key."
        13 -> "The request signature didn't match. Check the shared secret in Your API key."
        14 -> "Last.fm hasn't been given permission yet. Finish the page in the browser, then try again."
        15 -> "That authorisation has already been used. Try connecting again."
        26 -> "This API key has been suspended by Last.fm."
        29 -> "Too many requests to Last.fm just now. Wait a moment and try again."
        else -> message
    }

    private val lastFmJson = Json { ignoreUnknownKeys = true; isLenient = true }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    content.takeIf { it.isNotEmpty() && it != "null" }
