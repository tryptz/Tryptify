// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.party

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.android.data.api.ApiService
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.glyph.multiplayer.ClockSync
import tf.monochrome.android.glyph.multiplayer.RoomClient

enum class PartyStatus {
    OFF,
    CONNECTING,
    IN_PARTY,

    /** The socket dropped; the server keeps the seat for a minute while this phone comes back. */
    RECONNECTING,

    /** Over: [PartyState.end] says why. */
    ENDED,
    FAILED,
}

/** Why a party is over for this phone. */
enum class PartyEnd { ENDED, HOST_ENDED, KICKED, LEFT }

/** Everything the party screen and the controller read, as it stands. */
data class PartyState(
    val status: PartyStatus = PartyStatus.OFF,
    val error: String? = null,
    val end: PartyEnd? = null,
    val serverHost: String? = null,
    val myId: String? = null,
    val party: PartyView? = null,
    val lines: List<PartyLine> = emptyList(),
    /** The shortest round trip to the server, ms; null before the first pong. */
    val latencyMs: Long? = null,
) {
    val isHost: Boolean get() = party != null && party.hostId == myId
    val active: Boolean get() = status == PartyStatus.CONNECTING || status == PartyStatus.IN_PARTY || status == PartyStatus.RECONNECTING
}

/**
 * The party's own socket to the rooms server, apart from Trypt Mania's
 * [RoomClient], whose socket lives and dies with the game's screens: a party
 * plays on with the app in the background and every screen closed.
 *
 * Hello, then `create` or `join`; a ping every two seconds for the clock; and
 * when the socket drops, back with `hello.resume` and this phone's key, which
 * gets the seat back if the server still keeps it (five minutes). Away longer,
 * the seat is gone but the party may not be: the phone joins it again by its
 * code, as anyone would, and stays at the party it was at. Only when the
 * party itself is gone (a restart, a deploy, everyone left) is it over: "The
 * party ended". It never lands at another party.
 */
@Singleton
class PartyClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
    private val preferences: PreferencesManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PartyState())
    val state: StateFlow<PartyState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<PartyMsg>(extraBufferCapacity = 64)

    /** What happens once: lines said, songs added, refusals. */
    val events: SharedFlow<PartyMsg> = _events.asSharedFlow()

    /** Whether any server under Settings › Connections hosts rooms. */
    val hasServer: Flow<Boolean> = preferences.apiServers.map { servers -> servers.any { ApiService.MULTIPLAYER in it.services } }

    private val clock = ClockSync()
    private var job: Job? = null

    @Volatile
    private var send: (suspend (String) -> Unit)? = null

    @Volatile
    private var serverUrl: String? = null

    /** This phone's key: for the song routes, and to get its seat back. */
    @Volatile
    private var key: String = ""

    /** What to do once welcomed the first time: make a party, or join one. */
    private sealed interface Intent {
        data class Create(val name: String) : Intent
        data class Join(val code: String) : Intent
    }

    /** Makes a party called [partyName] (blank: "<name>'s party"), as [name] with the account's [token]. */
    fun start(name: String, token: String, partyName: String) = open(name, token, Intent.Create(partyName))

    /** Joins the party with [code]. */
    fun join(name: String, token: String, code: String) = open(name, token, Intent.Join(code))

    private fun open(name: String, token: String, intent: Intent) {
        job?.cancel()
        key = ""
        job = scope.launch {
            val server = preferences.apiServers.first().firstOrNull { ApiService.MULTIPLAYER in it.services }
            if (server == null) {
                _state.value = PartyState(status = PartyStatus.FAILED, error = NO_SERVER)
                return@launch
            }
            serverUrl = server.url
            val host = server.url.substringAfter("://").substringBefore('/')
            _state.value = PartyState(status = PartyStatus.CONNECTING, serverHost = host)
            // A server from before parties would take `create` for a game room
            // and never answer as a party: asked first, and said plainly.
            val holdsParties = runCatching {
                PartyProtocol.supportsParties(json, httpClient.get(server.url.trimEnd('/') + "/api/multiplayer/info").bodyAsText())
            }.getOrDefault(false)
            if (!holdsParties) {
                _state.update { it.copy(status = PartyStatus.FAILED, error = NOT_SUPPORTED) }
                return@launch
            }
            var attempt = 0
            var welcomedOnce = false
            while (isActive) {
                val outcome = runCatching { runSession(RoomClient.wsUrl(server.url), name, token, intent, welcomedOnce) }
                    .onFailure { Log.w(TAG, "party socket: ${it.message}") }
                    .getOrDefault(Outcome.UNREACHED)
                if (!isActive) break
                when (outcome) {
                    Outcome.DONE -> return@launch
                    Outcome.WELCOMED -> { welcomedOnce = true; attempt = 0 }
                    Outcome.UNREACHED -> attempt++
                }
                if (attempt > MAX_ATTEMPTS) {
                    _state.update {
                        if (welcomedOnce) it.copy(status = PartyStatus.ENDED, end = PartyEnd.ENDED, error = null)
                        else it.copy(status = PartyStatus.FAILED, error = it.error ?: "could not reach $host")
                    }
                    return@launch
                }
                _state.update { it.copy(status = if (welcomedOnce) PartyStatus.RECONNECTING else PartyStatus.CONNECTING) }
                delay(BACKOFF_MS * (1L shl attempt.coerceAtMost(3)))
            }
        }
    }

    private enum class Outcome { WELCOMED, UNREACHED, DONE }

    private suspend fun runSession(url: String, name: String, token: String, intent: Intent, again: Boolean): Outcome {
        var outcome = Outcome.UNREACHED
        // Back at the party by its code, the seat having gone while away.
        var rejoining = false
        val ws = httpClient.webSocketSession(url)
        var pinger: Job? = null
        try {
            send = { text -> ws.send(Frame.Text(text)) }
            ws.send(Frame.Text(PartyProtocol.hello(name, CLIENT, token, resume = if (again) key else "")))
            pinger = scope.launch {
                repeat(5) {
                    ws.send(Frame.Text(PartyProtocol.ping(System.currentTimeMillis())))
                    delay(300)
                }
                while (isActive) {
                    ws.send(Frame.Text(PartyProtocol.ping(System.currentTimeMillis())))
                    delay(PING_MS)
                }
            }
            ws.incoming.consumeEach { frame ->
                if (frame !is Frame.Text) return@consumeEach
                val msg = PartyProtocol.parse(json, frame.readText())
                when {
                    msg is PartyMsg.Welcome -> {
                        key = msg.key
                        outcome = Outcome.WELCOMED
                        _state.update { it.copy(myId = msg.id, error = null) }
                        if (again && !msg.resumed) {
                            // The server no longer keeps this phone's seat, but
                            // the party may be on still: in again by its code.
                            val code = _state.value.party?.code
                            if (code == null) {
                                ended(PartyEnd.ENDED)
                                outcome = Outcome.DONE
                                ws.close()
                                return@consumeEach
                            }
                            rejoining = true
                            ws.send(Frame.Text(PartyProtocol.join(code)))
                        }
                        if (!again) {
                            ws.send(
                                Frame.Text(
                                    when (intent) {
                                        is Intent.Create -> PartyProtocol.create(intent.name)
                                        is Intent.Join -> PartyProtocol.join(intent.code)
                                    },
                                ),
                            )
                        }
                    }
                    msg is PartyMsg.Joined -> rejoining = false
                    // Turned away: not signed in, or no party with that code
                    // (the party it was at has ended while this phone was away).
                    msg is PartyMsg.Error && (_state.value.party == null || rejoining) -> {
                        _state.update {
                            it.copy(
                                status = if ((msg.message == PartyProtocol.NO_PARTY && again) || rejoining) PartyStatus.ENDED
                                else PartyStatus.FAILED,
                                end = if (again) PartyEnd.ENDED else null,
                                error = msg.message,
                            )
                        }
                        outcome = Outcome.DONE
                        ws.close()
                        return@consumeEach
                    }
                    msg is PartyMsg.Left -> {
                        ended(
                            when {
                                msg.reason.contains("kicked") -> PartyEnd.KICKED
                                // The host who ended it is told the party ended, the rest who ended it.
                                msg.reason == PartyProtocol.HOST_ENDED ->
                                    if (_state.value.isHost) PartyEnd.ENDED else PartyEnd.HOST_ENDED
                                else -> PartyEnd.LEFT
                            },
                        )
                        outcome = Outcome.DONE
                        ws.close()
                        return@consumeEach
                    }
                }
                apply(msg)
            }
        } finally {
            send = null
            pinger?.cancel()
            runCatching { ws.close() }
        }
        return outcome
    }

    private fun ended(why: PartyEnd) {
        _state.update { it.copy(status = PartyStatus.ENDED, end = why, error = null) }
    }

    private fun apply(msg: PartyMsg) {
        when (msg) {
            is PartyMsg.Pong -> {
                clock.sample(msg.c, msg.s, System.currentTimeMillis())
                _state.update { it.copy(latencyMs = clock.rttMs) }
            }
            is PartyMsg.Party -> _state.update { it.copy(status = PartyStatus.IN_PARTY, party = msg.party) }
            is PartyMsg.History -> _state.update { it.copy(lines = msg.lines.takeLast(LINES)) }
            is PartyMsg.Line -> _state.update { it.copy(lines = (it.lines + msg.line).takeLast(LINES)) }
            is PartyMsg.Error -> _state.update { it.copy(error = msg.message) }
            else -> Unit
        }
        if (msg !is PartyMsg.Pong) _events.tryEmit(msg)
    }

    private fun tell(text: String) {
        val out = send ?: return
        scope.launch { runCatching { out(text) }.onFailure { Log.w(TAG, "party send: ${it.message}") } }
    }

    /** Leaves the party for good: the server is told, then the socket closes. */
    fun leave() {
        val out = send
        val running = job
        scope.launch {
            runCatching { out?.invoke(PartyProtocol.leave()) }
            delay(200)
            running?.cancel()
        }
        job = null
        _state.update { it.copy(status = PartyStatus.ENDED, end = PartyEnd.LEFT) }
    }

    /** Forgets a party that is over, back to no party at all. */
    fun reset() {
        job?.cancel()
        job = null
        send = null
        key = ""
        serverUrl = null
        _state.value = PartyState()
    }

    fun add(item: PartyAdd, ref: String) = tell(PartyProtocol.add(item, ref))
    fun remove(itemId: String) = tell(PartyProtocol.remove(itemId))
    fun move(itemId: String, to: Int) = tell(PartyProtocol.move(itemId, to))
    fun pause() = tell(PartyProtocol.pause())
    fun resume() = tell(PartyProtocol.resume())
    fun seek(positionMs: Long) = tell(PartyProtocol.seek(positionMs))
    fun skip(itemId: String) = tell(PartyProtocol.skip(itemId))
    fun vote(itemId: String, vote: Boolean) = tell(PartyProtocol.skipVote(itemId, vote))
    fun chat(text: String) = tell(PartyProtocol.chat(text))
    fun kick(id: String) = tell(PartyProtocol.kick(id))
    fun passHost(id: String) = tell(PartyProtocol.passHost(id))
    fun setRate(rate: Float, keepPitch: Boolean) = tell(PartyProtocol.setRate(rate, keepPitch))
    fun endParty() = tell(PartyProtocol.endParty())
    fun clearError() = _state.update { it.copy(error = null) }

    /** The server's time now, as this phone's clock and the pings put it. */
    fun serverNowMs(): Long = System.currentTimeMillis() + (clock.offsetMs ?: 0L)

    /** The link a guest opens in a browser, for this party; null when not in one. */
    fun inviteLink(): String? {
        val code = _state.value.party?.code ?: return null
        return PartyProtocol.inviteLink(code)
    }

    /**
     * Shares [file] with the party as song [itemId], which this phone added,
     * streamed from disk. [onProgress] goes 0 to 1; the party sees it uploading
     * from the server.
     */
    suspend fun upload(itemId: String, file: File, ext: String, onProgress: (Float) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val server = serverUrl ?: throw IOException("not connected to a rooms server")
                val length = file.length()
                val response = httpClient.put(PartyProtocol.songUrl(server, itemId, ext)) {
                    header(KEY_HEADER, key)
                    timeout {
                        requestTimeoutMillis = TRANSFER_TIMEOUT_MS
                        socketTimeoutMillis = STALL_TIMEOUT_MS
                    }
                    setBody(object : OutgoingContent.ReadChannelContent() {
                        override val contentType: ContentType = ContentType.Application.OctetStream
                        override val contentLength: Long = length
                        override fun readFrom(): ByteReadChannel =
                            Counting(file.inputStream()) { sent -> if (length > 0) onProgress(sent.toFloat() / length) }
                                .toByteReadChannel()
                    })
                }
                if (!response.status.isSuccess()) throw IOException(refusal(response.bodyAsText(), response.status.value))
                onProgress(1f)
            }
        }

    /**
     * Fetches shared song [item] into [into], through a `.part` file so a
     * broken download is never taken for a song. [onProgress] goes 0 to 1.
     */
    suspend fun fetch(item: PartyItem, into: File, onProgress: (Float) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val server = serverUrl ?: throw IOException("not connected to a rooms server")
                into.parentFile?.mkdirs()
                val part = File(into.path + ".part")
                try {
                    httpClient.prepareGet(PartyProtocol.songUrl(server, item.id)) {
                        header(KEY_HEADER, key)
                        timeout {
                            requestTimeoutMillis = TRANSFER_TIMEOUT_MS
                            socketTimeoutMillis = STALL_TIMEOUT_MS
                        }
                    }.execute { response ->
                        if (!response.status.isSuccess()) throw IOException(refusal(response.bodyAsText(), response.status.value))
                        val total = response.contentLength() ?: item.shared?.bytes ?: 0L
                        response.bodyAsChannel().toInputStream().use { input ->
                            part.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    done += n
                                    if (total > 0) onProgress(done.toFloat() / total)
                                }
                            }
                        }
                        if (total > 0 && part.length() != total) throw IOException("the download broke off")
                    }
                    if (!part.renameTo(into)) throw IOException("could not keep the song")
                    onProgress(1f)
                    into
                } finally {
                    part.delete()
                }
            }
        }

    /** The server's own words for a refusal (`{"error": …}`), else the status. */
    private fun refusal(body: String, status: Int): String =
        runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?: "the server answered HTTP $status"

    /** Counts what is read through it, for an upload's progress. */
    private class Counting(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) onRead(++count) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) { count += it; onRead(count) } }
    }

    companion object {
        private const val TAG = "PartyClient"
        private const val CLIENT = "tryptify-android"
        private const val PING_MS = 2_000L
        private const val BACKOFF_MS = 1_000L

        /** Tries after a drop before giving up: 1+2+4+8+8+8+8 s, past the server's minute. */
        private const val MAX_ATTEMPTS = 7
        private const val LINES = 100
        private const val KEY_HEADER = "X-Room-Key"
        private const val TRANSFER_TIMEOUT_MS = 15 * 60 * 1000L
        private const val STALL_TIMEOUT_MS = 60 * 1000L

        /** [PartyState.error] when no server under Settings › Connections hosts rooms. */
        const val NO_SERVER = "no rooms server"

        /** [PartyState.error] when the rooms server is from before parties. */
        const val NOT_SUPPORTED = "this rooms server does not hold parties"
    }
}
