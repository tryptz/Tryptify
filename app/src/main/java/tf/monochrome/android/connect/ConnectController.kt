// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.connect

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.R
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.device.DeviceIdProvider
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.player.PlaybackService
import tf.monochrome.android.player.PlaybackSignals
import tf.monochrome.android.player.QueueManager
import tf.monochrome.android.player.StreamResolver
import tf.monochrome.android.player.UnifiedTrackRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Tryptify Connect on this phone: it is one of the listener's devices, tells
 * the others what plays here and how loud, does what they ask (play, pause,
 * skip, seek, a volume), hands its song over at the same second with what
 * comes next, takes one over the same way, and makes way when another device
 * starts later. While this phone plays nothing and another device plays, it
 * says which song the player follows ([followed]).
 *
 * Battery first. Connected only when signed in and either the app is on
 * screen or music plays (the phone is awake then anyway), and a minute
 * after both stop it lets go. It watches the player through
 * [PlaybackSignals], never a controller of its own, and builds one only to
 * act: a controller holds the playback service up.
 */
@Singleton
class ConnectController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: ConnectClient,
    private val auth: SupabaseAuthManager,
    private val deviceIds: DeviceIdProvider,
    private val signals: PlaybackSignals,
    private val queueManager: QueueManager,
    private val registry: UnifiedTrackRegistry,
    private val streamResolver: StreamResolver,
    private val volume: DeviceVolume,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val visible = MutableStateFlow(false)
    private var deviceId: String = ""

    /** When this phone last started playing, by the server's clock: the later start wins. */
    @Volatile private var playSince = 0L
    private var controller: MediaController? = null

    /** One controller is built at a time: a command and a pause for a later start can ask together. */
    private val controllerLock = Mutex()
    private var started = false

    val state: StateFlow<ConnectState> get() = client.state

    private val _notes = MutableSharedFlow<Note>(extraBufferCapacity = 8)

    /** What the player screen says once: now on another device, taken over, refused. */
    val notes: SharedFlow<Note> = _notes.asSharedFlow()

    sealed interface Note {
        data class NowPlayingOn(val device: String) : Note
        data class PlayingHereFrom(val device: String) : Note
        data class MadeWayFor(val device: String) : Note
        data class CannotPlayHere(val title: String) : Note
        data class Refused(val message: String) : Note
    }

    val myId: String get() = deviceId

    /** Another device's song while this phone's player follows it. */
    data class Followed(
        /** What that device last said: its song, whether it plays, where, how loud. */
        val now: ConnectNow,
        /** The song as this phone's player shows it. */
        val track: Track,
        val deviceName: String,
        /** Whether this phone could play it itself; false for a file on that device. */
        val playableHere: Boolean,
    )

    private val _followed = MutableStateFlow<Followed?>(null)

    /**
     * The song the player shows in place of this phone's own: another
     * device's, from when it plays while this phone does not until this phone
     * plays or that device goes ([ConnectProtocol.follows]). The main player
     * and the mini player both read it, so they cannot disagree.
     */
    val followed: StateFlow<Followed?> = _followed.asStateFlow()

    /** Says [n] once: to the player screen, and as a toast while the app is on screen. */
    private fun note(n: Note) {
        _notes.tryEmit(n)
        if (!visible.value) return
        val text = when (n) {
            is Note.NowPlayingOn -> context.getString(R.string.connect_now_on, n.device)
            is Note.PlayingHereFrom -> context.getString(R.string.connect_here_from, n.device)
            is Note.MadeWayFor -> context.getString(R.string.connect_made_way, n.device)
            is Note.CannotPlayHere -> context.getString(R.string.connect_cannot_play, n.title)
            is Note.Refused -> n.message.ifBlank { context.getString(R.string.connect_not_connected) }
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    /** Starts it, once, from the Application. */
    @OptIn(FlowPreview::class)
    fun start(app: Application) {
        if (started) return
        started = true
        app.registerActivityLifecycleCallbacks(VisibleActivities { visible.value = it })
        scope.launch {
            deviceId = deviceIds.getOrCreate().takeIf(ConnectProtocol::isDeviceId) ?: return@launch
            launch { followWanted() }
            launch { publishChanges() }
            launch { followEvents() }
            launch { followOthers() }
            launch { followElsewhere() }
            launch { publishVolume() }
        }
    }

    /** Connected while signed in and (on screen or playing); let go a minute after neither. */
    private suspend fun followWanted() {
        combine(visible, signals.change.map { it.playing }.distinctUntilChanged(), auth.userProfile.map { it != null }) { v, p, s ->
            s && (v || p)
        }.distinctUntilChanged().collectLatest { wanted ->
            if (wanted) {
                client.open(deviceId, deviceName(), token = { auth.accessToken() }, onOpen = { if (signals.change.value.playing) send() })
            } else {
                delay(LINGER_MS)
                client.close()
                controllerLock.withLock { releaseController() }
            }
        }
    }

    /** A message on each change of play, pause, seek, song or speed, once things settle. */
    @OptIn(FlowPreview::class)
    private suspend fun publishChanges() {
        var wasPlaying = false
        combine(signals.change, queueManager.currentTrack) { change, track -> change to track }
            .drop(1)
            .debounce(PUBLISH_SETTLE_MS)
            .collect { (change, _) ->
                if (change.playing && !wasPlaying) {
                    playSince = client.serverNowMs() - (System.currentTimeMillis() - change.atMs).coerceAtLeast(0)
                }
                wasPlaying = change.playing
                send()
            }
    }

    private fun stateOut(): ConnectProtocol.StateOut? {
        val track = queueManager.currentTrack.value ?: return null
        val c = signals.change.value
        // Where the play head is now: the last posted position, run on if it plays.
        val positionMs = c.positionMs + if (c.playing) ((System.currentTimeMillis() - c.atMs) * c.speed).toLong() else 0L
        return ConnectProtocol.StateOut(c.playing, descriptorOf(track), positionMs / 1000.0, c.speed.toDouble(), playSince, volume.now())
    }

    /**
     * The phone's volume moved (the keys, the system panel, the DAC's slider,
     * another device's command): the others hear of it once it settles. Only
     * while this phone holds the song: said by a paused phone, it would take
     * the song shown everywhere from a device that is paused too.
     */
    @OptIn(FlowPreview::class)
    private suspend fun publishVolume() {
        client.state.map { it.status == ConnectState.Status.ON }.distinctUntilChanged().collectLatest { on ->
            if (!on) return@collectLatest
            volume.changes.debounce(VOLUME_SETTLE_MS).collect {
                if (client.state.value.now?.device == deviceId) send()
            }
        }
    }

    private suspend fun send() {
        // Signed out or offline there is no one to tell: skip describing the song and encoding it.
        if (!client.canTell) return
        val out = stateOut() ?: return
        // Paused here while another device plays: not this phone's turn to speak.
        if (!ConnectProtocol.maySay(out.playing, client.state.value.now, deviceId)) return
        if (client.tell(ConnectProtocol.state(out))) {
            client.remember(ConnectNow(deviceId, deviceName(), out.playing, out.track, out.position, out.rate, client.serverNowMs(), out.since, out.volume))
        }
    }

    /** Another device's song: this phone makes way when it started there later. */
    private suspend fun followOthers() {
        client.state.map { it.now }.distinctUntilChanged().collect { now ->
            if (now != null && ConnectProtocol.shouldMakeWay(now, deviceId, signals.change.value.playing, playSince)) {
                withController { it.pause() }
                note(Note.MadeWayFor(nameOf(now.device, now.name)))
            }
        }
    }

    /**
     * Follows another device's song while this phone plays nothing: see
     * [followed]. The song shown is made once a song, not once a message, so
     * the player's cover and lyrics are not built again at every pause or seek
     * of that device.
     */
    private suspend fun followElsewhere() {
        var following = false
        var shown: Triple<ConnectTrack, Track, Boolean>? = null
        combine(client.state, signals.change.map { it.playing }.distinctUntilChanged()) { s, here -> s to here }.collect { (s, playingHere) ->
            val elsewhere = ConnectProtocol.elsewhere(s.now, deviceId, s.devices)
            following = ConnectProtocol.follows(following, elsewhere, playingHere)
            val track = elsewhere?.track
            if (!following || track == null) {
                _followed.value = null
                return@collect
            }
            val known = shown?.takeIf { it.first == track }
                ?: shownTrackOf(track).let { Triple(track, it.first, it.second) }.also { shown = it }
            _followed.value = Followed(elsewhere, known.second, nameOf(elsewhere.device, elsewhere.name), known.third)
        }
    }

    /** [t] as this phone's player shows it, and whether this phone could play it itself. */
    private fun shownTrackOf(t: ConnectTrack): Pair<Track, Boolean> {
        val described = unifiedOf(t)
        if (described != null) {
            val id = described.toLegacyTrack().id
            // A song this phone already knows (it is in its queue, or played
            // here before) is shown as the phone knows it: with its album,
            // its quality and its cover.
            val unified = registry[id] ?: described.also { registry.put(id, it) }
            return unified.toLegacyTrack() to true
        }
        // A file on that device, or a source this phone has none of: shown, and no more.
        return Track(
            id = "connect:${t.src}:${t.id}".hashCode().toLong(),
            title = t.title.ifBlank { "—" },
            duration = t.duration?.toInt() ?: 0,
            artist = Artist(id = 0, name = t.artist),
            album = Album(id = 0, title = t.album, cover = ConnectProtocol.shareableCover(t.cover)),
        ) to false
    }

    private suspend fun followEvents() {
        client.events.collect { m ->
            when (m.type) {
                // The volume needs no controller: building one would hold the playback service up for it.
                "command" -> if (m.action == "volume") m.value?.let { volume.set(it) } else withController { mc ->
                    when (m.action) {
                        "play" -> mc.play()
                        "pause" -> mc.pause()
                        "next" -> mc.seekToNext()
                        "prev" -> mc.seekToPrevious()
                        "seek" -> m.value?.let { mc.seekTo((it * 1000).toLong()) }
                    }
                }
                "release" -> release(m)
                "takeover" -> takeover(m)
                "error" -> note(Note.Refused(m.message.orEmpty()))
            }
        }
    }

    // ── Handing over and taking over ──────────────────────────────────

    private suspend fun release(m: ConnectIncoming) {
        // Only to a device of this account that is in the room now.
        val to = m.to?.takeIf { id -> ConnectProtocol.isDeviceId(id) && client.state.value.devices.any { it.id == id } } ?: return
        // A sequence, so a long queue is not copied to send the next hundred.
        val upcoming = queueManager.queue.value.asSequence().drop(queueManager.currentIndex.value + 1)
            .take(ConnectProtocol.MAX_QUEUE).map(::descriptorOf).toList()
        client.tell(ConnectProtocol.handoff(to, stateOut(), upcoming))
        if (signals.change.value.playing) {
            withController { it.pause() }
            note(Note.NowPlayingOn(m.toName ?: nameOf(to, null)))
        }
    }

    private suspend fun takeover(m: ConnectIncoming) {
        val now = m.now ?: return
        val first = now.track ?: return
        val from = nameOf(m.from ?: now.device, now.name)
        val head = unifiedOf(first)
        if (head == null) {
            note(Note.CannotPlayHere(first.title))
            return
        }
        val tracks = listOf(head) + m.queue.orEmpty().mapNotNull(::unifiedOf)
        val resolved = withContext(Dispatchers.IO) { runCatching { streamResolver.resolveUnifiedTrack(tracks.first()) }.getOrNull() }
        if (resolved == null || !resolved.isPlayable) {
            note(Note.CannotPlayHere(first.title))
            return
        }
        val legacy = tracks.map { u -> u.toLegacyTrack().also { registry.put(it.id, u) } }
        val startMs = (ConnectProtocol.positionAt(now, client.serverNowMs()) * 1000).toLong()
        queueManager.setQueue(legacy, 0)
        withController { mc ->
            mc.setMediaItem(resolved.mediaItem, startMs)
            mc.prepare()
            if (now.playing) mc.play() else mc.pause()
        }
        note(Note.PlayingHereFrom(from))
    }

    /** Plays on [to] instead: this phone included, to take it from the device playing. */
    fun playOn(to: String) {
        val now = client.state.value.now
        val holderOnline = now != null && client.state.value.devices.any { it.id == now.device }
        scope.launch { if (!client.tell(ConnectProtocol.transfer(to, force = !holderOnline))) note(Note.Refused("")) }
    }

    /** Steers the device playing elsewhere; [value] is the second for "seek". */
    fun remote(action: String, value: Double? = null) {
        val now = client.state.value.now ?: return
        if (now.device == deviceId) return
        scope.launch { client.tell(ConnectProtocol.command(now.device, action, value)) }
    }

    private var volumeAsked: Double? = null
    private var volumeJob: kotlinx.coroutines.Job? = null

    /**
     * Sets the volume of the device playing elsewhere to [value] (0 to 1). A
     * dragged slider calls this at every step: at most ten go out a second,
     * which is all the room takes from one device, and the last one always.
     */
    fun remoteVolume(value: Double) {
        volumeAsked = ConnectProtocol.volume(value) ?: return
        if (volumeJob?.isActive == true) return
        volumeJob = scope.launch {
            while (true) {
                val v = volumeAsked ?: break
                volumeAsked = null
                val now = client.state.value.now
                if (now != null && now.device != deviceId) client.tell(ConnectProtocol.command(now.device, "volume", v))
                delay(VOLUME_EVERY_MS)
            }
        }
    }

    /** The server's clock as this phone reckons it, for where another device's song has got to. */
    fun serverNowMs(): Long = client.serverNowMs()

    /** A song this phone can play, from the way another device described it; null for one it cannot. */
    private fun unifiedOf(t: ConnectTrack): UnifiedTrack? {
        val n = t.id.toLongOrNull()
        val (id, source, type) = when (t.src) {
            "hifi" -> Triple(n?.toString(), n?.let { PlaybackSource.HiFiApi(it) }, SourceType.API)
            // The Cloud plays as the Subsonic server here (AGENTS.md), by the same song id.
            "subsonic", "cloud" -> Triple("subsonic_${t.id}", PlaybackSource.Subsonic(t.id), SourceType.SUBSONIC)
            "qobuz" -> Triple("qobuz_${t.id}", n?.let { PlaybackSource.QobuzCached(it) }, SourceType.QOBUZ)
            "deezer" -> Triple("deezer_${t.id}", n?.let { PlaybackSource.DeezerPreview(it) }, SourceType.DEEZER)
            "apple" -> Triple("apple_${t.id}", n?.let { PlaybackSource.AppleCached(it) }, SourceType.APPLE)
            else -> Triple(null, null, SourceType.LOCAL)
        }
        if (id == null || source == null) return null
        return UnifiedTrack(
            id = id, title = t.title.ifBlank { "Untitled" }, durationSeconds = (t.duration ?: 0.0).toInt(),
            artistName = t.artist.ifBlank { "Unknown Artist" }, albumTitle = t.album.ifBlank { null },
            // Another device's cover only as a plain https:// picture: never a
            // file, a content:// grant or an address carrying a sign-in.
            artworkUri = ConnectProtocol.shareableCover(t.cover), source = source, sourceType = type,
        )
    }

    /** How the other devices see [track]: where it can be found again, and how it looks. */
    private fun descriptorOf(track: Track): ConnectTrack {
        val unified = registry[track.id]
        val (src, id) = when (val s = unified?.source) {
            is PlaybackSource.HiFiApi -> "hifi" to s.tidalId.toString()
            is PlaybackSource.Subsonic -> "subsonic" to s.songId
            is PlaybackSource.QobuzCached -> "qobuz" to s.qobuzId.toString()
            is PlaybackSource.DeezerPreview -> "deezer" to s.deezerId.toString()
            is PlaybackSource.AppleCached -> "apple" to s.appleId.toString()
            null -> when {
                track.appleId != null -> "apple" to track.appleId.toString()
                track.deezerId != null -> "deezer" to track.deezerId.toString()
                track.id > 0 -> "hifi" to track.id.toString()
                else -> "local" to track.id.toString()
            }
            else -> "local" to unified.id
        }
        return ConnectTrack(
            src = src, id = id, title = track.title, artist = track.displayArtist, album = track.album?.title.orEmpty(),
            duration = track.duration.takeIf { it > 0 }?.toDouble(), quality = track.audioQuality.orEmpty(),
            cover = ConnectProtocol.shareableCover(track.coverUrl ?: unified?.artworkUri),
        )
    }

    private fun nameOf(id: String, fallback: String?): String =
        client.state.value.devices.firstOrNull { it.id == id }?.name ?: fallback ?: id

    /** The name the listener gave this phone (Settings › About), else its model. */
    private fun deviceName(): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL.let { model -> if (model.startsWith(Build.MANUFACTURER, ignoreCase = true)) model else "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} $model" }

    // ── A controller, only to act ─────────────────────────────────────

    private suspend fun withController(block: (MediaController) -> Unit) {
        // A controller the service has dropped ignores every command: build another.
        val mc = controllerLock.withLock {
            controller?.takeIf { it.isConnected }
                ?: run { releaseController(); connectController()?.also { controller = it } }
        } ?: return
        runCatching { block(mc) }.onFailure { Log.w(TAG, "connect: ${it.message}") }
    }

    private suspend fun connectController(): MediaController? {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        return withTimeoutOrNull(CONTROLLER_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                future.addListener({ cont.resume(runCatching { future.get() }.getOrNull()) }, MoreExecutors.directExecutor())
                // Timed out: let it go when it does connect, or it holds the service up for good.
                cont.invokeOnCancellation { MediaController.releaseFuture(future) }
            }
        }
    }

    private fun releaseController() {
        controller?.release()
        controller = null
    }

    /** Counts started activities: the app is on screen while any is. */
    private class VisibleActivities(private val onChange: (Boolean) -> Unit) : Application.ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(activity: Activity) { if (started++ == 0) onChange(true) }
        override fun onActivityStopped(activity: Activity) { if (--started <= 0) { started = 0; onChange(false) } }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    companion object {
        private const val TAG = "TryptifyConnect"
        private const val LINGER_MS = 60_000L
        private const val PUBLISH_SETTLE_MS = 250L
        private const val CONTROLLER_TIMEOUT_MS = 5_000L
        private const val VOLUME_SETTLE_MS = 200L
        private const val VOLUME_EVERY_MS = 110L
    }
}
