// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.di

import android.content.Context
import android.os.StatFs
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import tf.monochrome.android.audio.sampler.stems.DspStemBackend
import tf.monochrome.android.audio.sampler.stems.DspStemSeparator
import tf.monochrome.android.audio.sampler.stems.KtorModelTransport
import tf.monochrome.android.audio.sampler.stems.StemModelManager
import tf.monochrome.android.audio.sampler.stems.StemSeparationBackend
import tf.monochrome.android.audio.sampler.stems.StemSeparator
import java.io.File
import javax.inject.Singleton

/**
 * Which stem backends the app has.
 *
 * A set rather than a single binding, because more than one can be present at
 * once and which one runs is decided at call time by
 * [tf.monochrome.android.audio.sampler.stems.StemBackendRegistry] from what is
 * actually available on the device — a model that has not been downloaded yet
 * is a runtime fact, not a compile-time one.
 *
 * Only the DSP backend is bound today. A CPU (ONNX Runtime) backend and a QNN
 * backend each add one line here and change nothing else: the Stem Studio
 * depends on the registry and the interface, never on a runtime. See
 * MODEL_CARD.md for why the neural backends are not here yet — the blocker is
 * weights licensing, not inference.
 */
@Module
@InstallIn(SingletonComponent::class)
object StemProvisionModule {

    /**
     * Where downloaded models live.
     *
     * `filesDir` rather than the cache: a cache directory is something Android
     * may delete under storage pressure, and silently losing a model the user
     * waited ten minutes to download is not a behaviour worth having.
     */
    @Provides
    @Singleton
    fun provideStemModelManager(
        @ApplicationContext context: Context,
        transport: KtorModelTransport,
    ): StemModelManager {
        val root = File(context.filesDir, "stem-models")
        return StemModelManager(
            root = root,
            transport = transport,
            freeBytes = { StatFs(root.parentFile?.path ?: context.filesDir.path).availableBytes },
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StemModule {

    /**
     * The legacy single-separator binding, kept because the Stem Studio still
     * resolves the DSP path directly for its description string.
     */
    @Binds
    @Singleton
    abstract fun bindStemSeparator(implementation: DspStemSeparator): StemSeparator

    @Binds
    @IntoSet
    abstract fun bindDspBackend(implementation: DspStemBackend): StemSeparationBackend
}
