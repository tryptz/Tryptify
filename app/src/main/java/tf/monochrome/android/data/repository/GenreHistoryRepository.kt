package tf.monochrome.android.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.GenreHistory
import tf.monochrome.android.domain.model.GenreHistoryData
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the researched genre histories, on demand and never on the main thread.
 *
 * Unlike [GenreGraphRepository] this is *not* a `by lazy` off whichever thread
 * asks first. The asset is the better part of a megabyte of prose — an order of
 * magnitude more than the graph — and the graph's blocking lazy is only
 * defensible because search needs it during startup anyway. Nothing needs this
 * one until a listener expands the genre panel, so it is a suspending load on
 * the IO dispatcher, and a frame is never spent parsing it.
 *
 * Parsed at most once. The mutex is what makes that true: two panels expanded
 * in quick succession both find no cache, and without it both would decode the
 * whole asset.
 *
 * Fails soft, like every other asset here — a missing or malformed file means
 * the panel says the history isn't written yet, which is exactly what it says
 * for the genres the research legitimately found nothing for.
 */
@Singleton
class GenreHistoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()

    @Volatile
    private var cache: GenreHistoryData? = null

    /** Everything, loading it first if this is the first caller. */
    suspend fun data(): GenreHistoryData {
        cache?.let { return it }
        return mutex.withLock {
            cache ?: load().also { cache = it }
        }
    }

    /** The researched history for a genre, or null when there isn't one. */
    suspend fun of(genreId: String): GenreHistory? = data().entries[genreId]

    private suspend fun load(): GenreHistoryData = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            json.decodeFromString(GenreHistoryData.serializer(), text)
        }.getOrDefault(GenreHistoryData())
    }

    private companion object {
        const val ASSET = "genre_history.json"
    }
}
