package tf.monochrome.android.data.analysis

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.local.repository.LocalMediaRepository
import tf.monochrome.android.data.recommendations.SpotifyFeatureDb
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.domain.usecase.toUnifiedTrackAuto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the measured-feature store and the analysis worklist.
 *
 * "Every song in the user's library" = the local library plus the user's
 * streamed tracks they actually have a relationship with (favourites +
 * history) — NOT the entire streaming catalogue. Everything is deduplicated by
 * [normKey] so a song shared across sources is analysed once.
 */
@Singleton
class AudioFeatureRepository @Inject constructor(
    private val dao: AudioFeatureDao,
    private val localMedia: LocalMediaRepository,
    private val library: LibraryRepository,
    private val registry: QobuzIdRegistry,
) {
    fun observeAnalyzedCount(): Flow<Int> = dao.observeCount()

    suspend fun analyzedCount(): Int = dao.count()

    suspend fun featuresFor(track: UnifiedTrack): AudioFeatureEntity? = dao.get(normKeyOf(track))

    suspend fun featuresForKey(normKey: String): AudioFeatureEntity? = dao.get(normKey)

    suspend fun save(track: UnifiedTrack, f: MeasuredFeatures, analyzedAt: Long) {
        dao.upsert(
            AudioFeatureEntity(
                normKey = normKeyOf(track),
                trackId = track.id,
                title = track.title,
                artist = track.artistName,
                tempoBpm = f.tempoBpm,
                loudnessDb = f.loudnessDb,
                energy = f.energy,
                musicalKey = f.musicalKey,
                mode = f.mode,
                brightnessHz = f.brightnessHz,
                durationMs = f.durationMs,
                analyzedAt = analyzedAt,
                schemaVersion = AudioFeatureAnalyzer.SCHEMA_VERSION,
            )
        )
    }

    suspend fun clear() = dao.clear()

    /** Tracks in the user's library still missing a measured feature row. */
    suspend fun pendingTracks(): List<UnifiedTrack> {
        val local = runCatching { localMedia.getAllTracks().first() }.getOrDefault(emptyList())
        val favorites = runCatching { library.getFavoriteTracks().first() }.getOrDefault(emptyList())
            .map { it.toUnifiedTrackAuto(registry) }
        val history = runCatching { library.getHistory().first() }.getOrDefault(emptyList())
            .map { it.toUnifiedTrackAuto(registry) }

        val done = dao.allKeys().toHashSet()
        return (local + favorites + history)
            .filter { normKeyOf(it).let { k -> k.length > 1 && k.contains('|') } }
            .distinctBy { normKeyOf(it) }
            .filter { normKeyOf(it) !in done }
    }

    fun normKeyOf(track: UnifiedTrack): String =
        SpotifyFeatureDb.normalize(track.artistName) + "|" + SpotifyFeatureDb.normalize(track.title)
}
