package tf.monochrome.android.data.scxyt

/**
 * Songs made ready before their turn, by id, kept until their play takes
 * them: a SoundCloud song's signed link, asked of the server while the song
 * before it plays, so the play does not ask again.
 *
 * The server signs a link for six hours. One is handed out only in its first
 * [maxAgeMs], which leaves the play, its seeks and a long pause hours on it.
 * It is taken once: a play that fails on it asks the server afresh rather
 * than being handed the same link again. Only the song next in line is made
 * ready ahead, so a few ([capacity]) is plenty, the oldest going first.
 */
internal class ReadyAhead<T : Any>(
    private val maxAgeMs: Long,
    private val capacity: Int = 4,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Kept<T>(val value: T, val at: Long)

    private val kept = LinkedHashMap<String, Kept<T>>()

    @Synchronized
    fun put(id: String, value: T) {
        kept.remove(id)
        kept[id] = Kept(value, clock())
        while (kept.size > capacity) kept.remove(kept.keys.first())
    }

    /** [id]'s value while it is fresh, left for its play. */
    @Synchronized
    fun peek(id: String): T? = fresh(id)?.value

    /** [id]'s value while it is fresh, handed over once. */
    @Synchronized
    fun take(id: String): T? = fresh(id)?.value?.also { kept.remove(id) }

    private fun fresh(id: String): Kept<T>? {
        val entry = kept[id] ?: return null
        if (clock() - entry.at > maxAgeMs) {
            kept.remove(id)
            return null
        }
        return entry
    }
}
