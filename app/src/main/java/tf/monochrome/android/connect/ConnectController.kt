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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.R
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.device.DeviceIdProvider
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
 * the others what plays here, does what they ask (play, pause, skip, seek),
 * hands its song over at the same second with what comes next, takes one
 * over the same way, and makes way when another device starts later.
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val visible = MutableStateFlow(false)
    private var deviceId: String = ""

    /** When this phone last started playing, by the server's clock: the later start wins. */
    @Volatile private var playSince = 0L
    private var controller: MediaController? = null
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
        scope.launch { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
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
                releaseController()
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
        return ConnectProtocol.StateOut(c.playing, descriptorOf(track), positionMs / 1000.0, c.speed.toDouble(), playSince)
    }

    private suspend fun send() {
        val out = stateOut() ?: return
        if (client.tell(ConnectProtocol.state(out))) {
            client.remember(ConnectNow(deviceId, deviceName(), out.playing, out.track, out.position, out.rate, client.serverNowMs(), out.since))
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

    private suspend fun followEvents() {
        client.events.collect { m ->
            when (m.type) {
                "command" -> withController { mc ->
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
        val upcoming = queueManager.queue.value.drop(queueManager.currentIndex.value + 1).take(ConnectProtocol.MAX_QUEUE).map(::descriptorOf)
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
        val tracks = (listOf(first) + m.queue.orEmpty()).mapNotNull(::unifiedOf)
        if (tracks.isEmpty() || unifiedOf(first) == null) {
            note(Note.CannotPlayHere(first.title))
            return
        }
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

    /** Steers the device playing elsewhere. */
    fun remote(action: String) {
        val now = client.state.value.now ?: return
        if (now.device == deviceId) return
        scope.launch { client.tell(ConnectProtocol.command(now.device, action)) }
    }

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
        val mc = controller ?: connectController()?.also { controller = it } ?: return
        runCatching { block(mc) }.onFailure { Log.w(TAG, "connect: ${it.message}") }
    }

    private suspend fun connectController(): MediaController? {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        return withTimeoutOrNull(CONTROLLER_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                future.addListener({ cont.resume(runCatching { future.get() }.getOrNull()) }, MoreExecutors.directExecutor())
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
    }
}
