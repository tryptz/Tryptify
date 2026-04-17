package tf.monochrome.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import tf.monochrome.android.data.db.dao.DownloadDao
import tf.monochrome.android.data.db.dao.EqPresetDao
import tf.monochrome.android.data.db.dao.FavoriteDao
import tf.monochrome.android.data.db.dao.HistoryDao
import tf.monochrome.android.data.db.dao.MixPresetDao
import tf.monochrome.android.data.db.dao.PlayEventDao
import tf.monochrome.android.data.db.dao.PlaylistDao
import tf.monochrome.android.data.db.entity.CachedLyricsEntity
import tf.monochrome.android.data.db.entity.DownloadedTrackEntity
import tf.monochrome.android.data.db.entity.EqPresetEntity
import tf.monochrome.android.data.db.entity.MixPresetEntity
import tf.monochrome.android.data.db.entity.FavoriteAlbumEntity
import tf.monochrome.android.data.db.entity.FavoriteArtistEntity
import tf.monochrome.android.data.db.entity.FavoriteTrackEntity
import tf.monochrome.android.data.db.entity.HistoryTrackEntity
import tf.monochrome.android.data.db.entity.PlayEventEntity
import tf.monochrome.android.data.db.entity.PlaylistTrackEntity
import tf.monochrome.android.data.db.entity.UserPlaylistEntity
import tf.monochrome.android.data.collections.db.CollectionAlbumArtistCrossRef
import tf.monochrome.android.data.collections.db.CollectionAlbumEntity
import tf.monochrome.android.data.collections.db.CollectionArtistEntity
import tf.monochrome.android.data.collections.db.CollectionDao
import tf.monochrome.android.data.collections.db.CollectionDirectLinkEntity
import tf.monochrome.android.data.collections.db.CollectionEntity
import tf.monochrome.android.data.collections.db.CollectionTrackArtistCrossRef
import tf.monochrome.android.data.collections.db.CollectionTrackEntity
import tf.monochrome.android.data.local.db.LocalAlbumEntity
import tf.monochrome.android.data.local.db.LocalArtistEntity
import tf.monochrome.android.data.local.db.LocalFolderEntity
import tf.monochrome.android.data.local.db.LocalGenreEntity
import tf.monochrome.android.data.local.db.LocalMediaDao
import tf.monochrome.android.data.local.db.LocalTrackEntity
import tf.monochrome.android.data.local.db.ScanStateEntity

@Database(
    entities = [
        // Core library
        FavoriteTrackEntity::class,
        FavoriteAlbumEntity::class,
        FavoriteArtistEntity::class,
        HistoryTrackEntity::class,
        PlayEventEntity::class,
        UserPlaylistEntity::class,
        PlaylistTrackEntity::class,
        DownloadedTrackEntity::class,
        CachedLyricsEntity::class,
        EqPresetEntity::class,
        MixPresetEntity::class,
        // Local media
        LocalTrackEntity::class,
        LocalAlbumEntity::class,
        LocalArtistEntity::class,
        LocalGenreEntity::class,
        LocalFolderEntity::class,
        ScanStateEntity::class,
        // Collections
        CollectionEntity::class,
        CollectionArtistEntity::class,
        CollectionAlbumEntity::class,
        CollectionTrackEntity::class,
        CollectionDirectLinkEntity::class,
        CollectionTrackArtistCrossRef::class,
        CollectionAlbumArtistCrossRef::class
    ],
    version = 7,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun playEventDao(): PlayEventDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadDao(): DownloadDao
    abstract fun eqPresetDao(): EqPresetDao
    abstract fun localMediaDao(): LocalMediaDao
    abstract fun collectionDao(): CollectionDao
    abstract fun mixPresetDao(): MixPresetDao
}
