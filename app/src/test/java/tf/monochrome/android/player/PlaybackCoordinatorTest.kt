package tf.monochrome.android.player

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorTest {

    @Test
    fun requestPlayEmitsPlayResolvedTrackCommand() = runBlocking {
        val coordinator = PlaybackCoordinator()
        val command = async(start = CoroutineStart.UNDISPATCHED) { coordinator.commands.first() }

        coordinator.requestPlay("track-123")

        val emitted = command.await()
        assertTrue(emitted is PlaybackCommand.PlayResolvedTrack)
        assertEquals("track-123", (emitted as PlaybackCommand.PlayResolvedTrack).trackId)
    }

    @Test
    fun coordinatorDoesNotExposePlayerAcceptingApis() {
        val acceptsPlayer = PlaybackCoordinator::class.java.methods.any { method ->
            method.parameterTypes.any { Player::class.java.isAssignableFrom(it) }
        }

        assertFalse(acceptsPlayer)
    }
}
