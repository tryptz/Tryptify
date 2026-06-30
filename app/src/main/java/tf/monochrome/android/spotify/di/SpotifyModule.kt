package tf.monochrome.android.spotify.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.player.PlaybackCoordinator
import tf.monochrome.android.player.QueueManager
import tf.monochrome.android.radio.RadioQueueManager
import tf.monochrome.android.radio.RadioSeedBuilder
import tf.monochrome.android.radio.TrackResolver
import tf.monochrome.android.spotify.api.SpotifyApi
import tf.monochrome.android.spotify.api.SpotifyApiClient
import tf.monochrome.android.spotify.auth.SpotifyAuthManager
import tf.monochrome.android.spotify.repository.SpotifyRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SpotifyModule {
    @Provides
    @Singleton
    fun provideSpotifyApi(
        httpClient: HttpClient,
        apiClient: SpotifyApiClient,
    ): SpotifyApi = SpotifyApi(httpClient, apiClient)

    @Provides
    @Singleton
    fun provideSpotifyRepository(
        api: SpotifyApi,
        authManager: SpotifyAuthManager,
    ): SpotifyRepository = SpotifyRepository(api, authManager)

    @Provides
    @Singleton
    fun provideRadioQueueManager(
        spotifyRepo: SpotifyRepository,
        seedBuilder: RadioSeedBuilder,
        trackResolver: TrackResolver,
        queueManager: QueueManager,
        playbackCoordinator: PlaybackCoordinator,
        libraryRepository: LibraryRepository,
    ): RadioQueueManager = RadioQueueManager(
        spotifyRepository = spotifyRepo,
        seedBuilder = seedBuilder,
        trackResolver = trackResolver,
        queueManager = queueManager,
        playbackCoordinator = playbackCoordinator,
        libraryRepository = libraryRepository,
    )
}
