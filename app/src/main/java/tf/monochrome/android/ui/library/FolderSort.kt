package tf.monochrome.android.ui.library

import androidx.annotation.StringRes
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.UnifiedTrack

/**
 * How the songs in a folder are ordered.
 *
 * [ORIGINAL] is the order the music was made in: album, then disc, then track
 * number, then file name. In a folder that is one album that is just its track
 * order; in one that holds several (Download/), each album stays together in
 * its own order instead of every album's track 1 coming first. A file with no
 * track number falls back to its file name, which is how an untagged live
 * recording ("01 - Intro.flac", "02 - …") keeps its running order: sorted by
 * title it played alphabetically, the complaint that started this.
 *
 * [ALBUM] looks like [ORIGINAL] going up, but not coming down: [ORIGINAL]
 * reversed plays every album backwards, while [ALBUM] reversed takes the
 * albums Z to A and still plays each from its first track. The folder
 * screen's old toolbar had it, so leaving it out took a sort away.
 */
enum class FolderTrackOrder(
    @StringRes val label: Int,
    /** The direction a key starts in when picked: newest, largest and best first. */
    val firstAscending: Boolean = true,
) {
    ORIGINAL(R.string.sort_original),
    FILE_NAME(R.string.sort_file_name),
    TITLE(R.string.sort_title),
    ARTIST(R.string.sort_artist),
    ALBUM(R.string.sort_album),
    YEAR(R.string.sort_year),
    DURATION(R.string.sort_duration),
    DATE_MODIFIED(R.string.sort_date_modified, firstAscending = false),
    FILE_TYPE(R.string.sort_file_type),
    QUALITY(R.string.sort_quality, firstAscending = false),
    FILE_SIZE(R.string.sort_file_size, firstAscending = false),
}

/** How the folders inside a folder are ordered. */
enum class SubfolderOrder(
    @StringRes val label: Int,
    /** The direction a key starts in when picked: most songs and newest first. */
    val firstAscending: Boolean = true,
) {
    NAME(R.string.sort_name),
    SONG_COUNT(R.string.sort_song_count, firstAscending = false),
    /** The newest file anywhere inside: a folder you just added music to comes first. */
    DATE_MODIFIED(R.string.sort_date_modified, firstAscending = false),
}

/**
 * The one sort every folder uses, saved across restarts: songs and folders
 * each have a key and a direction.
 */
data class FolderSort(
    val tracks: FolderTrackOrder = FolderTrackOrder.ORIGINAL,
    val tracksAscending: Boolean = true,
    val folders: SubfolderOrder = SubfolderOrder.NAME,
    val foldersAscending: Boolean = true,
) {
    /** For the preference: "ORIGINAL:1:NAME:1". */
    fun encode(): String =
        "${tracks.name}:${if (tracksAscending) 1 else 0}:${folders.name}:${if (foldersAscending) 1 else 0}"

    companion object {
        /** [encode] read back; anything unreadable, a key renamed included, is the default. */
        fun decode(raw: String?): FolderSort {
            val parts = raw?.split(':') ?: return FolderSort()
            if (parts.size != 4) return FolderSort()
            return FolderSort(
                tracks = FolderTrackOrder.entries.firstOrNull { it.name == parts[0] } ?: FolderTrackOrder.ORIGINAL,
                tracksAscending = parts[1] != "0",
                folders = SubfolderOrder.entries.firstOrNull { it.name == parts[2] } ?: SubfolderOrder.NAME,
                foldersAscending = parts[3] != "0",
            )
        }
    }
}

/**
 * Text in the order people read it: numbers by value, so "Track 2" comes
 * before "Track 10" and "CD2" before "CD10", and letters without regard to
 * case. A plain string compare puts "10" before "2", which in a folder of
 * numbered files is exactly the wrong running order.
 *
 * Allocation-free, so it can sit inside a sort's comparator.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigitAscii() && b[j].isDigitAscii()) {
                val endA = digitRunEnd(a, i)
                val endB = digitRunEnd(b, j)
                // Compare by value: skip leading zeros, then the longer run is
                // the larger number, then digit by digit. No parse, so a run
                // longer than a Long still compares correctly.
                val startA = skipZeros(a, i, endA)
                val startB = skipZeros(b, j, endB)
                val lengthA = endA - startA
                val lengthB = endB - startB
                if (lengthA != lengthB) return lengthA.compareTo(lengthB)
                for (k in 0 until lengthA) {
                    val d = a[startA + k].compareTo(b[startB + k])
                    if (d != 0) return d
                }
                i = endA
                j = endB
            } else {
                val ca = a[i].lowercaseChar()
                val cb = b[j].lowercaseChar()
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        val rest = (a.length - i).compareTo(b.length - j)
        if (rest != 0) return rest
        // Equal as read ("a01" and "a1", "A" and "a"): any fixed order beats
        // one that depends on the order the list arrived in.
        return a.compareTo(b)
    }

    private fun Char.isDigitAscii() = this in '0'..'9'

    private fun digitRunEnd(s: String, from: Int): Int {
        var k = from
        while (k < s.length && s[k].isDigitAscii()) k++
        return k
    }

    private fun skipZeros(s: String, from: Int, end: Int): Int {
        var k = from
        while (k < end - 1 && s[k] == '0') k++
        return k
    }
}

/** The file a local track plays from, or null for one that has none. */
internal val UnifiedTrack.filePath: String?
    get() = (source as? PlaybackSource.LocalFile)?.filePath

/**
 * A track with its sort keys read once. A comparator's selectors run on every
 * comparison, O(n log n) times; a file name cut from the path each time would
 * allocate that often.
 */
private class Keyed(val track: UnifiedTrack) {
    val fileName: String = track.filePath?.substringAfterLast('/') ?: track.title
}

private fun <K> keyOrder(
    ascending: Boolean,
    comparator: Comparator<in K>,
    key: (Keyed) -> K?,
): Comparator<Keyed> = Comparator { a, b ->
    val ka = key(a)
    val kb = key(b)
    when {
        ka == null && kb == null -> 0
        // A song without the value goes last either way: "no year" is not
        // the oldest year, nor the newest.
        ka == null -> 1
        kb == null -> -1
        ascending -> comparator.compare(ka, kb)
        else -> comparator.compare(kb, ka)
    }
}

private val byFileName: Comparator<Keyed> = Comparator { a, b -> NaturalOrder.compare(a.fileName, b.fileName) }

private fun byAlbum(ascending: Boolean): Comparator<Keyed> =
    keyOrder(ascending, NaturalOrder) { it.track.albumTitle?.takeIf(String::isNotBlank) }

/** Within one album: disc, then track, then file name. */
private val runningOrder: Comparator<Keyed> =
    keyOrder(true, naturalOrder<Int>()) { it.track.discNumber ?: 1 }
        .then(keyOrder(true, naturalOrder<Int>()) { it.track.trackNumber?.takeIf { n -> n > 0 } })
        .then(byFileName)

private val originalOrder: Comparator<Keyed> = byAlbum(true).then(runningOrder)

/** Quality, best last: bit depth (lossy counts as none), then sample rate, then bitrate. */
private val byQuality: Comparator<UnifiedTrack> =
    compareBy<UnifiedTrack>({ it.bitDepth ?: 0 }, { it.sampleRate ?: 0 }, { it.bitRate ?: 0 })

/**
 * [tracks] in [order]. Only the chosen key follows [ascending]; whatever
 * breaks its ties stays in reading order, so the newest-first songs of one
 * day are still in track order, and a year sorted descending keeps each
 * album's running order.
 */
fun sortFolderTracks(tracks: List<UnifiedTrack>, order: FolderTrackOrder, ascending: Boolean): List<UnifiedTrack> {
    if (tracks.size < 2) return tracks
    val comparator: Comparator<Keyed> = when (order) {
        FolderTrackOrder.ORIGINAL -> if (ascending) originalOrder else originalOrder.reversed()
        FolderTrackOrder.FILE_NAME -> if (ascending) byFileName else byFileName.reversed()
        FolderTrackOrder.TITLE -> keyOrder(ascending, NaturalOrder) { it.track.title }.then(byFileName)
        FolderTrackOrder.ARTIST ->
            keyOrder(ascending, NaturalOrder) { it.track.artistName.takeIf(String::isNotBlank) }.then(originalOrder)
        FolderTrackOrder.ALBUM -> byAlbum(ascending).then(runningOrder)
        FolderTrackOrder.YEAR -> keyOrder(ascending, naturalOrder<Int>()) { it.track.releaseYear }.then(originalOrder)
        FolderTrackOrder.DURATION -> keyOrder(ascending, naturalOrder<Int>()) { it.track.durationSeconds }.then(byFileName)
        FolderTrackOrder.DATE_MODIFIED -> keyOrder(ascending, naturalOrder<Long>()) { it.track.dateModified }.then(byFileName)
        FolderTrackOrder.FILE_TYPE -> keyOrder(ascending, NaturalOrder) { it.track.codec?.name }.then(originalOrder)
        FolderTrackOrder.QUALITY -> keyOrder(ascending, byQuality) { it.track }.then(originalOrder)
        FolderTrackOrder.FILE_SIZE -> keyOrder(ascending, naturalOrder<Long>()) { it.track.fileSizeBytes }.then(byFileName)
    }
    return tracks.map(::Keyed).sortedWith(comparator).map { it.track }
}

/**
 * [folders] in [order]. As with songs, only the chosen key follows
 * [ascending]: folders it ties on stay in name order.
 */
fun <T> sortFolders(
    folders: List<T>,
    order: SubfolderOrder,
    ascending: Boolean,
    name: (T) -> String,
    songCount: (T) -> Int,
    newest: (T) -> Long?,
): List<T> {
    if (folders.size < 2) return folders
    val byName = Comparator<T> { a, b -> NaturalOrder.compare(name(a), name(b)) }
    val comparator: Comparator<T> = when (order) {
        SubfolderOrder.NAME -> if (ascending) byName else byName.reversed()
        SubfolderOrder.SONG_COUNT -> {
            val counts = if (ascending) compareBy(songCount) else compareByDescending(songCount)
            counts.then(byName)
        }
        SubfolderOrder.DATE_MODIFIED -> Comparator<T> { a, b ->
            val na = newest(a)
            val nb = newest(b)
            when {
                na == null && nb == null -> 0
                na == null -> 1
                nb == null -> -1
                ascending -> na.compareTo(nb)
                else -> nb.compareTo(na)
            }
        }.then(byName)
    }
    return folders.sortedWith(comparator)
}

/**
 * Every song under [rootPath], in the order the folder screen shows them:
 * each folder's own songs first, in the song sort, then the folders inside
 * it, in the folder sort, each the same way down. "Play" on an artist's
 * folder then plays album after album, each in its running order.
 *
 * Built from the songs alone: a folder's song count and newest file are
 * those of everything under it, so no folder rows are needed.
 */
fun folderPlayOrder(rootPath: String, tracks: List<UnifiedTrack>, sort: FolderSort): List<UnifiedTrack> {
    val root = rootPath.trimEnd('/')
    val own = HashMap<String, MutableList<UnifiedTrack>>()
    val children = HashMap<String, MutableSet<String>>()
    for (track in tracks) {
        val dir = track.filePath?.substringBeforeLast('/', missingDelimiterValue = "")
            ?.takeIf { it == root || it.startsWith("$root/") }
            ?: root
        own.getOrPut(dir) { mutableListOf() } += track
        // Link every folder between this one and the root, so a folder that
        // holds only folders is still walked.
        var child = dir
        while (child != root) {
            val parent = child.substringBeforeLast('/')
            if (!children.getOrPut(parent) { mutableSetOf() }.add(child)) break
            child = parent
        }
    }

    val count = HashMap<String, Int>()
    val newest = HashMap<String, Long?>()
    fun measure(dir: String) {
        var n = own[dir]?.size ?: 0
        var latest: Long? = own[dir]?.mapNotNull { it.dateModified }?.maxOrNull()
        for (sub in children[dir].orEmpty()) {
            measure(sub)
            n += count.getValue(sub)
            val subLatest = newest[sub]
            if (subLatest != null && (latest == null || subLatest > latest)) latest = subLatest
        }
        count[dir] = n
        newest[dir] = latest
    }
    measure(root)

    val out = ArrayList<UnifiedTrack>(tracks.size)
    fun walk(dir: String) {
        out += sortFolderTracks(own[dir].orEmpty(), sort.tracks, sort.tracksAscending)
        val subs = sortFolders(
            children[dir].orEmpty().toList(), sort.folders, sort.foldersAscending,
            name = { it.substringAfterLast('/') },
            songCount = { count.getValue(it) },
            newest = { newest[it] },
        )
        subs.forEach(::walk)
    }
    walk(root)
    return out
}

/** One tappable step of the folder path above a folder screen. */
data class FolderCrumb(val name: String, val path: String)

/**
 * The path from the folder the browser starts at down to [folderPath], one
 * crumb per folder: "Music › Swans › 2024 Live", not
 * "storage › emulated › 0 › Music…". [roots] are the folders the Folders tab
 * opens on; the deepest one [folderPath] is inside is where the crumbs start.
 * Outside all of them, they start below [deviceRoot] (the phone's own
 * storage), or at the top of the path.
 */
fun folderCrumbs(folderPath: String, roots: List<FolderCrumb>, deviceRoot: String?): List<FolderCrumb> {
    val path = folderPath.trimEnd('/')
    val start = roots
        .filter { path == it.path.trimEnd('/') || path.startsWith(it.path.trimEnd('/') + "/") }
        .maxByOrNull { it.path.trimEnd('/').length }
    val crumbs = mutableListOf<FolderCrumb>()
    val base: String
    if (start != null) {
        base = start.path.trimEnd('/')
        crumbs += FolderCrumb(start.name, base)
    } else {
        val device = deviceRoot?.trimEnd('/')
        base = if (device != null && path.startsWith("$device/")) device else ""
    }
    var built = base
    path.removePrefix(base).trim('/').split('/').filter { it.isNotEmpty() }.forEach { part ->
        built = "$built/$part"
        crumbs += FolderCrumb(part, built)
    }
    return crumbs
}
