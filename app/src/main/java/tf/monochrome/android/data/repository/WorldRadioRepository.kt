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
     * Stations matching a typed name. Null when the directory couldn't be
     * reached, empty when it was asked and had nothing — the same distinction
     * the panel makes, for the same reason.
     */
    suspend fun searchStations(query: String): List<RadioStation>? {
        if (query.isBlank()) return emptyList()
        return runCatching { client.searchByName(query.trim()) }.getOrNull()
    }

    /**
     * Cities matching what is being typed, best first.
     *
     * Answered from the bundled asset, so it costs no network and appears while
     * the directory is still being asked — which is the point of having it
     * alongside the station results rather than instead of them.
     *
     * Prefix matches rank above substring ones: someone typing "man" means
     * Manchester or Manila long before they mean Oman. Within each, the busiest
     * city wins, which is the same ordering the dots on the globe already use.
     */
    suspend fun searchCities(query: String, limit: Int = CITY_SUGGESTIONS): List<RadioCity> {
        val typed = query.trim().lowercase()
        if (typed.isBlank()) return emptyList()
        return data().cities.asSequence()
            .mapNotNull { city ->
                val name = city.name.lowercase()
                val ascii = city.ascii.lowercase()
                val rank = when {
                    name.startsWith(typed) || ascii.startsWith(typed) -> 0
                    name.contains(typed) || ascii.contains(typed) -> 1
                    else -> return@mapNotNull null
                }
                city to rank
            }
            .sortedWith(compareBy({ it.second }, { -it.first.stations }))
            .take(limit)
            .map { it.first }
            .toList()
    }

    /**
     * Where on the globe to fly for a station found by name.
     *
     * The directory records a station's country but not its coordinates, and the
     * globe can only fly to a city it actually has. So: among the cities of the
     * station's country, prefer one this station names — broadcasters put their
     * city in their own name far more often than not ("Radio Hamburg", "NYC
     * Sound") — and otherwise fall back to that country's busiest.
     *
     * Returns null for a station whose country is blank or is not on the globe,
     * which is the honest answer: better to play it from where you are than to
     * fly somewhere and claim it is from there.
     */
    suspend fun locate(station: RadioStation): RadioCity? {
        val code = station.countryCode.trim()
        if (code.isBlank()) return null
        val candidates = data().cities.filter { it.country.equals(code, ignoreCase = true) }
        if (candidates.isEmpty()) return null

        val haystack = "${station.name} ${station.tags}".lowercase()
        val named = candidates.filter { city ->
            // Word-boundary rather than contains: "Ely" is a city, and matching
            // it inside "Barely Radio" would fly the globe to Cambridgeshire.
            val name = city.name.lowercase()
            val ascii = city.ascii.lowercase()
            haystack.containsWord(name) || (ascii.isNotBlank() && haystack.containsWord(ascii))
        }
        // Among several named, and among none, the busiest is the best guess:
        // it is the one a listener is most likely to have meant.
        return (named.ifEmpty { candidates }).maxByOrNull { it.stations }
    }

    private fun String.containsWord(word: String): Boolean {
        if (word.isBlank()) return false
        var from = 0
        while (true) {
            val at = indexOf(word, from)
            if (at < 0) return false
            val before = at - 1
            val after = at + word.length
            val startOk = before < 0 || !this[before].isLetterOrDigit()
            val endOk = after >= length || !this[after].isLetterOrDigit()
            if (startOk && endOk) return true
            from = at + 1
        }
    }

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

        // Enough to cover the near-misses of a half-typed name, few enough that
        // the cities never push the station results off the end of the row.
        const val CITY_SUGGESTIONS = 6
    }
}
