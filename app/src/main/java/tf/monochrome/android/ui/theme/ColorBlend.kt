package tf.monochrome.android.ui.theme

/**
 * How long the album-art colours take to cross over when the track changes.
 *
 * By default this is tied to "Blend Between Tracks" so the picture and the sound
 * move together. With a blend set, the outgoing track is handed to the tail
 * player and the queue advances at `duration - blend` — the same moment the UI
 * sees the new track — so a colour fade of exactly the blend length ends on the
 * last sample of the old one. Any other duration would either finish while the
 * previous track is still audible or leave the old colour hanging after it has
 * gone.
 *
 * At zero the tracks butt straight together and there is no window to spread a
 * fade over, so the colours get [GAPLESS_MS] of their own. That is cosmetic
 * rather than matched to anything: it exists because a hard cut to a new colour
 * is jarring next to audio that gave no seam at all.
 *
 * Settings › Appearance can override the whole thing. That is a deliberate
 * choice to unpick the match above — someone who wants the room to change colour
 * slowly under a gapless album, or instantly under a long blend, is asking for
 * the picture and the sound to disagree, and is entitled to. Which is why the
 * default is [MATCH_BLEND] rather than a number: untouched, the pairing holds.
 */
object ColorBlend {

    /** Fade used when tracks run straight into each other (blend = 0s). */
    const val GAPLESS_MS = 600

    /** The stored value meaning "follow Blend Between Tracks", as it always did. */
    const val MATCH_BLEND = -1

    /**
     * Top of the slider's travel. Eight seconds is already a slow dissolve
     * across a whole screen; past it the colour is still arriving when the next
     * track starts on anything short.
     */
    const val MAX_MS = 8_000

    /** Slider granularity, and so the values [millisFor] can be handed. */
    const val STEP_MS = 250

    /**
     * The colour-fade length in ms: the listener's own [overrideMs] if they set
     * one, otherwise derived from the "Blend Between Tracks" value.
     *
     * The derived path is not clamped: if someone sets a 12s blend, the audio
     * really does take 12s to cross over, and a colour that arrived earlier
     * would be describing a track you cannot hear yet.
     */
    fun millisFor(crossfadeSeconds: Int, overrideMs: Int = MATCH_BLEND): Int = when {
        overrideMs >= 0 -> overrideMs
        crossfadeSeconds <= 0 -> GAPLESS_MS
        else -> crossfadeSeconds * 1_000
    }

    /** Every position the slider can rest at, "match blend" first. */
    val steps: List<Int> = buildList {
        add(MATCH_BLEND)
        for (ms in 0..MAX_MS step STEP_MS) add(ms)
    }
}
