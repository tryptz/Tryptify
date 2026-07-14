package tf.monochrome.android.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tf.monochrome.android.domain.model.Lyrics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped mirror of the currently-playing lyrics and playback position.
 * [tf.monochrome.android.ui.player.PlayerViewModel] (screen-scoped) publishes into
 * it, so other screens — e.g. the Lyrics FX Studio preview — can show the real
 * lyrics without holding a reference to the player screen's ViewModel.
 */
@Singleton
class NowPlayingLyricsHolder @Inject constructor() {
    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    fun setLyrics(value: Lyrics?) {
        _lyrics.value = value
    }

    fun setPosition(value: Long) {
        _positionMs.value = value
    }
}
