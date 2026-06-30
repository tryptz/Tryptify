package tf.monochrome.android.radio

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import tf.monochrome.android.data.local.repository.LocalMediaRepository
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.player.QueueManager
import tf.monochrome.android.player.UnifiedTrackRegistry
import tf.monochrome.android.radio.planner.PlannerCandidateSummary
import tf.monochrome.android.radio.planner.PlannerLocalMetadata
import tf.monochrome.android.radio.planner.PlannerMetaBrainzContext
import tf.monochrome.android.radio.planner.PlannerQobuzContext
import tf.monochrome.android.radio.planner.PlannerSessionHistory
import tf.monochrome.android.radio.planner.PlannerSettings
import tf.monochrome.android.radio.planner.PlannerSpotifyContext
import tf.monochrome.android.radio.planner.PlannerTrackIdentity
import tf.monochrome.android.radio.planner.PlannerTrackMetadata
import tf.monochrome.android.radio.planner.RadioPlan
import tf.monochrome.android.radio.planner.RadioPlannerClient
import tf.monochrome.android.radio.planner.RadioPlannerRequest
import tf.monochrome.android.spotify.api.model.SpotifyTrack
import tf.monochrome.android.spotify.repository.SpotifyRepository
import javax.inject.Inject
import javax.inject.Singleton

sealed class RadioSeed {
    data object FromCurrentTrack : RadioSeed()
    data object FromListeningSession : RadioSeed()
    data class FromTrack(val track: Track) : RadioSeed()
}

data class BuiltRadioSeed(
    val title: String,
    val seedArtistIds: List<String>,
    val genres: List<String>,
    val seedSpotifyIds: Set<String>,
    val seedKeys: Set<String>,
    val localCandidates: List<UnifiedTrack>,
    val candidatePool: List<SpotifyTrack>,
    val recentlyPlayedSpotifyIds: Set<String> = emptySet(),
    val topTrackSpotifyIds: Set<String> = emptySet(),
    val plannerPlan: RadioPlan? = null,
)

@Singleton
class RadioSeedBuilder @Inject constructor(
    private val queueManager: QueueManager,
    private val spotifyRepository: SpotifyRepository,
    private val preferences: PreferencesManager,
    private val localMediaRepository: LocalMediaRepository,
    private val plannerClient: RadioPlannerClient,
    private val unifiedTrackRegistry: UnifiedTrackRegistry,
) {
    suspend fun build(seed: RadioSeed): BuiltRadioSeed {
        val localSeeds = when (seed) {
            RadioSeed.FromCurrentTrack -> listOfNotNull(queueManager.currentTrack.value)
            is RadioSeed.FromTrack -> listOf(seed.track)
            RadioSeed.FromListeningSession -> sessionTracks()
        }
        val title = seedTitle(seed, localSeeds)
        val seedKeys = localSeeds.map { radioKey(it.title, it.primaryArtistName(), null) }.toSet()
        val llmPlannerEnabled = preferences.llmPlaylistRadioRecommendationsEnabled.first()
        val plan = if (llmPlannerEnabled) {
            requestPlannerPlan(seed, localSeeds, title)
        } else {
            null
        }
        plan?.let {
            Log.i(TAG, "planner queries=${it.queryMix.allQueries.size} confidence=${it.safety.confidence}")
        }
        val localCandidates = buildLocalCandidates(localSeeds, seedKeys, plan)

        val spotifySeeds = mutableListOf<SpotifyTrack>()
        if (seed == RadioSeed.FromListeningSession && preferences.spotifySyncCurrentPlaying.first()) {
            spotifyRepository.getCurrentlyPlaying()?.let { spotifySeeds += it }
        }
        localSeeds.mapNotNullTo(spotifySeeds) { track ->
            val artist = track.primaryArtistName() ?: return@mapNotNullTo null
            spotifyRepository.searchTrack(artist = artist, title = track.title)
        }

        val seedTracks = spotifySeeds.filter { it.isUsable }.distinctBy { it.stableId }.take(MAX_SEED_TRACKS)
        Log.i(TAG, "seed tracks=${seedTracks.size} ${seedTracks.joinToString { it.name }}")

        val seedArtistIds = seedTracks.mapNotNull { it.primaryArtistId }.distinct().take(MAX_SEED_ARTISTS)
        Log.i(TAG, "seed artistIds=${seedArtistIds.size} $seedArtistIds")

        val genres = coroutineScope {
            seedArtistIds.map { async { spotifyRepository.getArtist(it) } }.awaitAll()
        }.filterNotNull().flatMap { it.genres }.distinct().take(MAX_GENRES)
        Log.i(TAG, "genres=${genres.size} $genres")

        val recentTracks = spotifyRepository.getRecentlyPlayed()
        val topTracks = spotifyRepository.getTopTracks()
        val savedTracks = spotifyRepository.getSavedTracks()
        val playlistTracks = spotifyRepository.getOwnedOrCollaborativePlaylistTracks()
        val searchTracks = searchFallbackTracks(seedTracks, localSeeds, genres, plan)

        val seedIds = seedTracks.map { it.stableId }.filter { it.isNotBlank() }.toSet()
        val alreadyHeardSpotifyIds = seedIds +
            recentTracks.map { it.stableId }.filter { it.isNotBlank() } +
            topTracks.map { it.stableId }.filter { it.isNotBlank() }
        val pool = (searchTracks.shuffled() + playlistTracks.shuffled() + savedTracks.shuffled())
            .filter { it.isUsable }
            .distinctBy { it.stableId }
            .filter { it.stableId !in alreadyHeardSpotifyIds }
            .take(INITIAL_POOL_SIZE)
        Log.i(TAG, "candidate pool=${pool.size} discovery=${searchTracks.size} saved=${savedTracks.size} playlists=${playlistTracks.size}")

        require(seedTracks.isNotEmpty() || localCandidates.isNotEmpty() || pool.isNotEmpty()) {
            "No Spotify seed found for this track"
        }

        return BuiltRadioSeed(
            title = title,
            seedArtistIds = seedArtistIds,
            genres = genres,
            seedSpotifyIds = seedIds,
            seedKeys = seedKeys + seedTracks.map { radioKey(it.name, it.primaryArtistName, it.externalIds?.isrc) },
            localCandidates = localCandidates,
            candidatePool = pool,
            recentlyPlayedSpotifyIds = recentTracks.map { it.stableId }.filter { it.isNotBlank() }.toSet(),
            topTrackSpotifyIds = topTracks.map { it.stableId }.filter { it.isNotBlank() }.toSet(),
            plannerPlan = plan,
        )
    }

    suspend fun expand(seed: BuiltRadioSeed, exclude: Set<String>): List<SpotifyTrack> {
        val queries = (plannerExpansionQueries(seed.plannerPlan) + seed.genres + seed.seedArtistIds + seed.title)
            .cleanQueries(EXPAND_QUERIES)
            .shuffled()
        if (queries.isEmpty()) return emptyList()
        val tracks = coroutineScope {
            queries.map { query -> async { spotifyRepository.searchTracks(query, EXPAND_SEARCH_TARGET) } }.awaitAll()
        }.flatten()
        return tracks.filter { it.isUsable }
            .distinctBy { it.stableId }
            .filter { it.stableId !in exclude }
            .shuffled()
    }

    private suspend fun requestPlannerPlan(
        seed: RadioSeed,
        localSeeds: List<Track>,
        title: String,
    ): RadioPlan? {
        val weights = preferences.radioPlannerWeights.first()
        return plannerClient.plan(
            RadioPlannerRequest(
                seed = title.takeIf { it.isNotBlank() },
                localMetadata = PlannerLocalMetadata(
                    seedTracks = localSeeds.map { it.toPlannerMetadata("local_seed") },
                ),
                spotifyContext = PlannerSpotifyContext(),
                qobuzContext = PlannerQobuzContext(preferred = true),
                settings = PlannerSettings(
                    targetTrackCount = INITIAL_POOL_SIZE,
                    discoveryRatio = DISCOVERY_RATIO,
                    familiarityRatio = FAMILIARITY_RATIO,
                ),
                weights = weights,
                sliders = weights.toPlannerSliders(),
                sessionHistory = PlannerSessionHistory(
                    tracks = queueManager.playHistory.value.takeLast(8).map { it.toPlannerMetadata("history") },
                ),
                candidateSummary = PlannerCandidateSummary(
                    localCandidateCount = localSeeds.size,
                    targetTrackCount = INITIAL_POOL_SIZE,
                ),
                metabrainz = buildMetaBrainzContext(localSeeds),
            ),
        ).also { plan ->
            if (plan?.safety?.needsFallback == true) {
                Log.i(TAG, "planner fallback: ${plan.safety.fallbackReason.orEmpty()}")
            }
            if (seed == RadioSeed.FromListeningSession && plan == null) {
                Log.d(TAG, "planner unavailable for listening-session radio")
            }
        }
    }

    private suspend fun buildLocalCandidates(
        seeds: List<Track>,
        seedKeys: Set<String>,
        plan: RadioPlan?,
    ): List<UnifiedTrack> {
        if (seeds.isEmpty() && plan == null) return emptyList()
        val seedQueries = seeds.flatMap { seed ->
            listOfNotNull(seed.primaryArtistName(), seed.title, seed.album?.title)
        }
        val queries = (seedQueries + plannerLocalQueries(plan))
            .cleanQueries(LOCAL_SEARCH_QUERIES)
        if (queries.isEmpty()) return emptyList()
        val tracks = coroutineScope {
            queries.map { query -> async { localMediaRepository.searchTracks(query).first() } }.awaitAll()
        }.flatten()
        return tracks
            .distinctBy { radioKey(it.title, it.artistName, it.isrc) }
            .filter { radioKey(it.title, it.artistName, it.isrc) !in seedKeys }
            .take(LOCAL_CANDIDATE_POOL)
    }

    private suspend fun searchFallbackTracks(
        spotifySeeds: List<SpotifyTrack>,
        localSeeds: List<Track>,
        genres: List<String>,
        plan: RadioPlan?,
    ): List<SpotifyTrack> {
        val queries = buildList {
            addAll(plannerSpotifyQueries(plan))
            addAll(genres)
            addAll(spotifySeeds.map { it.primaryArtistName })
            addAll(localSeeds.mapNotNull { it.primaryArtistName() })
            addAll(spotifySeeds.map { it.name })
        }.cleanQueries(SEARCH_FALLBACK_QUERIES)
        if (queries.isEmpty()) return emptyList()
        return coroutineScope {
            queries.map { query -> async { spotifyRepository.searchTracks(query, SEARCH_FALLBACK_TARGET) } }.awaitAll()
        }.flatten()
    }

    private fun plannerLocalQueries(plan: RadioPlan?): List<String> {
        val queryMix = plan?.queryMix ?: return emptyList()
        return queryMix.localLibraryQueries + queryMix.exactQueries + queryMix.artistQueries + queryMix.albumQueries
    }

    private fun plannerSpotifyQueries(plan: RadioPlan?): List<String> {
        val queryMix = plan?.queryMix ?: return emptyList()
        return queryMix.spotifyQueries + queryMix.exactQueries + queryMix.artistQueries + queryMix.genreQueries + queryMix.qobuzQueries
    }

    private fun plannerExpansionQueries(plan: RadioPlan?): List<String> {
        val queryMix = plan?.queryMix ?: return emptyList()
        return queryMix.spotifyQueries + queryMix.genreQueries + queryMix.moodQueries + queryMix.artistQueries
    }

    private fun sessionTracks(): List<Track> {
        val history = queueManager.playHistory.value.takeLast(5)
        if (history.isNotEmpty()) return history
        val index = queueManager.currentQueueIndex
        return if (index > 0) queueManager.currentQueue.take(index).takeLast(5) else emptyList()
    }

    private fun seedTitle(seed: RadioSeed, localSeeds: List<Track>): String = when (seed) {
        RadioSeed.FromListeningSession -> "listening session"
        RadioSeed.FromCurrentTrack -> localSeeds.firstOrNull()?.title.orEmpty()
        is RadioSeed.FromTrack -> seed.track.title
    }

    private fun buildMetaBrainzContext(localSeeds: List<Track>): PlannerMetaBrainzContext {
        val historyTracks = queueManager.playHistory.value.takeLast(HISTORY_IDENTITY_LIMIT)
        val localQueueTracks = queueManager.currentQueue
            .asSequence()
            .mapNotNull { track -> unifiedTrackRegistry[track.id] }
            .filter { track ->
                track.sourceType == SourceType.LOCAL ||
                    track.sourceType == SourceType.COLLECTION ||
                    !track.isrc.isNullOrBlank() ||
                    !track.musicBrainzTrackId.isNullOrBlank()
            }
            .mapNotNull { it.toPlannerIdentity() }
            .distinctBy { it.identityKey() }
            .take(LOCAL_IDENTITY_LIMIT)
            .toList()

        return PlannerMetaBrainzContext(
            seedIdentities = localSeeds.mapNotNull { it.toPlannerIdentity() }
                .distinctBy { it.identityKey() }
                .take(SEED_IDENTITY_LIMIT),
            localIdentities = localQueueTracks,
            historyIdentities = historyTracks.mapNotNull { it.toPlannerIdentity() }
                .distinctBy { it.identityKey() }
                .take(HISTORY_IDENTITY_LIMIT),
        )
    }

    private fun Track.toPlannerMetadata(source: String): PlannerTrackMetadata {
        val unified = unifiedTrackRegistry[id]
        return PlannerTrackMetadata(
            title = title,
            artistName = primaryArtistName(),
            albumTitle = album?.title,
            isrc = unified?.isrc?.cleanOrNull(),
            source = source,
        )
    }

    private fun Track.toPlannerIdentity(): PlannerTrackIdentity? {
        val unified = unifiedTrackRegistry[id]
        return PlannerTrackIdentity(
            title = title.cleanOrNull() ?: return null,
            artist = primaryArtistName()?.cleanOrNull() ?: return null,
            album = album?.title.cleanOrNull(),
            isrc = unified?.isrc.cleanOrNull(),
            musicBrainzRecordingId = unified?.musicBrainzTrackId.cleanOrNull(),
            musicBrainzReleaseId = null,
            musicBrainzArtistIds = emptyList(),
        )
    }

    private fun UnifiedTrack.toPlannerIdentity(): PlannerTrackIdentity? {
        val cleanTitle = title.cleanOrNull() ?: return null
        val cleanArtist = artistName.cleanOrNull() ?: return null
        return PlannerTrackIdentity(
            title = cleanTitle,
            artist = cleanArtist,
            album = albumTitle.cleanOrNull(),
            isrc = isrc.cleanOrNull(),
            musicBrainzRecordingId = musicBrainzTrackId.cleanOrNull(),
            musicBrainzReleaseId = null,
            musicBrainzArtistIds = emptyList(),
        )
    }

    private fun PlannerTrackIdentity.identityKey(): String =
        musicBrainzRecordingId
            ?: isrc
            ?: listOf(title, artist, album.orEmpty())
                .joinToString("|") { it.trim().lowercase() }

    private fun String?.cleanOrNull(): String? =
        this?.trim()?.takeIf { it.isNotBlank() }

    private fun Track.primaryArtistName(): String? =
        (artist?.name ?: artists.firstOrNull()?.name ?: displayArtist)
            .takeIf { it.isNotBlank() }

    private fun Iterable<String>.cleanQueries(limit: Int): List<String> =
        map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(limit)

    private companion object {
        const val TAG = "RadioSeedBuilder"
        const val MAX_SEED_TRACKS = 5
        const val MAX_SEED_ARTISTS = 3
        const val MAX_GENRES = 4
        const val INITIAL_POOL_SIZE = 240
        const val DISCOVERY_RATIO = 0.85f
        const val FAMILIARITY_RATIO = 0.10f
        const val EXPAND_QUERIES = 12
        const val EXPAND_SEARCH_TARGET = 40
        const val LOCAL_SEARCH_QUERIES = 16
        const val LOCAL_CANDIDATE_POOL = 50
        const val SEARCH_FALLBACK_QUERIES = 16
        const val SEARCH_FALLBACK_TARGET = 50
        const val SEED_IDENTITY_LIMIT = 8
        const val LOCAL_IDENTITY_LIMIT = 32
        const val HISTORY_IDENTITY_LIMIT = 12
    }
}
