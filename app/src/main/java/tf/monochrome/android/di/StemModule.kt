// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tf.monochrome.android.audio.sampler.stems.DspStemSeparator
import tf.monochrome.android.audio.sampler.stems.StemSeparator
import javax.inject.Singleton

/**
 * Which stem backend the app uses.
 *
 * One line, and that is the point: the Stem Studio depends on the
 * [StemSeparator] interface, so swapping in a model-based backend — ONNX,
 * TFLite, NNAPI, or something remote — is a change here and nowhere else.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StemModule {

    @Binds
    @Singleton
    abstract fun bindStemSeparator(implementation: DspStemSeparator): StemSeparator
}
