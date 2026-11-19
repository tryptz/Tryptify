package tf.monochrome.android.ui.library

import tf.monochrome.android.data.db.entity.DownloadedTrackEntity
import tf.monochrome.android.domain.model.buildCoverUrl
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
 * The picture a Downloads row shows, as something the image loader can open.
 *
 * A download's record keeps the cover the catalogue's song carried, and for
 * TIDAL that is a cover id ("ab12cd34-..."), not an address: handed to the
 * image as it was, nothing loaded and every TIDAL download showed the music
 * note (a phone's report of 2026-11-19). [libraryCover] is the cover the local
 * library read out of the same file, a picture on the phone: it comes first,
 * since it needs no network and is the cover the file really has. Without one
 * the record's cover is made into its address ([buildCoverUrl], which leaves
 * an address or a path as it is).
 */
fun downloadCover(recordCover: String?, libraryCover: String?): String? =
    libraryCover?.takeIf { it.isNotBlank() }
        ?: recordCover?.takeIf { it.isNotBlank() }?.let { buildCoverUrl(it, DOWNLOAD_COVER_PX) }

/** The size a record's cover is asked for at: the one the player asks for, so both share one cached picture. */
const val DOWNLOAD_COVER_PX = 640

/**
 * Every download as the screen lists it, newest first: [records], the files
 * this app downloaded, and [scanned], the local library's rows for the
 * download folder. A file in both is the record's row, not a second one, with
 * the cover from [downloadCover]. [pathOf] turns a record's file (a path, or
 * a `content://` address in a picked folder) into the path the library knows
 * it by.
 */
fun mergeDownloads(
    records: List<DownloadedTrackEntity>,
    scanned: List<DownloadedTrackEntity>,
    pathOf: (String) -> String,
): List<DownloadedTrackEntity> {
    val scannedByPath = scanned.associateBy { it.filePath }
    val known = HashSet<String>(records.size)
    val merged = records.map { record ->
        val path = pathOf(record.filePath)
        known += path
        val cover = downloadCover(record.albumCover, scannedByPath[path]?.albumCover)
        if (cover == record.albumCover) record else record.copy(albumCover = cover)
    }
    return (merged + scanned.filter { it.filePath !in known }).sortedByDescending { it.downloadedAt }
}

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
