package tf.monochrome.android.ui.player

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the player's position poll waits for a screen to come back. */
class PositionPollGateTest {

    @Test
    fun `it waits only while the app is in the background`() {
        assertTrue(appScreensStopped(Lifecycle.State.CREATED))
        assertFalse(appScreensStopped(Lifecycle.State.STARTED))
        assertFalse(appScreensStopped(Lifecycle.State.RESUMED))
    }

    @Test
    fun `a process lifecycle that was never set up does not stop it`() {
        // INITIALIZED is where ProcessLifecycleOwner stays if androidx.startup
        // never runs its initialiser: waiting for STARTED there would freeze
        // the play head for good.
        assertFalse(appScreensStopped(Lifecycle.State.INITIALIZED))
    }
}
