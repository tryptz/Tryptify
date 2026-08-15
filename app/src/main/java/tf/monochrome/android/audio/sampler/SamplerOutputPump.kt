// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * The looper's own output, for when the Media3 chain is not running.
 *
 * With a track playing, patterns are summed into the pipeline by
 * [PatternMixProcessor] and inherit the whole Tryptify DSP chain. With nothing
 * playing there is no pipeline to sum into — no buffers are being pulled, so
 * the loop would simply be silent — and a sequencer that only works while
 * music is playing is not a sequencer. This class is that second path: one
 * AudioTrack, one thread, pulling the same engine.
 *
 * It is deliberately thin. It does not process, mix or convert anything beyond
 * interleaving; the engine does the work, [SamplerRenderRouter] decides who
 * pulls it, and this only exists while the transport or an open sampler screen
 * needs sound.
 */
@Singleton
class SamplerOutputPump @Inject constructor(
    private val bridge: NativeSamplerBridge,
    private val router: SamplerRenderRouter,
) {

    @Volatile
    private var running = false

    @Volatile
    private var worker: Thread? = null

    private val lock = Any()

    /** Sample rate the engine and the track agree on while the pump owns output. */
    @Volatile
    var sampleRate: Int = DEFAULT_SAMPLE_RATE
        private set

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            worker = thread(name = "TryptifySamplerPump", isDaemon = true) { pump() }
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            worker = null
        }
    }

    private fun pump() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val rate = DEFAULT_SAMPLE_RATE
        val minBytes = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) {
            Log.w(TAG, "no usable AudioTrack configuration; standalone pattern output disabled")
            running = false
            return
        }

        // Three device buffers. One is what the docs allow and what glitches
        // the moment the scheduler looks away; three is enough headroom for a
        // thermally throttled phone without adding latency anyone can hear.
        val bufferBytes = minBytes * 3
        val track = try {
            buildTrack(rate, bufferBytes)
        } catch (error: Throwable) {
            Log.e(TAG, "AudioTrack unavailable", error)
            running = false
            return
        }

        sampleRate = rate
        bridge.prepare(rate)

        val framesPerBlock = (bufferBytes / (2 * 4) / 3).coerceIn(MIN_BLOCK, MAX_BLOCK)
        val left = FloatArray(framesPerBlock)
        val right = FloatArray(framesPerBlock)
        val interleaved = FloatArray(framesPerBlock * 2)

        var playing = false
        try {
            while (running) {
                if (!router.pumpShouldRender()) {
                    // The chain has the output, or nothing needs sound. Park
                    // the track rather than writing silence — an idle
                    // AudioTrack in PLAYING state holds the output path open
                    // and keeps the DSP awake for no reason.
                    if (playing) {
                        runCatching { track.pause() }
                        runCatching { track.flush() }
                        playing = false
                    }
                    Thread.sleep(IDLE_POLL_MS)
                    continue
                }

                if (!playing) {
                    runCatching { track.play() }
                    playing = true
                }

                bridge.render(left, right, framesPerBlock, true)
                for (i in 0 until framesPerBlock) {
                    interleaved[i * 2] = left[i]
                    interleaved[i * 2 + 1] = right[i]
                }
                val written = track.write(
                    interleaved,
                    0,
                    framesPerBlock * 2,
                    AudioTrack.WRITE_BLOCKING,
                )
                if (written < 0) {
                    Log.w(TAG, "AudioTrack.write returned $written; stopping pump")
                    break
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            Log.e(TAG, "pattern output pump stopped", error)
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
            running = false
        }
    }

    private fun buildTrack(rate: Int, bufferBytes: Int): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Asks the framework for the fast mixer path. It is a request, not
            // a guarantee — a device that cannot honour it falls back to the
            // normal path rather than failing to build.
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "SamplerOutputPump"
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val MIN_BLOCK = 128
        private const val MAX_BLOCK = 1024
        private const val IDLE_POLL_MS = 40L
    }
}
