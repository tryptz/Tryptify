package tf.monochrome.android.data.collections.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {

    // ── Collections ─────────────────────────────────────────────────

    @Query("SELECT * FROM collections ORDER BY importedAt DESC")
    fun getAllCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE collectionId = :collectionId")
    suspend fun getCollection(collectionId: String): CollectionEntity?

    // ── Artists ──────────────────────────────────────────────────────

    @Query("SELECT * FROM collection_artists WHERE collectionId = :collectionId ORDER BY name")
    fun getArtistsByCollection(collectionId: String): Flow<List<CollectionArtistEntity>>

    @Query("SELECT * FROM collection_artists WHERE uuid = :uuid")
    suspend fun getArtist(uuid: String): CollectionArtistEntity?

    // ── Albums ───────────────────────────────────────────────────────

    @Query("SELECT * FROM collection_albums WHERE collectionId = :collectionId ORDER BY title")
    fun getAlbumsByCollection(collectionId: String): Flow<List<CollectionAlbumEntity>>

    @Query("SELECT * FROM collection_albums WHERE uuid = :uuid")
    suspend fun getAlbum(uuid: String): CollectionAlbumEntity?

    // ── Tracks ───────────────────────────────────────────────────────

    @Query("SELECT * FROM collection_tracks WHERE collectionId = :collectionId ORDER BY albumUuid, volumeNumber, trackNumber")
    fun getTracksByCollection(collectionId: String): Flow<List<CollectionTrackEntity>>

    @Query("SELECT * FROM collection_tracks WHERE albumUuid = :albumUuid ORDER BY volumeNumber, trackNumber")
    fun getTracksByAlbum(albumUuid: String): Flow<List<CollectionTrackEntity>>

    @Query("SELECT * FROM collection_tracks WHERE isrc = :isrc LIMIT 1")
    suspend fun findTrackByIsrc(isrc: String): CollectionTrackEntity?

    @Query("SELECT * FROM collection_tracks WHERE title LIKE :query OR isrc LIKE :query LIMIT 500")
    fun searchTracks(query: String): Flow<List<CollectionTrackEntity>>

    // ── Direct Links ─────────────────────────────────────────────────

    @Query("SELECT * FROM collection_direct_links WHERE trackUuid = :trackUuid ORDER BY quality DESC")
    suspend fun getDirectLinks(trackUuid: String): List<CollectionDirectLinkEntity>

    // ── Junction Tables ──────────────────────────────────────────────

    @Query("""
        SELECT ca.* FROM collection_artists ca
        INNER JOIN collection_track_artists cta ON ca.uuid = cta.artistUuid
        WHERE cta.trackUuid = :trackUuid
    """)
    suspend fun getArtistsForTrack(trackUuid: String): List<CollectionArtistEntity>
}
