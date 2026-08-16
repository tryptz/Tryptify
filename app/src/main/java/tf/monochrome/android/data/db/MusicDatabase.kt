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
import tf.monochrome.android.data.db.dao.PatternDao
import tf.monochrome.android.data.db.dao.PlaylistDao
import tf.monochrome.android.data.db.dao.SampleDao
import tf.monochrome.android.data.db.entity.CachedLyricsEntity
import tf.monochrome.android.data.db.entity.PatternChannelEntity
import tf.monochrome.android.data.db.entity.PatternEntity
import tf.monochrome.android.data.db.entity.PatternStepEntity
import tf.monochrome.android.data.db.entity.SampleEntity
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
        CollectionAlbumArtistCrossRef::class,
        // Sampler / pattern looper
        SampleEntity::class,
        PatternEntity::class,
        PatternChannelEntity::class,
        PatternStepEntity::class
    ],
    version = 16,
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
    abstract fun sampleDao(): SampleDao
    abstract fun patternDao(): PatternDao

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

        /**
         * v10 → v11: per-ear AutoEQ. Adds a nullable right-channel band list to
         * saved EQ presets; NULL means a mono preset whose left list drives
         * both ears, so every existing row stays valid as-is. A real migration
         * so saved presets survive the upgrade.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE eq_presets ADD COLUMN bandsRJson TEXT")
            }
        }

        /**
         * Composite index behind the library's main query, which is
         * `SELECT * FROM local_tracks ORDER BY albumArtist, album, discNumber,
         * trackNumber`. Only `albumArtist` and `album` were indexed, and never
         * together, so SQLite could not satisfy the ordering from an index and
         * built a temporary B-tree over the whole table on every emission —
         * which Room re-fires on every write to `local_tracks`, i.e. once per
         * 500-row batch during a scan.
         *
         * The index name MUST match what Room generates from the @Index
         * annotation on LocalTrackEntity (`index_<table>_<col>_<col>…`), or
         * Room's schema validation fails on open and — because the builder
         * carries fallbackToDestructiveMigration — silently drops the user's
         * library. Verified against Room's own generated CREATE INDEX output
         * for this table.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_local_tracks_albumArtist_album_discNumber_trackNumber` " +
                        "ON `local_tracks` (`albumArtist`, `album`, `discNumber`, `trackNumber`)"
                )
            }
        }

        /**
         * Folded title column + index behind the "play the on-device copy"
         * lookup, which shortlisted candidates with `title LIKE 'song%'`.
         *
         * That is a prefix pattern, so it looks indexable — but SQLite's LIKE
         * is case-insensitive by default and it only applies the LIKE
         * optimisation when the column carries NOCASE collation, which an
         * existing BINARY column cannot gain without rebuilding the table. So
         * every uncached resolution scanned all of local_tracks, on the path to
         * starting playback. A pre-folded column range-scans an ordinary index
         * instead.
         *
         * The backfill uses SQLite's `lower()`, which folds ASCII only, where
         * the scanner writes Kotlin's fuller `lowercase()`. That is not a
         * regression: the LIKE it replaces folded ASCII only too. A non-ASCII
         * title written by the backfill simply keeps matching exactly as well
         * as it did before, and gets the fuller key on the next rescan.
         *
         * As with 11→12, the column type and index name must match what Room
         * generates for the annotations on LocalTrackEntity, or validation
         * fails on open and fallbackToDestructiveMigration wipes the library.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `local_tracks` ADD COLUMN `titleSearchKey` TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_local_tracks_titleSearchKey` " +
                        "ON `local_tracks` (`titleSearchKey`)"
                )
                db.execSQL("UPDATE `local_tracks` SET `titleSearchKey` = lower(`title`)")
            }
        }

        /**
         * v13 → v14: the sampler and the pattern looper. Four new tables and
         * nothing touched that already existed, so an upgrade keeps the whole
         * library, every playlist and every saved preset.
         *
         * Every statement below has to be byte-for-byte what Room generates
         * from the annotations on `SamplerEntities.kt`, down to the index
         * names and the `ON UPDATE NO ACTION` clauses Room writes whether you
         * asked for them or not. Room validates the schema on open, and the
         * builder still carries `fallbackToDestructiveMigration` — so a
         * mismatch here does not fail loudly, it silently drops the user's
         * entire database. That is the same trap documented on 11→12.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sampler_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`nameKey` TEXT NOT NULL, " +
                        "`filePath` TEXT NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`sampleRate` INTEGER NOT NULL, " +
                        "`channelCount` INTEGER NOT NULL, " +
                        "`frameCount` INTEGER NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`tags` TEXT NOT NULL, " +
                        "`sourceTrackTitle` TEXT, " +
                        "`sourceArtist` TEXT, " +
                        "`sourceTimestampMs` INTEGER, " +
                        "`captureSource` TEXT, " +
                        "`bpm` REAL, " +
                        "`musicalKey` TEXT, " +
                        "`gain` REAL NOT NULL, " +
                        "`waveformPath` TEXT, " +
                        "`favorite` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sampler_samples_category` " +
                        "ON `sampler_samples` (`category`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sampler_samples_createdAt` " +
                        "ON `sampler_samples` (`createdAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sampler_samples_nameKey` " +
                        "ON `sampler_samples` (`nameKey`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sampler_patterns` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`bankSlot` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`lengthSteps` INTEGER NOT NULL, " +
                        "`stepsPerBeat` INTEGER NOT NULL, " +
                        "`beatsPerBar` INTEGER NOT NULL, " +
                        "`bpmOverride` REAL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sampler_patterns_bankSlot` " +
                        "ON `sampler_patterns` (`bankSlot`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sampler_pattern_channels` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`patternId` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`sampleId` INTEGER, " +
                        "`volume` REAL NOT NULL, " +
                        "`pan` REAL NOT NULL, " +
                        "`pitch` REAL NOT NULL, " +
                        "`sampleStart` REAL NOT NULL, " +
                        "`sampleEnd` REAL NOT NULL, " +
                        "`attackMs` REAL NOT NULL, " +
                        "`releaseMs` REAL NOT NULL, " +
                        "`filterHz` REAL NOT NULL, " +
                        "`reverse` INTEGER NOT NULL, " +
                        "`muted` INTEGER NOT NULL, " +
                        "`soloed` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`colorArgb` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`patternId`) REFERENCES `sampler_patterns`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sampler_pattern_channels_patternId` " +
                        "ON `sampler_pattern_channels` (`patternId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sampler_pattern_channels_sampleId` " +
                        "ON `sampler_pattern_channels` (`sampleId`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sampler_pattern_steps` (" +
                        "`channelId` INTEGER NOT NULL, " +
                        "`stepIndex` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`velocity` INTEGER NOT NULL, " +
                        "`pitch` INTEGER NOT NULL, " +
                        "`probability` INTEGER NOT NULL, " +
                        "`pan` INTEGER NOT NULL, " +
                        "`sampleStart` INTEGER NOT NULL, " +
                        "`locks` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`channelId`, `stepIndex`), " +
                        "FOREIGN KEY(`channelId`) REFERENCES `sampler_pattern_channels`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sampler_pattern_steps_channelId` " +
                        "ON `sampler_pattern_steps` (`channelId`)"
                )
            }
        }

        /**
         * v14 → v15: stem provenance. Two nullable columns on samples, saying
         * which stem a sample is and which sample it was cut from.
         *
         * Both nullable with no default, so every existing row is already
         * valid and the upgrade is two ALTERs and nothing else. `sourceSampleId`
         * is deliberately not a foreign key — deleting the source a stem came
         * from must not delete the stem, which by then is a finished sample of
         * its own.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sampler_samples` ADD COLUMN `stemType` TEXT")
                db.execSQL("ALTER TABLE `sampler_samples` ADD COLUMN `sourceSampleId` INTEGER")
            }
        }

        /**
         * 15 → 16: independent speed per channel, and per-step speed locks.
         *
         * All three columns are NOT NULL with defaults that mean "unchanged" —
         * 1.0x, not linked, no lock — so every pattern already in the library
         * keeps playing exactly as it did. A nullable column would have been
         * the easier migration and the wrong one: the engine would then have
         * to decide what null means on every trig.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `sampler_pattern_channels` " +
                        "ADD COLUMN `speed` REAL NOT NULL DEFAULT 1.0",
                )
                db.execSQL(
                    "ALTER TABLE `sampler_pattern_channels` " +
                        "ADD COLUMN `linked` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `sampler_pattern_steps` " +
                        "ADD COLUMN `speedCode` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }
}
