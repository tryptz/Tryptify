package tf.monochrome.android.data.local.scanner

/** A track the library knew at [filePath] whose file is no longer there. */
data class DepartedTrack(
    val id: Long,
    val filePath: String,
    val fileSizeBytes: Long,
    val lastModified: Long,
    val dynamicRange: Float?,
)

/** A file the library has nothing for yet. */
data class ArrivedFile(val path: String, val sizeBytes: Long, val modifiedMs: Long)

/**
 * Tells a moved file from a deleted one and a new one.
 *
 * A track was its path: move the music folder, or point the library at
 * another one, and every song was deleted and added again as a stranger, with
 * a new row number. What hangs on that number went with it: the song's place
 * in likes and playlists (found again only by its tags), its play history,
 * its Trypt Mania charts and scores, its timed lyrics. What hangs on the path
 * went too: the listener's tag corrections, a cover found for it, its
 * measured dynamic range.
 *
 * A file that left one path and one that turned up at another are the same
 * file when they are the same size to the byte and either carry the same
 * name or were last changed in the same second. Moving or copying keeps all
 * three; two different recordings do not share a size and a name by chance.
 */
object TrackMoves {

    /**
     * Each arrival that is one of [departed] come back, by its new path. A
     * departed track is matched once, to its best arrival: same name and
     * date, then same name, then same date; ties go to the first path in
     * order, so the answer does not depend on the order files were listed.
     */
    fun match(departed: List<DepartedTrack>, arrived: List<ArrivedFile>): Map<String, DepartedTrack> {
        if (departed.isEmpty() || arrived.isEmpty()) return emptyMap()
        val bySize = arrived.filter { it.sizeBytes > 0 }.sortedBy { it.path }.groupByTo(HashMap()) { it.sizeBytes }
        val taken = HashSet<String>()
        val moves = LinkedHashMap<String, DepartedTrack>()
        // Three passes over everything, strongest evidence first, so a weak
        // match never takes the file a stronger one was about to claim.
        for (rank in 0..2) {
            for (gone in departed.sortedBy { it.filePath }) {
                if (moves.values.any { it.id == gone.id }) continue
                val candidates = bySize[gone.fileSizeBytes] ?: continue
                val name = nameOf(gone.filePath)
                val hit = candidates.firstOrNull { file ->
                    if (file.path in taken) return@firstOrNull false
                    val sameName = nameOf(file.path).equals(name, ignoreCase = true)
                    val sameDate = file.modifiedMs / 1000 == gone.lastModified / 1000
                    when (rank) {
                        0 -> sameName && sameDate
                        1 -> sameName
                        else -> sameDate
                    }
                } ?: continue
                taken += hit.path
                moves[hit.path] = gone
            }
        }
        return moves
    }

    private fun nameOf(path: String): String = path.substringAfterLast('/')

    /**
     * The storage volume [path] is on: "/storage/emulated/0" for the phone's
     * own, "/storage/1A2B-3C4D" for a card; null for a path that is on
     * neither.
     */
    fun volumeRootOf(path: String): String? {
        val parts = path.split('/')
        if (parts.size < 3 || parts[0].isNotEmpty() || parts[1] != "storage") return null
        return when {
            parts[2] == "emulated" -> parts.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { "/storage/emulated/$it" }
            parts[2].isNotEmpty() -> "/storage/${parts[2]}"
            else -> null
        }
    }

    /**
     * [missing] without the tracks whose whole volume is not there right now
     * ([volumeThere] false): a memory card taken out, not files deleted. Their
     * rows stay, and play again when the card is back.
     */
    fun withoutOffline(missing: List<String>, volumeThere: (String) -> Boolean): List<String> {
        val there = HashMap<String, Boolean>()
        return missing.filter { path ->
            val volume = volumeRootOf(path) ?: return@filter true
            there.getOrPut(volume) { volumeThere(volume) }
        }
    }
}
