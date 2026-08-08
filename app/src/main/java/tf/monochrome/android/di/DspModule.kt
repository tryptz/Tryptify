package tf.monochrome.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tf.monochrome.android.audio.dsp.DspEngineManager
import tf.monochrome.android.audio.dsp.MixBusProcessor
import tf.monochrome.android.audio.dsp.crossfeed.CrossfeedEffect
import tf.monochrome.android.audio.input.StereoInputEngine
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
        // The concrete engine rather than the LineInSource interface: it is the
        // only implementation, and taking it directly keeps the graph free of a
        // @Binds module for a one-member interface that exists purely to keep
        // Android audio APIs off the audio thread's dependency list.
        stereoInput: StereoInputEngine,
    ): MixBusProcessor = MixBusProcessor(inflator, compressor, crossfeed, stereoInput)

    @Provides
    @Singleton
    fun provideDspEngineManager(processor: MixBusProcessor, preferences: PreferencesManager): DspEngineManager =
        DspEngineManager(processor, preferences)
}
