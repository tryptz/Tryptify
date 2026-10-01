package tf.monochrome.android.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A TIDAL track TIDAL could not play, which Qobuz has: the question the app
 * asks instead of quietly switching services.
 */
data class QobuzOffer(
    val tidalId: Long,
    val title: String,
    val artist: String,
)

/**
 * The listener's say over which service a song plays from.
 *
 * A TIDAL pick plays from TIDAL. When TIDAL cannot play it, the resolver used
 * to find the same recording on Qobuz and play that, with nothing on screen
 * to say the song now came from somewhere else. Now it posts a [QobuzOffer]
 * here instead, the app asks, and only a track the listener has [allow]ed
 * plays from Qobuz — for the rest of the session, so a repeat or a skip back
 * does not ask again.
 */
@Singleton
class SourceConsent @Inject constructor() {

    private val allowed = ConcurrentHashMap.newKeySet<Long>()

    private val _offer = MutableStateFlow<QobuzOffer?>(null)

    /** The question waiting for an answer, or null. */
    val offer: StateFlow<QobuzOffer?> = _offer.asStateFlow()

    fun isAllowed(tidalId: Long): Boolean = tidalId in allowed

    /** Asks about [offer], unless the listener already said yes to this track. */
    fun post(offer: QobuzOffer) {
        if (!isAllowed(offer.tidalId)) _offer.value = offer
    }

    /** Yes: this track may play from Qobuz. */
    fun allow(tidalId: Long) {
        allowed += tidalId
        if (_offer.value?.tidalId == tidalId) _offer.value = null
    }

    /** No: leave it skipped. */
    fun dismiss() {
        _offer.value = null
    }
}
