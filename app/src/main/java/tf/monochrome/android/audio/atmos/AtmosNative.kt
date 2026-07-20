package tf.monochrome.android.audio.atmos

/**
 * JNI bridge to the clean-room Atmos metadata/object native code (the
 * Cavern-ported EMDF / OAMD / JOC chain in cpp/atmos).
 *
 * Under the Atmos renderer plan's Option B, the E-AC-3 core is decoded to bed
 * PCM by Media3's [androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer] (NextLib);
 * this native side does the object work. The sample tap uses [nativeParseAtmos]
 * to detect Atmos frames and read their object count; the JOC QMF upmix surface
 * is added as the AtmosAudioProcessor lands.
 */
object AtmosNative {
    @Volatile private var available: Boolean = false

    init {
        available = try {
            System.loadLibrary("monochrome_atmos_jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /** Whether the native Atmos library loaded (false = Atmos rendering disabled). */
    val isAvailable: Boolean get() = available

    /**
     * Parses a raw E-AC-3 frame for Atmos object metadata by walking the EMDF
     * container in its aux data.
     *
     * @param frame one complete E-AC-3 access unit (starting at the 0x0B77 syncword)
     * @return the OAMD object count (>= 1) if the frame carries Atmos side-data,
     *   or -1 for a plain non-Atmos frame / no decodable side-data
     */
    external fun nativeParseAtmos(frame: ByteArray): Int

    // ── Object reconstruction engine (P1) ────────────────────────────────
    /** Creates a native object-upmix engine; returns a handle (0 on failure). */
    external fun nativeEngineCreate(): Long

    /** Destroys a native engine handle from [nativeEngineCreate]. */
    external fun nativeEngineDestroy(engine: Long)

    /**
     * Upmixes one E-AC-3 frame's bed PCM into objects.
     *
     * @param engine handle from [nativeEngineCreate]
     * @param frame the raw E-AC-3 access unit (carries the JOC/OAMD side-data)
     * @param bedInterleaved channels*samples floats, interleaved, decoder order
     * @return object count (>= 1) or -1 if the frame has no JOC. Object PCM stays
     *   native for the HRTF render stage.
     */
    external fun nativeUpmixFrame(
        engine: Long,
        frame: ByteArray,
        bedInterleaved: FloatArray,
        channels: Int,
        samples: Int,
    ): Int

    // ── Full render pipeline (P1 upmix -> P2 binaural render) ─────────────
    /** Creates the end-to-end render pipeline; returns a handle (0 on failure). */
    external fun nativePipelineCreate(sampleRate: Int, maxObjects: Int): Long

    /** Destroys a pipeline handle from [nativePipelineCreate]. */
    external fun nativePipelineDestroy(pipeline: Long)

    /**
     * Pushes the user's renderer profile into the native pipeline.
     *
     * @param mode RendererMode ordinal (0 passthrough, 1 object render, 2 bed+HRTF)
     * @param downmix StereoDownmixMode ordinal (0 binaural, 1 Lo/Ro, 2 Lt/Rt)
     * @param binauralStrength 0 (dry) .. 1 (full HRTF)
     * @param heightVirtualization false collapses sources to the ear-level plane
     * @param lfeGainDb LFE trim in dB (applied in bed+HRTF mode)
     */
    external fun nativeSetRenderParams(
        pipeline: Long,
        mode: Int,
        downmix: Int,
        binauralStrength: Float,
        heightVirtualization: Boolean,
        lfeGainDb: Float,
    )

    /**
     * Renders one E-AC-3 frame's Atmos content to interleaved binaural stereo.
     *
     * @param frame the raw E-AC-3 access unit (carries JOC/OAMD side-data)
     * @param bedInterleaved channels*samples floats, interleaved, decoder order
     * @param stereoOut receives 2*samples floats when Atmos is rendered
     * @return 1 if rendered into [stereoOut], or -1 if the frame has no objects
     *   (the caller then passes the bed through unchanged)
     */
    external fun nativeProcessFrame(
        pipeline: Long,
        frame: ByteArray,
        bedInterleaved: FloatArray,
        channels: Int,
        samples: Int,
        stereoOut: FloatArray,
    ): Int
}
