// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.replaygain

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReplayGain's Media3 shell: what it decides before any sample reaches the
 * C++ stage. The samples themselves (exact at unity, the gain otherwise, no
 * wrap-around, the glide and back to exact) are the C++ stage's, and are held
 * on the desktop with the rest of the DSP chain: cpp/dsp/tests/replay_gain_test.cpp.
 */
class ReplayGainProcessorTest {

    @Test
    fun `other encodings are left alone`() {
        val p = ReplayGainProcessor()
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_24BIT)))
        p.flush()
        assertFalse(p.isActive)
    }

    @Test
    fun `nonsense gains are taken as unity`() {
        val p = ReplayGainProcessor()
        p.setGain(Float.NaN)
        assertEquals(1f, p.gain)
        p.setGain(-2f)
        assertEquals(1f, p.gain)
        p.setGain(Float.POSITIVE_INFINITY)
        assertEquals(1f, p.gain)
        p.setGain(0.5f)
        assertEquals(0.5f, p.gain)
    }

    // Which start a flush asks the stage for. Media3 configures every processor
    // for each new track and then flushes; a seek or a change of speed flushes
    // alone. The flush itself needs the native stage, so the choice is held here.

    @Test
    fun `a flush after a configure is a new stream, started by the mode`() {
        assertEquals(ReplayGainNative.START_BY_MODE, ReplayGainProcessor.startOf(newStream = true, seed = 0f))
    }

    @Test
    fun `a flush with no configure first is the same stream, carried on`() {
        assertEquals(ReplayGainNative.START_RESUME, ReplayGainProcessor.startOf(newStream = false, seed = 0f))
    }

    @Test
    fun `a seeded stream starts at the seed, new or not`() {
        assertEquals(1.4f, ReplayGainProcessor.startOf(newStream = true, seed = 1.4f))
        assertEquals(1.4f, ReplayGainProcessor.startOf(newStream = false, seed = 1.4f))
    }

    @Test
    fun `the starts land in the ranges the JNI reads them by`() {
        // replay_gain_jni.cpp: above 0 a level, at or below -2 resume, anything else by the mode.
        assertTrue(ReplayGainNative.START_RESUME <= -2f)
        assertTrue(ReplayGainNative.START_BY_MODE > -2f && ReplayGainNative.START_BY_MODE <= 0f)
    }
}
