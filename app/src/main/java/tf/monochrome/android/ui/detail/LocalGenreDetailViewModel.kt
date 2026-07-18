package tf.monochrome.android.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import tf.monochrome.android.data.local.repository.LocalMediaRepository
import tf.monochrome.android.domain.model.UnifiedTrack
import javax.inject.Inject

@HiltViewModel
class LocalGenreDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    localMediaRepository: LocalMediaRepository
) : ViewModel() {

    // Navigation already decoded this once; the previous manual URLDecoder
    // call was a second decode that mangled genres containing '+' or '%'.
    val genreName: String = savedStateHandle.get<String>("genre").orEmpty()

    val tracks: StateFlow<List<UnifiedTrack>> =
        if (genreName.isBlank()) MutableStateFlow(emptyList())
        else localMediaRepository.getTracksByGenre(genreName)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
