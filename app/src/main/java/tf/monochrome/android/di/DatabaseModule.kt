package tf.monochrome.android.di

import android.content.Context
import androidx.room.Room
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
            .addMigrations(MusicDatabase.MIGRATION_8_9)
            // Retained as a safety net for any version gap without an explicit
            // migration; the THX upgrade (8→9) migrates in place above.
            .fallbackToDestructiveMigration(dropAllTables = true)
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
