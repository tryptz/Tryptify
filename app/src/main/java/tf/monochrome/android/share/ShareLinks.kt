// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.share

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.R
import tf.monochrome.android.data.api.HiFiApiClient
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.scxyt.ScxytError
import tf.monochrome.android.data.scxyt.ScxytRepository
import tf.monochrome.android.data.scxyt.ScxytShare
import tf.monochrome.android.data.scxyt.ScxytSource
import tf.monochrome.android.domain.model.AlbumDetail
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.locale.AppLanguage

/**
 * Share as a link: the tryptify.com/listen link TrypT-hifi's share button
 * makes, for a song (it plays, then the rest of its album) or an album. The
 * person it is sent to opens it in Tryptify and it plays on whichever service
 * they have (SharedLinkResolver).
 *
 * The link is signed by a TrypT-hifi this phone has added when one answers,
 * so its page shows the cover and Discord plays a preview; else it is made
 * here, unsigned, and still plays. A YouTube or SoundCloud song gets a link
 * to the song alone, signed by trypt-hifi-scxyt, which makes its preview
 * when it is shared ([signedSongLink]); the website makes the rest of its
 * page from YouTube's or SoundCloud's own preview. A local file, a music
 * server's song, a collection or a radio station has no catalog to point
 * at, so it has no link.
 */
@Singleton
class ShareLinks @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: HiFiApiClient,
    private val qobuzIdRegistry: QobuzIdRegistry,
    private val scxyt: ScxytRepository,
) {
    /**
     * Shares [track], played as [unified] says (null: a legacy track, its
     * catalog read from its ids). [previewStartSec] is where the preview
     * Discord plays begins: where the song is playing, when the song shared
     * is the one playing; null lets the server start a third of the way in.
     */
    suspend fun shareTrack(track: Track, unified: UnifiedTrack?, previewStartSec: Int? = null) {
        val source = unified?.source
        val link = if (source is PlaybackSource.Scxyt) {
            signedSongLink(track, source, unified?.pageUrl) { preview(track.title, it, previewStartSec) }
        } else {
            linkFor(track, source, unified?.pageUrl)
        }
        if (link == null) {
            toast(text().getString(R.string.share_link_unavailable))
            return
        }
        share(link, track.title, previewStartSec)
    }

    /** Shares [detail], from the catalog its page loaded it from. */
    suspend fun shareAlbum(albumId: Long, detail: AlbumDetail) {
        val (source, album) = when {
            qobuzIdRegistry.isDeezerAlbum(albumId) -> SharedLink.Source.DEEZER to albumId.toString()
            qobuzIdRegistry.isAppleAlbum(albumId) -> SharedLink.Source.APPLE to albumId.toString()
            else -> qobuzIdRegistry.albumSlugFor(albumId)?.let { SharedLink.Source.QOBUZ to it }
                ?: (SharedLink.Source.TIDAL to albumId.toString())
        }
        val link = SharedLink(source, album, null, title = detail.album.title, artist = detail.album.artist?.name)
        share(link, detail.album.title)
    }

    /**
     * The link for [track]: its catalog, its album there, and its id there;
     * for a YouTube or SoundCloud song, the song and its [page] ([songLink]).
     */
    fun linkFor(track: Track, source: PlaybackSource?, page: String? = null): SharedLink? {
        val (catalog, id) = when (source) {
            is PlaybackSource.Scxyt -> return songLink(track, source, page)
            is PlaybackSource.QobuzCached -> SharedLink.Source.QOBUZ to source.qobuzId
            is PlaybackSource.AppleCached -> SharedLink.Source.APPLE to source.appleId
            is PlaybackSource.DeezerPreview -> SharedLink.Source.DEEZER to source.deezerId
            is PlaybackSource.HiFiApi -> SharedLink.Source.TIDAL to source.tidalId
            null -> when {
                track.appleId != null -> SharedLink.Source.APPLE to track.appleId
                track.deezerId != null -> SharedLink.Source.DEEZER to track.deezerId
                qobuzIdRegistry.isQobuzTrack(track.id) -> SharedLink.Source.QOBUZ to track.id
                track.id > 0 -> SharedLink.Source.TIDAL to track.id
                else -> return null
            }
            else -> return null
        }
        val albumId = track.album?.id?.takeIf { it > 0 } ?: return null
        // A Qobuz album loads by its slug; without one, the number still lets
        // the other end find the song by name.
        val album = if (catalog == SharedLink.Source.QOBUZ) qobuzIdRegistry.albumSlugFor(albumId) ?: albumId.toString() else albumId.toString()
        return SharedLink(
            source = catalog,
            album = album,
            track = id,
            title = track.title,
            artist = track.displayArtist.takeIf { it.isNotBlank() },
            durationSec = track.duration.takeIf { it > 0 },
        )
    }

    /**
     * The preview of the YouTube or SoundCloud song [mediaId], from
     * trypt-hifi-scxyt: seconds of sending a YouTube song up and encoding its
     * clip, so the listener is told the tap was taken. Signed out there is
     * nothing to wait for: the server would only refuse it.
     */
    private suspend fun preview(title: String, mediaId: String, startSec: Int?): Result<ScxytShare> {
        if (!scxyt.isSignedIn()) return Result.failure(ScxytError.SignedOut())
        // Says what the wait is for: the share sheet opens only once the preview is made.
        toast(text().getString(R.string.share_link_preparing, title))
        val share = withTimeoutOrNull(PREVIEW_WAIT_MS) { withContext(Dispatchers.IO) { scxyt.share(mediaId, startSec) } }
            ?: Result.failure(ScxytError.Timeout())
        share.onFailure { Log.i(TAG, "share link: no preview for $mediaId, shared without it: ${it::class.simpleName}: ${it.message}") }
        return share
    }

    private suspend fun share(link: SharedLink, title: String, previewStartSec: Int? = null) {
        val url = link.album?.let { apiClient.shareLink(link.source.slug, it, link.track, previewStartSec) } ?: SharedLink.url(link)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        // Launched from the application context, so it needs a task of its own.
        val chooser = Intent.createChooser(send, text().getString(R.string.share_link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }.onFailure { Log.w(TAG, "share link: launch failed", it) }
    }

    private fun text(): Context = AppLanguage.wrap(context)

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "ShareLinks"

        /**
         * The longest a share waits for its preview, the song's fetch onto
         * the phone included when it is not in the cache, before it goes
         * without one.
         */
        const val PREVIEW_WAIT_MS = 120_000L

        /**
         * The playing song's position as a preview's start, in whole
         * seconds; null for no position (nothing loaded yet). The server
         * holds it inside the song: shared in the last seconds, the clip is
         * the song's last thirty.
         */
        fun previewStart(positionMs: Long): Int? = if (positionMs < 0) null else (positionMs / 1000).toInt().coerceAtMost(99_999)

        /**
         * [songLink], signed with the preview [preview] has the server make,
         * so Discord shows the song's cover with a play button. Whatever goes
         * wrong (signed out, no network, the server's refusal, a song too
         * large to send), the link goes without it: it opens and plays all
         * the same, and Discord shows a small card instead. Nothing is asked
         * for an id that has no link.
         */
        suspend fun signedSongLink(
            track: Track,
            source: PlaybackSource.Scxyt,
            page: String?,
            preview: suspend (mediaId: String) -> Result<ScxytShare>,
        ): SharedLink? {
            if (songLink(track, source, page) == null) return null
            val share = try {
                preview(source.mediaId).getOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            return songLink(track, source, page, share)
        }

        /**
         * A YouTube or SoundCloud song's link: its video id or track number,
         * its title, artist and length, so a phone signed out of SoundCloud
         * can find it on YouTube, and a SoundCloud song's [page] when it is
         * known, else the one the server found when it made the [share]
         * (null for an id neither service hands out). The [share]'s
         * signature lets the website show the song's preview.
         */
        fun songLink(track: Track, source: PlaybackSource.Scxyt, page: String?, share: ScxytShare? = null): SharedLink? {
            val service = when (ScxytSource.ofId(source.mediaId)) {
                ScxytSource.YOUTUBE -> SharedLink.Source.YOUTUBE
                ScxytSource.SOUNDCLOUD -> SharedLink.Source.SOUNDCLOUD
                null -> return null
            }
            return SharedLink(
                source = service,
                album = null,
                track = null,
                title = track.title.takeIf { it.isNotBlank() },
                artist = track.displayArtist.takeIf { it.isNotBlank() },
                durationSec = track.duration.takeIf { it > 0 },
                song = source.mediaId.substringAfter(':'),
                page = listOfNotNull(page, share?.page)
                    .firstOrNull { service == SharedLink.Source.SOUNDCLOUD && SharedLink.isSoundcloudPage(it) },
                signature = share?.s,
            )
        }
    }
}
