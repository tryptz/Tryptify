// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.party

import kotlin.math.roundToLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/*
 * A listening party on the rooms server, as the wire has it: JSON, one object
 * per WebSocket text frame, tagged by `t`, the same socket and hello as Trypt
 * Mania's rooms (tryptz/tryptify-party's `src/protocol.rs` is the other
 * side). Times are ms; the server's clock is UNIX time, and [ClockSync] gives
 * this phone's gap to it.
 */

/** A song shared with the party: uploading until [ready], then fetched by whoever has no copy. */
@Serializable
data class PartySong(
    val ready: Boolean = false,
    val bytes: Long = 0,
    val received: Long = 0,
    val ext: String = "",
) {
    /** How far the upload is, 0 to 1; 0 while its size is not known. */
    val progress: Float get() = if (bytes > 0) (received.toFloat() / bytes).coerceIn(0f, 1f) else 0f
}

/** A song in the party's queue, or the one playing. */
@Serializable
data class PartyItem(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val addedBy: String = "",
    val addedByName: String = "",
    val isrc: String? = null,
    val catalogId: String? = null,
    val audioHash: String? = null,
    val cover: String? = null,
    /** Null: not shared; every phone plays its own copy or sits it out. */
    val shared: PartySong? = null,
)

/**
 * Where the party is in its song: at server time [atServerMs] the song
 * [itemId] was at [positionMs], and it runs on from there unless paused.
 */
@Serializable
data class PartyPlayback(
    val itemId: String? = null,
    val positionMs: Long = 0,
    val atServerMs: Long = 0,
    val paused: Boolean = false,
    /** The host's speed: 1.25 plays 1.25 s of the song a second. */
    val rate: Double = 1.0,
    /** Whether the speed keeps the pitch; false is vinyl style, the pitch riding it. */
    val keepPitch: Boolean = true,
) {
    /** The song's position at server time [serverNowMs]. */
    fun positionAt(serverNowMs: Long): Long =
        if (paused) positionMs else positionMs + ((serverNowMs - atServerMs).coerceAtLeast(0) * rate).roundToLong()
}

@Serializable
data class PartyMember(
    val id: String,
    val name: String,
    val user: String? = null,
    /** A website guest: listens and chats, adds nothing. */
    val guest: Boolean = false,
    /** Listening in a browser. */
    val web: Boolean = false,
    /** Lost their connection and has a minute to come back. */
    val away: Boolean = false,
)

@Serializable
data class PartySkip(val votes: List<String> = emptyList(), val needed: Int = 1)

@Serializable
data class PartyView(
    val code: String,
    val name: String,
    /** Empty while only website guests are left. */
    val hostId: String = "",
    val members: List<PartyMember> = emptyList(),
    val now: PartyItem? = null,
    val queue: List<PartyItem> = emptyList(),
    val playback: PartyPlayback = PartyPlayback(),
    val skip: PartySkip = PartySkip(),
    val sharedBytes: Long = 0,
    val sharedLimit: Long = 0,
) {
    val host: PartyMember? get() = members.firstOrNull { it.id == hostId }

    /** Everyone here now, the away left out. */
    val listening: Int get() = members.count { !it.away }

    /** Of those, how many are in a browser. */
    val onTheWeb: Int get() = members.count { !it.away && it.web }
}

enum class PartyLineKind { SAY, JOIN, LEAVE, ADD, SKIP, HOST }

/**
 * A line of the party's chat. The server's own lines (joins, songs added,
 * skips) carry their [kind] and the names in them; the app says them in the
 * listener's language.
 */
data class PartyLine(
    val kind: PartyLineKind,
    val id: String?,
    val name: String,
    val text: String,
    val at: Long,
)

sealed interface PartyMsg {
    data class Welcome(val id: String, val serverTime: Long, val key: String, val resumed: Boolean) : PartyMsg
    data class Pong(val c: Long, val s: Long) : PartyMsg
    data class Joined(val code: String, val songToken: String) : PartyMsg
    data class Party(val party: PartyView) : PartyMsg
    data class History(val lines: List<PartyLine>) : PartyMsg
    data class Line(val line: PartyLine) : PartyMsg
    data class Added(val ref: String, val itemId: String) : PartyMsg
    data class Left(val reason: String) : PartyMsg
    data class Error(val message: String) : PartyMsg
    data object Ignored : PartyMsg
}

/** What a phone puts in the queue: enough for every other phone to find its own copy. */
data class PartyAdd(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val isrc: String? = null,
    val catalogId: String? = null,
    val audioHash: String? = null,
    val cover: String? = null,
    val share: Boolean = false,
)

object PartyProtocol {
    /** The server's words for a party code nobody holds any more. */
    const val NO_PARTY = "no party with that code"

    fun parse(json: Json, text: String): PartyMsg {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return PartyMsg.Ignored
        fun str(key: String) = root[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun long(key: String) = root[key]?.jsonPrimitive?.longOrNull ?: 0L
        return runCatching {
            when (str("t")) {
                "welcome" -> PartyMsg.Welcome(
                    str("id"), long("serverTime"), str("key"),
                    root["resumed"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
                "pong" -> PartyMsg.Pong(long("c"), long("s"))
                "joined" -> PartyMsg.Joined(str("code"), str("songToken"))
                "party" -> PartyMsg.Party(json.decodeFromJsonElement(root.getValue("party")))
                "history" -> PartyMsg.History(
                    (root["lines"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { line(it) },
                )
                "line" -> line(root.getValue("line"))?.let { PartyMsg.Line(it) } ?: PartyMsg.Ignored
                "added" -> PartyMsg.Added(str("ref"), str("itemId"))
                "left" -> PartyMsg.Left(str("reason"))
                "error" -> PartyMsg.Error(str("message"))
                else -> PartyMsg.Ignored
            }
        }.getOrElse { PartyMsg.Error("unreadable message: ${it.message}") }
    }

    private fun line(element: JsonElement): PartyLine? {
        val o = element as? JsonObject ?: return null
        fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull
        val kind = when (str("kind")) {
            "say" -> PartyLineKind.SAY
            "join" -> PartyLineKind.JOIN
            "leave" -> PartyLineKind.LEAVE
            "add" -> PartyLineKind.ADD
            "skip" -> PartyLineKind.SKIP
            "host" -> PartyLineKind.HOST
            else -> return null
        }
        return PartyLine(kind, str("id"), str("name").orEmpty(), str("text").orEmpty(), o["at"]?.jsonPrimitive?.longOrNull ?: 0L)
    }

    private fun msg(t: String, vararg fields: Pair<String, JsonElement>): String =
        JsonObject(mapOf("t" to JsonPrimitive(t)) + fields.toMap()).toString()

    private fun opt(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

    /** [resume] is the key an earlier welcome gave this phone: within a minute it gets the seat back. */
    fun hello(name: String, client: String, token: String, resume: String = ""): String =
        msg(
            "hello",
            "name" to JsonPrimitive(name), "client" to JsonPrimitive(client),
            "token" to JsonPrimitive(token), "resume" to JsonPrimitive(resume),
        )
    fun ping(clientMs: Long): String = msg("ping", "c" to JsonPrimitive(clientMs))
    fun create(name: String): String = msg("create", "name" to JsonPrimitive(name), "kind" to JsonPrimitive("party"))
    fun join(code: String): String = msg("join", "code" to JsonPrimitive(code.trim().uppercase()))
    fun leave(): String = msg("leave")
    fun chat(text: String): String = msg("chat", "text" to JsonPrimitive(text))
    fun add(item: PartyAdd, ref: String): String = msg(
        "queueAdd",
        "ref" to JsonPrimitive(ref),
        "item" to JsonObject(
            mapOf(
                "title" to JsonPrimitive(item.title),
                "artist" to JsonPrimitive(item.artist),
                "album" to JsonPrimitive(item.album),
                "durationMs" to JsonPrimitive(item.durationMs),
                "isrc" to opt(item.isrc),
                "catalogId" to opt(item.catalogId),
                "audioHash" to opt(item.audioHash),
                "cover" to opt(item.cover),
                "share" to JsonPrimitive(item.share),
            ),
        ),
    )
    fun remove(itemId: String): String = msg("queueRemove", "id" to JsonPrimitive(itemId))
    fun move(itemId: String, to: Int): String = msg("queueMove", "id" to JsonPrimitive(itemId), "to" to JsonPrimitive(to))
    fun pause(): String = msg("pause")
    fun resume(): String = msg("resume")
    fun seek(positionMs: Long): String = msg("seek", "positionMs" to JsonPrimitive(positionMs))
    fun skip(itemId: String): String = msg("skip", "itemId" to JsonPrimitive(itemId))
    fun skipVote(itemId: String, vote: Boolean): String =
        msg("skipVote", "itemId" to JsonPrimitive(itemId), "vote" to JsonPrimitive(vote))
    fun kick(id: String): String = msg("kick", "id" to JsonPrimitive(id))
    fun passHost(id: String): String = msg("passHost", "id" to JsonPrimitive(id))
    fun setRate(rate: Float, keepPitch: Boolean): String = msg(
        "setRate",
        "rate" to JsonPrimitive(rate.coerceIn(MIN_RATE, MAX_RATE).toDouble()),
        "keepPitch" to JsonPrimitive(keepPitch),
    )

    /** The speeds a party plays at, as the server allows them. */
    const val MIN_RATE = 0.25f
    const val MAX_RATE = 4f
    fun endParty(): String = msg("endParty")

    /** The reason the server gives everyone when the host ends the party. */
    const val HOST_ENDED = "the host ended the party"

    /** Song [itemId]'s address on [server] (`https://host/secret`); with [ext] when uploading. */
    fun songUrl(server: String, itemId: String, ext: String? = null): String {
        val base = "${server.trimEnd('/')}/api/party/song?item=${URLEncoder.encode(itemId, "UTF-8")}"
        return if (ext.isNullOrBlank()) base else "$base&ext=${URLEncoder.encode(ext, "UTF-8")}"
    }

    /**
     * The link a guest opens: the party's page on tryptify.com for [code],
     * which opens in the app where it is installed. Never the rooms server's
     * own address.
     */
    fun inviteLink(code: String): String = "$INVITE_BASE${code.uppercase()}"

    /** Where invites point: the website's party page. */
    const val INVITE_BASE = "https://tryptify.com/party/"

    /**
     * The party code in an invite link: `https://tryptify.com/party/<code>`,
     * a translated page's (`/de/party/<code>`), or an older app's
     * (`https://host/<secret>/party/<code>`); null for any other link.
     */
    fun inviteCode(scheme: String?, path: String?): String? {
        if (!scheme.equals("https", ignoreCase = true) || path == null) return null
        return INVITE_PATH.matchEntire(path)?.groupValues?.get(1)?.uppercase()
    }

    // Any secret path before /party/, but never the server's own /api/party/… routes.
    private val INVITE_PATH = Regex("""(?:/(?!api/)[^/]+)*/party/([A-Za-z0-9]{4,8})/?""")

    /** Whether a rooms server's info (`/api/multiplayer/info`) says it holds parties. */
    fun supportsParties(json: Json, info: String): Boolean = runCatching {
        val data = json.parseToJsonElement(info).jsonObject["data"]?.jsonObject ?: return false
        (data["features"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .any { it.jsonPrimitive.contentOrNull == "party" }
    }.getOrDefault(false)
}

/**
 * The party's moment as a position in its song, and the rule for when a
 * player out of step is moved to it.
 */
object PartySync {
    /**
     * How far from the party's moment a player may drift before it is moved:
     * more than a room of people in different places can hear, less than a
     * line of a song. A seek drops the sound while the player refills, so it
     * is the last resort, not a correction made every second. A second, the
     * owner's call: at 600 ms phones were still being moved more than heard.
     */
    const val TOLERANCE_MS = 1_000L

    fun needsSeek(localMs: Long, targetMs: Long, toleranceMs: Long = TOLERANCE_MS): Boolean =
        kotlin.math.abs(localMs - targetMs) > toleranceMs

    /** The party's moment as a position in a song of [durationMs] (0: not known). */
    fun target(playback: PartyPlayback, serverNowMs: Long, durationMs: Long): Long {
        val at = playback.positionAt(serverNowMs)
        return if (durationMs > 0) at.coerceIn(0, durationMs) else at.coerceAtLeast(0)
    }
}

/**
 * When to seek a player that has drifted from the party, and where to.
 *
 * Seeking every second the player was a quarter second out made the sound
 * drop every second: each seek costs the time the player takes to refill,
 * which leaves it behind again, so the next check seeked again. Now:
 *
 * - nothing is checked for [settleMs] after a song is loaded or a seek, while
 *   the player settles;
 * - a drift past [PartySync.TOLERANCE_MS] has to be seen [confirm] times in a
 *   row, so a moment of clock jitter or a position read mid-update moves
 *   nothing;
 * - a seek aims ahead of the party by what the last seeks cost ([leadMs]),
 *   learnt from where the player lands, so one seek lands in step.
 */
class PartyDrift(
    private val toleranceMs: Long = PartySync.TOLERANCE_MS,
    private val settleMs: Long = 4_000,
    private val confirm: Int = 2,
) {
    /** How far ahead of the party a seek aims: what a seek has been costing. */
    var leadMs: Long = 250
        private set
    private var quietUntil = 0L
    private var strikes = 0
    private var measuring = false

    /** A song loaded (or reloaded) at [nowMs]: give it time to start. */
    fun loaded(nowMs: Long) {
        quietUntil = nowMs + settleMs
        strikes = 0
        measuring = false
    }

    /**
     * The party moved on purpose (the host seeked or resumed): the next check
     * may seek at once, without the settling or the second look a drift gets.
     */
    fun moved() {
        quietUntil = 0
        strikes = confirm - 1
    }

    /** How far out the player is: positive ahead of the party, negative behind. */
    fun drift(localMs: Long, targetMs: Long): Long = localMs - targetMs

    /**
     * The player at [localMs] and the party at [targetMs], at [nowMs] on this
     * phone's clock: where to seek to, or null to leave it playing.
     */
    fun check(localMs: Long, targetMs: Long, nowMs: Long): Long? {
        if (nowMs < quietUntil) return null
        val drift = drift(localMs, targetMs)
        if (measuring) {
            // Where the last seek landed says what a seek costs: behind by
            // that much means aim that much further ahead next time.
            leadMs = (leadMs - drift).coerceIn(0, MAX_LEAD_MS)
            measuring = false
        }
        if (kotlin.math.abs(drift) <= toleranceMs) {
            strikes = 0
            return null
        }
        if (++strikes < confirm) return null
        strikes = 0
        quietUntil = nowMs + settleMs
        measuring = true
        return targetMs + leadMs
    }

    private companion object {
        const val MAX_LEAD_MS = 2_000L
    }
}
