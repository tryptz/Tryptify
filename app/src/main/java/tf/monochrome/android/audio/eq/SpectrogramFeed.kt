package tf.monochrome.android.audio.eq

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * The 3D spectrogram's spectra, in the order they were made, each with the
 * place in the sound its window ends at.
 *
 * One spectrum a display frame, the newest only, was all there was at first.
 * Scrolled fast (half a second across the picture) that drew every frame's
 * spectrum as a slab a frame wide, joined to the next by a ramp: "mega
 * blocky", the owner, 2026-11-26. So the analyzer makes one every hop of the
 * listener's FFT size and overlap, several to a frame, and a reader plays
 * them back at the sound's own pace ([tf.monochrome.android.ui.player.
 * SpectrogramPlayhead]).
 *
 * One writer, the analyzer's coroutine: [slot], then [publish]. Any number
 * of readers on other threads, without a lock: a spectrum is readable from
 * the write of [count] that follows its own, and a reader keeps to the
 * newest [CAPACITY] - [GUARD], which the writer is far from overwriting.
 */
class SpectrogramFeed(val bins: Int = SpectrumAnalyzerTap.OUTPUT_BINS) {
    private val spectra = Array(CAPACITY) { FloatArray(bins) { SpectrogramBands.SILENCE_DB } }
    private val ends = LongArray(CAPACITY)
    private val readers = AtomicInteger(0)

    /** Spectra made since the stream last started; the newest is number `count - 1`. */
    @Volatile var count: Long = 0L
        private set

    /** Goes up each time the stream starts again (a new rate, a new FFT size): numbers and times from before mean nothing then. */
    @Volatile var epoch: Int = 0
        private set

    /** Samples a second of the sound the times count. */
    @Volatile var sampleRate: Int = 48_000
        private set

    /** While a 3D spectrogram is on screen. Without one the analyzer makes one spectrum a pass, as it always did. */
    val isWanted: Boolean get() = readers.get() > 0

    fun acquire() {
        readers.incrementAndGet()
    }

    fun release() {
        readers.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /** The writer's: a new stream. */
    fun restart(sampleRate: Int) {
        this.sampleRate = sampleRate.coerceAtLeast(1)
        count = 0L
        epoch = epoch + 1
    }

    /** The writer's: the array to fill with the next spectrum. */
    fun slot(): FloatArray = spectra[(count % CAPACITY).toInt()]

    /** The writer's: the spectrum in [slot] is done, and its window ends at sample [endSample] of the stream. */
    fun publish(endSample: Long) {
        ends[(count % CAPACITY).toInt()] = endSample
        count = count + 1
    }

    /** The oldest spectrum a reader may still read. */
    fun oldest(): Long = max(0L, count - (CAPACITY - GUARD))

    /** Spectrum [number], one of [oldest] until [count]. Not to be kept: the writer comes round to it. */
    fun spectrum(number: Long): FloatArray = spectra[(number % CAPACITY).toInt()]

    /** The sample of the stream spectrum [number]'s window ends at. */
    fun endSample(number: Long): Long = ends[(number % CAPACITY).toInt()]

    companion object {
        const val CAPACITY = 96
        /** Slots a reader leaves alone ahead of the writer. */
        const val GUARD = 32
    }
}
