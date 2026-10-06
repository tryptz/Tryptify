package tf.monochrome.android.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import tf.monochrome.android.data.api.ApiService
import tf.monochrome.android.data.api.HiFiApiClient
import tf.monochrome.android.data.api.ServiceQuality
import tf.monochrome.android.data.api.KugouLyricsClient
import tf.monochrome.android.data.api.LrcLibClient
import tf.monochrome.android.data.api.NetEaseLyricsClient
import tf.monochrome.android.data.preferences.LyricsWordProvider
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.AlbumDetail
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.ArtistDetail
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.domain.model.Playlist
import tf.monochrome.android.domain.model.SearchResult
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.TrackStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepository @Inject constructor(
    private val apiClient: HiFiApiClient,
    private val lrcLibClient: LrcLibClient,
    private val netEaseLyricsClient: NetEaseLyricsClient,
    private val kugouLyricsClient: KugouLyricsClient,
    private val preferences: PreferencesManager,
    @ApplicationContext private val context: Context
) {
    // --- Search ---

    suspend fun search(query: String, offset: Int = 0, limit: Int = 50): Result<SearchResult> = runCatching {
        apiClient.search(query, offset, limit)
    }

    suspend fun searchQobuz(query: String, offset: Int = 0): Result<SearchResult> = runCatching {
        apiClient.searchQobuz(query, offset)
    }

    suspend fun searchApple(query: String, offset: Int = 0): Result<SearchResult> = runCatching {
        apiClient.searchApple(query, offset)
    }

    suspend fun appleStreamUrl(
        appleId: Long,
        quality: tf.monochrome.android.domain.model.AudioQuality,
        atmos: Boolean,
    ): Result<String?> = runCatching {
        apiClient.getAppleStreamUrl(appleId, quality, atmos)
    }

    /** TIDAL track's ISRC (metadata pool) — used by the Qobuz playback fallback. */
    suspend fun getTidalIsrc(trackId: Long): String? = apiClient.getTidalIsrc(trackId)

    /** Qobuz match (track id + album slug + artist id) for an ISRC, or null. */
    suspend fun findQobuzByIsrc(isrc: String): tf.monochrome.android.data.api.QobuzTrackMatch? =
        apiClient.findQobuzTrackByIsrc(isrc)

    /**
     * Qobuz album-detail fetch. Returns Result.failure when the Qobuz instance
     * isn't configured or the lookup fails so the detail VM can fall back to
     * the TIDAL path cleanly.
     */
    suspend fun getQobuzAlbum(albumSlug: String): Result<AlbumDetail> = runCatching {
        apiClient.getQobuzAlbum(albumSlug)
            ?: throw IllegalStateException("Qobuz album not available: $albumSlug")
    }

    suspend fun getQobuzArtist(artistId: Long): Result<ArtistDetail> = runCatching {
        apiClient.getQobuzArtist(artistId)
            ?: throw IllegalStateException("Qobuz artist not available: $artistId")
    }

    /** Apple album detail — numeric id, no slug (see HiFiApiClient.getAppleAlbum). */
    suspend fun getAppleAlbum(albumId: Long): Result<AlbumDetail> = runCatching {
        apiClient.getAppleAlbum(albumId)
            ?: throw IllegalStateException("Apple album not available: $albumId")
    }

    suspend fun getAppleArtist(artistId: Long): Result<ArtistDetail> = runCatching {
        apiClient.getAppleArtist(artistId)
            ?: throw IllegalStateException("Apple artist not available: $artistId")
    }

    suspend fun searchDeezer(query: String, offset: Int = 0): Result<SearchResult> = runCatching {
        apiClient.searchDeezer(query, offset)
    }

    /** Deezer album detail — served by the Qobuz instance's /api/deezer/get-album. */
    suspend fun getDeezerAlbum(albumId: Long): Result<AlbumDetail> = runCatching {
        apiClient.getDeezerAlbum(albumId)
            ?: throw IllegalStateException("Deezer album not available: $albumId")
    }

    suspend fun getDeezerArtist(artistId: Long): Result<ArtistDetail> = runCatching {
        apiClient.getDeezerArtist(artistId)
            ?: throw IllegalStateException("Deezer artist not available: $artistId")
    }

    /** Signed URL of a Deezer track's 30-second preview, or null. */
    suspend fun deezerPreviewUrl(deezerId: Long): String? =
        runCatching { apiClient.getDeezerPreviewUrl(deezerId) }.getOrNull()

    suspend fun searchTracks(query: String, offset: Int = 0, limit: Int = 50): Result<List<Track>> = runCatching {
        apiClient.searchTracks(query, offset, limit)
    }

    suspend fun searchAlbums(query: String, offset: Int = 0, limit: Int = 50): Result<List<Album>> = runCatching {
        apiClient.searchAlbums(query, offset, limit)
    }

    suspend fun searchArtists(query: String, offset: Int = 0, limit: Int = 50): Result<List<Artist>> = runCatching {
        apiClient.searchArtists(query, offset, limit)
    }

    suspend fun searchPlaylists(query: String, offset: Int = 0, limit: Int = 50): Result<List<Playlist>> = runCatching {
        apiClient.searchPlaylists(query, offset, limit)
    }

    // --- Detail ---

    suspend fun getAlbum(albumId: Long): Result<AlbumDetail> = runCatching {
        apiClient.getAlbum(albumId)
    }

    suspend fun getArtist(artistId: Long): Result<ArtistDetail> = runCatching {
        apiClient.getArtist(artistId)
    }

    suspend fun getPlaylist(playlistId: String): Result<Playlist> = runCatching {
        apiClient.getPlaylist(playlistId)
    }

    // --- Streaming ---

    /** A TIDAL track's stream, in TIDAL's streaming quality for the network in use. */
    suspend fun getTrackStream(trackId: Long): Result<TrackStream> = runCatching {
        apiClient.getTrackStream(trackId, streamQuality(ApiService.TIDAL))
    }

    suspend fun getTrackStream(trackId: Long, quality: AudioQuality): Result<TrackStream> = runCatching {
        apiClient.getTrackStream(trackId, quality)
    }

    // --- Recommendations ---

    suspend fun getRecommendations(trackId: Long): Result<List<Track>> = runCatching {
        apiClient.getRecommendations(trackId)
    }

    // --- AI Recommendations ---

    // --- Lyrics ---

    suspend fun getLyrics(
        trackId: Long,
        track: Track? = null,
        skipTidal: Boolean = false,
    ): Result<Lyrics?> = runCatching {
        val romajiEnabled = preferences.romajiLyrics.first()
        if (!skipTidal) {
            // TIDAL by id — highest-quality path (LRC + word-level timing).
            apiClient.getLyrics(trackId, romajiEnabled)?.let { return@runCatching it }
        } else {
            // Qobuz: the id can't be used on TIDAL (different namespace → wrong
            // song), but TIDAL usually still HAS the song. Match it by metadata
            // and use TIDAL's lyrics — the same working instance the player
            // already streams from — before falling back to LRCLib, which may
            // be unreachable on some networks and lacks word-level timing.
            tidalLyricsByMetadata(track, romajiEnabled)?.let { return@runCatching it }
        }
        // No word-level lyrics from TIDAL — try free, no-auth catalogs that
        // carry per-word (karaoke-style) timing before falling back to
        // LRCLib's line-level-only synced lyrics. Which provider(s) run is
        // user-selected: NetEase only, Kugou only, or both as each other's
        // fallback (NetEase first). Skip entirely when we don't have enough
        // info to make any reasonable query.
        val title = track?.title?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val artistName = (track.artist?.name ?: track.artists.firstOrNull()?.name)
            ?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val durationSeconds = track.duration.takeIf { it > 0 }
        val wordProvider = preferences.lyricsWordProvider.first()

        if (wordProvider != LyricsWordProvider.KUGOU_ONLY) {
            runCatching {
                netEaseLyricsClient.lookup(title, artistName, durationSeconds, romajiEnabled)
            }.getOrNull()?.let { return@runCatching it }
        }

        if (wordProvider != LyricsWordProvider.NETEASE_ONLY) {
            runCatching {
                kugouLyricsClient.lookup(title, artistName, durationSeconds, romajiEnabled)
            }.getOrNull()?.let { return@runCatching it }
        }

        lrcLibClient.lookup(
            title = title,
            artist = artistName,
            album = track.album?.title,
            durationSeconds = durationSeconds,
            convertToRomaji = romajiEnabled,
        )
    }

    /**
     * Resolve lyrics for a non-TIDAL track (e.g. Qobuz) by finding the matching
     * TIDAL track through catalogue search, then fetching that track's lyrics.
     * Qobuz exposes no lyrics endpoint of its own, so this reuses the working
     * TIDAL instance rather than depending solely on lrclib.net.
     */
    private suspend fun tidalLyricsByMetadata(track: Track?, romajiEnabled: Boolean): Lyrics? {
        val rawTitle = track?.title?.takeIf { it.isNotBlank() } ?: return null
        val artistName = (track.artist?.name ?: track.artists.firstOrNull()?.name)
            ?.takeIf { it.isNotBlank() } ?: return null
        // Qobuz appends the version with an em dash ("Song — Radio Edit"); drop
        // it so the search matches the base track.
        val title = rawTitle.substringBefore(" — ").trim().ifBlank { rawTitle }
        val results = runCatching { apiClient.search("$title $artistName", 0, 5) }
            .getOrNull()?.tracks?.takeIf { it.isNotEmpty() } ?: return null
        val artistMatches = { c: Track ->
            c.artist?.name?.contains(artistName, ignoreCase = true) == true ||
                c.artists.any { it.name.contains(artistName, ignoreCase = true) }
        }
        // Prefer an exact (cleaned) title match; otherwise the closest title
        // that still shares the artist. Never match on title alone.
        val match = results.firstOrNull { c ->
            c.title.substringBefore(" — ").trim().equals(title, ignoreCase = true) && artistMatches(c)
        } ?: results.firstOrNull { c ->
            c.title.contains(title, ignoreCase = true) && artistMatches(c)
        } ?: return null
        return apiClient.getLyrics(match.id, romajiEnabled)
    }

    // --- Quality ---

    /**
     * [service]'s streaming quality for the network in use: its Wi-Fi setting
     * on Wi-Fi, its cellular one otherwise. Each service has its own pair.
     */
    suspend fun streamQuality(service: ApiService): AudioQuality {
        val setting = if (isOnWifi()) ServiceQuality.Setting.WIFI else ServiceQuality.Setting.CELLULAR
        return preferences.quality(service, setting).first()
    }

    private fun isOnWifi(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
