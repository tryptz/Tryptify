// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.usb

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Who may be playing on the exclusive USB DAC: the main player, or Trypt Mania (its song or a preview). */
enum class StreamClaimant { PLAYER, GAME }

/**
 * Whose the DAC's one stream is.
 *
 * The driver carries a single stream (one format, one ring, one pump), and
 * since 2026-10-29 two sinks feed it: the main player's and Trypt Mania's
 * ([UsbGameAudio]). This decides between them, with one rule: the last to
 * start the stream holds it, and the other one's writes, parks, flushes and
 * stops do nothing until it starts the stream again. So a run's song takes
 * the DAC from the main player (which pauses, [PlaybackService] watching
 * [holder]), and the next play on the main player takes it back.
 *
 * Letting go is [release]: the stream is parked, never stopped, because
 * releasing the USB interface hands the DAC to Android's kernel driver and
 * the next start fails ([LibusbUacDriver.flushRing]).
 *
 * Apart from the driver, this holds no USB state, so it has a plain unit
 * test ([StreamLeaseTest]); the driver loads its native library on
 * construction and cannot.
 */
class StreamLease {
    private val lock = ReentrantLock()
    private val _holder = MutableStateFlow<StreamClaimant?>(null)

    /** Who holds the stream, or null while nobody does (none started, or the last holder let go). */
    val holder: StateFlow<StreamClaimant?> = _holder.asStateFlow()

    fun holds(who: StreamClaimant): Boolean = _holder.value == who

    /** Someone else holds the stream. */
    fun heldByAnother(who: StreamClaimant): Boolean = _holder.value.let { it != null && it != who }

    /**
     * [who] brings the stream up with [start] and holds it if that worked;
     * always allowed, whoever held it before. If [start] fails, nobody
     * holds it: the stream that was running is down.
     */
    fun take(who: StreamClaimant, start: () -> Boolean): Boolean = lock.withLock {
        val ok = start()
        _holder.value = if (ok) who else null
        ok
    }

    /** Runs [action] only while [who] holds the stream; null otherwise. */
    fun <T> asHolder(who: StreamClaimant, action: () -> T): T? = lock.withLock {
        if (_holder.value == who) action() else null
    }

    /**
     * [asHolder] for the audio thread: never waits. Null when [who] does not
     * hold the stream, or another thread is starting or stopping it this
     * instant, which closes the gap where a write checked the holder just
     * before the other player's start.
     */
    fun <T> tryAsHolder(who: StreamClaimant, action: () -> T): T? {
        if (_holder.value != who) return null
        if (!lock.tryLock()) return null
        try {
            return if (_holder.value == who) action() else null
        } finally {
            lock.unlock()
        }
    }

    /**
     * [who] lets go: [park] runs and nobody holds the stream. One that does
     * not hold it changes nothing (false).
     */
    fun release(who: StreamClaimant, park: () -> Unit): Boolean = lock.withLock {
        if (_holder.value != who) return false
        park()
        _holder.value = null
        true
    }

    /**
     * [who] ends the stream with [stop], allowed when it holds it or nobody
     * does (a parked stream the last holder left); nobody holds it after.
     * False, and nothing run, while another holds it.
     */
    fun end(who: StreamClaimant, stop: () -> Unit): Boolean = lock.withLock {
        if (heldByAnother(who)) return false
        stop()
        _holder.value = null
        true
    }

    /** The DAC is gone: nobody holds anything. */
    fun clear() = lock.withLock { _holder.value = null }
}
