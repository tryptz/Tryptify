// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.replaygain

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
