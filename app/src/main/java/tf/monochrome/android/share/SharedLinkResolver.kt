// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.share

import javax.inject.Inject
import javax.inject.Singleton
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.AlbumDetail
import tf.monochrome.android.domain.model.SearchResult
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.party.PartyItem
import tf.monochrome.android.party.PartyMatch

/**
 * What a shared link plays on this phone, whichever services it has: an
 * album's songs and the one to start at.
 *
 * 1. The album from the service it was shared from, when this phone has it.
 * 2. Else the same recording on Qobuz by its ISRC, and that album.
 * 3. Else the song (or album) by title, artist and length on every service
 *    this phone has, TIDAL, Qobuz, Deezer then Apple Music, matched as a
 *    listening party matches a song (PartyMatch.best), and its album.
 *
 * Null when none of them has it.
 */
@Singleton
class SharedLinkResolver @Inject constructor(
    private val repository: MusicRepository,
    private val qobuzIdRegistry: QobuzIdRegistry,
) {
    data class Resolved(val album: AlbumDetail, val start: Track)

    suspend fun resolve(link: SharedLink): Resolved? =
        fromSource(link) ?: byIsrc(link) ?: byName(link)

    private suspend fun fromSource(link: SharedLink): Resolved? {
        val album = when (link.source) {
            SharedLink.Source.QOBUZ -> repository.getQobuzAlbum(link.album)
            SharedLink.Source.DEEZER -> repository.getDeezerAlbum(link.album.toLong())
            SharedLink.Source.APPLE -> repository.getAppleAlbum(link.album.toLong())
            SharedLink.Source.TIDAL -> repository.getAlbum(link.album.toLong())
        }.getOrNull()
        return album?.let { startIn(it) { t -> t.id == link.track || t.deezerId == link.track || t.appleId == link.track } }
    }

    private suspend fun byIsrc(link: SharedLink): Resolved? {
        val isrc = link.isrc ?: return null
        val match = runCatching { repository.findQobuzByIsrc(isrc) }.getOrNull() ?: return null
        val album = match.albumSlug?.let { repository.getQobuzAlbum(it).getOrNull() } ?: return null
        // An album link's ISRC is its first song's: the album plays from its start.
        return startIn(album) { t -> link.track != null && t.id == match.trackId }
    }

    private suspend fun byName(link: SharedLink): Resolved? {
        val title = link.title ?: return null
        val query = listOfNotNull(link.artist, title).joinToString(" ")
        for (search in searches) {
            val found = runCatching { search(query) }.getOrNull() ?: continue
            if (link.track != null) {
                val want = PartyItem(id = "shared", title = title, artist = link.artist.orEmpty(), durationMs = (link.durationSec ?: 0) * 1000L)
                val track = PartyMatch.best(want, found.tracks) ?: continue
                val album = track.album?.id?.let { albumById(it) }
                return album?.let { startIn(it) { t -> t.id == track.id } }
                    ?: Resolved(AlbumDetail(track.album ?: continue, listOf(track)), track)
            } else {
                val album = found.albums.firstOrNull { sameAlbum(it.title, it.artist?.name, title, link.artist) } ?: continue
                return albumById(album.id)?.let { startIn(it) { false } } ?: continue
            }
        }
        return null
    }

    // Every service this phone may have, as search results. One it hasn't fails, and is skipped.
    private val searches: List<suspend (String) -> SearchResult?> = listOf(
        { q -> SearchResult(tracks = repository.searchTracks(q, limit = 10).getOrThrow(), albums = repository.searchAlbums(q, limit = 10).getOrThrow()) },
        { q -> repository.searchQobuz(q).getOrThrow() },
        { q -> repository.searchDeezer(q).getOrThrow() },
        { q -> repository.searchApple(q).getOrThrow() },
    )

    // An album found by search, from its own catalog, as its page loads it
    // (AlbumDetailViewModel): search registered which catalog its id is in.
    private suspend fun albumById(id: Long): AlbumDetail? = when {
        qobuzIdRegistry.isDeezerAlbum(id) -> repository.getDeezerAlbum(id)
        qobuzIdRegistry.isAppleAlbum(id) -> repository.getAppleAlbum(id)
        else -> qobuzIdRegistry.albumSlugFor(id)?.let { repository.getQobuzAlbum(it) } ?: repository.getAlbum(id)
    }.getOrNull()

    // [album] from its song [first] picks, or from its first song.
    private fun startIn(album: AlbumDetail, first: (Track) -> Boolean): Resolved? {
        val tracks = album.tracks.takeIf { it.isNotEmpty() } ?: return null
        return Resolved(album, tracks.firstOrNull(first) ?: tracks.first())
    }

    companion object {
        /** Two names for one album: the same letters (one maybe with an edition on), by the same artist. */
        fun sameAlbum(title: String, artist: String?, wantTitle: String, wantArtist: String?): Boolean {
            fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }
            val (a, b) = norm(title) to norm(wantTitle)
            if (a.isEmpty() || b.isEmpty() || !(a.startsWith(b) || b.startsWith(a))) return false
            val (x, y) = norm(artist) to norm(wantArtist)
            return y.isEmpty() || x.isEmpty() || x.contains(y) || y.contains(x)
        }
    }
}
