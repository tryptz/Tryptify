package tf.monochrome.android.debug

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug screen recorder: captures the display at its NATIVE panel resolution
 * and refresh rate (Display.mode.physicalWidth/Height + refreshRate, not the
 * app window size), muxed with the app's own audio captured digitally in
 * STEREO via AudioPlaybackCapture — no microphone, no room, no AGC, so
 * downmix A/B differences (Lt/Rt anti-phase, preamp, LFE filtering) survive
 * intact in the file.
 *
 * Video: H.264 surface encoder fed by a VirtualDisplay. Audio: 48 kHz stereo
 * PCM16 from the projection's playback capture (USAGE_MEDIA), AAC-encoded at
 * 256 kbps. Both tracks share the monotonic clock (surface frames carry
 * system-time PTS; audio PTS is anchored to nanoTime at the first read and
 * advanced by sample count), so MediaMuxer keeps them in sync.
 *
 * Caveats, deliberate for a debug tool: capture-audio requires RECORD_AUDIO
 * (falls back to video-only when not granted), and the USB exclusive bypass
 * path bypasses AudioFlinger so it cannot be captured — use the normal
 * output path when recording.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class DebugScreenRecorder(
    private val context: Context,
    private val projection: MediaProjection,
) {
    private var muxer: MediaMuxer? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    private val running = AtomicBoolean(false)
    private val muxerLock = java.lang.Object()
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var trackCount = 0

    private var videoThread: Thread? = null
    private var audioPumpThread: Thread? = null
    private var audioDrainThread: Thread? = null

    /** Native mode of the default display: width, height, fps. */
    private fun displayMode(): Triple<Int, Int, Int> {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display.mode
        // Encoders want even dimensions.
        val w = mode.physicalWidth and -2
        val h = mode.physicalHeight and -2
        val fps = mode.refreshRate.toInt().coerceAtLeast(30)
        return Triple(w, h, fps)
    }

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun start(fd: FileDescriptor, captureAudio: Boolean) {
        check(running.compareAndSet(false, true)) { "already recording" }
        val (w, h, fps) = displayMode()
        Log.i(TAG, "debug capture ${w}x$h@$fps audio=$captureAudio")

        muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        trackCount = if (captureAudio) 2 else 1

        // ── Video: surface H.264 at native mode ─────────────────────────
        val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            // ~0.08 bpp·fps, capped — plenty for UI content at native res.
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * fps * 0.08).toInt().coerceAtMost(60_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        vCodec.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = vCodec.createInputSurface()
        vCodec.start()
        videoCodec = vCodec

        virtualDisplay = projection.createVirtualDisplay(
            "tryptify-debug-recorder", w, h,
            context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null,
        )

        videoThread = Thread({ drainLoop(vCodec, isVideo = true) }, "dbgrec-video").also { it.start() }

        // ── Audio: playback capture (this app's media out) → AAC stereo ─
        if (captureAudio) {
            val capConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            val rec = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(capConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
                    ) * 4
                )
                .build()
            audioRecord = rec

            val aFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2,
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
            }
            val aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            aCodec.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aCodec.start()
            audioCodec = aCodec

            rec.startRecording()
            audioPumpThread = Thread({ audioPumpLoop(rec, aCodec) }, "dbgrec-apump").also { it.start() }
            audioDrainThread = Thread({ drainLoop(aCodec, isVideo = false) }, "dbgrec-adrain").also { it.start() }
        }
    }

    /** PCM from the capture into the AAC encoder, PTS anchored to nanoTime. */
    private fun audioPumpLoop(rec: AudioRecord, codec: MediaCodec) {
        val bytesPerFrame = 4 // stereo PCM16
        var anchorUs = -1L
        var framesRead = 0L
        while (true) {
            val stop = !running.get()
            val idx = try {
                codec.dequeueInputBuffer(10_000)
            } catch (e: IllegalStateException) {
                return
            }
            if (idx < 0) continue
            val buf = codec.getInputBuffer(idx) ?: continue
            if (stop) {
                codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            buf.clear()
            val n = rec.read(buf, buf.remaining())
            if (n <= 0) {
                codec.queueInputBuffer(idx, 0, 0, 0, 0)
                continue
            }
            if (anchorUs < 0) anchorUs = System.nanoTime() / 1000
            val ptsUs = anchorUs + framesRead * 1_000_000L / SAMPLE_RATE
            framesRead += n / bytesPerFrame
            codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
        }
    }

    /** Shared encoder→muxer drain; exits on EOS or codec teardown. */
    private fun drainLoop(codec: MediaCodec, isVideo: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                return // stopped underneath us during teardown
            }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxerLock) {
                    val m = muxer ?: return
                    val track = m.addTrack(codec.outputFormat)
                    if (isVideo) videoTrack = track else audioTrack = track
                    val added = (if (videoTrack >= 0) 1 else 0) + (if (audioTrack >= 0) 1 else 0)
                    if (added == trackCount) {
                        m.start()
                        muxerStarted = true
                        muxerLock.notifyAll()
                    }
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        synchronized(muxerLock) {
                            // Wait briefly for the other track's format so the
                            // first key frame isn't dropped on the floor.
                            var waited = 0L
                            while (!muxerStarted && running.get() && waited < 1000) {
                                muxerLock.wait(50); waited += 50
                            }
                            if (muxerStarted) {
                                muxer?.writeSampleData(
                                    if (isVideo) videoTrack else audioTrack, buf, info,
                                )
                            }
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // Video EOS via the surface; audio EOS via the pump thread.
        runCatching { virtualDisplay?.release() }
        runCatching { videoCodec?.signalEndOfInputStream() }
        runCatching { audioRecord?.stop() }
        videoThread?.join(3000)
        audioPumpThread?.join(3000)
        audioDrainThread?.join(3000)
        synchronized(muxerLock) {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            muxer = null
            muxerStarted = false
        }
        runCatching { videoCodec?.stop() }; runCatching { videoCodec?.release() }
        runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }
        runCatching { audioRecord?.release() }
        runCatching { projection.stop() }
        Log.i(TAG, "debug capture stopped")
    }

    companion object {
        private const val TAG = "DebugScreenRecorder"
        private const val SAMPLE_RATE = 48_000
    }
}
