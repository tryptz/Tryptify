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
 *
 * A YouTube or SoundCloud song has no album, so its link names the song
 * alone: `https://tryptify.com/listen/<youtube|soundcloud>/track/<song>?title=…&artist=…&duration=…[&page=…]`,
 * with [album] and [track] null and [song] its video id or track number.
 * [page] is a SoundCloud song's soundcloud.com address, which its number
 * does not give: the website links the song there. Their page is made from
 * YouTube's and SoundCloud's oEmbed, so there is no signature.
 */
data class SharedLink(
    val source: Source,
    val album: String?,
    val track: Long?,
    val isrc: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val durationSec: Int? = null,
    val song: String? = null,
    val page: String? = null,
) {
    /** [catalog]: shared as an album and a song on it; else as a song alone. */
    enum class Source(val slug: String, val label: String, val catalog: Boolean = true) {
        QOBUZ("qobuz", "Qobuz"),
        DEEZER("deezer", "Deezer"),
        TIDAL("tidal", "TIDAL"),
        APPLE("apple", "Apple Music"),
        YOUTUBE("youtube", "YouTube", catalog = false),
        SOUNDCLOUD("soundcloud", "SoundCloud", catalog = false),
    }

    /** The song's id as the app plays it (`yt:…`, `sc:…`); null for a catalog link. */
    val mediaId: String?
        get() = song?.let {
            when (source) {
                Source.YOUTUBE -> "yt:$it"
                Source.SOUNDCLOUD -> "sc:$it"
                else -> null
            }
        }

    companion object {
        private val HOSTS = setOf("tryptify.com", "www.tryptify.com")
        private val PATH = Regex("""/listen/([a-z]+)/album/([A-Za-z0-9]{1,40})(?:/track/(\d{1,18}))?/?""")
        // The website's own check (its worker's LISTEN_SONG): a video id, or a track number.
        private val SONG_PATH = Regex("""/listen/(?:youtube/track/([A-Za-z0-9_-]{11})|soundcloud/track/(\d{1,20}))/?""")
        private val ISRC = Regex("""[A-Z]{2}[A-Z0-9]{3}\d{7}""")

        /** The only `page=` the website links to: a song's address on soundcloud.com, whole. */
        private val SOUNDCLOUD_PAGE = Regex("""https://soundcloud\.com/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+""")

        fun isSoundcloudPage(url: String?): Boolean = url != null && SOUNDCLOUD_PAGE.matches(url)

        /**
         * The link for [link], as TrypT-hifi's share button makes it, less its
         * signature: what this phone shares when no TrypT-hifi it has added
         * will sign one. It opens and plays in Tryptify all the same; only the
         * website's preview (the cover, Discord's player) needs the signature.
         */
        fun url(link: SharedLink): String {
            if (link.album == null) return songUrl(link)
            val path = "/listen/${link.source.slug}/album/${enc(link.album)}" + (link.track?.let { "/track/$it" } ?: "")
            val query = listOfNotNull(
                link.isrc?.let { "isrc=${enc(it)}" },
                link.title?.let { "title=${enc(it.take(200))}" },
                link.artist?.let { "artist=${enc(it.take(200))}" },
                link.durationSec?.takeIf { link.track != null && it > 0 }?.let { "duration=$it" },
            ).joinToString("&")
            return "https://tryptify.com$path" + (if (query.isEmpty()) "" else "?$query")
        }

        // A YouTube or SoundCloud song's link, as the website reads it: the
        // song alone, its page only when it is one the website would link to.
        private fun songUrl(link: SharedLink): String {
            val query = listOfNotNull(
                link.title?.let { "title=${enc(it.take(200))}" },
                link.artist?.let { "artist=${enc(it.take(200))}" },
                link.durationSec?.takeIf { it > 0 }?.let { "duration=$it" },
                link.page?.takeIf { link.source == Source.SOUNDCLOUD && isSoundcloudPage(it) }?.let { "page=${enc(it)}" },
            ).joinToString("&")
            val path = "/listen/${link.source.slug}/track/${link.song.orEmpty()}"
            return "https://tryptify.com$path" + (if (query.isEmpty()) "" else "?$query")
        }

        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

        /**
         * The link in [scheme], [host], [path] and its [query] parameters, or
         * null for any other link. Qobuz albums are slugs (letters and digits);
         * every other id must be a number, since that is how those catalogs
         * load it. A YouTube song is its 11-character video id, a SoundCloud
         * one its number. A malformed ISRC, title, artist, length or page is
         * dropped, not the link.
         */
        fun parse(scheme: String?, host: String?, path: String?, query: (String) -> String? = { null }): SharedLink? {
            if (!scheme.equals("https", ignoreCase = true) || host?.lowercase() !in HOSTS || path == null) return null
            fun text(name: String) = query(name)?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
            fun duration() = query("duration")?.toIntOrNull()?.takeIf { it in 1..36_000 }
            SONG_PATH.matchEntire(path)?.destructured?.let { (video, number) ->
                val source = if (video.isNotEmpty()) Source.YOUTUBE else Source.SOUNDCLOUD
                return SharedLink(
                    source = source,
                    album = null,
                    track = null,
                    title = text("title"),
                    artist = text("artist"),
                    durationSec = duration(),
                    song = video.ifEmpty { number },
                    page = query("page")?.takeIf { source == Source.SOUNDCLOUD && isSoundcloudPage(it) },
                )
            }
            val (slug, album, track) = PATH.matchEntire(path)?.destructured ?: return null
            val source = Source.entries.firstOrNull { it.slug == slug && it.catalog } ?: return null
            if (source != Source.QOBUZ && album.toLongOrNull() == null) return null
            return SharedLink(
                source = source,
                album = album,
                track = track.takeIf { it.isNotEmpty() }?.toLong(),
                isrc = query("isrc")?.uppercase()?.takeIf { ISRC.matches(it) },
                title = text("title"),
                artist = text("artist"),
                durationSec = duration(),
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
