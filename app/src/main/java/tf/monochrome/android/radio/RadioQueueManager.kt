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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The radio "queue maker" for the Qobuz (trypt-hifi) catalog: seeds from the
 * playing track, gathers candidates, ranks them against the listener's
 * recommendation weights, and appends batches through [QueueManager]. Refills
 * as the listener nears the queue tail.
 *
 * Ranking is on-device, in [LocalRadioPlanner], and always runs. A remote
 * Tryptify-Playlist planner used to supply extra candidates alongside the
 * catalog; it has been removed, and radio worked without it before it went.
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
    private val repository: MusicRepository,
    private val libraryRepository: LibraryRepository,
    private val preferences: PreferencesManager,
    private val qobuzIdRegistry: QobuzIdRegistry,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Ranks every batch against the user's weights. */
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

        _statusMessage.value =
            if (!qobuzConfigured) "No Qobuz instance set — using TIDAL fallback" else null

        // Qobuz: the seed artist's top tracks plus similar-artist expansion.
        // TIDAL (unconfigured Qobuz only): catalog track radio.
        val candidates = if (qobuzConfigured) {
            qobuzBackbone(seed)
        } else {
            repository.getRecommendations(seed.id).getOrDefault(emptyList()).map {
                RadioCandidate(it, CandidateOrigin.SEARCH, fromQobuz = false)
            }
        }

        // Rank on-device against the user's weights. This is what makes those
        // sliders mean something — the catalog hands back its own order, and
        // for a while the weights were only ever read by a remote service.
        val libraryKeys = libraryKeys()
        val ranked = localPlanner.rank(
            candidates = candidates.map { it.copy(inLibrary = titleKey(it.track) in libraryKeys) },
            context = RadioTasteContext(
                seed = seed,
                historyKeys = history.map { titleKey(it) },
                libraryKeys = libraryKeys,
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
        private const val MAX_SIMILAR_ARTISTS = 3
        private const val TOP_TRACKS_PER_ARTIST = 5
        private const val HISTORY_CONTEXT_SIZE = 30
        private const val MAX_EMPTY_BATCHES = 2
    }
}
