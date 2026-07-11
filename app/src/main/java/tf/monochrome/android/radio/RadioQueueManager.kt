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
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.player.QueueManager
import tf.monochrome.android.player.UnifiedTrackRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The radio "queue maker": seeds from the playing track, asks the optional
 * Tryptify-Playlist planner for query/candidate hints, resolves them to real
 * catalog tracks, and appends batches through [QueueManager]. Refills as the
 * listener nears the queue tail.
 *
 * The planner is advisory only — this class always validates candidates
 * against the catalog and falls back to on-device recommendations when the
 * planner is unconfigured, slow, or down. It never mutates ExoPlayer; all
 * queue changes flow through [QueueManager].
 */
@Singleton
class RadioQueueManager @Inject constructor(
    private val queueManager: QueueManager,
    private val plannerClient: RadioPlannerClient,
    private val repository: MusicRepository,
    private val libraryRepository: LibraryRepository,
    private val preferences: PreferencesManager,
    private val unifiedTrackRegistry: UnifiedTrackRegistry,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // Human-readable note about the last generation ("Planner offline — using
    // similar tracks", etc.) surfaced in the queue sheet.
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

        val plan = requestPlan(seed, history)
        _statusMessage.value = when {
            plan == null -> "Using on-device recommendations"
            plan.fallbackReason != null -> "Planner fallback: ${plan.fallbackReason}"
            else -> null
        }

        val candidates = coroutineScope {
            // Planner hints resolved by search — bounded so a misbehaving
            // response can't fan out into dozens of requests.
            val hintResults = (plan?.candidateHints.orEmpty())
                .filter { !it.title.isNullOrBlank() }
                .take(MAX_HINTS)
                .map { hint ->
                    async { resolveHint(hint) }
                }
            val queryResults = (plan?.queries.orEmpty())
                .filter { it.isNotBlank() }
                .take(MAX_QUERIES)
                .map { query ->
                    async {
                        runCatching { repository.searchTracks(query, limit = 5).getOrThrow() }
                            .getOrDefault(emptyList())
                    }
                }
            // Deterministic backbone: catalog recommendations for the seed.
            // Always requested so radio works with the planner disabled and
            // pads out thin planner batches.
            val recommendations = async {
                repository.getRecommendations(seed.id).getOrDefault(emptyList())
            }
            val hints = hintResults.mapNotNull { it.await() }
            val queries = queryResults.flatMap { it.await() }
            // Hints are the planner's strongest signal; recommendations next;
            // broad query results last.
            hints + recommendations.await() + queries
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
    private suspend fun resolveHint(hint: PlannerCandidateHint): Track? {
        val title = hint.title ?: return null
        val artist = hint.artist.orEmpty()
        val query = if (artist.isBlank()) title else "$title $artist"
        val results = runCatching { repository.searchTracks(query, limit = 5).getOrThrow() }
            .getOrDefault(emptyList())
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
        private const val HISTORY_CONTEXT_SIZE = 30
        private const val IDENTITY_CONTEXT_SIZE = 10
        private const val MAX_EMPTY_BATCHES = 2
    }
}
