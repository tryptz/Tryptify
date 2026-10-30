// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.share

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import tf.monochrome.android.R
import tf.monochrome.android.data.api.HiFiApiClient
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.domain.model.AlbumDetail
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.locale.AppLanguage

/**
 * Share as a link: the tryptify.com/listen link TrypT-hifi's share button
 * makes, for a song (it plays, then the rest of its album) or an album. The
 * person it is sent to opens it in Tryptify and it plays on whichever service
 * they have (SharedLinkResolver).
 *
 * The link is signed by a TrypT-hifi this phone has added when one answers,
 * so its page shows the cover and Discord plays a preview; else it is made
 * here, unsigned, and still plays. A local file, a music server's song, a
 * collection or a radio station has no catalog to point at, so it has no link.
 */
@Singleton
class ShareLinks @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: HiFiApiClient,
    private val qobuzIdRegistry: QobuzIdRegistry,
) {
    /** Shares [track], played from [source] (null: a legacy track, its catalog read from its ids). */
    suspend fun shareTrack(track: Track, source: PlaybackSource?) {
        val link = linkFor(track, source)
        if (link == null) {
            toast(text().getString(R.string.share_link_unavailable))
            return
        }
        share(link, track.title)
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

    /** The link for [track]: its catalog, its album there, and its id there. */
    fun linkFor(track: Track, source: PlaybackSource?): SharedLink? {
        val (catalog, id) = when (source) {
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

    private suspend fun share(link: SharedLink, title: String) {
        val url = apiClient.shareLink(link.source.slug, link.album, link.track) ?: SharedLink.url(link)
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

    private companion object {
        const val TAG = "ShareLinks"
    }
}
