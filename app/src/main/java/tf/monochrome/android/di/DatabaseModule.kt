package tf.monochrome.android.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tf.monochrome.android.data.db.MusicDatabase
import tf.monochrome.android.data.db.dao.DownloadDao
import tf.monochrome.android.data.db.dao.EqPresetDao
import tf.monochrome.android.data.db.dao.FavoriteDao
import tf.monochrome.android.data.db.dao.HistoryDao
import tf.monochrome.android.data.db.dao.MixPresetDao
import tf.monochrome.android.data.db.dao.PlayEventDao
import tf.monochrome.android.data.db.dao.PlaylistDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MusicDatabase {
        return Room.databaseBuilder(
            context,
            MusicDatabase::class.java,
            "monochrome_db"
        )
            .addMigrations(
                MusicDatabase.MIGRATION_8_9,
                MusicDatabase.MIGRATION_9_10,
                MusicDatabase.MIGRATION_10_11,
                MusicDatabase.MIGRATION_11_12,
                MusicDatabase.MIGRATION_12_13,
            )
            // Retained as a safety net for any version gap without an explicit
            // migration; the THX (8→9) and Atmos (9→10) upgrades migrate in
            // place above.
            .fallbackToDestructiveMigration(dropAllTables = true)
            // …and it says so when it fires. This drops every table the user
            // owns — playlists, favourites, history, presets — and it did it
            // silently, so the app came back looking like a fresh install with
            // no record anywhere of why. A dropped database is recoverable for
            // a signed-in listener (LibraryRestoreCoordinator pulls it back)
            // and unrecoverable for everyone else, and either way it is the
            // single most destructive thing this app does to its own data. It
            // does not get to be quiet about it.
            .addCallback(object : RoomDatabase.Callback() {
                override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                    Log.e(
                        "MusicDatabase",
                        "Destructive migration: every local table was dropped " +
                            "(schema mismatch or a version gap with no migration). " +
                            "Local library data is gone; a signed-in account will " +
                            "restore from the cloud on next launch.",
                    )
                }
            })
            .build()
    }

    @Provides
    fun provideFavoriteDao(db: MusicDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideHistoryDao(db: MusicDatabase): HistoryDao = db.historyDao()

    @Provides
    fun providePlayEventDao(db: MusicDatabase): PlayEventDao = db.playEventDao()

    @Provides
    fun providePlaylistDao(db: MusicDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideDownloadDao(db: MusicDatabase): DownloadDao = db.downloadDao()

    @Provides
    fun provideEqPresetDao(db: MusicDatabase): EqPresetDao = db.eqPresetDao()

    @Provides
    fun provideMixPresetDao(db: MusicDatabase): MixPresetDao = db.mixPresetDao()
}
