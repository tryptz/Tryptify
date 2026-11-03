package tf.monochrome.android.ui.player

import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack

/** What the player asks the lyrics sources for, for a track from wherever it came. */
internal object LyricsLookup {

    /**
     * Whether TIDAL's lyrics by id must be skipped for a track from [source]:
     * its id is not a TIDAL id. Qobuz and Deezer ids name other songs on
     * TIDAL; a local file's or a collection's is a hash of its own key
     * ("local_123"), which TIDAL either does not know or knows as some other
     * song entirely, and a YouTube or SoundCloud song's a hash of its server
     * id. TIDAL is then matched by title and artist instead.
     */
    fun skipsTidalById(source: SourceType?): Boolean = when (source) {
        SourceType.QOBUZ, SourceType.DEEZER, SourceType.LOCAL, SourceType.COLLECTION,
        SourceType.YOUTUBE, SourceType.SOUNDCLOUD -> true
        else -> false
    }

    /**
     * [track] as the lyrics sources should see it. A local file plays under
     * its album artist, so a compilation's every track was searched as
     * "Various Artists" and found nothing: the search takes the track's own
     * artist, as tagged, instead.
     */
    fun trackFor(track: Track, unified: UnifiedTrack?): Track {
        if (unified?.sourceType != SourceType.LOCAL) return track
        val own = unified.artistNames.firstOrNull { it.isNotBlank() } ?: return track
        if (own == track.artist?.name) return track
        return track.copy(artist = Artist(id = own.hashCode().toLong(), name = own))
    }
}
