package tf.monochrome.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 10,
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

    companion object {
        /**
         * v8 → v9: THX Spatial Audio designation. Adds the `version` +
         * `isThxSpatialAudio` columns to downloaded tracks and an
         * `isThxSpatialAudio` column to scanned local tracks, and backfills the
         * flag for existing rows whose title/album already names the release.
         * A real migration (not destructive fallback) so existing downloads and
         * library scans survive the upgrade.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloaded_tracks ADD COLUMN version TEXT")
                db.execSQL("ALTER TABLE downloaded_tracks ADD COLUMN isThxSpatialAudio INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE downloaded_tracks SET isThxSpatialAudio = 1 " +
                        "WHERE title LIKE '%THX Spatial Audio%'"
                )
                db.execSQL("ALTER TABLE local_tracks ADD COLUMN isThxSpatialAudio INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE local_tracks SET isThxSpatialAudio = 1 " +
                        "WHERE title LIKE '%THX Spatial Audio%' OR album LIKE '%THX Spatial Audio%'"
                )
            }
        }

        /**
         * v9 → v10: Dolby Atmos designation. Adds an `isDolbyAtmos` column to
         * scanned local tracks and backfills the flag for rows whose title/album
         * already names the release "Dolby Atmos". Local-only: sideloaded Atmos
         * files land in the library scan, so (unlike THX) there is no
         * `downloaded_tracks` column to add. A real migration so existing scans
         * survive the upgrade.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_tracks ADD COLUMN isDolbyAtmos INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE local_tracks SET isDolbyAtmos = 1 " +
                        "WHERE title LIKE '%Dolby Atmos%' OR album LIKE '%Dolby Atmos%'"
                )
            }
        }
    }
}
