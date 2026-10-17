// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import tf.monochrome.android.audio.stepmania.CourseType
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphChart
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * StepMania's "Turn" options: one lane permutation for the whole chart.
 *
 * Left and Right turn the pad a quarter: under Left every Up becomes a Left,
 * every Left a Down, every Down a Right and every Right an Up, as if the
 * arrows had been rotated a quarter turn counter-clockwise. Right is the
 * other way. Shuffle is a permutation seeded by the chart, so the same chart
 * shuffles the same way every time and can be learned.
 *
 * On pop'n's nine buttons a quarter turn means nothing, so Left and Right
 * slide every note one button that way, wrapping round; Mirror is pop'n's
 * own MIRROR, reflected about the red centre, and Shuffle its RANDOM.
 */
enum class GlyphTurn(val id: String, val label: String) {
    OFF("off", "Off"),
    MIRROR("mirror", "Mirror"),
    LEFT("left", "Left"),
    RIGHT("right", "Right"),
    SHUFFLE("shuffle", "Shuffle"),
    ;

    /**
     * Where a note written in [lane] lands. [shuffleSeed] seeds Shuffle and
     * is ignored by the rest.
     */
    fun map(lane: GlyphLane, shuffleSeed: Long = 0L, course: CourseType = CourseType.DANCE): GlyphLane = when (this) {
        OFF -> lane
        MIRROR -> GlyphLane.mirrorOf(lane, course)
        LEFT -> when (course) {
            CourseType.DANCE -> when (lane) {
                GlyphLane.UP -> GlyphLane.LEFT
                GlyphLane.LEFT -> GlyphLane.DOWN
                GlyphLane.DOWN -> GlyphLane.RIGHT
                GlyphLane.RIGHT -> GlyphLane.UP
                else -> lane
            }
            CourseType.POPN -> slide(lane, -1, course)
        }
        RIGHT -> when (course) {
            CourseType.DANCE -> when (lane) {
                GlyphLane.UP -> GlyphLane.RIGHT
                GlyphLane.RIGHT -> GlyphLane.DOWN
                GlyphLane.DOWN -> GlyphLane.LEFT
                GlyphLane.LEFT -> GlyphLane.UP
                else -> lane
            }
            CourseType.POPN -> slide(lane, 1, course)
        }
        SHUFFLE -> shuffleOrder(shuffleSeed, course).getOrNull(lane.ordinal) ?: lane
    }

    private fun slide(lane: GlyphLane, by: Int, course: CourseType): GlyphLane =
        GlyphLane.entries[Math.floorMod(lane.ordinal + by, course.laneCount)]

    companion object {
        fun fromId(id: String?): GlyphTurn = entries.firstOrNull { it.id == id } ?: OFF

        /** One permutation of a course's lanes, the same for the same seed. */
        fun shuffleOrder(seed: Long, course: CourseType = CourseType.DANCE): List<GlyphLane> {
            val order = GlyphLane.forCourse(course).toMutableList()
            val random = java.util.Random(seed)
            for (index in order.indices.reversed()) {
                val swap = random.nextInt(index + 1)
                order[index] = order[swap].also { order[swap] = order[index] }
            }
            return order
        }
    }
}

/**
 * [this] chart with [turn] applied to every note, once, as a chart transform:
 * a note that moved lane between scoring and drawing would be a note nobody
 * could hit. Shuffle is seeded by the chart's name, so it is learnable.
 */
fun GlyphChart.withTurn(turn: GlyphTurn): GlyphChart {
    if (turn == GlyphTurn.OFF) return this
    val seed = chartName.hashCode().toLong()
    return copy(notes = notes.map { it.copy(lane = turn.map(it.lane, seed, course)) })
}

/**
 * StepMania's "Appearance" options: where on the way down a note can be seen.
 *
 * Hidden fades notes out as they near the receptors, Sudden only shows them
 * once they are close, and Stealth hides them entirely. Looks only; the notes
 * are judged exactly as before.
 */
enum class GlyphAppearance(val id: String, val label: String) {
    VISIBLE("visible", "Visible"),
    HIDDEN("hidden", "Hidden"),
    SUDDEN("sudden", "Sudden"),
    STEALTH("stealth", "Stealth"),
    ;

    /**
     * How opaque a note is at [distance]: 0 on the receptor line, 1 at the
     * far edge of the playfield, past it beyond.
     */
    fun alphaAt(distance: Float): Float = when (this) {
        VISIBLE -> 1f
        // Gone by the last 25% of the way, fully there past 45%.
        HIDDEN -> ramp(distance, from = 0.25f, to = 0.45f)
        // Nothing until the last 60%, all there inside 40%.
        SUDDEN -> 1f - ramp(distance, from = 0.40f, to = 0.60f)
        STEALTH -> 0f
    }

    companion object {
        fun fromId(id: String?): GlyphAppearance = entries.firstOrNull { it.id == id } ?: VISIBLE

        private fun ramp(value: Float, from: Float, to: Float): Float =
            ((value - from) / (to - from)).coerceIn(0f, 1f)
    }
}

/**
 * StepMania's timing difficulty ("Judge"): every window scaled together.
 * Judge 4 is the standard set; a run under any other is marked on the high
 * score table.
 */
enum class GlyphJudge(val level: Int, val scale: Float) {
    J1(1, 1.50f),
    J2(2, 1.33f),
    J3(3, 1.16f),
    J4(4, 1.00f),
    J5(5, 0.84f),
    J6(6, 0.66f),
    J7(7, 0.50f),
    JUSTICE(8, 0.33f),
    ;

    val label: String get() = if (this == JUSTICE) "Justice" else "J$level"

    companion object {
        fun forScale(scale: Float): GlyphJudge =
            entries.minBy { kotlin.math.abs(it.scale - scale) }
    }
}

/**
 * Trypt Mania's graphics: which of the playfield's effects are drawn, for
 * taste or for a phone that struggles. None of them touches a judgement.
 * Reduced motion and the app's low-performance glass switch still turn the
 * effects off whatever is set here.
 */
data class GlyphGraphics(
    /** The god-ray burst on each hit. */
    val hitRays: Boolean = true,
    /** Samples per shaft for the rays, 1 to 4; the lyrics' quality scale. */
    val rayQuality: Int = 1,
    /** The flash's light caught by the notes and receptors around a hit. */
    val noteLighting: Boolean = true,
    /** The receptor explosion on each hit. */
    val explosions: Boolean = true,
    /** Receptors flashing on the beat. */
    val beatFlash: Boolean = true,
    /** The burst across the field every fifty combo. */
    val comboBurst: Boolean = true,
    /** The playfield seen through an old TV; [GlyphTvShader.OFF] by default. */
    val tvShader: GlyphTvShader = GlyphTvShader.OFF,
    /** The TV's sliders: its pixel size and how strongly each part shows. */
    val tv: GlyphTvTuning = GlyphTvTuning(),
) {
    companion object {
        val RAY_QUALITIES = listOf(1, 2, 3)

        fun rayQualityLabel(quality: Int): String = when (quality) {
            1 -> "Low"
            2 -> "Medium"
            else -> "High"
        }
    }
}

/**
 * The look of an old screen over the playfield. Each multiplies the picture by
 * a pattern, so none of them moves a note or touches a judgement.
 *
 * [mode] is the number the shader picks its pattern by; [id] is what is saved.
 */
enum class GlyphTvShader(val id: String, val label: String, val note: String, val mode: Int) {
    OFF("off", "Off", "The game as it is drawn.", 0),
    SCANLINES("scanlines", "Scanlines", "Every other line at half brightness.", 1),
    DIAGONAL("diagonal", "Diagonal", "A red, green and blue mask that steps one pixel each row.", 2),
    TRIANGULAR("triangular", "Triangular", "Two-pixel red, green and blue columns, shifted every second row pair.", 3),
    WAVE("wave", "Wave", "The picture in lines, each one brighter at its middle.", 4),
    CRT("crt", "CRT", "Soft scanlines, a shadow mask and a little bloom: a CRT monitor.", 5),
    ROYALE(
        "royale",
        "CRT-Royale",
        "A consumer TV up close: beams that widen as they brighten, an aperture grille, slot or shadow mask, " +
            "and light that glows through the glass. The heaviest of the seven.",
        6,
    ),
    ;

    companion object {
        fun fromId(id: String?): GlyphTvShader = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/**
 * The TV shader's sliders.
 *
 * A TV pixel is a whole number of the phone's own pixels, never a fraction:
 * a grid that does not land on the screen's pixels beats against them, and
 * that beat (moiré) is what a line grid of 640 x 480 drew on a 1272-pixel
 * screen. [pixelSizes] is kept per look, since each has its own default: the
 * CRT at the phone's own resolution, the masks at a desktop monitor's pixel
 * (where a one-pixel line is still visible), Wave and CRT-Royale at the
 * original's 480 lines.
 *
 * [strength] and [brightness] apply to every look, and so do [chromatic] and
 * the two curves ([curveTopDeg], [curveSideDeg]), which bend the finished
 * picture as the glass of a tube would. The CRT's four run 0 to 1, and
 * [crtScan] and the rest turn them into the shader's own numbers; the
 * `royale` ones are CRT-Royale's, the same way. Every default is the
 * original's; the tube's start flat and in line.
 */
data class GlyphTvTuning(
    val pixelSizes: Map<GlyphTvShader, Int> = emptyMap(),
    /** How much of the effect: 0 is the plain picture. */
    val strength: Float = 1f,
    /** A multiplier on the result, 0.5 to 2, for looks that darken the picture. */
    val brightness: Float = 1f,
    val scanlines: Float = DEFAULT_SCANLINES,
    val mask: Float = DEFAULT_MASK,
    val glow: Float = DEFAULT_GLOW,
    val sharpness: Float = DEFAULT_SHARPNESS,
    /** Red and blue drawn apart sideways, as on a tube whose three beams are out of line: 0 is in line. */
    val chromatic: Float = 0f,
    /**
     * How far the glass bows toward the top and bottom edges, in degrees,
     * 0 to [MAX_CURVE_DEG]: those edges bow outward, and the corners go dark.
     */
    val curveTopDeg: Float = 0f,
    /** The same toward the left and right edges. */
    val curveSideDeg: Float = 0f,
    /**
     * The tube seen from one side, in degrees, -[MAX_TILT_DEG] to
     * [MAX_TILT_DEG]: turned about its upright middle, so one side comes
     * nearer and stands taller, as a TV filmed from beside it.
     */
    val tiltSideDeg: Float = 0f,
    /** The same from above or below: turned about its level middle. */
    val tiltTopDeg: Float = 0f,
    /** The dark frame around the picture, 0 to 1: the picture drawn smaller inside it. */
    val bezel: Float = 0f,
    /** How round the picture's corners are, 0 to 1. */
    val corners: Float = 0f,
    /** The picture darkening toward its edges, 0 to 1. */
    val vignette: Float = 0f,
    /** Light on the glass, 0 to 1: a soft sheen across it and a streak on the frame. */
    val glare: Float = 0f,
    /** CRT-Royale's phosphor mask: [ROYALE_GRILLE], [ROYALE_SLOT] (its default) or [ROYALE_SHADOW]. */
    val royaleMask: Int = ROYALE_SLOT,
    /** CRT-Royale's mask triad, in phone pixels; 0 is this screen's default ([royaleTriadPx]). */
    val royaleTriad: Int = 0,
    /** How much of the mask shows, 0 to 1: CRT-Royale draws all of it. */
    val royaleMaskStrength: Float = 1f,
    /** How wide a bright line's beam spreads, 0 to 1 (`beam_max_sigma`, 0.3 by default). */
    val royaleBeamWidth: Float = DEFAULT_ROYALE_BEAM_WIDTH,
    /** How flat-topped and hard-edged a bright beam is, 0 to 1 (`beam_max_shape`, 4 by default). */
    val royaleBeamShape: Float = DEFAULT_ROYALE_BEAM_SHAPE,
    /** Light scattered inside the tube onto the beams, 0 to 1 (`halation_weight`, 0 by default). */
    val royaleHalation: Float = 0f,
    /** Light scattered by the glass over the picture, 0 to 1 (`diffusion_weight`, 0.075 by default). */
    val royaleDiffusion: Float = DEFAULT_ROYALE_DIFFUSION,
    /** How much a bright area glows past what its mask shows, 0 to 1 (`bloom_excess`, 0 by default). */
    val royaleBloom: Float = 0f,
    /** The dark rim around the picture, 0 to 1 (`border_size`, 0.015 of the screen by default). */
    val royaleBorder: Float = DEFAULT_ROYALE_BORDER,
) {
    /** Phone pixels to a TV pixel for [shader], on a screen of [density] and [windowHeightPx]. */
    fun pixelSize(shader: GlyphTvShader, density: Float, windowHeightPx: Int): Int =
        (pixelSizes[shader] ?: defaultPixelSize(shader, density, windowHeightPx)).coerceIn(pixelRange(shader))

    fun withPixelSize(shader: GlyphTvShader, size: Int): GlyphTvTuning =
        copy(pixelSizes = pixelSizes + (shader to size.coerceIn(pixelRange(shader))))

    /** How hard the gaps between scanlines are: the shader's `hardScan`, -8 by default. */
    val crtScan: Float get() = -(SCAN_SOFTEST + (SCAN_HARDEST - SCAN_SOFTEST) * scanlines)

    /** How sharp a pixel's edges are: the shader's `hardPix`, -3 by default. */
    val crtSharpness: Float get() = -(SHARP_SOFTEST + (SHARP_HARDEST - SHARP_SOFTEST) * sharpness)

    /** The shadow mask's dark and light channel levels: 0.5 and 1.5 by default. */
    val crtMaskDark: Float get() = 1f - mask
    val crtMaskLight: Float get() = 1f + mask

    /** The bloom added over the picture: 0.15 by default. */
    val crtBloom: Float get() = GLOW_MOST * glow

    /** CRT-Royale's mask triad on a screen of [density]: about 2.5 dp unless set. */
    fun royaleTriadPx(density: Float): Int =
        (if (royaleTriad > 0) royaleTriad else (density * ROYALE_TRIAD_DP).roundToInt()).coerceIn(ROYALE_TRIADS)

    /** CRT-Royale's `beam_max_sigma`: a bright beam's spread, in lines. */
    val royaleMaxSigma: Float get() = ROYALE_SIGMA_LEAST + (ROYALE_SIGMA_MOST - ROYALE_SIGMA_LEAST) * royaleBeamWidth

    /** CRT-Royale's `beam_max_shape`: 2 is a Gaussian, more is flatter on top and harder at the edge. */
    val royaleMaxShape: Float get() = ROYALE_SHAPE_LEAST + (ROYALE_SHAPE_MOST - ROYALE_SHAPE_LEAST) * royaleBeamShape

    val royaleHalationWeight: Float get() = ROYALE_HALATION_MOST * royaleHalation
    val royaleDiffusionWeight: Float get() = ROYALE_DIFFUSION_MOST * royaleDiffusion

    /** CRT-Royale's `border_size`, as a share of the screen's short side. */
    val royaleBorderSize: Float get() = ROYALE_BORDER_MOST * royaleBorder

    /** How far red and blue each stand from green, in phone pixels on a screen of [density]. */
    fun chromaticPx(density: Float): Float = CHROMATIC_MOST_DP * density * chromatic

    /**
     * The tube's bow along each axis: the CRT shader's `warp`, `1/cos θ − 1`
     * of each angle. [warpX] bows the sides out, [warpY] the top and bottom;
     * about 14.1° and 16.3° are the original's 1/32 and 1/24.
     */
    val warpX: Float get() = warpOf(curveSideDeg)
    val warpY: Float get() = warpOf(curveTopDeg)

    /** Whether the picture is bowed at all. */
    val curved: Boolean get() = curveTopDeg >= CURVE_MIN_DEG || curveSideDeg >= CURVE_MIN_DEG

    /** Whether it is seen from an angle. */
    val tilted: Boolean get() = abs(tiltSideDeg) >= CURVE_MIN_DEG || abs(tiltTopDeg) >= CURVE_MIN_DEG

    /**
     * Whether anything moves the picture off the screen's own pixels (a bow,
     * a tilt, a frame), so taps on it have to follow ([tubeSource]).
     */
    val reshaped: Boolean get() = curved || tilted || bezel >= TUBE_MIN

    /** Whether the picture has an edge of its own to soften: reshaped, or its corners rounded. */
    val framed: Boolean get() = reshaped || corners >= TUBE_MIN

    /** Whether the tube's pass runs at all. */
    val hasTube: Boolean get() = framed || chromatic >= TUBE_MIN || vignette >= TUBE_MIN || glare >= TUBE_MIN

    /**
     * The tube's view of a [width] by [height] picture: turned by the two
     * tilts and seen from [VIEW_DISTANCE] half-sides away, then drawn small
     * enough that all of it fits, and smaller again by the [bezel]. What
     * the tube shader is handed, and what [tubeSource] works backwards with.
     */
    fun tubeView(width: Float, height: Float): TubeView {
        val short = minOf(width, height).coerceAtLeast(1f)
        val ax = width / short
        val ay = height / short
        val a = Math.toRadians(tiltSideDeg.coerceIn(-MAX_TILT_DEG, MAX_TILT_DEG).toDouble())
        val b = Math.toRadians(tiltTopDeg.coerceIn(-MAX_TILT_DEG, MAX_TILT_DEG).toDouble())
        val ca = cos(a).toFloat()
        val sa = kotlin.math.sin(a).toFloat()
        val cb = cos(b).toFloat()
        val sb = kotlin.math.sin(b).toFloat()
        // Ry(a) · Rx(b), by its columns.
        val col0 = floatArrayOf(ca, 0f, -sa)
        val col1 = floatArrayOf(sa * sb, cb, ca * sb)
        val normal = floatArrayOf(sa * cb, -sb, ca * cb)
        val d = VIEW_DISTANCE
        // The turned picture's corners on the screen: it is drawn to fill
        // them, centred, so the nearer side is not pushed off the screen.
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (u in floatArrayOf(-ax, ax)) for (v in floatArrayOf(-ay, ay)) {
            val wx = col0[0] * u + col1[0] * v
            val wy = col0[1] * u + col1[1] * v
            val wz = col0[2] * u + col1[2] * v
            val k = d / (wz + d)
            minX = minOf(minX, wx * k / ax)
            maxX = maxOf(maxX, wx * k / ax)
            minY = minOf(minY, wy * k / ay)
            maxY = maxOf(maxY, wy * k / ay)
        }
        val frame = 1f + BEZEL_MOST * bezel
        return TubeView(
            ax, ay,
            (maxX - minX) / 2f * frame, (maxY - minY) / 2f * frame,
            (maxX + minX) / 2f, (maxY + minY) / 2f,
            col0, col1, normal, d, warpX, warpY,
        )
    }

    /**
     * Where on the flat picture the point [x], [y] of a [width] by [height]
     * tube shows it: the tube shader's own sum (fitted, turned back through
     * the tilts, then bowed), so a tap can be sent to what is seen under it.
     * Off the picture (its frame, its dark corners) the point is held to the
     * picture's edge. [out] gets the two, x then y.
     */
    fun tubeSource(x: Float, y: Float, width: Float, height: Float, out: FloatArray) {
        if (width <= 0f || height <= 0f) {
            out[0] = x
            out[1] = y
            return
        }
        val view = tubeView(width, height)
        val plane = view.plane((x / width * 2f - 1f), (y / height * 2f - 1f))
        val px = plane?.first ?: 2f * kotlin.math.sign(x / width - 0.5f)
        val py = plane?.second ?: 2f * kotlin.math.sign(y / height - 0.5f)
        val sx = px * (1f + py * py * warpX)
        val sy = py * (1f + px * px * warpY)
        out[0] = ((sx * 0.5f + 0.5f) * width).coerceIn(0f, width)
        out[1] = ((sy * 0.5f + 0.5f) * height).coerceIn(0f, height)
    }

    /** Every value inside its slider's range, and every size inside its look's. */
    fun clamped(): GlyphTvTuning = GlyphTvTuning(
        pixelSizes = pixelSizes.mapValues { (shader, size) -> size.coerceIn(pixelRange(shader)) },
        strength = strength.unit(1f),
        brightness = (if (brightness.isFinite()) brightness else 1f).coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS),
        scanlines = scanlines.unit(DEFAULT_SCANLINES),
        mask = mask.unit(DEFAULT_MASK),
        glow = glow.unit(DEFAULT_GLOW),
        sharpness = sharpness.unit(DEFAULT_SHARPNESS),
        chromatic = chromatic.unit(0f),
        curveTopDeg = curveTopDeg.angle(),
        curveSideDeg = curveSideDeg.angle(),
        tiltSideDeg = tiltSideDeg.tilt(),
        tiltTopDeg = tiltTopDeg.tilt(),
        bezel = bezel.unit(0f),
        corners = corners.unit(0f),
        vignette = vignette.unit(0f),
        glare = glare.unit(0f),
        royaleMask = royaleMask.coerceIn(ROYALE_GRILLE, ROYALE_SHADOW),
        royaleTriad = if (royaleTriad <= 0) 0 else royaleTriad.coerceIn(ROYALE_TRIADS),
        royaleMaskStrength = royaleMaskStrength.unit(1f),
        royaleBeamWidth = royaleBeamWidth.unit(DEFAULT_ROYALE_BEAM_WIDTH),
        royaleBeamShape = royaleBeamShape.unit(DEFAULT_ROYALE_BEAM_SHAPE),
        royaleHalation = royaleHalation.unit(0f),
        royaleDiffusion = royaleDiffusion.unit(DEFAULT_ROYALE_DIFFUSION),
        royaleBloom = royaleBloom.unit(0f),
        royaleBorder = royaleBorder.unit(DEFAULT_ROYALE_BORDER),
    )

    /**
     * Back to [shader]'s defaults: every slider that look shows, and its own
     * pixel size; the other looks' sizes are kept.
     */
    fun reset(shader: GlyphTvShader): GlyphTvTuning = GlyphTvTuning(pixelSizes = pixelSizes - shader)

    /**
     * One line for a settings store: `name=value` pairs, `;` between them,
     * a look's pixel size as `px.<look>`. [decode] reads it back.
     */
    fun encode(): String = buildList {
        add("strength=$strength")
        add("brightness=$brightness")
        add("scanlines=$scanlines")
        add("mask=$mask")
        add("glow=$glow")
        add("sharpness=$sharpness")
        add("chromatic=$chromatic")
        add("curveTop=$curveTopDeg")
        add("curveSide=$curveSideDeg")
        add("tiltSide=$tiltSideDeg")
        add("tiltTop=$tiltTopDeg")
        add("bezel=$bezel")
        add("corners=$corners")
        add("vignette=$vignette")
        add("glare=$glare")
        add("royaleMask=$royaleMask")
        add("royaleTriad=$royaleTriad")
        add("royaleMaskStrength=$royaleMaskStrength")
        add("royaleBeamWidth=$royaleBeamWidth")
        add("royaleBeamShape=$royaleBeamShape")
        add("royaleHalation=$royaleHalation")
        add("royaleDiffusion=$royaleDiffusion")
        add("royaleBloom=$royaleBloom")
        add("royaleBorder=$royaleBorder")
        pixelSizes.forEach { (shader, size) -> add("px.${shader.id}=$size") }
    }.joinToString(";")

    companion object {
        const val DEFAULT_SCANLINES = 6f / 14f
        const val DEFAULT_MASK = 0.5f
        const val DEFAULT_GLOW = 0.3f
        const val DEFAULT_SHARPNESS = 0.4f
        const val MIN_BRIGHTNESS = 0.5f
        const val MAX_BRIGHTNESS = 2f

        private const val SCAN_SOFTEST = 2f
        private const val SCAN_HARDEST = 16f
        private const val SHARP_SOFTEST = 1f
        private const val SHARP_HARDEST = 6f
        private const val GLOW_MOST = 0.5f

        /** Red and blue's furthest from green, in dp: past it the letters read double. */
        const val CHROMATIC_MOST_DP = 3f

        /** Each curve's far end: past it the corners take most of the screen. */
        const val MAX_CURVE_DEG = 40f

        /** Each tilt's far end: a TV seen well from one side. */
        const val MAX_TILT_DEG = 30f

        /** How far away the tube is seen from, in half its short side. */
        const val VIEW_DISTANCE = 3.5f

        /** The frame's widest: the picture drawn at 1 / 1.3 of the screen. */
        const val BEZEL_MOST = 0.3f

        /** The roundest corner, as a share of the short side. */
        const val CORNER_MOST = 0.12f

        /** Below this a curve is flat, with no pass for it. */
        private const val CURVE_MIN_DEG = 0.05f

        /** Below this the chromatic shift is off. */
        private const val TUBE_MIN = 0.001f

        const val ROYALE_GRILLE = 0
        const val ROYALE_SLOT = 1
        const val ROYALE_SHADOW = 2

        /** CRT-Royale's mask triads, in phone pixels, and their default in dp. */
        val ROYALE_TRIADS = 3..24
        private const val ROYALE_TRIAD_DP = 2.5f

        // Each Royale slider's range in the shader's own numbers, its default
        // the original's: beam_max_sigma 0.3, beam_max_shape 4, diffusion
        // 0.075, border 0.015.
        private const val ROYALE_SIGMA_LEAST = 0.1f
        private const val ROYALE_SIGMA_MOST = 0.6f
        const val DEFAULT_ROYALE_BEAM_WIDTH = 0.4f
        private const val ROYALE_SHAPE_LEAST = 2f
        private const val ROYALE_SHAPE_MOST = 10f
        const val DEFAULT_ROYALE_BEAM_SHAPE = 0.25f
        private const val ROYALE_HALATION_MOST = 0.5f
        private const val ROYALE_DIFFUSION_MOST = 0.5f
        const val DEFAULT_ROYALE_DIFFUSION = 0.15f
        private const val ROYALE_BORDER_MOST = 0.06f
        const val DEFAULT_ROYALE_BORDER = 0.25f

        /** A monitor's pixel, in dp: about a hundredth of an inch. */
        private const val MONITOR_PIXEL_DP = 1.6f

        /** The original's line count, for Wave's and CRT-Royale's defaults. */
        private const val ORIGINAL_LINES = 480

        private fun Float.unit(fallback: Float): Float = (if (isFinite()) this else fallback).coerceIn(0f, 1f)

        private fun Float.angle(): Float = (if (isFinite()) this else 0f).coerceIn(0f, MAX_CURVE_DEG)

        private fun Float.tilt(): Float = (if (isFinite()) this else 0f).coerceIn(-MAX_TILT_DEG, MAX_TILT_DEG)

        /** `1/cos θ − 1`: how much further out than the tube's corner the picture's corner lies. */
        fun warpOf(degrees: Float): Float {
            if (degrees < CURVE_MIN_DEG) return 0f
            return (1.0 / cos(Math.toRadians(degrees.coerceAtMost(MAX_CURVE_DEG).toDouble())) - 1.0).toFloat()
        }

        /** The angle whose bow is [warp]: [warpOf] backwards, for a curve saved as a share of a slider. */
        fun degreesOf(warp: Float): Float =
            if (warp <= 0f || !warp.isFinite()) 0f else Math.toDegrees(acos(1.0 / (1.0 + warp))).toFloat()

        /**
         * [encode]'s line read back, each value held to its range; a name it
         * does not know, or a value it cannot read, keeps that default. Null
         * or blank is every default. A line from the build with one Curvature
         * slider (0 to 1, warps of 1/8 and 1/6 at its end) becomes the two
         * angles that draw the same tube.
         */
        fun decode(line: String?): GlyphTvTuning {
            if (line.isNullOrBlank()) return GlyphTvTuning()
            val pairs = line.split(';').mapNotNull { part ->
                val name = part.substringBefore('=', "").trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }.toMap()
            fun float(name: String, default: Float) = pairs[name]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: default
            fun int(name: String, default: Int) = pairs[name]?.toIntOrNull() ?: default
            val defaults = GlyphTvTuning()
            val legacy = pairs["curvature"]?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            return GlyphTvTuning(
                pixelSizes = GlyphTvShader.entries.mapNotNull { shader ->
                    pairs["px.${shader.id}"]?.toIntOrNull()?.takeIf { it > 0 }?.let { shader to it }
                }.toMap(),
                strength = float("strength", defaults.strength),
                brightness = float("brightness", defaults.brightness),
                scanlines = float("scanlines", defaults.scanlines),
                mask = float("mask", defaults.mask),
                glow = float("glow", defaults.glow),
                sharpness = float("sharpness", defaults.sharpness),
                chromatic = float("chromatic", defaults.chromatic),
                curveTopDeg = float("curveTop", legacy?.let { degreesOf(LEGACY_WARP_Y * it) } ?: 0f),
                curveSideDeg = float("curveSide", legacy?.let { degreesOf(LEGACY_WARP_X * it) } ?: 0f),
                tiltSideDeg = float("tiltSide", 0f),
                tiltTopDeg = float("tiltTop", 0f),
                bezel = float("bezel", 0f),
                corners = float("corners", 0f),
                vignette = float("vignette", 0f),
                glare = float("glare", 0f),
                royaleMask = int("royaleMask", defaults.royaleMask),
                royaleTriad = int("royaleTriad", defaults.royaleTriad),
                royaleMaskStrength = float("royaleMaskStrength", defaults.royaleMaskStrength),
                royaleBeamWidth = float("royaleBeamWidth", defaults.royaleBeamWidth),
                royaleBeamShape = float("royaleBeamShape", defaults.royaleBeamShape),
                royaleHalation = float("royaleHalation", defaults.royaleHalation),
                royaleDiffusion = float("royaleDiffusion", defaults.royaleDiffusion),
                royaleBloom = float("royaleBloom", defaults.royaleBloom),
                royaleBorder = float("royaleBorder", defaults.royaleBorder),
            ).clamped()
        }

        /**
         * An old TV filmed from beside it, as in the TRYPT CORE videos:
         * CRT-Royale's beams at about 240 lines down a screen of
         * [windowHeightPx], a faint mask, glow through the glass, colours
         * apart, the tube bowed and seen from a little to its left inside a
         * thin dark frame, rounded corners, darker edges and a glare. Pixel
         * sizes for the other looks are kept from [keep].
         */
        fun oldTv(windowHeightPx: Int, keep: GlyphTvTuning = GlyphTvTuning()): GlyphTvTuning = GlyphTvTuning(
            pixelSizes = keep.pixelSizes,
            brightness = 1.45f,
            chromatic = 0.35f,
            curveTopDeg = 12f,
            curveSideDeg = 10f,
            tiltSideDeg = 7f,
            bezel = 0.12f,
            corners = 0.35f,
            vignette = 0.35f,
            glare = 0.5f,
            royaleMaskStrength = 0.25f,
            royaleBeamWidth = 0.5f,
            royaleHalation = 0.25f,
            royaleDiffusion = 0.3f,
            royaleBloom = 0.35f,
            royaleBorder = 0f,
        ).let {
            if (windowHeightPx > 0) it.withPixelSize(GlyphTvShader.ROYALE, (windowHeightPx / OLD_TV_LINES.toFloat()).roundToInt()) else it
        }

        /** The Old TV look's lines down the screen. */
        private const val OLD_TV_LINES = 240

        /** The one Curvature slider's bows at its end, for [decode]. */
        const val LEGACY_WARP_X = 0.125f
        const val LEGACY_WARP_Y = 1f / 6f

        fun pixelRange(shader: GlyphTvShader): IntRange = when (shader) {
            // Wave's line is its bright middle and two dim edges: three pixels at least.
            GlyphTvShader.WAVE -> 3..12
            // A Royale line needs room for its beam to be seen to widen.
            GlyphTvShader.ROYALE -> 2..16
            else -> 1..8
        }

        fun defaultPixelSize(shader: GlyphTvShader, density: Float, windowHeightPx: Int): Int = when (shader) {
            GlyphTvShader.CRT -> 1
            GlyphTvShader.WAVE, GlyphTvShader.ROYALE ->
                if (windowHeightPx > 0) (windowHeightPx.toFloat() / ORIGINAL_LINES).roundToInt() else 6
            else -> (density * MONITOR_PIXEL_DP).roundToInt()
        }.coerceIn(pixelRange(shader))
    }
}

/**
 * What [GlyphTvTuning.tubeView] works out for one picture size: its aspect
 * (each side over the short one), how much larger than the screen the
 * sampled area is so the turned picture fits ([fitX], [fitY]) and where its
 * middle lands ([shiftX], [shiftY]), so it is centred, the turned
 * plane's two axes and its normal (columns of the turn), how far away it is
 * seen from, and the bows.
 */
class TubeView(
    val aspectX: Float,
    val aspectY: Float,
    val fitX: Float,
    val fitY: Float,
    val shiftX: Float,
    val shiftY: Float,
    val col0: FloatArray,
    val col1: FloatArray,
    val normal: FloatArray,
    val distance: Float,
    val warpX: Float,
    val warpY: Float,
) {
    /**
     * The point on the turned picture, before its bow, under the screen
     * point [cx], [cy] (each -1 to 1 across the screen); null where the line
     * of sight misses it. The tube shader's sum.
     */
    fun plane(cx: Float, cy: Float): Pair<Float, Float>? {
        val sx = (cx * fitX + shiftX) * aspectX
        val sy = (cy * fitY + shiftY) * aspectY
        val denom = normal[0] * sx + normal[1] * sy + normal[2] * distance
        if (denom <= 1e-4f) return null
        val t = distance * normal[2] / denom
        val px = t * sx
        val py = t * sy
        val pz = -distance + t * distance
        val u = (col0[0] * px + col0[1] * py + col0[2] * pz) / aspectX
        val v = (col1[0] * px + col1[1] * py + col1[2] * pz) / aspectY
        return u to v
    }
}
