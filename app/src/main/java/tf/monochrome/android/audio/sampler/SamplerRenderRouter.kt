// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides which of the two output paths pulls the pattern engine.
 *
 * The looper has to sound in two quite different situations, and there is no
 * single place that covers both:
 *
 * * **A track is playing.** The pattern should go through the Tryptify DSP
 *   chain, so it is summed into the Media3 pipeline by [PatternMixProcessor]
 *   ahead of the mix bus. That is what makes the loop share the user's EQ,
 *   mix and Oxford chain instead of arriving beside them.
 * * **Nothing is playing.** The Media3 chain is not running at all — no
 *   buffers, no callbacks — so the loop would be silent. [SamplerOutputPump]
 *   takes over with its own AudioTrack.
 *
 * Both drive the *same* engine; the engine's clock advances by whatever is
 * rendered, so exactly one of them may render at a time. This class is that
 * arbiter, and it is deliberately a timestamp rather than a lock: the audio
 * thread must not block, and the pump must not be able to deadlock against a
 * renderer that has gone away.
 *
 * The handover is not instant. When playback starts, the pump can render at
 * most one more block (~10 ms) before it sees the chain has claimed the
 * output; when playback stops, the pump waits out [CHAIN_STALE_MS] before
 * picking up. Both are audible only as a slight stumble at the moment music
 * starts or stops, which is the cheapest correct trade — the alternative is
 * synchronising two audio threads.
 */
@Singleton
class SamplerRenderRouter @Inject constructor() {

    private val lastChainRenderMs = AtomicLong(0L)
    private val engineWanted = AtomicBoolean(false)

    /**
     * Called by [PatternMixProcessor] every time it processes a buffer. This
     * is the claim: for as long as it keeps happening, the pump stays quiet.
     */
    fun noteChainRender() {
        lastChainRenderMs.set(SystemClock.elapsedRealtime())
    }

    /** True while the Media3 chain is actively pulling the engine. */
    val chainOwnsOutput: Boolean
        get() = SystemClock.elapsedRealtime() - lastChainRenderMs.get() < CHAIN_STALE_MS

    /**
     * Set while anything needs the engine to sound — the transport running,
     * or the Patterns / Sampler screen being open so pads audition instantly.
     * The pump only holds an AudioTrack open while this is true, so an idle
     * app is not keeping an output stream alive.
     */
    var engineNeeded: Boolean
        get() = engineWanted.get()
        set(value) { engineWanted.set(value) }

    /** The pump's per-block question. */
    fun pumpShouldRender(): Boolean = engineWanted.get() && !chainOwnsOutput

    companion object {
        /**
         * How long after the last chain buffer the pump waits before taking
         * over. Long enough to ride out a normal buffer interval and a pause
         * for a track change, short enough that stopping music does not leave
         * a running loop silent for a noticeable beat.
         */
        const val CHAIN_STALE_MS = 250L
    }
}
