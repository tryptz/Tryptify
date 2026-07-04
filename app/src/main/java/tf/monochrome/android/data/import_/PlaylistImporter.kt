package tf.monochrome.android.data.import_

import tf.monochrome.android.data.api.SpotifyApiClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports playlists from Spotify into the local library: fetches metadata
 * via the Spotify Web API (user must have connected their account — see
 * SpotifyAuthManager), then runs the shared search-and-match pipeline in
 * [PlaylistImportService].
 */
@Singleton
class PlaylistImporter @Inject constructor(
    private val spotifyApiClient: SpotifyApiClient,
    private val importService: PlaylistImportService,
) {

    /** Import from a pasted link — Spotify playlist URLs and spotify: URIs. */
    suspend fun importFromUrl(url: String, strictAlbumMatch: Boolean = false): Result<ImportProgress.Done> {
        val playlistId = extractSpotifyPlaylistId(url)
        if (playlistId == null) {
            val isYouTube = url.contains("youtube.com/playlist") || url.contains("music.youtube.com/playlist")
            val message = if (isYouTube) {
                "YouTube Music import is not supported yet — only Spotify playlist links work."
            } else {
                "Unrecognized playlist URL. Paste a Spotify playlist link like " +
                    "https://open.spotify.com/playlist/…"
            }
            importService.reportFailure(message)
            return Result.failure(Exception(message))
        }
        return importSpotifyPlaylist(playlistId, knownName = null, strictAlbumMatch = strictAlbumMatch)
    }

    /**
     * @param knownName playlist name when the caller already has it (the
     *   picker does) — skips the metadata request entirely. For URL imports
     *   pass null; the name is then looked up best-effort and a metadata
     *   failure does not abort the import (the /items call is what matters).
     */
    suspend fun importSpotifyPlaylist(
        playlistId: String,
        knownName: String?,
        strictAlbumMatch: Boolean = false,
    ): Result<ImportProgress.Done> = runCatching {
        importService.reportFetching("Spotify")
        val tracks = spotifyApiClient.getPlaylistTracks(playlistId).getOrThrow()
        if (tracks.isEmpty()) throw Exception("This Spotify playlist has no importable tracks.")
        val name = knownName?.takeIf { it.isNotBlank() }
            ?: spotifyApiClient.getPlaylistMeta(playlistId).getOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: "Spotify Playlist"
        importService.importTracks(
            name = name,
            description = "Imported from Spotify",
            tracks = tracks,
            strictAlbumMatch = strictAlbumMatch,
        )
    }.onFailure { importService.reportFailure(it.message ?: "Spotify import failed") }

    suspend fun importSpotifyLikedSongs(strictAlbumMatch: Boolean = false): Result<ImportProgress.Done> =
        runCatching {
            importService.reportFetching("Spotify")
            val tracks = spotifyApiClient.getLikedSongs().getOrThrow()
            if (tracks.isEmpty()) throw Exception("Your Spotify Liked Songs list is empty.")
            importService.importTracks(
                name = "Liked Songs (Spotify)",
                description = "Imported from Spotify",
                tracks = tracks,
                strictAlbumMatch = strictAlbumMatch,
            )
        }.onFailure { importService.reportFailure(it.message ?: "Spotify import failed") }

    companion object {
        // 22-char base62 ID from open.spotify.com/playlist/{id}, /intl-xx/playlist/{id},
        // or spotify:playlist:{id} URIs.
        private val SPOTIFY_PLAYLIST_ID = Regex("playlist[/:]([A-Za-z0-9]{22})")

        fun extractSpotifyPlaylistId(url: String): String? =
            SPOTIFY_PLAYLIST_ID.find(url)?.groupValues?.get(1)
    }
}
