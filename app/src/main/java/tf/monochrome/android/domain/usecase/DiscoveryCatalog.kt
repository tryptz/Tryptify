package tf.monochrome.android.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import tf.monochrome.android.data.api.ApiService
import tf.monochrome.android.data.api.InstanceManager
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.ArtistDetail
import tf.monochrome.android.domain.model.SearchResult
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import javax.inject.Inject
import javax.inject.Singleton

/** The catalogues Discover can find its music on, in the order the page offers them. */
val DISCOVERY_SERVICES = listOf(ApiService.TIDAL, ApiService.QOBUZ, ApiService.DEEZER)

/** What Discover used before it could be chosen, and what a new install starts on. */
val DEFAULT_DISCOVERY_SERVICE = ApiService.QOBUZ

/** A stored name back to a service Discover can use; anything else is the default. */
internal fun discoveryServiceFromName(name: String?): ApiService =
    DISCOVERY_SERVICES.firstOrNull { it.name == name } ?: DEFAULT_DISCOVERY_SERVICE

/**
 * The key a catalogue answer is remembered under.
 *
 * Qobuz keeps the bare key, so the answers already saved on disk from before
 * the choice existed (all of them Qobuz) stay valid. The others are prefixed:
 * "AIROD Acid Storm" found on TIDAL is a TIDAL track, and handing it back while
 * Qobuz is chosen would play it from the wrong service under the wrong label.
 */
internal fun discoveryMemoKey(service: ApiService, key: String): String =
    if (service == ApiService.QOBUZ) key else service.name.lowercase() + ":" + key

/**
 * One catalogue, as Discover talks to it.
 *
 * Discover was written against Qobuz alone — every search, artist page and
 * album fetch, and the tagging that tells the player where a track plays from.
 * This is the one place those calls are made, so the page can be pointed at
 * TIDAL or Deezer instead and every shelf, chart and genre play follows it.
 *
 * What differs between them, and what the rest of Discover may rely on:
 * * **Tracks are tagged with their service** ([unified]), so a Qobuz id is never
 *   played as the TIDAL track with the same number.
 * * **Artist ids are registered** where the artist screen routes by registry
 *   (Qobuz, Deezer); a TIDAL id is the screen's default and needs nothing.
 * * **Deezer has no similar-artists list.** [artist] returns it empty, and the
 *   shelf built from it comes up short rather than borrowing another service's.
 * * **Deezer tracks play from Qobuz when Qobuz has the recording**, and as the
 *   30-second preview when it does not. The page says so when Deezer is chosen.
 */
class DiscoveryCatalog internal constructor(
    val service: ApiService,
    private val music: MusicRepository,
    private val registry: QobuzIdRegistry,
) {
    suspend fun search(query: String, offset: Int = 0): Result<SearchResult> = when (service) {
        ApiService.TIDAL -> music.search(query, offset)
        ApiService.DEEZER -> music.searchDeezer(query, offset)
        else -> music.searchQobuz(query, offset)
    }

    suspend fun artist(id: Long): Result<ArtistDetail> = when (service) {
        ApiService.TIDAL -> music.getArtist(id)
        ApiService.DEEZER -> music.getDeezerArtist(id)
        else -> music.getQobuzArtist(id)
    }

    /**
     * An album's tracks, or null when it can't be had. A Qobuz album is
     * fetched by the slug its search registered; the others by id.
     */
    suspend fun albumTracks(album: Album): List<Track>? = when (service) {
        ApiService.TIDAL -> music.getAlbum(album.id).getOrNull()?.tracks
        ApiService.DEEZER -> music.getDeezerAlbum(album.id).getOrNull()?.tracks
        else -> registry.albumSlugFor(album.id)?.let { slug -> music.getQobuzAlbum(slug).getOrNull()?.tracks }
    }

    /** A track from this catalogue, tagged to play from it. */
    fun unified(track: Track): UnifiedTrack = when (service) {
        ApiService.TIDAL -> track.toUnifiedTrack()
        ApiService.DEEZER -> track.toDeezerUnifiedTrack()
        else -> track.toQobuzUnifiedTrack()
    }

    /**
     * Tags credited artist ids as this catalogue's, so a tapped artist opens on
     * the right service. A featured artist on a Qobuz track is a Qobuz id, and
     * without this the artist screen would look it up on TIDAL.
     */
    fun registerArtists(ids: List<Long>) {
        val real = ids.filter { it > 0L }.distinct()
        when (service) {
            ApiService.QOBUZ -> real.forEach { registry.registerArtist(it) }
            ApiService.DEEZER -> real.forEach { registry.registerDeezerArtist(it) }
            else -> Unit
        }
    }

    /** See [discoveryMemoKey]. */
    fun memoKey(key: String): String = discoveryMemoKey(service, key)
}

/**
 * Which catalogue Discover uses, and the way to change it.
 *
 * The choice is held here in memory as well as stored, and [select] sets the
 * memory first: the page rebuilds the moment it is switched, and a build that
 * read the stored value before the write landed would fetch the whole page
 * from the service just left.
 */
@Singleton
class DiscoveryCatalogs @Inject constructor(
    private val music: MusicRepository,
    private val registry: QobuzIdRegistry,
    private val preferences: PreferencesManager,
    private val instances: InstanceManager,
) {
    private val chosen = MutableStateFlow<ApiService?>(null)

    private val catalogs = DISCOVERY_SERVICES.associateWith { DiscoveryCatalog(it, music, registry) }

    fun of(service: ApiService): DiscoveryCatalog =
        catalogs[service] ?: catalogs.getValue(DEFAULT_DISCOVERY_SERVICE)

    suspend fun currentService(): ApiService {
        chosen.value?.let { return it }
        val stored = discoveryServiceFromName(preferences.discoveryService.first())
        chosen.compareAndSet(null, stored)
        return chosen.value ?: stored
    }

    /** The catalogue Discover is on right now. */
    suspend fun current(): DiscoveryCatalog = of(currentService())

    /** The chosen service, as it changes. */
    val selected: Flow<ApiService> = flow {
        emit(currentService())
        emitAll(chosen.filterNotNull())
    }.distinctUntilChanged()

    suspend fun select(service: ApiService) {
        if (service !in DISCOVERY_SERVICES) return
        chosen.value = service
        preferences.setDiscoveryService(service.name)
    }

    /** The services with a server under Settings › Connections. */
    suspend fun available(): Set<ApiService> = DISCOVERY_SERVICES.filterTo(LinkedHashSet()) { service ->
        when (service) {
            ApiService.TIDAL -> instances.tidalInstanceOrNull() != null
            ApiService.QOBUZ -> instances.qobuzInstanceOrNull() != null
            ApiService.DEEZER -> instances.deezerInstanceOrNull() != null
            else -> false
        }
    }
}
