// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.share

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A song or album shared from TrypT-hifi's share button:
 * `https://tryptify.com/listen/<source>/album/<album>[/track/<track>]?isrc=…&title=…&artist=…&duration=…&s=…`.
 * The album is always named, because an album is what every catalog loads by
 * id; [track] is where to start in it (null: from its first song). [isrc]
 * (an album link's first song's), [title], [artist] and [durationSec] let a
 * phone without that service find the same music on one it has
 * (SharedLinkResolver); any of them may be missing. The `s=` signature is for
 * the website's preview and is not needed here.
 */
data class SharedLink(
    val source: Source,
    val album: String,
    val track: Long?,
    val isrc: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val durationSec: Int? = null,
) {
    enum class Source(val slug: String, val label: String) {
        QOBUZ("qobuz", "Qobuz"),
        DEEZER("deezer", "Deezer"),
        TIDAL("tidal", "TIDAL"),
        APPLE("apple", "Apple Music"),
    }

    companion object {
        private val HOSTS = setOf("tryptify.com", "www.tryptify.com")
        private val PATH = Regex("""/listen/([a-z]+)/album/([A-Za-z0-9]{1,40})(?:/track/(\d{1,18}))?/?""")
        private val ISRC = Regex("""[A-Z]{2}[A-Z0-9]{3}\d{7}""")

        /**
         * The link in [scheme], [host], [path] and its [query] parameters, or
         * null for any other link. Qobuz albums are slugs (letters and digits);
         * every other id must be a number, since that is how those catalogs
         * load it. A malformed ISRC, title, artist or length is dropped, not
         * the link.
         */
        fun parse(scheme: String?, host: String?, path: String?, query: (String) -> String? = { null }): SharedLink? {
            if (!scheme.equals("https", ignoreCase = true) || host?.lowercase() !in HOSTS || path == null) return null
            val (slug, album, track) = PATH.matchEntire(path)?.destructured ?: return null
            val source = Source.entries.firstOrNull { it.slug == slug } ?: return null
            if (source != Source.QOBUZ && album.toLongOrNull() == null) return null
            fun text(name: String) = query(name)?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
            return SharedLink(
                source = source,
                album = album,
                track = track.takeIf { it.isNotEmpty() }?.toLong(),
                isrc = query("isrc")?.uppercase()?.takeIf { ISRC.matches(it) },
                title = text("title"),
                artist = text("artist"),
                durationSec = query("duration")?.toIntOrNull()?.takeIf { it in 1..36_000 },
            )
        }
    }
}

/**
 * The shared link opened from outside the app, until the app's root takes it
 * and plays it (once: rotated or reopened from Recents, the same link does
 * not play again, see MainActivity).
 */
@Singleton
class SharedLinks @Inject constructor() {
    private val _link = MutableStateFlow<SharedLink?>(null)

    val link: StateFlow<SharedLink?> = _link.asStateFlow()

    /** Keeps [uri] when it is a shared link; false for any other link. */
    fun offer(uri: Uri): Boolean {
        val link = SharedLink.parse(uri.scheme, uri.host, uri.path) { uri.getQueryParameter(it) } ?: return false
        _link.value = link
        return true
    }

    fun clear() {
        _link.value = null
    }
}
