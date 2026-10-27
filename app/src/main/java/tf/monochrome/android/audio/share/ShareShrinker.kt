// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.share

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.audio.wav.TryptifyExtractors

/**
 * The smaller copy of a song to share ([SongShrinker]), made with Media3
 * Transformer: audio only, Opus at [ShareSize.OPUS_BITRATE], 48 kHz, mono
 * kept mono and anything over stereo mixed down to stereo, in an MP4
 * container (`.m4a`), into the cache.
 *
 * The song is read with the app's own extractors ([TryptifyExtractors], so
 * a float WAV and Apple's ALAC read as they play) and decoded by the
 * phone's MediaCodec decoders: FLAC, WAV, MP3, AAC everywhere, ALAC where
 * the phone has a decoder for it. What the phone cannot read fails, cleanly.
 *
 * The MP4 is written by Media3's own muxer ([InAppMuxer]): Android's
 * MediaMuxer takes no Opus in MP4. Opus encoding needs Android 10 and an
 * `audio/opus` encoder; without them [available] is false and nothing is
 * tried.
 *
 * Transformer is driven from one Looper thread of its own, a [HandlerThread]
 * per copy (Media3's pattern: built with `setLooper`, started, polled and
 * cancelled on that looper; its listener is called there too), so nothing
 * of it touches the main thread.
 */
@Singleton
@OptIn(UnstableApi::class)
class ShareShrinker @Inject constructor(
    @ApplicationContext private val context: Context,
) : SongShrinker {

    override val available: Boolean by lazy { hasOpusEncoder() }

    override suspend fun shrink(source: String, onProgress: (Float) -> Unit): File? {
        if (!available) return null
        val dir = File(context.cacheDir, DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        sweep(dir)
        val out = File(dir, "${UUID.randomUUID()}.${ShareSize.SHRUNK_EXT}")
        val thread = HandlerThread("ShareShrinker").apply { start() }
        try {
            val done = withTimeoutOrNull(TIMEOUT_MS) { export(Handler(thread.looper), thread.looper, source, out, onProgress) }
            if (done == true && out.isFile && out.length() > 0) return out
            if (done == null) Log.w(TAG, "gave up after ${TIMEOUT_MS / 60_000} minutes")
            out.delete()
            return null
        } catch (e: CancellationException) {
            out.delete()
            throw e
        } finally {
            // Messages already posted (the cancel) still run before it stops.
            thread.quitSafely()
        }
    }

    /** One export of [source] into [out] on [looper]: true when it completed. */
    private suspend fun export(
        handler: Handler,
        looper: Looper,
        source: String,
        out: File,
        onProgress: (Float) -> Unit,
    ): Boolean = suspendCancellableCoroutine { cont ->
        // Touched only on the looper's thread.
        var transformer: Transformer? = null
        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                val t = transformer ?: return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                handler.postDelayed(this, POLL_MS)
            }
        }
        fun finish(ok: Boolean) {
            handler.removeCallbacks(poll)
            if (!ok) out.delete()
            if (cont.isActive) cont.resume(ok)
        }
        handler.post {
            try {
                val t = Transformer.Builder(context)
                    .setLooper(looper)
                    .setAudioMimeType(MimeTypes.AUDIO_OPUS)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedAudioEncoderSettings(
                                AudioEncoderSettings.Builder().setBitrate(ShareSize.OPUS_BITRATE).build(),
                            )
                            .build(),
                    )
                    .setMuxerFactory(InAppMuxer.Factory.Builder().build())
                    .setAssetLoaderFactory(
                        DefaultAssetLoaderFactory(
                            context,
                            DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build(),
                            Clock.DEFAULT,
                            DefaultMediaSourceFactory(context, TryptifyExtractors.factory),
                            DataSourceBitmapLoader(context),
                        ),
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) = finish(true)

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            Log.w(TAG, "could not make the copy: ${exportException.errorCodeName}: ${exportException.message}")
                            finish(false)
                        }
                    })
                    .build()
                transformer = t
                t.start(item(source), out.absolutePath)
                handler.post(poll)
            } catch (e: Exception) {
                Log.w(TAG, "could not start the copy: ${e.message}")
                finish(false)
            }
        }
        cont.invokeOnCancellation {
            handler.post {
                handler.removeCallbacks(poll)
                runCatching { transformer?.cancel() }
                out.delete()
            }
        }
    }

    /** [source], audio only, at 48 kHz, in mono or stereo. */
    private fun item(source: String): EditedMediaItem {
        val downmix = ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1))
            putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2))
            for (n in 3..MAX_CHANNELS) putChannelMixingMatrix(ChannelMixingMatrix(n, 2, ShareSize.stereoDownmix(n)))
        }
        val resample = SonicAudioProcessor().apply { setOutputSampleRateHz(ShareSize.SAMPLE_RATE) }
        return EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setRemoveVideo(true)
            .setEffects(Effects(listOf<AudioProcessor>(downmix, resample), emptyList()))
            .build()
    }

    /** Copies left by a run that died mid-way (the app killed during an upload). */
    private fun sweep(dir: File) {
        val old = System.currentTimeMillis() - STALE_MS
        dir.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
    }

    private fun hasOpusEncoder(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.AUDIO_OPUS, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "ShareShrinker"
        const val DIR = "share-shrink"
        const val POLL_MS = 500L
        const val MAX_CHANNELS = 8

        /** Long enough for a long hi-res song on a slow phone; a stuck export is given up. */
        const val TIMEOUT_MS = 15 * 60 * 1000L

        /** A copy this old is no upload's any more. */
        const val STALE_MS = 6 * 60 * 60 * 1000L
    }
}
