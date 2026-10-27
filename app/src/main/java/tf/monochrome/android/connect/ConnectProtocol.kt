// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.connect

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tryptify Connect's messages as the app speaks them: the same protocol as
 * the web player's (tryptify-servers/connect/README.md). Pure, so it is tested on the
 * desktop (ConnectProtocolTest).
 */

/** A song as devices pass it around: enough for another device to find and play it, and to show it. */
@Serializable
data class ConnectTrack(
    /** "hifi" (a TIDAL id), "cloud", "subsonic", "qobuz", "deezer", "apple", or "local" (this device's own file). */
    val src: String,
    val id: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Double? = null,
    val quality: String = "",
    val cover: String? = null,
    val coverArt: String? = null,
)

/** What plays, on which device: [position] seconds at server time [at]; [since] is when it started playing there. */
@Serializable
data class ConnectNow(
    val device: String = "",
    val name: String? = null,
    val playing: Boolean = false,
    val track: ConnectTrack? = null,
    val position: Double = 0.0,
    val rate: Double = 1.0,
    val at: Long = 0,
    val since: Long = 0,
)

@Serializable
data class ConnectDevice(
    val id: String,
    val name: String = "",
    val kind: String = "web",
    val since: Long = 0,
    val active: Boolean = false,
)

/** Any message from the room; [type] says which fields it carries. */
@Serializable
data class ConnectIncoming(
    val type: String = "",
    val serverTime: Long? = null,
    val you: String? = null,
    val devices: List<ConnectDevice>? = null,
    val now: ConnectNow? = null,
    val from: String? = null,
    val to: String? = null,
    val toName: String? = null,
    val action: String? = null,
    val value: Double? = null,
    val queue: List<ConnectTrack>? = null,
    val message: String? = null,
)

object ConnectProtocol {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    /** A message from the room, or null for anything else (the "pong" keep-alive, junk). */
    fun parse(text: String): ConnectIncoming? =
        if (text == "pong") null else runCatching { json.decodeFromString<ConnectIncoming>(text) }.getOrNull()?.takeIf { it.type.isNotEmpty() }

    /** Where [now] is at server time [serverTimeMs]: playing time runs on at its rate, never past the end. */
    fun positionAt(now: ConnectNow, serverTimeMs: Long): Double {
        if (!now.playing) return now.position
        val p = now.position + (serverTimeMs - now.at).coerceAtLeast(0) / 1000.0 * now.rate
        val d = now.track?.duration
        return if (d != null && d > 0) p.coerceAtMost(d) else p
    }

    /**
     * Whether this device stops for [other]: it plays, it is another device,
     * and it started after this one did. A late message from an earlier start
     * never stops a later one, whichever arrives first.
     */
    fun shouldMakeWay(other: ConnectNow?, myId: String, playingHere: Boolean, mySince: Long): Boolean =
        other != null && other.playing && other.device != myId && playingHere &&
            (if (other.since > 0) other.since else other.at) >= mySince

    /** Only plain https covers travel: a music server's carry its sign-in, and a file's own picture is this phone's. */
    fun shareableCover(url: String?): String? {
        if (url.isNullOrBlank() || !url.startsWith("https://")) return null
        val query = url.substringAfter('?', "")
        if (query.split('&').any { it.substringBefore('=') in SIGN_IN_PARAMS }) return null
        return url
    }

    /** A device id as the room takes it: 8–64 of A–Z, a–z, 0–9, - and _. */
    fun isDeviceId(id: String): Boolean = id.length in 8..64 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }

    private fun stateFields(now: StateOut): JsonObject = buildJsonObject {
        put("playing", now.playing)
        now.track?.let { put("track", json.encodeToJsonElement(ConnectTrack.serializer(), it)) }
        put("position", now.position)
        put("rate", now.rate)
        put("since", now.since)
    }

    fun state(now: StateOut): String = json.encodeToString(JsonObject(mapOf("type" to JsonPrimitive("state")) + stateFields(now)))

    fun command(to: String, action: String, value: Double? = null): String = json.encodeToString(buildJsonObject {
        put("type", "command"); put("to", to); put("action", action); value?.let { put("value", it) }
    })

    fun transfer(to: String, force: Boolean): String = json.encodeToString(buildJsonObject {
        put("type", "transfer"); put("to", to); put("force", force)
    })

    fun handoff(to: String, now: StateOut?, queue: List<ConnectTrack>): String = json.encodeToString(buildJsonObject {
        put("type", "handoff")
        put("to", to)
        put("now", if (now == null) JsonObject(emptyMap()) else stateFields(now))
        put("queue", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ConnectTrack.serializer()), queue.take(MAX_QUEUE)))
    })

    /** What this device says it plays. */
    data class StateOut(val playing: Boolean, val track: ConnectTrack?, val position: Double, val rate: Double, val since: Long)

    const val MAX_QUEUE = 100

    /** A music server's sign-in in a cover's address: its user, token, salt and password. */
    private val SIGN_IN_PARAMS = setOf("t", "s", "p", "u")
    val ACTIONS = setOf("play", "pause", "next", "prev", "seek")
}
