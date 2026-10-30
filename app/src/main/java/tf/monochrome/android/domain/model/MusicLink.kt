package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Which part of the sound a music link follows. The four named ones are the
 * picks the link editor offers as big buttons; [CUSTOM] carries its own edges
 * in [LinkShape.loHz] / [LinkShape.hiHz].
 */
@Serializable
enum class LinkBand(val loHz: Float, val hiHz: Float) {
    KICK(30f, 150f),
    VOCALS(300f, 3000f),
    SNARE(1000f, 4000f),
    HATS(6000f, 16000f),
    CUSTOM(200f, 800f),
}

/** Which way the setting moves when the band gets louder. */
@Serializable
enum class LinkWay {
    /** Rests at the lowest value; each hit pushes it up. */
    UP,

    /** Rests at the highest value; each hit pulls it down. */
    DOWN,

    /** Rests in the middle and swings either way around the band's own average. */
    BOTH,
}

/** How the band's strength maps onto the range. */
@Serializable
enum class LinkCurve {
    /** Moves as much as the music does. */
    EVEN,

    /** Only big hits move it far. */
    GENTLE,

    /** Small hits already move it a lot. */
    STRONG,
}

/**
 * The anti-jitter low-pass after the follower, as a cutoff in Hz. A control
 * signal stepping at the analyser's rate reads as zipper noise on audio and as
 * shimmer on a shader; this irons it out.
 */
@Serializable
enum class LinkSmoothing(val cutoffHz: Float) {
    OFF(0f),
    LOW(24f),
    MEDIUM(10f),
    HIGH(4f),
}

/**
 * How a music link moves its setting: what it listens for, how far the setting
 * may go, and how it gets there. Shared by the visual links (Lyrics FX) and the
 * mixer's knob links, so the editor, the theme packs and the native engine all
 * agree on one meaning for every field.
 *
 * [lowest] and [highest] are in the target setting's own units; the target
 * clamps them, since only it knows its range.
 */
@Serializable
data class LinkShape(
    val band: LinkBand = LinkBand.KICK,
    val loHz: Float = LinkBand.KICK.loHz,
    val hiHz: Float = LinkBand.KICK.hiHz,
    val lowest: Float = 0f,
    val highest: Float = 1f,
    val way: LinkWay = LinkWay.UP,
    /** 0 = snappy, 1 = smooth; sets attack and release together ([attackMs], [releaseMs]). */
    val feel: Float = DEFAULT_FEEL,
    val curve: LinkCurve = LinkCurve.EVEN,
    /** Anything quieter than this (in the analyser's dB) does not move the setting. */
    val quietDb: Float = DEFAULT_QUIET_DB,
    /** Input trim before the follower, in dB. */
    val boostDb: Float = 0f,
    val smoothing: LinkSmoothing = LinkSmoothing.MEDIUM,
    /** Optional expression over `value` and `band`; blank means none. See [LinkFormula]. */
    val formula: String = "",
    /** Stop listening after [SILENT_PAUSE_SEC] below [SILENT_PAUSE_DB], to save battery. */
    val pauseWhenSilent: Boolean = true,
) {
    /** The band's lower edge: the preset's own unless the listener picked the edges. */
    val effectiveLoHz: Float get() = if (band == LinkBand.CUSTOM) loHz else band.loHz

    /** The band's upper edge: the preset's own unless the listener picked the edges. */
    val effectiveHiHz: Float get() = if (band == LinkBand.CUSTOM) hiHz else band.hiHz

    val attackMs: Float get() = attackMsFor(feel)
    val releaseMs: Float get() = releaseMsFor(feel)

    /**
     * Every field inside its contract, with [lowest] and [highest] inside the
     * target's [min]..[max]. Non-finite values fall back to the default, as the
     * settings blobs' own `clamped()` do, so a hand-edited or newer-versioned
     * file can never hand a renderer a NaN.
     */
    fun clamped(min: Float, max: Float): LinkShape {
        val d = LinkShape()
        fun Float.c(lo: Float, hi: Float, fb: Float) = if (isFinite()) coerceIn(lo, hi) else fb
        val lo = loHz.c(MIN_HZ, MAX_HZ / MIN_BAND_RATIO, d.loHz)
        val hi = hiHz.c(lo * MIN_BAND_RATIO, MAX_HZ, maxOf(d.hiHz, lo * MIN_BAND_RATIO))
        return copy(
            loHz = lo,
            hiHz = hi,
            lowest = lowest.c(min, max, min),
            highest = highest.c(min, max, max),
            feel = feel.c(0f, 1f, d.feel),
            quietDb = quietDb.c(MIN_QUIET_DB, 0f, d.quietDb),
            boostDb = boostDb.c(-MAX_BOOST_DB, MAX_BOOST_DB, 0f),
            formula = formula.take(LinkFormula.MAX_LENGTH),
        )
    }

    companion object {
        const val DEFAULT_FEEL = 0.35f
        const val DEFAULT_QUIET_DB = -60f
        const val MIN_QUIET_DB = -96f
        const val MAX_BOOST_DB = 24f
        const val MIN_HZ = 20f
        const val MAX_HZ = 20_000f

        /** The narrowest band the editor allows: the upper edge at least this × the lower. */
        const val MIN_BAND_RATIO = 1.1f

        /** "Pause when silent": below this … */
        const val SILENT_PAUSE_DB = -70f

        /** … for this long. */
        const val SILENT_PAUSE_SEC = 2f

        /** Snappy end of Feel: attack 1 ms, release 40 ms. Smooth end: 60 ms and 1200 ms. */
        fun attackMsFor(feel: Float): Float {
            val x = feel.coerceIn(0f, 1f)
            return 1f + 59f * x * x
        }

        fun releaseMsFor(feel: Float): Float = 40f * 30f.pow(feel.coerceIn(0f, 1f))

        /**
         * Where a link starts: resting at the setting's current value and pushed
         * half of the way to whichever end has more room. The listener sees their
         * own look at rest, and the beat moves it somewhere they can see.
         */
        fun startingRange(current: Float, min: Float, max: Float): Pair<Float, Float> {
            val c = current.coerceIn(min, max)
            val up = max - c
            val down = c - min
            return if (up >= down) c to (c + up * 0.5f) else (c - down * 0.5f) to c
        }
    }
}

/**
 * One visual setting following the music: [target] is a [VisualLinkTargets]
 * key such as `lyrics.godRayExposure`. A setting carries at most one link.
 */
@Serializable
data class VisualLink(
    val target: String,
    val shape: LinkShape = LinkShape(),
    val paused: Boolean = false,
)

/**
 * The arithmetic of a music link, kept off the frame clock so it can be
 * tested: the band's level in, the setting's value out. The native mixer runs
 * the same steps on the audio thread (`mod_route.h`); `MusicLinkFollowerTest`
 * pins this side.
 */
class MusicLinkFollower(shape: LinkShape) {
    var shape: LinkShape = shape
        set(value) {
            field = value
            program = LinkFormula.compile(value.formula).program
        }

    private var program: FloatArray? = LinkFormula.compile(shape.formula).program
    private var floor = 0f
    private var ceiling = 0f
    private var floorInit = false
    private var quietFor = 0f
    private var sinceStart = 0f
    private var env = 0f
    private var smooth = 0f
    private var avg = 0.3f
    private var silentFor = 0f

    /** The band's strength after the follower, 0..1: what the editor's meter shows. */
    var strength = 0f
        private set

    /** True once the band has sat below [LinkShape.SILENT_PAUSE_DB] for [LinkShape.SILENT_PAUSE_SEC]. */
    val asleep: Boolean get() = shape.pauseWhenSilent && silentFor >= LinkShape.SILENT_PAUSE_SEC

    /**
     * One step: [bandDb] is the band's level this frame (before the boost),
     * [dt] the seconds since the last step. Returns the setting's value.
     */
    fun step(bandDb: Float, dt: Float): Float {
        val s = shape
        val dtc = if (dt.isFinite()) dt.coerceIn(0.0005f, 0.1f) else 0.016f
        val db = (if (bandDb.isFinite()) bandDb else -120f) + s.boostDb
        silentFor = if (db < LinkShape.SILENT_PAUSE_DB) silentFor + dtc else 0f

        // An adaptive floor that falls fast into the quiet gaps and rises
        // slowly, so a kick pokes above it and a held vocal stays above it for
        // as long as it is held; and a ceiling that leaps to each hit and sinks
        // slowly, so the band's own swing is the full range. Silence moves
        // neither: a pause dragged the floor to -120 dB, and the music after it
        // read as full for a dozen seconds, pinned at the top. After a real
        // pause both start again from the first sound, the floor just under it.
        val raw: Float
        if (db < s.quietDb) {
            quietFor += dtc
            raw = 0f
        } else {
            if (!floorInit || quietFor >= RESTART_AFTER_SEC) {
                // Just under the first sound, so that first hit shows.
                floor = db - MIN_RANGE_DB
                ceiling = db
                floorInit = true
                sinceStart = 0f
            }
            quietFor = 0f
            sinceStart += dtc
            // Settling at first, then slowly, so a held note sinks in over seconds.
            val floorTau = if (db < floor || sinceStart < SETTLE_SEC) FLOOR_FALL_SEC else FLOOR_RISE_SEC
            floor += (db - floor) * (1f - exp(-dtc / floorTau))
            val ceilingTau = if (db > ceiling) CEILING_RISE_SEC else CEILING_FALL_SEC
            ceiling += (db - ceiling) * (1f - exp(-dtc / ceilingTau))
            // A loud, flat master swings a few dB: that is its whole range, so
            // it moves the setting the whole way rather than sitting still.
            val range = (ceiling - floor).coerceIn(MIN_RANGE_DB, RANGE_DB)
            raw = ((db - floor) / range).coerceIn(0f, 1f)
        }

        val tau = (if (raw > env) s.attackMs else s.releaseMs) / 1000f
        env += (raw - env) * (1f - exp(-dtc / tau.coerceAtLeast(0.0005f)))

        val fc = s.smoothing.cutoffHz
        smooth = if (fc <= 0f) env else smooth + (env - smooth) * (1f - exp(-2f * PI_F * fc * dtc))
        strength = smooth.coerceIn(0f, 1f)
        avg += (strength - avg) * (1f - exp(-dtc / AVERAGE_SEC))
        return valueFor(strength, avg, s, program)
    }

    companion object {
        /** How many dB above the floor counts as "full", at most: less when the band swings less. */
        const val RANGE_DB = 12f

        /** The least it does: a band steadier than this is not blown up into noise. */
        const val MIN_RANGE_DB = 4f
        const val FLOOR_FALL_SEC = 0.3f
        const val FLOOR_RISE_SEC = 6f
        const val CEILING_RISE_SEC = 0.02f
        const val CEILING_FALL_SEC = 4f

        /** Quiet this long (a pause, a gap between songs) and the floor and ceiling start again. */
        const val RESTART_AFTER_SEC = 0.25f

        /** How long after a (re)start the floor rises quickly to where the band sits. */
        const val SETTLE_SEC = 1f

        /** The running average "Both ways" swings around. */
        const val AVERAGE_SEC = 1.5f

        /** How far from its average the band must be to swing the full way, as a gain. */
        const val BOTH_GAIN = 2.6f
        private const val PI_F = 3.1415927f

        fun curved(c: Float, curve: LinkCurve): Float {
            val x = c.coerceIn(0f, 1f)
            return when (curve) {
                LinkCurve.EVEN -> x
                LinkCurve.GENTLE -> x * x
                LinkCurve.STRONG -> sqrt(x)
            }
        }

        /**
         * The setting's value for a band [strength] and its running [average]:
         * the curve, then the way, then the formula, and always back inside
         * lowest..highest.
         */
        fun valueFor(strength: Float, average: Float, shape: LinkShape, program: FloatArray? = null): Float {
            val lo = shape.lowest
            val hi = shape.highest
            val c = curved(strength, shape.curve)
            val v = when (shape.way) {
                LinkWay.UP -> lo + (hi - lo) * c
                LinkWay.DOWN -> hi - (hi - lo) * c
                LinkWay.BOTH -> {
                    val swing = ((strength - average) * BOTH_GAIN).coerceIn(-1f, 1f)
                    (lo + hi) / 2f + (hi - lo) / 2f * swing
                }
            }
            val out = if (program != null) LinkFormula.evaluate(program, v, c) ?: v else v
            return out.coerceIn(minOf(lo, hi), maxOf(lo, hi))
        }

        /**
         * The analyser bin for [hz] on [SpectrumAnalyzerTap]'s log axis:
         * 256 bins from 20 Hz to 20 kHz.
         */
        fun binFor(hz: Float, bins: Int = 256): Int {
            val f = hz.coerceIn(LinkShape.MIN_HZ, LinkShape.MAX_HZ)
            val t = ln(f / LinkShape.MIN_HZ) / ln(LinkShape.MAX_HZ / LinkShape.MIN_HZ)
            return (t * (bins - 1)).toInt().coerceIn(0, bins - 1)
        }

        /** The mean level of the bins covering [loHz]..[hiHz], at least one bin wide. */
        fun bandLevel(bins: FloatArray, loHz: Float, hiHz: Float): Float {
            if (bins.isEmpty()) return -120f
            val a = binFor(loHz, bins.size)
            val b = binFor(hiHz, bins.size).coerceAtLeast(a)
            var sum = 0f
            for (i in a..b) sum += bins[i]
            return sum / (b - a + 1)
        }
    }
}

/** The JSON form of the visual links, tolerant of fields and enum values from newer versions. */
object VisualLinkCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun encode(links: List<VisualLink>): String = json.encodeToString(links)

    /**
     * Decodes and cleans: links to settings this version does not know are
     * dropped, every shape is clamped into its target's range, and a setting
     * named twice keeps its first link.
     */
    fun decode(text: String?): List<VisualLink> {
        if (text.isNullOrBlank()) return emptyList()
        val raw = runCatching { json.decodeFromString<List<VisualLink>>(text) }.getOrNull() ?: return emptyList()
        return sanitize(raw)
    }

    fun sanitize(links: List<VisualLink>): List<VisualLink> =
        links.mapNotNull { link ->
            val target = VisualLinkTargets.byKey(link.target) ?: return@mapNotNull null
            link.copy(shape = link.shape.clamped(target.min, target.max))
        }.distinctBy { it.target }
}
