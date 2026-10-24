package tf.monochrome.android.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tf.monochrome.android.data.db.dao.DownloadDao
import tf.monochrome.android.data.db.dao.FavoriteDao
import tf.monochrome.android.data.db.dao.HistoryDao
import tf.monochrome.android.data.db.dao.PlayEventDao
import tf.monochrome.android.data.db.dao.PlaylistDao
import tf.monochrome.android.data.db.dao.PlaylistTrackReplacement
import tf.monochrome.android.data.db.entity.DownloadedTrackEntity
import tf.monochrome.android.data.db.entity.FavoriteAlbumEntity
import tf.monochrome.android.data.db.entity.FavoriteArtistEntity
import tf.monochrome.android.data.db.entity.FavoriteTrackEntity
import tf.monochrome.android.data.db.entity.HistoryTrackEntity
import tf.monochrome.android.data.db.entity.PlayEventEntity
import tf.monochrome.android.data.db.entity.PlaylistTrackEntity
import tf.monochrome.android.data.db.entity.UserPlaylistEntity
import tf.monochrome.android.data.device.DeviceRegistry
import tf.monochrome.android.data.sync.SupabaseSyncRepository
import tf.monochrome.android.data.sync.SyncKind
import tf.monochrome.android.data.sync.SyncOp
import tf.monochrome.android.data.sync.playlistTrackKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.player.PlaySessionManager
import tf.monochrome.android.player.UnifiedTrackRegistry
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val playEventDao: PlayEventDao,
    private val playlistDao: PlaylistDao,
    private val downloadDao: DownloadDao,
    private val supabaseSync: SupabaseSyncRepository,
    private val deviceRegistry: DeviceRegistry,
    private val playSessionManager: PlaySessionManager,
    private val unifiedTrackRegistry: UnifiedTrackRegistry,
    private val qobuzIdRegistry: QobuzIdRegistry,
    private val preferences: tf.monochrome.android.data.preferences.PreferencesManager,
    private val downloadManager: tf.monochrome.android.data.downloads.DownloadManager,
    private val localTrackLocator: tf.monochrome.android.player.LocalTrackLocator,
) {
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // ignoreUnknownKeys lets older serialized rows survive forward-compatible
    // schema changes to UnifiedTrack / PlaybackSource.
    private val historyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // --- Favorites ---

    fun getFavoriteTracks(): Flow<List<Track>> = favoriteDao.getFavoriteTracks().map { entities ->
        entities.map { rememberSource(it.id, it.source); it.toDomain() }
    }

    fun getFavoriteAlbums(): Flow<List<Album>> = favoriteDao.getFavoriteAlbums().map { entities ->
        entities.map { it.toDomain() }
    }

    fun getFavoriteArtists(): Flow<List<Artist>> = favoriteDao.getFavoriteArtists().map { entities ->
        entities.map { it.toDomain() }
    }

    // Every library edit below is written to Room first and then queued for
    // the cloud with supabaseSync.queueChange, which holds it until Supabase
    // accepts it. Before, none of these reached the cloud except through the
    // manual Sync button, and deletes never did — so the launch-time pull
    // brought unliked songs and deleted playlists straight back.

    /**
     * The service [track]'s id belongs to, kept with a liked or playlisted
     * song (FavoriteTrackEntity.source): Qobuz, Deezer, Apple and TIDAL ids
     * are plain numbers in overlapping ranges, and the registry that tells
     * them apart lives on this device only, so a fresh install or the website
     * would otherwise read every one as TIDAL's. What the queued track plays
     * from says it outright; a bare Track falls back to the registry, as
     * toUnifiedTrackAuto does.
     */
    /**
     * Teaches this device's registry a saved song's service, so a row synced
     * from another device (or kept from before a reinstall) plays from where
     * it came from instead of falling through to TIDAL.
     */
    private fun rememberSource(id: Long, source: String?) {
        when (source) {
            TrackSource.QOBUZ -> qobuzIdRegistry.registerTrack(id)
            TrackSource.DEEZER -> qobuzIdRegistry.registerDeezerTrack(id)
            TrackSource.APPLE -> qobuzIdRegistry.registerAppleTrack(id)
        }
    }

    private fun sourceOf(track: Track): String = when (unifiedTrackRegistry[track.id]?.source) {
        is PlaybackSource.LocalFile -> TrackSource.LOCAL
        is PlaybackSource.QobuzCached -> TrackSource.QOBUZ
        is PlaybackSource.DeezerPreview -> TrackSource.DEEZER
        is PlaybackSource.AppleCached -> TrackSource.APPLE
        is PlaybackSource.Subsonic -> TrackSource.SUBSONIC
        is PlaybackSource.HiFiApi -> TrackSource.TIDAL
        else -> when {
            track.deezerId != null || qobuzIdRegistry.isDeezerTrack(track.id) -> TrackSource.DEEZER
            track.appleId != null || qobuzIdRegistry.isAppleTrack(track.id) -> TrackSource.APPLE
            qobuzIdRegistry.isQobuzTrack(track.id) -> TrackSource.QOBUZ
            // A local file's id is a hash and its cover a file in the app's storage.
            track.id <= 0 || track.album?.cover?.startsWith("/") == true -> TrackSource.LOCAL
            else -> TrackSource.TIDAL
        }
    }

    suspend fun toggleFavoriteTrack(track: Track) {
        if (favoriteDao.isFavoriteTrack(track.id)) {
            favoriteDao.deleteFavoriteTrack(track.id)
            supabaseSync.queueChange(SyncKind.FAVORITE_TRACK, track.id.toString(), SyncOp.DELETE)
        } else {
            favoriteDao.insertFavoriteTrack(track.toFavoriteEntity().copy(source = sourceOf(track)))
            supabaseSync.queueChange(SyncKind.FAVORITE_TRACK, track.id.toString(), SyncOp.UPSERT)
            autoDownloadOnLike(track)
        }
    }

    /**
     * "Automatically download liked songs": queue this song if the setting is
     * on and it isn't already on the device.
     *
     * Hangs off the like itself rather than off the setting, which is the whole
     * safety property — the toggle never looks at songs liked before it was
     * switched on, so flipping it can't enqueue a whole Liked Songs library's
     * worth of workers in one go. Downloads then arrive at the rate the user
     * actually likes things.
     *
     * Runs detached and swallows its own failures: liking a song must succeed
     * even when the download can't be queued.
     */
    private fun autoDownloadOnLike(track: Track) {
        syncScope.launch {
            runCatching {
                if (!preferences.autoDownloadLikedSongs.first()) return@launch
                if (downloadDao.isDownloaded(track.id)) return@launch
                // Already playable from disk — a scanned library file, a previous
                // download under another catalogue's id — so there's nothing to fetch.
                if (unifiedTrackRegistry[track.id]?.source is PlaybackSource.LocalFile) return@launch
                val onDisk = localTrackLocator.findLocalSource(
                    title = track.title,
                    artist = track.displayArtist,
                    albumTitle = track.album?.title,
                    durationSeconds = track.duration,
                    catalogTrackId = track.id,
                )
                if (onDisk != null) return@launch
                downloadManager.downloadTrack(track)
            }
        }
    }

    /** Explicit bulk unlike (idempotent — no toggle race on already-removed ids). */
    suspend fun removeFavoriteTracks(trackIds: Collection<Long>) {
        trackIds.forEach {
            favoriteDao.deleteFavoriteTrack(it)
            supabaseSync.queueChange(SyncKind.FAVORITE_TRACK, it.toString(), SyncOp.DELETE)
        }
    }

    fun isFavoriteTrack(trackId: Long): Flow<Boolean> = favoriteDao.isFavoriteTrackFlow(trackId)
    fun isFavoriteAlbum(albumId: Long): Flow<Boolean> = favoriteDao.isFavoriteAlbumFlow(albumId)
    fun isFavoriteArtist(artistId: Long): Flow<Boolean> = favoriteDao.isFavoriteArtistFlow(artistId)

    // --- History ---

    fun getHistory(): Flow<List<Track>> = historyDao.getHistory().map { entities ->
        entities.map { row ->
            // Re-hydrate the in-memory routing registries so a tap on a
            // Recently-Played row after process death plays the same source it
            // was added under (Qobuz/local/collection) instead of falling
            // through to TIDAL with a wrong id.
            row.unifiedJson?.let { json ->
                runCatching { historyJson.decodeFromString<UnifiedTrack>(json) }
                    .getOrNull()
                    ?.let { unified ->
                        unifiedTrackRegistry.put(row.id, unified)
                        if (unified.source is PlaybackSource.QobuzCached) {
                            qobuzIdRegistry.registerTrack(row.id)
                        }
                    }
            }
            row.toDomain()
        }
    }

    suspend fun addToHistory(track: Track, unified: UnifiedTrack? = null) {
        val historyRow = track.toHistoryEntity().copy(
            unifiedJson = unified?.let {
                runCatching { historyJson.encodeToString(it) }.getOrNull()
            }
        )
        val local = isLocalPlay(track.id, unified?.source)
        val event = track.toPlayEventEntity(local)
        historyDao.addToHistory(historyRow)
        val localRowId = playEventDao.insert(event)
        // One song closer to the tip bar. Counted here rather than in the
        // service because this is already the app's own definition of "a track
        // was played", and the service reaches it from four different places.
        preferences.recordPlayTowardsDonatePrompt()
        // Fire-and-forget cloud sync — no-op if the user isn't signed in.
        // Only play_events goes up. The row used to go to play_history as
        // well, a table nothing ever read back: a second request per play,
        // and a prune trigger on every insert.
        syncScope.launch {
            val sessionId = playSessionManager.sessionFor(event.playedAt)
            val deviceId = deviceRegistry.snapshotRemoteId()
            val sourceType = track.cloudSourceType(local)
            val sourceRef = track.cloudSourceRef(sourceType)
            val cloudId = supabaseSync.pushPlayEvent(
                event = event,
                sessionId = sessionId,
                deviceId = deviceId,
                sourceType = sourceType,
                sourceRef = sourceRef,
            )
            if (cloudId != null) {
                playEventDao.setCloudId(localRowId, cloudId)
            }
        }
    }

    suspend fun clearHistory() {
        historyDao.clearHistory()
        playEventDao.clearAll()
    }

    /** Remove individual entries from listening history (play-event stats are kept). */
    suspend fun removeFromHistory(trackIds: Collection<Long>) {
        trackIds.forEach { historyDao.deleteByTrackId(it) }
    }

    // --- Play events / stats ---

    suspend fun getMostPlayed(limit: Int = 50): List<Track> {
        return historyDao.getMostPlayed(limit).map { it.toDomain() }
    }

    /**
     * Artist names the user has shown taste for, ordered by signal strength:
     * explicitly hearted artists first, then artists of hearted tracks, then
     * the most-played artists from history. De-duped case-insensitively and
     * capped at [limit]. Used to seed the personalized discovery feed; names
     * (not ids) keep the downstream Qobuz lookups in one namespace.
     */
    suspend fun getSeedArtistNames(limit: Int = 6): List<String> {
        val favArtists = favoriteDao.getFavoriteArtistsSnapshot().map { it.name }
        val favTrackArtists = favoriteDao.getFavoriteTracksSnapshot().map { it.artistName }
        val historyArtists = historyDao.getTopArtists(limit).first().map { it.name }
        return (favArtists + favTrackArtists + historyArtists)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(limit)
    }

    // --- Playlists ---

    fun getAllPlaylists(): Flow<List<UserPlaylistEntity>> = playlistDao.getAllPlaylists()

    suspend fun createPlaylist(name: String, description: String? = null): String {
        val id = UUID.randomUUID().toString()
        playlistDao.insertPlaylist(
            UserPlaylistEntity(
                id = id,
                name = name,
                description = description
            )
        )
        supabaseSync.queueChange(SyncKind.PLAYLIST, id, SyncOp.UPSERT)
        return id
    }

    suspend fun updatePlaylist(playlistId: String, name: String, description: String?, isPublic: Boolean = false) {
        playlistDao.getPlaylist(playlistId)?.let { playlist ->
            playlistDao.updatePlaylist(
                playlist.copy(
                    name = name,
                    description = description,
                    isPublic = isPublic,
                    updatedAt = System.currentTimeMillis()
                )
            )
            supabaseSync.queueChange(SyncKind.PLAYLIST, playlistId, SyncOp.UPSERT)
        }
    }

    suspend fun deletePlaylist(playlistId: String) {
        playlistDao.deletePlaylist(playlistId)
        supabaseSync.queueChange(SyncKind.PLAYLIST, playlistId, SyncOp.DELETE)
    }

    fun getPlaylistTracks(playlistId: String): Flow<List<Track>> {
        return playlistDao.getPlaylistTracks(playlistId).map { entities ->
            entities.map { rememberSource(it.trackId, it.source); it.toDomain() }
        }
    }

    suspend fun addTrackToPlaylist(playlistId: String, track: Track) {
        playlistDao.addTrackToPlaylist(playlistId, track.toPlaylistTrackEntity(playlistId).copy(source = sourceOf(track)))
        supabaseSync.queueChange(SyncKind.PLAYLIST_TRACK, playlistTrackKey(playlistId, track.id), SyncOp.UPSERT)
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: Long) {
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)
        supabaseSync.queueChange(SyncKind.PLAYLIST_TRACK, playlistTrackKey(playlistId, trackId), SyncOp.DELETE)
    }

    /** The playlist's rows as stored, in order — ids, positions and all. */
    suspend fun getPlaylistTrackRows(playlistId: String): List<PlaylistTrackEntity> =
        playlistDao.getPlaylistTracksSnapshot(playlistId)

    /**
     * Swaps the row [oldTrackId] for [replacement] in place (see
     * [PlaylistDao.replacePlaylistTrack]). Both keys are queued: the old one's
     * delete, so no pull brings the wrong id back, and the new one's upsert.
     */
    suspend fun replacePlaylistTrack(
        playlistId: String,
        oldTrackId: Long,
        replacement: Track,
    ): PlaylistTrackReplacement {
        val outcome = playlistDao.replacePlaylistTrack(
            playlistId, oldTrackId, replacement.toPlaylistTrackEntity(playlistId).copy(source = sourceOf(replacement))
        )
        if (outcome == PlaylistTrackReplacement.REPLACED || outcome == PlaylistTrackReplacement.MERGED) {
            supabaseSync.queueChange(SyncKind.PLAYLIST_TRACK, playlistTrackKey(playlistId, oldTrackId), SyncOp.DELETE)
        }
        if (outcome == PlaylistTrackReplacement.REPLACED) {
            supabaseSync.queueChange(SyncKind.PLAYLIST_TRACK, playlistTrackKey(playlistId, replacement.id), SyncOp.UPSERT)
        }
        return outcome
    }

    // --- Downloads ---

    fun getDownloadedTracks(): Flow<List<DownloadedTrackEntity>> = downloadDao.getDownloadedTracks()

    suspend fun isDownloaded(trackId: Long): Boolean = downloadDao.isDownloaded(trackId)

    fun isDownloadedFlow(trackId: Long): Flow<Boolean> = downloadDao.isDownloadedFlow(trackId)

    suspend fun getDownloadedTrack(trackId: Long): DownloadedTrackEntity? =
        downloadDao.getDownloadedTrack(trackId)

    fun getTotalDownloadSize(): Flow<Long?> = downloadDao.getTotalDownloadSize()
}

// --- Entity <-> Domain conversion ---

private fun FavoriteTrackEntity.toDomain() = Track(
    id = id,
    title = title,
    duration = duration,
    artist = Artist(id = artistId ?: 0, name = artistName),
    album = albumId?.let { Album(id = it, title = albumTitle ?: "", cover = albumCover) },
    audioQuality = audioQuality,
    explicit = explicit,
    trackNumber = trackNumber,
    deezerId = id.takeIf { source == TrackSource.DEEZER },
    appleId = id.takeIf { source == TrackSource.APPLE },
)

private fun FavoriteAlbumEntity.toDomain() = Album(
    id = id,
    title = title,
    artist = Artist(id = artistId ?: 0, name = artistName),
    cover = cover,
    numberOfTracks = numberOfTracks,
    releaseDate = releaseDate,
    type = type
)

private fun FavoriteArtistEntity.toDomain() = Artist(
    id = id,
    name = name,
    picture = picture
)

private fun HistoryTrackEntity.toDomain() = Track(
    id = id,
    title = title,
    duration = duration,
    artist = Artist(id = artistId ?: 0, name = artistName),
    album = albumId?.let { Album(id = it, title = albumTitle ?: "", cover = albumCover) },
    audioQuality = audioQuality
)

private fun PlaylistTrackEntity.toDomain() = Track(
    id = trackId,
    title = title,
    duration = duration,
    artist = Artist(id = 0, name = artistName),
    album = albumId?.let { Album(id = it, title = albumTitle ?: "", cover = albumCover) },
    deezerId = trackId.takeIf { source == TrackSource.DEEZER },
    appleId = trackId.takeIf { source == TrackSource.APPLE },
)

private fun Track.toFavoriteEntity() = FavoriteTrackEntity(
    id = id,
    title = title,
    duration = duration,
    artistId = artist?.id,
    artistName = displayArtist,
    albumId = album?.id,
    albumTitle = album?.title,
    albumCover = album?.cover,
    audioQuality = audioQuality,
    explicit = explicit,
    trackNumber = trackNumber
)

private fun Album.toFavoriteEntity() = FavoriteAlbumEntity(
    id = id,
    title = title,
    artistId = artist?.id,
    artistName = displayArtist,
    cover = cover,
    numberOfTracks = numberOfTracks,
    releaseDate = releaseDate,
    type = type
)

private fun Artist.toFavoriteEntity() = FavoriteArtistEntity(
    id = id,
    name = name,
    picture = picture
)

private fun Track.toHistoryEntity() = HistoryTrackEntity(
    id = id,
    title = title,
    duration = duration,
    artistId = artist?.id,
    artistName = displayArtist,
    albumId = album?.id,
    albumTitle = album?.title,
    albumCover = album?.cover,
    audioQuality = audioQuality
)

/**
 * Whether a play is of a file in the local library: what the queued track
 * says it plays from. A local file is always queued with its [UnifiedTrack],
 * so a track without one is a catalogue stream, and the old rule (positive
 * ids are the catalogue's) stands for it. The id alone can't tell a local
 * file: its id is the hash of "local_<n>", positive about half the time.
 */
internal fun isLocalPlay(trackId: Long, source: PlaybackSource?): Boolean =
    if (source == null) trackId <= 0 else source is PlaybackSource.LocalFile

/**
 * A play as the stats keep it. A local file's play says so ("local"); a stream
 * leaves the source empty, as before, since which service it came from is not
 * known here (see StatsReport's sourceOf).
 */
private fun Track.toPlayEventEntity(local: Boolean) = PlayEventEntity(
    trackId = id,
    title = title,
    duration = duration,
    artistId = artist?.id,
    artistName = displayArtist,
    albumId = album?.id,
    albumTitle = album?.title,
    albumCover = album?.cover,
    audioQuality = audioQuality,
    source = if (local) "local" else null,
    playedAt = System.currentTimeMillis()
)

private fun Track.toPlaylistTrackEntity(playlistId: String) = PlaylistTrackEntity(
    playlistId = playlistId,
    trackId = id,
    title = title,
    duration = duration,
    artistName = displayArtist,
    albumId = album?.id,
    albumTitle = album?.title,
    albumCover = album?.cover
)

/**
 * Best-effort mapping from a Track to the canonical-catalog `source` tag
 * used by `public.catalog_track_sources`. A local file is "local", whatever
 * its id ([isLocalPlay]: a local id is a hash, often positive, and was filed
 * as a TIDAL track). Otherwise positive `id` values come from the HiFi (TIDAL)
 * backend. Collection-direct playback uses its own repository path (not this
 * one).
 */
private fun Track.cloudSourceType(local: Boolean): String = if (!local && id > 0) "tidal" else "local"

/**
 * Stable per-source identifier for catalog upsert. Must be deterministic
 * so the same physical track always resolves to the same catalog_tracks.id.
 */
private fun Track.cloudSourceRef(sourceType: String): String = when (sourceType) {
    "tidal" -> id.toString()
    else -> sha1("local:${artist?.id ?: 0}:${album?.id ?: 0}:$title:$duration")
}

private fun sha1(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

/** The values of FavoriteTrackEntity.source and PlaylistTrackEntity.source, as synced. */
object TrackSource {
    const val TIDAL = "tidal"
    const val QOBUZ = "qobuz"
    const val DEEZER = "deezer"
    const val APPLE = "apple"
    const val SUBSONIC = "subsonic"
    const val LOCAL = "local"
}
