package tf.monochrome.android.audio.eq.dac

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.ImportedEq
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.ShelfForm
import io.github.chronosauros.contour.core.isPass
import io.github.chronosauros.contour.core.native.FiioCodec
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import io.github.chronosauros.contour.core.FilterType as DacFilter

/**
 * Between the app's EQ bands and the ones Contour's core sends to a USB DAC
 * (third_party/contour).
 *
 * The two differ in one thing that is heard. The app draws and plays a shelf
 * in the cookbook's Q form. Some DACs build theirs from a slope instead
 * ([ShelfForm.SLOPE]: the ones that play the coefficients Contour computes),
 * and for those Contour's band holds the slope where the app's holds a Q. The
 * two agree only at Q 0.707; at Q 2 the same number is a shelf that peaks
 * some 3 dB higher. So a shelf's number is converted on the way out and back
 * ([slopeForQ], [qForSlope]), and the curve the page draws is the one the DAC
 * is asked to play.
 */
object DacEqMapping {

    /** The rate both this page and Contour draw the intended curve at. */
    const val DISPLAY_SAMPLE_RATE = 96_000f

    fun toDac(type: FilterType): DacFilter = when (type) {
        FilterType.PEAKING -> DacFilter.PEAK
        FilterType.LOWSHELF -> DacFilter.LOW_SHELF
        FilterType.HIGHSHELF -> DacFilter.HIGH_SHELF
    }

    /** Null for the four pass filters, which the app's EQ has no band for. */
    fun fromDac(type: DacFilter): FilterType? = when (type) {
        DacFilter.PEAK -> FilterType.PEAKING
        DacFilter.LOW_SHELF -> FilterType.LOWSHELF
        DacFilter.HIGH_SHELF -> FilterType.HIGHSHELF
        else -> null
    }

    /**
     * The slope that makes a slope-form shelf the same filter as a Q-form
     * shelf of [q] at [gainDb]: both forms' alpha set equal. Always positive.
     */
    fun slopeForQ(q: Double, gainDb: Double): Double {
        val a = 10.0.pow(abs(gainDb) / 40.0)
        return 1.0 / ((1.0 / (q * q) - 2.0) / (a + 1.0 / a) + 1.0)
    }

    /**
     * The Q that draws a slope-form shelf of [slope] at [gainDb], or null for
     * a shelf with no real response (a strong boost with a slope well over
     * 1), which no Q describes.
     */
    fun qForSlope(slope: Double, gainDb: Double): Double? {
        val a = 10.0.pow(abs(gainDb) / 40.0)
        val x = (a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0
        return if (slope > 0.0 && x > 0.0) 1.0 / sqrt(x) else null
    }

    /**
     * A band's number as the number it shows. The app keeps a band in
     * floats, and 0.6 as a float is 0.60000002: taken as that, a filter's
     * coefficients come out a few parts in a hundred million off the ones
     * the same EQ gets from its numbers as typed. Read through its shortest
     * decimal, the float is 0.6 again, and the frames for an EQ are the
     * frames another tool makes for it, to the byte (`DacEqTp35ProCaptureTest`
     * holds that against a recorded DAC).
     */
    fun exact(value: Float): Double = value.toString().toDouble()

    private fun slopeShelf(type: FilterType, form: ShelfForm) =
        form == ShelfForm.SLOPE && type != FilterType.PEAKING

    /** A band's Q as the DAC's own number: the slope for a slope-form shelf. */
    fun dacQ(band: EqBand, form: ShelfForm): Double =
        if (slopeShelf(band.type, form)) slopeForQ(exact(band.q), exact(band.gain)) else exact(band.q)

    /** The DAC's number as the Q the app draws; null where it has none ([qForSlope]). */
    fun appQ(type: FilterType, dacQ: Double, gainDb: Double, form: ShelfForm): Double? =
        if (slopeShelf(type, form)) qForSlope(dacQ, gainDb) else dacQ

    /**
     * [bands] as the profile Contour sends. Only the bands that are on go:
     * a DAC's unused filters are written neutral by its own session.
     * [preampDb] null is Contour's automatic preamp, which follows the rules
     * of the DAC's planner (whole dB on most, a folded high shelf on some).
     */
    fun profile(name: String, bands: List<EqBand>, preampDb: Double?, target: DeviceTarget, now: Long): Profile {
        val form = target.caps.shelfForm
        return Profile(
            id = "tryptify",
            name = slotName(name),
            bands = bands.filter { it.enabled }.mapIndexed { i, b ->
                Band(
                    id = "b$i",
                    type = toDac(b.type),
                    freqHz = exact(b.freq),
                    gainDb = exact(b.gain),
                    q = dacQ(b, form),
                )
            },
            preampDb = preampDb,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Whether a preamp can be sent to [target] at all. Some DACs have none
     * (their only gain is the analog one, never written), and one has a
     * preamp Contour does not write yet: those are sent 0 dB, and the page
     * says that boosts may clip. Contour's own planner is asked, with a band
     * that does nothing and a preamp of -1 dB, so a DAC it adds such a rule
     * for is known here without being named.
     */
    fun writesPreamp(target: DeviceTarget): Boolean {
        if (!target.hasPreamp) return false
        // The page asks on every change; a DAC's answer does not change.
        return preampTaken.getOrPut(target.caps.name) {
            val probe = listOf(EqBand(id = 0, type = FilterType.PEAKING, freq = 1000f, gain = 0f, q = 1f))
            fun taken(preampDb: Double) = runCatching { target.issues(profile("probe", probe, preampDb, target, 0L)) }
                .getOrDefault(listOf("refused")).isEmpty()
            // A DAC that refuses the probe at 0 dB too is refusing something else.
            taken(-1.0) || !taken(0.0)
        }
    }

    private val preampTaken = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * A name a DAC's slot can take. Some DACs keep a name with the EQ, A to Z
     * and digits only; a name in another script would lose all its letters,
     * so it is not used.
     */
    fun slotName(name: String): String =
        name.takeIf { FiioCodec.nameFitsSlot(it) && FiioCodec.slotName(it).isNotEmpty() } ?: FALLBACK_NAME

    const val FALLBACK_NAME = "Tryptify"

    /**
     * What a DAC holds, as the app's bands.
     *
     * @property passFilters how many of the DAC's filters are low, high, band
     *   or all pass. The app's EQ has no such band, so they are left out, and
     *   sending from this page would replace them.
     * @property slopesGuessed shelves whose slope no Q describes; they are
     *   shown at Q 0.707.
     */
    class Imported(
        val bands: List<EqBand>,
        val preampDb: Float?,
        val passFilters: Int,
        val slopesGuessed: Int,
        val notes: List<String>,
    )

    fun fromDac(eq: ImportedEq, target: DeviceTarget): Imported {
        val form = target.caps.shelfForm
        var guessed = 0
        val bands = eq.bands.filter { !it.type.isPass }.mapIndexed { i, b ->
            val type = requireNotNull(fromDac(b.type))
            val q = appQ(type, b.q, b.gainDb, form) ?: DEFAULT_SHELF_Q.also { guessed++ }
            EqBand(id = i, type = type, freq = b.freqHz.toFloat(), gain = b.gainDb.toFloat(), q = q.toFloat(), enabled = b.enabled)
        }
        return Imported(
            bands = bands,
            preampDb = eq.preampDb?.toFloat(),
            passFilters = eq.bands.count { it.type.isPass },
            slopesGuessed = guessed,
            notes = eq.notes,
        )
    }

    const val DEFAULT_SHELF_Q = 0.7071
}
