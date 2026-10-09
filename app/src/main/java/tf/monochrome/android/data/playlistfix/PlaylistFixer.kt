package tf.monochrome.android.data.playlistfix

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.api.ApiService
import tf.monochrome.android.data.api.InstanceManager
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.db.dao.PlaylistTrackReplacement
import tf.monochrome.android.data.db.entity.PlaylistTrackEntity
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.player.LocalTrackLocator
import javax.inject.Inject
import javax.inject.Singleton

/** The catalogues a playlist can be checked against or rebuilt from, in fallback order. */
val PLAYLIST_FIX_SERVICES = listOf(ApiService.TIDAL, ApiService.QOBUZ, ApiService.DEEZER)

enum class PlaylistFixMode { REPAIR, REGENERATE }

/** How a repair or regeneration went, row by row. */
data class PlaylistFixResult(
    val total: Int,
    /** Downloaded, or a file on the device: played from disk, so never touched. */
    val onDevice: Int,
    /** The stored id is the song the row says it is (on the chosen service). */
    val alreadyRight: Int,
    /** Rows now pointing at the right song. */
    val fixed: Int,
    /** Wrong rows removed because the right song is already in the playlist. */
    val merged: Int,
    /** "Title — Artist" of rows no catalogue had a sure match for; left as they were. */
    val notMatched: List<String>,
    /** Rows that couldn't be checked: their catalogue isn't connected or didn't answer. */
    val unchecked: Int,
    /** Stopped before the end; the rows done so far stay done. */
    val stopped: Boolean,
)

sealed interface PlaylistFixState {
    /** The playlist this job is for; null when there is none. */
    val playlistId: String?

    data object Idle : PlaylistFixState {
        override val playlistId: String? = null
    }

    data class Running(
        override val playlistId: String,
        val mode: PlaylistFixMode,
        val checked: Int,
        val total: Int,
        val fixed: Int,
    ) : PlaylistFixState

    data class Done(
        override val playlistId: String,
        val mode: PlaylistFixMode,
        val result: PlaylistFixResult,
    ) : PlaylistFixState

    data class Failed(
        override val playlistId: String,
        val mode: PlaylistFixMode,
        val message: String,
    ) : PlaylistFixState
}

/** What a search on a row's own catalogue says about the id it stores. */
internal enum class StoredIdCheck {
    /** Listed, and the same song as the row. */
    RIGHT,

    /** Listed, and a different song: the id is wrong. */
    WRONG,

    /** Not in the results, so the search can't tell. */
    NOT_LISTED,
}

internal fun checkStoredId(storedId: Long, target: SongTarget, candidates: List<Track>): StoredIdCheck {
    val listed = candidates.firstOrNull { it.id == storedId } ?: return StoredIdCheck.NOT_LISTED
    return if (TrackMatcher.matches(target, listed)) StoredIdCheck.RIGHT else StoredIdCheck.WRONG
}

/**
 * Repairs a playlist's wrong song ids, or rebuilds the playlist from one
 * catalogue with another as fallback, matching every song by title, artist and
 * length ([TrackMatcher]).
 *
 * **Why ids go wrong.** A playlist row stores a bare number, and which catalogue
 * the number belongs to is remembered apart from it, in [QobuzIdRegistry]. When
 * that is lost — a playlist pulled from the cloud onto a new device, say — a
 * Qobuz or Deezer id is played as the TIDAL track that has the same number: a
 * different song under this row's title. The importer could also store the wrong
 * song outright, by taking a search's first hit.
 *
 * **What is never touched.** A row that is downloaded, or that a file on the
 * device answers for, plays from disk whatever its id says; rewriting its id would
 * only detach it from its download. Such rows are counted and skipped. A row no
 * catalogue has a sure match for is left as it was, never removed.
 *
 * Each rewrite goes through [LibraryRepository.replacePlaylistTrack], which keeps
 * the row's place and queues both sync keys, so the next pull can't bring a wrong
 * id back. The work runs on this singleton's own scope, so leaving the playlist
 * screen doesn't stop it; [stop] does, after the row in hand.
 */
@Singleton
class PlaylistFixer @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val musicRepository: MusicRepository,
    private val registry: QobuzIdRegistry,
    private val instanceManager: InstanceManager,
    private val localTrackLocator: LocalTrackLocator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<PlaylistFixState>(PlaylistFixState.Idle)
    val state: StateFlow<PlaylistFixState> = _state.asStateFlow()

    /** The catalogues with a server under Settings › Connections, in fallback order. */
    suspend fun availableServices(): List<ApiService> = PLAYLIST_FIX_SERVICES.filter { service ->
        when (service) {
            ApiService.TIDAL -> instanceManager.tidalInstanceOrNull() != null
            ApiService.QOBUZ -> instanceManager.qobuzInstanceOrNull() != null
            ApiService.DEEZER -> instanceManager.deezerInstanceOrNull() != null
            ApiService.APPLE -> false
        }
    }

    /**
     * Checks every row that isn't on the device against its own catalogue, and
     * relinks the ones whose id plays a different song. False when a job is
     * already running.
     */
    fun repair(playlistId: String): Boolean =
        start(playlistId, PlaylistFixMode.REPAIR) { row, services -> repairRow(row, services) }

    /**
     * Finds every row that isn't on the device again on [primary], then on
     * [fallback] when [primary] has no sure match. False when a job is already
     * running.
     */
    fun regenerate(playlistId: String, primary: ApiService, fallback: ApiService?): Boolean {
        val order = listOfNotNull(primary, fallback?.takeIf { it != primary })
        return start(playlistId, PlaylistFixMode.REGENERATE) { row, services ->
            regenerateRow(row, order.filter { it in services })
        }
    }

    /** Stops the running job; rows already rewritten stay rewritten. */
    fun stop() {
        job?.cancel()
    }

    /** Clears a finished job's summary for [playlistId]. */
    fun dismiss(playlistId: String) {
        _state.update { s ->
            when {
                s is PlaylistFixState.Done && s.playlistId == playlistId -> PlaylistFixState.Idle
                s is PlaylistFixState.Failed && s.playlistId == playlistId -> PlaylistFixState.Idle
                else -> s
            }
        }
    }

    @Synchronized
    private fun start(
        playlistId: String,
        mode: PlaylistFixMode,
        decide: suspend (row: PlaylistTrackEntity, services: List<ApiService>) -> RowOutcome,
    ): Boolean {
        if (job?.isActive == true) return false
        _state.value = PlaylistFixState.Running(playlistId, mode, checked = 0, total = 0, fixed = 0)
        job = scope.launch { run(playlistId, mode, decide) }
        return true
    }

    private suspend fun run(
        playlistId: String,
        mode: PlaylistFixMode,
        decide: suspend (row: PlaylistTrackEntity, services: List<ApiService>) -> RowOutcome,
    ) {
        val tally = Tally()
        try {
            // Which catalogue an id is in is read from the registry; until it is
            // loaded every id would read as TIDAL.
            registry.awaitLoaded()
            val services = availableServices()
            val rows = libraryRepository.getPlaylistTrackRows(playlistId)
            tally.total = rows.size
            publish(playlistId, mode, tally)
            rows.chunked(PARALLEL_ROWS).forEach { chunk ->
                val outcomes = coroutineScope { chunk.map { row -> async { decide(row, services) } }.awaitAll() }
                // Written one at a time, in playlist order, and never half-way:
                // a row rewritten without its sync keys queued would come back
                // wrong on the next pull.
                withContext(NonCancellable) {
                    chunk.zip(outcomes).forEach { (row, outcome) -> record(playlistId, row, outcome, tally) }
                }
                publish(playlistId, mode, tally)
            }
            _state.value = PlaylistFixState.Done(playlistId, mode, tally.result(stopped = false))
        } catch (e: CancellationException) {
            _state.value = PlaylistFixState.Done(playlistId, mode, tally.result(stopped = true))
            throw e
        } catch (e: Exception) {
            _state.value = PlaylistFixState.Failed(playlistId, mode, e.message.orEmpty())
        }
    }

    private fun publish(playlistId: String, mode: PlaylistFixMode, tally: Tally) {
        _state.value = PlaylistFixState.Running(playlistId, mode, tally.checked, tally.total, tally.fixed)
    }

    // ── Per row ───────────────────────────────────────────────────────────

    internal sealed interface RowOutcome {
        data object OnDevice : RowOutcome
        data object Right : RowOutcome
        /** The id was right; its catalogue had been forgotten and is known again. */
        data object Restored : RowOutcome
        data class Replace(val track: Track) : RowOutcome
        data class NotMatched(val label: String) : RowOutcome
        data object Unchecked : RowOutcome
    }

    private suspend fun repairRow(row: PlaylistTrackEntity, services: List<ApiService>): RowOutcome {
        if (isOnDevice(row)) return RowOutcome.OnDevice
        val target = row.target()
        val own = playsFrom(row.trackId) ?: return RowOutcome.Unchecked
        if (own !in services) return RowOutcome.Unchecked
        val ownLookup = lookUp(own, target) ?: return RowOutcome.Unchecked

        when (storedIdIsRight(row, target, own, ownLookup)) {
            true -> return RowOutcome.Right
            // Unproven: only the same song from the same catalogue may take its place.
            null -> return ownLookup.best?.let { RowOutcome.Replace(it) } ?: RowOutcome.NotMatched(row.label())
            false -> Unit
        }

        // The id plays the wrong song. It may be another catalogue's id whose
        // catalogue was forgotten: searching that catalogue records its results,
        // which puts the id back where it belongs without rewriting the row.
        val elsewhere = mutableListOf<Lookup>()
        for (service in services) {
            if (service == own) continue
            val lookup = lookUp(service, target) ?: continue
            if (checkStoredId(row.trackId, target, lookup.candidates) == StoredIdCheck.RIGHT &&
                routesOnlyTo(row.trackId, service)
            ) return RowOutcome.Restored
            elsewhere += lookup
        }
        val replacement = ownLookup.best ?: elsewhere.firstNotNullOfOrNull { it.best }
        return replacement?.let { RowOutcome.Replace(it) } ?: RowOutcome.NotMatched(row.label())
    }

    private suspend fun regenerateRow(row: PlaylistTrackEntity, order: List<ApiService>): RowOutcome {
        if (isOnDevice(row)) return RowOutcome.OnDevice
        val target = row.target()
        var reached = false
        for (service in order) {
            val lookup = lookUp(service, target) ?: continue
            reached = true
            // Already on this service: kept when right, and when it can't be
            // proven wrong — a search that misses a song is no reason to move it
            // to the fallback.
            if (playsFrom(row.trackId) == service) {
                when (storedIdIsRight(row, target, service, lookup)) {
                    true -> return RowOutcome.Right
                    null -> return lookup.best?.let { RowOutcome.Replace(it) } ?: RowOutcome.NotMatched(row.label())
                    false -> Unit
                }
            }
            lookup.best?.let { return RowOutcome.Replace(it) }
        }
        return if (reached) RowOutcome.NotMatched(row.label()) else RowOutcome.Unchecked
    }

    private suspend fun record(playlistId: String, row: PlaylistTrackEntity, outcome: RowOutcome, tally: Tally) {
        tally.checked++
        when (outcome) {
            RowOutcome.OnDevice -> tally.onDevice++
            RowOutcome.Right -> tally.right++
            RowOutcome.Restored -> tally.fixed++
            RowOutcome.Unchecked -> tally.unchecked++
            is RowOutcome.NotMatched -> tally.notMatched += outcome.label
            is RowOutcome.Replace ->
                when (libraryRepository.replacePlaylistTrack(playlistId, row.trackId, outcome.track)) {
                    PlaylistTrackReplacement.REPLACED -> tally.fixed++
                    PlaylistTrackReplacement.MERGED -> tally.merged++
                    PlaylistTrackReplacement.UNCHANGED -> tally.right++
                    // Removed from the playlist while this ran: nothing to fix.
                    PlaylistTrackReplacement.GONE -> Unit
                }
        }
    }

    /**
     * Whether the row's stored id is its song on [service], the catalogue it plays
     * from: true or false when that can be told, null when it can't. A search that
     * lists the id settles it. Otherwise TIDAL can be asked about the id itself —
     * nothing there, or a different song, is the forgotten-catalogue case — while
     * Qobuz and Deezer can't, so their unlisted ids stay unproven.
     */
    private suspend fun storedIdIsRight(
        row: PlaylistTrackEntity,
        target: SongTarget,
        service: ApiService,
        lookup: Lookup,
    ): Boolean? = when (checkStoredId(row.trackId, target, lookup.candidates)) {
        StoredIdCheck.RIGHT -> true
        StoredIdCheck.WRONG -> false
        StoredIdCheck.NOT_LISTED ->
            if (service == ApiService.TIDAL) {
                musicRepository.getTidalTrack(row.trackId)?.let { TrackMatcher.matches(target, it) } ?: false
            } else null
    }

    // ── Lookups ───────────────────────────────────────────────────────────

    /** What one catalogue's search turned up for a row. */
    private class Lookup(val candidates: List<Track>, val best: Track?)

    /**
     * Searches [service] for [target], most specific query first, and keeps the
     * best sure match whose id would really play from [service]. Null when the
     * service couldn't be searched at all.
     */
    private suspend fun lookUp(service: ApiService, target: SongTarget): Lookup? {
        val seen = mutableListOf<Track>()
        var reached = false
        for (query in TrackMatcher.queries(target)) {
            val hits = search(service, query) ?: continue
            reached = true
            seen += hits
            val best = TrackMatcher.best(target, hits.filter { routesOnlyTo(it.id, service) })
            if (best != null) return Lookup(seen, best)
        }
        return if (reached) Lookup(seen, null) else null
    }

    private suspend fun search(service: ApiService, query: String): List<Track>? = when (service) {
        ApiService.TIDAL -> musicRepository.searchTracks(query).getOrNull()
        ApiService.QOBUZ -> musicRepository.searchQobuz(query).getOrNull()?.tracks
        ApiService.DEEZER -> musicRepository.searchDeezer(query).getOrNull()?.tracks
        ApiService.APPLE -> null
    }

    /**
     * The catalogue a stored id plays from, in the order the stream resolver
     * asks: Qobuz, then Deezer, then TIDAL. Null for an Apple id, which this
     * doesn't check.
     */
    private fun playsFrom(id: Long): ApiService? = when {
        registry.isQobuzTrack(id) -> ApiService.QOBUZ
        registry.isDeezerTrack(id) -> ApiService.DEEZER
        registry.isAppleTrack(id) -> null
        else -> ApiService.TIDAL
    }

    /**
     * True when [id] is known only as [service]'s. Catalogue numbers overlap, and
     * the player and the source tag ask the registry in different orders, so an id
     * recorded under two catalogues may play from one and be labelled the other.
     * A replacement must be unambiguous.
     */
    private fun routesOnlyTo(id: Long, service: ApiService): Boolean {
        val qobuz = registry.isQobuzTrack(id)
        val deezer = registry.isDeezerTrack(id)
        val apple = registry.isAppleTrack(id)
        return when (service) {
            ApiService.TIDAL -> !qobuz && !deezer && !apple
            ApiService.QOBUZ -> qobuz && !deezer && !apple
            ApiService.DEEZER -> deezer && !qobuz && !apple
            ApiService.APPLE -> false
        }
    }

    private suspend fun isOnDevice(row: PlaylistTrackEntity): Boolean =
        libraryRepository.isDownloaded(row.trackId) ||
            localTrackLocator.findLocalSource(
                title = row.title,
                artist = row.artistName,
                albumTitle = row.albumTitle,
                durationSeconds = row.duration,
                catalogTrackId = row.trackId,
            ) != null

    private fun PlaylistTrackEntity.target() = SongTarget(
        title = title,
        artist = artistName,
        durationSeconds = duration,
        album = albumTitle,
    )

    private fun PlaylistTrackEntity.label(): String =
        if (artistName.isBlank()) title else "$title — $artistName"

    private class Tally {
        var total = 0
        var checked = 0
        var onDevice = 0
        var right = 0
        var fixed = 0
        var merged = 0
        var unchecked = 0
        val notMatched = mutableListOf<String>()

        fun result(stopped: Boolean) = PlaylistFixResult(
            total = total,
            onDevice = onDevice,
            alreadyRight = right,
            fixed = fixed,
            merged = merged,
            notMatched = notMatched.toList(),
            unchecked = unchecked,
            stopped = stopped,
        )
    }

    private companion object {
        /** Rows looked up at once; each is one to a few searches. */
        const val PARALLEL_ROWS = 4
    }
}
