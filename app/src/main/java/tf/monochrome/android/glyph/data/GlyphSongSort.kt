// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.data

/**
 * How Trypt Mania's song list and leaderboard are ordered.
 *
 * Each key starts in its natural direction — mapped songs first, A to Z,
 * shortest first, slowest first — and picking it again turns it round, as the
 * app's other sort menus do. Ties fall back to the title, then the artist, so
 * the order never shuffles between two looks at the same list.
 */
enum class GlyphSortKey(val id: String, val label: String) {
    MAPPED("mapped", "Mapped"),
    NAME("name", "Name"),
    DURATION("duration", "Duration"),
    BPM("bpm", "BPM"),
    ;

    companion object {
        fun fromId(id: String?): GlyphSortKey = entries.firstOrNull { it.id == id } ?: MAPPED
    }
}

data class GlyphSongSort(
    val key: GlyphSortKey = GlyphSortKey.MAPPED,
    val ascending: Boolean = true,
) {
    /** The same key turned round, or [next] from its natural direction. */
    fun pick(next: GlyphSortKey): GlyphSongSort =
        if (next == key) copy(ascending = !ascending) else GlyphSongSort(next, ascending = true)

    fun apply(songs: List<GlyphSong>): List<GlyphSong> = songs.sortedWith(comparator())

    fun comparator(): Comparator<GlyphSong> {
        val byName = compareBy<GlyphSong, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.artist }
        val primary: Comparator<GlyphSong> = when (key) {
            // Mapped songs first: the ones that can be played now.
            GlyphSortKey.MAPPED -> compareBy { if (it.hasChart) 0 else 1 }
            GlyphSortKey.NAME -> byName
            GlyphSortKey.DURATION -> compareBy { it.durationSeconds }
            // BPM is only known once a song is mapped. Unknown tempos go
            // last whichever way round, rather than leading a descending list.
            GlyphSortKey.BPM -> Comparator { a, b ->
                val x = a.bpm
                val y = b.bpm
                when {
                    x == null && y == null -> 0
                    x == null -> if (ascending) 1 else -1
                    y == null -> if (ascending) -1 else 1
                    else -> x.compareTo(y)
                }
            }
        }
        val directed = if (ascending) primary else primary.reversed()
        return directed.then(byName)
    }
}
