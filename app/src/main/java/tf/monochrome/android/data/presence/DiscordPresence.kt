package tf.monochrome.android.data.presence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The Discord gateway, as pure data: the frames sent, the frames understood,
 * and the activity a playing track becomes.
 *
 * Separated from the socket so all of it can be tested without one. A presence
 * bug is nearly invisible from inside the app — the card renders on somebody
 * else's screen, and a malformed activity is dropped by Discord in silence
 * rather than answered with an error — so the payloads are the part worth
 * pinning down exactly.
 *
 * **This is not the official Rich Presence path.** Discord's own SDK talks to
 * the *desktop* client over a local socket; Android has no equivalent and no
 * REST endpoint for setting your own presence. The only mechanism that works
 * from a phone is holding a gateway connection as the user and sending
 * [presenceFrame], which is what every Android presence app does. It is
 * automating a user account, which Discord's terms prohibit, and the token it
 * needs is unscoped. Both facts belong in front of anyone switching it on.
 */
object DiscordPresence {

    const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"

    /** Opcodes this client sends or acts on. Everything else is ignored. */
    const val OP_DISPATCH = 0
    const val OP_HEARTBEAT = 1
    const val OP_IDENTIFY = 2
    const val OP_PRESENCE_UPDATE = 3
    const val OP_RECONNECT = 7
    const val OP_INVALID_SESSION = 9
    const val OP_HELLO = 10
    const val OP_HEARTBEAT_ACK = 11

    /**
     * Activity type 2 — "Listening to …".
     *
     * The type is what decides the whole shape of the card. Type 0 (Playing)
     * puts the app name on the top line and pushes the track down; type 2 reads
     * "Listening to Tryptify" with the track as the heading under it, which is
     * the layout people recognise from Spotify.
     */
    const val ACTIVITY_LISTENING = 2

    /** How far the play head may drift before an update is worth sending. */
    const val POSITION_TOLERANCE_MS = 2_000L

    /** What Discord should be told is playing. */
    data class NowPlaying(
        val title: String,
        val artist: String?,
        val album: String?,
        /** Already an `mp:external/…` path, or an https URL, or null for no art. */
        val artworkAsset: String?,
        val positionMs: Long,
        val durationMs: Long,
        val paused: Boolean,
    ) {
        /**
         * Whether this says the same thing as [other], for dropping repeats.
         *
         * The position is compared with a tolerance rather than exactly: the
         * three callbacks that push one song each read the play head a few
         * hundred milliseconds apart, and a bar that is two seconds out is not
         * a difference anyone can see — while a frame Discord counts against a
         * rate limit is a real cost. A seek, which is the case that must never
         * be swallowed, moves it far further than this.
         */
        fun sameAs(other: NowPlaying): Boolean =
            title == other.title &&
                artist == other.artist &&
                album == other.album &&
                paused == other.paused &&
                durationMs == other.durationMs &&
                kotlin.math.abs(positionMs - other.positionMs) < POSITION_TOLERANCE_MS
    }

    /**
     * The activity object for a track.
     *
     * `timestamps` is the interesting part: Discord draws the progress bar
     * itself from `start` and `end`, and then animates it locally. That means a
     * frame is only needed when something actually changes — a track, a seek, a
     * pause — rather than once a second, which is the difference between a
     * feature and a battery complaint.
     *
     * A paused track sends no timestamps at all. Leaving them in would leave a
     * bar visibly advancing through music that is not playing, and Discord has
     * no "paused" state to express instead.
     */
    fun activity(now: NowPlaying, appName: String, applicationId: String?): JsonObject =
        buildJsonObject {
            put("name", appName)
            put("type", ACTIVITY_LISTENING)
            // Discord requires both of these to be at least two characters and
            // silently drops the whole activity if either is shorter, which is
            // a real case: tracks called "4" exist.
            put("details", pad(now.title))
            now.artist?.takeIf { it.isNotBlank() }?.let { put("state", pad(it)) }
            if (!applicationId.isNullOrBlank()) put("application_id", applicationId)

            if (!now.paused && now.durationMs > 0) {
                val start = System.currentTimeMillis() - now.positionMs
                putJsonObject("timestamps") {
                    put("start", start)
                    put("end", start + now.durationMs)
                }
            }

            if (now.artworkAsset != null || now.album != null) {
                putJsonObject("assets") {
                    now.artworkAsset?.let { put("large_image", it) }
                    now.album?.takeIf { it.isNotBlank() }?.let { put("large_text", pad(it)) }
                    if (now.paused) {
                        // The only way to say "paused" on a card that has no
                        // such state: the small badge over the artwork.
                        put("small_text", "Paused")
                    }
                }
            }
        }

    /** The opening frame: who we are, and what we're listening to already. */
    fun identifyFrame(token: String, activity: JsonObject?): String = frame(OP_IDENTIFY) {
        put("token", token)
        put("capabilities", 0)
        putJsonObject("properties") {
            // Presented as the Android client, because that is what this is —
            // a phone holding a presence. Claiming a desktop client here would
            // put a desktop icon on a status coming from a phone.
            //
            // This shape is confirmed accepted by the gateway, which is the
            // only reason it looks the way it does. It was briefly cut back to
            // three fields on a theory that a fuller block was what earned a
            // 4004; the 4004 turned out to be a mistyped token, and the theory
            // was wrong. Left as the version known to work rather than the
            // version that seems tidier.
            put("os", "Android")
            put("browser", "Discord Android")
            put("device", "android")
            put("system_locale", "en-US")
            put("client_version", CLIENT_VERSION)
            put("release_channel", "googleRelease")
            put("device_vendor_id", "")
            put("browser_user_agent", "")
            put("browser_version", "")
            put("os_version", "")
            put("client_build_number", CLIENT_BUILD)
            put("client_event_source", JsonNull)
            put("design_id", 0)
        }
        put("compress", false)
        put("presence", presenceData(activity))
    }

    /** A presence change on an already-open connection. */
    fun presenceFrame(activity: JsonObject?): String = frame(OP_PRESENCE_UPDATE) {
        presenceData(activity).forEach { (k, v) -> put(k, v) }
    }

    /** The keepalive. `seq` is the last dispatch sequence, or null before the first. */
    fun heartbeatFrame(seq: Int?): String =
        if (seq == null) """{"op":$OP_HEARTBEAT,"d":null}"""
        else """{"op":$OP_HEARTBEAT,"d":$seq}"""

    private fun presenceData(activity: JsonObject?): JsonObject = buildJsonObject {
        put("status", "online")
        put("since", 0)
        put("afk", false)
        putJsonArray("activities") { activity?.let { add(it) } }
    }

    /** What the gateway just said, reduced to the parts this client acts on. */
    sealed interface Frame {
        data class Hello(val heartbeatIntervalMs: Long) : Frame
        data class Dispatch(val event: String?, val seq: Int?) : Frame
        data object HeartbeatAck : Frame
        data object HeartbeatRequest : Frame
        data object Reconnect : Frame
        /** [resumable] false means the token was refused — reconnecting will not help. */
        data class InvalidSession(val resumable: Boolean) : Frame
        data object Ignored : Frame
    }

    fun parseFrame(text: String): Frame {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return Frame.Ignored
        val seq = root["s"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull()
        return when (root["op"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull()) {
            OP_HELLO -> {
                val interval = root["d"]?.jsonObject
                    ?.get("heartbeat_interval")?.jsonPrimitive?.contentOrNull()?.toLongOrNull()
                Frame.Hello(interval ?: DEFAULT_HEARTBEAT_MS)
            }
            OP_HEARTBEAT -> Frame.HeartbeatRequest
            OP_HEARTBEAT_ACK -> Frame.HeartbeatAck
            OP_RECONNECT -> Frame.Reconnect
            OP_INVALID_SESSION ->
                Frame.InvalidSession(root["d"]?.jsonPrimitive?.contentOrNull() == "true")
            OP_DISPATCH -> Frame.Dispatch(root["t"]?.jsonPrimitive?.contentOrNull(), seq)
            else -> Frame.Ignored
        }
    }

    /**
     * Clean up a pasted token.
     *
     * Copying one out of a browser's network inspector picks up whatever was
     * around it — a trailing newline, the quotes from a JSON view, sometimes an
     * `Authorization:` label or a scheme word. None of that is visible in a
     * single-line text field, and all of it produces the same 4004 as a genuinely
     * wrong token, which is an unpleasant thing to debug by eye.
     */
    fun normalizeToken(raw: String): String {
        var value = raw.trim().trim('"', '\'', '`')
        for (prefix in listOf("authorization:", "authorization", "bearer ", "bot ")) {
            if (value.startsWith(prefix, ignoreCase = true)) {
                value = value.removeRange(0, prefix.length).trim()
            }
        }
        return value.trim().trim('"', '\'')
    }

    /**
     * Whether [token] is even shaped like a Discord user token.
     *
     * Three dot-separated parts: the base64 user id, a timestamp, and an HMAC.
     * Worth checking before anything is sent, because the failure for a
     * malformed token and for a revoked one is the identical 4004 — and only
     * one of those is worth fetching a new token over.
     */
    fun looksLikeToken(token: String): Boolean {
        val parts = token.split('.')
        return parts.size == 3 && parts.all { it.isNotBlank() } && parts[0].length >= 16
    }

    /** Why the gateway hung up, and whether trying again could ever help. */
    data class CloseVerdict(val fatal: Boolean, val message: String)

    /**
     * Read a websocket close code.
     *
     * This is the half of the protocol that is easy to forget exists, and
     * forgetting it is expensive: **Discord refuses a bad token by closing the
     * connection with 4004, not by sending an opcode**. A client that only
     * watches for opcode 9 sees a rejected token as an ordinary disconnect,
     * reconnects, gets closed again, and sits on "connecting" forever while
     * hammering an endpoint that will never say yes. Every failure looks like a
     * network blip.
     *
     * 4008 is the other one worth naming rather than retrying blind — it is the
     * rate limit, and backing off is the only response to it.
     */
    fun readClose(code: Int?, reason: String?): CloseVerdict = when (code) {
        4004 -> CloseVerdict(
            fatal = true,
            message = "Discord rejected the token (4004). It rotates whenever you " +
                "change your password or log out everywhere — paste a fresh one.",
        )
        4010, 4011, 4012, 4013, 4014 -> CloseVerdict(
            fatal = true,
            message = "Discord refused this connection ($code)" +
                reason?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty(),
        )
        4008 -> CloseVerdict(
            fatal = false,
            message = "Discord is rate limiting this account (4008). Waiting before retrying.",
        )
        null -> CloseVerdict(fatal = false, message = "Lost the connection to Discord. Retrying.")
        else -> CloseVerdict(
            fatal = false,
            message = "Discord closed the connection ($code). Retrying.",
        )
    }

    /**
     * Every Discord activity string must be 2–128 characters. One character is
     * rejected, so a track called "4" would take the whole presence down with
     * it — pad rather than drop.
     */
    internal fun pad(value: String): String {
        val trimmed = value.trim().take(128)
        return if (trimmed.length < 2) trimmed.padEnd(2, ' ') else trimmed
    }

    private fun frame(op: Int, data: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        buildJsonObject {
            put("op", op)
            putJsonObject("d", data)
        }.toString()

    /** The client Discord is told this is. Part of an accepted handshake. */
    private const val CLIENT_VERSION = "277.15 - rn"
    private const val CLIENT_BUILD = 2938

    private const val DEFAULT_HEARTBEAT_MS = 41_250L
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    content.takeIf { it.isNotEmpty() && it != "null" }
