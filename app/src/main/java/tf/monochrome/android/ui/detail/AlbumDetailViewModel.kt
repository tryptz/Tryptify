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
import tf.monochrome.android.data.downloads.DownloadManager
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.AlbumDetail
import javax.inject.Inject

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MusicRepository,
    private val qobuzIdRegistry: QobuzIdRegistry,
    private val downloadManager: DownloadManager,
) : ViewModel() {

    private val albumId: Long = savedStateHandle.get<Long>("albumId") ?: 0L

    private val _albumDetail = MutableStateFlow<AlbumDetail?>(null)
    val albumDetail: StateFlow<AlbumDetail?> = _albumDetail.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadAlbum()
    }

    /**
     * Qobuz-aware load: when the registry has an alphanumeric slug for this
     * numeric id (i.e. the user navigated from a Qobuz search hit), hit the
     * trypt-hifi /api/get-album endpoint. Otherwise — and on Qobuz failure
     * — fall back to the TIDAL pool's /album endpoint. Either branch
     * surfaces a clean error string instead of crashing on HTML responses,
     * because both repository methods return Result.
     */
    private fun loadAlbum() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            val qobuzSlug = qobuzIdRegistry.albumSlugFor(albumId)
            val qobuzResult = qobuzSlug?.let { repository.getQobuzAlbum(it) }

            val finalResult = if (qobuzResult?.isSuccess == true) {
                qobuzResult
            } else {
                repository.getAlbum(albumId)
            }

            finalResult
                .onSuccess { _albumDetail.value = it }
                .onFailure { _error.value = it.message ?: "Failed to load album" }
            _isLoading.value = false
        }
    }

    fun retry() = loadAlbum()

    /** Queue every track on the loaded album for download. */
    fun downloadAll() {
        _albumDetail.value?.tracks?.takeIf { it.isNotEmpty() }
            ?.let { downloadManager.downloadTracks(it) }
    }
}
