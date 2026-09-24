package tf.monochrome.android.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmSinkDataSourceTest {

    // 44.1 kHz * 2 ch * 2 bytes = 176 400 bytes per second.

    @Test
    fun `byte offsets map to the millisecond they fall in`() {
        assertEquals(0, PcmSinkDataSource.pcmOffsetToMs(0))
        assertEquals(0, PcmSinkDataSource.pcmOffsetToMs(176))
        assertEquals(1, PcmSinkDataSource.pcmOffsetToMs(177))
        assertEquals(1_000, PcmSinkDataSource.pcmOffsetToMs(176_400))
        assertEquals(60_000, PcmSinkDataSource.pcmOffsetToMs(176_400L * 60))
    }

    @Test
    fun `negative offsets clamp to the start`() {
        assertEquals(0, PcmSinkDataSource.pcmOffsetToMs(-44))
    }
}
