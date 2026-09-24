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
import tf.monochrome.android.data.api.SpotifyApiClient
import tf.monochrome.android.data.api.SpotifyIdRegistry
import tf.monochrome.android.data.downloads.DownloadManager
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.AlbumDetail
import tf.monochrome.android.domain.usecase.toSpotifyCatalogDetail
import javax.inject.Inject

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MusicRepository,
    private val qobuzIdRegistry: QobuzIdRegistry,
    private val spotifyApiClient: SpotifyApiClient,
    private val spotifyIdRegistry: SpotifyIdRegistry,
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

            // Spotify first when this id came out of the Spotify catalog: the
            // hashed numeric id is recorded in SpotifyIdRegistry with the real
            // base62 id it stands for. Spotify, Apple and Qobuz ids share no
            // namespace, so trying the others with a Spotify id just wastes a
            // round trip and surfaces a misleading error.
            val spotifyBase62 = spotifyIdRegistry.albumBase62For(albumId)
            val spotifyResult = if (spotifyBase62 != null) {
                spotifyApiClient.getAlbum(spotifyBase62)
                    .mapCatching { it.toSpotifyCatalogDetail(spotifyIdRegistry)
                        ?: error("Spotify album unavailable") }
            } else null

            // Apple next when this id came out of the Apple catalog: Apple and
            // Qobuz ids share no namespace, so trying Qobuz with an Apple id
            // just wastes a round trip and returns an error.
            val appleResult = if (spotifyResult?.isSuccess == true) null
                else if (qobuzIdRegistry.isAppleAlbum(albumId)) {
                    repository.getAppleAlbum(albumId)
                } else null

            val qobuzSlug = if (spotifyResult?.isSuccess == true || appleResult?.isSuccess == true) null
                else qobuzIdRegistry.albumSlugFor(albumId)
            val qobuzResult = if (appleResult?.isSuccess == true || spotifyResult?.isSuccess == true) null
                else qobuzSlug?.let { repository.getQobuzAlbum(it) }

            val finalResult = when {
                spotifyResult?.isSuccess == true -> spotifyResult
                appleResult?.isSuccess == true -> appleResult
                qobuzResult?.isSuccess == true -> qobuzResult
                else -> repository.getAlbum(albumId)
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
