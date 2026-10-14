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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.audio.sampler.PcmDecoder
import tf.monochrome.android.audio.sampler.SampleEdits
import tf.monochrome.android.audio.sampler.stems.SeparationOptions
import tf.monochrome.android.audio.sampler.stems.Stem
import tf.monochrome.android.audio.sampler.stems.StemBackendRegistry
import tf.monochrome.android.audio.sampler.stems.StemQuality
import tf.monochrome.android.audio.sampler.stems.StemSeparationBackend

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
    private val analyzer = StemRhythmAnalyzer()

    suspend fun convert(
        source: Uri,
        destination: Uri,
        request: StepManiaRequest,
        quality: StemQuality = StemQuality.BALANCED,
        onProgress: (StepManiaConversionProgress) -> Unit = {},
    ): Result<StepManiaConversionResult> = try {
        onProgress(StepManiaConversionProgress(0f, "Decoding audio"))
        val backend = stemBackends.active()
            ?: throw IllegalStateException("No stem separation backend is available")
        val status = backend.status()
        backend.initialize().getOrThrow()

        val envelope = withContext(Dispatchers.Default) {
            separateStreaming(source, quality, backend, onProgress)
        }

        onProgress(StepManiaConversionProgress(0.88f, "Generating charts"))
        val generated = withContext(Dispatchers.Default) {
            generator.generate(analyzer.analyze(envelope), request)
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

    /**
     * Decode, separate and reduce the song to its rhythm envelope, a piece at a
     * time.
     *
     * The whole song is never in memory. It used to be: decoded whole, then
     * separated whole into a drum stem as large again, plus whatever the
     * separator holds while it works — several hundred megabytes for a long
     * song, past what the app's heap allows. Now a decoder thread cuts the
     * song into [CHUNK_SECONDS] pieces ([SeparationChunker]) and hands them over
     * one at a time; each is separated, its drums folded into the envelope,
     * and dropped. The queue between the two holds one piece, so decoding
     * waits for separation instead of running ahead of it.
     *
     * Only the drum stem's core is kept from each piece. When the separator
     * gives back no drums, or a stem that does not line up with what it was
     * given, the piece's own mix stands in, as the analyzer did for the whole
     * song before.
     */
    private suspend fun separateStreaming(
        source: Uri,
        quality: StemQuality,
        backend: StemSeparationBackend,
        onProgress: (StepManiaConversionProgress) -> Unit,
    ): TransientEnvelopeBuilder = coroutineScope {
        val chunks = Channel<SeparationChunker.Chunk>(capacity = 1)
        var expectedFrames = 0L
        var envelope: TransientEnvelopeBuilder? = null

        launch(Dispatchers.IO) {
            try {
                var chunker: SeparationChunker? = null
                PcmDecoder.stream(
                    context = context,
                    uri = source,
                    maxFrames = MAX_DECODED_FRAMES,
                    isActive = { isActive },
                    onFormat = { format ->
                        expectedFrames = format.estimatedFrames
                        envelope = TransientEnvelopeBuilder(format.sampleRate)
                        chunker = SeparationChunker(
                            coreFrames = format.sampleRate * CHUNK_SECONDS,
                            contextFrames = format.sampleRate * CONTEXT_SECONDS,
                            stereo = format.channels >= 2,
                        ) { chunk -> chunks.trySendBlocking(chunk).getOrThrow() }
                    },
                ) { left, right, frames -> chunker?.add(left, right, frames) }
                chunker?.finish()
            } finally {
                chunks.close()
            }
        }

        chunks.consumeEach { chunk ->
            val sink = envelope ?: return@consumeEach
            val mix = SampleEdits.Buffer(chunk.left, chunk.right, sink.sampleRate)
            // How far through the song this piece starts and ends, for the bar.
            fun songFraction(within: Float): Float {
                if (expectedFrames <= 0L) return 0f
                val done = chunk.startFrame + chunk.coreFrames * within.coerceIn(0f, 1f)
                return (done / expectedFrames).coerceIn(0f, 1f)
            }
            val separated = backend.separate(
                input = mix,
                requested = setOf(Stem.DRUMS),
                options = SeparationOptions(quality = quality),
            ) { progress ->
                onProgress(
                    StepManiaConversionProgress(
                        fraction = 0.02f + songFraction(progress.fraction) * 0.84f,
                        stage = progress.stage,
                    ),
                )
            }
            val drums = separated[Stem.DRUMS]?.takeIf {
                it.sampleRate == mix.sampleRate && it.frames == mix.frames
            } ?: mix
            sink.add(drums.left, drums.right, chunk.coreOffset, chunk.coreOffset + chunk.coreFrames)
        }

        envelope ?: throw IOException("No audio could be decoded from that file")
    }

    private companion object {
        // Ten minutes at 48 kHz. Memory no longer depends on it — the song is
        // streamed — but separating a podcast picked by accident would still
        // take the better part of an hour.
        const val MAX_DECODED_FRAMES = 48_000L * 60 * 10

        /** How much of the song is separated at once. */
        const val CHUNK_SECONDS = 30

        /** What each piece hears either side of its own, so its edges come out clean. */
        const val CONTEXT_SECONDS = 3
    }
}
