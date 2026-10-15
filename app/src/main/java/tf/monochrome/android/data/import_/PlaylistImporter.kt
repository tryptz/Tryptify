package tf.monochrome.android.data.import_

import tf.monochrome.android.R
import tf.monochrome.android.data.api.SpotifyApiClient
import tf.monochrome.android.data.api.SpotifyEmbed
import tf.monochrome.android.data.api.SpotifyPlaylistUnavailableException
import tf.monochrome.android.data.api.SpotifyPublicPlaylist
import tf.monochrome.android.data.api.SpotifyPublicPlaylistReader
import tf.monochrome.android.data.auth.SpotifyAuthManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports playlists from Spotify into the local library: reads the tracks —
 * through the connected account (SpotifyAuthManager) where Spotify serves
 * them to it, or from the public embed for a pasted link — then runs the
 * shared search-and-match pipeline in [PlaylistImportService].
 */
@Singleton
class PlaylistImporter @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val spotifyApiClient: SpotifyApiClient,
    private val spotifyAuthManager: SpotifyAuthManager,
    private val publicReader: SpotifyPublicPlaylistReader,
    private val importService: PlaylistImportService,
) {
    /** Messages and the names given to new playlists, in the app's language. */
    private fun text(id: Int): String = tf.monochrome.android.locale.AppLanguage.wrap(context).getString(id)


    /**
     * Import from a pasted link: a Spotify playlist URL, a spotify: URI, or a
     * spotify.link short link from the share sheet.
     *
     * Read through the connected account when there is one and Spotify will
     * serve the playlist to it (the user's own: every track). Otherwise — no
     * account, or someone else's playlist, which Spotify no longer serves to
     * this app — from the public embed, which needs no account and lists the
     * first 100 tracks ([SpotifyPublicPlaylistReader]). [nameOverride] and
     * [description] are what the Create playlist dialog was given; a blank
     * name takes the playlist's own.
     */
    suspend fun importFromUrl(
        url: String,
        strictAlbumMatch: Boolean = false,
        nameOverride: String? = null,
        description: String? = null,
    ): Result<ImportProgress.Done> {
        importService.reportFetching("Spotify")
        val playlistId = SpotifyEmbed.playlistId(url)
            ?: SpotifyEmbed.shortLink(url)?.let { publicReader.resolveShortLink(it) }
        if (playlistId == null) {
            val isYouTube = url.contains("youtube.com/playlist") || url.contains("music.youtube.com/playlist")
            val message = if (isYouTube) {
                text(R.string.import_youtube_unsupported)
            } else {
                text(R.string.import_unrecognized_url)
            }
            importService.reportFailure(message)
            return Result.failure(Exception(message))
        }
        return runCatching {
            val source = readPlaylist(playlistId)
            if (source.tracks.isEmpty()) throw Exception(text(R.string.import_no_tracks))
            importService.importTracks(
                name = nameOverride?.trim()?.takeIf { it.isNotEmpty() }
                    ?: source.name.takeIf { it.isNotBlank() }
                    ?: text(R.string.import_default_playlist_name),
                description = description?.trim()?.takeIf { it.isNotEmpty() } ?: text(R.string.import_from_spotify),
                tracks = source.tracks,
                strictAlbumMatch = strictAlbumMatch,
                sourceTotal = source.totalTracks,
            )
        }.onFailure { importService.reportFailure(failureMessage(it)) }
    }

    /**
     * [playlistId]'s tracks: through the connected account when it can read
     * them, else from the public embed.
     */
    private suspend fun readPlaylist(playlistId: String): SpotifyPublicPlaylist {
        if (spotifyAuthManager.isConnected.value) {
            val viaAccount = spotifyApiClient.getPlaylistTracks(playlistId).getOrNull()
            if (!viaAccount.isNullOrEmpty()) {
                val name = spotifyApiClient.getPlaylistMeta(playlistId).getOrNull()?.name.orEmpty()
                return SpotifyPublicPlaylist(name = name, tracks = viaAccount, totalTracks = null)
            }
        }
        return publicReader.read(playlistId)
    }

    private fun failureMessage(error: Throwable): String = when (error) {
        is SpotifyPlaylistUnavailableException -> text(R.string.import_spotify_not_found)
        is java.io.IOException -> text(R.string.import_spotify_unreachable)
        else -> error.message?.takeIf { it.isNotBlank() } ?: text(R.string.import_spotify_failed)
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
        if (tracks.isEmpty()) throw Exception(text(R.string.import_no_tracks))
        val name = knownName?.takeIf { it.isNotBlank() }
            ?: spotifyApiClient.getPlaylistMeta(playlistId).getOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: text(R.string.import_default_playlist_name)
        importService.importTracks(
            name = name,
            description = text(R.string.import_from_spotify),
            tracks = tracks,
            strictAlbumMatch = strictAlbumMatch,
        )
    }.onFailure { importService.reportFailure(it.message ?: text(R.string.import_spotify_failed)) }

    suspend fun importSpotifyLikedSongs(strictAlbumMatch: Boolean = false): Result<ImportProgress.Done> =
        runCatching {
            importService.reportFetching("Spotify")
            val tracks = spotifyApiClient.getLikedSongs().getOrThrow()
            if (tracks.isEmpty()) throw Exception(text(R.string.import_liked_empty))
            importService.importTracks(
                name = text(R.string.import_liked_name),
                description = text(R.string.import_from_spotify),
                tracks = tracks,
                strictAlbumMatch = strictAlbumMatch,
            )
        }.onFailure { importService.reportFailure(it.message ?: text(R.string.import_spotify_failed)) }

    companion object {
        /** The playlist id in a Spotify link or URI; see [SpotifyEmbed.playlistId]. */
        fun extractSpotifyPlaylistId(url: String): String? = SpotifyEmbed.playlistId(url)
    }
}
