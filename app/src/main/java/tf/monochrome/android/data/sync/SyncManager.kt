package tf.monochrome.android.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import tf.monochrome.android.data.auth.AuthRepository
import tf.monochrome.android.data.db.dao.FavoriteDao
import tf.monochrome.android.data.db.dao.HistoryDao
import tf.monochrome.android.data.db.dao.PlaylistDao
import tf.monochrome.android.data.db.entity.FavoriteAlbumEntity
import tf.monochrome.android.data.db.entity.FavoriteArtistEntity
import tf.monochrome.android.data.db.entity.FavoriteTrackEntity
import tf.monochrome.android.data.db.entity.HistoryTrackEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages bidirectional sync between local Room DB and PocketBase cloud.
 * Mirrors syncManager.onAuthStateChanged() from the web reference.
 */
@Singleton
class SyncManager @Inject constructor(
    private val authRepository: AuthRepository,
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val playlistDao: PlaylistDao,
    private val pocketBaseClient: PocketBaseClient,
    private val json: Json
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isSyncing = false

    fun startSync() {
        scope.launch {
            authRepository.isLoggedIn.collectLatest { isLoggedIn ->
                if (isLoggedIn) {
                    syncNow()
                }
            }
        }
    }

    suspend fun syncNow() {
        val uid = authRepository.getUserId() ?: return
        if (isSyncing) return
        isSyncing = true

        try {
            val cloudData = pocketBaseClient.getUserData(uid) ?: return

            // Parse cloud data
            val cloudLibrary = safeParseObject(cloudData.library)
            val cloudHistory = safeParseArray(cloudData.history)

            // Get local data
            val localTracks = favoriteDao.getFavoriteTracksSnapshot()
            val localAlbums = favoriteDao.getFavoriteAlbumsSnapshot()
            val localArtists = favoriteDao.getFavoriteArtistsSnapshot()
            val localHistory = historyDao.getHistorySnapshot()

            // Merge local into cloud
            var needsCloudUpdate = false
            val mergedLibrary = cloudLibrary.toMutableMap()

            val cloudTracks = (mergedLibrary["tracks"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            val cloudAlbums = (mergedLibrary["albums"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            val cloudArtists = (mergedLibrary["artists"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()

            // Merge local favorites into cloud
            for (track in localTracks) {
                val key = track.id.toString()
                if (!cloudTracks.containsKey(key)) {
                    cloudTracks[key] = minifyTrack(track)
                    needsCloudUpdate = true
                }
            }
            for (album in localAlbums) {
                val key = album.id.toString()
                if (!cloudAlbums.containsKey(key)) {
                    cloudAlbums[key] = minifyAlbum(album)
                    needsCloudUpdate = true
                }
            }
            for (artist in localArtists) {
                val key = artist.id.toString()
                if (!cloudArtists.containsKey(key)) {
                    cloudArtists[key] = minifyArtist(artist)
                    needsCloudUpdate = true
                }
            }

            mergedLibrary["tracks"] = JsonObject(cloudTracks)
            mergedLibrary["albums"] = JsonObject(cloudAlbums)
            mergedLibrary["artists"] = JsonObject(cloudArtists)

            // Merge history
            val mergedHistoryList = mutableListOf<JsonElement>()
            val seenTimestamps = mutableSetOf<Long>()

            // Combine cloud + local history
            val allHistory = mutableListOf<Pair<Long, JsonElement>>()
            for (item in cloudHistory) {
                val ts = item.jsonObject["timestamp"]?.jsonPrimitive?.longOrNull
                    ?: item.jsonObject["playedAt"]?.jsonPrimitive?.longOrNull ?: 0L
                allHistory.add(ts to item)
            }
            for (track in localHistory) {
                allHistory.add(track.playedAt to buildJsonObject {
                    put("id", JsonPrimitive(track.id))
                    put("title", JsonPrimitive(track.title))
                    put("duration", JsonPrimitive(track.duration))
                    put("artistName", JsonPrimitive(track.artistName))
                    put("albumTitle", JsonPrimitive(track.albumTitle ?: ""))
                    put("albumCover", JsonPrimitive(track.albumCover ?: ""))
                    put("timestamp", JsonPrimitive(track.playedAt))
                })
            }

            allHistory.sortByDescending { it.first }
            for ((ts, item) in allHistory) {
                if (ts > 0 && !seenTimestamps.contains(ts)) {
                    seenTimestamps.add(ts)
                    mergedHistoryList.add(item)
                }
                if (mergedHistoryList.size >= 100) break
            }

            if (mergedHistoryList.size != cloudHistory.size) {
                needsCloudUpdate = true
            }

            // Push merged data back to cloud if changed
            if (needsCloudUpdate) {
                pocketBaseClient.updateUserField(uid, "library", json.encodeToString(JsonObject.serializer(), JsonObject(mergedLibrary)))
                pocketBaseClient.updateUserField(uid, "history", json.encodeToString(JsonArray.serializer(), JsonArray(mergedHistoryList)))
            }

            // Import cloud data into local DB
            importCloudToLocal(cloudTracks, cloudAlbums, cloudArtists, cloudHistory)
        } catch (_: Exception) {
        } finally {
            isSyncing = false
        }
    }

    private suspend fun importCloudToLocal(
        cloudTracks: Map<String, JsonElement>,
        cloudAlbums: Map<String, JsonElement>,
        cloudArtists: Map<String, JsonElement>,
        cloudHistory: List<JsonElement>
    ) {
        // Import cloud favorite tracks into local DB
        for ((_, trackJson) in cloudTracks) {
            try {
                val obj = trackJson.jsonObject
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                if (!favoriteDao.isFavoriteTrack(id)) {
                    favoriteDao.insertFavoriteTrack(FavoriteTrackEntity(
                        id = id,
                        title = obj["title"]?.jsonPrimitive?.content ?: "",
                        duration = obj["duration"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        artistName = obj["artist"]?.let { artist ->
                            if (artist is JsonObject) artist["name"]?.jsonPrimitive?.content else artist.jsonPrimitive.content
                        } ?: "",
                        albumId = obj["album"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull,
                        albumTitle = obj["album"]?.jsonObject?.get("title")?.jsonPrimitive?.content,
                        albumCover = obj["album"]?.jsonObject?.get("cover")?.jsonPrimitive?.content,
                        explicit = obj["explicit"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                        addedAt = obj["addedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                    ))
                }
            } catch (_: Exception) {
            }
        }

        // Import cloud favorite albums
        for ((_, albumJson) in cloudAlbums) {
            try {
                val obj = albumJson.jsonObject
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                if (!favoriteDao.isFavoriteAlbum(id)) {
                    favoriteDao.insertFavoriteAlbum(FavoriteAlbumEntity(
                        id = id,
                        title = obj["title"]?.jsonPrimitive?.content ?: "",
                        artistName = obj["artist"]?.let { artist ->
                            if (artist is JsonObject) artist["name"]?.jsonPrimitive?.content else artist.jsonPrimitive.content
                        } ?: "",
                        cover = obj["cover"]?.jsonPrimitive?.content,
                        numberOfTracks = obj["numberOfTracks"]?.jsonPrimitive?.content?.toIntOrNull(),
                        releaseDate = obj["releaseDate"]?.jsonPrimitive?.content,
                        addedAt = obj["addedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                    ))
                }
            } catch (_: Exception) {
            }
        }

        // Import cloud favorite artists
        for ((_, artistJson) in cloudArtists) {
            try {
                val obj = artistJson.jsonObject
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                if (!favoriteDao.isFavoriteArtist(id)) {
                    favoriteDao.insertFavoriteArtist(FavoriteArtistEntity(
                        id = id,
                        name = obj["name"]?.jsonPrimitive?.content ?: "",
                        picture = obj["picture"]?.jsonPrimitive?.content,
                        addedAt = obj["addedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                    ))
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun minifyTrack(track: FavoriteTrackEntity): JsonElement = buildJsonObject {
        put("id", JsonPrimitive(track.id))
        put("addedAt", JsonPrimitive(track.addedAt))
        put("title", JsonPrimitive(track.title))
        put("duration", JsonPrimitive(track.duration))
        put("explicit", JsonPrimitive(track.explicit))
        if (track.artistName.isNotBlank()) {
            put("artist", buildJsonObject {
                track.artistId?.let { put("id", JsonPrimitive(it)) }
                put("name", JsonPrimitive(track.artistName))
            })
        }
        if (track.albumId != null) {
            put("album", buildJsonObject {
                put("id", JsonPrimitive(track.albumId))
                track.albumTitle?.let { put("title", JsonPrimitive(it)) }
                track.albumCover?.let { put("cover", JsonPrimitive(it)) }
            })
        }
    }

    private fun minifyAlbum(album: FavoriteAlbumEntity): JsonElement = buildJsonObject {
        put("id", JsonPrimitive(album.id))
        put("addedAt", JsonPrimitive(album.addedAt))
        put("title", JsonPrimitive(album.title))
        album.cover?.let { put("cover", JsonPrimitive(it)) }
        album.releaseDate?.let { put("releaseDate", JsonPrimitive(it)) }
        if (album.artistName.isNotBlank()) {
            put("artist", buildJsonObject {
                album.artistId?.let { put("id", JsonPrimitive(it)) }
                put("name", JsonPrimitive(album.artistName))
            })
        }
        album.numberOfTracks?.let { put("numberOfTracks", JsonPrimitive(it)) }
    }

    private fun minifyArtist(artist: FavoriteArtistEntity): JsonElement = buildJsonObject {
        put("id", JsonPrimitive(artist.id))
        put("addedAt", JsonPrimitive(artist.addedAt))
        put("name", JsonPrimitive(artist.name))
        artist.picture?.let { put("picture", JsonPrimitive(it)) }
    }

    private fun safeParseObject(str: String): MutableMap<String, JsonElement> {
        return try {
            json.parseToJsonElement(str).jsonObject.toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun safeParseArray(str: String): List<JsonElement> {
        return try {
            json.parseToJsonElement(str).jsonArray.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
