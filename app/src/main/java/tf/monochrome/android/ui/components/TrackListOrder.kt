package tf.monochrome.android.ui.components

import tf.monochrome.android.domain.model.Track

/**
 * Ways a list of catalogue [Track]s can be ordered.
 *
 * Separate from `LibrarySort`, which orders the local library's `UnifiedTrack`
 * and sorts on fields only a file on disk has — a modified date, an extension.
 * A streamed track has neither, so offering those keys here would give a menu
 * entry that quietly does nothing.
 *
 * [ORIGINAL] is deliberately first and the default everywhere: a playlist's
 * running order, an album's track order and a search's relevance ranking are
 * all decisions someone already made, and re-sorting should be something the
 * user asks for rather than something a list does on arrival.
 */
enum class TrackOrder(val label: String) {
    ORIGINAL("Original order"),
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    DURATION("Duration"),
}

/** A chosen order and its direction. */
data class TrackSort(
    val order: TrackOrder = TrackOrder.ORIGINAL,
    val ascending: Boolean = true,
)

/**
 * Keys worth offering for an arbitrary catalogue list.
 *
 * A list whose rows carry no album (a single album's own track list) or no
 * artist variation should pass its own subset rather than show a key that can
 * only produce one grouping.
 */
val DEFAULT_TRACK_ORDERS: List<TrackOrder> = TrackOrder.entries

fun List<Track>.applySort(sort: TrackSort): List<Track> {
    if (sort.order == TrackOrder.ORIGINAL) {
        return if (sort.ascending) this else asReversed()
    }
    val comparator: Comparator<Track> = when (sort.order) {
        // Case-insensitive so "abba" doesn't sort after "Zappa", which is what
        // a plain string compare does and what users read as broken.
        TrackOrder.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        TrackOrder.ARTIST -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayArtist }
        TrackOrder.ALBUM -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.album?.title.orEmpty() }
        TrackOrder.DURATION -> compareBy { it.duration }
        TrackOrder.ORIGINAL -> return this
    }
    // Title breaks every tie, so an album sort inside one album stays stable and
    // readable rather than falling back to whatever order the list arrived in.
    val full = comparator.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
    return if (sort.ascending) sortedWith(full) else sortedWith(full.reversed())
}

/**
 * Filters by a free-text query across the fields a person would type.
 *
 * Blank query returns the list untouched — including its identity, so a list
 * that isn't being searched costs nothing.
 */
fun List<Track>.applyQuery(query: String): List<Track> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { track ->
        track.title.contains(q, ignoreCase = true) ||
            track.displayArtist.contains(q, ignoreCase = true) ||
            track.album?.title?.contains(q, ignoreCase = true) == true
    }
}

/** Search then sort, in that order — sorting a filtered list is the cheaper way round. */
fun List<Track>.applySearchAndSort(query: String, sort: TrackSort): List<Track> =
    applyQuery(query).applySort(sort)

/**
 * Saves a [TrackSort] across configuration changes and process death.
 *
 * A list you have re-ordered and then rotated should still be re-ordered;
 * without this it silently snaps back to the playlist's own order and looks
 * like the control stopped working.
 */
val TrackSortSaver: androidx.compose.runtime.saveable.Saver<TrackSort, List<Any>> =
    androidx.compose.runtime.saveable.Saver(
        save = { listOf(it.order.name, it.ascending) },
        restore = { saved ->
            val order = runCatching { TrackOrder.valueOf(saved[0] as String) }
                .getOrDefault(TrackOrder.ORIGINAL)
            TrackSort(order, saved[1] as Boolean)
        },
    )
