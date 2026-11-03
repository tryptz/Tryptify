// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.share

import kotlin.math.abs
import tf.monochrome.android.data.scxyt.ScxytTrack
import tf.monochrome.android.data.scxyt.toUnifiedTrack
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.party.PartyMatch

/**
 * What a YouTube or SoundCloud link plays on this phone, and YouTube as the
 * last resort for a catalog song none of its services has.
 *
 * - A YouTube link plays that video's song, fetched on the phone, which
 *   needs no sign-in (ScxytRouter).
 * - A SoundCloud link plays through the server when signed in. Signed out,
 *   SoundCloud is off, so the same song is looked for on YouTube by its
 *   artist and title, and the best match plays instead.
 * - A catalog song no service here has is looked for on YouTube the same
 *   way. An album is not: YouTube here is songs only.
 *
 * Two calls are all it needs from outside, whether there is a sign-in and a
 * YouTube search (empty when it fails), so it is held without a network.
 */
class SharedSongResolver(
    private val signedIn: suspend () -> Boolean,
    private val searchYoutube: suspend (String) -> List<UnifiedTrack>,
) {
    /** [link]'s YouTube or SoundCloud song; null when it cannot play here. */
    suspend fun resolve(link: SharedLink): SharedLinkResolver.Resolved.Song? {
        val track = trackOf(link) ?: return null
        if (link.source == SharedLink.Source.SOUNDCLOUD && !signedIn()) return onYoutube(link)
        return SharedLinkResolver.Resolved.Song(track)
    }

    /** [link]'s song as YouTube has it, found by artist and title; null for an album or no match. */
    suspend fun onYoutube(link: SharedLink): SharedLinkResolver.Resolved.Song? {
        if (link.album != null && link.track == null) return null
        val title = link.title ?: return null
        val artist = link.artist
        // "Daft Punk - One More Time" by Daft Punk is searched for once by name.
        val query = if (artist == null || title.startsWith(artist, ignoreCase = true)) title else "$artist $title"
        val best = bestMatch(title, artist, link.durationSec, searchYoutube(query)) ?: return null
        return SharedLinkResolver.Resolved.Song(best, substitute = true)
    }

    companion object {
        /**
         * Lengths further apart than this are another song. Looser than a
         * party's match: YouTube's copy is often a video's sound, with a few
         * seconds more or less than the record.
         */
        const val LENGTH_SLACK_SEC = 10

        /**
         * A YouTube or SoundCloud link's song as the app plays it, named as
         * the link names it; null for a link that is neither.
         */
        fun trackOf(link: SharedLink): UnifiedTrack? {
            val mediaId = link.mediaId ?: return null
            return ScxytTrack(
                id = mediaId,
                title = link.title.orEmpty(),
                artist = link.artist.orEmpty(),
                duration = (link.durationSec ?: 0).toDouble(),
                // The picture the website shows for it too: it needs no
                // signature and does not expire.
                cover = if (link.source == SharedLink.Source.YOUTUBE) "https://i.ytimg.com/vi/${link.song}/hqdefault.jpg" else null,
                url = link.page,
            ).toUnifiedTrack()
        }

        /**
         * Of [found], the song [title] by [artist], about [durationSec] long:
         * the same title, by the same artist, within [LENGTH_SLACK_SEC]; the
         * nearest in length, else the first YouTube ranked.
         *
         * Titles compare as a party compares them (the same letters, one
         * maybe with a version on), less an "Artist - " in front, as
         * SoundCloud uploads and YouTube videos often name a song. The artist
         * may be the uploader or, for a song a channel reposted, named in
         * the title instead.
         */
        fun bestMatch(title: String, artist: String?, durationSec: Int?, found: List<UnifiedTrack>): UnifiedTrack? {
            fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }
            fun bare(name: String, by: String) =
                if (by.isNotEmpty() && name.length > by.length && name.startsWith(by)) name.removePrefix(by) else name
            val wantTitle = norm(title)
            val wantArtist = norm(artist)
            val wantSec = durationSec ?: 0
            val matches = found.filter { candidate ->
                val by = norm(candidate.artistName)
                val got = bare(norm(candidate.title), by)
                val sameTitle = PartyMatch.sameTitle(got, bare(wantTitle, wantArtist)) || PartyMatch.sameTitle(got, bare(wantTitle, by))
                val sameArtist = wantArtist.isEmpty() ||
                    (by.isNotEmpty() && (by.contains(wantArtist) || wantArtist.contains(by) || wantTitle.startsWith(by)))
                val sameLength = wantSec <= 0 || candidate.durationSeconds <= 0 ||
                    abs(candidate.durationSeconds - wantSec) <= LENGTH_SLACK_SEC
                sameTitle && sameArtist && sameLength
            }
            if (wantSec <= 0) return matches.firstOrNull()
            return matches.minByOrNull { if (it.durationSeconds > 0) abs(it.durationSeconds - wantSec) else LENGTH_SLACK_SEC + 1 }
        }
    }
}
