package tf.monochrome.android.data.local.scanner

import tf.monochrome.android.data.local.scanner.MediaScanner.Companion.buildAlbumGroupingKey
import tf.monochrome.android.data.local.scanner.MediaScanner.Companion.normalizeText

/**
 * Which album each local track belongs to.
 *
 * The key is album title + album artist (else the track's own artist) +
 * year, as [buildAlbumGroupingKey] makes it. Keyed on the bare tags, a track
 * missing one its album's other tracks have was split off into an album of
 * its own: a song downloaded as a single, next to the album it is from,
 * carries no album artist and no year, so "1" by The Beatles came out twice.
 * Now such a track is not split off for that alone:
 *  - without an album artist, it takes the one named by the same-titled
 *    album's tracks in its folder, if its own artist credits that album
 *    artist or one of those tracks' artists ("The Beatles, Billy Preston"
 *    credits The Beatles). A flat folder holds many artists, and ABBA's
 *    "Greatest Hits" track is not Queen's because the titles match;
 *  - without a year, it takes the year of the album with its title and
 *    artist (the most common one, if the album's tracks give several).
 * Different releases still stay apart: the same title in two years, under
 * two album artists, or by two artists in two folders without one. Tracks
 * with no album tag keep their key: they are not an album together.
 *
 * Kept free of Android and Room types so it can be unit tested.
 */
object AlbumGrouping {

    /** The tags a track is grouped by, and the folder it is in. */
    data class Facts(
        val album: String?,
        val albumArtist: String?,
        val artist: String?,
        val year: Int?,
        val folder: String,
    )

    /** The album grouping key of each of [tracks], in the same order. */
    fun keys(tracks: List<Facts>): List<String> {
        val titled = tracks.map { it.album?.takeIf(String::isNotBlank)?.let(::normalizeText) }

        val albumInFolder: Map<Pair<String, String>, FolderAlbum> = tracks.indices
            .filter { titled[it] != null && !tracks[it].albumArtist.isNullOrBlank() }
            .groupBy { tracks[it].folder to titled[it]!! }
            .mapValues { (_, members) ->
                FolderAlbum(
                    albumArtist = mostCommon(members.map { tracks[it].albumArtist!! }),
                    names = members
                        .flatMap { listOfNotNull(tracks[it].albumArtist, tracks[it].artist) }
                        .map(::normalizeText)
                        .filter(String::isNotEmpty)
                        .toSet(),
                )
            }
        val artists = tracks.indices.map { i ->
            val own = tracks[i].artist
            val ownKey = own?.takeIf(String::isNotBlank)?.let(::normalizeText)
            tracks[i].albumArtist?.takeIf(String::isNotBlank)
                ?: titled[i]?.let { albumInFolder[tracks[i].folder to it] }
                    ?.takeIf { album -> ownKey == null || album.names.any { credits(ownKey, it) } }
                    ?.albumArtist
                ?: own
        }
        val artistKeys = artists.map { normalizeText(it ?: "unknown") }

        val yearOfAlbum: Map<Pair<String, String>, Int> = tracks.indices
            .filter { titled[it] != null && tracks[it].year != null }
            .groupBy { titled[it]!! to artistKeys[it] }
            .mapValues { (_, members) -> mostCommon(members.map { tracks[it].year!! }) }

        return tracks.indices.map { i ->
            val year = tracks[i].year ?: titled[i]?.let { yearOfAlbum[it to artistKeys[i]] }
            buildAlbumGroupingKey(tracks[i].album, artists[i], year)
        }
    }

    /** An album in a folder: its album artist, and every artist its tracks name. */
    private class FolderAlbum(val albumArtist: String, val names: Set<String>)

    /**
     * Whether artist tag [credit] names [name] as a whole word or words,
     * alone or among others: "the beatles, billy preston" credits "the
     * beatles", and "ravel" does not credit "ra". Both already normalized.
     */
    private fun credits(credit: String, name: String): Boolean {
        var from = 0
        while (true) {
            val at = credit.indexOf(name, from)
            if (at < 0) return false
            val end = at + name.length
            val startsWord = at == 0 || !credit[at - 1].isLetterOrDigit()
            val endsWord = end == credit.length || !credit[end].isLetterOrDigit()
            if (startsWord && endsWord) return true
            from = at + 1
        }
    }

    /** The value seen most often; on a tie, the one seen first. */
    private fun <T> mostCommon(values: List<T>): T =
        values.groupingBy { it }.eachCount().maxBy { it.value }.key
}
