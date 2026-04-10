package tf.monochrome.android.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tf.monochrome.android.audio.dsp.DspEngineManager
import tf.monochrome.android.audio.dsp.MixBusProcessor
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DspModule {

    @Provides
    @Singleton
    fun provideMixBusProcessor(): MixBusProcessor = MixBusProcessor()

    @Provides
    @Singleton
    fun provideSpatialAudioProcessor(@ApplicationContext context: Context): tf.monochrome.android.audio.dsp.SpatialAudioProcessor = 
        tf.monochrome.android.audio.dsp.SpatialAudioProcessor(context)

    @Provides
    @Singleton
    fun provideDspEngineManager(processor: MixBusProcessor, preferences: PreferencesManager): DspEngineManager =
        DspEngineManager(processor, preferences)
}
