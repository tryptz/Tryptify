// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Audition for the wave editor.
 *
 * Deliberately separate from the pattern engine. The engine plays *samples
 * assigned to channels*, addressed by slot and triggered by the sequencer;
 * what the editor needs is to hear an arbitrary range of a buffer that has not
 * been saved yet, with a playhead it can draw. Routing that through the
 * sequencer would mean inventing a fake channel and a fake pattern to hang it
 * on, and it would fight the transport for the engine's clock.
 *
 * So this is its own small AudioTrack. It plays one buffer at a time, from one
 * frame to another, optionally looping, and publishes the frame it is on.
 *
 * The buffer is referenced, never copied — an edit chain already allocates
 * enough. It is only ever read here, and [stop] joins nothing: the worker sees
 * the generation counter change and exits on its own.
 */
@Singleton
class SamplePreviewPlayer @Inject constructor() {

    data class State(
        val playing: Boolean = false,
        val frame: Int = 0,
        val looping: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Bumped on every [play] and [stop]. The worker checks it each block and
     * exits when it no longer owns the player, which is how a new preview
     * replaces one already running without a lock or a join.
     */
    private val generation = AtomicInteger(0)

    private val lock = Any()

    /**
     * Plays `[fromFrame, toFrame)` of [buffer].
     *
     * Starting a new preview supersedes whatever was playing.
     */
    fun play(
        buffer: SampleEdits.Buffer,
        fromFrame: Int,
        toFrame: Int,
        looping: Boolean = false,
    ) {
        val start = fromFrame.coerceIn(0, buffer.frames)
        val end = toFrame.coerceIn(start, buffer.frames)
        if (end - start < 1) return

        val mine = generation.incrementAndGet()
        synchronized(lock) {
            thread(name = "TryptifySamplePreview", isDaemon = true) {
                render(mine, buffer, start, end, looping)
            }
        }
        _state.value = State(playing = true, frame = start, looping = looping)
    }

    fun stop() {
        generation.incrementAndGet()
        _state.value = _state.value.copy(playing = false)
    }

    private fun render(
        mine: Int,
        buffer: SampleEdits.Buffer,
        start: Int,
        end: Int,
        looping: Boolean,
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        val rate = if (buffer.sampleRate in 8000..384000) buffer.sampleRate else 48000
        val minBytes = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) {
            Log.w(TAG, "no AudioTrack configuration for ${rate}Hz; preview unavailable")
            finish(mine)
            return
        }

        val track = try {
            build(rate, minBytes * 2)
        } catch (error: Throwable) {
            Log.e(TAG, "preview AudioTrack unavailable", error)
            finish(mine)
            return
        }

        val block = 1024
        val interleaved = FloatArray(block * 2)
        var position = start

        try {
            track.play()
            while (generation.get() == mine) {
                var written = 0
                while (written < block) {
                    if (position >= end) {
                        if (!looping) break
                        position = start
                    }
                    val left = buffer.left[position]
                    // Mono previews as centred rather than left-only; hearing a
                    // mono sample in one ear is a bug report every time.
                    val right = buffer.right?.get(position) ?: left
                    interleaved[written * 2] = left
                    interleaved[written * 2 + 1] = right
                    position += 1
                    written += 1
                }
                if (written == 0) break

                // Publishing before the write rather than after: the write
                // blocks until the device has room, so a position published
                // after it would already be a buffer stale by the time the UI
                // drew it.
                _state.value = State(playing = true, frame = position, looping = looping)

                val result = track.write(interleaved, 0, written * 2, AudioTrack.WRITE_BLOCKING)
                if (result < 0) {
                    Log.w(TAG, "preview write returned $result")
                    break
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "preview stopped", error)
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
            finish(mine)
        }
    }

    /** Only the worker that still owns the player may clear the playing flag. */
    private fun finish(mine: Int) {
        if (generation.get() == mine) {
            _state.value = _state.value.copy(playing = false)
        }
    }

    private fun build(rate: Int, bufferBytes: Int): AudioTrack {
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
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "SamplePreviewPlayer"
    }
}
