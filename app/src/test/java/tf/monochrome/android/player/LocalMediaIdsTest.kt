package tf.monochrome.android.player

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A library track is found by its row; nothing else is taken for one. */
class LocalMediaIdsTest {

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    @Test
    fun `a library track's row`() {
        assertEquals(42L, localRowIdOf(item("local_42")))
    }

    @Test
    fun `anything else is not a library track`() {
        for (id in listOf("12345", "download_7", "local_album_3", "local_", "tidal_99", "")) assertNull(id, localRowIdOf(item(id)))
        assertNull(localRowIdOf(null))
    }
}
