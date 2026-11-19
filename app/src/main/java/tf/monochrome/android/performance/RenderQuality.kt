package tf.monochrome.android.performance

import kotlin.math.roundToInt

/**
 * Settings › System › Performance › Rendering quality: how much the GPU is
 * asked to draw for the glass and the lyrics' light, app-wide.
 *
 * The cost of a shader is the pixels it covers times the work on each. On a
 * phone's log (2026-11-18, a 165 Hz screen) the glass covered 7 to 10
 * megapixels a frame on the player, three to four times the screen, at 9 ms of
 * GPU a frame against a budget of 6; the app's own code took 1 to 2. So the
 * setting moves both factors, and nothing else:
 *
 *  - [lensDivisor]: the size the live lens under a pane (the screen bent and
 *    blurred behind the mini player, the tab bar, a panel, the play disc and
 *    the dock) is drawn at before it is scaled back up. Pixels go with its
 *    square: 2 is a quarter of them, 3 a ninth.
 *  - [shaftsScale]: the same for the god rays' shafts.
 *  - [maxGlassRings]: the most rings of texture reads a pixel of the glass
 *    slab takes (1, 2, 3 are 5, 9, 13 reads), over the Studio's own sliders,
 *    which stay what the listener set. [maxRayQuality] is the same for the
 *    rays' samples.
 *  - [dither]: whether the gradients' dither pass, a whole-screen shader, runs.
 *
 * The slab itself is not drawn smaller. Its layer holds the pane's rim and,
 * on the player, its icons, and its shader measures its bevel in pixels: at
 * half size both would go soft, which would be a third look. Fewer reads a
 * pixel is the saving there. A frosted pane's backdrop is already blurred at
 * a sixteenth of its pixels (`frostInputScale`), so there is nothing left to
 * take.
 *
 * [HIGH] is the app as it was before the setting: every value is the one that
 * was fixed in the code. It is the default, so nothing changes for anyone who
 * does not choose.
 *
 * A device setting like the frame rate limit, not part of a theme and not
 * synced: a phone that needs it should not turn it on for a tablet.
 */
enum class RenderQuality(
    /** The value saved in the preferences. */
    val id: String,
    /** What the live lens's size is divided by: 1 is every pixel. */
    val lensDivisor: Int,
    /** The most sample rings a glass pixel reads, whatever the Studio's slider says. */
    val maxGlassRings: Int,
    /** The size the god rays' shafts are drawn at, against the screen. */
    val shaftsScale: Float,
    /** The highest god-ray quality step that runs (1 to 4: 16, 24, 32, 50 samples a pixel). */
    val maxRayQuality: Int,
    /** Whether gradients are dithered against banding: a shader over each one, every frame. */
    val dither: Boolean,
) {
    HIGH("high", lensDivisor = 1, maxGlassRings = 3, shaftsScale = 0.5f, maxRayQuality = 4, dither = true),
    BALANCED("balanced", lensDivisor = 2, maxGlassRings = 2, shaftsScale = 0.4f, maxRayQuality = 2, dither = true),
    FAST("fast", lensDivisor = 3, maxGlassRings = 1, shaftsScale = 1f / 3f, maxRayQuality = 1, dither = false);

    /** The share of a live lens's pixels the GPU shades, in percent: one over the divisor squared. */
    val lensPixelPercent: Int get() = (100f / (lensDivisor * lensDivisor)).roundToInt()

    /** The share of the shafts' pixels the GPU shades against the screen's, in percent. */
    val shaftsPixelPercent: Int get() = (shaftsScale * shaftsScale * 100f).roundToInt()

    /** The texture reads a slab pixel takes at most: the centre and four a ring. */
    val maxGlassReads: Int get() = 1 + 4 * maxGlassRings

    /** [rings] as the Studio set them, held to what this quality runs. */
    fun glassRings(rings: Int): Int = rings.coerceIn(1, maxGlassRings)

    /** A god-ray quality step as the Studio set it, held to what this quality runs. */
    fun rayQuality(step: Int): Int = step.coerceIn(1, maxRayQuality)

    companion object {
        /** The quality saved as [id]; [HIGH] for none, or one this build does not know. */
        fun of(id: String?): RenderQuality = entries.firstOrNull { it.id == id } ?: HIGH
    }
}
