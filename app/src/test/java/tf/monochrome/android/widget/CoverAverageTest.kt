package tf.monochrome.android.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Album glass's colour off a real cover, scaled as the phone scales it
 * (Android's own graphics, not a stand-in), so the scale is held as well as
 * the average ([averageArgb], in `WidgetStyleTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverAverageTest {

    @Test
    fun aWhiteSubjectOnARedCoverStillGivesRedGlass() {
        // The widget's cover size, the middle quarter white. Scaled straight to
        // 1×1 it came out white: only the pixels at the middle were read.
        val cover = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        cover.eraseColor(0xFF8B0000.toInt())
        Canvas(cover).drawRect(64f, 64f, 192f, 192f, Paint().apply { color = 0xFFFFFFFF.toInt() })
        val average = Color(averageColor(cover))
        assertEquals((255 + 3 * 0x8B) / 4f / 255f, average.red, 4f / 255f)
        assertEquals(255 / 4f / 255f, average.green, 4f / 255f)
        assertEquals(average.green, average.blue, 1f / 255f)
        assertEquals(1f, average.alpha, 0f)
        // The cover itself is the caller's; only the small copy is let go.
        assertFalse(cover.isRecycled)
    }

    @Test
    fun aCoverNoBiggerThanTheGridIsAveragedAsItIsAndKept() {
        // Immutable and small enough that the scale hands back the cover itself.
        val pixels = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        pixels.setPixel(0, 0, 0xFF000000.toInt())
        pixels.setPixel(1, 0, 0xFFFFFFFF.toInt())
        val cover = pixels.copy(Bitmap.Config.ARGB_8888, false)
        val average = Color(averageColor(cover))
        assertEquals(0.5f, average.red, 1f / 255f)
        assertFalse(cover.isRecycled)
    }
}
