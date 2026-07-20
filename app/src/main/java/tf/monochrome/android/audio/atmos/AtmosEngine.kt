package tf.monochrome.android.audio.atmos

/**
 * Kotlin entry point to the native Atmos decode path (libmonochrome_atmos.so).
 *
 * This is the JNI boundary onto the ported EMDF -> OAMD + JOC decode chain. It is
 * intentionally OFF the player's critical path: nothing in playback loads it yet,
 * so it cannot affect audio output. It exists so the decode path is callable from
 * the app and so the render/output wiring has a place to grow into once a core
 * E-AC-3 bed decoder is chosen.
 *
 * Unlike the always-linked DSP/USB libraries, the Atmos library is loaded lazily
 * and failure-tolerant: [ensureLoaded] returns false instead of throwing if the
 * .so is missing, so callers can degrade gracefully while the feature is
 * incomplete.
 */
object AtmosEngine {

    @Volatile
    private var loaded = false

    /** Loads libmonochrome_atmos.so once; returns whether native calls are usable. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (!loaded) {
            loaded = runCatching { System.loadLibrary("monochrome_atmos") }.isSuccess
        }
        return loaded
    }

    /** True when the native library links and its bed-layout self-check passes. */
    fun selfCheck(): Boolean = ensureLoaded() && nativeSelfCheck()

    /**
     * Walks an EMDF side-data buffer and returns the number of JOC objects the
     * frame carries (0 if it holds no EMDF/JOC payload, or the library is
     * unavailable). Exercises the full EMDF -> JOC decode path natively.
     */
    fun decodeEmdfObjectCount(emdf: ByteArray): Int =
        if (ensureLoaded()) nativeDecodeEmdfObjectCount(emdf) else 0

    private external fun nativeSelfCheck(): Boolean
    private external fun nativeDecodeEmdfObjectCount(emdf: ByteArray): Int
}
