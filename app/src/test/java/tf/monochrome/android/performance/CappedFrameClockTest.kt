package tf.monochrome.android.performance

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The limit reaches what is composed: a composition whose recomposer runs on
 * [CappedFrameClock] sees, in its `withFrameNanos` loops, only the frames the
 * limit keeps of a display's. Under Robolectric only for the Android classes
 * the runtime traces with; the display here is a clock of the test's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class CappedFrameClockTest {

    @After
    fun reset() {
        FrameRateLimit.fps = FrameRateLimit.MAX
    }

    private object NoApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    /** Frames a composed `withFrameNanos` loop sees over 2 s of a 120 Hz display at [limit]. */
    private fun framesSeen(limit: Int): Int = runBlocking {
        FrameRateLimit.fps = limit
        val display = BroadcastFrameClock()
        val capped = CappedFrameClock(display)
        val recomposer = Recomposer(coroutineContext + capped)
        val runner = launch(capped) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoApplier, recomposer)
        var frames = 0
        composition.setContent {
            LaunchedEffect(Unit) {
                while (true) withFrameNanos { frames++ }
            }
        }
        suspend fun settle() = repeat(50) { yield() }
        settle()
        for (i in 1..240) {
            display.sendFrame(i * 8_333_333L)
            settle()
        }
        composition.dispose()
        recomposer.cancel()
        runner.cancel()
        frames
    }

    @Test
    fun `with no limit every display frame reaches the composition`() {
        assertTrue(framesSeen(FrameRateLimit.MAX) >= 235)
    }

    @Test
    fun `at a limit only its frames reach the composition`() {
        val at30 = framesSeen(30)
        assertTrue("30 fps for 2 s saw $at30", at30 in 58..62)
        val at60 = framesSeen(60)
        assertTrue("60 fps for 2 s saw $at60", at60 in 118..122)
    }
}
