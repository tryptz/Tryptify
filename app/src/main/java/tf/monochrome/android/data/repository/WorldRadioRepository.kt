package tf.monochrome.android.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import tf.monochrome.android.data.api.RadioBrowserClient
import tf.monochrome.android.domain.model.RadioCity
import tf.monochrome.android.domain.model.RadioStation
import tf.monochrome.android.domain.model.WorldRadioData
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The globe's geography, and the stations currently playing on it.
 *
 * Two very different lifetimes behind one door. The *map* — coastlines and the
 * cities that broadcast — is a bundled asset that never changes between releases,
 * so it is parsed once and held. The *stations* are fetched per city and cached
 * only for the session, because the directory is a moving target and a listing
 * from an hour ago is already slightly wrong.
 *
 * Loaded the way [GenreHistoryRepository] loads its prose rather than the way
 * [GenreGraphRepository] loads the graph: suspending, on IO, once. Nothing needs
 * the globe during startup, so no frame should ever be spent parsing it.
 *
 * Fails soft throughout. A missing asset means an empty globe with a message, and
 * an unreachable directory means "couldn't reach the directory" — which the panel
 * states as a different thing from "this city has no radio", because it is.
 */
@Singleton
class WorldRadioRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: RadioBrowserClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()

    @Volatile
    private var cache: WorldRadioData? = null

    private val stationCache = mutableMapOf<String, List<RadioStation>>()
    private val stationMutex = Mutex()

    /** The bundled globe, loading it first if this is the first caller. */
    suspend fun data(): WorldRadioData {
        cache?.let { return it }
        return mutex.withLock {
            cache ?: load().also { cache = it }
        }
    }

    /**
     * What is on air in a city right now.
     *
     * Cached per city for the session so that stepping between neighbours and
     * back doesn't re-query. Returns null — distinct from an empty list — when
     * the directory could not be reached at all.
     */
    suspend fun stations(city: RadioCity): List<RadioStation>? {
        stationMutex.withLock { stationCache[city.id] }?.let { return it }

        val found = client.stations(city.name, city.country).ifEmpty {
            // The local spelling is what stations usually print, but not always;
            // the ASCII form is the second thing worth asking for before giving
            // up on a city the build pass said has radio.
            if (city.ascii.isNotBlank() && !city.ascii.equals(city.name, true)) {
                client.stations(city.ascii, city.country)
            } else {
                emptyList()
            }
        }

        if (found.isEmpty()) return emptyList()
        stationMutex.withLock { stationCache[city.id] = found }
        return found
    }

    /** Tell the directory a station was played; it is what their ordering is built from. */
    suspend fun reportPlay(station: RadioStation) = client.reportClick(station.uuid)

    /**
     * The nearest other cities that also broadcast.
     *
     * Great-circle distance, because on a globe the flat one is wrong in exactly
     * the places the map is most interesting — near the poles, and across the
     * date line, where Anchorage and Vladivostok are neighbours and a naive
     * longitude subtraction says they are as far apart as it is possible to be.
     */
    suspend fun nearby(city: RadioCity, limit: Int = 6): List<RadioCity> {
        val all = data().cities
        val scale = data().scale.toDouble().takeIf { it > 0 } ?: 1.0
        return all.asSequence()
            .filter { it.id != city.id }
            .map { it to greatCircle(city, it, scale) }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun greatCircle(a: RadioCity, b: RadioCity, scale: Double): Double {
        val lat1 = Math.toRadians(a.lat / scale)
        val lat2 = Math.toRadians(b.lat / scale)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians((b.lon - a.lon) / scale)
        val h = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
            kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    private suspend fun load(): WorldRadioData = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            json.decodeFromString(WorldRadioData.serializer(), text)
        }.getOrDefault(WorldRadioData())
    }

    private companion object {
        const val ASSET = "world_radio.json"
    }
}
