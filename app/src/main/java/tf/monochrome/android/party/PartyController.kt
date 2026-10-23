// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.party

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.audio.share.ShareShrinker
import tf.monochrome.android.audio.share.ShareSize
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.player.SpeedOverride
import tf.monochrome.android.player.SpeedSetting
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.domain.model.buildCoverUrl
import tf.monochrome.android.glyph.multiplayer.RoomProtocol
import tf.monochrome.android.player.LocalTrackLocator
import tf.monochrome.android.player.PlaybackService
import tf.monochrome.android.player.QueueManager
import tf.monochrome.android.player.StreamResolver
import tf.monochrome.android.player.UnifiedTrackRegistry
import tf.monochrome.android.radio.RadioQueueManager

/** How this phone is doing with the party's song. */
enum class PartyHearing {
    /** No song at the party. */
    IDLE,

    /** Finding this phone's copy of the song, or fetching the shared one. */
    LOADING,

    /** Playing, within a quarter second of everyone else. */
    IN_SYNC,

    /** Playing, but catching up after a seek, a stall or a late start. */
    CATCHING_UP,

    /** The host paused the party. */
    PAUSED,

    /** Paused on this phone alone; the party plays on. */
    PAUSED_HERE,

    /** The listener picked something else to play; the party plays on without this phone. */
    ELSEWHERE,

    /** The song is still uploading; it starts here when it is all there. */
    WAITING_FOR_UPLOAD,

    /** No copy here, none shared, and the account's catalogue has no match. */
    CANT_PLAY,
}

/** Everything the party screen draws, as it stands. */
data class PartyUi(
    val client: PartyState = PartyState(),
    val hearing: PartyHearing = PartyHearing.IDLE,
    /** A shared song's download, 0 to 1, while it is fetched. */
    val fetching: Float? = null,
    val shareSongs: Boolean = true,
    /** Covers this phone has for songs the party carries none for (a file's own picture), by item id. */
    val covers: Map<String, String> = emptyMap(),
    /** This phone's songs being made smaller to share (too big as they are), 0 to 1, by item id. */
    val shrinking: Map<String, Float> = emptyMap(),
) {
    /** The cover to show for [item]: the party's, else this phone's own copy's. */
    fun coverFor(item: PartyItem): String? = item.cover ?: covers[item.id]

    val party: PartyView? get() = client.party
    val myId: String? get() = client.myId
    val isHost: Boolean get() = client.isHost
}

/**
 * Owns the listening party while one is on, and plays it through the app's
 * own player: a MediaController on the PlaybackService, as the widget and the
 * player screen drive it, and QueueManager holding the one song, so the
 * notification, the lock screen and playing in the background are the
 * player's own, and the song counts as a play like any other.
 *
 * Each song the party plays is looked for in this order: the copy the phone
 * that added it said it had, a copy on this phone (the library or a download,
 * by catalogue id, ISRC, then title, artist and length), the song shared with
 * the party (fetched into the cache, deleted when the party ends), and last
 * the listener's own catalogue by id or by search. A song none of those finds
 * is sat out in silence, and the screen says so.
 *
 * The party's clock is followed every second: a player more than
 * [PartySync.TOLERANCE_MS] from the party's moment is moved to it. The host's
 * pause from the notification pauses the party; anyone else's pauses only
 * their phone. Playing another song from the app leaves the party playing
 * without this phone, until the listener goes back to it.
 */
@Singleton
class PartyController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: PartyClient,
    private val auth: SupabaseAuthManager,
    private val queueManager: QueueManager,
    private val radioQueueManager: RadioQueueManager,
    private val registry: UnifiedTrackRegistry,
    private val streamResolver: StreamResolver,
    private val localTrackLocator: LocalTrackLocator,
    private val repository: MusicRepository,
    private val catalogIds: tf.monochrome.android.data.api.QobuzIdRegistry,
    private val prefs: PartyPrefs,
    private val notifier: PartyNotifier,
    private val preferences: PreferencesManager,
    private val speedOverride: SpeedOverride,
    private val shrinker: ShareShrinker,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hearing = MutableStateFlow(PartyHearing.IDLE)
    private val fetching = MutableStateFlow<Float?>(null)

    /** Each song's cover as this phone has it (its own copy's), by item id, for songs the party sends none for. */
    private val covers = MutableStateFlow<Map<String, String>>(emptyMap())

    /** This phone's songs being made smaller before they go up, by item id. */
    private val shrinking = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** When to move a drifting player, and how far ahead a seek must aim. */
    private val drift = PartyDrift()
    private var wasPaused = false

    /** The party's playback as last seen, to tell a host's seek from a drift. */
    private var lastAnchor: PartyPlayback? = null

    val state: StateFlow<PartyUi> =
        combine(client.state, hearing, fetching, prefs.shareSongs, combine(covers, shrinking, ::Pair)) { c, h, f, share, (art, shrink) ->
            PartyUi(c, h, f, share, art, shrink)
        }
            .stateIn(scope, SharingStarted.Eagerly, PartyUi())

    /** Whether a party is on: the song menus offer "Add to party" only then. */
    val active: StateFlow<Boolean> = client.state.map { it.active }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var controller: MediaController? = null
    private var work: Job? = null

    /** The party song now in the player, and the queue's Track for it. */
    private var loadedItem: String? = null
    private var loadedTrackId: Long? = null
    private var resolving: Job? = null

    /** Our own play and pause, told apart from the notification's and the app's. */
    private var selfChange = false
    private var pausedHere = false

    /** Songs this phone added, by the id the server gave them: where its copy is. */
    private val mine = mutableMapOf<String, Mine>()

    /** Songs on their way to the server, by the phone's own ref, until it names their id. */
    private val pending = mutableMapOf<String, Mine>()

    /** Shared songs fetched into the cache for this party, by item id. */
    private val fetched = mutableMapOf<String, File>()

    private data class Mine(val unified: UnifiedTrack?, val legacy: Track?, val file: File?)

    private val cacheDir: File get() = File(context.cacheDir, "party")

    fun start(partyName: String) {
        val (name, token) = me() ?: return
        begin()
        client.start(name, token, partyName)
    }

    fun join(code: String) {
        val (name, token) = me() ?: return
        begin()
        client.join(name, token, code)
    }

    /** Whether the listener is signed in, which a party needs as rooms do. */
    fun signedIn(): Boolean = auth.accessToken() != null

    private fun me(): Pair<String, String>? {
        val token = auth.accessToken() ?: return null
        return (auth.playerName() ?: "Listener") to token
    }

    private fun begin() {
        stop()
        cacheDir.deleteRecursively()
        // At the party in the background too, playing or not.
        PartyService.start(context)
        work = scope.launch {
            radioQueueManager.stopRadio()
            controller = connect()
            launch { followSong() }
            launch { followClock() }
            launch { followSpeed() }
            launch { followEvents() }
            // Over, by the server's word or a seat lost for good: the party's
            // song stops with it, and everyone sees why on the party screen.
            client.state.first { it.status == PartyStatus.ENDED || it.status == PartyStatus.FAILED }
            runCatching { controller?.pause() }
            cacheDir.deleteRecursively()
            PartyService.stop(context)
            coroutineContext.cancelChildren()
        }
    }

    /** Leaves the party; the song playing stops with it. */
    fun leave() {
        client.leave()
        runCatching { controller?.pause() }
        finish()
    }

    /** A party that is over, put away: back to no party. */
    fun dismiss() {
        client.reset()
        finish()
    }

    private fun finish() {
        stop()
        PartyService.stop(context)
        cacheDir.deleteRecursively()
        notifier.clear()
    }

    private fun stop() {
        work?.cancel()
        work = null
        // This phone's own speed again.
        speedOverride.set(null)
        resolving?.cancel()
        controller?.release()
        controller = null
        loadedItem = null
        loadedTrackId = null
        pausedHere = false
        mine.clear()
        pending.clear()
        fetched.clear()
        covers.value = emptyMap()
        hearing.value = PartyHearing.IDLE
        fetching.value = null
    }

    private suspend fun connect(): MediaController? {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val mc = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                future.addListener(
                    { cont.resume(runCatching { future.get() }.getOrNull()) { _, _, _ -> } },
                    MoreExecutors.directExecutor(),
                )
            }
        }
        mc?.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (selfChange || !partySongPlaying()) return
                // Only the listener's own press is the party's play or pause.
                // The player also stops when this phone leaves, when the app
                // is swiped away, for a call or for headphones pulled out; on
                // the host's phone each of those paused the party for
                // everyone, which looked like everyone quitting with the host.
                if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) return
                if (client.state.value.status != PartyStatus.IN_PARTY) return
                val party = client.state.value.party ?: return
                // Only a change the party has not made: our own play and
                // pause, coming back from the session, match it already.
                if (playWhenReady == !party.playback.paused) {
                    if (playWhenReady) pausedHere = false
                    return
                }
                // The notification, the lock screen or the app's own button.
                if (client.state.value.isHost) {
                    if (playWhenReady) client.resume() else client.pause()
                } else if (!party.playback.paused) {
                    pausedHere = !playWhenReady
                }
            }
        })
        if (mc == null) Log.w(TAG, "no player to play the party through")
        return mc
    }

    // The song.

    private suspend fun followSong() {
        client.state.map { it.party?.now }.distinctUntilChanged().collect { item ->
            if (item == null) {
                resolving?.cancel()
                loadedItem = null
                hearing.value = PartyHearing.IDLE
                return@collect
            }
            val shareTurnedReady = item.id == loadedItem && loadedTrackId == null && item.shared?.ready == true
            if (item.id == loadedItem && !shareTurnedReady) return@collect
            loadedItem = item.id
            loadedTrackId = null
            pausedHere = false
            resolving?.cancel()
            resolving = scope.launch { load(item) }
        }
    }

    /** Whether the player holds the party's song, not one picked elsewhere in the app. */
    private fun partySongPlaying(): Boolean {
        val id = loadedTrackId ?: return false
        return queueManager.currentTrack.value?.id == id
    }

    private suspend fun load(item: PartyItem) {
        hearing.value = PartyHearing.LOADING
        val media = runCatching { resolve(item) }.onFailure { Log.w(TAG, "party song: ${it.message}") }.getOrNull()
        if (loadedItem != item.id) return
        if (media == null) {
            val uploading = item.shared?.ready == false
            hearing.value = if (uploading) PartyHearing.WAITING_FOR_UPLOAD else PartyHearing.CANT_PLAY
            runCatching { controller?.pause() }
            return
        }
        val (mediaItem, legacy) = media
        val mc = controller ?: run {
            hearing.value = PartyHearing.CANT_PLAY
            return
        }
        queueManager.setQueue(listOf(legacy), 0)
        val party = client.state.value.party ?: return
        // A playing song is started where the party will be once it has
        // loaded, not where it was when asked.
        val target = PartySync.target(party.playback, client.serverNowMs(), item.durationMs) +
            if (party.playback.paused) 0 else drift.leadMs
        coverOf(legacy)?.let { art -> covers.value = covers.value + (item.id to art) }
        drift.loaded(System.currentTimeMillis())
        wasPaused = party.playback.paused
        selfChange = true
        try {
            mc.setMediaItem(mediaItem, target)
            mc.prepare()
            if (party.playback.paused) mc.pause() else mc.play()
        } finally {
            selfChange = false
        }
        loadedTrackId = legacy.id
        hearing.value = PartyHearing.CATCHING_UP
    }

    /** This phone's way to play [item], and the Track the queue shows, or null for none. */
    private suspend fun resolve(item: PartyItem): Pair<MediaItem, Track>? {
        mine[item.id]?.let { own ->
            own.unified?.let { return playable(it) }
            own.legacy?.let { return playableLegacy(it) }
        }
        val catalogId = PartyMatch.catalogKey(item.catalogId)?.takeIf { it.first == "tidal" }?.second
        val local = withContext(Dispatchers.IO) {
            localTrackLocator.findLocalSource(
                title = item.title, artist = item.artist, albumTitle = item.album.ifBlank { null },
                durationSeconds = (item.durationMs / 1000).toInt(), catalogTrackId = catalogId, isrc = item.isrc,
            )
        }
        if (local != null) return playable(unifiedFor(item, local))
        val shared = item.shared
        if (shared != null) {
            if (!shared.ready) return null
            val file = fetched[item.id] ?: fetch(item) ?: return null
            return playable(unifiedFor(item, PlaybackSource.LocalFile(file.absolutePath, codecFor(shared.ext), 0)))
        }
        exact(item)?.let { return it }
        // Last resort, for a song the host sent with no catalogue id (an older
        // app) or one its own catalogue refused: the same song by name.
        val track = withContext(Dispatchers.IO) { search(item) } ?: return null
        return playableLegacy(track)
    }

    /**
     * [item] from the very catalogue and id the host played it from, through
     * trypt-hifi, as the host's own phone plays it: a Qobuz song from Qobuz,
     * a Deezer song from Deezer, a TIDAL song from TIDAL. Null when it has no
     * catalogue id, or that catalogue cannot serve it here.
     */
    private suspend fun exact(item: PartyItem): Pair<MediaItem, Track>? {
        val (kind, id) = PartyMatch.catalogKey(item.catalogId) ?: return null
        return when (kind) {
            "qobuz" -> playable(unifiedFor(item, "qobuz_$id", PlaybackSource.QobuzCached(id), SourceType.QOBUZ))
            "deezer" -> playable(
                unifiedFor(item, "deezer_$id", PlaybackSource.DeezerPreview(id, isrc = item.isrc), SourceType.DEEZER),
            )
            "tidal" -> withContext(Dispatchers.IO) {
                // An older app sent Qobuz ids as TIDAL ones, so a TIDAL id is
                // believed only when TIDAL names the same song under it.
                runCatching { repository.getTidalTrack(id) }.getOrNull()
                    ?.takeIf { PartyMatch.sameTitle(it.title, item.title) }
            }?.let { playableLegacy(it) }
            else -> null
        }
    }

    /**
     * [track]'s cover as the app's lists draw it: a catalogue cover's address,
     * or a local file's own picture (its path, which the app's image loader
     * reads the embedded picture from).
     */
    private fun coverOf(track: Track): String? =
        track.album?.cover?.takeIf { it.isNotBlank() }?.let { buildCoverUrl(it, 640) }

    private suspend fun playable(track: UnifiedTrack): Pair<MediaItem, Track>? {
        val resolved = withContext(Dispatchers.IO) { streamResolver.resolveUnifiedTrack(track) }
        if (!resolved.isPlayable) return null
        val legacy = track.toLegacyTrack()
        registry.put(legacy.id, track)
        return resolved.mediaItem to legacy
    }

    private suspend fun playableLegacy(track: Track): Pair<MediaItem, Track>? {
        val (media, _) = withContext(Dispatchers.IO) { streamResolver.resolveMediaItem(track) }
        return media?.let { it to track }
    }

    /** The listener's own catalogue, searched by artist and title, a match within 3 s of the length. */
    private suspend fun search(item: PartyItem): Track? {
        val query = listOf(item.artist, item.title).filter { it.isNotBlank() }.joinToString(" ")
        val found = repository.searchTracks(query, limit = 10).getOrNull().orEmpty()
        return PartyMatch.best(item, found)
    }

    private suspend fun fetch(item: PartyItem): File? {
        val ext = RoomProtocol.songExt("song." + (item.shared?.ext ?: "bin"))
        val into = File(cacheDir, "${item.id.filter(Char::isLetterOrDigit)}.$ext")
        fetching.value = 0f
        val result = client.fetch(item, into) { p -> fetching.value = p }
        fetching.value = null
        return result.getOrNull()?.also { fetched[item.id] = it }
    }

    private fun unifiedFor(
        item: PartyItem,
        source: PlaybackSource.LocalFile,
    ) = unifiedFor(item, "party_${item.id}_${item.title.hashCode()}", source, SourceType.LOCAL)

    private fun unifiedFor(item: PartyItem, id: String, source: PlaybackSource, type: SourceType) = UnifiedTrack(
        id = id,
        title = item.title,
        durationSeconds = (item.durationMs / 1000).toInt(),
        artistName = item.artist.ifBlank { "Unknown Artist" },
        albumTitle = item.album.ifBlank { null },
        artworkUri = item.cover,
        isrc = item.isrc,
        source = source,
        sourceType = type,
    )

    // The clock.

    /**
     * The host's speed for everyone, vinyl style: at a party the pitch always
     * rides the speed (the owner's call), whatever the keep-pitch switch says,
     * so every phone and the website hear one sound. The host's own speed
     * setting goes to the party (a quarter second after a slider stops moving,
     * not at every step of it). Every phone, the host's too, plays through
     * [SpeedOverride]: the host its own speed with the pitch riding it, the
     * rest the party's. No setting is written, so the keep-pitch switch is as
     * it was once the party is over.
     */
    @OptIn(FlowPreview::class)
    private suspend fun followSpeed() = coroutineScope {
        val hosting = client.state.map { it.isHost && it.status == PartyStatus.IN_PARTY }.distinctUntilChanged()
        launch {
            combine(hosting, preferences.playbackSpeed) { host, speed -> if (host) speed else null }
                .debounce(250)
                .distinctUntilChanged()
                .collect { mine -> if (mine != null) client.setRate(mine, keepPitch = false) }
        }
        combine(client.state, preferences.playbackSpeed) { s, mySpeed ->
            when {
                s.isHost -> SpeedSetting(mySpeed, keepPitch = false)
                else -> s.party?.playback?.let { SpeedSetting(it.rate.toFloat(), keepPitch = false) }
            }
        }
            .distinctUntilChanged()
            .collect { speedOverride.set(it) }
    }

    private suspend fun followClock() {
        while (scope.isActive) {
            delay(SYNC_EVERY_MS)
            val mc = controller ?: continue
            val party = client.state.value.party ?: continue
            val item = party.now
            if (item == null || item.id != loadedItem || loadedTrackId == null) continue
            if (!partySongPlaying()) {
                hearing.value = PartyHearing.ELSEWHERE
                continue
            }
            if (party.playback.paused) {
                if (mc.playWhenReady) self { mc.pause() }
                // Paused, a seek drops nothing: the player is put exactly where the party stopped.
                val target = PartySync.target(party.playback, client.serverNowMs(), item.durationMs)
                if (PartySync.needsSeek(mc.currentPosition, target)) self { mc.seekTo(target) }
                wasPaused = true
                hearing.value = PartyHearing.PAUSED
                continue
            }
            if (wasPaused) {
                // Just resumed: the player starts from where it waits, so it gets time to start.
                wasPaused = false
                drift.loaded(System.currentTimeMillis())
            }
            if (pausedHere) {
                hearing.value = PartyHearing.PAUSED_HERE
                continue
            }
            if (!mc.playWhenReady) self { mc.play() }
            if (mc.playbackState != Player.STATE_READY) {
                hearing.value = PartyHearing.CATCHING_UP
                continue
            }
            // A new anchor for the same song is the host seeking: followed at once.
            val anchor = party.playback
            if (lastAnchor != null && lastAnchor != anchor && lastAnchor?.itemId == anchor.itemId) drift.moved()
            lastAnchor = anchor
            val target = PartySync.target(party.playback, client.serverNowMs(), item.durationMs)
            val local = mc.currentPosition
            val seekTo = drift.check(local, target, System.currentTimeMillis())
            if (seekTo != null) {
                self { mc.seekTo(seekTo.coerceAtLeast(0)) }
                hearing.value = PartyHearing.CATCHING_UP
            } else {
                hearing.value =
                    if (PartySync.needsSeek(local, target)) PartyHearing.CATCHING_UP else PartyHearing.IN_SYNC
            }
        }
    }

    private inline fun self(block: () -> Unit) {
        selfChange = true
        try {
            block()
        } finally {
            selfChange = false
        }
    }

    /** Back to the party after playing something else, or after pausing here. */
    fun rejoinPlayback() {
        pausedHere = false
        val item = client.state.value.party?.now ?: return
        loadedItem = item.id
        loadedTrackId = null
        resolving?.cancel()
        resolving = scope.launch { load(item) }
    }

    // What happens once.

    private suspend fun followEvents() {
        client.events.collect { msg ->
            when (msg) {
                is PartyMsg.Added -> {
                    val own = pending.remove(msg.ref) ?: return@collect
                    mine[msg.itemId] = own
                    (own.unified?.let { coverOf(it.toLegacyTrack()) } ?: own.legacy?.let(::coverOf))
                        ?.let { art -> covers.value = covers.value + (msg.itemId to art) }
                    val file = own.file
                    if (file != null && prefs.shareSongs.value) share(msg.itemId, file)
                }
                is PartyMsg.Line -> notifier.line(msg.line, client.state.value.myId)
                else -> Unit
            }
        }
    }

    /**
     * One of this phone's songs shared with the party, so every phone without
     * it and the website play this very file. One upload at a time, in the
     * order the songs went in (a whole playlist added at once used to upload
     * every song together, the playing one starved by the rest), and tried
     * up to three times: a dropped connection used to leave it "Uploading"
     * for good. A song no longer in the queue is not uploaded at all.
     *
     * A file bigger than the server takes (its `maxSongBytes`, or what is
     * left of the party's room) goes up as an Opus copy at 320 kbps
     * ([ShareSize], [ShareShrinker]), made once before the tries and deleted
     * after them. Where no copy can be made, or it is still too big, nothing
     * goes up but an empty upload ([PartyClient.giveUpUpload]), and the song
     * shows as not shared: Cloudflare refuses a body over 100 MB before the
     * server sees it, which left the song "Uploading" for good.
     */
    private fun share(itemId: String, file: File) {
        scope.launch {
            uploads.withLock {
                val party = client.state.value.party ?: return@withLock
                val item = party.now?.takeIf { it.id == itemId } ?: party.queue.firstOrNull { it.id == itemId } ?: return@withLock
                val limit = ShareSize.limit(client.maxSongBytes, party.sharedLimit, party.sharedBytes)
                val prepared = try {
                    withContext(Dispatchers.IO) {
                        ShareSize.prepare(
                            shrinker, Uri.fromFile(file).toString(), file.length(), RoomProtocol.songExt(file.name), limit,
                            durationMs = item.durationMs.takeIf { it > 0 },
                        ) { p -> shrinking.update { it + (itemId to p.coerceIn(0f, 1f)) } }
                    }
                } finally {
                    shrinking.update { it - itemId }
                }
                if (prepared.shrunk == null && file.length() > limit) {
                    client.giveUpUpload(itemId, prepared.ext)
                    return@withLock
                }
                try {
                    repeat(UPLOAD_TRIES) { attempt ->
                        val now = client.state.value.party ?: return@withLock
                        if (now.now?.id != itemId && now.queue.none { it.id == itemId }) return@withLock
                        val done = client.upload(itemId, prepared.shrunk ?: file, prepared.ext) { }
                            .onFailure { Log.w(TAG, "share, try ${attempt + 1}: ${it.message}") }
                        if (done.isSuccess) return@withLock
                        delay(2_000L shl attempt)
                    }
                } finally {
                    prepared.discard()
                }
            }
        }
    }

    private val uploads = Mutex()

    // Adding songs.

    /** A song from a menu: [filePath] is its file where the list knows one. */
    fun add(track: Track, filePath: String? = null) {
        scope.launch { prepareAndSend(track, filePath) }
    }

    /**
     * Many songs at once, a multi-selection or a whole playlist or album, into
     * the queue in the order given: one after another, each looked for on
     * this phone first, so a slow lookup never lets a later song in ahead.
     */
    fun addAll(tracks: List<Track>) {
        scope.launch { tracks.forEach { prepareAndSend(it, null) } }
    }

    fun addAllUnified(tracks: List<UnifiedTrack>) = tracks.forEach { add(it) }

    private suspend fun prepareAndSend(track: Track, filePath: String?) {
        run {
            val file = filePath?.let(::File)?.takeIf { it.isFile }
                ?: withContext(Dispatchers.IO) {
                    localTrackLocator.findLocalSource(
                        title = track.title, artist = track.displayArtist, albumTitle = track.album?.title,
                        durationSeconds = track.duration, catalogTrackId = track.id,
                    )?.filePath?.let(::File)?.takeIf { it.isFile }
                }
            val local = file != null && filePath != null
            val catalogId = catalogIdOf(track, local)
            // TIDAL is asked for an ISRC only for a TIDAL song: asked with a
            // Qobuz id it answered for whatever TIDAL song has that number.
            val isrc = if (catalogId?.startsWith("tidal:") != true) null
            else withContext(Dispatchers.IO) { runCatching { repository.getTidalIsrc(track.id) }.getOrNull() }
            val item = PartyAdd(
                title = track.title,
                artist = track.displayArtist,
                album = track.album?.title.orEmpty(),
                durationMs = track.duration * 1000L,
                isrc = isrc,
                catalogId = catalogId,
                cover = track.album?.cover?.let { buildCoverUrl(it, 640) }?.takeIf { it.startsWith("https://") },
                share = file != null && prefs.shareSongs.value,
            )
            send(item, Mine(unified = null, legacy = track, file = file))
        }
    }

    /**
     * Which catalogue [track] is from, by the ids this app has seen come out
     * of each: a Qobuz, Apple or Deezer id is a plain number like a TIDAL one,
     * and labelled TIDAL every other phone and the website played whatever
     * TIDAL song has that number, or nothing.
     */
    private fun catalogIdOf(track: Track, local: Boolean): String? = when {
        local -> null
        track.appleId != null -> "apple:${track.appleId}"
        track.deezerId != null -> "deezer:${track.deezerId}"
        catalogIds.isAppleTrack(track.id) -> "apple:${track.id}"
        catalogIds.isDeezerTrack(track.id) -> "deezer:${track.id}"
        catalogIds.isQobuzTrack(track.id) -> "qobuz:${track.id}"
        else -> PartyMatch.catalogId(track, local)
    }

    /** A song that is a file on this phone: the library, or one opened with Tryptify. */
    fun add(track: UnifiedTrack) {
        val file = (track.source as? PlaybackSource.LocalFile)?.filePath?.let(::File)?.takeIf { it.isFile }
        val item = PartyAdd(
            title = track.title,
            artist = track.artistName,
            album = track.albumTitle.orEmpty(),
            durationMs = track.durationSeconds * 1000L,
            isrc = track.isrc,
            // Which catalogue song it is, so every phone and the website find
            // the same recording; search results are UnifiedTracks, and
            // without it a song added from search was found by name alone.
            catalogId = PartyMatch.catalogId(track.source),
            // A TIDAL cover comes as its id: made an address, as the lists do.
            cover = track.artworkUri?.let { buildCoverUrl(it, 640) }?.takeIf { it.startsWith("https://") },
            share = file != null && prefs.shareSongs.value,
        )
        send(item, Mine(unified = track, legacy = null, file = file))
    }

    private fun send(item: PartyAdd, own: Mine) {
        if (item.durationMs <= 0) return
        val ref = UUID.randomUUID().toString()
        pending[ref] = own
        client.add(item, ref)
    }

    fun setShareSongs(on: Boolean) = prefs.setShareSongs(on)

    // The host's moves, and everyone's.

    fun togglePlay() {
        val party = client.state.value.party ?: return
        if (party.playback.paused) client.resume() else client.pause()
    }

    fun seek(positionMs: Long) = client.seek(positionMs)
    fun skip() = client.state.value.party?.now?.let { client.skip(it.id) }
    fun vote() {
        val s = client.state.value
        val item = s.party?.now ?: return
        client.vote(item.id, s.myId !in s.party.skip.votes)
    }
    fun remove(itemId: String) = client.remove(itemId)
    fun playNext(itemId: String) = client.move(itemId, 0)
    fun kick(id: String) = client.kick(id)
    fun passHost(id: String) = client.passHost(id)
    fun endParty() = client.endParty()
    fun chat(text: String) = text.trim().takeIf { it.isNotEmpty() }?.let { client.chat(it) }
    fun clearError() = client.clearError()
    fun inviteLink(): String? = client.inviteLink()

    /** The party's moment in its song, for the progress bar. */
    fun positionNow(): Long {
        val party = client.state.value.party ?: return 0
        return PartySync.target(party.playback, client.serverNowMs(), party.now?.durationMs ?: 0)
    }

    /** Whether the party screen is on show; its chat is then not notified. */
    fun screenShown(shown: Boolean) = notifier.screenShown(shown)

    companion object {
        private const val TAG = "PartyController"
        private const val UPLOAD_TRIES = 3
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val SYNC_EVERY_MS = 1_000L

        fun codecFor(ext: String): AudioCodec = when (ext.lowercase()) {
            "flac" -> AudioCodec.FLAC
            "mp3" -> AudioCodec.MP3
            "m4a", "aac", "mp4" -> AudioCodec.AAC
            "alac" -> AudioCodec.ALAC
            "ogg", "oga" -> AudioCodec.OGG_VORBIS
            "opus" -> AudioCodec.OPUS
            "wav" -> AudioCodec.WAV
            "aif", "aiff" -> AudioCodec.AIFF
            else -> AudioCodec.UNKNOWN
        }
    }
}

/**
 * How a party's song is matched to a song in this listener's catalogue, and
 * how a song is named for the others to match.
 */
object PartyMatch {
    /** Lengths within this much are the same recording, as the rooms' own match has it. */
    const val LENGTH_SLACK_MS = 3_000L

    /** Two titles for one song: the same letters, one maybe with a version on (as the server judges). */
    fun sameTitle(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val (x, y) = norm(a) to norm(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x.startsWith(y) || y.startsWith(x))
    }

    /** A song's catalogue id from where it plays from; null for a file or a collection. */
    fun catalogId(source: PlaybackSource): String? = when (source) {
        is PlaybackSource.HiFiApi -> "tidal:${source.tidalId}"
        is PlaybackSource.QobuzCached -> "qobuz:${source.qobuzId}"
        is PlaybackSource.AppleCached -> "apple:${source.appleId}"
        is PlaybackSource.DeezerPreview -> "deezer:${source.deezerId}"
        else -> null
    }

    /** A party catalogue id split into its catalogue and number: "qobuz:12" is ("qobuz", 12). */
    fun catalogKey(catalogId: String?): Pair<String, Long>? {
        val kind = catalogId?.substringBefore(':', "")?.takeIf { it.isNotEmpty() } ?: return null
        val id = catalogId.substringAfter(':').toLongOrNull()?.takeIf { it > 0 } ?: return null
        return kind to id
    }

    /** [track]'s id in its catalogue as the party carries it; none for a file of the listener's own. */
    fun catalogId(track: Track, localFile: Boolean): String? = when {
        localFile -> null
        track.appleId != null -> "apple:${track.appleId}"
        track.deezerId != null -> "deezer:${track.deezerId}"
        track.id > 0 -> "tidal:${track.id}"
        else -> null
    }

    /** Of [found], the one that is [item]: the same title and artist, near enough the same length. */
    fun best(item: PartyItem, found: List<Track>): Track? {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val title = norm(item.title)
        val artist = norm(item.artist)
        return found
            .filter { norm(it.title) == title || norm(it.title).startsWith(title) }
            .filter { artist.isEmpty() || norm(it.displayArtist).contains(artist) || artist.contains(norm(it.displayArtist)) }
            .filter { item.durationMs <= 0 || it.duration <= 0 || kotlin.math.abs(it.duration * 1000L - item.durationMs) <= LENGTH_SLACK_MS }
            .minByOrNull { kotlin.math.abs(it.duration * 1000L - item.durationMs) }
    }
}
