package tf.monochrome.android.domain.usecase

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.RadarRelease
import tf.monochrome.android.domain.model.ReleaseRadar
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the release radar: the discographies of the artists the listener
 * plays most, on the catalogue Discover is on, narrowed by [ReleaseRadar].
 *
 * A dozen artists is two dozen requests, so the answer is kept for
 * [TTL_MS] per service — in memory, and on disk for the next launch — and
 * opening Discover again inside that does not ask again. The requests are
 * gated three at a time with a budget each, so one slow artist page costs its
 * own budget and not everyone's. An answer from a service that could not be
 * reached at all is not kept: "unreachable on the train" must not read as
 * "nothing new" for six hours.
 */
@Singleton
class ReleaseRadarUseCase @Inject constructor(
    private val catalogs: DiscoveryCatalogs,
    private val library: LibraryRepository,
    @ApplicationContext private val context: Context,
) {
    @Serializable
    private data class Cached(val service: String, val at: Long, val releases: List<RadarRelease>)

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var memory: Cached? = null
    private val file: File get() = File(context.cacheDir, FILE_NAME)

    /** The radar for the current service; [force] skips the cache. */
    suspend fun releases(force: Boolean = false): List<RadarRelease> {
        val service = catalogs.currentService()
        val now = System.currentTimeMillis()
        if (!force) cached(service.name, now)?.let { return it }
        val fresh = fetch(catalogs.of(service)) ?: return emptyList()
        store(Cached(service.name, now, fresh))
        return fresh
    }

    /** Null when not one artist could be asked: the service was unreachable. */
    private suspend fun fetch(catalog: DiscoveryCatalog): List<RadarRelease>? {
        val artists = runCatching { library.getSeedArtistNames(ARTISTS) }.getOrDefault(emptyList())
        if (artists.isEmpty()) return emptyList()
        val gate = Semaphore(CONCURRENCY)
        val answers = coroutineScope {
            artists.map { name ->
                async { name to gate.withPermit { withTimeoutOrNull(ARTIST_BUDGET_MS) { discography(catalog, name) } } }
            }.awaitAll()
        }
        if (answers.all { it.second == null }) return null
        val byArtist = answers.mapNotNull { (name, albums) -> albums?.let { name to it } }.toMap()
        return ReleaseRadar.select(byArtist, LocalDate.now())
    }

    /**
     * Everything [name] has released, or null when the catalogue did not
     * answer. An artist it answered for but does not carry is an empty list.
     */
    private suspend fun discography(catalog: DiscoveryCatalog, name: String): List<Album>? {
        val search = catalog.search(name).getOrNull() ?: return null
        val artist = search.artists.firstOrNull { matchesArtistName(it.name, name) } ?: return emptyList()
        val detail = catalog.artist(artist.id).getOrNull() ?: return null
        catalog.registerArtists(listOf(artist.id))
        return detail.albums + detail.eps + detail.singles
    }

    private suspend fun cached(service: String, now: Long): List<RadarRelease>? = mutex.withLock {
        val held = memory ?: readFile().also { memory = it }
        held?.takeIf { it.service == service && now - it.at in 0 until TTL_MS }?.releases
    }

    private suspend fun store(cached: Cached) = mutex.withLock {
        memory = cached
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(Cached.serializer(), cached)) }
        }
    }

    private suspend fun readFile(): Cached? = withContext(Dispatchers.IO) {
        runCatching {
            if (file.exists()) json.decodeFromString(Cached.serializer(), file.readText()) else null
        }.getOrNull()
    }

    private companion object {
        const val FILE_NAME = "release-radar.json"
        const val ARTISTS = 12
        const val CONCURRENCY = 3
        const val ARTIST_BUDGET_MS = 8_000L
        const val TTL_MS = 6 * 60 * 60 * 1000L
    }
}
