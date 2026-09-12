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
