package tf.monochrome.android.data.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.atomic.AtomicInteger
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
 *
 * Nothing is ever forgotten, deliberately. A favourite album or artist is saved
 * with no note of its service, so this registry is the only record that its id
 * is a Qobuz, Deezer or Apple one: an id dropped to keep the sets small would
 * open that favourite against TIDAL, as some other record or as an error.
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
    // Ids that came out of the Deezer catalog (/api/deezer/*). Deezer ids are
    // plain numbers in the same range as Qobuz and TIDAL ids, so without these
    // a Deezer album would open against Qobuz and a Deezer track would be
    // streamed or downloaded as whatever Qobuz/TIDAL recording has that id.
    private val deezerTrackIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val deezerAlbumIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val deezerArtistIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    // Foreign (TIDAL) artist id -> Qobuz artist id, from the playback fallback.
    // Session-scoped (cheap to rebuild) — not persisted.
    private val artistAliases = ConcurrentHashMap<Long, Long>()

    // Coalesces frequent registrations (a single search registers dozens of ids)
    // into one debounced disk write.
    private val saveSignal = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Completed once [load] has run, however it went. The in-memory sets are
    // empty until then, and a caller deciding which service a bare id belongs
    // to (the download queue, restored at startup) would read "not Qobuz".
    private val loaded = CompletableDeferred<Unit>()

    // Which saved sets have changed since they were last written, a bit each.
    // Only those are encoded and written: the id sets run to tens of thousands,
    // and one new album slug used to re-encode all ten of them.
    private val dirty = AtomicInteger(0)

    /** One saved set: its bit in [dirty], its key, and how it is written. */
    private class Saved(val bit: Int, val key: Preferences.Key<String>, val encode: () -> String)

    private val saved = listOf(
        Saved(ALBUM_SLUGS, KEY_ALBUM_SLUGS) { json.encodeToString(albumSlugs.toMap()) },
        Saved(ARTIST_IDS, KEY_ARTIST_IDS) { json.encodeToString(qobuzArtistIds.toList()) },
        Saved(TRACK_IDS, KEY_TRACK_IDS) { json.encodeToString(qobuzTrackIds.toList()) },
        Saved(APPLE_TRACK_IDS, KEY_APPLE_TRACK_IDS) { json.encodeToString(appleTrackIds.toList()) },
        Saved(APPLE_ID_BY_TRACK, KEY_APPLE_ID_BY_TRACK) { json.encodeToString(appleIdByTrack.toMap()) },
        Saved(APPLE_ALBUM_IDS, KEY_APPLE_ALBUM_IDS) { json.encodeToString(appleAlbumIds.toList()) },
        Saved(APPLE_ARTIST_IDS, KEY_APPLE_ARTIST_IDS) { json.encodeToString(appleArtistIds.toList()) },
        Saved(DEEZER_TRACK_IDS, KEY_DEEZER_TRACK_IDS) { json.encodeToString(deezerTrackIds.toList()) },
        Saved(DEEZER_ALBUM_IDS, KEY_DEEZER_ALBUM_IDS) { json.encodeToString(deezerAlbumIds.toList()) },
        Saved(DEEZER_ARTIST_IDS, KEY_DEEZER_ARTIST_IDS) { json.encodeToString(deezerArtistIds.toList()) },
    )

    init {
        scope.launch {
            try {
                load()
            } finally {
                loaded.complete(Unit)
            }
        }
        scope.launch {
            @OptIn(FlowPreview::class)
            saveSignal.debounce(SAVE_DEBOUNCE_MS).collect {
                // Never before the load: a set written then would be only this
                // session's ids, over the file that has every earlier one.
                loaded.await()
                persist()
            }
        }
    }

    fun registerAlbum(qobuzId: Long, slug: String) {
        if (slug.isBlank()) return
        if (albumSlugs.put(qobuzId, slug) != slug) markDirty(ALBUM_SLUGS)
    }

    fun albumSlugFor(qobuzId: Long): String? = albumSlugs[qobuzId]

    fun registerArtist(id: Long) {
        if (qobuzArtistIds.add(id)) markDirty(ARTIST_IDS)
    }

    fun isQobuzArtist(id: Long): Boolean = id in qobuzArtistIds

    fun registerTrack(id: Long) {
        if (qobuzTrackIds.add(id)) markDirty(TRACK_IDS)
    }

    fun isQobuzTrack(id: Long): Boolean = id in qobuzTrackIds

    /** Suspends until the persisted ids are back in memory. */
    suspend fun awaitLoaded() = loaded.await()

    fun registerAppleTrack(id: Long) {
        if (appleTrackIds.add(id)) markDirty(APPLE_TRACK_IDS)
    }

    fun isAppleTrack(id: Long): Boolean = id in appleTrackIds

    fun registerAppleAlbum(id: Long) {
        if (id != 0L && appleAlbumIds.add(id)) markDirty(APPLE_ALBUM_IDS)
    }

    fun isAppleAlbum(id: Long): Boolean = id in appleAlbumIds

    fun registerAppleArtist(id: Long) {
        if (id != 0L && appleArtistIds.add(id)) markDirty(APPLE_ARTIST_IDS)
    }

    fun isAppleArtist(id: Long): Boolean = id in appleArtistIds

    fun registerDeezerTrack(id: Long) {
        if (id != 0L && deezerTrackIds.add(id)) markDirty(DEEZER_TRACK_IDS)
    }

    fun isDeezerTrack(id: Long): Boolean = id in deezerTrackIds

    fun registerDeezerAlbum(id: Long) {
        if (id != 0L && deezerAlbumIds.add(id)) markDirty(DEEZER_ALBUM_IDS)
    }

    fun isDeezerAlbum(id: Long): Boolean = id in deezerAlbumIds

    fun registerDeezerArtist(id: Long) {
        if (id != 0L && deezerArtistIds.add(id)) markDirty(DEEZER_ARTIST_IDS)
    }

    fun isDeezerArtist(id: Long): Boolean = id in deezerArtistIds

    /** Remember the Apple adamId that matches a foreign (Qobuz/TIDAL/local) track id. */
    fun registerAppleIdFor(trackId: Long, adamId: Long) {
        if (trackId == 0L || adamId <= 0L) return
        if (appleIdByTrack.put(trackId, adamId) != adamId) markDirty(APPLE_ID_BY_TRACK)
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
        if (appleIdByTrack.put(trackId, NO_APPLE_MATCH) != NO_APPLE_MATCH) markDirty(APPLE_ID_BY_TRACK)
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

    private fun markDirty(set: Int) {
        // Marked before the signal, so the save the signal leads to sees it.
        dirty.getAndUpdate { bits -> bits or set }
        saveSignal.tryEmit(Unit)
    }

    private suspend fun persist() {
        // Taken before encoding: an id registered while this runs marks its set
        // again and is written by the next save.
        val changed = dirty.getAndSet(0)
        if (changed == 0) return
        runCatching {
            val values = saved.filter { (it.bit and changed) != 0 }.map { Pair(it.key, it.encode()) }
            dataStore.edit { prefs -> values.forEach { (key, value) -> prefs[key] = value } }
        }.onFailure { _ ->
            // Not written: these go again with the next save.
            dirty.getAndUpdate { bits -> bits or changed }
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
            prefs[KEY_DEEZER_TRACK_IDS]?.let { raw ->
                runCatching { json.decodeFromString<List<Long>>(raw) }.getOrNull()
                    ?.let { deezerTrackIds.addAll(it) }
            }
            prefs[KEY_DEEZER_ALBUM_IDS]?.let { raw ->
                runCatching { json.decodeFromString<List<Long>>(raw) }.getOrNull()
                    ?.let { deezerAlbumIds.addAll(it) }
            }
            prefs[KEY_DEEZER_ARTIST_IDS]?.let { raw ->
                runCatching { json.decodeFromString<List<Long>>(raw) }.getOrNull()
                    ?.let { deezerArtistIds.addAll(it) }
            }
        }
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 750L

        // A bit per saved set, for [dirty].
        const val ALBUM_SLUGS = 1 shl 0
        const val ARTIST_IDS = 1 shl 1
        const val TRACK_IDS = 1 shl 2
        const val APPLE_TRACK_IDS = 1 shl 3
        const val APPLE_ID_BY_TRACK = 1 shl 4
        const val APPLE_ALBUM_IDS = 1 shl 5
        const val APPLE_ARTIST_IDS = 1 shl 6
        const val DEEZER_TRACK_IDS = 1 shl 7
        const val DEEZER_ALBUM_IDS = 1 shl 8
        const val DEEZER_ARTIST_IDS = 1 shl 9

        val KEY_ALBUM_SLUGS = stringPreferencesKey("album_slugs")
        val KEY_ARTIST_IDS = stringPreferencesKey("artist_ids")
        val KEY_TRACK_IDS = stringPreferencesKey("track_ids")
        val KEY_APPLE_TRACK_IDS = stringPreferencesKey("apple_track_ids")
        val KEY_APPLE_ID_BY_TRACK = stringPreferencesKey("apple_id_by_track")
        val KEY_APPLE_ALBUM_IDS = stringPreferencesKey("apple_album_ids")
        val KEY_APPLE_ARTIST_IDS = stringPreferencesKey("apple_artist_ids")
        val KEY_DEEZER_TRACK_IDS = stringPreferencesKey("deezer_track_ids")
        val KEY_DEEZER_ALBUM_IDS = stringPreferencesKey("deezer_album_ids")
        val KEY_DEEZER_ARTIST_IDS = stringPreferencesKey("deezer_artist_ids")
    }
}
