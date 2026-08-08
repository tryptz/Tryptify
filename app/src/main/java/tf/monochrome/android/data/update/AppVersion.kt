package tf.monochrome.android.data.update

/**
 * Comparison of release version strings, for deciding whether what's on GitHub
 * is newer than what's installed.
 *
 * Tolerant of how tags actually get written: a leading `v`, a trailing
 * pre-release suffix, and any number of numeric parts. Missing parts count as
 * zero, so `1.9` and `1.9.0` are the same release.
 *
 * Kept free of Android types so the ordering can be unit tested — this decides
 * whether every user gets nagged, and an off-by-one here either nags forever or
 * never mentions an update at all.
 */
object AppVersion {

    /**
     * Negative when [a] is older than [b], zero when equal, positive when newer.
     *
     * A pre-release sorts *before* the same numeric version — `1.9.0-beta1` is
     * older than `1.9.0` — so shipping a stable build to someone on its beta
     * still reads as an update.
     */
    fun compare(a: String, b: String): Int {
        val (numsA, preA) = parse(a)
        val (numsB, preB) = parse(b)
        val size = maxOf(numsA.size, numsB.size)
        for (i in 0 until size) {
            val x = numsA.getOrElse(i) { 0 }
            val y = numsB.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        // Same numbers: whichever lacks a pre-release suffix is the real one.
        return when {
            preA.isEmpty() && preB.isEmpty() -> 0
            preA.isEmpty() -> 1
            preB.isEmpty() -> -1
            else -> preA.compareTo(preB)
        }
    }

    /** True when [candidate] is a strictly newer release than [installed]. */
    fun isNewer(candidate: String, installed: String): Boolean {
        if (candidate.isBlank() || installed.isBlank()) return false
        // A tag that carries no digits at all isn't a version; treating it as
        // 0.0.0 would silently compare equal to another junk tag.
        if (parse(candidate).first.isEmpty()) return false
        return compare(candidate, installed) > 0
    }

    /** Numeric parts plus any pre-release suffix, from a tag like `v1.8.4-rc1`. */
    private fun parse(raw: String): Pair<List<Int>, String> {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        val core = trimmed.substringBefore('-').substringBefore('+')
        val pre = trimmed.substringAfter('-', "").substringBefore('+')
        val nums = core.split('.')
            .map { part -> part.takeWhile { it.isDigit() } }
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull() }
        return nums to pre
    }
}
