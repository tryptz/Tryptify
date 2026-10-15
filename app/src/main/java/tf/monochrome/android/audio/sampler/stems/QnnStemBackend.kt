// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import tf.monochrome.android.BuildConfig
import tf.monochrome.android.audio.sampler.SampleEdits

/**
 * The installed ONNX model, run on a Snapdragon's NPU ([QnnNpuStemBackend]) or
 * GPU ([QnnGpuStemBackend]) through ONNX Runtime's QNN execution provider.
 *
 * The same model file the CPU runs, not a separate download: the provider
 * takes the parts of the graph the hardware can run and leaves the rest to the
 * CPU inside the same session. Qualcomm's runtime libraries ship in the APK
 * (see `app/build.gradle.kts`, which keeps only the Hexagon generations a
 * current Snapdragon has).
 *
 * ## Whether it works is only known by trying
 *
 * The provider compiles the model for the hardware when the session opens,
 * and a model can fail to compile — an op the NPU lacks, a shape it will not
 * take. A failure is remembered against that model and that build of the app
 * ([QnnFailures]) and the backend reports itself unavailable after it, so the
 * next song goes straight to the next backend instead of spending another
 * minute failing. An app update tries again.
 *
 * ## The NPU's compile is cached
 *
 * Compiling a separator for the Hexagon NPU takes long enough to notice, so
 * the compiled graph is saved next to nothing else ([cacheDir]) the first time
 * and loaded from there after. It is tied to the model's hash and the app's
 * version, and one that will not load is deleted and rebuilt.
 */
abstract class QnnStemBackend(
    models: StemModelManager,
    protected val context: Context,
    private val unit: Accelerator,
) : OnnxStemBackend(models) {

    /** Which part of the chip, and the QNN library that drives it. */
    enum class Accelerator(val kind: BackendKind, val library: String) {
        NPU(BackendKind.QNN, "libQnnHtp.so"),
        GPU(BackendKind.GPU, "libQnnGpu.so"),
    }

    override val kind: BackendKind get() = unit.kind

    private val failures = QnnFailures(context)
    private val nativeDir: String get() = context.applicationInfo.nativeLibraryDir

    override fun unavailableReason(model: StemModel): String? = when {
        model.format != ModelFormat.ONNX -> "The installed model is not an ONNX model"
        !isSnapdragon() -> "Not a Snapdragon phone"
        // Also catches a build whose native libraries were not extracted: the
        // NPU's helper loads its half from a file, never from inside the APK.
        !File(nativeDir, unit.library).isFile -> "This build does not include the ${kind.label} libraries"
        else -> failures.reasonFor(unit, model)
    }

    override fun openSession(env: OrtEnvironment, model: StemModel, file: File): OrtSession = try {
        open(env, model, file)
    } catch (failure: Throwable) {
        failures.record(unit, model, failure)
        throw failure
    }

    override suspend fun separate(
        input: SampleEdits.Buffer,
        requested: Set<Stem>,
        options: SeparationOptions,
        onProgress: (StemProgress) -> Unit,
    ): Map<Stem, SampleEdits.Buffer> = try {
        super.separate(input, requested, options, onProgress)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        // A model that compiled can still fail to run. Remember that too.
        models.installed()?.let { failures.record(unit, it, failure) }
        throw failure
    }

    private fun open(env: OrtEnvironment, model: StemModel, file: File): OrtSession {
        val provider = HashMap<String, String>()
        provider["backend_path"] = unit.library
        if (unit == Accelerator.GPU) {
            return env.createSession(file.absolutePath, baseOptions().apply { addQnn(provider) })
        }

        // The NPU half of QNN runs on the Hexagon DSP, which loads its library
        // from a directory it is told about — the app's own first.
        Os.setenv("ADSP_LIBRARY_PATH", "$nativeDir;$SYSTEM_DSP_PATHS", true)
        // A job measured in minutes: the clock the NPU can hold, not its burst.
        provider["htp_performance_mode"] = "sustained_high_performance"
        // The installed model is float; the NPU runs it at half precision.
        provider["enable_htp_fp16_precision"] = "1"
        // A slower compile for a faster graph, paid once thanks to the cache.
        provider["htp_graph_finalization_optimization_mode"] = "3"

        val cached = cacheFile(model)
        if (cached.isFile) {
            try {
                return env.createSession(cached.absolutePath, baseOptions().apply { addQnn(provider) })
            } catch (stale: Throwable) {
                Log.w(TAG, "Compiled NPU graph would not load; rebuilding it", stale)
                cached.delete()
            }
        }
        cacheDir(context).listFiles()?.forEach { it.delete() }
        cacheDir(context).mkdirs()
        val options = baseOptions().apply {
            addQnn(provider)
            addConfigEntry("ep.context_enable", "1")
            addConfigEntry("ep.context_file_path", cached.absolutePath)
            addConfigEntry("ep.context_embed_mode", "1")
        }
        return env.createSession(file.absolutePath, options)
    }

    private fun cacheFile(model: StemModel): File =
        File(cacheDir(context), "${model.sha256.take(16)}-${BuildConfig.VERSION_CODE}.onnx")

    private fun isSnapdragon(): Boolean {
        if (Build.SUPPORTED_64_BIT_ABIS.none { it == "arm64-v8a" }) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val maker = Build.SOC_MANUFACTURER
            if (maker.equals("QTI", ignoreCase = true) || maker.contains("qualcomm", ignoreCase = true)) return true
        }
        return Build.HARDWARE.contains("qcom", ignoreCase = true)
    }

    companion object {
        private const val TAG = "QnnStemBackend"

        /** Where a Snapdragon keeps its own DSP libraries, after the app's. */
        private const val SYSTEM_DSP_PATHS = "/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp"

        /** The NPU's compiled graphs. Hundreds of megabytes; [clear] when the model goes. */
        fun cacheDir(context: Context): File = File(context.filesDir, "stem_npu_cache")

        /** Forget the compiled graphs and the remembered failures, for a model being removed. */
        fun clear(context: Context) {
            cacheDir(context).deleteRecursively()
            QnnFailures(context).clear()
        }
    }
}

@Singleton
class QnnNpuStemBackend @Inject constructor(
    models: StemModelManager,
    @ApplicationContext context: Context,
) : QnnStemBackend(models, context, Accelerator.NPU)

@Singleton
class QnnGpuStemBackend @Inject constructor(
    models: StemModelManager,
    @ApplicationContext context: Context,
) : QnnStemBackend(models, context, Accelerator.GPU)

/**
 * Which accelerator could not run which model, kept across launches so a
 * failure costs one attempt and not one per song.
 */
internal class QnnFailures(context: Context) {

    private val prefs = context.getSharedPreferences("stem_accelerators", Context.MODE_PRIVATE)

    fun reasonFor(unit: QnnStemBackend.Accelerator, model: StemModel): String? = prefs.getString(key(unit, model), null)

    fun record(unit: QnnStemBackend.Accelerator, model: StemModel, failure: Throwable) {
        val reason = "Could not run on the ${unit.kind.label}: " +
            (failure.message?.lineSequence()?.firstOrNull()?.take(160) ?: failure.javaClass.simpleName)
        Log.w("QnnStemBackend", reason, failure)
        prefs.edit().putString(key(unit, model), reason).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun key(unit: QnnStemBackend.Accelerator, model: StemModel) =
        "${unit.name}:${model.sha256}:${BuildConfig.VERSION_CODE}"
}
