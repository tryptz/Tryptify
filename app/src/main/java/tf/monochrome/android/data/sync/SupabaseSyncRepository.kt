package tf.monochrome.android.data.sync

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import tf.monochrome.android.data.auth.SupabaseAuthManager
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
    val preamp: Float = 0f,
    val target_id: String = "",
    val target_name: String = "",
    val is_custom: Boolean = true,
    val created_at: String? = null,
    val updated_at: String? = null
)

@Serializable
data class SbMixPreset(
    val id: String? = null,
    val user_id: String? = null,
    val local_id: String,
    val name: String,
    val state_json: String,
    val is_custom: Boolean = true,
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
    val played_at: String? = null
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

// ─── Repository ───────────────────────────────────────────────────────────────

@Singleton
class SupabaseSyncRepository @Inject constructor(
    private val authManager: SupabaseAuthManager,
    private val favoritesDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val mixPresetDao: MixPresetDao,
    private val playlistDao: PlaylistDao,
    private val playEventDao: PlayEventDao,
) {
    private val supabase get() = authManager.supabase
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun userId(): String? = authManager.userProfile.value?.id

    // ─── EQ Presets ──────────────────────────────────────────────────────────

    suspend fun pushEqPreset(preset: EqPresetEntity) {
        val uid = userId() ?: return
        runCatching {
            supabase.postgrest["eq_presets"].upsert(
                SbEqPreset(
                    user_id = uid,
                    local_id = preset.id,
                    name = preset.name,
                    description = preset.description,
                    bands = preset.bandsJson,
                    preamp = preset.preamp,
                    target_id = preset.targetId,
                    target_name = preset.targetName,
                    is_custom = preset.isCustom
                )
            ) { onConflict = "user_id,local_id" }
        }.onFailure { Log.e(TAG, "pushEqPreset failed: ${it.message}") }
    }

    suspend fun deleteEqPreset(localId: String) {
        val uid = userId() ?: return
        runCatching {
            supabase.postgrest["eq_presets"]
                .delete { filter { eq("user_id", uid); eq("local_id", localId) } }
        }.onFailure { Log.e(TAG, "deleteEqPreset failed: ${it.message}") }
    }

    // ─── Mix Presets ─────────────────────────────────────────────────────────

    suspend fun pushMixPreset(preset: MixPresetEntity) {
        val uid = userId() ?: return
        runCatching {
            supabase.postgrest["mix_presets"].upsert(
                SbMixPreset(
                    user_id = uid,
                    local_id = preset.id.toString(),
                    name = preset.name,
                    state_json = preset.stateJson,
                    is_custom = preset.isCustom
                )
            ) { onConflict = "user_id,local_id" }
        }.onFailure { Log.e(TAG, "pushMixPreset failed: ${it.message}") }
    }

    suspend fun deleteMixPreset(localId: Long) {
        val uid = userId() ?: return
        runCatching {
            supabase.postgrest["mix_presets"]
                .delete { filter { eq("user_id", uid); eq("local_id", localId.toString()) } }
        }.onFailure { Log.e(TAG, "deleteMixPreset failed: ${it.message}") }
    }

    // ─── Favorites ───────────────────────────────────────────────────────────

    suspend fun pushFavoriteTrack(track: FavoriteTrackEntity) {
        val uid = userId() ?: return
        runCatching {
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
        }.onFailure { Log.e(TAG, "pushFavoriteTrack failed: ${it.message}") }
    }

    suspend fun deleteFavoriteTrack(trackId: Long) {
        val uid = userId() ?: return
        runCatching {
            supabase.postgrest["favorite_tracks"]
                .delete { filter { eq("user_id", uid); eq("id", trackId) } }
        }.onFailure { Log.e(TAG, "deleteFavoriteTrack failed: ${it.message}") }
    }

    suspend fun pushFavoriteAlbum(album: FavoriteAlbumEntity) {
        val uid = userId() ?: return
        runCatching {
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
        }.onFailure { Log.e(TAG, "pushFavoriteAlbum failed: ${it.message}") }
    }

    suspend fun deleteFavoriteAlbum(albumId: Long) {
        val uid = userId() ?: return
        runCatching {
            supabase.postgrest["favorite_albums"]
                .delete { filter { eq("user_id", uid); eq("id", albumId) } }
        }.onFailure { Log.e(TAG, "deleteFavoriteAlbum failed: ${it.message}") }
    }

    suspend fun pushFavoriteArtist(artist: FavoriteArtistEntity) {
        val uid = userId() ?: return
        runCatching {
            supabase.postgrest["favorite_artists"].upsert(
                SbFavoriteArtist(
                    id = artist.id,
                    user_id = uid,
                    name = artist.name,
                    picture = artist.picture
                )
            ) { onConflict = "user_id,id" }
        }.onFailure { Log.e(TAG, "pushFavoriteArtist failed: ${it.message}") }
    }

    suspend fun deleteFavoriteArtist(artistId: Long) {
        val uid = userId() ?: return
        runCatching {
            supabase.postgrest["favorite_artists"]
                .delete { filter { eq("user_id", uid); eq("id", artistId) } }
        }.onFailure { Log.e(TAG, "deleteFavoriteArtist failed: ${it.message}") }
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
                    audio_quality = track.audioQuality
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

    suspend fun pushPlaylist(playlist: UserPlaylistEntity) {
        val uid = userId() ?: return
        runCatching {
            supabase.postgrest["user_playlists"].upsert(
                SbPlaylist(
                    id = playlist.id,
                    user_id = uid,
                    name = playlist.name,
                    description = playlist.description,
                    is_public = playlist.isPublic
                )
            ) { onConflict = "id" }
        }.onFailure { Log.e(TAG, "pushPlaylist failed: ${it.message}") }
    }

    suspend fun pushPlaylistTrack(track: PlaylistTrackEntity) {
        val uid = userId() ?: return
        runCatching {
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
        }.onFailure { Log.e(TAG, "pushPlaylistTrack failed: ${it.message}") }
    }

    suspend fun deletePlaylist(playlistId: String) {
        val uid = userId() ?: return
        runCatching {
            supabase.postgrest["user_playlists"]
                .delete { filter { eq("id", playlistId); eq("user_id", uid) } }
        }.onFailure { Log.e(TAG, "deletePlaylist failed: ${it.message}") }
    }

    // ─── Full initial sync (pull from cloud → merge into Room) ───────────────

    /**
     * Called once after sign-in. Pulls all cloud data and merges it into Room.
     * Existing local data wins on conflict — we don't overwrite local with cloud.
     */
    suspend fun pullAll() {
        val uid = userId() ?: return
        Log.d(TAG, "Starting full cloud pull for user $uid")

        // Favorites
        runCatching {
            val tracks = supabase.postgrest["favorite_tracks"]
                .select { filter { eq("user_id", uid) } }
                .decodeList<SbFavoriteTrack>()
            tracks.forEach { t ->
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
        }.onFailure { Log.e(TAG, "pull favorite_tracks: ${it.message}") }

        runCatching {
            val albums = supabase.postgrest["favorite_albums"]
                .select { filter { eq("user_id", uid) } }
                .decodeList<SbFavoriteAlbum>()
            albums.forEach { a ->
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
        }.onFailure { Log.e(TAG, "pull favorite_albums: ${it.message}") }

        runCatching {
            val artists = supabase.postgrest["favorite_artists"]
                .select { filter { eq("user_id", uid) } }
                .decodeList<SbFavoriteArtist>()
            artists.forEach { a ->
                favoritesDao.insertArtistIfNotExists(
                    FavoriteArtistEntity(id = a.id, name = a.name, picture = a.picture)
                )
            }
        }.onFailure { Log.e(TAG, "pull favorite_artists: ${it.message}") }

        // Mix Presets
        runCatching {
            val mixPresets = supabase.postgrest["mix_presets"]
                .select { filter { eq("user_id", uid) } }
                .decodeList<SbMixPreset>()
            mixPresets.forEach { p ->
                mixPresetDao.insertIfNotExists(
                    MixPresetEntity(
                        id = p.local_id.toLongOrNull() ?: 0L,
                        name = p.name,
                        stateJson = p.state_json,
                        isCustom = p.is_custom
                    )
                )
            }
        }.onFailure { Log.e(TAG, "pull mix_presets: ${it.message}") }

        // Playlists
        runCatching {
            val playlists = supabase.postgrest["user_playlists"]
                .select { filter { eq("user_id", uid) } }
                .decodeList<SbPlaylist>()
            playlists.forEach { p ->
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
                tracks.forEach { t ->
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
        }.onFailure { Log.e(TAG, "pull playlists: ${it.message}") }

        // Play events — pull the most recent 1000 and merge into Room so stats
        // come across on a new device. Skip events already present (same track
        // + same playedAt) to avoid duplicating scrobbles if pull runs twice.
        runCatching {
            val events = supabase.postgrest["play_events"]
                .select {
                    filter { eq("user_id", uid) }
                    order("played_at_ms", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                    limit(1000)
                }
                .decodeList<SbPlayEvent>()
            events.forEach { e ->
                playEventDao.insert(
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
                        playedAt = e.played_at_ms
                    )
                )
            }
        }.onFailure { Log.e(TAG, "pull play_events: ${it.message}") }

        Log.d(TAG, "Cloud pull complete")
    }

    // ─── Full push (local → cloud) after import ─────────────────────────────

    /**
     * Push all local favorites, playlists, and history to Supabase.
     * Called after a JSON library import to sync imported data to cloud.
     */
    suspend fun pushAll() {
        val uid = userId() ?: return
        Log.d(TAG, "Starting full cloud push for user $uid")

        // Favorite tracks
        runCatching {
            favoritesDao.getFavoriteTracksSnapshot().forEach { pushFavoriteTrack(it) }
        }.onFailure { Log.e(TAG, "push favorite_tracks: ${it.message}") }

        // Favorite albums
        runCatching {
            favoritesDao.getFavoriteAlbumsSnapshot().forEach { pushFavoriteAlbum(it) }
        }.onFailure { Log.e(TAG, "push favorite_albums: ${it.message}") }

        // Favorite artists
        runCatching {
            favoritesDao.getFavoriteArtistsSnapshot().forEach { pushFavoriteArtist(it) }
        }.onFailure { Log.e(TAG, "push favorite_artists: ${it.message}") }

        // History (aggregated — one row per track)
        runCatching {
            historyDao.getHistorySnapshot(500).forEach { pushHistoryTrack(it) }
        }.onFailure { Log.e(TAG, "push play_history: ${it.message}") }

        // Play events (per-play scrobble log — drives Listening Stats)
        runCatching {
            playEventDao.getRecent(1000).forEach { pushPlayEvent(it) }
        }.onFailure { Log.e(TAG, "push play_events: ${it.message}") }

        // Playlists + tracks
        runCatching {
            playlistDao.getAllPlaylistsSnapshot().forEach { playlist ->
                pushPlaylist(playlist)
                playlistDao.getPlaylistTracksSnapshot(playlist.id).forEach { track ->
                    pushPlaylistTrack(track)
                }
            }
        }.onFailure { Log.e(TAG, "push playlists: ${it.message}") }

        Log.d(TAG, "Cloud push complete")
    }
}
