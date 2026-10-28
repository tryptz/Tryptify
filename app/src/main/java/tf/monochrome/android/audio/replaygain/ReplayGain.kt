// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.replaygain

import tf.monochrome.android.domain.model.UnifiedTrack
import kotlin.math.pow

/** Which of a song's gains evens it out. */
enum class ReplayGainMode {
    /** None: every song plays as it was mastered. */
    OFF,

    /** The song's own gain: every song at the same loudness. */
    TRACK,

    /** The album's gain: albums at the same loudness, a quiet song on one still quiet. */
    ALBUM,

    /** The album's gain while an album plays in order, the song's own otherwise. */
    SMART,
}

/**
 * The listener's choice: the mode, and the loudness, in LUFS, every gain found
 * aims each song at.
 */
data class ReplayGainSettings(
    val mode: ReplayGainMode = ReplayGainMode.OFF,
    val targetLufs: Float = ReplayGain.DEFAULT_TARGET_LUFS,
)

/**
 * A song's gains, in dB relative to ReplayGain's -18 LUFS reference, and its
 * peaks as a fraction of full scale (1.0 = 0 dBFS) when known.
 */
data class SongGain(
    val trackDb: Float? = null,
    val albumDb: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
) {
    val isEmpty: Boolean get() = trackDb == null && albumDb == null
}

/**
 * ReplayGain, worked out: from a song's tags and the listener's choice to the
 * one number [ReplayGainProcessor] multiplies its samples by.
 *
 * It only ever turns a song down, unless its peak is known and leaves room:
 * a boost with no peak to check it against could push the loudest moment past
 * full scale, and on the 16-bit path that is a hard clip. Files' own peak tags
 * are not read yet, so for them that means loud songs come down and quiet
 * ones stay where they are.
 */
object ReplayGain {

    /** R128 gains are relative to -23 LUFS, ReplayGain's to -18: an R128 gain is 5 dB less. */
    const val R128_TO_REPLAYGAIN_DB = 5f

    /** Q7.8: an R128 gain counts 1/256 dB steps. */
    private const val Q78 = 256f

    /** A gain, with the target's shift, past this is a broken tag or a slip, not a choice. */
    const val MAX_DB = 24f

    /** What a ReplayGain tag's gain brings a song to: ReplayGain 2's reference. */
    const val REFERENCE_LUFS = -18f

    /** Where songs land unless the listener picks otherwise: what most streaming services play at. */
    const val DEFAULT_TARGET_LUFS = -14f

    /**
     * What the target setting offers, in LUFS: EBU R128's broadcast level,
     * ReplayGain's own, Apple Music's, the one most streaming services use,
     * and a loud master's. Few songs are louder than -8, and a song is raised
     * only as far as its peak allows, so at -8 most play as they are.
     */
    val TARGET_STEPS = listOf(-23f, -18f, -16f, -14f, -8f)

    /**
     * The target for a listener who chose a pre-amp before there was a target
     * (the setting it replaced): the step nearest the loudness that pre-amp
     * aimed at, the quieter one on a tie.
     */
    fun targetFromPreamp(preampDb: Float): Float {
        val aimed = REFERENCE_LUFS + preampDb
        return TARGET_STEPS.minWith(compareBy<Float> { kotlin.math.abs(it - aimed) }.thenBy { it })
    }

    /** A song's gains from its tags: ReplayGain's when it has them, R128's (Opus) otherwise. */
    fun of(track: UnifiedTrack?): SongGain {
        if (track == null) return SongGain()
        return SongGain(
            trackDb = track.replayGainTrack ?: track.r128TrackGain?.let { it / Q78 + R128_TO_REPLAYGAIN_DB },
            albumDb = track.replayGainAlbum ?: track.r128AlbumGain?.let { it / Q78 + R128_TO_REPLAYGAIN_DB },
        )
    }

    /**
     * True when the playing song sits between songs of its own album in the
     * queue, which is what an album played in order looks like: the queue
     * keeps no note of where it came from.
     */
    fun playingAnAlbum(previousAlbumId: Long?, albumId: Long?, nextAlbumId: Long?): Boolean =
        albumId != null && (albumId == previousAlbumId || albumId == nextAlbumId)

    /**
     * The gain to apply, linear (1.0 = unchanged): the song's gain, which
     * brings it to [REFERENCE_LUFS], moved by the distance from there to the
     * listener's target. 1.0 when off, and for a song with no gain tags: its
     * loudness is not known, so there is nothing to aim from.
     */
    fun linear(gain: SongGain, settings: ReplayGainSettings, inAlbum: Boolean): Float {
        if (settings.mode == ReplayGainMode.OFF || gain.isEmpty) return 1f
        val useAlbum = when (settings.mode) {
            ReplayGainMode.ALBUM -> true
            ReplayGainMode.SMART -> inAlbum
            else -> false
        }
        val db = (if (useAlbum) gain.albumDb ?: gain.trackDb else gain.trackDb ?: gain.albumDb) ?: return 1f
        val peak = if (useAlbum) gain.albumPeak ?: gain.trackPeak else gain.trackPeak ?: gain.albumPeak
        val total = (db + settings.targetLufs - REFERENCE_LUFS).coerceIn(-MAX_DB, MAX_DB)
        val linear = 10f.pow(total / 20f)
        if (linear <= 1f) return linear
        // A boost: only as far as the peak allows, and not at all without one.
        val room = peak?.takeIf { it > 0f }?.let { 1f / it } ?: 1f
        return minOf(linear, room).coerceAtLeast(1f)
    }
}
