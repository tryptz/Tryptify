package tf.monochrome.android.data.local.scanner

import tf.monochrome.android.data.local.db.LocalFolderEntity

/**
 * Every folder the given track paths imply, as rows for `local_folders`.
 *
 * This used to key only on `path.substringBeforeLast('/')` — the folder holding
 * each *file* — so a folder that held nothing but other folders was never
 * written down. `getSubfolders()` looks up children by `parentPath`, so a
 * missing link did not hide one row, it severed the tree: everything below an
 * unlisted folder became unreachable in the browser.
 *
 * Two levels was enough to lose a library. For `/Music/Some/Deeper/a.mp3` the
 * only row was `/Music/Some/Deeper`, whose parent `/Music/Some` had no row of
 * its own, so opening `/Music` listed nothing at all and the screen was blank.
 * One level happened to work, which is why it read as "some folders".
 *
 * So ancestors are filled in up to the top of each path.
 *
 * `parentPath` keeps its old shape deliberately: the empty string above a
 * top-level folder, never null. `getRootFolders()` selects on `parentPath IS
 * NULL`, so handing `/storage` a null parent would make it a root and put a row
 * nobody asked for at the head of the Folders tab. Connecting the tree is the
 * fix here; what counts as a root is a separate question.
 *
 * `trackCount` is the whole subtree, not the files sitting directly in the
 * folder. For a folder that only holds other folders the direct count is zero,
 * and a row reading "0 tracks" above a hundred of them is worse than no row at
 * all. Leaves are unaffected: with no subfolders the two counts are the same
 * number, so nothing already on screen changes.
 */
internal fun buildFolderTree(trackPaths: List<String>): List<LocalFolderEntity> {
    val counts = mutableMapOf<String, Int>()

    for (path in trackPaths) {
        var folder = path.substringBeforeLast('/', missingDelimiterValue = "")
        // Walk up, counting this track against every folder above it. The loop
        // ends at "" — the parent of a top-level folder — which is not itself a
        // folder and gets no row.
        while (folder.isNotEmpty()) {
            counts[folder] = (counts[folder] ?: 0) + 1
            folder = folder.substringBeforeLast('/', missingDelimiterValue = "")
        }
    }

    return counts.map { (folderPath, trackCount) ->
        LocalFolderEntity(
            path = folderPath,
            parentPath = folderPath.substringBeforeLast('/', missingDelimiterValue = ""),
            displayName = folderPath.substringAfterLast('/'),
            trackCount = trackCount,
            totalDuration = 0,
        )
    }
}

/**
 * The folders the Folders tab opens on.
 *
 * The tab used to read `getRootFolders()`, which selects `parentPath IS NULL`
 * — and no row ever has that. `parentPath` is `substringBeforeLast('/')`, which
 * for a top-level `/storage` is the empty string, not null. That query has
 * always returned nothing, so the tab showed only the roots a user had added by
 * hand: a folder of 500 songs the scanner had indexed never appeared, while the
 * same songs filled the Songs list.
 *
 * The rule here is the one a person would apply looking at the tree: walk down
 * while there is nothing to choose. `/storage` holds only `emulated`, which
 * holds only `0` — three taps that ask no question — so the roots are `0`'s
 * children: Music, Download, and whatever else has music in it. Descent stops
 * at the first folder that branches or that holds tracks of its own, because
 * past that point the choice is the user's.
 *
 * Returns that level, not the folder above it: a row labelled "0" is not a
 * place anyone recognises.
 */
internal fun folderBrowseRoots(folders: List<LocalFolderEntity>): List<LocalFolderEntity> {
    if (folders.isEmpty()) return emptyList()

    val childrenOf = folders.groupBy { it.parentPath.orEmpty() }
    val known = folders.mapTo(HashSet()) { it.path }
    // The tops: rows whose parent has no row of its own. Normally just
    // "/storage", but a path shape this code has not seen still starts
    // somewhere rather than nowhere.
    var level = folders.filter { it.parentPath.orEmpty() !in known }

    while (level.size == 1) {
        val only = level.single()
        val kids = childrenOf[only.path].orEmpty()
        if (kids.isEmpty()) break
        // trackCount is the whole subtree, so what is left after the children
        // have taken their share is what sits in this folder itself. A folder
        // holding its own music is a destination, not a corridor to walk past.
        if (only.trackCount - kids.sumOf { it.trackCount } > 0) break
        level = kids
    }

    return level.sortedBy { it.displayName.lowercase() }
}

