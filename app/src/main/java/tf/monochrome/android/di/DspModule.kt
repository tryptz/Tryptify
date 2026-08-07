package tf.monochrome.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tf.monochrome.android.audio.dsp.DspEngineManager
import tf.monochrome.android.audio.dsp.MixBusProcessor
import tf.monochrome.android.audio.dsp.crossfeed.CrossfeedEffect
import tf.monochrome.android.audio.dsp.oxford.CompressorEffect
import tf.monochrome.android.audio.dsp.oxford.InflatorEffect
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DspModule {

    @Provides
    @Singleton
    fun provideMixBusProcessor(
        inflator: InflatorEffect,
        compressor: CompressorEffect,
        crossfeed: CrossfeedEffect,
    ): MixBusProcessor = MixBusProcessor(inflator, compressor, crossfeed)

    @Provides
    @Singleton
    fun provideDspEngineManager(processor: MixBusProcessor, preferences: PreferencesManager): DspEngineManager =
        DspEngineManager(processor, preferences)
}
