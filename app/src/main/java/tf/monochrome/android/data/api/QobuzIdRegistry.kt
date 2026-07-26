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

private val Context.qobuzIdDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "qobuz_id_registry")

/**
 * Registry that records which numeric ids came out of the trypt-hifi (Qobuz) API,
 * plus the alphanumeric album **slug** Qobuz's /api/get-album endpoint needs (the
 * navigation routes are keyed by the numeric `Long`, not the slug).
 *
 * **Persistence (why):** album navigation only works if the slug for that numeric id
 * is known. `searchQobuz`/`getQobuzAlbum`/`getQobuzArtist` register slugs as a side
 * effect, but in-memory only — so before this was persisted, opening a Qobuz album
 * from a non-search surface (favorites, history, a playlist) after the slug had been
 * registered in a *previous* session fell through to the TIDAL album endpoint with a
 * Qobuz id and surfaced an API error. The maps are now write-through to a DataStore
 * (debounced) and reloaded at startup, so a slug seen once is known everywhere and
 * survives process death. Track/artist ids are persisted too, which also keeps Qobuz
 * playback routing correct across restarts.
 *
 * The in-memory maps remain the synchronous source of truth (lookups never block on
 * disk); persistence pre-seeds them and is updated in the background.
 */
@Singleton
class QobuzIdRegistry @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore = context.qobuzIdDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    /** Sentinel stored in [appleIdByTrack] when a lookup found no Apple match. */
    private val NO_APPLE_MATCH = -1L

    private val albumSlugs = ConcurrentHashMap<Long, String>()
    private val qobuzArtistIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val qobuzTrackIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    // Track ids that came out of the Apple Music catalog (searchApple), so the
    // download worker + playback route them to /api/apple/* instead of Qobuz.
    private val appleTrackIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    // Cross-catalog bridge: any track id -> the Apple adamId of the same
    // recording, resolved by title/artist match. Lets the downloader pull a
    // Qobuz/TIDAL/local track from the Apple wrapper. Persisted because the
    // match costs a network round trip and the answer never changes.
    private val appleIdByTrack = ConcurrentHashMap<Long, Long>()
    // Album/artist ids that came out of the Apple catalog, so the detail
    // screens call /api/apple/get-album|get-artist instead of the Qobuz ones.
    // Apple and Qobuz ids share no namespace, so without this an Apple album
    // opened from search resolved against Qobuz and surfaced an API error.
    private val appleAlbumIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val appleArtistIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    // Foreign (TIDAL) artist id -> Qobuz artist id, from the playback fallback.
    // Session-scoped (cheap to rebuild) — not persisted.
    private val artistAliases = ConcurrentHashMap<Long, Long>()

    // Coalesces frequent registrations (a single search registers dozens of ids)
    // into one debounced disk write.
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

    fun registerAlbum(qobuzId: Long, slug: String) {
        if (slug.isBlank()) return
        if (albumSlugs.put(qobuzId, slug) != slug) markDirty()
    }

    fun albumSlugFor(qobuzId: Long): String? = albumSlugs[qobuzId]

    fun registerArtist(id: Long) {
        if (qobuzArtistIds.add(id)) markDirty()
    }

    fun isQobuzArtist(id: Long): Boolean = id in qobuzArtistIds

    fun registerTrack(id: Long) {
        if (qobuzTrackIds.add(id)) markDirty()
    }

    fun isQobuzTrack(id: Long): Boolean = id in qobuzTrackIds

    fun registerAppleTrack(id: Long) {
        if (appleTrackIds.add(id)) markDirty()
    }

    fun isAppleTrack(id: Long): Boolean = id in appleTrackIds

    fun registerAppleAlbum(id: Long) {
        if (id != 0L && appleAlbumIds.add(id)) markDirty()
    }

    fun isAppleAlbum(id: Long): Boolean = id in appleAlbumIds

    fun registerAppleArtist(id: Long) {
        if (id != 0L && appleArtistIds.add(id)) markDirty()
    }

    fun isAppleArtist(id: Long): Boolean = id in appleArtistIds

    /** Remember the Apple adamId that matches a foreign (Qobuz/TIDAL/local) track id. */
    fun registerAppleIdFor(trackId: Long, adamId: Long) {
        if (trackId == 0L || adamId <= 0L) return
        if (appleIdByTrack.put(trackId, adamId) != adamId) markDirty()
    }

    /** The Apple adamId matching this track id, if one has been resolved. */
    fun appleIdFor(trackId: Long): Long? =
        appleIdByTrack[trackId]?.takeIf { it != NO_APPLE_MATCH }
            ?: trackId.takeIf { it in appleTrackIds }

    /**
     * Negative cache marker so a track with no Apple counterpart isn't
     * re-queried forever. Stored in the same map as real matches (rather than a
     * second set) so one persisted blob covers both.
     */
    fun markNoAppleMatch(trackId: Long) {
        if (trackId == 0L) return
        if (appleIdByTrack.put(trackId, NO_APPLE_MATCH) != NO_APPLE_MATCH) markDirty()
    }

    /** True once a lookup has been attempted for this id (hit or miss). */
    fun hasAppleLookup(trackId: Long): Boolean =
        appleIdByTrack.containsKey(trackId) || trackId in appleTrackIds

    /** Link a foreign (TIDAL) artist id to its Qobuz artist id. */
    fun registerArtistAlias(foreignId: Long, qobuzId: Long) {
        if (foreignId != qobuzId) artistAliases[foreignId] = qobuzId
    }

    /** The Qobuz artist id for a foreign (TIDAL) artist id, if one was linked. */
    fun qobuzArtistIdFor(foreignId: Long): Long? = artistAliases[foreignId]

    private fun markDirty() {
        saveSignal.tryEmit(Unit)
    }

    private suspend fun persist() {
        runCatching {
            val albumsJson = json.encodeToString(albumSlugs.toMap())
            val artistsJson = json.encodeToString(qobuzArtistIds.toList())
            val tracksJson = json.encodeToString(qobuzTrackIds.toList())
            val appleTracksJson = json.encodeToString(appleTrackIds.toList())
            val appleBridgeJson = json.encodeToString(appleIdByTrack.toMap())
            val appleAlbumsJson = json.encodeToString(appleAlbumIds.toList())
            val appleArtistsJson = json.encodeToString(appleArtistIds.toList())
            dataStore.edit { prefs ->
                prefs[KEY_ALBUM_SLUGS] = albumsJson
                prefs[KEY_ARTIST_IDS] = artistsJson
                prefs[KEY_TRACK_IDS] = tracksJson
                prefs[KEY_APPLE_TRACK_IDS] = appleTracksJson
                prefs[KEY_APPLE_ID_BY_TRACK] = appleBridgeJson
                prefs[KEY_APPLE_ALBUM_IDS] = appleAlbumsJson
                prefs[KEY_APPLE_ARTIST_IDS] = appleArtistsJson
            }
        }
    }

    private suspend fun load() {
        runCatching {
            val prefs = dataStore.data.first()
            prefs[KEY_ALBUM_SLUGS]?.let { raw ->
                runCatching { json.decodeFromString<Map<Long, String>>(raw) }.getOrNull()
                    ?.let { albumSlugs.putAll(it) }
            }
            prefs[KEY_ARTIST_IDS]?.let { raw ->
                runCatching { json.decodeFromString<List<Long>>(raw) }.getOrNull()
                    ?.let { qobuzArtistIds.addAll(it) }
            }
            prefs[KEY_TRACK_IDS]?.let { raw ->
                runCatching { json.decodeFromString<List<Long>>(raw) }.getOrNull()
                    ?.let { qobuzTrackIds.addAll(it) }
            }
            prefs[KEY_APPLE_TRACK_IDS]?.let { raw ->
                runCatching { json.decodeFromString<List<Long>>(raw) }.getOrNull()
                    ?.let { appleTrackIds.addAll(it) }
            }
            prefs[KEY_APPLE_ID_BY_TRACK]?.let { raw ->
                runCatching { json.decodeFromString<Map<Long, Long>>(raw) }.getOrNull()
                    ?.let { appleIdByTrack.putAll(it) }
            }
            prefs[KEY_APPLE_ALBUM_IDS]?.let { raw ->
                runCatching { json.decodeFromString<List<Long>>(raw) }.getOrNull()
                    ?.let { appleAlbumIds.addAll(it) }
            }
            prefs[KEY_APPLE_ARTIST_IDS]?.let { raw ->
                runCatching { json.decodeFromString<List<Long>>(raw) }.getOrNull()
                    ?.let { appleArtistIds.addAll(it) }
            }
        }
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 750L
        val KEY_ALBUM_SLUGS = stringPreferencesKey("album_slugs")
        val KEY_ARTIST_IDS = stringPreferencesKey("artist_ids")
        val KEY_TRACK_IDS = stringPreferencesKey("track_ids")
        val KEY_APPLE_TRACK_IDS = stringPreferencesKey("apple_track_ids")
        val KEY_APPLE_ID_BY_TRACK = stringPreferencesKey("apple_id_by_track")
        val KEY_APPLE_ALBUM_IDS = stringPreferencesKey("apple_album_ids")
        val KEY_APPLE_ARTIST_IDS = stringPreferencesKey("apple_artist_ids")
    }
}
