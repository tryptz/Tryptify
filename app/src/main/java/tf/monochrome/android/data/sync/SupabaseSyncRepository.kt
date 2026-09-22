package tf.monochrome.android.data.sync

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.db.dao.EqPresetDao
import tf.monochrome.android.data.db.dao.FavoriteDao
import tf.monochrome.android.data.db.dao.HistoryDao
import tf.monochrome.android.data.db.dao.MixPresetDao
import tf.monochrome.android.data.db.dao.PlaylistDao
import tf.monochrome.android.data.db.entity.EqPresetEntity
import tf.monochrome.android.data.db.entity.FavoriteAlbumEntity
import tf.monochrome.android.data.db.entity.FavoriteArtistEntity
import tf.monochrome.android.data.db.entity.FavoriteTrackEntity
import tf.monochrome.android.data.db.entity.HistoryTrackEntity
import tf.monochrome.android.data.db.entity.MixPresetEntity
import tf.monochrome.android.data.db.entity.PlayEventEntity
import tf.monochrome.android.data.db.entity.PlaylistTrackEntity
import tf.monochrome.android.data.db.entity.UserPlaylistEntity
import tf.monochrome.android.data.db.dao.PlayEventDao
import tf.monochrome.android.data.preferences.SettingsSyncCodec
import tf.monochrome.android.domain.model.EqBand
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SupabaseSync"

// ─── Supabase row DTOs (flat, snake_case) ────────────────────────────────────

@Serializable
data class SbEqPreset(
    val id: String? = null,
    val user_id: String? = null,
    val local_id: String,
    val name: String,
    val description: String = "",
    val bands: String = "[]",   // JSON-serialized List<EqBand>
    /** Right-ear bands. Null is a mono preset whose left list drives both ears. */
    val bands_r: String? = null,
    val preamp: Float = 0f,
    val target_id: String = "",
    val target_name: String = "",
    val is_custom: Boolean = true,
    /**
     * 0 = AutoEQ, 1 = Parametric, mirroring [EqPresetEntity.eqType].
     *
     * One Room table serves both EQ screens and this is the only thing telling
     * them apart. Without it a pull hands every parametric preset to the AutoEQ
     * screen and vice versa, which is not a display bug: the two carry
     * different band shapes.
     */
    val eq_type: Int = 0,
    /** Device clock at the user's last edit. See [cloudCopyIsNewer]. */
    val created_at_ms: Long = 0,
    val updated_at_ms: Long = 0,
    val created_at: String? = null,
    val updated_at: String? = null
)

@Serializable
data class SbMixPreset(
    val id: String? = null,
    val user_id: String? = null,
    /** The preset's creation time in epoch milliseconds. See [mixPushPayload]. */
    val local_id: String,
    val name: String,
    val state_json: String,
    val is_custom: Boolean = true,
    /** Device clock at the user's last edit. See [cloudCopyIsNewer]. */
    val created_at_ms: Long = 0,
    val updated_at_ms: Long = 0,
    val created_at: String? = null,
    val updated_at: String? = null
)

@Serializable
data class SbFavoriteTrack(
    val id: Long,
    val user_id: String? = null,
    val title: String,
    val duration: Int = 0,
    val artist_id: Long? = null,
    val artist_name: String = "",
    val album_id: Long? = null,
    val album_title: String? = null,
    val album_cover: String? = null,
    val audio_quality: String? = null,
    val explicit: Boolean = false,
    val track_number: Int? = null,
    val added_at: String? = null
)

@Serializable
data class SbFavoriteAlbum(
    val id: Long,
    val user_id: String? = null,
    val title: String,
    val artist_id: Long? = null,
    val artist_name: String = "",
    val cover: String? = null,
    val number_of_tracks: Int? = null,
    val release_date: String? = null,
    val type: String? = null,
    val added_at: String? = null
)

@Serializable
data class SbFavoriteArtist(
    val id: Long,
    val user_id: String? = null,
    val name: String,
    val picture: String? = null,
    val added_at: String? = null
)

@Serializable
data class SbPlayHistory(
    val id: Long? = null,
    val user_id: String? = null,
    val track_id: Long,
    val title: String,
    val duration: Int = 0,
    val artist_id: Long? = null,
    val artist_name: String = "",
    val album_id: Long? = null,
    val album_title: String? = null,
    val album_cover: String? = null,
    val audio_quality: String? = null,
    val played_at: String? = null,
    // Mirrors history_tracks.unifiedJson on the local DB. Carries the
    // serialized UnifiedTrack so cross-device re-routing of Recently Played
    // doesn't fall back to TIDAL with a Qobuz id / file-path hash.
    val unified_json: String? = null,
)

/**
 * Per-play scrobble log synced to `play_events` on Supabase.
 * Append-only — one row per playback, used to drive Listening Stats
 * aggregations across devices. Legacy denormalised fields remain for
 * backfill readers; canonical FKs (track_uuid/session_id/device_id)
 * are populated when the client is signed in and the catalog RPC succeeds.
 */
@Serializable
data class SbPlayEvent(
    val id: Long? = null,
    val user_id: String? = null,
    val track_id: Long,
    val title: String,
    val duration: Int = 0,
    val artist_id: Long? = null,
    val artist_name: String = "",
    val album_id: Long? = null,
    val album_title: String? = null,
    val album_cover: String? = null,
    val audio_quality: String? = null,
    val source: String? = null,
    val played_at_ms: Long = 0L,
    val track_uuid: String? = null,
    val session_id: String? = null,
    val device_id: String? = null,
    val started_at: String? = null,
    val duration_played_ms: Int? = null,
    val completed: Boolean = false,
)

@Serializable
data class SbLocalFolder(
    val id: String? = null,
    val user_id: String? = null,
    val path: String,
    val display_name: String = "",
    val added_at: String? = null
)

@Serializable
data class SbPlaylist(
    val id: String,
    val user_id: String? = null,
    val name: String,
    val description: String? = null,
    val is_public: Boolean = false,
    val created_at: String? = null,
    val updated_at: String? = null
)

@Serializable
data class SbPlaylistTrack(
    val playlist_id: String,
    val track_id: Long,
    val title: String,
    val duration: Int = 0,
    val artist_name: String = "",
    val album_id: Long? = null,
    val album_title: String? = null,
    val album_cover: String? = null,
    val position: Int = 0,
    val added_at: String? = null
)

/** What a settings pull found. */
enum class SettingsPull {
    /** The cloud copy was read and applied. */
    APPLIED,
    /** The cloud answered and holds no settings for this account yet. */
    NO_CLOUD_COPY,
    /** The cloud could not be read. Nothing is known about what it holds. */
    FAILED,
}

/** One row per user: a tagged-JSON blob of the user's allow-listed app settings. */
@Serializable
data class SbUserSettings(
    val user_id: String? = null,
    val payload: String,
    val updated_at: String? = null,
)

// ─── Repository ───────────────────────────────────────────────────────────────

@Singleton
class SupabaseSyncRepository @Inject constructor(
    private val authManager: SupabaseAuthManager,
    private val favoritesDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val eqPresetDao: EqPresetDao,
    private val mixPresetDao: MixPresetDao,
    private val playlistDao: PlaylistDao,
    private val playEventDao: PlayEventDao,
    private val preferences: tf.monochrome.android.data.preferences.PreferencesManager,
    private val outbox: SyncOutbox,
) {
    private val supabase get() = authManager.supabase
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Where queued edits are flushed from, so saving never waits on the network. */
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** One flush at a time — two racing would send the same edit twice. */
    private val flushLock = Mutex()

    private fun userId(): String? = authManager.userProfile.value?.id

    // ─── App settings (single JSON row per user) ─────────────────────────────

    /**
     * Upload the settings this device changed, merged into the cloud copy.
     *
     * Read-then-write rather than a blind overwrite: see
     * [SettingsSyncCodec.merge] for the keys a plain overwrite used to delete.
     * Only keys edited here since the last agreed snapshot go up, so a value
     * this device merely still holds can't overwrite a newer one set on another
     * device. With no agreed snapshot yet (seeding a new account) everything
     * goes up. If the cloud copy can't be read, nothing is written — a push that
     * can't see what it would replace doesn't replace it.
     *
     * @return true when the cloud holds this device's edits afterwards.
     */
    suspend fun pushSettings(): Boolean {
        val uid = userId() ?: return false
        return runCatching {
            val cloud = fetchCloudSettings(uid)
            val local = preferences.exportSettingsJson()
            val base = outbox.settingsBase(uid)
            val outgoing = if (base == null || cloud == null) local
                else SettingsSyncCodec.only(local, SettingsSyncCodec.changedSince(base, local))
            val merged = SettingsSyncCodec.merge(cloud, outgoing)
                ?: error("cloud settings are in a format this build can't read; not overwriting them")
            if (merged != cloud) {
                supabase.postgrest["user_settings"].upsert(
                    SbUserSettings(
                        user_id = uid,
                        payload = merged,
                        updated_at = java.time.Instant.now().toString(),
                    )
                ) { onConflict = "user_id" }
            }
            // The cloud now holds everything this device has: the new base.
            outbox.setSettingsBase(uid, local)
        }.onFailure { Log.e(TAG, "pushSettings failed: ${it.message}") }.isSuccess
    }

    /** The cloud settings payload, null when the account has none. Throws when unreachable. */
    private suspend fun fetchCloudSettings(uid: String): String? =
        supabase.postgrest["user_settings"]
            .select { filter { eq("user_id", uid) } }
            .decodeSingleOrNull<SbUserSettings>()
            ?.payload

    /**
     * Fetch the cloud settings row (if any) and apply it to local DataStore —
     * except for keys this device changed since the two last agreed, which are
     * kept and pushed instead. See [SettingsSyncCodec.changedSince]; without
     * that, settings changed on a device that launched offline were reverted
     * by the next online launch.
     *
     * Reports what it found rather than swallowing it, because the caller has
     * to tell "the cloud has nothing" (safe to seed from this device) from "the
     * cloud couldn't be reached" (this device knows nothing, so must not push).
     */
    suspend fun pullSettings(): SettingsPull {
        val uid = userId() ?: return SettingsPull.FAILED
        return runCatching {
            val cloud = fetchCloudSettings(uid) ?: return@runCatching SettingsPull.NO_CLOUD_COPY
            val localEdits = SettingsSyncCodec.changedSince(
                outbox.settingsBase(uid),
                preferences.exportSettingsJson(),
            )
            preferences.importSettingsJson(SettingsSyncCodec.without(cloud, localEdits))
            if (localEdits.isEmpty()) {
                outbox.setSettingsBase(uid, preferences.exportSettingsJson())
            } else {
                // Sets the base itself on success. On failure the base stays
                // put, so these keys still count as local edits next time.
                pushSettings()
            }
            SettingsPull.APPLIED
        }.getOrElse {
            Log.e(TAG, "pullSettings failed: ${it.message}")
            SettingsPull.FAILED
        }
    }

    // ─── EQ Presets ──────────────────────────────────────────────────────────

    /**
     * Upload one EQ preset, AutoEQ or parametric.
     *
     * A no-op when signed out, like every other push here, so callers on a save
     * path do not have to check first.
     */
    suspend fun pushEqPreset(preset: EqPresetEntity): Boolean {
        val uid = userId() ?: return false
        return runCatching {
            supabase.postgrest["eq_presets"].upsert(eqPushPayload(preset, uid)) {
                onConflict = "user_id,local_id"
            }
        }.onFailure { Log.e(TAG, "pushEqPreset failed: ${it.message}") }.isSuccess
    }

    suspend fun deleteEqPreset(localId: String): Boolean {
        val uid = userId() ?: return false
        return runCatching {
            supabase.postgrest["eq_presets"]
                .delete { filter { eq("user_id", uid); eq("local_id", localId) } }
        }.onFailure { Log.e(TAG, "deleteEqPreset failed: ${it.message}") }.isSuccess
    }

    // ─── Mix Presets ─────────────────────────────────────────────────────────

    /** Upload one mixer preset. Keyed on its creation time — see [toCloudRow]. */
    suspend fun pushMixPreset(preset: MixPresetEntity): Boolean {
        val uid = userId() ?: return false
        return runCatching {
            supabase.postgrest["mix_presets"].upsert(mixPushPayload(preset, uid)) {
                onConflict = "user_id,local_id"
            }
        }.onFailure { Log.e(TAG, "pushMixPreset failed: ${it.message}") }.isSuccess
    }

    /**
     * Remove a mixer preset from the cloud, so deleting it on one device
     * removes it everywhere.
     *
     * [createdAt] rather than the row id, because that is the identity the
     * cloud knows this preset by.
     */
    suspend fun deleteMixPreset(createdAt: Long): Boolean {
        val uid = userId() ?: return false
        return runCatching {
            supabase.postgrest["mix_presets"]
                .delete { filter { eq("user_id", uid); eq("local_id", createdAt.toString()) } }
        }.onFailure { Log.e(TAG, "deleteMixPreset failed: ${it.message}") }.isSuccess
    }

    // ─── Favorites ───────────────────────────────────────────────────────────

    suspend fun pushFavoriteTrack(track: FavoriteTrackEntity): Boolean {
        val uid = userId() ?: return false
        return runCatching {
            supabase.postgrest["favorite_tracks"].upsert(
                SbFavoriteTrack(
                    id = track.id,
                    user_id = uid,
                    title = track.title,
                    duration = track.duration,
                    artist_id = track.artistId,
                    artist_name = track.artistName,
                    album_id = track.albumId,
                    album_title = track.albumTitle,
                    album_cover = track.albumCover,
                    audio_quality = track.audioQuality,
                    explicit = track.explicit,
                    track_number = track.trackNumber
                )
            ) { onConflict = "user_id,id" }
        }.onFailure { Log.e(TAG, "pushFavoriteTrack failed: ${it.message}") }.isSuccess
    }

    suspend fun deleteFavoriteTrack(trackId: Long): Boolean {
        val uid = userId() ?: return false
        return runCatching {
            supabase.postgrest["favorite_tracks"]
                .delete { filter { eq("user_id", uid); eq("id", trackId) } }
        }.onFailure { Log.e(TAG, "deleteFavoriteTrack failed: ${it.message}") }.isSuccess
    }

    suspend fun pushFavoriteAlbum(album: FavoriteAlbumEntity): Boolean {
        val uid = userId() ?: return false
        return runCatching {
            supabase.postgrest["favorite_albums"].upsert(
                SbFavoriteAlbum(
                    id = album.id,
                    user_id = uid,
                    title = album.title,
                    artist_id = album.artistId,
                    artist_name = album.artistName,
                    cover = album.cover,
                    number_of_tracks = album.numberOfTracks,
                    release_date = album.releaseDate,
                    type = album.type
                )
            ) { onConflict = "user_id,id" }
        }.onFailure { Log.e(TAG, "pushFavoriteAlbum failed: ${it.message}") }.isSuccess
    }

    suspend fun deleteFavoriteAlbum(albumId: Long): Boolean {
        val uid = userId() ?: return false
        return runCatching {
            supabase.postgrest["favorite_albums"]
                .delete { filter { eq("user_id", uid); eq("id", albumId) } }
        }.onFailure { Log.e(TAG, "deleteFavoriteAlbum failed: ${it.message}") }.isSuccess
    }

    suspend fun pushFavoriteArtist(artist: FavoriteArtistEntity): Boolean {
        val uid = userId() ?: return false
        return runCatching {
            supabase.postgrest["favorite_artists"].upsert(
                SbFavoriteArtist(
                    id = artist.id,
                    user_id = uid,
                    name = artist.name,
                    picture = artist.picture
                )
            ) { onConflict = "user_id,id" }
        }.onFailure { Log.e(TAG, "pushFavoriteArtist failed: ${it.message}") }.isSuccess
    }

    suspend fun deleteFavoriteArtist(artistId: Long): Boolean {
        val uid = userId() ?: return false
        return runCatching {
            supabase.postgrest["favorite_artists"]
                .delete { filter { eq("user_id", uid); eq("id", artistId) } }
        }.onFailure { Log.e(TAG, "deleteFavoriteArtist failed: ${it.message}") }.isSuccess
    }

    // ─── Play History ────────────────────────────────────────────────────────

    suspend fun pushHistoryTrack(track: HistoryTrackEntity) {
        val uid = userId() ?: return
        runCatching {
            supabase.postgrest["play_history"].insert(
                SbPlayHistory(
                    user_id = uid,
                    track_id = track.id,
                    title = track.title,
                    duration = track.duration,
                    artist_id = track.artistId,
                    artist_name = track.artistName,
                    album_id = track.albumId,
                    album_title = track.albumTitle,
                    album_cover = track.albumCover,
                    audio_quality = track.audioQuality,
                    unified_json = track.unifiedJson,
                )
            )
        }.onFailure { Log.e(TAG, "pushHistoryTrack failed: ${it.message}") }
    }

    // ─── Play Events (per-play scrobble log) ─────────────────────────────────

    /**
     * Push a scrobble to Supabase with full FK context (track_uuid, session,
     * device). Falls back to legacy denormalised columns if the catalog RPC
     * is unavailable or the track can't be resolved.
     *
     * @param sessionId   Resolved play_sessions.id (UUID) for this play.
     * @param deviceId    Resolved user_devices.id (UUID) for this install.
     * @param sourceType  "tidal" | "collection" | "local" — used for
     *                    ensure_catalog_track() + the legacy `source` column.
     * @param sourceRef   Stable per-source track key (tidal id, collection
     *                    hash, sha1(path)). Required to resolve track_uuid.
     */
    suspend fun pushPlayEvent(
        event: PlayEventEntity,
        sessionId: String? = null,
        deviceId: String? = null,
        sourceType: String? = null,
        sourceRef: String? = null,
        durationPlayedMs: Int? = null,
        completed: Boolean = false,
    ): Long? {
        val uid = userId() ?: return null
        val trackUuid = if (sourceType != null && sourceRef != null) {
            ensureCatalogTrack(event, sourceType, sourceRef)
        } else null
        val startedAtIso = java.time.Instant.ofEpochMilli(event.playedAt).toString()

        return runCatching {
            supabase.postgrest["play_events"].insert(
                SbPlayEvent(
                    user_id = uid,
                    track_id = event.trackId,
                    title = event.title,
                    duration = event.duration,
                    artist_id = event.artistId,
                    artist_name = event.artistName,
                    album_id = event.albumId,
                    album_title = event.albumTitle,
                    album_cover = event.albumCover,
                    audio_quality = event.audioQuality,
                    source = event.source ?: sourceType,
                    played_at_ms = event.playedAt,
                    track_uuid = trackUuid,
                    session_id = sessionId,
                    device_id = deviceId,
                    started_at = startedAtIso,
                    duration_played_ms = durationPlayedMs,
                    completed = completed,
                )
            ) { select() }
                .decodeSingleOrNull<SbPlayEvent>()
                ?.id
        }.getOrElse {
            Log.e(TAG, "pushPlayEvent failed: ${it.message}")
            null
        }
    }

    // ─── Pull play_events from cloud into local Room (phase 2.1) ─────────────

    /**
     * Fetch play_events for the signed-in user with `played_at_ms >= since`
     * and upsert them into Room keyed on cloud_row_id. Idempotent — rerunning
     * the pull after more plays arrive is safe.
     *
     * @return the number of rows actually inserted (ignored duplicates
     *         don't count), or null if the user isn't signed in or the
     *         request failed.
     */
    suspend fun pullPlayEventsSince(since: Long, pageSize: Int = 500): Int? {
        val uid = userId() ?: return null
        return runCatching {
            var inserted = 0
            var offset = 0
            while (true) {
                val page = supabase.postgrest["play_events"]
                    .select {
                        filter {
                            eq("user_id", uid)
                            gte("played_at_ms", since)
                        }
                        order("played_at_ms", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                        range(offset.toLong(), (offset + pageSize - 1).toLong())
                    }
                    .decodeList<SbPlayEvent>()
                if (page.isEmpty()) break
                page.forEach { e ->
                    val rowId = playEventDao.insertFromCloud(
                        PlayEventEntity(
                            trackId = e.track_id,
                            title = e.title,
                            duration = e.duration,
                            artistId = e.artist_id,
                            artistName = e.artist_name,
                            albumId = e.album_id,
                            albumTitle = e.album_title,
                            albumCover = e.album_cover,
                            audioQuality = e.audio_quality,
                            source = e.source,
                            playedAt = e.played_at_ms,
                            cloudRowId = e.id
                        )
                    )
                    if (rowId > 0) inserted += 1
                }
                if (page.size < pageSize) break
                offset += pageSize
            }
            inserted
        }.getOrElse {
            Log.e(TAG, "pullPlayEventsSince failed: ${it.message}")
            null
        }
    }

    /**
     * Atomically upsert artist/album/track/source mapping via the
     * `public.ensure_catalog_track(jsonb)` RPC. Returns the canonical
     * track uuid, or null on any failure.
     */
    private suspend fun ensureCatalogTrack(
        event: PlayEventEntity,
        source: String,
        sourceRef: String,
    ): String? {
        userId() ?: return null
        val payload: JsonObject = buildJsonObject {
            put("source", JsonPrimitive(source))
            put("source_ref", JsonPrimitive(sourceRef))
            put("title", JsonPrimitive(event.title))
            put("duration_s", JsonPrimitive(event.duration))
            put("artist_name", JsonPrimitive(event.artistName.ifBlank { "Unknown Artist" }))
            event.albumTitle?.let { put("album_title", JsonPrimitive(it)) }
            event.albumCover?.let { put("album_cover", JsonPrimitive(it)) }
            event.audioQuality?.let { put("audio_quality", JsonPrimitive(it)) }
        }
        return runCatching {
            supabase.postgrest.rpc("ensure_catalog_track", payload)
                .decodeAs<String>()
                .takeIf { it.isNotBlank() }
        }.getOrElse {
            Log.w(TAG, "ensure_catalog_track($source/$sourceRef) failed: ${it.message}")
            null
        }
    }

    // ─── Local Folder Routes ─────────────────────────────────────────────────

    suspend fun pushLocalFolder(path: String, displayName: String) {
        val uid = userId() ?: return
        runCatching {
            supabase.postgrest["local_folder_routes"].upsert(
                SbLocalFolder(user_id = uid, path = path, display_name = displayName)
            ) { onConflict = "user_id,path" }
        }.onFailure { Log.e(TAG, "pushLocalFolder failed: ${it.message}") }
    }

    suspend fun deleteLocalFolder(path: String) {
        val uid = userId() ?: return
        runCatching {
            supabase.postgrest["local_folder_routes"]
                .delete { filter { eq("user_id", uid); eq("path", path) } }
        }.onFailure { Log.e(TAG, "deleteLocalFolder failed: ${it.message}") }
    }

    suspend fun fetchLocalFolders(): List<SbLocalFolder> {
        val uid = userId() ?: return emptyList()
        return runCatching {
            supabase.postgrest["local_folder_routes"]
                .select { filter { eq("user_id", uid) } }
                .decodeList<SbLocalFolder>()
        }.getOrElse { Log.e(TAG, "fetchLocalFolders failed: ${it.message}"); emptyList() }
    }

    // ─── Playlists ───────────────────────────────────────────────────────────

    suspend fun pushPlaylist(playlist: UserPlaylistEntity): Boolean {
        val uid = userId() ?: return false
        return runCatching {
            supabase.postgrest["user_playlists"].upsert(
                SbPlaylist(
                    id = playlist.id,
                    user_id = uid,
                    name = playlist.name,
                    description = playlist.description,
                    is_public = playlist.isPublic
                )
            ) { onConflict = "id" }
        }.onFailure { Log.e(TAG, "pushPlaylist failed: ${it.message}") }.isSuccess
    }

    suspend fun pushPlaylistTrack(track: PlaylistTrackEntity): Boolean {
        val uid = userId() ?: return false
        return runCatching {
            supabase.postgrest["playlist_tracks"].upsert(
                SbPlaylistTrack(
                    playlist_id = track.playlistId,
                    track_id = track.trackId,
                    title = track.title,
                    duration = track.duration,
                    artist_name = track.artistName,
                    album_id = track.albumId,
                    album_title = track.albumTitle,
                    album_cover = track.albumCover,
                    position = track.position
                )
            ) { onConflict = "playlist_id,track_id" }
        }.onFailure { Log.e(TAG, "pushPlaylistTrack failed: ${it.message}") }.isSuccess
    }

    suspend fun deletePlaylist(playlistId: String): Boolean {
        val uid = userId() ?: return false
        return runCatching {
            supabase.postgrest["user_playlists"]
                .delete { filter { eq("id", playlistId); eq("user_id", uid) } }
        }.onFailure { Log.e(TAG, "deletePlaylist failed: ${it.message}") }.isSuccess
    }

    /** Remove one track from a playlist in the cloud. RLS scopes it to the owner's playlists. */
    suspend fun deletePlaylistTrack(playlistId: String, trackId: Long): Boolean {
        userId() ?: return false
        return runCatching {
            supabase.postgrest["playlist_tracks"]
                .delete { filter { eq("playlist_id", playlistId); eq("track_id", trackId) } }
        }.onFailure { Log.e(TAG, "deletePlaylistTrack failed: ${it.message}") }.isSuccess
    }

    // ─── Pending local edits (Room → cloud, held until confirmed) ────────────

    /**
     * Records a library edit already written to Room, then sends it.
     *
     * Call it after the local write, from the repository that made it. The edit
     * stays in [SyncOutbox] until the cloud accepts it, so an edit made offline
     * or lost to a dropped connection goes out on the next flush instead of
     * never — and a delete still waiting there is one the pull will not undo.
     *
     * Signed out, nothing is recorded. An edit made then belongs to no account,
     * and replaying it under whichever account signs in next could delete that
     * account's rows.
     */
    suspend fun queueChange(kind: SyncKind, key: String, op: SyncOp) {
        val uid = userId() ?: return
        outbox.record(PendingChange(userId = uid, kind = kind, key = key, op = op))
        flushScope.launch { flushOutbox() }
    }

    /**
     * Sends every pending edit for the signed-in account and keeps whatever the
     * cloud did not accept, for the next flush. Runs after each edit, before
     * every pull, and at the start of a manual sync.
     */
    suspend fun flushOutbox() {
        val uid = userId() ?: return
        flushLock.withLock {
            val playlistsInCloud = HashSet<String>()
            for (change in outbox.forUser(uid)) {
                // Signed out or switched account mid-flush: the push helpers
                // write as whoever is signed in now, so stop rather than send
                // one account's edits into another's.
                if (userId() != uid) return
                val sent = runCatching { send(change, playlistsInCloud) }.getOrElse {
                    Log.e(TAG, "flush ${change.kind} ${change.key}: ${it.message}")
                    false
                }
                if (sent) outbox.settle(change)
            }
        }
    }

    /**
     * Sends one pending edit. An upsert re-reads the row from Room so it sends
     * the latest version; a row that is gone by now has nothing left to send,
     * which counts as done (its delete, if any, is its own pending edit).
     */
    private suspend fun send(change: PendingChange, playlistsInCloud: MutableSet<String>): Boolean {
        val upsert = change.op == SyncOp.UPSERT
        return when (change.kind) {
            SyncKind.FAVORITE_TRACK -> {
                val id = change.key.toLongOrNull() ?: return true
                if (upsert) favoritesDao.getFavoriteTrack(id)?.let { pushFavoriteTrack(it) } ?: true
                else deleteFavoriteTrack(id)
            }
            SyncKind.FAVORITE_ALBUM -> {
                val id = change.key.toLongOrNull() ?: return true
                if (upsert) favoritesDao.getFavoriteAlbum(id)?.let { pushFavoriteAlbum(it) } ?: true
                else deleteFavoriteAlbum(id)
            }
            SyncKind.FAVORITE_ARTIST -> {
                val id = change.key.toLongOrNull() ?: return true
                if (upsert) favoritesDao.getFavoriteArtist(id)?.let { pushFavoriteArtist(it) } ?: true
                else deleteFavoriteArtist(id)
            }
            SyncKind.PLAYLIST ->
                if (upsert) {
                    val playlist = playlistDao.getPlaylist(change.key) ?: return true
                    pushPlaylist(playlist).also { if (it) playlistsInCloud += playlist.id }
                } else {
                    // The cloud cascades this to the playlist's tracks.
                    deletePlaylist(change.key)
                }
            SyncKind.PLAYLIST_TRACK -> {
                val (playlistId, trackId) = parsePlaylistTrackKey(change.key) ?: return true
                if (upsert) {
                    val track = playlistDao.getPlaylistTrack(playlistId, trackId) ?: return true
                    // RLS refuses an entry until its playlist is in the cloud,
                    // and one created while signed out never was. Upserting it
                    // first is idempotent, so do it once per playlist per flush.
                    if (playlistId !in playlistsInCloud) {
                        val playlist = playlistDao.getPlaylist(playlistId) ?: return true
                        if (!pushPlaylist(playlist)) return false
                        playlistsInCloud += playlistId
                    }
                    pushPlaylistTrack(track)
                } else {
                    deletePlaylistTrack(playlistId, trackId)
                }
            }
            SyncKind.EQ_PRESET ->
                if (upsert) eqPresetDao.getPresetById(change.key)?.let { pushEqPreset(it) } ?: true
                else deleteEqPreset(change.key)
            SyncKind.MIX_PRESET -> {
                val createdAt = change.key.toLongOrNull() ?: return true
                if (upsert) mixPresetDao.getByCreatedAt(createdAt)?.takeIf { it.isCustom }?.let { pushMixPreset(it) } ?: true
                else deleteMixPreset(createdAt)
            }
        }
    }

    // ─── Full initial sync (pull from cloud → merge into Room) ───────────────

    /**
     * Everything the cloud holds, merged into Room. Safe to run as often as you
     * like: see [pullLibrary] for what each section does on a conflict.
     *
     * This is the manual "Sync now" pull. [pullLibrary] is the same thing minus
     * the two expensive, least-repairing sections, and is what runs by itself.
     *
     * The order is deliberate. The library lands before the settings, so the
     * preset rows exist by the time `EQ_ACTIVE_PRESET_ID` arrives in DataStore
     * naming one of them.
     */
    suspend fun pullAll(): List<String> =
        pullLibrary(includePlayEvents = true) + pullSettingsSection()

    /**
     * The library half of a pull: favourites, mix presets and playlists.
     *
     * Split out so it can run unattended on every launch. The whole reason this
     * exists is that a device which loses library rows had no way back: app
     * settings have healed themselves on sign-in ever since
     * [SettingsSyncCoordinator] landed, while playlists and favourites were
     * meant to be pushed on every edit and were pulled only when somebody found
     * the Sync button on the profile screen. One half of the same account's data repaired
     * itself and the other half did not, which is how a full set of playlists
     * can sit intact in the cloud while the device that owns them shows an
     * empty list.
     *
     * Two conflict policies live here, on purpose. Favourites, playlists and
     * scrobbles are set membership: add and remove are the whole vocabulary and
     * a re-add is idempotent, so those sections insert-if-not-exists and local
     * data always wins. Presets are mutable documents under a stable key, and
     * that rule would freeze each one at the first version ever pushed: tune a
     * profile on the phone and the tablet keeps the old curve for ever. Those
     * two sections take the newer edit instead, compared as described on
     * [cloudCopyIsNewer].
     *
     * Neither policy may undo a local delete. Pending edits are flushed first,
     * and any row whose delete is still waiting in [SyncOutbox] — the cloud
     * was unreachable, or refused it — is skipped rather than re-inserted.
     * Without that, a song unliked offline was liked again on the next launch.
     *
     * @param includePlayEvents whether to pull the last thousand scrobbles too.
     *   They are the heaviest section by far and the least worth repeating on a
     *   launch — nothing on screen is missing without them — so the automatic
     *   path leaves them to the manual sync.
     */
    suspend fun pullLibrary(includePlayEvents: Boolean = true): List<String> {
        val uid = userId() ?: return listOf("not signed in")
        Log.d(TAG, "Starting cloud pull for user $uid (playEvents=$includePlayEvents)")
        val failed = mutableListOf<String>()

        // Local edits go up before cloud rows come down, so the cloud copy the
        // sections below read already includes them wherever it could.
        runCatching { flushOutbox() }.onFailure { Log.e(TAG, "flush before pull: ${it.message}") }
        // Read per section, after its fetch, so a delete made while the pull is
        // running is still honoured.
        suspend fun deleted(kind: SyncKind) = outbox.pendingDeletes(uid, kind)

        // Favorites
        runCatching {
            val tracks = supabase.postgrest["favorite_tracks"]
                .select { filter { eq("user_id", uid) } }
                .decodeList<SbFavoriteTrack>()
            val gone = deleted(SyncKind.FAVORITE_TRACK)
            tracks.filterNot { it.id.toString() in gone }.forEach { t ->
                favoritesDao.insertTrackIfNotExists(
                    FavoriteTrackEntity(
                        id = t.id,
                        title = t.title,
                        duration = t.duration,
                        artistId = t.artist_id,
                        artistName = t.artist_name,
                        albumId = t.album_id,
                        albumTitle = t.album_title,
                        albumCover = t.album_cover,
                        audioQuality = t.audio_quality,
                        explicit = t.explicit,
                        trackNumber = t.track_number
                    )
                )
            }
        }.onFailure { failed += "favorite_tracks"; Log.e(TAG, "pull favorite_tracks: ${it.message}") }

        runCatching {
            val albums = supabase.postgrest["favorite_albums"]
                .select { filter { eq("user_id", uid) } }
                .decodeList<SbFavoriteAlbum>()
            val gone = deleted(SyncKind.FAVORITE_ALBUM)
            albums.filterNot { it.id.toString() in gone }.forEach { a ->
                favoritesDao.insertAlbumIfNotExists(
                    FavoriteAlbumEntity(
                        id = a.id,
                        title = a.title,
                        artistId = a.artist_id,
                        artistName = a.artist_name,
                        cover = a.cover,
                        numberOfTracks = a.number_of_tracks,
                        releaseDate = a.release_date,
                        type = a.type
                    )
                )
            }
        }.onFailure { failed += "favorite_albums"; Log.e(TAG, "pull favorite_albums: ${it.message}") }

        runCatching {
            val artists = supabase.postgrest["favorite_artists"]
                .select { filter { eq("user_id", uid) } }
                .decodeList<SbFavoriteArtist>()
            val gone = deleted(SyncKind.FAVORITE_ARTIST)
            artists.filterNot { it.id.toString() in gone }.forEach { a ->
                favoritesDao.insertArtistIfNotExists(
                    FavoriteArtistEntity(id = a.id, name = a.name, picture = a.picture)
                )
            }
        }.onFailure { failed += "favorite_artists"; Log.e(TAG, "pull favorite_artists: ${it.message}") }

        // EQ presets, AutoEQ and parametric alike. These are the reason the
        // settings blob's EQ_ACTIVE_PRESET_ID used to name a preset that did
        // not exist on a fresh device: the id travelled and the preset it
        // pointed at did not.
        runCatching {
            val presets = supabase.postgrest["eq_presets"]
                .select { filter { eq("user_id", uid) } }
                .decodeList<SbEqPreset>()
            val gone = deleted(SyncKind.EQ_PRESET)
            presets.filterNot { it.local_id in gone }.forEach { p ->
                val local = eqPresetDao.getPresetById(p.local_id)
                if (cloudCopyIsNewer(local?.updatedAt, p.updated_at_ms)) {
                    eqPresetDao.insertPreset(p.toEntity())
                }
            }
        }.onFailure { failed += "eq_presets"; Log.e(TAG, "pull eq_presets: ${it.message}") }

        // Mix Presets
        runCatching {
            val mixPresets = supabase.postgrest["mix_presets"]
                .select { filter { eq("user_id", uid) } }
                .decodeList<SbMixPreset>()
            val gone = deleted(SyncKind.MIX_PRESET)
            mixPresets.filterNot { it.local_id in gone }.forEach { p ->
                // Matched on creation time, which is what local_id holds. The
                // code here used to assign local_id straight to the primary
                // key, so every incoming preset landed on row 0 and the last
                // one won.
                val createdAt = p.local_id.toLongOrNull()
                val local = createdAt?.let { mixPresetDao.getByCreatedAt(it) }
                if (cloudCopyIsNewer(local?.updatedAt, p.updated_at_ms)) {
                    mixPresetDao.upsert(p.toEntity(existingId = local?.id ?: 0L))
                }
            }
        }.onFailure { failed += "mix_presets"; Log.e(TAG, "pull mix_presets: ${it.message}") }

        // Playlists
        runCatching {
            val playlists = supabase.postgrest["user_playlists"]
                .select { filter { eq("user_id", uid) } }
                .decodeList<SbPlaylist>()
            val gonePlaylists = deleted(SyncKind.PLAYLIST)
            playlists.filterNot { it.id in gonePlaylists }.forEach { p ->
                playlistDao.insertPlaylistIfNotExists(
                    UserPlaylistEntity(
                        id = p.id,
                        name = p.name,
                        description = p.description,
                        isPublic = p.is_public
                    )
                )
                val tracks = supabase.postgrest["playlist_tracks"]
                    .select { filter { eq("playlist_id", p.id) } }
                    .decodeList<SbPlaylistTrack>()
                val goneTracks = deleted(SyncKind.PLAYLIST_TRACK)
                tracks.filterNot { playlistTrackKey(it.playlist_id, it.track_id) in goneTracks }.forEach { t ->
                    playlistDao.insertTrackIfNotExists(
                        PlaylistTrackEntity(
                            playlistId = t.playlist_id,
                            trackId = t.track_id,
                            title = t.title,
                            duration = t.duration,
                            artistName = t.artist_name,
                            albumId = t.album_id,
                            albumTitle = t.album_title,
                            albumCover = t.album_cover,
                            position = t.position
                        )
                    )
                }
            }
        }.onFailure { failed += "playlists"; Log.e(TAG, "pull playlists: ${it.message}") }

        // Play events — pull the most recent 1000 and merge into Room so stats
        // come across on a new device. Skip events already present (same track
        // + same playedAt) to avoid duplicating scrobbles if pull runs twice.
        if (includePlayEvents) runCatching {
            val events = supabase.postgrest["play_events"]
                .select {
                    filter { eq("user_id", uid) }
                    order("played_at_ms", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                    limit(1000)
                }
                .decodeList<SbPlayEvent>()
            events.forEach { e ->
                // insertFromCloud is @Insert(onConflict = IGNORE) on the unique
                // cloudRowId index — so re-pulling never duplicates local stats.
                // Carrying cloudRowId is also what lets pushAll skip these rows.
                playEventDao.insertFromCloud(
                    PlayEventEntity(
                        trackId = e.track_id,
                        title = e.title,
                        duration = e.duration,
                        artistId = e.artist_id,
                        artistName = e.artist_name,
                        albumId = e.album_id,
                        albumTitle = e.album_title,
                        albumCover = e.album_cover,
                        audioQuality = e.audio_quality,
                        source = e.source,
                        playedAt = e.played_at_ms,
                        cloudRowId = e.id,
                    )
                )
            }
        }.onFailure { failed += "play_events"; Log.e(TAG, "pull play_events: ${it.message}") }

        Log.d(TAG, "Cloud pull complete (${failed.size} failed sections)")
        return failed
    }

    /**
     * App settings, applied to local DataStore.
     *
     * Kept out of [pullLibrary] because [SettingsSyncCoordinator] already owns
     * this on sign-in, and it owns it carefully: it holds an applyingRemote
     * flag while the snapshot lands so the writes it causes don't echo straight
     * back as a push. A second, unguarded pull racing that one is how the
     * ping-pong it was written to prevent gets back in.
     */
    private suspend fun pullSettingsSection(): List<String> =
        if (pullSettings() == SettingsPull.FAILED) listOf("settings") else emptyList()

    // ─── Full push (local → cloud) after import ─────────────────────────────

    /**
     * Push all local library data + settings to Supabase.
     * @return the names of any sections that failed (empty = full success). The
     * caller must surface a non-empty result instead of reporting success — each
     * section swallows its own exception so a failure here is otherwise silent.
     */
    suspend fun pushAll(): List<String> {
        val uid = userId() ?: return listOf("not signed in")
        Log.d(TAG, "Starting full cloud push for user $uid")
        val failed = mutableListOf<String>()
        suspend fun section(name: String, block: suspend () -> Unit) {
            runCatching { block() }.onFailure { failed += name; Log.e(TAG, "push $name: ${it.message}") }
        }
        // Each push swallows its own error and answers false, so a section has
        // to count the refusals itself — otherwise a sync that sent nothing
        // reports success and the user trusts a backup that isn't there.
        suspend fun <T> List<T>.sendAll(push: suspend (T) -> Boolean) {
            val refused = count { !push(it) }
            check(refused == 0) { "$refused of $size rows refused" }
        }

        // Pending deletes first: they are the edits a full push can't express.
        section("pending_edits") {
            flushOutbox()
            check(outbox.forUser(uid).isEmpty()) { "some local edits are still unsent" }
        }
        section("favorite_tracks") { favoritesDao.getFavoriteTracksSnapshot().sendAll { pushFavoriteTrack(it) } }
        section("favorite_albums") { favoritesDao.getFavoriteAlbumsSnapshot().sendAll { pushFavoriteAlbum(it) } }
        section("favorite_artists") { favoritesDao.getFavoriteArtistsSnapshot().sendAll { pushFavoriteArtist(it) } }
        section("play_history") { historyDao.getHistorySnapshot(500).forEach { pushHistoryTrack(it) } }
        // Play events: only push rows not already in the cloud (cloudRowId IS
        // NULL), and record the assigned cloud id, so repeated syncs don't
        // re-insert the same scrobble and inflate stats (the old code re-pushed
        // the last 1000 every time).
        section("play_events") {
            playEventDao.getUnsynced(1000).forEach { e ->
                pushPlayEvent(e)?.let { cloudId -> playEventDao.setCloudId(e.rowId, cloudId) }
            }
        }
        section("playlists") {
            playlistDao.getAllPlaylistsSnapshot().forEach { playlist ->
                check(pushPlaylist(playlist)) { "playlist ${playlist.id} refused" }
                playlistDao.getPlaylistTracksSnapshot(playlist.id).sendAll { pushPlaylistTrack(it) }
            }
        }
        // Only custom presets: the built-ins ship with the app, are identical
        // on every device, and would be a table full of noise that the pull
        // then writes back over the local originals.
        section("eq_presets") {
            eqPresetDao.getAllPresetsSnapshot()
                .filter { it.isCustom }
                .sendAll { pushEqPreset(it) }
        }
        section("mix_presets") {
            mixPresetDao.getAllPresetsSnapshot()
                .filter { it.isCustom }
                .sendAll { pushMixPreset(it) }
        }
        section("settings") { check(pushSettings()) { "settings refused" } }

        Log.d(TAG, "Cloud push complete (${failed.size} failed sections)")
        return failed
    }
}
