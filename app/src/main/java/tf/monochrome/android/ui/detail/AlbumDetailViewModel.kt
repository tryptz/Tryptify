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
import tf.monochrome.android.R
import tf.monochrome.android.ui.components.errorText

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

    private val _error = MutableStateFlow<tf.monochrome.android.ui.components.UiText?>(null)
    val error: StateFlow<tf.monochrome.android.ui.components.UiText?> = _error.asStateFlow()

    private val _source = kotlinx.coroutines.flow.MutableStateFlow<tf.monochrome.android.domain.model.SourceType?>(null)

    /** The catalog this album was loaded from, for its source tag. */
    val source: kotlinx.coroutines.flow.StateFlow<tf.monochrome.android.domain.model.SourceType?> = _source

    // Last: Kotlin runs initializers top to bottom, and loadAlbum's coroutine
    // starts at once (Main.immediate). An album already cached never suspends,
    // so it writes every flow above before the constructor returns; a flow
    // declared below this block would still be null then.
    init {
        loadAlbum()
    }

    /**
     * Loads the album from the catalog its id belongs to — Deezer, Apple,
     * Qobuz (when the registry has its slug), else TIDAL — and only there.
     * Every branch surfaces a clean error string instead of crashing on HTML
     * responses, because the repository methods return Result.
     */
    private fun loadAlbum() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            // A Deezer album is only ever a Deezer album. Its id is a plain
            // number that Qobuz or TIDAL would happily resolve to some other
            // record, so there is no fallback: a miss is reported as a miss.
            if (qobuzIdRegistry.isDeezerAlbum(albumId)) {
                repository.getDeezerAlbum(albumId)
                    .onSuccess { _albumDetail.value = it; _source.value = tf.monochrome.android.domain.model.SourceType.DEEZER }
                    .onFailure { _error.value = errorText(it, R.string.error_load_album) }
                _isLoading.value = false
                return@launch
            }

            // Every album loads from its own catalog and no other. This used to
            // fall through Apple -> Qobuz -> TIDAL, and the last step asked
            // TIDAL for the Apple or Qobuz *number* — ids overlap across
            // catalogs, so a failed Apple or Qobuz album could open some
            // unrelated TIDAL album in its place. Now a miss is a miss, as it
            // already was for Deezer.
            val qobuzSlug = qobuzIdRegistry.albumSlugFor(albumId)
            val (source, finalResult) = when {
                qobuzIdRegistry.isAppleAlbum(albumId) ->
                    tf.monochrome.android.domain.model.SourceType.APPLE to repository.getAppleAlbum(albumId)
                qobuzSlug != null ->
                    tf.monochrome.android.domain.model.SourceType.QOBUZ to repository.getQobuzAlbum(qobuzSlug)
                else ->
                    tf.monochrome.android.domain.model.SourceType.API to repository.getAlbum(albumId)
            }

            finalResult
                .onSuccess { _albumDetail.value = it; _source.value = source }
                .onFailure { _error.value = errorText(it, R.string.error_load_album) }
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
