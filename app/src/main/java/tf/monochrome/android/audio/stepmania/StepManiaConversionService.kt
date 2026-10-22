// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.api.ApiServer

data class StepManiaConversionProgress(
    val fraction: Float,
    val stage: String,
)

data class StepManiaConversionResult(
    val simfile: GeneratedSimfile,
    /** Which server separated the song: its model, its host, and whether on a GPU. */
    val backendName: String,
    /** How the server's part went, for a batch's estimates. */
    val remote: RemoteRun,
)

/**
 * MP3/FLAC to StepMania conversion, separated on a TryptMania Parser server.
 *
 * The phone separates nothing. A song goes to the servers [ChartMode] picks
 * ([RemoteStemAnalysis.planFor]) — in Fast mode a GPU, then a CPU server if
 * the GPU cannot be reached; in Slow mode a CPU server — and what comes back
 * is the drums' and each layer's envelope, which the chart generator reads
 * here. This service never enters a playback callback.
 */
@Singleton
class StepManiaConversionService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remoteStems: RemoteStemAnalysis,
) {
    private val generator = StepManiaMapGenerator()
    private val analyzer = StemRhythmAnalyzer()

    suspend fun convert(
        source: Uri,
        destination: Uri,
        request: StepManiaRequest,
        mode: ChartMode,
        onProgress: (StepManiaConversionProgress) -> Unit = {},
    ): Result<StepManiaConversionResult> = try {
        val plan = remoteStems.plan(mode)
        if (plan.isEmpty()) throw IOException(noServer(mode))
        val remote = separate(plan, source, onProgress)
        finish(remote, request, destination, onProgress)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    /** The song separated on the first server of [plan] that manages it. */
    private suspend fun separate(
        plan: List<ApiServer>,
        source: Uri,
        onProgress: (StepManiaConversionProgress) -> Unit,
    ): RemoteEnvelopes {
        var lastFailure: Throwable? = null
        for (server in plan) {
            val where = when (server.separatesOnGpu) {
                true -> "the GPU"
                false -> "the CPU server"
                null -> RemoteStemAnalysis.hostOf(server.url)
            }
            onProgress(StepManiaConversionProgress(0.02f, "Separating on $where"))
            try {
                val remote = remoteStems.analyze(server.url, source)
                Log.i(TAG, "Separated on ${remote.label}")
                remoteStems.learnDevice(server.url, remote.run.device)
                return remote
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // The host only: a server's address can carry a secret path.
                Log.w(TAG, "${server.url.substringAfter("://").substringBefore('/')} could not separate it; trying the next server", failure)
                lastFailure = failure
            }
        }
        throw IOException(
            "No separation server could chart it" + (lastFailure?.message?.let { ": $it" } ?: "."),
            lastFailure,
        )
    }

    /** Charts from the envelopes, written to [destination]. */
    private suspend fun finish(
        remote: RemoteEnvelopes,
        request: StepManiaRequest,
        destination: Uri,
        onProgress: (StepManiaConversionProgress) -> Unit,
    ): Result<StepManiaConversionResult> {
        onProgress(StepManiaConversionProgress(0.88f, "Generating charts"))
        val generated = withContext(Dispatchers.Default) {
            generator.generate(analyzer.analyze(remote.drums, remote.layers), request)
        }
        withContext(Dispatchers.IO) {
            onProgress(StepManiaConversionProgress(0.97f, "Writing simfile"))
            val output = context.contentResolver.openOutputStream(destination, "wt")
                ?: throw IOException("Could not open the StepMania destination")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(generated.ssc) }
        }
        onProgress(StepManiaConversionProgress(1f, "Complete"))
        return Result.success(StepManiaConversionResult(generated, remote.label, remote.run))
    }

    private companion object {
        const val TAG = "StepManiaConversion"

        fun noServer(mode: ChartMode): String = when (mode) {
            ChartMode.FAST ->
                "No separation server. Add one under Settings › Connections: a GPU server for Fast mode."
            ChartMode.SLOW ->
                "Slow mode needs a CPU separation server. Add one under Settings › Connections, or chart in Fast mode."
        }
    }
}
