package tf.monochrome.android.data.local.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitArtistTitleTest {
    @Test
    fun `a spaced tilde splits like a spaced hyphen`() {
        assertEquals("Banana Inc" to "Black Magic (Dub)", splitArtistTitle("Banana Inc ~ Black Magic (Dub)"))
        assertEquals("Dj Satomi" to "Waves", splitArtistTitle("Dj Satomi - Waves"))
    }

    @Test
    fun `the first separator wins`() {
        assertEquals("A" to "B - C", splitArtistTitle("A ~ B - C"))
    }

    @Test
    fun `unspaced separators and dashes inside titles do not split`() {
        assertNull(splitArtistTitle("Xeroa_converted"))
        assertNull(splitArtistTitle("lo-fi~mix"))
        assertNull(splitArtistTitle("Heroine — Pat B Remix"))
        assertNull(splitArtistTitle(" ~ Title"))
    }
}
