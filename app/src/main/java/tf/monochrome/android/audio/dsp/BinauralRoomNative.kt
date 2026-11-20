package tf.monochrome.android.audio.dsp

/**
 * What the mixer's Binaural Room says about itself, for its card's picture:
 * which of the spatial renderer's room speakers
 * ([tf.monochrome.android.audio.atmos.AtmosNative.nativeRoomLayout]'s order)
 * its stereo pair can stand at. Asked of the effect's own code
 * (`BinauralRoomProcessor::ringSpeakers`), never kept as a second table
 * here: the picture lights the speakers the sound comes from.
 */
object BinauralRoomNative {
    /** Speakers a side on the ring. */
    const val RING = 6

    init {
        DspNativeLoader.ensureLoaded()
    }

    /**
     * The ring: [speakers] gets [RING] indices for the left side then [RING]
     * for the right, nearest the centre first, and [degrees] each one's
     * angle from straight ahead. Returns how many a side, 0 when it failed.
     */
    @JvmStatic
    external fun nativeRing(speakers: IntArray, degrees: FloatArray): Int

    /** The ring, read once; null where the library or the layout is missing. */
    val ring: Ring? by lazy {
        runCatching {
            val speakers = IntArray(2 * RING)
            val degrees = FloatArray(RING)
            val n = nativeRing(speakers, degrees)
            if (n != RING) null else Ring(speakers.copyOfRange(0, n), speakers.copyOfRange(n, 2 * n), degrees)
        }.getOrNull()
    }

    class Ring(val left: IntArray, val right: IntArray, val degrees: FloatArray)
}
