package tf.monochrome.android.ui.player

import tf.monochrome.android.audio.eq.SpectrogramFeed
import kotlin.math.abs
import kotlin.math.max

/**
 * Plays a [SpectrogramFeed] back to the 3D spectrogram at the sound's own
 * pace: each frame the picture moves on by the frame's time, and every
 * spectrum the playhead has passed is handed over with the time since the
 * one before it, so each takes the room in the picture it took in the sound.
 *
 * The playhead keeps [LATENCY_S] behind the newest spectrum. The analyzer
 * hands spectra over in batches, a pass's worth at a time, and a playhead at
 * the newest would stand still between batches and then jump: with that
 * margin the next spectrum is nearly always there already, and the time left
 * of a frame after the last one passed is spent moving toward it. The sound
 * itself leaves the phone later than that anyway.
 *
 * Free of Android and of the scene: it calls [step] and no more, so
 * [SpectrogramPlayheadTest] can hold it to its sums.
 */
internal class SpectrogramPlayhead(private val feed: SpectrogramFeed) {
    private var epoch = Int.MIN_VALUE
    /** The first spectrum not handed over yet. */
    private var next = 0L
    /** The feed's time, in seconds, the picture has been moved to, and where the playhead is. */
    private var shown = 0.0
    private var head = 0.0
    /** The last spectrum handed over, a copy: the feed's own array is written again later. */
    private val held = FloatArray(feed.bins)
    private var holding = false

    /**
     * Moves on by [dt] seconds: [step] is called with each spectrum and the
     * seconds the picture moves while it comes up, in order. With nothing in
     * the feed yet it is not called at all.
     */
    fun advance(dt: Float, step: (bins: FloatArray, seconds: Float) -> Unit) {
        val count = feed.count
        if (count <= 0L) {
            if (holding) step(held, dt)
            return
        }
        val rate = feed.sampleRate.toDouble()
        val target = feed.endSample(count - 1) / rate - LATENCY_S
        if (feed.epoch != epoch || next > count || next < feed.oldest() || abs(target - (head + dt)) > RESYNC_S) {
            // A new stream, or too far off to catch up (a pause, a stall):
            // start at the target, and leave what lies before it unplayed.
            epoch = feed.epoch
            head = target
            shown = target
            next = feed.oldest()
            while (next < count && feed.endSample(next) / rate <= target) next++
            if (holding) step(held, dt)
            return
        }
        // On by the frame's time, and a little toward the target: the frame
        // clock and the sound's are not the same clock.
        head += dt + (target - (head + dt)) * SERVO
        var handed = 0
        while (next < count && handed < MOST_A_FRAME) {
            val at = feed.endSample(next) / rate
            if (at > head) break
            val bins = feed.spectrum(next)
            step(bins, max(0.0, at - shown).toFloat())
            System.arraycopy(bins, 0, held, 0, held.size)
            holding = true
            shown = max(shown, at)
            next++
            handed++
        }
        if (head > shown) {
            // The rest of the frame: toward the spectrum that comes next
            // where it is there already, else holding the last.
            if (next < count) step(feed.spectrum(next), (head - shown).toFloat())
            else if (holding) step(held, (head - shown).toFloat())
            shown = head
        }
    }

    companion object {
        /** How far behind the newest spectrum the playhead keeps: two of the analyzer's passes at 60 a second. */
        const val LATENCY_S = 0.034
        /** Further than this from where it belongs, the playhead starts again there. */
        const val RESYNC_S = 0.25
        /** The share of its distance from the target the playhead makes up each frame. */
        const val SERVO = 0.08
        /** Spectra handed over in one frame, at most. */
        const val MOST_A_FRAME = 48
    }
}
