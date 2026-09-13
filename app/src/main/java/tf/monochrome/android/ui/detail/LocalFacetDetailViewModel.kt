package tf.monochrome.android.ui.detail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Style
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import tf.monochrome.android.data.local.repository.LocalMediaRepository
import tf.monochrome.android.domain.model.UnifiedTrack
import javax.inject.Inject

/**
 * A tag the local library can be sliced by, one screen per value.
 *
 * These four share a screen because they are the same screen: a name, a track
 * count, play and shuffle, and the tracks. Albums and artists do not appear
 * here — they have rows of their own in the database, artwork, and detail
 * screens that show it, so they keep theirs.
 *
 * [key] is what travels in the route. It is spelled out rather than derived
 * from [name] so renaming a constant cannot silently break a deep link, and
 * [fromKey] falls back to [GENRE] rather than crashing on an unknown one.
 */
enum class LocalFacet(
    val key: String,
    /** What one value of this facet is called, for the search placeholder. */
    val noun: String,
    val icon: ImageVector,
) {
    GENRE("genre", "genre", Icons.Default.Style),
    ALBUM_ARTIST("album_artist", "album artist", Icons.Default.Groups),
    COMPOSER("composer", "composer", Icons.Default.Piano),
    YEAR("year", "year", Icons.Default.CalendarMonth);

    companion object {
        fun fromKey(key: String?): LocalFacet =
            entries.firstOrNull { it.key == key } ?: GENRE
    }
}

@HiltViewModel
class LocalFacetDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    localMediaRepository: LocalMediaRepository
) : ViewModel() {

    val facet: LocalFacet = LocalFacet.fromKey(savedStateHandle.get<String>("facet"))

    // Navigation already decoded this once; a second URLDecoder call here was
    // what used to mangle genres containing '+' or '%'.
    val value: String = savedStateHandle.get<String>("value").orEmpty()

    val tracks: StateFlow<List<UnifiedTrack>> = tracksFlow(localMediaRepository)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun tracksFlow(repo: LocalMediaRepository): Flow<List<UnifiedTrack>> {
        if (value.isBlank()) return MutableStateFlow(emptyList())
        return when (facet) {
            LocalFacet.GENRE -> repo.getTracksByGenre(value)
            LocalFacet.ALBUM_ARTIST -> repo.getTracksByAlbumArtist(value)
            LocalFacet.COMPOSER -> repo.getTracksByComposer(value)
            // The only facet whose column is not text. A route carrying
            // something that is not a year matches no rows rather than
            // throwing on the way in.
            LocalFacet.YEAR -> value.toIntOrNull()
                ?.let { repo.getTracksByYear(it) }
                ?: flowOf(emptyList())
        }
    }
}
