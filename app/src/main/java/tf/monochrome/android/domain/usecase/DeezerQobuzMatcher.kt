package tf.monochrome.android.domain.usecase

import tf.monochrome.android.data.api.HiFiApiClient
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.api.QobuzTrackMatch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the Qobuz track that is the same recording as a Deezer track.
 *
 * Deezer comes into the app as a browse catalog: the public API behind the
 * instance's /api/deezer routes only serve 30-second previews. So a Deezer
 * pick is played — and downloaded — from Qobuz whenever Qobuz has the same
 * recording, and only falls back to the preview when it doesn't.
 *
 * "Same recording" is decided ISRC-first, because an ISRC names exactly one
 * recording across every store and cannot pick a different master. Only when
 * no ISRC is known does it fall back to title + artist, and then strictly —
 * the same rule StreamResolver's TIDAL fallback uses — so a near miss plays
 * the preview rather than someone else's song.
 */
@Singleton
class DeezerQobuzMatcher @Inject constructor(
    private val apiClient: HiFiApiClient,
    private val registry: QobuzIdRegistry,
) {
    suspend fun qobuzMatchFor(
        deezerId: Long,
        knownIsrc: String?,
        title: String,
        artist: String,
        durationSeconds: Int,
    ): QobuzTrackMatch? {
        registry.qobuzIdForDeezer(deezerId)?.let { return QobuzTrackMatch(it, albumSlug = null, artistId = null) }

        val isrc = knownIsrc?.takeIf { it.isNotBlank() } ?: apiClient.getDeezerIsrc(deezerId)
        val match = isrc?.let { apiClient.findQobuzTrackByIsrc(it) }
            ?: metadataMatch(title, artist, durationSeconds)
            ?: return null
        registry.registerQobuzForDeezer(deezerId, match.trackId)
        return match
    }

    private suspend fun metadataMatch(title: String, artist: String, durationSeconds: Int): QobuzTrackMatch? {
        if (title.isBlank() || artist.isBlank()) return null
        // Qobuz joins the version onto titles with an em dash; compare bare titles.
        val cleanTitle = title.substringBefore(" — ").trim().ifBlank { title }
        val candidates = runCatching { apiClient.searchQobuz("$cleanTitle $artist").tracks }
            .getOrNull() ?: return null
        val match = candidates.firstOrNull { c ->
            CrossSourceMatcher.fuzzyMatch(
                cleanTitle, artist, durationSeconds,
                c.title.substringBefore(" — ").trim(), c.displayArtist, c.duration,
            )
        } ?: return null
        return QobuzTrackMatch(
            trackId = match.id,
            albumSlug = match.album?.id?.let { registry.albumSlugFor(it) },
            artistId = match.artist?.id,
        )
    }
}
