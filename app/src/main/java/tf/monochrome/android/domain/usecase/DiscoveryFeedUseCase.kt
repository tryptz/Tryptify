package tf.monochrome.android.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.UnifiedTrack
import javax.inject.Inject

/**
 * Builds the personalized discovery feed: one "New from <artist>" row per artist
 * the user plays and hearts, each showing tracks from that artist's most recent
 * Qobuz release. Everything runs through the Qobuz instance (same backend as the
 * existing genre recommendations) so the feed stays in one id namespace.
 *
 * Returns an empty list when the user has no taste data or the Qobuz instance is
 * unconfigured / disabled — callers fall back to the static genre rows in that case.
 */
class DiscoveryFeedUseCase @Inject constructor(
    private val library: LibraryRepository,
    private val music: MusicRepository,
    private val registry: QobuzIdRegistry,
) {
    data class DiscoveryRow(val label: String, val tracks: List<UnifiedTrack>)

    suspend fun build(maxArtists: Int = 6, tracksPerRow: Int = 12): List<DiscoveryRow> =
        coroutineScope {
            library.getSeedArtistNames(maxArtists).map { name ->
                async { rowForArtist(name, tracksPerRow) }
            }.mapNotNull { it.await() }
        }

    private suspend fun rowForArtist(name: String, tracksPerRow: Int): DiscoveryRow? =
        withTimeoutOrNull(QOBUZ_BUDGET_MS) {
            // searchQobuz also registers each album's slug into the QobuzIdRegistry
            // as a side effect, so the newest album below is resolvable by id.
            val result = music.searchQobuz(name).getOrNull() ?: return@withTimeoutOrNull null

            // Newest release attributed to this artist, by release date.
            val newest = result.albums
                .filter { it.displayArtist.matchesArtist(name) && !it.releaseDate.isNullOrBlank() }
                .maxByOrNull { it.releaseDate!! }

            val albumTracks = newest
                ?.let { registry.albumSlugFor(it.id) }
                ?.let { slug -> music.getQobuzAlbum(slug).getOrNull()?.tracks }
                ?.take(tracksPerRow)

            // Fallback: if no resolvable newest album, surface the search's top
            // Qobuz tracks for this artist so the row still populates.
            val sourceTracks = albumTracks?.takeIf { it.isNotEmpty() }
                ?: result.tracks.take(tracksPerRow)

            // Tag the artist ids we're about to wire navigation to as Qobuz, so
            // ArtistDetailViewModel routes them to getQobuzArtist. getQobuzAlbum
            // registers track ids + album slugs but not the album's artist id, so
            // without this a dual-source setup could mis-route to the TIDAL pool.
            sourceTracks.mapNotNull { it.artist?.id }.distinct()
                .forEach { registry.registerArtist(it) }

            val tracks = sourceTracks.map { it.toQobuzUnifiedTrack() }

            tracks.takeIf { it.isNotEmpty() }?.let { DiscoveryRow("New from $name", it) }
        }

    /** Lenient match so search albums credited to the seed artist are kept. */
    private fun String.matchesArtist(name: String): Boolean {
        val a = trim().lowercase()
        val b = name.trim().lowercase()
        return a == b || a.contains(b) || b.contains(a)
    }

    companion object {
        // Per-artist ceiling, mirroring the 7s budget SearchViewModel uses for
        // Qobuz so one slow artist can't stall the whole feed.
        private const val QOBUZ_BUDGET_MS = 7_000L
    }
}
