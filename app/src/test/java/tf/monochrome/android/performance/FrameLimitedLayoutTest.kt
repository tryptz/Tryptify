package tf.monochrome.android.performance

import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * The root view passes a child's redraw requests on to the window at no more
 * than the limit, whoever asks: here a child asking every few milliseconds.
 * Counted against the main looper's own clock, which Robolectric moves on as
 * it runs the held frames, not against the steps asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class FrameLimitedLayoutTest {

    @After
    fun reset() {
        FrameRateLimit.fps = FrameRateLimit.MAX
    }

    /** Requests passed to the window, a second, while a child asks every 1/120 s at [limit]. */
    private fun passedPerSecond(limit: Int): Double {
        FrameRateLimit.fps = limit
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        var count = 0
        val window = object : FrameLayout(context) {
            override fun onDescendantInvalidated(child: View, target: View) { count++ }
        }
        val layout = FrameLimitedLayout(context)
        val child = View(context)
        layout.addView(child)
        window.addView(layout)
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < 3_000) {
            layout.onDescendantInvalidated(child, child)
            ShadowLooper.idleMainLooper(8, TimeUnit.MILLISECONDS)
        }
        return count * 1000.0 / (SystemClock.uptimeMillis() - start)
    }

    @Test
    fun `with no limit requests pass as they come`() {
        assertTrue(passedPerSecond(FrameRateLimit.MAX) > 30.0)
    }

    @Test
    fun `at a limit no more than that many requests a second pass`() {
        val at30 = passedPerSecond(30)
        // A request a little early (FramePacer.SLACK_NANOS) passes: a hair over the limit
        // on this clock, which moves in 32 ms jumps; on a display, the next vsync.
        assertTrue("30 passed $at30 a second", at30 in 20.0..33.0)
        val at60 = passedPerSecond(60)
        assertTrue("60 passed $at60 a second", at60 in 40.0..62.0)
    }
}
