package tf.monochrome.android.data.presence

import android.content.Context
import android.util.Log
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import androidx.core.graphics.scale
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.GenreGraphRepository
import tf.monochrome.android.ui.theme.DynamicColorExtractor
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
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val preferences: PreferencesManager,
    private val genreGraph: GenreGraphRepository,
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

    /**
     * Cache of (cover, groove, tint) → the uploaded animation's CDN URL.
     *
     * Keyed on everything that changes the picture, so replaying a track — or
     * looping one — costs nothing. Without it every repeat would re-render two
     * dozen frames and post another attachment.
     */
    private val compositeCache = HashMap<String, String>()

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Who the stored token belongs to, once Discord has confirmed it. */
    private val _username = MutableStateFlow<String?>(null)
    val username: StateFlow<String?> = _username.asStateFlow()

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

    /**
     * Ask Discord who this token belongs to.
     *
     * The whole point is to tell two failures apart that otherwise look
     * identical. The gateway answers a bad token and a handshake it dislikes
     * with the same 4004 close, so a client that only talks to the gateway can
     * never say which it is — and "your token is wrong" is very bad advice to
     * give someone whose token is fine. A 200 here means the credential is
     * good and any later 4004 is this app's IDENTIFY at fault; a 401 means the
     * token really is dead.
     *
     * It also gives the settings screen a name to show, which is the only
     * confirmation that survives the music not playing.
     */
    suspend fun verifyToken(token: String): Boolean {
        val clean = DiscordPresence.normalizeToken(token)
        if (!DiscordPresence.looksLikeToken(clean)) {
            _status.value = Status.FAILED
            _errorMessage.value =
                "That doesn't look like a Discord token — they're three parts " +
                    "separated by dots. Copy the whole Authorization value."
            return false
        }
        return runCatching {
            val response = httpClient.get("$API_BASE/users/@me") {
                header("Authorization", clean)
            }
            when (response.status.value) {
                in 200..299 -> {
                    val name = runCatching {
                        json.parseToJsonElement(response.bodyAsText())
                            .jsonObject["username"]?.jsonPrimitive?.content
                    }.getOrNull()
                    _username.value = name
                    _errorMessage.value = null
                    if (_status.value == Status.FAILED) _status.value = Status.OFF
                    true
                }
                401 -> {
                    _username.value = null
                    _status.value = Status.FAILED
                    _errorMessage.value =
                        "Discord rejected the token (401). It rotates on every " +
                            "password change and logout — copy a fresh one."
                    false
                }
                else -> {
                    _errorMessage.value =
                        "Discord answered ${response.status.value} when checking the token."
                    false
                }
            }
        }.onFailure {
            Log.w(TAG, "token check failed", it)
            _errorMessage.value = "Couldn't reach Discord to check the token: ${it.message}"
        }.getOrDefault(false)
    }

    private suspend fun show(now: DiscordPresence.NowPlaying) {
        if (!preferences.discordPresenceEnabled.first()) return
        val token = DiscordPresence.normalizeToken(preferences.discordToken.first())
        if (token.isBlank()) return
        if (lastSent?.sameAs(now) == true) return

        val appId = preferences.discordApplicationId.first().takeIf { it.isNotBlank() }
        // The composited cover replaces the plain one when it works, and the
        // circular badge is dropped with it — two spectrums on one card is one
        // too many. Everything about it can fail (no channel, no cover, an
        // upload that 403s), and every failure falls back to what the card
        // showed before rather than to nothing.
        // NOT run through the external-assets proxy. That endpoint exists to
        // mint an asset path for an image hosted somewhere Discord does not
        // control — and this one is already on Discord's own CDN, because we
        // just uploaded it there. Asking the proxy to adopt a cdn.discordapp
        // URL fails, which is why the card kept falling back to the plain cover
        // while the upload itself was working perfectly. An attachment has a
        // media-proxy path of its own, derived from the URL.
        val composited = compositedArtwork(now, token)
        val resolved = if (composited != null) {
            now.copy(artworkAsset = composited, badgeAsset = null)
        } else {
            now.copy(
                artworkAsset = now.artworkAsset?.let { proxiedAsset(it, token, appId) },
                badgeAsset = badgeFor(now)?.let { proxiedAsset(it, token, appId) },
            )
        }
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
                // consumeEach returns when the socket closes, and the close
                // code is the only thing that says whether trying again could
                // ever work. Without reading it, a token Discord refuses with
                // 4004 is indistinguishable from a tunnel — so the client
                // reconnects forever and the status sits on "connecting".
                val closed = session.closeReason.await()
                val verdict = DiscordPresence.readClose(closed?.code?.toInt(), closed?.message)
                Log.w(TAG, "gateway closed: ${closed?.code} ${closed?.message}")
                if (verdict.fatal) {
                    _status.value = Status.FAILED
                    // A token the REST API just accepted, refused by the
                    // gateway, is not the listener's problem to fix — say so
                    // rather than sending them after another token that will
                    // fail in exactly the same way.
                    _errorMessage.value = if (closed?.code?.toInt() == 4004 && _username.value != null) {
                        "Discord accepted this token but refused the connection (4004). " +
                            "That's a fault in the app's handshake, not your token."
                    } else {
                        verdict.message
                    }
                } else if (!established) {
                    _errorMessage.value = verdict.message
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
     * The cover with the spectrum drawn across it, uploaded, as a URL.
     *
     * Null whenever anything is missing or fails — no channel configured, no
     * artwork, a render that returned nothing, an upload Discord refused. The
     * caller falls back to the plain cover plus the circular badge, which is
     * the behaviour this is an upgrade on rather than a replacement for.
     */
    private suspend fun compositedArtwork(
        now: DiscordPresence.NowPlaying,
        token: String,
    ): String? {
        if (!preferences.discordPresenceAnimated.first()) return null
        val channel = preferences.discordUploadChannel.first().takeIf { it.isNotBlank() }
            ?: return null
        val coverUrl = now.artworkUrl ?: return null

        val palette = DynamicColorExtractor.extract(context, coverUrl)
        val tint = (palette?.vibrant ?: palette?.dominant)?.let {
            val argb = it.value.toULong() shr 32
            android.graphics.Color.rgb(
                ((argb shr 16) and 0xFFu).toInt(),
                ((argb shr 8) and 0xFFu).toInt(),
                (argb and 0xFFu).toInt(),
            )
        } ?: android.graphics.Color.rgb(88, 101, 242)

        val lineage = lineageOf(now.genreId)
        val groove = PresenceBadge.groove(lineage)
        // The nearest ancestor that declares a tempo. Leaves are often silent
        // about BPM where their family is not, so walking up finds a real
        // number more often than reading the leaf alone.
        val bpm = lineage.asSequence()
            .mapNotNull { genreGraph.graph[it] }
            .firstOrNull { it.hasTempo }
            ?.let { (it.bpmLow + it.bpmHigh) / 2 }
        val key = "$coverUrl|$groove|$bpm|$tint"
        compositeCache[key]?.let { return it }

        val bitmap = loadCover(coverUrl) ?: return null
        // Rendering two dozen frames is real work; keep it off whatever thread
        // the playback callback arrived on.
        val bytes = withContext(Dispatchers.Default) {
            PresenceArtwork.render(bitmap, groove, tint, bpm)
        } ?: return null

        val url = upload(bytes, channel, token) ?: return null
        val asset = attachmentAsset(url) ?: return null
        compositeCache[key] = asset
        return asset
    }

    /**
     * The media-proxy path for an attachment we uploaded.
     *
     * `https://cdn.discordapp.com/attachments/<channel>/<message>/<name>?ex=…`
     * becomes `mp:attachments/<channel>/<message>/<name>`. The query string is
     * dropped deliberately: those are expiring signed parameters for direct
     * fetches, and the proxy path does not take them.
     */
    internal fun attachmentAsset(cdnUrl: String): String? {
        val withoutQuery = cdnUrl.substringBefore('?')
        val marker = "/attachments/"
        val at = withoutQuery.indexOf(marker)
        if (at < 0) return null
        val path = withoutQuery.substring(at + 1)
        return if (path.count { it == '/' } >= 3) "mp:$path" else null
    }

    /**
     * Host a local cover on Discord and return the asset path for it.
     *
     * Everything Discord will render has to be somewhere Discord can fetch, and
     * a track ripped from a CD has artwork that exists only as bytes in a tag on
     * this phone. The external-asset proxy cannot help — it adopts URLs from the
     * public web — so the picture is uploaded as an attachment and referenced by
     * the media-proxy path that gives it, which is the same trick the animated
     * composite already uses. The link Discord mints is temporary and signed,
     * and it only has to outlive the track.
     *
     * Needs the upload channel, for the same reason the composite does: an
     * attachment has to be posted *somewhere*. Without one this returns null and
     * the card goes out with no art rather than not going out.
     *
     * Cached per cover, so a local album played end to end uploads once per
     * sleeve rather than once per track, and a repeat costs nothing.
     */
    private suspend fun hostedAsset(url: String, token: String): String? {
        assetCache[url]?.let { return it }
        val channel = preferences.discordUploadChannel.first().takeIf { it.isNotBlank() }
            ?: return null

        val bitmap = loadCover(url) ?: return null
        val bytes = withContext(Dispatchers.Default) {
            // Square, and no larger than the card ever renders. Tag artwork runs
            // to a few thousand pixels either way, and posting that costs the
            // listener's data to produce something drawn at a couple of hundred.
            val square = bitmap.scale(PresenceArtwork.SIZE, PresenceArtwork.SIZE)
            val out = java.io.ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val ok = square.compress(
                android.graphics.Bitmap.CompressFormat.WEBP,
                PresenceArtwork.QUALITY,
                out,
            )
            if (square !== bitmap) square.recycle()
            if (ok) out.toByteArray() else null
        } ?: return null

        val uploaded = upload(bytes, channel, token) ?: return null
        val asset = attachmentAsset(uploaded) ?: return null
        assetCache[url] = asset
        return asset
    }

    /** Fetch a cover as a software bitmap, reusing the app-wide image cache. */
    private suspend fun loadCover(url: String): android.graphics.Bitmap? = runCatching {
        val request = ImageRequest.Builder(context)
            .data(url)
            // Palette and Canvas both need pixels they can read.
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        (result.image as? BitmapImage)?.bitmap
    }.onFailure { Log.w(TAG, "cover load failed", it) }.getOrNull()

    /**
     * Post the animation as an attachment and return its CDN URL.
     *
     * A multipart message to a channel, which is the only way a phone can hand
     * Discord a file it will later serve back. The link carries expiring signed
     * parameters, which is fine — it only has to outlive the track.
     */
    private suspend fun upload(bytes: ByteArray, channelId: String, token: String): String? =
        runCatching {
            val response = httpClient.submitFormWithBinaryData(
                url = "$API_BASE/channels/$channelId/messages",
                formData = formData {
                    append("payload_json", """{"content":""}""")
                    append(
                        "files[0]", bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, "image/webp")
                            append(HttpHeaders.ContentDisposition, "filename=\"np.webp\"")
                        },
                    )
                },
            ) { header("Authorization", token) }

            val body = response.bodyAsText()
            if (response.status.value !in 200..299) {
                Log.w(TAG, "artwork upload refused (${response.status.value}): $body")
                return null
            }
            json.parseToJsonElement(body).jsonObject["attachments"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
        }.onFailure { Log.w(TAG, "artwork upload failed", it) }.getOrNull()

    /** The genre and its ancestors, nearest first. */
    private fun lineageOf(genreId: String?): List<String> {
        val graph = genreGraph.graph
        return buildList {
            var node = genreId?.let { graph[it] }
            var guard = 0
            while (node != null && guard++ < LINEAGE_DEPTH) {
                add(node.id)
                node = node.parents.firstOrNull()?.let { graph[it] }
            }
        }
    }

    /**
     * The animated spectrum for this track, as a URL, or null for none.
     *
     * The rhythm comes from the genre and its ancestors in the graph; the
     * colour from the cover's own palette, which the player already extracts
     * for its theming — so this costs one cached palette read, not a render.
     */
    private suspend fun badgeFor(now: DiscordPresence.NowPlaying): String? {
        if (!preferences.discordPresenceAnimated.first()) return null
        val lineage = lineageOf(now.genreId)
        // No early return for an empty lineage or a colourless cover: both fall
        // back inside PresenceBadge, so every track gets a spectrum rather than
        // the card gaining and losing a graphic depending on how much the app
        // happens to know about what is playing.
        val palette = DynamicColorExtractor.extract(context, now.artworkUrl)
        val colour = palette?.vibrant ?: palette?.dominant
        val rgb = colour?.let {
            val argb = it.value.toULong() shr 32
            Triple(
                ((argb shr 16) and 0xFFu).toInt(),
                ((argb shr 8) and 0xFFu).toInt(),
                (argb and 0xFFu).toInt(),
            )
        }
        return PresenceBadge.url(lineage, rgb)
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
        // A local file has no URL Discord can reach, so the proxy is the wrong
        // tool: it adopts images hosted elsewhere on the public web, and a
        // `content://` or `file://` path is meaningless to it. The whole local
        // library used to fail here and play with no cover at all. Hosting it
        // ourselves is the way round, and the app can already do that — it is
        // how the animated composite gets on the card.
        if (!url.startsWith("https://")) return hostedAsset(url, token)
        if (appId == null) return null
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

        /** How far up the genre graph a badge looks for a rhythm it recognises. */
        const val LINEAGE_DEPTH = 12

        /** How long a queue has to read empty before it counts as stopped. */
        const val HIDE_GRACE_MS = 15_000L
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
