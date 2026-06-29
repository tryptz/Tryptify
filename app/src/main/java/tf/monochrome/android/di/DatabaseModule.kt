package tf.monochrome.android.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tf.monochrome.android.data.analysis.AudioFeatureDao
import tf.monochrome.android.data.analysis.AudioFeatureDatabase
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
        ).fallbackToDestructiveMigration().build()
    }

    // Separate DB for measured audio features — kept apart from MusicDatabase
    // so its schema can evolve without that DB's destructive migration wiping
    // the user's library.
    @Provides
    @Singleton
    fun provideAudioFeatureDatabase(@ApplicationContext context: Context): AudioFeatureDatabase {
        return Room.databaseBuilder(
            context,
            AudioFeatureDatabase::class.java,
            "audio_features_db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideAudioFeatureDao(db: AudioFeatureDatabase): AudioFeatureDao = db.audioFeatureDao()

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
