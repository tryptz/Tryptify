package tf.monochrome.android.data.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private val Context.spotifyIdDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "spotify_id_registry")

/**
 * Registry mapping Spotify's base62 catalog ids onto the numeric `Long` ids the
 * app's navigation uses. Every album/artist screen takes a numeric route
 * argument (`album/{albumId}`), but Spotify ids are strings — so Spotify
 * catalog rows carry a stable 63-bit hash of the base62 id and this registry
 * records the hash → base62 pair (QobuzIdRegistry-style, persisted to a
 * DataStore so navigation keeps working across process death).
 *
 * Without the registry a hashed stand-in would open some unrelated catalogue
 * page; with it, `AlbumDetailViewModel` can tell "this numeric id came out of
 * the Spotify catalog" and route to the Web API instead of TIDAL/Qobuz — the
 * same trick the Qobuz slug registry and the Apple id sets use.
 *
 * The in-memory maps are the synchronous source of truth; persistence is
 * write-through (debounced) and reloaded at startup.
 */
@Singleton
class SpotifyIdRegistry @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore = context.spotifyIdDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val albumBase62ById = ConcurrentHashMap<Long, String>()
    private val trackBase62ById = ConcurrentHashMap<Long, String>()
    private val artistBase62ById = ConcurrentHashMap<Long, String>()

    // A Track holds its length in whole seconds, but the stream declared for
    // a Spotify track is cut at the length it is given — so a second-rounded
    // length drops the song's last fraction of a second. The exact length is
    // kept here, in memory only: after process death the second-rounded one
    // is what there is, as before.
    private val trackDurationMsById = ConcurrentHashMap<Long, Long>()

    // Coalesces frequent registrations (a single search registers dozens) into
    // one debounced disk write — same shape as QobuzIdRegistry.
    private val saveSignal = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        scope.launch { load() }
        scope.launch {
            @OptIn(FlowPreview::class)
            saveSignal.debounce(SAVE_DEBOUNCE_MS).collect { persist() }
        }
    }

    fun registerAlbum(numericId: Long, base62: String) {
        if (base62.isBlank()) return
        if (albumBase62ById.put(numericId, base62) != base62) markDirty()
    }

    fun albumBase62For(numericId: Long): String? = albumBase62ById[numericId]

    fun isSpotifyAlbum(numericId: Long): Boolean = albumBase62ById.containsKey(numericId)

    fun registerTrack(numericId: Long, base62: String, durationMs: Long = 0L) {
        if (base62.isBlank()) return
        if (durationMs > 0L) trackDurationMsById[numericId] = durationMs
        if (trackBase62ById.put(numericId, base62) != base62) markDirty()
    }

    /** The exact length of a registered track, if this process has seen it. */
    fun trackDurationMsFor(numericId: Long): Long? = trackDurationMsById[numericId]

    fun trackBase62For(numericId: Long): String? = trackBase62ById[numericId]

    fun isSpotifyTrack(numericId: Long): Boolean = trackBase62ById.containsKey(numericId)

    fun registerArtist(numericId: Long, base62: String) {
        if (base62.isBlank()) return
        if (artistBase62ById.put(numericId, base62) != base62) markDirty()
    }

    fun artistBase62For(numericId: Long): String? = artistBase62ById[numericId]

    fun isSpotifyArtist(numericId: Long): Boolean = artistBase62ById.containsKey(numericId)

    private fun markDirty() {
        saveSignal.tryEmit(Unit)
    }

    private suspend fun persist() {
        runCatching {
            val albumsJson = json.encodeToString(albumBase62ById.toMap())
            val tracksJson = json.encodeToString(trackBase62ById.toMap())
            val artistsJson = json.encodeToString(artistBase62ById.toMap())
            dataStore.edit { prefs ->
                prefs[KEY_ALBUM_IDS] = albumsJson
                prefs[KEY_TRACK_IDS] = tracksJson
                prefs[KEY_ARTIST_IDS] = artistsJson
            }
        }
    }

    private suspend fun load() {
        runCatching {
            val prefs = dataStore.data.first()
            prefs[KEY_ALBUM_IDS]?.let { raw ->
                runCatching { json.decodeFromString<Map<Long, String>>(raw) }.getOrNull()
                    ?.let { albumBase62ById.putAll(it) }
            }
            prefs[KEY_TRACK_IDS]?.let { raw ->
                runCatching { json.decodeFromString<Map<Long, String>>(raw) }.getOrNull()
                    ?.let { trackBase62ById.putAll(it) }
            }
            prefs[KEY_ARTIST_IDS]?.let { raw ->
                runCatching { json.decodeFromString<Map<Long, String>>(raw) }.getOrNull()
                    ?.let { artistBase62ById.putAll(it) }
            }
        }
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 750L
        val KEY_ALBUM_IDS = stringPreferencesKey("album_base62_by_numeric")
        val KEY_TRACK_IDS = stringPreferencesKey("track_base62_by_numeric")
        val KEY_ARTIST_IDS = stringPreferencesKey("artist_base62_by_numeric")
    }
}

/**
 * Stable non-negative numeric stand-in for a base62 Spotify id. Deterministic
 * (the same base62 id always maps to the same Long, in every session) so the
 * registry mapping survives persistence and dedup keys stay stable. A 63-bit
 * hash over a 22-character alphabet makes collisions between real catalog
 * ids vanishingly rare; the registry mapping is the authority either way.
 */
fun spotifyNumericIdFor(base62: String): Long {
    var h = 1125899906842597L // 2^50 + 2^32 + 2^23ish — arbitrary odd seed
    for (c in base62) {
        h = h * 31L + c.code
    }
    return h and Long.MAX_VALUE
}