package tf.monochrome.android.audio.atmos

/**
 * JNI bridge to the clean-room Atmos metadata/object native code (the
 * Cavern-ported EMDF / OAMD / JOC chain in cpp/atmos).
 *
 * The E-AC-3 core is decoded to bed PCM by Media3's
 * [androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer] (NextLib); this native
 * side does the object work. The sample tap uses [nativeParseAtmos] to find
 * Atmos frames and count their objects; the pipeline rebuilds and renders them.
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

    // ── The render pipeline ──────────────────────────────────────────────
    /** Creates the end-to-end render pipeline; returns a handle (0 on failure). */
    external fun nativePipelineCreate(sampleRate: Int, maxObjects: Int): Long

    /** Destroys a pipeline handle from [nativePipelineCreate]. */
    external fun nativePipelineDestroy(pipeline: Long)

    /**
     * Clears the pipeline's time history (latency-matching delay lines,
     * convolution tails, DRC envelope) after a seek/flush, so no pre-seek audio
     * leaks into the first frames. Allocation-free; audio-thread-safe.
     */
    external fun nativePipelineFlush(pipeline: Long)

    /**
     * Installs a runtime HRTF from raw .sofa file bytes, replacing the baked
     * MIT KEMAR default. Parses + onset-aligns off the audio thread and swaps it
     * in double-buffered, so it is safe to call while playing. Returns 1 on
     * success, 0 if the buffer is not a decodable SOFA (the baked HRTF stays).
     */
    external fun nativeLoadSofa(pipeline: Long, sofa: ByteArray): Int

    /** Reverts the pipeline to the baked default HRTF. */
    external fun nativeClearSofa(pipeline: Long)

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
        bassManagement: Boolean,
        crossoverHz: Int,
        drcMode: Int,
        dialogNormalization: Boolean,
    )

    /**
     * Renders one E-AC-3 frame's Atmos content to interleaved binaural stereo.
     *
     * @param frame the raw E-AC-3 access unit (carries JOC/OAMD side-data)
     * @param bedInterleaved channels*samples floats, interleaved, decoder order
     * @param stereoOut receives 2*samples floats when the pipeline produced
     *   output — the object render or its latency-matched fallback downmix
     *   (path switches are crossfaded inside the pipeline)
     * @return 1 if [stereoOut] was written, or -1 if the pipeline is inactive
     *   (passthrough / LoRo / LtRt — the caller folds down itself)
     */
    external fun nativeProcessFrame(
        pipeline: Long,
        frame: ByteArray,
        bedInterleaved: FloatArray,
        channels: Int,
        samples: Int,
        stereoOut: FloatArray,
    ): Int

    /**
     * Selects the loudspeaker layout for [nativeProcessFrameSpeakers]
     * (ChannelLayout.nativeId). Allocates — call it when (re)configuring, not
     * per frame. Returns the layout's speaker count (0 without a pipeline).
     */
    external fun nativeSetOutputLayout(pipeline: Long, layoutId: Int): Int

    /**
     * Renders one E-AC-3 frame's Atmos objects to the loudspeaker layout set by
     * [nativeSetOutputLayout]: interleaved, [outChannels] wide, speakers in
     * ChannelLayout.speakers() order (Android mask order). Frames without JOC
     * come out as the decoded bed mapped onto the layout, latency-aligned with
     * the object render and crossfaded at every switch.
     *
     * @param out receives outChannels*samples floats when the return is 1
     * @return 1 if [out] was written, -1 if inactive (passthrough, or
     *   [outChannels] doesn't match the configured layout)
     */
    external fun nativeProcessFrameSpeakers(
        pipeline: Long,
        frame: ByteArray,
        bedInterleaved: FloatArray,
        channels: Int,
        samples: Int,
        out: FloatArray,
        outChannels: Int,
    ): Int

    /**
     * How much of the virtual room's reflections and tail the headphone
     * render has, 0..1. (Objects and beds are panned onto the room's 15.1.12
     * speakers, and its speakers are what the ears hear.)
     */
    external fun nativeSetRoomAmount(pipeline: Long, room: Float)

    /**
     * Objects rendered in the last frame (the LFE included), or 0 when the
     * decoded bed played instead: no JOC in the stream, none yet after a seek,
     * or the bed mode.
     */
    external fun nativeLastObjectCount(pipeline: Long): Int

    /**
     * The last frame's objects: [OBJECT_METER_FIELDS] floats each into [out],
     * the room position (x 0..1 left to right, y 0..1 front to back, z -1..1
     * floor to ceiling), the frame's peak, and what it is (0 an object, 1 a
     * bed channel, 2 the LFE). Returns how many were written.
     */
    external fun nativeObjectMeter(pipeline: Long, out: FloatArray): Int

    /**
     * The last frame's Atmos metadata as the native engine saw it, for the
     * log: [container found, JOC valid, JOC objects, downmix configuration,
     * OAMD valid, OAMD objects, OAMD states, warp mode, frames held, LFE slot].
     */
    external fun nativeDebugInfo(pipeline: Long, out: IntArray): Int

    /**
     * Reads [frame]'s object metadata without rendering anything, for when the
     * object render is off: where the stream's objects are, into [out] as
     * [nativeObjectMeter] writes them, with a level of -1 (there is no audio
     * to take one from). An empty [frame] keeps the last one's. Returns the
     * object count, 0 for a stream with none.
     */
    external fun nativeTrackObjects(pipeline: Long, frame: ByteArray, out: FloatArray): Int

    /**
     * The virtual room's speakers (15.1.12): [ROOM_FIELDS] floats each into
     * [out], where the speaker stands (x 0..1 left to right, y 0..1 front to
     * back, z 0 at ear level to 1 at the ceiling) and 1 for the LFE. Returns
     * how many were written.
     */
    external fun nativeRoomLayout(out: FloatArray): Int

    /**
     * How hard each of the room's speakers was driven in the last frame, one
     * float a speaker into [out], in [nativeRoomLayout]'s order: the peak of
     * its feed when the room rendered the frame, else where the objects
     * would go on it; under zero when the objects are only watched
     * ([tracked]) and have no level. Returns the speaker count, 0 for none.
     */
    external fun nativeRoomMeter(pipeline: Long, tracked: Boolean, out: FloatArray): Int

    /**
     * A measured room out of a SOFA file of room impulse responses (BRIRs,
     * SRIRs, DRIRs), as a packed room ([tf.monochrome.android.domain.model.AtmosRoom]
     * reads one). Null when the file cannot be read or holds no room. Slow:
     * it parses the whole file. Never on the playback thread.
     */
    external fun nativePrepareRoomSofa(sofa: ByteArray): FloatArray?

    /**
     * A room worked out from its size in metres and the share of sound its
     * walls take up ([absorption], 0..1), as a packed room. Never on the
     * playback thread.
     */
    external fun nativeBuildBoxRoom(width: Float, depth: Float, height: Float, absorption: Float): FloatArray?

    /**
     * The room [pipeline]'s headphone render plays in: a packed room, or null
     * for the built-in one. Returns 1 when it was taken. Never on the
     * playback thread while it renders.
     */
    external fun nativeSetRoom(pipeline: Long, packed: FloatArray?): Int

    /**
     * The panning envelope ([tf.monochrome.android.domain.model.PanEnvelope]):
     * with [enabled], objects use less of the room's width and depth the
     * higher they are, on headphones and on loudspeakers alike.
     */
    external fun nativeSetPanEnvelope(
        pipeline: Long,
        enabled: Boolean,
        ceilingWidth: Float,
        ceilingDepth: Float,
        curve: Float,
    )

    /**
     * The listener's ears on top of the HRTF set
     * ([tf.monochrome.android.domain.model.EarTune]). It builds the room's
     * filters again, which takes a moment: never from the audio thread while
     * it renders.
     */
    external fun nativeSetEarTune(pipeline: Long, size: Float, freqHz: Float, gainDb: Float, q: Float)

    /**
     * For the ear tuner's graph: what a speaker at ([azDeg], [elDeg]) puts at
     * the nearer ear, in dB at each of [hz]. [out] gets the set as it is, then
     * the same with the tuning: twice [hz]'s length. From [pipeline]'s set, or
     * the built-in one for 0. Returns the points in each curve, 0 when refused.
     */
    external fun nativeEarCurve(
        pipeline: Long,
        size: Float,
        freqHz: Float,
        gainDb: Float,
        q: Float,
        azDeg: Float,
        elDeg: Float,
        hz: FloatArray,
        out: FloatArray,
    ): Int

    const val ROOM_FIELDS = 4
    const val ROOM_MAX_SPEAKERS = 32
    const val OBJECT_METER_FIELDS = 5

    /** An object's fifth meter field: what it is (AtmosPipeline::kMeterBed, kMeterLfe). */
    const val METER_KIND_BED = 1f
    const val METER_KIND_LFE = 2f

    /** [nativeDebugInfo]'s field count (ObjectEngine::kDebugFields) and its held-frames field. */
    const val DEBUG_FIELDS = 10
    const val DEBUG_HELD_FRAMES = 8
}
