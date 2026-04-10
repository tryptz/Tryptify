package tf.monochrome.android.data.collections.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {

    // ── Collections ─────────────────────────────────────────────────

    @Query("SELECT * FROM collections ORDER BY importedAt DESC")
    fun getAllCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE collectionId = :collectionId")
    suspend fun getCollection(collectionId: String): CollectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: CollectionEntity)

    @Query("DELETE FROM collections WHERE collectionId = :collectionId")
    suspend fun deleteCollection(collectionId: String)

    // ── Artists ──────────────────────────────────────────────────────

    @Query("SELECT * FROM collection_artists WHERE collectionId = :collectionId ORDER BY name")
    fun getArtistsByCollection(collectionId: String): Flow<List<CollectionArtistEntity>>

    @Query("SELECT * FROM collection_artists WHERE uuid = :uuid")
    suspend fun getArtist(uuid: String): CollectionArtistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtist(artist: CollectionArtistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtists(artists: List<CollectionArtistEntity>)

    // ── Albums ───────────────────────────────────────────────────────

    @Query("SELECT * FROM collection_albums WHERE collectionId = :collectionId ORDER BY title")
    fun getAlbumsByCollection(collectionId: String): Flow<List<CollectionAlbumEntity>>

    @Query("SELECT * FROM collection_albums WHERE uuid = :uuid")
    suspend fun getAlbum(uuid: String): CollectionAlbumEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbum(album: CollectionAlbumEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbums(albums: List<CollectionAlbumEntity>)

    // ── Tracks ───────────────────────────────────────────────────────

    @Query("SELECT * FROM collection_tracks WHERE collectionId = :collectionId ORDER BY albumUuid, volumeNumber, trackNumber")
    fun getTracksByCollection(collectionId: String): Flow<List<CollectionTrackEntity>>

    @Query("SELECT * FROM collection_tracks WHERE collectionId = :collectionId ORDER BY albumUuid, volumeNumber, trackNumber LIMIT :limit OFFSET :offset")
    suspend fun getTracksByCollectionPaginated(collectionId: String, limit: Int, offset: Int): List<CollectionTrackEntity>

    @Query("SELECT * FROM collection_tracks WHERE albumUuid = :albumUuid ORDER BY volumeNumber, trackNumber")
    fun getTracksByAlbum(albumUuid: String): Flow<List<CollectionTrackEntity>>

    @Query("SELECT * FROM collection_tracks WHERE uuid = :uuid")
    suspend fun getTrack(uuid: String): CollectionTrackEntity?

    @Query("SELECT * FROM collection_tracks WHERE isrc = :isrc LIMIT 1")
    suspend fun findTrackByIsrc(isrc: String): CollectionTrackEntity?

    @Query("SELECT * FROM collection_tracks WHERE title LIKE :query OR isrc LIKE :query LIMIT 500")
    fun searchTracks(query: String): Flow<List<CollectionTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: CollectionTrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<CollectionTrackEntity>)

    // ── Direct Links ─────────────────────────────────────────────────

    @Query("SELECT * FROM collection_direct_links WHERE trackUuid = :trackUuid ORDER BY quality DESC")
    suspend fun getDirectLinks(trackUuid: String): List<CollectionDirectLinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDirectLink(link: CollectionDirectLinkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDirectLinks(links: List<CollectionDirectLinkEntity>)

    // ── Junction Tables ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackArtistCrossRef(ref: CollectionTrackArtistCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackArtistCrossRefs(refs: List<CollectionTrackArtistCrossRef>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbumArtistCrossRef(ref: CollectionAlbumArtistCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbumArtistCrossRefs(refs: List<CollectionAlbumArtistCrossRef>)

    @Query("""
        SELECT ca.* FROM collection_artists ca
        INNER JOIN collection_track_artists cta ON ca.uuid = cta.artistUuid
        WHERE cta.trackUuid = :trackUuid
    """)
    suspend fun getArtistsForTrack(trackUuid: String): List<CollectionArtistEntity>

    @Query("""
        SELECT ca.* FROM collection_artists ca
        INNER JOIN collection_album_artists caa ON ca.uuid = caa.artistUuid
        WHERE caa.albumUuid = :albumUuid
    """)
    suspend fun getArtistsForAlbum(albumUuid: String): List<CollectionArtistEntity>

    // ── Cascade delete ───────────────────────────────────────────────

    @Transaction
    suspend fun deleteCollectionCascade(collectionId: String) {
        // Delete junction tables first
        deleteTrackArtistsByCollection(collectionId)
        deleteAlbumArtistsByCollection(collectionId)
        // Direct links cascade via FK
        deleteTracksByCollection(collectionId)
        deleteAlbumsByCollection(collectionId)
        deleteArtistsByCollection(collectionId)
        deleteCollection(collectionId)
    }

    @Query("DELETE FROM collection_tracks WHERE collectionId = :collectionId")
    suspend fun deleteTracksByCollection(collectionId: String)

    @Query("DELETE FROM collection_albums WHERE collectionId = :collectionId")
    suspend fun deleteAlbumsByCollection(collectionId: String)

    @Query("DELETE FROM collection_artists WHERE collectionId = :collectionId")
    suspend fun deleteArtistsByCollection(collectionId: String)

    @Query("""
        DELETE FROM collection_track_artists WHERE trackUuid IN
        (SELECT uuid FROM collection_tracks WHERE collectionId = :collectionId)
    """)
    suspend fun deleteTrackArtistsByCollection(collectionId: String)

    @Query("""
        DELETE FROM collection_album_artists WHERE albumUuid IN
        (SELECT uuid FROM collection_albums WHERE collectionId = :collectionId)
    """)
    suspend fun deleteAlbumArtistsByCollection(collectionId: String)

    // ── Stats ────────────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM collections")
    suspend fun getCollectionCount(): Int

    @Query("SELECT COUNT(*) FROM collection_tracks WHERE collectionId = :collectionId")
    suspend fun getTrackCountForCollection(collectionId: String): Int
}
