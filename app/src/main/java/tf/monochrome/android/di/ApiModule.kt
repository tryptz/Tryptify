package tf.monochrome.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tf.monochrome.android.data.api.HeadphoneAutoEqApi
import tf.monochrome.android.data.api.SquiglinkApi
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    @Singleton
    fun provideHeadphoneAutoEqApi(): HeadphoneAutoEqApi {
        return HeadphoneAutoEqApi()
    }

    @Provides
    @Singleton
    fun provideSquiglinkApi(): SquiglinkApi {
        return SquiglinkApi()
    }
}
