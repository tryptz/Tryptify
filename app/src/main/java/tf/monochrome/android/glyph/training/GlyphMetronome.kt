// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.training

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A click on every beat, and a louder one on the downbeat.
 *
 * Synthesised rather than sampled: a click is two sine bursts and an envelope,
 * and generating it avoids shipping an asset and lets the downbeat differ by
 * pitch rather than by volume alone — which is what makes the bar position
 * audible instead of merely the pulse.
 *
 * Driven by beat number rather than by a timer. The caller passes the beat the
 * song is currently on and this fires when that number changes, so the click
 * follows the audio clock like everything else in the mode and cannot drift
 * away from the music the way a periodic timer would.
 */
class GlyphMetronome {

    private var track: AudioTrack? = null
    private var lastBeat = Int.MIN_VALUE

    /**
     * Fire a click if [beat] has moved on since the last call.
     *
     * Silently does nothing when audio is unavailable — a metronome that could
     * not be created is not a reason to stop a run.
     */
    fun tick(beat: Int, beatsPerBar: Int = 4) {
        if (beat == lastBeat) return
        // A seek backwards (a loop wrap) must not fire a burst of clicks for
        // every beat it skipped; it just re-anchors.
        val isFirst = lastBeat == Int.MIN_VALUE || beat < lastBeat
        lastBeat = beat
        if (isFirst && beat != 0) return

        val downbeat = beatsPerBar > 0 && beat % beatsPerBar == 0
        runCatching { play(if (downbeat) DOWNBEAT_HZ else BEAT_HZ) }
    }

    fun reset() {
        lastBeat = Int.MIN_VALUE
    }

    private fun play(frequency: Float) {
        val samples = ShortArray(CLICK_FRAMES) { index ->
            val t = index.toDouble() / SAMPLE_RATE
            // A short exponential decay; a click with no envelope is a pop.
            val envelope = exp(-t * DECAY)
            val value = sin(2.0 * PI * frequency * t) * envelope * AMPLITUDE
            (value * Short.MAX_VALUE).toInt().toShort()
        }

        val output = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Sonification, not media: the click should duck under a
                    // call and must never be what a headset's next-track button
                    // is aimed at.
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        output.write(samples, 0, samples.size)
        output.play()

        // Replaces the previous click rather than accumulating tracks: at 200
        // BPM this is called three times a second and leaking one AudioTrack
        // per click would exhaust the pool within a song.
        track?.release()
        track = output
    }

    fun release() {
        track?.release()
        track = null
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val CLICK_FRAMES = 1_800
        const val BEAT_HZ = 1_000f
        const val DOWNBEAT_HZ = 1_500f
        const val DECAY = 55.0
        const val AMPLITUDE = 0.35
    }
}
