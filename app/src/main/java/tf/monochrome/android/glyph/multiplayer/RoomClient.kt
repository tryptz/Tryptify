// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.multiplayer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
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
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import tf.monochrome.android.data.api.ApiService
import tf.monochrome.android.data.preferences.PreferencesManager

enum class RoomStatus { OFF, CONNECTING, CONNECTED, FAILED }

/** Everything the rooms screen and the game read, as it stands. */
data class RoomSession(
    val status: RoomStatus = RoomStatus.OFF,
    val error: String? = null,
    val serverHost: String? = null,
    val myId: String? = null,
    /** The account the server took this phone for; null on a server that takes guests. */
    val user: String? = null,
    /** Whether the server can pass songs round a room (it handed this phone a key). */
    val canShareSongs: Boolean = false,
    val rooms: List<RoomSummary> = emptyList(),
    val room: RoomView? = null,
    /** The picked chart with its notes, as the server handed it over. */
    val chart: RoomChart? = null,
    /** Everyone's score as it stands, by player id, while a song plays. */
    val frames: Map<String, RoomLive> = emptyMap(),
    val ranking: List<RoomPlacing>? = null,
    val chat: List<RoomChatLine> = emptyList(),
    /** The shortest round trip to the server, ms; null before the first pong. */
    val latencyMs: Long? = null,
    val offsetMs: Long? = null,
) {
    val me: RoomPlayer? get() = room?.players?.firstOrNull { it.id == myId }
    val isHost: Boolean get() = room != null && room.hostId == myId
}

/**
 * One phone's socket to the rooms server, kept while the rooms screen or a
 * room game is open: hello, a ping every two seconds for the clock, and
 * every message in and out. [session] is the state the screens draw;
 * [events] carries what happens once (the start, the results, a kick), for
 * the game to act on. Reconnects with backoff while wanted; a room left
 * by a dropped socket is gone, as the server forgets it.
 */
@Singleton
class RoomClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val json: Json,
    private val preferences: PreferencesManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _session = MutableStateFlow(RoomSession())
    val session: StateFlow<RoomSession> = _session.asStateFlow()
    private val _events = MutableSharedFlow<RoomMsg>(extraBufferCapacity = 64)
    val events: SharedFlow<RoomMsg> = _events.asSharedFlow()

    /** Whether any server under Settings › Connections hosts rooms. */
    val hasServer: Flow<Boolean> = preferences.apiServers.map { servers -> servers.any { ApiService.MULTIPLAYER in it.services } }

    private val clock = ClockSync()
    private var job: Job? = null

    @Volatile
    private var send: (suspend (String) -> Unit)? = null

    /** Why the server turned the hello down, when it did; null otherwise. */
    @Volatile
    private var refused: String? = null

    /** The rooms server's address (`https://host/secret`), while connected to one. */
    @Volatile
    private var serverUrl: String? = null

    /** This phone's key for the song routes, from its welcome; empty on a server without them. */
    @Volatile
    private var songKey: String = ""

    /**
     * Opens the socket to the first rooms server, as [name] with the signed-in
     * account's [token]; nothing when one is open. A server that checks
     * accounts refuses a hello without a good token, and that refusal is
     * final: it is shown, not retried.
     */
    fun connect(name: String, token: String) {
        if (job?.isActive == true) return
        refused = null
        job = scope.launch {
            val server = preferences.apiServers.first().firstOrNull { ApiService.MULTIPLAYER in it.services }
            if (server == null) {
                _session.update { it.copy(status = RoomStatus.FAILED, error = "no rooms server") }
                return@launch
            }
            serverUrl = server.url
            val host = server.url.substringAfter("://").substringBefore('/')
            _session.update { RoomSession(status = RoomStatus.CONNECTING, serverHost = host) }
            var attempt = 0
            while (isActive) {
                val established = runCatching { runSession(wsUrl(server.url), name, token) }
                    .onFailure { Log.w(TAG, "rooms socket: ${it.message}") }
                    .getOrDefault(false)
                if (!isActive) break
                refused?.let { why ->
                    _session.update { it.copy(status = RoomStatus.FAILED, error = why) }
                    return@launch
                }
                if (established) attempt = 0 else attempt++
                if (attempt > MAX_ATTEMPTS) {
                    _session.update { it.copy(status = RoomStatus.FAILED, error = it.error ?: "could not reach $host") }
                    return@launch
                }
                _session.update { it.copy(status = RoomStatus.CONNECTING, room = null, frames = emptyMap()) }
                delay(BACKOFF_MS * (1L shl attempt.coerceAtMost(4)))
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        send = null
        refused = null
        serverUrl = null
        songKey = ""
        _session.value = RoomSession()
    }

    private suspend fun runSession(url: String, name: String, token: String): Boolean {
        var established = false
        val ws = httpClient.webSocketSession(url)
        var pinger: Job? = null
        try {
            send = { text -> ws.send(Frame.Text(text)) }
            ws.send(Frame.Text(RoomProtocol.hello(name, CLIENT, token)))
            pinger = scope.launch {
                // Quick at first, so the clock is known before anyone starts.
                repeat(5) {
                    ws.send(Frame.Text(RoomProtocol.ping(System.currentTimeMillis())))
                    delay(300)
                }
                while (isActive) {
                    ws.send(Frame.Text(RoomProtocol.ping(System.currentTimeMillis())))
                    delay(PING_MS)
                }
            }
            ws.incoming.consumeEach { frame ->
                if (frame !is Frame.Text) return@consumeEach
                val msg = RoomProtocol.parse(json, frame.readText())
                if (msg is RoomMsg.Welcome) {
                    established = true
                    songKey = msg.key
                    _session.update {
                        it.copy(status = RoomStatus.CONNECTED, error = null, myId = msg.id, user = msg.user, canShareSongs = msg.key.isNotEmpty())
                    }
                    ws.send(Frame.Text(RoomProtocol.rooms()))
                }
                // Turned away before the welcome: not signed in, as far as
                // the server can tell. Said once and not tried again.
                if (!established && msg is RoomMsg.Error) refused = msg.message
                apply(msg)
            }
            val closed = ws.closeReason.await()
            Log.i(TAG, "rooms socket closed: ${closed?.code} ${closed?.message}")
        } finally {
            send = null
            pinger?.cancel()
            runCatching { ws.close() }
        }
        return established
    }

    private fun apply(msg: RoomMsg) {
        when (msg) {
            is RoomMsg.Pong -> {
                clock.sample(msg.c, msg.s, System.currentTimeMillis())
                _session.update { it.copy(latencyMs = clock.rttMs, offsetMs = clock.offsetMs) }
            }
            is RoomMsg.Rooms -> _session.update { it.copy(rooms = msg.rooms) }
            is RoomMsg.Room -> _session.update { s ->
                val wasPlaying = s.room?.playing == true
                s.copy(
                    room = msg.room,
                    // A new song clears the last one's results and scores.
                    ranking = if (msg.room.playing && !wasPlaying) null else s.ranking,
                    frames = if (msg.room.playing) s.frames else emptyMap(),
                    chart = if (msg.room.chart == null) null else s.chart,
                )
            }
            is RoomMsg.Chart -> _session.update { it.copy(chart = msg.chart, ranking = null) }
            is RoomMsg.Frames -> _session.update { it.copy(frames = msg.players.toMap()) }
            is RoomMsg.Results -> _session.update { it.copy(ranking = msg.ranking) }
            is RoomMsg.Chat -> _session.update { it.copy(chat = (it.chat + RoomChatLine(msg.id, msg.name, msg.text)).takeLast(CHAT_LINES)) }
            is RoomMsg.Left -> _session.update { it.copy(room = null, chart = null, frames = emptyMap(), ranking = null, chat = emptyList()) }
            is RoomMsg.Error -> _session.update { it.copy(error = msg.message) }
            else -> Unit
        }
        if (msg !is RoomMsg.Pong) _events.tryEmit(msg)
    }

    private fun tell(text: String) {
        val out = send ?: return
        scope.launch { runCatching { out(text) }.onFailure { Log.w(TAG, "rooms send: ${it.message}") } }
    }

    fun refreshRooms() = tell(RoomProtocol.rooms())
    fun createRoom(name: String) = tell(RoomProtocol.create(name))
    fun joinRoom(code: String) = tell(RoomProtocol.join(code))
    fun leaveRoom() = tell(RoomProtocol.leave())
    fun pick(chart: RoomChart) = tell(RoomProtocol.pick(json, chart))
    fun have(audio: Boolean) = tell(RoomProtocol.have(audio))
    fun ready(ready: Boolean) = tell(RoomProtocol.ready(ready))

    /**
     * "I have the song" and then "ready", in that order on the one socket:
     * two [tell]s are two coroutines and may land the other way round, and
     * the server turns down a ready from a phone it thinks has no song.
     */
    fun haveAndReady() {
        val out = send ?: return
        scope.launch {
            runCatching {
                out(RoomProtocol.have(true))
                out(RoomProtocol.ready(true))
            }.onFailure { Log.w(TAG, "rooms send: ${it.message}") }
        }
    }
    fun start() = tell(RoomProtocol.start())
    fun kick(id: String) = tell(RoomProtocol.kick(id))
    fun frame(live: RoomLive) = tell(RoomProtocol.frame(json, live))
    fun finish(result: RoomFinished) = tell(RoomProtocol.finish(json, result))
    fun chat(text: String) = tell(RoomProtocol.chat(text))

    /** Clears the last error shown. */
    fun clearError() = _session.update { it.copy(error = null) }

    /**
     * Host: shares [source] with the room as the song of the picked chart
     * [hash], streamed from where it is, never read whole. [onProgress] goes
     * from 0 to 1 as it uploads; the room hears it is there from the server.
     */
    suspend fun shareSong(hash: String, source: Uri, ext: String, onProgress: (Float) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val server = serverUrl ?: throw IOException("not connected to a rooms server")
                val key = songKey.ifEmpty { throw IOException("this rooms server cannot share songs") }
                val length = sizeOf(source)
                val response = httpClient.put(RoomProtocol.songUrl(server, hash, ext)) {
                    header(KEY_HEADER, key)
                    timeout {
                        requestTimeoutMillis = TRANSFER_TIMEOUT_MS
                        socketTimeoutMillis = STALL_TIMEOUT_MS
                    }
                    setBody(object : OutgoingContent.ReadChannelContent() {
                        override val contentType: ContentType = ContentType.Application.OctetStream
                        override val contentLength: Long? = length?.takeIf { it > 0 }
                        override fun readFrom(): ByteReadChannel {
                            val input = context.contentResolver.openInputStream(source)
                                ?: throw IOException("could not open the song")
                            return Counting(input) { sent -> length?.takeIf { it > 0 }?.let { onProgress(sent.toFloat() / it) } }
                                .toByteReadChannel()
                        }
                    })
                }
                if (!response.status.isSuccess()) throw IOException(refusal(response.bodyAsText(), response.status.value))
                onProgress(1f)
            }
        }

    /**
     * A player without the song: fetches the room's [song] into [into],
     * through a `.part` file so a broken download is never taken for a song.
     * [onProgress] goes from 0 to 1.
     */
    suspend fun fetchSong(song: RoomSharedSong, into: File, onProgress: (Float) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val server = serverUrl ?: throw IOException("not connected to a rooms server")
                val key = songKey.ifEmpty { throw IOException("this rooms server cannot share songs") }
                into.parentFile?.mkdirs()
                val part = File(into.path + ".part")
                try {
                    httpClient.prepareGet(RoomProtocol.songUrl(server, song.hash)) {
                        header(KEY_HEADER, key)
                        timeout {
                            requestTimeoutMillis = TRANSFER_TIMEOUT_MS
                            socketTimeoutMillis = STALL_TIMEOUT_MS
                        }
                    }.execute { response ->
                        if (!response.status.isSuccess()) throw IOException(refusal(response.bodyAsText(), response.status.value))
                        val total = response.contentLength() ?: song.bytes
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

    /**
     * The biggest song the rooms server takes (its info's `maxSongBytes`),
     * asked once per server; null when not connected or it does not say.
     */
    suspend fun maxSongBytes(): Long? = withContext(Dispatchers.IO) {
        val server = serverUrl ?: return@withContext null
        songLimit?.takeIf { it.first == server }?.let { return@withContext it.second }
        val limit = runCatching {
            val info = httpClient.get(server.trimEnd('/') + "/api/multiplayer/info") {
                timeout { requestTimeoutMillis = INFO_TIMEOUT_MS }
            }.bodyAsText()
            RoomProtocol.maxSongBytes(json, info)
        }.getOrNull()
        if (limit != null) songLimit = server to limit
        limit
    }

    /** The server whose limit was asked last, and its answer. */
    @Volatile
    private var songLimit: Pair<String, Long>? = null

    /** How big the song at [source] is, in bytes; null when its provider does not say. */
    suspend fun songSize(source: Uri): Long? = withContext(Dispatchers.IO) { sizeOf(source) }

    private fun sizeOf(source: Uri): Long? = runCatching {
        if (source.scheme == "file") return@runCatching source.path?.let { File(it).length() }
        context.contentResolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

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

    /** The local wall-clock ms at which the server's [serverMs] falls. */
    fun localMsFor(serverMs: Long): Long = clock.localMsFor(serverMs)

    companion object {
        private const val TAG = "RoomClient"
        private const val CLIENT = "tryptify-android"
        private const val PING_MS = 2_000L
        private const val BACKOFF_MS = 1_000L
        private const val MAX_ATTEMPTS = 5
        private const val CHAT_LINES = 60
        private const val KEY_HEADER = "X-Room-Key"
        private const val INFO_TIMEOUT_MS = 10_000L

        /** A whole song either way, on a slow phone connection. */
        private const val TRANSFER_TIMEOUT_MS = 15 * 60 * 1000L

        /** Nothing moving for this long and the transfer has stalled. */
        private const val STALL_TIMEOUT_MS = 60 * 1000L

        /** `https://host/secret` as the socket's `wss://host/secret/ws`. */
        fun wsUrl(server: String): String {
            val base = server.trimEnd('/')
            val ws = when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                else -> base
            }
            return "$ws/ws"
        }
    }
}
