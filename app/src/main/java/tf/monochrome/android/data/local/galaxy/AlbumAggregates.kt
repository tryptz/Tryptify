package tf.monochrome.android.data.local.galaxy

import tf.monochrome.android.data.local.db.LocalTrackEntity
import kotlin.math.log10
import kotlin.math.pow

/**
 * What an album's tracks add up to, for its planet: its tempo, its key, how
 * loud it was mastered and how much dynamic range it kept. Built from the rows
 * the repository already holds, so it costs no query, and pure, so it is tested
 * without a database.
 *
 * Every value is null when no track has it: an album with no tempo tags has no
 * tempo, rather than a guessed one.
 */
object AlbumAggregates {

    class Result(
        /** The median tempo of the tracks that have one. */
        val bpm: Float?,
        /** The key most of its tracks are in; see [commonestKey]. */
        val camelotKey: Int?,
        /** Its loudness, LUFS; see [lufs]. */
        val lufs: Float?,
        /** The median dynamic range of the tracks measured, dB. */
        val dynamicRange: Float?,
    )

    fun of(tracks: List<LocalTrackEntity>): Result = Result(
        bpm = median(tracks.mapNotNull { it.bpm }),
        camelotKey = commonestKey(tracks),
        lufs = lufs(tracks),
        dynamicRange = median(tracks.mapNotNull { it.dynamicRange }),
    )

    /** The middle value, or the mean of the middle two; null for none. */
    fun median(values: List<Float>): Float? {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return null
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    /**
     * The key the most tracks are in. A tie goes to the key with more running
     * time, then to the lower code, so the same album always gets the same key.
     */
    fun commonestKey(tracks: List<LocalTrackEntity>): Int? =
        tracks.filter { it.camelotKey != null }
            .groupBy { it.camelotKey!! }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<Int, List<LocalTrackEntity>>> { it.value.size }
                    .thenByDescending { e -> e.value.sumOf { it.durationSeconds.coerceAtLeast(0) } }
                    .thenBy { it.key },
            )
            .firstOrNull()?.key

    /**
     * How loud the album was mastered, LUFS. Its album gain says so directly:
     * ReplayGain's is measured against -18 LUFS, R128's (Opus) against -23, in
     * 256ths of a dB. Without one, its tracks' own loudness is averaged as
     * sound energy, weighted by length, which is how one long loud track and
     * a few quiet short ones add up.
     */
    fun lufs(tracks: List<LocalTrackEntity>): Float? {
        median(tracks.mapNotNull { it.rgAlbumGain })?.let { return RG_REFERENCE - it }
        median(tracks.mapNotNull { it.r128AlbumGain?.toFloat() })?.let { return R128_REFERENCE - it / Q78 }
        var energy = 0.0
        var weight = 0.0
        for (t in tracks) {
            val loudness = trackLufs(t) ?: continue
            val w = t.durationSeconds.coerceAtLeast(1).toDouble()
            energy += w * 10.0.pow(loudness / 10.0)
            weight += w
        }
        if (weight == 0.0) return null
        return (10.0 * log10(energy / weight)).toFloat()
    }

    /** One track's loudness, LUFS, from its own ReplayGain or R128 track gain. */
    fun trackLufs(t: LocalTrackEntity): Float? =
        t.rgTrackGain?.let { RG_REFERENCE - it } ?: t.r128TrackGain?.let { R128_REFERENCE - it / Q78 }

    /** The loudness ReplayGain 2 brings a track to, and so measures its gain against. */
    const val RG_REFERENCE = -18f

    /** The loudness an Opus R128 gain brings a track to (EBU R128's target). */
    const val R128_REFERENCE = -23f

    /** R128 gains are Q7.8 fixed point: 256 steps per dB. */
    private const val Q78 = 256f
}
