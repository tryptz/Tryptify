package tf.monochrome.android.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.ArtistDetail
import javax.inject.Inject

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MusicRepository,
    private val qobuzIdRegistry: QobuzIdRegistry,
) : ViewModel() {

    private val artistId: Long = savedStateHandle.get<Long>("artistId") ?: 0L

    private val _artistDetail = MutableStateFlow<ArtistDetail?>(null)
    val artistDetail: StateFlow<ArtistDetail?> = _artistDetail.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadArtist()
    }

    /**
     * Source-agnostic load with a two-way fallback. We try the most likely
     * source first — Qobuz when the registry has tagged this id as Qobuz (a
     * search hit / top-track / album row), otherwise the TIDAL pool — then fall
     * back to the OTHER source if the first fails. This is what fixes the
     * "No API instances available" error: on a Qobuz-only setup the TIDAL pool
     * is empty, and an artist reached from a now-playing Qobuz track isn't
     * pre-registered, so it must still resolve via /api/get-artist. Both layers
     * are Result-wrapped so a miss surfaces as a clean error instead of a crash.
     */
    private fun loadArtist() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            // A fallback-played TIDAL track links its TIDAL artist id to the
            // matched Qobuz artist id; use that for the Qobuz call when present.
            val aliasQobuzId = qobuzIdRegistry.qobuzArtistIdFor(artistId)
            val qobuzArtistId = aliasQobuzId ?: artistId
            val preferQobuz = aliasQobuzId != null || qobuzIdRegistry.isQobuzArtist(artistId)
            val primary = if (preferQobuz) {
                repository.getQobuzArtist(qobuzArtistId)
            } else {
                repository.getArtist(artistId)
            }
            val finalResult = if (primary.isSuccess) {
                primary
            } else {
                val secondary = if (preferQobuz) {
                    repository.getArtist(artistId)
                } else {
                    repository.getQobuzArtist(qobuzArtistId)
                }
                if (secondary.isSuccess) secondary else primary
            }

            finalResult
                .onSuccess { _artistDetail.value = it }
                .onFailure { _error.value = it.message ?: "Failed to load artist" }
            _isLoading.value = false
        }
    }

    fun retry() = loadArtist()
}
