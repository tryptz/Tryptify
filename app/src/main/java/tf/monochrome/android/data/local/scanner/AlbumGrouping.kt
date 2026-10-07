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
 *    album's tracks in its folder;
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

        val albumArtistInFolder: Map<Pair<String, String>, String> = tracks.indices
            .filter { titled[it] != null && !tracks[it].albumArtist.isNullOrBlank() }
            .groupBy { tracks[it].folder to titled[it]!! }
            .mapValues { (_, members) -> mostCommon(members.map { tracks[it].albumArtist!! }) }
        val artists = tracks.indices.map { i ->
            tracks[i].albumArtist?.takeIf(String::isNotBlank)
                ?: titled[i]?.let { albumArtistInFolder[tracks[i].folder to it] }
                ?: tracks[i].artist
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

    /** The value seen most often; on a tie, the one seen first. */
    private fun <T> mostCommon(values: List<T>): T =
        values.groupingBy { it }.eachCount().maxBy { it.value }.key
}
