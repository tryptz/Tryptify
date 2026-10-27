// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.multiplayer

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The rooms server's wire format (TryptMania-Parser, `multiplayer/`): JSON,
 * one object per WebSocket text frame, tagged by `t`. Times are
 * milliseconds; the server's clock is UNIX time, which every phone measures
 * its gap to ([ClockSync]). Pure: no socket in it, so it is tested alone.
 */

/** The chart the host picked: the room's parity check, and its notes once. */
@Serializable
data class RoomChart(
    /** SHA-256 of the chart text ([RoomProtocol.chartHash]). */
    val hash: String,
    val title: String,
    val artist: String,
    val difficulty: String = "",
    /** The song's length in seconds; a phone matches its song by it. */
    val duration: Double = 0.0,
    val bpm: Double = 0.0,
    /** The `.ssc` text itself, sent once to each player; empty in room state. */
    val notes: String = "",
)

/** A player's score as it stands, a few times a second while the song plays. */
@Serializable
data class RoomLive(
    val score: Long = 0L,
    val combo: Int = 0,
    val acc: Double = 0.0,
    val hp: Double = 1.0,
    val pos: Long = 0L,
)

@Serializable
data class RoomFinished(
    val score: Long = 0L,
    val acc: Double = 0.0,
    val maxCombo: Int = 0,
    val grade: String = "",
    val failed: Boolean = false,
    val judgements: Map<String, Int> = emptyMap(),
)

@Serializable
data class RoomPlayer(
    val id: String,
    val name: String,
    val ready: Boolean = false,
    /** Whether the phone has the song: null until it has said. */
    val hasSong: Boolean? = null,
    val live: RoomLive = RoomLive(),
    val finished: RoomFinished? = null,
    /** In the room but not in this song: joined while it played, or has no song. */
    val spectating: Boolean = false,
)

/**
 * The picked chart's song as the host shared it with the room: a phone
 * without it fetches it ([RoomClient.fetchSong]) instead of sitting it out.
 */
@Serializable
data class RoomSharedSong(
    /** The chart it is the song of ([RoomChart.hash]). */
    val hash: String,
    val bytes: Long = 0L,
    /** The host's file extension: `flac`, `mp3`, `m4a`, … */
    val ext: String = "",
)

@Serializable
data class RoomView(
    val code: String,
    val name: String,
    val hostId: String,
    /** `lobby`, `playing` or `results`. */
    val state: String,
    val chart: RoomChart? = null,
    val players: List<RoomPlayer> = emptyList(),
    val startAt: Long? = null,
    /** The picked chart's song, when the host has shared it; null on a server that cannot. */
    val sharedSong: RoomSharedSong? = null,
) {
    val playing: Boolean get() = state == "playing"
    val atResults: Boolean get() = state == "results"
}

@Serializable
data class RoomSummary(
    val code: String,
    val name: String,
    val host: String,
    val players: Int,
    val state: String,
    val chart: String? = null,
)

@Serializable
data class RoomPlacing(val rank: Int, val id: String, val name: String, val result: RoomFinished)

data class RoomChatLine(val id: String, val name: String, val text: String)

/** What the server sends, parsed. */
sealed interface RoomMsg {
    /** [user] is the account the server took this phone for; null for a guest server. */
    /** [key] is this phone's own key for the song routes; empty on a server without them. */
    data class Welcome(
        val id: String,
        val serverTime: Long,
        val version: String,
        val user: String? = null,
        val key: String = "",
    ) : RoomMsg
    data class Pong(val c: Long, val s: Long) : RoomMsg
    data class Rooms(val rooms: List<RoomSummary>) : RoomMsg
    data class Room(val room: RoomView) : RoomMsg
    data class Chart(val chart: RoomChart) : RoomMsg
    data class Start(val at: Long, val hash: String) : RoomMsg
    data class Frames(val players: List<Pair<String, RoomLive>>) : RoomMsg
    data class Results(val ranking: List<RoomPlacing>) : RoomMsg
    data class Chat(val id: String, val name: String, val text: String) : RoomMsg
    data class Left(val reason: String) : RoomMsg
    data class Error(val message: String) : RoomMsg
    data object Ignored : RoomMsg
}

object RoomProtocol {

    fun parse(json: Json, text: String): RoomMsg {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return RoomMsg.Ignored
        fun str(key: String) = root[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun long(key: String) = root[key]?.jsonPrimitive?.longOrNull ?: 0L
        return runCatching {
            when (str("t")) {
                "welcome" -> RoomMsg.Welcome(str("id"), long("serverTime"), str("version"), root["user"]?.jsonPrimitive?.contentOrNull, str("key"))
                "pong" -> RoomMsg.Pong(long("c"), long("s"))
                "rooms" -> RoomMsg.Rooms(root["rooms"]?.let { json.decodeFromJsonElement(it) } ?: emptyList())
                "room" -> RoomMsg.Room(json.decodeFromJsonElement(root.getValue("room")))
                "chart" -> RoomMsg.Chart(json.decodeFromJsonElement(root.getValue("chart")))
                "start" -> RoomMsg.Start(long("at"), str("hash"))
                "frames" -> RoomMsg.Frames(
                    root["players"]?.jsonArray.orEmpty().mapNotNull { pair ->
                        val parts = (pair as? JsonArray) ?: return@mapNotNull null
                        val id = parts.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val live: RoomLive = parts.getOrNull(1)?.let { json.decodeFromJsonElement(it) } ?: return@mapNotNull null
                        id to live
                    },
                )
                "results" -> RoomMsg.Results(root["ranking"]?.let { json.decodeFromJsonElement(it) } ?: emptyList())
                "chat" -> RoomMsg.Chat(str("id"), str("name"), str("text"))
                "left" -> RoomMsg.Left(str("reason"))
                "error" -> RoomMsg.Error(str("message"))
                else -> RoomMsg.Ignored
            }
        }.getOrElse { RoomMsg.Error("unreadable message: ${it.message}") }
    }

    private fun msg(t: String, vararg fields: Pair<String, JsonElement>): String =
        JsonObject(mapOf("t" to JsonPrimitive(t)) + fields.toMap()).toString()

    /** [token] is the signed-in Supabase account's access token; the server checks it when set up to. */
    fun hello(name: String, client: String, token: String = ""): String =
        msg("hello", "name" to JsonPrimitive(name), "client" to JsonPrimitive(client), "token" to JsonPrimitive(token))
    fun ping(clientMs: Long): String = msg("ping", "c" to JsonPrimitive(clientMs))
    fun rooms(): String = msg("rooms")
    fun create(name: String): String = msg("create", "name" to JsonPrimitive(name))
    fun join(code: String): String = msg("join", "code" to JsonPrimitive(code.trim().uppercase()))
    fun leave(): String = msg("leave")
    fun pick(json: Json, chart: RoomChart): String = msg("pick", "chart" to json.encodeToJsonElement(RoomChart.serializer(), chart))
    fun have(audio: Boolean): String = msg("have", "audio" to JsonPrimitive(audio))
    fun ready(ready: Boolean): String = msg("ready", "ready" to JsonPrimitive(ready))
    fun start(): String = msg("start")
    fun kick(id: String): String = msg("kick", "id" to JsonPrimitive(id))
    fun frame(json: Json, live: RoomLive): String = msg("frame", "live" to json.encodeToJsonElement(RoomLive.serializer(), live))
    fun finish(json: Json, result: RoomFinished): String = msg("finish", "result" to json.encodeToJsonElement(RoomFinished.serializer(), result))
    fun chat(text: String): String = msg("chat", "text" to JsonPrimitive(text))

    /**
     * The room's parity check: SHA-256 of the chart text with its line
     * endings and trailing space made one, so the same chart saved on two
     * phones (one by Windows, one not) still hashes the same.
     */
    fun chartHash(text: String): String {
        val normalised = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalised.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Which player a frame pair belongs to, with its name from [room]. */
    /**
     * The shared song's address on [server] (`https://host/secret`), for
     * chart [hash]; with [ext] when uploading, which the server keeps it under.
     */
    fun songUrl(server: String, hash: String, ext: String? = null): String {
        val base = "${server.trimEnd('/')}/api/multiplayer/song?hash=${java.net.URLEncoder.encode(hash, "UTF-8")}"
        return if (ext.isNullOrBlank()) base else "$base&ext=${java.net.URLEncoder.encode(ext, "UTF-8")}"
    }

    /**
     * A song file's extension as the server takes one: lower-case letters and
     * digits, up to five; anything else is `bin`. [name] is a path or a file name.
     */
    fun songExt(name: String): String {
        val ext = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return ext.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) } ?: "bin"
    }

    /**
     * The biggest song the rooms server takes, from its info
     * (`/api/multiplayer/info` → `data.maxSongBytes`); null when it does not say.
     */
    fun maxSongBytes(json: Json, info: String): Long? = runCatching {
        val data = json.parseToJsonElement(info).jsonObject["data"]?.jsonObject ?: return@runCatching null
        (data["maxSongBytes"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
    }.getOrNull()

    fun nameOf(room: RoomView?, id: String): String = room?.players?.firstOrNull { it.id == id }?.name ?: id
}

/**
 * How far this phone's clock is from the server's, from ping/pong as NTP
 * does it: each answer gives the server's time at the middle of a round
 * trip, and the shortest round trip is the surest, so its sample is the
 * one kept. [offsetMs] added to a local time gives the server's.
 */
class ClockSync(private val keep: Int = 8) {
    private val samples = ArrayDeque<Pair<Long, Long>>() // (rtt, offset)

    val offsetMs: Long? get() = samples.minByOrNull { it.first }?.second
    val rttMs: Long? get() = samples.minOfOrNull { it.first }
    val samplesTaken: Int get() = samples.size

    /** A pong: sent at [clientMs], answered at server time [serverMs], received at [receivedMs]. */
    fun sample(clientMs: Long, serverMs: Long, receivedMs: Long) {
        val rtt = (receivedMs - clientMs).coerceAtLeast(0)
        val offset = serverMs - (clientMs + rtt / 2)
        samples.addLast(rtt to offset)
        while (samples.size > keep) samples.removeFirst()
    }

    /** The local wall-clock ms at which the server's [serverMs] falls; the server's own when nothing is known yet. */
    fun localMsFor(serverMs: Long): Long = serverMs - (offsetMs ?: 0L)
}
