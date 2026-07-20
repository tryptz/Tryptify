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
}
