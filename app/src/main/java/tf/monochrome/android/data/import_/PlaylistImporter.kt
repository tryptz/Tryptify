package tf.monochrome.android.data.import_

import tf.monochrome.android.R
import tf.monochrome.android.data.api.SpotifyEmbed
import tf.monochrome.android.data.api.SpotifyPlaylistUnavailableException
import tf.monochrome.android.data.api.SpotifyPublicPlaylistReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports Spotify playlists into the local library from a pasted link: reads
 * the tracks from the public embed, which needs no account, then runs the
 * shared search-and-match pipeline in [PlaylistImportService].
 */
@Singleton
class PlaylistImporter @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val publicReader: SpotifyPublicPlaylistReader,
    private val importService: PlaylistImportService,
) {
    /** Messages and the names given to new playlists, in the app's language. */
    private fun text(id: Int): String = tf.monochrome.android.locale.AppLanguage.wrap(context).getString(id)


    /**
     * Import from a pasted link: a Spotify playlist URL, a spotify: URI, or a
     * spotify.link short link from the share sheet.
     *
     * Read from the public embed, which needs no account and lists the first
     * 100 tracks ([SpotifyPublicPlaylistReader]). [nameOverride] and
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
            val source = publicReader.read(playlistId)
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

    private fun failureMessage(error: Throwable): String = when (error) {
        is SpotifyPlaylistUnavailableException -> text(R.string.import_spotify_not_found)
        is java.io.IOException -> text(R.string.import_spotify_unreachable)
        else -> error.message?.takeIf { it.isNotBlank() } ?: text(R.string.import_spotify_failed)
    }

    companion object {
        /** The playlist id in a Spotify link or URI; see [SpotifyEmbed.playlistId]. */
        fun extractSpotifyPlaylistId(url: String): String? = SpotifyEmbed.playlistId(url)
    }
}
