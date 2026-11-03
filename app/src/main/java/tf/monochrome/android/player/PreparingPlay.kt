package tf.monochrome.android.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The play the listener is waiting for, while its song is made ready before
 * the player can have it: a YouTube song fetched whole, a SoundCloud one
 * prepared by the server. That takes seconds, longer on mobile data, and the
 * player's buffering spinner only follows the player, which has nothing yet;
 * [waiting] is what lets the play button show it meanwhile.
 *
 * Every play takes a turn ([begin]), whether it waits or not, the app's own
 * (PlayerViewModel) and the service's (a track end, a notification skip)
 * alike. A newer turn supersedes the one before: an older play that comes back
 * late neither plays over the newer one ([isLatest]) nor clears its spinner
 * ([end]). A song made ready ahead of its turn takes none: nobody is waiting
 * for it.
 */
@Singleton
class PreparingPlay @Inject constructor() {
    private var latest = 0L

    private val _waiting = MutableStateFlow(false)
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()

    /** A play starts, [prepares] when its song must be made ready first; its turn. */
    @Synchronized
    fun begin(prepares: Boolean): Long {
        _waiting.value = prepares
        return ++latest
    }

    /** Whether [turn] is still the newest play, the one the player is for. */
    @Synchronized
    fun isLatest(turn: Long): Boolean = turn == latest

    /** [turn]'s song is in the player, failed, or was given up: the wait is over, unless a newer play's. */
    @Synchronized
    fun end(turn: Long) {
        if (turn == latest) _waiting.value = false
    }
}
