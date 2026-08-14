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
 * playing track, gathers candidates, ranks them against the listener's
 * recommendation weights, and appends batches through [QueueManager]. Refills
 * as the listener nears the queue tail.
 *
 * Ranking is on-device, in [LocalRadioPlanner], and always runs. The remote
 * Tryptify-Playlist planner is one optional *source* of candidates among
 * several — it is good at naming tracks the catalog alone wouldn't surface —
 * but it is not required for the weights to work, and radio is fully
 * functional with it switched off. When it is configured its `source_boosts`
 * additionally scale whole candidate sources during ranking.
 *
 * Resolution is Qobuz-first by design — `searchQobuz` registers every
 * returned id in [QobuzIdRegistry], so appended tracks play through the
 * QobuzCached path on the configured instance. Candidates are always
 * validated against the catalog, whatever suggested them. TIDAL is used only
 * as a last resort when no Qobuz instance is configured at all. This class
 * never mutates ExoPlayer; all queue changes flow through [QueueManager].
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

    /** Ranks every batch against the user's weights, planner or no planner. */
    private val localPlanner = LocalRadioPlanner()

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

    /**
     * Starts radio. With no argument the currently playing track seeds the
     * station; passing [seed] seeds from that specific track (queue radio on
     * any queue row). An explicit seed while radio is already running
     * re-seeds the station in place — the session dedupe survives so tracks
     * suggested for the old seed don't repeat under the new one.
     */
    fun startRadio(seed: Track? = null) {
        val seedTrack = seed ?: queueManager.currentTrack.value ?: return
        if (_isActive.value) {
            if (seed == null) return
            // Re-seed: drop the in-flight generation, keep the dedupe state.
            generationJob?.cancel()
            _isGenerating.value = false
        } else {
            seenTrackIds.clear()
            seenTitleKeys.clear()
            // Never re-suggest anything already in the queue or the seed itself.
            queueManager.currentQueue.forEach { remember(it) }
        }
        consecutiveEmptyBatches = 0
        _isActive.value = true
        _statusMessage.value = null
        generate(seedTrack)
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
            plan == null -> null
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
                    async {
                        search(query).take(5).map {
                            RadioCandidate(it, CandidateOrigin.SEARCH, fromQobuz = qobuzConfigured)
                        }
                    }
                }
            // The on-device backbone. Radio runs on this alone when no planner
            // is configured — Qobuz: the seed artist's top tracks plus
            // similar-artist expansion; TIDAL (unconfigured Qobuz only):
            // catalog track radio.
            val backbone = async {
                if (qobuzConfigured) {
                    qobuzBackbone(seed)
                } else {
                    repository.getRecommendations(seed.id).getOrDefault(emptyList()).map {
                        RadioCandidate(it, CandidateOrigin.SEARCH, fromQobuz = false)
                    }
                }
            }
            val hints = hintResults.mapNotNull { it.await() }.map {
                RadioCandidate(it, CandidateOrigin.PLANNER_HINT, fromQobuz = qobuzConfigured)
            }
            hints + backbone.await() + queryResults.flatMap { it.await() }
        }

        // Rank on-device against the user's weights. This is what makes those
        // sliders mean something with no planner configured — previously the
        // order was just hints, then backbone, then queries, and the weights
        // were only ever read by the remote service.
        val libraryKeys = libraryKeys()
        val ranked = localPlanner.rank(
            candidates = candidates.map { it.copy(inLibrary = titleKey(it.track) in libraryKeys) },
            context = RadioTasteContext(
                seed = seed,
                historyKeys = history.map { titleKey(it) },
                libraryKeys = libraryKeys,
                // Previously decoded and thrown away; now it scales whole
                // sources when a planner has an opinion about them.
                sourceBoosts = plan?.sourceBoosts.orEmpty(),
            ),
            weights = preferences.radioPlannerWeights.first(),
        )

        // History is no longer filtered out outright — "avoid recently played"
        // is a weight, and a hard filter is the one setting of it the user
        // cannot change. It is a strong penalty instead, so recent tracks sink
        // to the bottom of the pool and only resurface when the alternative is
        // an empty batch, which is what used to stop the station dead.
        return ranked
            .asSequence()
            .map { it.candidate.track }
            .filter { it.id != seed.id }
            .filter { it.id !in seenTrackIds }
            .filter { titleKey(it) !in seenTitleKeys }
            .distinctBy { it.id }
            .distinctBy { titleKey(it) }
            .take(BATCH_SIZE)
            .toList()
    }

    /**
     * Songs the listener already holds on-device — favourites and completed
     * downloads — as dedupe keys, for the "local library" weight.
     */
    private suspend fun libraryKeys(): Set<String> = runCatching {
        val favorites = libraryRepository.getFavoriteTracks().first().map { titleKey(it) }
        val downloads = libraryRepository.getDownloadedTracks().first()
            .map { keyOf(it.artistName, it.title) }
        (favorites + downloads).toSet()
    }.getOrDefault(emptySet())

    /**
     * Qobuz on-device backbone: the seed artist's top tracks plus the top
     * tracks of a few similar artists (both from the trypt-hifi
     * /api/get-artist endpoint, which registers every id for QobuzCached
     * playback), padded with a plain artist search when the seed's Qobuz
     * artist id isn't known.
     */
    private suspend fun qobuzBackbone(seed: Track): List<RadioCandidate> = coroutineScope {
        val artistId = seedQobuzArtistId(seed)
        val detail = artistId?.let { repository.getQobuzArtist(it).getOrNull() }
        // Position in the similar-artist list IS the distance from the seed —
        // the signal both "artist similarity" and "discovery distance" score
        // against — so it is captured here rather than lost in a flat list.
        val similarTops = detail?.similarArtists.orEmpty()
            .take(MAX_SIMILAR_ARTISTS)
            .mapIndexed { index, artist ->
                async {
                    repository.getQobuzArtist(artist.id).getOrNull()
                        ?.topTracks.orEmpty().take(TOP_TRACKS_PER_ARTIST)
                        .map {
                            RadioCandidate(
                                it,
                                CandidateOrigin.SIMILAR_ARTIST,
                                artistDistance = index + 1,
                            )
                        }
                }
            }
        val artistSearch = async {
            val artistName = seed.displayArtist
            if (artistName.isBlank()) {
                emptyList()
            } else {
                searchQobuzTracks(artistName).take(TOP_TRACKS_PER_ARTIST).map {
                    RadioCandidate(it, CandidateOrigin.SEARCH, artistDistance = 0)
                }
            }
        }
        detail?.topTracks.orEmpty().take(TOP_TRACKS_PER_ARTIST)
            .map { RadioCandidate(it, CandidateOrigin.SEED_ARTIST, artistDistance = 0) } +
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

    private fun titleKey(track: Track): String = keyOf(track.displayArtist, track.title)

    private fun keyOf(artist: String, title: String): String =
        "$artist|$title".lowercase().filter { it.isLetterOrDigit() || it == '|' }

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
