package tf.monochrome.android.ui.library

import tf.monochrome.android.data.db.entity.DownloadedTrackEntity
import tf.monochrome.android.ui.components.TrackOrder
import tf.monochrome.android.ui.components.TrackSort

/**
 * The keys the Downloads list offers: the catalogue keys plus file size, which
 * only means something here, where every row is a file and shows its size.
 */
val DOWNLOAD_TRACK_ORDERS: List<TrackOrder> = listOf(
    TrackOrder.ORIGINAL,
    TrackOrder.TITLE,
    TrackOrder.ARTIST,
    TrackOrder.ALBUM,
    TrackOrder.DURATION,
    TrackOrder.FILE_SIZE,
)

/**
 * [TrackOrder.ORIGINAL] is the order the list arrives in — newest download
 * first — so reversing it gives oldest first, as on every other list.
 */
fun List<DownloadedTrackEntity>.applyDownloadSort(sort: TrackSort): List<DownloadedTrackEntity> {
    val comparator: Comparator<DownloadedTrackEntity> = when (sort.order) {
        TrackOrder.ORIGINAL -> return if (sort.ascending) this else asReversed()
        TrackOrder.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        TrackOrder.ARTIST -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.artistName }
        TrackOrder.ALBUM -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.albumTitle.orEmpty() }
        TrackOrder.DURATION -> compareBy { it.duration }
        // Ascending is the smallest first, like every other key; the arrow in
        // the menu flips it for "what is taking the space".
        TrackOrder.FILE_SIZE -> compareBy { it.sizeBytes }
    }
    val full = comparator.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
    return if (sort.ascending) sortedWith(full) else sortedWith(full.reversed())
}

/** Title, artist or album containing [query]; a blank query returns the list itself. */
fun List<DownloadedTrackEntity>.applyDownloadQuery(query: String): List<DownloadedTrackEntity> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { track ->
        track.title.contains(q, ignoreCase = true) ||
            track.artistName.contains(q, ignoreCase = true) ||
            track.albumTitle?.contains(q, ignoreCase = true) == true
    }
}

fun List<DownloadedTrackEntity>.applyDownloadSearchAndSort(query: String, sort: TrackSort): List<DownloadedTrackEntity> =
    applyDownloadQuery(query).applyDownloadSort(sort)
