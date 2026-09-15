package tf.monochrome.android.ui.player

/**
 * Which rows of a station list become the queue, and which one starts playing.
 *
 * [keep] is indices into the list handed in, in order. [startIndex] indexes
 * [keep], not the original list.
 */
internal data class RadioQueuePlan(val keep: List<Int>, val startIndex: Int)

/**
 * Turn a city's station list into a queue.
 *
 * Two things have to be true and they pull against each other. The queue must
 * not contain the same id twice — a repeat would fight over one slot in the
 * unified-track registry, the second overwriting the first, and both entries
 * would then play the same stream while the queue claimed to hold two. And the
 * station the listener tapped has to be the one that comes out of the speaker,
 * which is not the same as "the row at [tappedIndex]" once rows have been
 * dropped from in front of it.
 *
 * The station directory is third-party and does not promise unique uuids —
 * `WorldRadioScreen` leaves its list unkeyed for exactly that reason — so this
 * is a real case rather than a defensive one.
 *
 * Duplicates keep their **first** appearance. Two rows with one id are one
 * station listed twice, so the earlier row is as good as the later one, and
 * keeping the first means the queue reads in the order the panel does.
 */
internal fun planRadioQueue(ids: List<String>, tappedIndex: Int): RadioQueuePlan {
    if (ids.isEmpty()) return RadioQueuePlan(emptyList(), 0)

    // Out of range means the caller and the list disagree about what is on
    // screen. Clamping into range plays the nearest real row instead of
    // nothing, which is the better failure for a tap.
    val tapped = tappedIndex.coerceIn(0, ids.lastIndex)

    val keep = mutableListOf<Int>()
    val seen = mutableSetOf<String>()
    ids.forEachIndexed { index, id ->
        if (seen.add(id)) keep += index
    }
    // Not keep.indexOf(tapped): the tapped row may itself be a duplicate that
    // was dropped, and then it is the surviving row with the same id that
    // should play — the same station either way.
    val start = keep.indexOfFirst { ids[it] == ids[tapped] }.coerceAtLeast(0)
    return RadioQueuePlan(keep, start)
}
