package tf.monochrome.android.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which catalog a [Track] belongs to, for its source tag.
 *
 * A Track carries a number, not a catalog, and Qobuz and Deezer numbers share
 * TIDAL's range — so the answer comes from the registry every catalog's
 * results are recorded in as they arrive, checked before TIDAL, which is
 * what is left. The same order the stream resolver uses to decide where a
 * track plays from, so the tag and the playback cannot disagree.
 */
@Singleton
class TrackSourceResolver @Inject constructor(
    private val registry: QobuzIdRegistry,
) {
    fun of(track: Track): SourceType = when {
        track.deezerId != null || registry.isDeezerTrack(track.id) -> SourceType.DEEZER
        registry.isAppleTrack(track.id) -> SourceType.APPLE
        registry.isQobuzTrack(track.id) -> SourceType.QOBUZ
        else -> SourceType.API
    }
}

/** The app's [TrackSourceResolver], for rows that only have a [Track]; null shows no tag. */
val LocalTrackSource = staticCompositionLocalOf<(Track) -> SourceType?> { { null } }
