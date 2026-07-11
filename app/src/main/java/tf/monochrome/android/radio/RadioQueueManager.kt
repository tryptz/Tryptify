package tf.monochrome.android.radio

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.player.QueueManager
import tf.monochrome.android.player.UnifiedTrackRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The radio "queue maker" for the Qobuz (trypt-hifi) catalog: seeds from the
 * playing track, asks the Tryptify-Playlist planner for query/candidate
 * hints, resolves everything against the configured Qobuz instance, and
 * appends batches through [QueueManager]. Refills as the listener nears the
 * queue tail.
 *
 * Resolution is Qobuz-first by design — `searchQobuz` registers every
 * returned id in [QobuzIdRegistry], so appended tracks play through the
 * QobuzCached path on the configured instance. The planner is advisory only:
 * candidates are always validated against the catalog, and when the planner
 * is unconfigured or down the station keeps running on Qobuz artist
 * top-tracks and similar-artist expansion. TIDAL is used only as a last
 * resort when no Qobuz instance is configured at all. This class never
 * mutates ExoPlayer; all queue changes flow through [QueueManager].
 */
@Singleton
class RadioQueueManager @Inject constructor(
    private val queueManager: QueueManager,
    private val plannerClient: RadioPlannerClient,
    private val repository: MusicRepository,
    private val libraryRepository: LibraryRepository,
    private val preferences: PreferencesManager,
    private val unifiedTrackRegistry: UnifiedTrackRegistry,
    private val qobuzIdRegistry: QobuzIdRegistry,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // Human-readable note about the last generation ("Planner offline — using
    // similar artists", etc.) surfaced in the queue sheet.
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // Duplicate blockers: every track id ever enqueued this radio session plus
    // normalized artist|title keys, so the same song from a different release
    // (remaster, single vs album) doesn't come back around.
    private val seenTrackIds = mutableSetOf<Long>()
    private val seenTitleKeys = mutableSetOf<String>()

    private var generationJob: Job? = null
    private var consecutiveEmptyBatches = 0

    init {
        // Refill near the tail. Only reacts while radio is active; manual
        // reorder/delete keeps radio running (per the interaction rules), and a
        // full reset goes through stopRadio() before the queue shrinks.
        scope.launch {
            combine(queueManager.queue, queueManager.currentIndex) { queue, index ->
                queue.size - 1 - index
            }.collect { remaining ->
                if (_isActive.value && !_isGenerating.value && remaining in 0..REFILL_THRESHOLD) {
                    queueManager.currentTrack.value?.let { generate(it) }
                }
            }
        }
    }

    /** Starts radio seeded from the currently playing track. */
    fun startRadio() {
        val seed = queueManager.currentTrack.value ?: return
        if (_isActive.value) return
        seenTrackIds.clear()
        seenTitleKeys.clear()
        consecutiveEmptyBatches = 0
        // Never re-suggest anything already in the queue or the seed itself.
        queueManager.currentQueue.forEach { remember(it) }
        _isActive.value = true
        _statusMessage.value = null
        generate(seed)
    }

    fun stopRadio() {
        _isActive.value = false
        _isGenerating.value = false
        _statusMessage.value = null
        generationJob?.cancel()
        generationJob = null
    }

    /**
     * Queue reset means the user rejected the generated tail — stop radio so
     * it doesn't instantly refill and make reset feel broken.
     */
    fun onQueueReset() {
        if (_isActive.value) stopRadio()
    }

    private fun generate(seed: Track) {
        if (_isGenerating.value) return
        _isGenerating.value = true
        generationJob = scope.launch {
            try {
                val batch = withContext(Dispatchers.IO) { buildBatch(seed) }
                if (batch.isEmpty()) {
                    consecutiveEmptyBatches++
                    if (consecutiveEmptyBatches >= MAX_EMPTY_BATCHES) {
                        _statusMessage.value = "Radio stopped — no more similar tracks found"
                        stopRadio()
                    }
                } else {
                    consecutiveEmptyBatches = 0
                    batch.forEach { remember(it) }
                    queueManager.addToQueue(batch)
                }
            } catch (e: Exception) {
                Log.w(TAG, "radio generation failed", e)
                _statusMessage.value = "Radio couldn't fetch tracks — will retry"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private suspend fun buildBatch(seed: Track): List<Track> {
        val history = runCatching { libraryRepository.getHistory().first() }
            .getOrDefault(emptyList())
            .take(HISTORY_CONTEXT_SIZE)

        val qobuzConfigured =
            preferences.qobuzInstanceUrl.first()?.isNotBlank() == true

        val plan = requestPlan(seed, history)
        _statusMessage.value = when {
            !qobuzConfigured -> "No Qobuz instance set — using TIDAL fallback"
            plan == null -> "Using Qobuz similar-artist recommendations"
            plan.fallbackReason != null -> "Planner fallback: ${plan.fallbackReason}"
            else -> null
        }

        val search: suspend (String) -> List<Track> =
            if (qobuzConfigured) ::searchQobuzTracks else ::searchTidalTracks

        val candidates = coroutineScope {
            // Planner hints resolved by search — bounded so a misbehaving
            // response can't fan out into dozens of requests.
            val hintResults = (plan?.candidateHints.orEmpty())
                .filter { !it.title.isNullOrBlank() }
                .take(MAX_HINTS)
                .map { hint ->
                    async { resolveHint(hint, search) }
                }
            val queryResults = (plan?.queries.orEmpty())
                .filter { it.isNotBlank() }
                .take(MAX_QUERIES)
                .map { query ->
                    async { search(query).take(5) }
                }
            // Deterministic backbone so radio works with the planner disabled
            // and pads out thin planner batches. Qobuz: seed artist's top
            // tracks + similar-artist expansion. TIDAL (unconfigured Qobuz
            // only): catalog track radio.
            val backbone = async {
                if (qobuzConfigured) qobuzBackbone(seed)
                else repository.getRecommendations(seed.id).getOrDefault(emptyList())
            }
            val hints = hintResults.mapNotNull { it.await() }
            val queries = queryResults.flatMap { it.await() }
            // Hints are the planner's strongest signal; the backbone next;
            // broad query results last.
            hints + backbone.await() + queries
        }

        val historyIds = history.map { it.id }.toSet()
        val historyKeys = history.map { titleKey(it) }.toSet()
        return candidates
            .asSequence()
            .filter { it.id != seed.id }
            .filter { it.id !in seenTrackIds && it.id !in historyIds }
            .filter { titleKey(it) !in seenTitleKeys && titleKey(it) !in historyKeys }
            .distinctBy { it.id }
            .distinctBy { titleKey(it) }
            .take(BATCH_SIZE)
            .toList()
    }

    /**
     * Qobuz on-device backbone: the seed artist's top tracks plus the top
     * tracks of a few similar artists (both from the trypt-hifi
     * /api/get-artist endpoint, which registers every id for QobuzCached
     * playback), padded with a plain artist search when the seed's Qobuz
     * artist id isn't known.
     */
    private suspend fun qobuzBackbone(seed: Track): List<Track> = coroutineScope {
        val artistId = seedQobuzArtistId(seed)
        val detail = artistId?.let { repository.getQobuzArtist(it).getOrNull() }
        val similarTops = detail?.similarArtists.orEmpty()
            .take(MAX_SIMILAR_ARTISTS)
            .map { artist ->
                async {
                    repository.getQobuzArtist(artist.id).getOrNull()
                        ?.topTracks.orEmpty().take(TOP_TRACKS_PER_ARTIST)
                }
            }
        val artistSearch = async {
            val artistName = seed.displayArtist
            if (artistName.isBlank()) emptyList()
            else searchQobuzTracks(artistName).take(TOP_TRACKS_PER_ARTIST)
        }
        detail?.topTracks.orEmpty().take(TOP_TRACKS_PER_ARTIST) +
            similarTops.flatMap { it.await() } +
            artistSearch.await()
    }

    /**
     * The Qobuz artist id for the seed's primary artist. Direct when the seed
     * came from Qobuz search/album/artist; via the TIDAL→Qobuz alias map when
     * the playback fallback established one; null otherwise.
     */
    private fun seedQobuzArtistId(seed: Track): Long? {
        val id = seed.artist?.id ?: seed.artists.firstOrNull()?.id ?: return null
        return when {
            qobuzIdRegistry.isQobuzArtist(id) -> id
            else -> qobuzIdRegistry.qobuzArtistIdFor(id)
        }
    }

    private suspend fun searchQobuzTracks(query: String): List<Track> =
        repository.searchQobuz(query).getOrDefault(
            tf.monochrome.android.domain.model.SearchResult()
        ).tracks

    private suspend fun searchTidalTracks(query: String): List<Track> =
        repository.searchTracks(query, limit = 5).getOrDefault(emptyList())

    private suspend fun requestPlan(seed: Track, history: List<Track>): RadioPlanResponse? {
        if (!plannerClient.isConfigured()) return null
        val weights = preferences.radioPlannerWeights.first()
        val request = RadioPlanRequest(
            seed = seedText(seed),
            history = history.map { PlannerHistoryItem(title = it.title, artist = it.displayArtist) },
            weights = weights,
            metabrainz = PlannerMetaBrainzContext(
                seedIdentities = listOf(identityFor(seed)),
                historyIdentities = history.take(IDENTITY_CONTEXT_SIZE).map { identityFor(it) },
            ),
        )
        return plannerClient.plan(request)
    }

    private fun seedText(seed: Track): String = buildString {
        append(seed.title)
        val artist = seed.displayArtist
        if (artist.isNotBlank()) append(" by ").append(artist)
        seed.album?.title?.takeIf { it.isNotBlank() }?.let { append(" (album: ").append(it).append(")") }
    }

    private fun identityFor(track: Track): PlannerTrackIdentity {
        // Local/Qobuz tracks promoted to UnifiedTrack carry ISRC / MusicBrainz
        // ids from file tags — the strongest identity MetaBrainz can match on.
        val unified = unifiedTrackRegistry[track.id]
        return PlannerTrackIdentity(
            title = track.title,
            artist = track.displayArtist,
            album = track.album?.title,
            isrc = unified?.isrc,
            musicBrainzRecordingId = unified?.musicBrainzTrackId,
        )
    }

    /** Search the catalog for a planner hint; require an artist match. */
    private suspend fun resolveHint(
        hint: PlannerCandidateHint,
        search: suspend (String) -> List<Track>,
    ): Track? {
        val title = hint.title ?: return null
        val artist = hint.artist.orEmpty()
        val query = if (artist.isBlank()) title else "$title $artist"
        val results = search(query).take(5)
        if (artist.isBlank()) return results.firstOrNull()
        return results.firstOrNull { candidate ->
            candidate.displayArtist.contains(artist, ignoreCase = true) ||
                artist.contains(candidate.displayArtist, ignoreCase = true)
        }
    }

    private fun remember(track: Track) {
        seenTrackIds += track.id
        seenTitleKeys += titleKey(track)
    }

    private fun titleKey(track: Track): String =
        "${track.displayArtist}|${track.title}".lowercase().filter { it.isLetterOrDigit() || it == '|' }

    companion object {
        private const val TAG = "RadioQueueManager"
        private const val BATCH_SIZE = 12
        private const val REFILL_THRESHOLD = 2
        private const val MAX_HINTS = 12
        private const val MAX_QUERIES = 6
        private const val MAX_SIMILAR_ARTISTS = 3
        private const val TOP_TRACKS_PER_ARTIST = 5
        private const val HISTORY_CONTEXT_SIZE = 30
        private const val IDENTITY_CONTEXT_SIZE = 10
        private const val MAX_EMPTY_BATCHES = 2
    }
}
