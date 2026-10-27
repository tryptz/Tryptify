// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.share

import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Makes a song too big to share into a smaller copy: Opus at 320 kbps in an
 * MP4 container. [ShareShrinker] is the real one (Media3 Transformer); the
 * tests hand in their own.
 */
interface SongShrinker {
    /** Whether this phone can make the copy at all (Android 10+ with an Opus encoder). */
    val available: Boolean

    /**
     * The copy of [source] (a `file://` or `content://` address) in the
     * cache, or null when it could not be made. [onProgress] goes 0 to 1.
     * Nothing half-made is left behind, on failure or when cancelled.
     */
    suspend fun shrink(source: String, onProgress: (Float) -> Unit): File?
}

/**
 * When a shared song goes up as it is and when it goes up as a smaller copy
 * first (the owner's rule: "If a file is higher than 100mb upload it in opus
 * 320"). The rooms server takes a song of at most `maxSongBytes` (its info
 * says how many; Cloudflare takes no bigger request), and a party at most
 * what is left of its room for shared songs.
 */
object ShareSize {
    /** The rooms server's limit when its info does not say (server/rooms `SONG_LIMIT`). */
    const val DEFAULT_MAX_SONG_BYTES = 99_000_000L

    /** What the smaller copy is uploaded as. */
    const val SHRUNK_EXT = "m4a"
    const val OPUS_BITRATE = 320_000
    const val SAMPLE_RATE = 48_000

    /** What an Opus stream at [OPUS_BITRATE] carries on top of its audio: the MP4 boxes, packet sizes. */
    private const val CONTAINER_SLACK = 1.03

    /**
     * The most a shared song may be: the server's [maxSongBytes] (the
     * default when unknown), and at a party ([partyLimit] > 0) no more than
     * is left of its room once [partyUsed] is taken.
     */
    fun limit(maxSongBytes: Long?, partyLimit: Long = 0, partyUsed: Long = 0): Long {
        val song = maxSongBytes?.takeIf { it > 0 } ?: DEFAULT_MAX_SONG_BYTES
        if (partyLimit <= 0) return song
        return minOf(song, (partyLimit - partyUsed).coerceAtLeast(0))
    }

    /** About how big the Opus copy of a song [durationMs] long comes out. */
    fun estimate(durationMs: Long): Long = (OPUS_BITRATE / 8.0 * durationMs / 1000.0 * CONTAINER_SLACK).toLong()

    /**
     * Whether a song of [size] bytes is made smaller before it goes up: only
     * when it is over [limit], the phone [canShrink], there is room for
     * anything at all, and the copy can fit (a song [durationMs] long, when
     * known, whose 320 kbps copy would still be too big is not tried: an
     * hour's mix stays too big at any 320 kbps). A song of unknown size goes
     * up as it is, as before.
     */
    fun shouldShrink(size: Long?, limit: Long, canShrink: Boolean, durationMs: Long? = null): Boolean = when {
        size == null || size <= 0 -> false
        size <= limit -> false
        limit <= 0 -> false
        !canShrink -> false
        durationMs != null && durationMs > 0 && estimate(durationMs) > limit -> false
        else -> true
    }

    /**
     * What goes up: the original ([shrunk] null, with its own [ext]) or the
     * smaller copy ([shrunk], [SHRUNK_EXT]). [discard] deletes the copy; the
     * caller calls it once the upload is over, whichever way it went.
     */
    class Prepared(val shrunk: File?, val ext: String) {
        fun discard() {
            shrunk?.delete()
        }
    }

    /**
     * The file to share for [source], [size] bytes with extension [ext]:
     * the smaller copy when [shouldShrink] says so and the copy fits
     * [limit], else the original, as before (the server then refuses one too
     * big, and the song shows as not shared). A copy that failed or came out
     * too big is deleted here.
     */
    suspend fun prepare(
        shrinker: SongShrinker,
        source: String,
        size: Long?,
        ext: String,
        limit: Long,
        durationMs: Long? = null,
        onProgress: (Float) -> Unit = {},
    ): Prepared {
        val original = Prepared(null, ext)
        // Asked last: whether the phone has an Opus encoder is a codec-list
        // query, wasted on the many songs that are small enough to go as they are.
        if (!shouldShrink(size, limit, canShrink = true, durationMs) || !shrinker.available) return original
        val copy = try {
            shrinker.shrink(source, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return original
        val bytes = copy.length()
        if (bytes <= 0 || bytes > limit) {
            copy.delete()
            return original
        }
        return Prepared(copy, SHRUNK_EXT)
    }

    /**
     * How a song of [channels] channels is mixed down to stereo for the
     * Opus copy, as Media3's `ChannelMixingMatrix` takes it: input channel
     * by input channel, (left, right) each. Mono and stereo stay as they
     * are; more channels are taken in Android's order (front left, front
     * right, centre, LFE, then the surrounds), the centre and the surrounds
     * at -3 dB, the LFE left out, scaled so no side can clip.
     */
    fun stereoDownmix(channels: Int): FloatArray {
        require(channels >= 3) { "only more than two channels are mixed down" }
        val c = 0.7071f
        // Each input channel's share of (left, right).
        val roles: List<Pair<Float, Float>> = when (channels) {
            3 -> listOf(1f to 0f, 0f to 1f, c to c)
            4 -> listOf(1f to 0f, 0f to 1f, c to 0f, 0f to c)
            5 -> listOf(1f to 0f, 0f to 1f, c to c, c to 0f, 0f to c)
            6 -> listOf(1f to 0f, 0f to 1f, c to c, 0f to 0f, c to 0f, 0f to c)
            7 -> listOf(1f to 0f, 0f to 1f, c to c, 0f to 0f, c * c to c * c, c to 0f, 0f to c)
            8 -> listOf(1f to 0f, 0f to 1f, c to c, 0f to 0f, c to 0f, 0f to c, c to 0f, 0f to c)
            else -> List(channels) { i ->
                when {
                    i == 0 -> 1f to 0f
                    i == 1 -> 0f to 1f
                    i == 2 -> c to c
                    i == 3 -> 0f to 0f
                    i % 2 == 0 -> c to 0f
                    else -> 0f to c
                }
            }
        }
        val scale = 1f / maxOf(roles.sumOf { it.first.toDouble() }, roles.sumOf { it.second.toDouble() }).toFloat()
        return FloatArray(channels * 2) { k ->
            val (left, right) = roles[k / 2]
            (if (k % 2 == 0) left else right) * scale
        }
    }
}
