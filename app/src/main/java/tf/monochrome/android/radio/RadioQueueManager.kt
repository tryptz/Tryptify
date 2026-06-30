package tf.monochrome.android.radio

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.player.QueueManager
import tf.monochrome.android.spotify.api.model.SpotifyTrack
import tf.monochrome.android.spotify.repository.SpotifyRadioFailure
import tf.monochrome.android.spotify.repository.SpotifyRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class RadioState {
    data object Idle : RadioState()
    data object Loading : RadioState()
    data class Active(
        val seedTrackTitle: String,
        val queueSize: Int,
        val resolvedCount: Int,
        val skippedCount: Int,
    ) : RadioState()
    data class Error(val message: String) : RadioState()
}

sealed class RadioEvent {
    data class Snackbar(val message: String) : RadioEvent()
}

data class ScoredRadioCandidate(
    val unifiedTrack: UnifiedTrack,
    val legacyTrack: Track,
    val score: Float,
    val reason: String,
)

class RadioQueueManager(
    private val spotifyRepository: SpotifyRepository,
    private val seedBuilder: RadioSeedBuilder,
    private val trackResolver: TrackResolver,
    private val queueManager: QueueManager,
    private val libraryRepository: LibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()

    private val _radioState = MutableStateFlow<RadioState>(RadioState.Idle)
    val radioState: StateFlow<RadioState> = _radioState.asStateFlow()

    private val _events = MutableSharedFlow<RadioEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<RadioEvent> = _events.asSharedFlow()

    private var activeSeed: BuiltRadioSeed? = null
    private val seenSpotifyIds = LinkedHashSet<String>()
    private val seenRadioKeys = LinkedHashSet<String>()
    private val failedRadioKeys = LinkedHashSet<String>()
    private val playedRadioKeys = LinkedHashSet<String>()
    private val candidateBuffer = ArrayDeque<SpotifyTrack>()
    private var resolvedCount = 0
    private var skippedCount = 0
    private var refillRunning = false

    init {
        scope.launch {
            combine(queueManager.queue, queueManager.currentIndex) { queue, index ->
                queue.size to index
            }.collect { (size, index) ->
                val state = _radioState.value
                if (state is RadioState.Active && index >= 0 && size - index - 1 <= TAIL_REFRESH_THRESHOLD) {
                    refillTail()
                }
            }
        }
        scope.launch {
            spotifyRepository.failures.collect { failure ->
                _events.tryEmit(RadioEvent.Snackbar(failure.toSnackbar()))
            }
        }
    }

    fun startRadio(seed: RadioSeed) {
        scope.launch {
            mutex.withLock {
                if (!spotifyRepository.isAuthenticated()) {
                    spotifyRepository.launchAuth()
                    _radioState.value = RadioState.Error("Spotify authorization is required")
                    _events.tryEmit(RadioEvent.Snackbar(SpotifyRadioFailure.NotConnected.toSnackbar()))
                    return@withLock
                }

                _events.tryEmit(RadioEvent.Snackbar("Radio started — finding similar tracks…"))
                _radioState.value = RadioState.Loading

                if (!spotifyRepository.hasUsableToken()) {
                    spotifyRepository.launchAuth()
                    _radioState.value = RadioState.Error("Spotify authorization is required")
                    _events.tryEmit(RadioEvent.Snackbar(SpotifyRadioFailure.ReauthRequired.toSnackbar()))
                    return@withLock
                }
                resolvedCount = 0
                skippedCount = 0
                seenSpotifyIds.clear()
                seenRadioKeys.clear()
                failedRadioKeys.clear()
                playedRadioKeys.clear()

                try {
                    refreshPlayedRadioKeys()
                    val builtSeed = seedBuilder.build(seed)
                    activeSeed = builtSeed
                    seenSpotifyIds += builtSeed.seedSpotifyIds
                    seenSpotifyIds += builtSeed.recentlyPlayedSpotifyIds
                    seenSpotifyIds += builtSeed.topTrackSpotifyIds
                    seenRadioKeys += builtSeed.seedKeys
                    candidateBuffer.clear()
                    candidateBuffer.addAll(builtSeed.candidatePool)
                    val added = appendRadioBatch(builtSeed, includeLocalCandidates = true)
                    if (added > 0 && shouldRefillTail()) {
                        refillTail()
                    }
                } catch (e: Exception) {
                    activeSeed = null
                    val message = e.message ?: "Radio could not start"
                    Log.w(TAG, "Radio start failed: $message")
                    _radioState.value = RadioState.Error(message)
                    _events.tryEmit(RadioEvent.Snackbar(message.toRadioStartSnackbar()))
                }
            }
        }
    }

    fun stopRadioIfActive() {
        scope.launch {
            mutex.withLock {
                if (_radioState.value !is RadioState.Active && _radioState.value !is RadioState.Loading) {
                    return@withLock
                }
                activeSeed = null
                candidateBuffer.clear()
                seenSpotifyIds.clear()
                seenRadioKeys.clear()
                failedRadioKeys.clear()
                playedRadioKeys.clear()
                resolvedCount = 0
                skippedCount = 0
                refillRunning = false
                _radioState.value = RadioState.Idle
                _events.tryEmit(RadioEvent.Snackbar("Radio stopped after queue reset."))
            }
        }
    }

    private fun refillTail() {
        if (refillRunning) return
        val seed = activeSeed ?: return
        scope.launch {
            mutex.withLock {
                if (refillRunning) return@withLock
                refillRunning = true
                var added = 0
                try {
                    if (shouldRefillTail()) {
                        added = appendRadioBatch(seed, includeLocalCandidates = false)
                    }
                } finally {
                    refillRunning = false
                }
                if (added > 0 && shouldRefillTail()) {
                    refillTail()
                }
            }
        }
    }

    private suspend fun appendRadioBatch(
        seed: BuiltRadioSeed,
        includeLocalCandidates: Boolean,
    ): Int {
        if (queueManager.shuffleEnabled.value) queueManager.toggleShuffle()
        queueManager.setRepeatMode(RepeatMode.OFF)
        refreshPlayedRadioKeys()

        val localScored = if (includeLocalCandidates) scoreLocalCandidates(seed) else emptyList()
        var requested = localScored.size
        var skippedTotal = 0
        val resolvedScored = mutableListOf<ScoredRadioCandidate>()
        resolvedScored += localScored

        for (attempt in 0 until MAX_RECOMMENDATION_PULLS) {
            val recommendations = nextCandidateBatch(seed, RADIO_BATCH_SIZE)
                .filter { candidate ->
                    val id = candidate.stableId
                    val key = radioKey(candidate.name, candidate.primaryArtistName, candidate.externalIds?.isrc)
                    id.isNotBlank() && seenSpotifyIds.add(id) && key !in failedRadioKeys && !isAlreadyKnown(key)
                }

            if (recommendations.isEmpty()) break
            requested += recommendations.size

            val results = coroutineScope {
                recommendations.map { track ->
                    async(Dispatchers.IO) { track to trackResolver.resolve(track) }
                }.awaitAll()
            }

            val resolved = results.mapNotNull { (candidate, result) ->
                when (result) {
                    is RadioTrackResult.Resolved -> scoreResolvedCandidate(seed, candidate, result)
                    is RadioTrackResult.Skipped -> {
                        skippedTotal++
                        skippedCount++
                        failedRadioKeys += radioKey(candidate.name, candidate.primaryArtistName, candidate.externalIds?.isrc)
                        Log.d("RadioSkipEvent", result.spotifyId + ": " + candidate.name + " - " + candidate.primaryArtistName + ": " + result.reason)
                        null
                    }
                }
            }
            resolvedScored += resolved

            val failedRatio = skippedTotal.toDouble() / requested.coerceAtLeast(1).toDouble()
            if (resolved.isNotEmpty() && failedRatio <= MAX_SILENT_SKIP_RATIO) break
            if (resolvedScored.size >= MIN_PLAYABLE_APPEND) break
        }

        val appendable = resolvedScored
            .distinctBy { keyOf(it.unifiedTrack) }
            .filterNot { isAlreadyKnown(keyOf(it.unifiedTrack)) }
            .sortedByDescending { it.score }

        appendable.forEach { seenRadioKeys += keyOf(it.unifiedTrack) }
        val toAppend = appendable.map { it.legacyTrack }
        if (toAppend.isNotEmpty()) {
            queueManager.addToQueue(toAppend)
        }

        resolvedCount += toAppend.size
        val appended = toAppend.size
        if (requested == 0 && resolvedCount == 0) {
            Log.w(TAG, "No similar tracks found for radio seed")
            _radioState.value = RadioState.Error("No similar tracks found")
            _events.tryEmit(RadioEvent.Snackbar("Couldn't find similar tracks for radio."))
            return 0
        }

        val failedRatio = if (requested > 0) skippedTotal.toDouble() / requested.toDouble() else 0.0
        if (failedRatio > MAX_SILENT_SKIP_RATIO) {
            _events.tryEmit(RadioEvent.Snackbar("Most Spotify recommendations were not playable here."))
        }

        _radioState.value = if (resolvedCount > 0) {
            RadioState.Active(
                seedTrackTitle = seed.title,
                queueSize = queueManager.currentQueue.size,
                resolvedCount = resolvedCount,
                skippedCount = skippedCount,
            )
        } else {
            Log.w(TAG, "No playable radio tracks found after $requested candidates")
            _events.tryEmit(RadioEvent.Snackbar(SpotifyRadioFailure.NoResolvableTracks.toSnackbar()))
            RadioState.Error("No playable radio tracks found")
        }
        return appended
    }

    private fun scoreLocalCandidates(seed: BuiltRadioSeed): List<ScoredRadioCandidate> =
        seed.localCandidates.mapNotNull { unified ->
            val key = keyOf(unified)
            if (isAlreadyKnown(key)) return@mapNotNull null
            val registered = trackResolver.registerUnified(unified)
            val legacy = registered.toLegacyTrack()
            ScoredRadioCandidate(
                unifiedTrack = registered,
                legacyTrack = legacy,
                score = 55f + plannerSourceBonus(seed, TrackSource.LOCAL) + seedBonus(seed, registered.artistName, registered.albumTitle) + noveltyPenalty(seed, key, registered.artistName, registered.albumTitle, null),
                reason = "local_library",
            )
        }

    private fun scoreResolvedCandidate(
        seed: BuiltRadioSeed,
        candidate: SpotifyTrack,
        result: RadioTrackResult.Resolved,
    ): ScoredRadioCandidate? {
        val key = keyOf(result.unifiedTrack)
        if (isAlreadyKnown(key)) return null
        val legacy = result.unifiedTrack.toLegacyTrack()
        return ScoredRadioCandidate(
            unifiedTrack = result.unifiedTrack,
            legacyTrack = legacy,
            score = result.score + plannerSourceBonus(seed, result.source) + seedBonus(seed, result.unifiedTrack.artistName, result.unifiedTrack.albumTitle) + noveltyPenalty(seed, key, result.unifiedTrack.artistName, result.unifiedTrack.albumTitle, candidate.stableId),
            reason = result.reason,
        )
    }

    private fun seedBonus(seed: BuiltRadioSeed, artist: String?, album: String?): Float {
        var score = 0f
        val normArtist = artist.orEmpty().trim().lowercase()
        if (normArtist.isNotBlank() && seed.seedKeys.any { it.contains(normArtist) }) score += 8f
        if (!album.isNullOrBlank() && queueManager.playHistory.value.any { sameText(it.album?.title, album) }) score -= 35f

        val hints = seed.plannerPlan?.scoringHints ?: return score
        if (hintMatches(normArtist, hints.boostArtists)) score += 18f
        if (hintMatches(normArtist, hints.avoidArtists)) score -= 35f
        val albumText = album.orEmpty().lowercase()
        if (hintMatches(albumText, hints.avoidKeywords)) score -= 12f
        return score
    }

    private fun plannerSourceBonus(seed: BuiltRadioSeed, source: TrackSource): Float {
        val weights = seed.plannerPlan?.sourceWeights ?: return 0f
        return when (source) {
            TrackSource.LOCAL -> weights.localLibrary
            TrackSource.QOBUZ -> weights.qobuzCatalog
        }.coerceIn(0f, 1f) * 15f
    }

    private fun hintMatches(value: String, hints: List<String>): Boolean {
        if (value.isBlank()) return false
        return hints.any { hint ->
            val normalized = hint.trim().lowercase()
            normalized.isNotBlank() && (value.contains(normalized) || normalized.contains(value))
        }
    }

    private fun noveltyPenalty(
        seed: BuiltRadioSeed,
        key: String,
        artist: String?,
        album: String?,
        spotifyId: String?,
    ): Float {
        var penalty = 0f
        if (key in playedRadioKeys || queueManager.playHistory.value.any { keyOf(it) == key }) penalty -= 90f
        if (spotifyId != null && spotifyId in seed.recentlyPlayedSpotifyIds) penalty -= 75f
        if (spotifyId != null && spotifyId in seed.topTrackSpotifyIds) penalty -= 60f
        if (!album.isNullOrBlank() && queueManager.playHistory.value.any { sameText(it.album?.title, album) }) penalty -= 35f
        val normArtist = artist.orEmpty().trim().lowercase()
        if (normArtist.isNotBlank() && queueManager.playHistory.value.takeLast(8).any { sameText(it.displayArtist, normArtist) }) penalty -= 20f
        return penalty
    }

    private suspend fun refreshPlayedRadioKeys() {
        val history = runCatching { libraryRepository.getHistory().first() }.getOrDefault(emptyList())
        playedRadioKeys.clear()
        playedRadioKeys += history.map { keyOf(it) }
        playedRadioKeys += queueManager.playHistory.value.map { keyOf(it) }
    }

    private fun sameText(left: String?, right: String?): Boolean =
        left.orEmpty().trim().lowercase() == right.orEmpty().trim().lowercase()

    private fun String.toRadioStartSnackbar(): String = when {
        contains("No Spotify seed", ignoreCase = true) -> "No Spotify seed found for this track."
        contains("authorization", ignoreCase = true) -> "Connect Spotify to start radio."
        else -> "Radio could not start."
    }

    private suspend fun nextCandidateBatch(seed: BuiltRadioSeed, size: Int): List<SpotifyTrack> {
        if (candidateBuffer.size < size) {
            val more = runCatching { seedBuilder.expand(seed, seenSpotifyIds) }.getOrDefault(emptyList())
            more.forEach { candidate ->
                val id = candidate.stableId
                val key = radioKey(candidate.name, candidate.primaryArtistName, candidate.externalIds?.isrc)
                if (id.isNotBlank() && id !in seenSpotifyIds && key !in failedRadioKeys && !isAlreadyKnown(key)) {
                    candidateBuffer.addLast(candidate)
                }
            }
        }
        val batch = ArrayList<SpotifyTrack>(size)
        while (batch.size < size && candidateBuffer.isNotEmpty()) {
            batch.add(candidateBuffer.removeFirst())
        }
        return batch
    }

    private fun shouldRefillTail(): Boolean {
        val index = queueManager.currentQueueIndex
        return index >= 0 && queueManager.currentQueue.size - index - 1 <= TAIL_REFRESH_THRESHOLD
    }

    private fun isAlreadyKnown(key: String): Boolean =
        key in seenRadioKeys ||
            key in playedRadioKeys ||
            queueManager.currentQueue.any { keyOf(it) == key } ||
            queueManager.playHistory.value.any { keyOf(it) == key }

    private fun keyOf(track: UnifiedTrack): String = radioKey(track.title, track.artistName, track.isrc)

    private fun keyOf(track: Track): String = radioKey(track.title, track.displayArtist, null)

    private fun SpotifyRadioFailure.toSnackbar(): String = when (this) {
        SpotifyRadioFailure.NotConnected -> "Connect Spotify to start radio."
        SpotifyRadioFailure.ReauthRequired -> "Spotify needs reconnecting."
        SpotifyRadioFailure.PremiumRequired -> "Spotify Premium is required for this integration."
        SpotifyRadioFailure.ScopeMissing -> "Spotify permission is missing. Reconnect Spotify."
        SpotifyRadioFailure.RateLimited -> "Spotify is rate limiting radio. Trying again shortly."
        SpotifyRadioFailure.EndpointUnavailable -> "Spotify endpoint unavailable; using local/Qobuz only."
        SpotifyRadioFailure.NoResolvableTracks -> "No matching local or Qobuz tracks found."
    }

    private companion object {
        const val TAG = "RadioQueue"
        const val RADIO_BATCH_SIZE = 36
        const val MAX_RECOMMENDATION_PULLS = 5
        const val MIN_PLAYABLE_APPEND = 12
        const val TAIL_REFRESH_THRESHOLD = 5
        const val MAX_SILENT_SKIP_RATIO = 0.60
    }
}
