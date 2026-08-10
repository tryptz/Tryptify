package tf.monochrome.android.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.downloads.DownloadManager
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.data.api.NoInstancesConfiguredException
import tf.monochrome.android.domain.model.ArtistDetail
import javax.inject.Inject

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MusicRepository,
    private val qobuzIdRegistry: QobuzIdRegistry,
    private val downloadManager: DownloadManager,
) : ViewModel() {

    private val artistId: Long = savedStateHandle.get<Long>("artistId") ?: 0L

    /** The fallback identity, when the row that opened this had no id. */
    private val artistName: String = savedStateHandle.get<String>("name").orEmpty()

    private val _artistDetail = MutableStateFlow<ArtistDetail?>(null)
    val artistDetail: StateFlow<ArtistDetail?> = _artistDetail.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** One-shot messages for the artist screen (download-all outcome). */
    private val _downloadMessage = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val downloadMessage: SharedFlow<String> = _downloadMessage.asSharedFlow()

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

            // Try the catalog this id actually belongs to first, then the other
            // one, then TIDAL. Apple and Qobuz ids share no namespace, so order
            // matters: querying the wrong catalog returns a clean miss, never
            // the right artist.
            val ordered = buildList<suspend () -> Result<ArtistDetail>> {
                if (qobuzIdRegistry.isAppleArtist(artistId)) {
                    add { repository.getAppleArtist(artistId) }
                }
                if (preferQobuz) {
                    add { repository.getQobuzArtist(qobuzArtistId) }
                    add { repository.getArtist(artistId) }
                } else {
                    add { repository.getArtist(artistId) }
                    add { repository.getQobuzArtist(qobuzArtistId) }
                }
                if (!qobuzIdRegistry.isAppleArtist(artistId)) {
                    add { repository.getAppleArtist(artistId) }
                }
            }

            // Which failure gets *reported* matters as much as the order they
            // are tried in. Keeping the first one meant a Qobuz-only setup
            // always showed "No API instances available" — the TIDAL pool being
            // empty is the first thing that goes wrong and the least
            // informative thing that went wrong, since that pool was never the
            // one holding this artist. It sent people to configure instances
            // for a catalogue they don't use, for an artist that failed
            // somewhere else entirely.
            //
            // So a missing pool is only reported when every source failed that
            // way, i.e. when there is genuinely nothing configured to ask.
            // An id of 0 is not an artist that failed to load, it is an artist
            // we were never told the identity of — some catalogue rows reach
            // the player with a name and no id. Searching the catalogue for the
            // name recovers a real id, which is the only thing that makes the
            // link work at all; every lookup below needs one.
            if (artistId <= 0L && artistName.isNotBlank()) {
                val found = repository.searchArtists(artistName, limit = 5).getOrNull()
                    ?.firstOrNull { it.name.equals(artistName, ignoreCase = true) && it.id > 0 }
                if (found != null) {
                    val recovered = repository.getArtist(found.id)
                    if (recovered.isSuccess) {
                        _artistDetail.value = recovered.getOrThrow()
                        _isLoading.value = false
                        return@launch
                    }
                }
                _error.value = "Couldn't find \"$artistName\" in your catalogues."
                _isLoading.value = false
                return@launch
            }

            var firstFailure: Result<ArtistDetail>? = null
            var realFailure: Result<ArtistDetail>? = null
            var success: Result<ArtistDetail>? = null
            for (attempt in ordered) {
                val r = attempt()
                if (r.isSuccess) { success = r; break }
                if (firstFailure == null) firstFailure = r
                if (realFailure == null &&
                    r.exceptionOrNull() !is NoInstancesConfiguredException
                ) {
                    realFailure = r
                }
            }
            val finalResult = success
                ?: realFailure
                ?: firstFailure
                ?: Result.failure(Exception("Failed to load artist"))

            finalResult
                .onSuccess { _artistDetail.value = it }
                .onFailure { _error.value = it.message ?: "Failed to load artist" }
            _isLoading.value = false
        }
    }

    fun retry() = loadArtist()

    private val _isDownloadingAll = MutableStateFlow(false)
    val isDownloadingAll: StateFlow<Boolean> = _isDownloadingAll.asStateFlow()

    /**
     * Download every release for this artist. The artist payload carries albums but
     * not their tracks, so each release is resolved (Qobuz slug first, else TIDAL)
     * in parallel, then all resulting tracks are queued.
     */
    fun downloadAllReleases() {
        val detail = _artistDetail.value ?: return
        if (_isDownloadingAll.value) return
        val releases = detail.albums + detail.eps + detail.singles
        if (releases.isEmpty()) return
        viewModelScope.launch {
            _isDownloadingAll.value = true
            try {
                val perRelease = coroutineScope {
                    releases.map { album ->
                        async {
                            val slug = qobuzIdRegistry.albumSlugFor(album.id)
                            val result = if (slug != null) {
                                repository.getQobuzAlbum(slug)
                            } else {
                                repository.getAlbum(album.id)
                            }
                            result.getOrNull()?.tracks
                        }
                    }.awaitAll()
                }
                val failed = perRelease.count { it == null }
                val tracks = perRelease.filterNotNull().flatten().distinctBy { it.id }
                when {
                    tracks.isNotEmpty() -> {
                        downloadManager.downloadTracks(tracks)
                        _downloadMessage.tryEmit(
                            if (failed > 0) "Downloading ${tracks.size} tracks (couldn't fetch $failed release${if (failed == 1) "" else "s"})"
                            else "Downloading ${tracks.size} tracks"
                        )
                    }
                    else -> _downloadMessage.tryEmit("Couldn't fetch releases. Check your connection.")
                }
            } finally {
                _isDownloadingAll.value = false
            }
        }
    }
}
