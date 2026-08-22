// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tf.monochrome.android.audio.sampler.PcmDecoder
import tf.monochrome.android.audio.sampler.SampleEdits
import tf.monochrome.android.audio.sampler.stems.SeparationOptions
import tf.monochrome.android.audio.sampler.stems.Stem
import tf.monochrome.android.audio.sampler.stems.StemBackendRegistry
import tf.monochrome.android.audio.sampler.stems.StemQuality

data class StepManiaConversionProgress(
    val fraction: Float,
    val stage: String,
)

data class StepManiaConversionResult(
    val simfile: GeneratedSimfile,
    /** The backend actually used, including an honest fallback to CPU or DSP. */
    val backendName: String,
)

/**
 * End-to-end, offline MP3/FLAC to StepMania conversion.
 *
 * The existing decoder and stem registry stay authoritative. This service does
 * not create a second audio engine and never enters a playback callback.
 */
@Singleton
class StepManiaConversionService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stemBackends: StemBackendRegistry,
) {
    private val generator = StepManiaMapGenerator()

    suspend fun convert(
        source: Uri,
        destination: Uri,
        request: StepManiaRequest,
        quality: StemQuality = StemQuality.BALANCED,
        onProgress: (StepManiaConversionProgress) -> Unit = {},
    ): Result<StepManiaConversionResult> = try {
        val decoded = withContext(Dispatchers.Default) {
            onProgress(StepManiaConversionProgress(0f, "Decoding audio"))
            PcmDecoder.decode(context, source, MAX_DECODED_FRAMES)
        }
        val mix = SampleEdits.Buffer(decoded.left, decoded.right, decoded.sampleRate)
        val backend = stemBackends.active()
            ?: throw IllegalStateException("No stem separation backend is available")
        val status = backend.status()

        backend.initialize().getOrThrow()
        val separated = withContext(Dispatchers.Default) {
            backend.separate(
                input = mix,
                requested = setOf(Stem.DRUMS),
                options = SeparationOptions(quality = quality),
            ) { progress ->
                onProgress(
                    StepManiaConversionProgress(
                        fraction = 0.08f + progress.fraction.coerceIn(0f, 1f) * 0.78f,
                        stage = progress.stage,
                    ),
                )
            }
        }

        onProgress(StepManiaConversionProgress(0.88f, "Generating charts"))
        val generated = withContext(Dispatchers.Default) {
            generator.generate(mix, separated, request)
        }
        withContext(Dispatchers.IO) {
            onProgress(StepManiaConversionProgress(0.97f, "Writing simfile"))
            val output = context.contentResolver.openOutputStream(destination, "wt")
                ?: throw IOException("Could not open the StepMania destination")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(generated.ssc) }
        }
        onProgress(StepManiaConversionProgress(1f, "Complete"))
        Result.success(StepManiaConversionResult(generated, status.displayName))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    private companion object {
        // Ten minutes at 48 kHz. It prevents an accidentally selected podcast
        // from turning into multi-gigabyte float buffers during offline work.
        const val MAX_DECODED_FRAMES = 48_000 * 60 * 10
    }
}
