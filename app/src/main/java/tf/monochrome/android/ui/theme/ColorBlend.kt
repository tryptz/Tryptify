package tf.monochrome.android.ui.theme

/**
 * How long the album-art colours take to cross over when the track changes.
 *
 * Tied to "Blend Between Tracks" so the picture and the sound move together.
 * With a blend set, the outgoing track is handed to the tail player and the
 * queue advances at `duration - blend` — the same moment the UI sees the new
 * track — so a colour fade of exactly the blend length ends on the last sample
 * of the old one. Any other duration would either finish while the previous
 * track is still audible or leave the old colour hanging after it has gone.
 *
 * At zero the tracks butt straight together and there is no window to spread a
 * fade over, so the colours get [GAPLESS_MS] of their own. That is cosmetic
 * rather than matched to anything: it exists because a hard cut to a new colour
 * is jarring next to audio that gave no seam at all.
 */
object ColorBlend {

    /** Fade used when tracks run straight into each other (blend = 0s). */
    const val GAPLESS_MS = 600

    /**
     * The colour-fade length for a given "Blend Between Tracks" value, in ms.
     *
     * Not clamped: if someone sets a 12s blend, the audio really does take 12s
     * to cross over, and a colour that arrived earlier would be describing a
     * track you cannot hear yet.
     */
    fun millisFor(crossfadeSeconds: Int): Int =
        if (crossfadeSeconds <= 0) GAPLESS_MS else crossfadeSeconds * 1_000
}
