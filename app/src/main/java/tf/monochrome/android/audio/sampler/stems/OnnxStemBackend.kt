// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tf.monochrome.android.audio.sampler.SampleEdits
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Runs a downloaded ONNX separator on the CPU.
 *
 * This is the backend that makes an installed model mean something. Everything
 * around it already existed — the manager puts a verified file on disk,
 * [ChunkedSeparation] cuts a track into windows and puts it back together
 * without seams — and this fills the hole in the middle: hand one window to the
 * model, take the stems back out.
 *
 * ## The output order is the whole game
 *
 * The model returns a `(1, stems, 2, samples)` tensor and nothing in it says
 * which stem is which. HTDemucs emits `drums, bass, other, vocals` — not
 * alphabetical, not the order [Stem] declares. So the mapping comes from
 * [StemModel.outputOrder], recorded when the model was installed, and is
 * applied by index here. Guess it and separation still appears to work: every
 * stem is produced, every level is plausible, and the vocal file contains the
 * drums.
 *
 * ## Threading
 *
 * Inference is seconds per window and runs on [Dispatchers.Default]. It never
 * touches the audio thread — a separation is a background job, and the brief is
 * explicit that playback must not stall behind it.
 */
@Singleton
class OnnxStemBackend @Inject constructor(
    private val models: StemModelManager,
) : StemSeparationBackend {

    override val kind: BackendKind = BackendKind.CPU

    private var session: OrtSession? = null
    private var loadedFor: String? = null

    override fun status(): BackendStatus {
        val model = models.installed()
        return BackendStatus(
            kind = BackendKind.CPU,
            available = model != null,
            modelName = model?.name,
            modelVersion = model?.version,
            unavailableReason = if (model == null) "No model installed" else null,
        )
    }

    override fun availableStems(input: SampleEdits.Buffer): Set<Stem> =
        models.installed()?.stems.orEmpty()

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val model = models.installed() ?: error("No model installed")
            loadSession(model)
            Unit
        }
    }

    private fun loadSession(model: StemModel): OrtSession {
        val existing = session
        if (existing != null && loadedFor == model.sha256) return existing

        release()
        val file = models.modelFile(model)
        require(file.isFile) { "The installed model file is missing" }

        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            // Leave a core free. Pinning every thread makes the UI stutter for
            // the whole job, and the job is measured in minutes.
            val cores = Runtime.getRuntime().availableProcessors()
            setIntraOpNumThreads(maxOf(1, cores - 1))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val created = env.createSession(file.absolutePath, options)
        session = created
        loadedFor = model.sha256
        return created
    }

    override suspend fun separate(
        input: SampleEdits.Buffer,
        requested: Set<Stem>,
        options: SeparationOptions,
        onProgress: (StemProgress) -> Unit,
    ): Map<Stem, SampleEdits.Buffer> = withContext(Dispatchers.Default) {
        val model = models.installed() ?: return@withContext emptyMap()
        val wanted = requested intersect model.stems
        if (wanted.isEmpty() || input.frames == 0) return@withContext emptyMap()

        onProgress(StemProgress(0.02f, "Loading model"))
        val ortSession = loadSession(model)
        val env = OrtEnvironment.getEnvironment()

        val order = model.outputOrder
        val segment = model.segmentFrames
        val inputName = ortSession.inputNames.firstOrNull() ?: "mix"

        // Reused across windows. A 6-stem window is ~16 MB of output and a
        // track is hundreds of windows; allocating per window would spend the
        // whole job in the collector.
        val inputData = FloatArray(2 * segment)

        ChunkedSeparation.run(
            input = input,
            segmentFrames = segment,
            stems = wanted,
            overlap = options.overlap,
            onProgress = { fraction ->
                onProgress(StemProgress(0.02f + 0.96f * fraction, "Separating"))
            },
        ) { chunk ->
            coroutineContext.ensureActive()

            val left = chunk.left
            val right = chunk.right ?: chunk.left
            // Planar, not interleaved: the tensor is (1, 2, samples), so the
            // whole left channel comes first.
            System.arraycopy(left, 0, inputData, 0, segment)
            System.arraycopy(right, 0, inputData, segment, segment)

            val shape = longArrayOf(1, 2, segment.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape).use { tensor ->
                ortSession.run(mapOf(inputName to tensor)).use { results ->
                    val output = results[0] as OnnxTensor
                    val flat = output.floatBuffer

                    buildMap {
                        order.forEachIndexed { index, stem ->
                            if (stem !in wanted) return@forEachIndexed
                            val outL = FloatArray(segment)
                            val outR = FloatArray(segment)
                            // (stem, channel, sample) into a flat buffer.
                            val base = index * 2 * segment
                            flat.position(base)
                            flat.get(outL, 0, segment)
                            flat.position(base + segment)
                            flat.get(outR, 0, segment)
                            put(stem, SampleEdits.Buffer(outL, outR, chunk.sampleRate))
                        }
                    }
                }
            }
        }
    }

    override fun release() {
        runCatching { session?.close() }
            .onFailure { Log.w(TAG, "Closing the ONNX session failed", it) }
        session = null
        loadedFor = null
    }

    private companion object {
        const val TAG = "OnnxStemBackend"
    }
}
