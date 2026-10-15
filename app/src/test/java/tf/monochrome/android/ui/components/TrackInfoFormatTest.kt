package tf.monochrome.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackInfoFormatTest {
    @Test
    fun `sample rates in kHz`() {
        assertEquals("48 kHz", sampleRate(48000))
        assertEquals("44.1 kHz", sampleRate(44100))
        assertEquals("88.2 kHz", sampleRate(88200))
    }

    @Test
    fun `lengths as a clock`() {
        assertEquals("3:05", length(185.4))
        assertEquals("1:02:03", length(3723.0))
    }
}
