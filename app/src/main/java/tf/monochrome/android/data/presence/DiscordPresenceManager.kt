package tf.monochrome.android.data.presence

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

/**
 * Holds a Discord gateway connection and keeps the listener's presence on it.
 *
 * The socket is opened lazily — the first track to play after the feature is
 * switched on — and closed as soon as playback stops for good, rather than
 * being held for the life of the process. There is no reason for a music app to
 * keep a websocket alive while nothing is playing, and every reason not to.
 *
 * Updates are event-driven, not polled. Discord animates the progress bar from
 * the `start`/`end` timestamps in the activity, so a frame is only sent when
 * the track, the play state or the position genuinely changes. A playing album
 * costs one frame per track.
 */
@Singleton
class DiscordPresenceManager @Inject constructor(
    private val httpClient: HttpClient,
    private val preferences: PreferencesManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private var socketJob: Job? = null

    /**
     * Written by the session coroutine when the socket opens and cleared when
     * it closes; read by [update] on whichever thread a track change arrives
     * on. Volatile because those are genuinely different threads and a stale
     * null here means a silently skipped presence update.
     */
    @Volatile private var send: (suspend (String) -> Unit)? = null

    @Volatile private var current: JsonObject? = null

    /**
     * The last state actually sent, for dropping repeats.
     *
     * Three things push a presence for one song — the queue moving, the player
     * becoming ready, the play state settling — and holding a skip button walks
     * through a queue faster than Discord's presence rate limit allows. Going
     * over it doesn't return an error; Discord closes the connection, which
     * costs a reconnect and looks to the listener like the card freezing. The
     * repeats are genuinely redundant, so they are dropped rather than paced.
     */
    @Volatile private var lastSent: DiscordPresence.NowPlaying? = null

    /** Cache of artwork URL → Discord's proxied asset path, per process. */
    private val assetCache = HashMap<String, String>()

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    enum class Status { OFF, CONNECTING, CONNECTED, FAILED }

    /**
     * Every presence change, in the order it was asked for.
     *
     * [update] and [clear] used to be independent `launch`es, which meant the
     * order they ran in was whatever the dispatcher felt like — and since the
     * queue reads empty for a moment between tracks, "stop" and "start the next
     * one" were racing on every single track change. One channel with one
     * consumer makes the order the order they were called in, which is the only
     * order that can be reasoned about.
     */
    private val commands = Channel<Command>(Channel.BUFFERED)

    private var pendingHide: Job? = null

    private sealed interface Command {
        data class Show(val now: DiscordPresence.NowPlaying) : Command
        data object Hide : Command
        data object Blank : Command
    }

    init {
        scope.launch { for (command in commands) handle(command) }
        // Turning the switch off has to tear the socket down straight away —
        // otherwise the last activity sits on the profile indefinitely, since
        // Discord keeps a presence until the connection that set it goes away.
        scope.launch {
            preferences.discordPresenceEnabled.distinctUntilChanged().collect { on ->
                if (!on) shutdown()
            }
        }
    }

    /**
     * Push what's playing.
     *
     * Silently does nothing when the feature is off or no token is stored,
     * which is the overwhelmingly common case — this is called from the
     * playback service on every track change and must stay cheap there.
     */
    fun update(now: DiscordPresence.NowPlaying) {
        commands.trySend(Command.Show(now))
    }

    /**
     * Nothing is playing.
     *
     * Deliberately not immediate. The queue reads empty for a moment between
     * tracks, and treating that as "stopped" meant tearing the socket down and
     * opening a new one for every song — Discord rate-limits reconnects far
     * more tightly than presence updates, so a few tracks in it stops accepting
     * the connection at all and the card vanishes. A gap only counts once it
     * has lasted [HIDE_GRACE_MS]; anything shorter is a track change.
     */
    fun clear() {
        commands.trySend(Command.Hide)
    }

    /** Playback is over for good: blank the card and drop the connection. */
    fun shutdown() {
        scope.launch { stop() }
    }

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Show -> {
                pendingHide?.cancel()
                pendingHide = null
                show(command.now)
            }
            Command.Hide -> {
                pendingHide?.cancel()
                pendingHide = scope.launch {
                    delay(HIDE_GRACE_MS)
                    commands.send(Command.Blank)
                }
            }
            Command.Blank -> stop()
        }
    }

    private suspend fun show(now: DiscordPresence.NowPlaying) {
        if (!preferences.discordPresenceEnabled.first()) return
        val token = preferences.discordToken.first()
        if (token.isBlank()) return
        if (lastSent?.sameAs(now) == true) return

        val appId = preferences.discordApplicationId.first().takeIf { it.isNotBlank() }
        val resolved = now.copy(
            artworkAsset = now.artworkAsset?.let { proxiedAsset(it, token, appId) },
        )
        val activity = DiscordPresence.activity(resolved, APP_NAME, appId)
        lock.withLock {
            lastSent = now
            current = activity
            val sender = send
            if (sender == null) {
                // A token Discord has already refused will be refused again,
                // and trying once per track change is how a dead token turns
                // into a rate limit on the account. Wait to be told the
                // credentials changed — clearError() is that signal.
                if (_status.value != Status.FAILED) connect(token)
            } else {
                runCatching { sender(DiscordPresence.presenceFrame(activity)) }
                    .onFailure { Log.w(TAG, "presence update failed", it) }
            }
        }
    }

    private suspend fun stop() {
        val sender: (suspend (String) -> Unit)?
        val job: Job?
        lock.withLock {
            current = null
            // Otherwise replaying the track that was up when playback stopped
            // would be read as a repeat and never sent.
            lastSent = null
            sender = send
            job = socketJob
            socketJob = null
            send = null
        }
        // Blank the card before dropping the socket. Closing it clears the
        // presence too, but not always promptly — Discord can hold a
        // disconnected client's activity for a beat, which shows up as a song
        // lingering after the music stopped.
        sender?.let { runCatching { it(DiscordPresence.presenceFrame(null)) } }
        job?.cancelAndJoin()
        if (_status.value != Status.FAILED) _status.value = Status.OFF
    }

    fun clearError() {
        _errorMessage.value = null
        if (_status.value == Status.FAILED) _status.value = Status.OFF
    }

    /**
     * Open the socket. Called with [lock] held, and with [current] already set
     * to the activity that IDENTIFY should carry — the presence goes out inside
     * the opening frame rather than as a follow-up, so there is no window where
     * the connection is up and the card is empty.
     */
    private fun connect(token: String) {
        _status.value = Status.CONNECTING
        socketJob = scope.launch {
            var attempt = 0
            while (isActive) {
                val clean = runCatching { runSession(token, attempt == 0) }
                    .onFailure { Log.w(TAG, "gateway session ended", it) }
                    .getOrDefault(false)
                if (!isActive) return@launch
                // A refused token is the one failure reconnecting cannot fix,
                // and retrying it forever is how an app gets its user rate
                // limited. runSession reports it by returning cleanly with the
                // status already set.
                if (_status.value == Status.FAILED) return@launch
                attempt = if (clean) 0 else attempt + 1
                val backoff = min(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl min(attempt, 5))
                delay(backoff + Random.nextLong(500))
            }
        }
    }

    /** @return true if the session ran normally before ending, false if it failed early. */
    private suspend fun runSession(token: String, first: Boolean): Boolean {
        if (!first) Log.d(TAG, "reconnecting to the gateway")
        var heartbeat: Job? = null
        var seq: Int? = null
        var established = false
        try {
            val session = httpClient.webSocketSession(DiscordPresence.GATEWAY_URL)
            send = { text -> session.send(Frame.Text(text)) }
            try {
                session.incoming.consumeEach { frame ->
                    if (frame !is Frame.Text) return@consumeEach
                    when (val parsed = DiscordPresence.parseFrame(frame.readText())) {
                        is DiscordPresence.Frame.Hello -> {
                            session.send(Frame.Text(DiscordPresence.identifyFrame(token, current)))
                            heartbeat = scope.launch {
                                // Discord asks for a random first beat inside
                                // the interval so a fleet of clients doesn't
                                // arrive in lockstep after an outage.
                                delay((parsed.heartbeatIntervalMs * Random.nextDouble()).toLong())
                                while (isActive) {
                                    session.send(Frame.Text(DiscordPresence.heartbeatFrame(seq)))
                                    delay(parsed.heartbeatIntervalMs)
                                }
                            }
                        }
                        is DiscordPresence.Frame.Dispatch -> {
                            parsed.seq?.let { seq = it }
                            if (parsed.event == "READY") {
                                established = true
                                _status.value = Status.CONNECTED
                                _errorMessage.value = null
                            }
                        }
                        DiscordPresence.Frame.HeartbeatRequest ->
                            session.send(Frame.Text(DiscordPresence.heartbeatFrame(seq)))
                        is DiscordPresence.Frame.InvalidSession -> {
                            if (!parsed.resumable && !established) {
                                _status.value = Status.FAILED
                                _errorMessage.value =
                                    "Discord rejected the token. Paste a fresh one and try again."
                            }
                            session.close()
                        }
                        DiscordPresence.Frame.Reconnect -> session.close()
                        DiscordPresence.Frame.HeartbeatAck, DiscordPresence.Frame.Ignored -> Unit
                    }
                }
            } finally {
                send = null
                heartbeat?.cancel()
                runCatching { session.close() }
            }
        } catch (e: Exception) {
            send = null
            heartbeat?.cancel()
            if (!established) {
                _errorMessage.value = "Couldn't reach Discord: ${e.message}"
            }
            throw e
        }
        return established
    }

    /**
     * Turn an artwork URL into something Discord will actually render.
     *
     * Assets on a gateway-set presence are normally names of images uploaded to
     * a developer application, and a plain https URL in `large_image` renders
     * as nothing at all. The way round it is Discord's own media proxy: an
     * application can ask for an external URL to be minted into an
     * `external/…` path, and `mp:` + that path is a legal asset. Which is why
     * album art needs an application id and text-only presence does not.
     *
     * Returns null on any failure, and the presence goes out without art
     * rather than not going out.
     */
    private suspend fun proxiedAsset(url: String, token: String, appId: String?): String? {
        if (appId == null) return null
        // A local file path is meaningless to Discord's proxy, and most of a
        // local library has one. Fail fast rather than spend a request on it.
        if (!url.startsWith("https://")) return null
        assetCache[url]?.let { return it }

        return runCatching {
            val response = httpClient.post("$API_BASE/applications/$appId/external-assets") {
                header("Authorization", token)
                contentType(ContentType.Application.Json)
                setBody("""{"urls":["$url"]}""")
            }
            val path = json.parseToJsonElement(response.bodyAsText())
                .jsonArray.firstOrNull()?.jsonObject
                ?.get("external_asset_path")?.jsonPrimitive?.content
            path?.let { "mp:$it" }?.also { assetCache[url] = it }
        }.onFailure { Log.w(TAG, "artwork proxy failed", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "DiscordPresence"
        const val APP_NAME = "Tryptify"
        const val API_BASE = "https://discord.com/api/v9"
        const val BASE_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L

        /** How long a queue has to read empty before it counts as stopped. */
        const val HIDE_GRACE_MS = 15_000L
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
