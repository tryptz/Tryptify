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
import tf.monochrome.android.R
import tf.monochrome.android.ui.components.errorText

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

    private val _error = MutableStateFlow<tf.monochrome.android.ui.components.UiText?>(null)
    val error: StateFlow<tf.monochrome.android.ui.components.UiText?> = _error.asStateFlow()

    /** One-shot messages for the artist screen (download-all outcome). */
    private val _downloadMessage = MutableSharedFlow<tf.monochrome.android.ui.components.UiText>(extraBufferCapacity = 2)
    val downloadMessage: SharedFlow<tf.monochrome.android.ui.components.UiText> = _downloadMessage.asSharedFlow()

    private val _source = MutableStateFlow<tf.monochrome.android.domain.model.SourceType?>(null)

    /** The catalog this artist was loaded from, for its source tag. */
    val source: StateFlow<tf.monochrome.android.domain.model.SourceType?> = _source

    // Last: Kotlin runs initializers top to bottom, and loadArtist's coroutine
    // starts at once (Main.immediate). An artist already cached never
    // suspends, so it writes every flow above before the constructor returns;
    // a flow declared below this block would still be null then.
    init {
        loadArtist()
    }

    /**
     * Catalog-true load. An id registered to a catalog (Deezer, Apple, Qobuz)
     * loads from it alone; only an unregistered id is looked up across TIDAL,
     * Qobuz and Apple, and the page is tagged with the one that answered. This is what fixes the
     * "No API instances available" error: on a Qobuz-only setup the TIDAL pool
     * is empty, and an artist reached from a now-playing Qobuz track isn't
     * pre-registered, so it must still resolve via /api/get-artist. Both layers
     * are Result-wrapped so a miss surfaces as a clean error instead of a crash.
     */
    private fun loadArtist() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            // Deezer artist ids, like Deezer album ids, resolve to someone else
            // anywhere but Deezer — so Deezer only, and a miss stays a miss.
            if (qobuzIdRegistry.isDeezerArtist(artistId)) {
                repository.getDeezerArtist(artistId)
                    .onSuccess { _artistDetail.value = it; _source.value = tf.monochrome.android.domain.model.SourceType.DEEZER }
                    .onFailure { _error.value = errorText(it, R.string.error_load_artist) }
                _isLoading.value = false
                return@launch
            }

            // A fallback-played TIDAL track links its TIDAL artist id to the
            // matched Qobuz artist id; use that for the Qobuz call when present.
            val aliasQobuzId = qobuzIdRegistry.qobuzArtistIdFor(artistId)
            val qobuzArtistId = aliasQobuzId ?: artistId
            val preferQobuz = aliasQobuzId != null || qobuzIdRegistry.isQobuzArtist(artistId)

            // An id the registry knows belongs to one catalog loads from that
            // catalog only: ids overlap across catalogs, so asking another one
            // for the same number can open somebody else's page. Only an id
            // nobody has claimed — an artist reached from a row that never
            // registered it — is looked up across the catalogs, and the page
            // is tagged with whichever one answered.
            val ordered = buildList<Pair<tf.monochrome.android.domain.model.SourceType, suspend () -> Result<ArtistDetail>>> {
                when {
                    qobuzIdRegistry.isAppleArtist(artistId) ->
                        add(tf.monochrome.android.domain.model.SourceType.APPLE to { repository.getAppleArtist(artistId) })
                    preferQobuz ->
                        add(tf.monochrome.android.domain.model.SourceType.QOBUZ to { repository.getQobuzArtist(qobuzArtistId) })
                    else -> {
                        add(tf.monochrome.android.domain.model.SourceType.API to { repository.getArtist(artistId) })
                        add(tf.monochrome.android.domain.model.SourceType.QOBUZ to { repository.getQobuzArtist(qobuzArtistId) })
                        add(tf.monochrome.android.domain.model.SourceType.APPLE to { repository.getAppleArtist(artistId) })
                    }
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
                        _source.value = tf.monochrome.android.domain.model.SourceType.API
                        _isLoading.value = false
                        return@launch
                    }
                }
                _error.value = tf.monochrome.android.ui.components.UiText.Res(R.string.error_artist_not_in_catalogues, listOf(artistName))
                _isLoading.value = false
                return@launch
            }

            var firstFailure: Result<ArtistDetail>? = null
            var realFailure: Result<ArtistDetail>? = null
            var success: Result<ArtistDetail>? = null
            for ((source, attempt) in ordered) {
                val r = attempt()
                if (r.isSuccess) { success = r; _source.value = source; break }
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
                .onFailure { _error.value = errorText(it, R.string.error_load_artist) }
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
                        val downloading = tf.monochrome.android.ui.components.UiText.Plural(R.plurals.downloading_tracks, tracks.size)
                        _downloadMessage.tryEmit(
                            if (failed > 0) {
                                tf.monochrome.android.ui.components.UiText.Res(
                                    R.string.downloading_with_failures,
                                    listOf(downloading, tf.monochrome.android.ui.components.UiText.Plural(R.plurals.releases_count, failed)),
                                )
                            } else {
                                downloading
                            }
                        )
                    }
                    else -> _downloadMessage.tryEmit(tf.monochrome.android.ui.components.UiText.Res(R.string.error_fetch_releases))
                }
            } finally {
                _isDownloadingAll.value = false
            }
        }
    }
}
