package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.devices.GeneratedDevices

/** What a DAC accepts. The editor greys out everything outside it; [ProtocolMicro.plan] refuses it. */
data class DeviceCapabilities(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val bands: Int,
    val gainMinDb: Double,
    val gainMaxDb: Double,
    val qMin: Double,
    val qMax: Double,
    val freqMinHz: Double,
    val freqMaxHz: Double,
    val types: Set<FilterType>,
    /** The device stores the preamp in whole dB only. */
    val preampWholeDb: Boolean,
    /**
     * How the DAC builds a shelf from the band's Q (record `peq.shelfForm`, see [ShelfForm]). What the graph draws and what AUTO preamp computes follow it; the band registers, the coefficient bytes and the Q scales never do.
     * Required: a new caps has to say it.
     */
    val shelfForm: ShelfForm,
    /**
     * Measured Q conventions of the DAC (records `peq.shelfQScale` and `peq.peakQScale`): the register holds intended Q x the scale for that type, so
     * [qMin] and [qMax] are the register range and the Q the editor offers is that range divided by the scale ([qRange]). 1.0 = the register is the Q.
     */
    val shelfQScale: Double = 1.0,
    val peakQScale: Double = 1.0,
    /** The rate the DSP runs the biquad coefficients Contour sends at (record `peq.coefficientRateHz`); WalkPlay only, see [WalkPlay.computeIir]. */
    val coefficientRateHz: Double = WalkPlay.DESIGN_FS,
    /** A shelf's limits where they are narrower than the device-wide ones (record `peq.shelfGainDb` and `peq.shelfQ`: what a unit was measured to play within tolerance); null = none. */
    val shelfLimits: ShelfLimits? = null,
    /**
     * A band whose biquad overflows signed Q30 is sent with its coefficients wrapped to int32, as devicePEQ's quantizer and byte split do, instead of being refused. Only for a DAC that
     * plays the registers and not the coefficient bytes, and only where that was measured to be right (the Protocol Max, see [ProtocolMax]); false everywhere else. The Protocol Micro and the Echo-A play the bytes, so a wrapped
     * one would be a different filter. The TRN Black Pearl plays the registers like the Max (its record says it computes shelves from the registers and does not play the band-frame bytes), but the wrapped band was measured and enabled on the Max only, so on the TRN this stays false and an overflowing band is refused.
     */
    val wrapQ30: Boolean = false,
) {
    /** The gain a band of [type] may have: the device range, narrowed to [shelfLimits] for a shelf. */
    fun gainRange(type: FilterType): ClosedFloatingPointRange<Double> =
        if (type.isShelf && shelfLimits != null) maxOf(gainMinDb, shelfLimits.gainMinDb)..minOf(gainMaxDb, shelfLimits.gainMaxDb) else gainMinDb..gainMaxDb

    /** The register-over-intended Q factor for a band of [type]: shelves and peaks have their own, pass filters play the register as Q. */
    fun qScale(type: FilterType): Double = when {
        type.isShelf -> shelfQScale
        type == FilterType.PEAK -> peakQScale
        else -> 1.0
    }

    /** The intended (acoustic) Q range for a band of [type]: the register Q range divided by [qScale]. */
    fun qRange(type: FilterType): ClosedFloatingPointRange<Double> = qScale(type).let { qMin / it..qMax / it }.let { r ->
        if (type.isShelf && shelfLimits != null) maxOf(r.start, shelfLimits.qMin)..minOf(r.endInclusive, shelfLimits.qMax) else r
    }
}

/** Shelf limits narrower than the device's own: gain in dB and the INTENDED Q (not the register Q). */
data class ShelfLimits(val gainMinDb: Double, val gainMaxDb: Double, val qMin: Double, val qMax: Double)

/** A profile translated for the device: 8 band writes plus the preamp, or the reasons it does not fit. */
sealed interface DevicePlan {
    data class Ready(val bands: List<WalkPlay.BandWrite>, val preampDb: Int) : DevicePlan
    data class Rejected(val issues: List<String>) : DevicePlan
}

/** Register-matching reconstruction; original coefficient bytes are not guaranteed, or an import error. */
sealed interface DacImport {
    /** [warning]: the DAC holds something Contour would refuse to send; the profile loads, sending it stays blocked. */
    data class Ready(val eq: ImportedEq, val warning: String? = null) : DacImport
    data class Rejected(val issues: List<String>) : DacImport
}

/**
 * A dB value for a refusal text: whole numbers without a decimal point, otherwise rounded to 4 decimals. [stillRefused]
 * says whether a (rounded) number is still outside the rule; when rounding would land inside, the exact value is printed
 * so the number in the text itself fails the rule.
 */
internal fun dbText(x: Double, stillRefused: (Double) -> Boolean = { true }): String {
    if (!x.isFinite()) return x.toString()
    if (kotlin.math.abs(x) >= 1e9) return java.math.BigDecimal.valueOf(x).toPlainString()
    val r = Math.round(x * 10000) / 10000.0
    if (!stillRefused(r)) return java.math.BigDecimal.valueOf(x).stripTrailingZeros().toPlainString()
    return if (r == Math.rint(r)) r.toLong().toString() else r.toString()
}

/**
 * Why a send is refused, as the editor shows it after "SEND BLOCKED:" (the profile itself is never changed).
 * The refusals only ever block: a profile that passes is sent byte for byte as before.
 */
internal object SendBlocked {
    /** [device] is the caps name; [number] is the band's row in the editor, [type] the band as the user set it. */
    fun band(device: String, number: Int, type: FilterType): String {
        val hint = if (type == FilterType.HIGH_SHELF) "Reduce the boost, raise the shelf frequency or adjust Q."
            else "Reduce the boost or adjust frequency/Q."
        return "${device.removePrefix("CrinEar Protocol ")} band $number cannot be represented safely. $hint"
    }

    /** True for the refusal of a band that does not fit signed Q30 ([band]). */
    fun isBandRefusal(issue: String): Boolean = " cannot be represented safely." in issue

    /** AUTO needs [neededDb] of attenuation but the device takes down to [minDb] only. */
    fun auto(neededDb: Int, minDb: Int): String =
        "this EQ needs $neededDb dB; Contour supports down to $minDb dB for this device. Reduce the combined boosts."
}

/**
 * TRN Black Pearl (WalkPlay SchemeNo16): its LOW PASS and HIGH PASS. The TRN stores both as sent (written and read back from a PC, 19.10.2026; runs RUN-TRN1..3 of the evidence matrix: LP and HP pass on the
 * PC) and the record holds them in `peq.heldTypes`; like the Micro's ([ProtocolMicro.PASS_FILTERS_VERIFIED]) and the SnowSky Melody's, they are offered behind one flag and [Proven.on]: a review build offers
 * them, a release does not until a phone has taken them.
 */
object ProtocolTrn {
    private val CAPS_PLAIN: DeviceCapabilities = capabilities(false)
    private val CAPS_WITH_PASS: DeviceCapabilities = capabilities(true)

    /** The caps the app plans with now: the record's, plus LOW PASS and HIGH PASS where [passFiltersOn]. */
    val CAPABILITIES: DeviceCapabilities get() = if (passFiltersOn) CAPS_WITH_PASS else CAPS_PLAIN

    /** LOW PASS and HIGH PASS are offered on the TRN: proven ([PASS_FILTERS_VERIFIED]) or a review build ([Proven.on]). */
    val passFiltersOn: Boolean get() = Proven.on(PASS_FILTERS_VERIFIED)

    /**
     * THE verification flag of LOW PASS and HIGH PASS on the TRN Black Pearl in the app. False until a phone has written them and read them back (the PC bench has: 19.10.2026): the TRN then
     * offers PEAK and the shelves only, as released. One line to enable: set this to true.
     */
    const val PASS_FILTERS_VERIFIED = false

    /** The TRN's record caps; with [passFilters] LOW PASS and HIGH PASS are offered too (the tests build both). */
    fun capabilities(passFilters: Boolean): DeviceCapabilities = GeneratedDevices.TRN_BLACK_PEARL.caps().let { base ->
        if (passFilters) base.copy(types = base.types + setOf(FilterType.LOW_PASS, FilterType.HIGH_PASS)) else base
    }
}

/**
 * CrinEar Protocol Max (WalkPlay SchemeNo16). What a unit measured on the Mac (dac-lab runs 2026-11-10-crinear-protocol-max-07 to -14) says about it: the DSP plays the REGISTERS (frequency, gain, Q) at the stream
 * rate and not the coefficient bytes, and a shelf plays Q = register / sqrt(2) (the record's `shelfQScale`, the TRN's convention, in every build). Three things were CANDIDATES behind one flag,
 * [CANDIDATES_VERIFIED], and [Proven]: LOW PASS and HIGH PASS (native codes 4 and 5, the TRN's frames; they played within 0.1 dB, runs 12 and 20), gain up to +-[CANDIDATE_GAIN_DB] dB for peaks and shelves
 * (registers kept, run 08; -12 dB cuts and +9 dB boosts played, runs 16 and 19; devicePEQ's profile stops at +-10) and shelf boosts whose coefficient bytes overflow signed Q30 (sent wrapped to int32 as
 * devicePEQ sends them, [DeviceCapabilities.wrapQ30]; the unit plays the registers, run 16). The flag is true since the phone run of 10.11.2026 (dac-lab run 2026-11-10-crinear-protocol-max-22-phone: a full edge
 * profile with all three, VERIFIED, byte-identical after a replug), so a release offers them and the record states them (`peq.types`, `peq.gainDb`); the wrap is code, not data. Set false, a release goes back to PEAK
 * and the two shelves at +-10 dB with the refusal of an overflowing shelf. The bench's `--pass-filters`, `--wide-gain` and `--wrap-q30` ([capabilities]) add the candidates one by one on top of that.
 */
object ProtocolMax {
    /** The edge beyond devicePEQ's +-10 dB. */
    const val CANDIDATE_GAIN_DB = 12.0

    /** devicePEQ's gain edge: what a Max offers while the candidates are off. */
    private const val VENDOR_GAIN_DB = 10.0

    private val CAPS_PLAIN: DeviceCapabilities = capabilities(passFilters = false, wideGain = false, wrapQ30 = false)
    private val CAPS_CANDIDATES: DeviceCapabilities = capabilities(passFilters = true, wideGain = true, wrapQ30 = true)

    /** The caps the app plans with now: the record's (LOW PASS, HIGH PASS, +-12 dB) with wrapped Q30 where [candidatesOn]; without it PEAK and the shelves at +-10 dB, no wrap. */
    val CAPABILITIES: DeviceCapabilities get() = if (candidatesOn) CAPS_CANDIDATES else CAPS_PLAIN

    /** LOW PASS, HIGH PASS, +-12 dB and wrapped shelf boosts are offered on the Max: proven ([CANDIDATES_VERIFIED], true) or a review build ([Proven.on]). */
    val candidatesOn: Boolean get() = Proven.on(CANDIDATES_VERIFIED)

    /**
     * THE verification flag of the Max candidates above. True since the phone run of 10.11.2026 (dac-lab run 2026-11-10-crinear-protocol-max-22-phone: the edge profile with LOW PASS, HIGH PASS, +-12 dB
     * peaks and shelves and a wrapped shelf boost written, VERIFIED, and read back byte for byte after an unplug), after the Mac had played them. One line to disable: set this to false.
     */
    const val CANDIDATES_VERIFIED = true

    /** The Max's record caps (which state LOW PASS, HIGH PASS and +-12 dB), narrowed to PEAK, the shelves and +-10 dB unless [passFilters] and [wideGain]; wrapped Q30 overflow when [wrapQ30]. */
    fun capabilities(passFilters: Boolean, wideGain: Boolean, wrapQ30: Boolean = false): DeviceCapabilities = GeneratedDevices.CRINEAR_PROTOCOL_MAX.caps().let { base ->
        base.copy(
            types = if (passFilters) base.types + setOf(FilterType.LOW_PASS, FilterType.HIGH_PASS) else base.types - setOf(FilterType.LOW_PASS, FilterType.HIGH_PASS),
            gainMinDb = if (wideGain) -CANDIDATE_GAIN_DB else -VENDOR_GAIN_DB,
            gainMaxDb = if (wideGain) CANDIDATE_GAIN_DB else VENDOR_GAIN_DB,
            wrapQ30 = wrapQ30,
        )
    }
}

/** CrinEar Protocol Micro (WalkPlay SchemeNo11). Unofficial; not affiliated with CrinEar. */
object ProtocolMicro {
    /**
     * The shelf form the Micro's planner works in: [WalkPlay.scaledCoefficients] computes the shelf in the slope form (S = Q), and the Protocol Micro and the Moondrop Echo-A, the DACs this planner serves, play those coefficient bytes. The
     * one named constant for "the form the coefficient bytes have": the planner takes no form parameter, and the two records say the same (`peq.shelfForm` "slope").
     */
    val COEFFICIENT_SHELF_FORM: ShelfForm = ShelfForm.SLOPE

    // The caps are the record's (devices/crinear-protocol-micro.json). HIGH_SHELF has no native register type we trust on SchemeNo11: it is
    // offered, sent as an emulated LOW_SHELF plus preamp (see [plan]); LP/HP are unverified, so held in the record and not offered.
    private val CAPS_PLAIN: DeviceCapabilities = capabilities(false)
    private val CAPS_WITH_PASS: DeviceCapabilities = capabilities(true)

    /** The caps the app plans with now: the record's, plus LOW PASS and HIGH PASS where [passFiltersOn]. */
    val CAPABILITIES: DeviceCapabilities get() = if (passFiltersOn) CAPS_WITH_PASS else CAPS_PLAIN

    /** LOW PASS and HIGH PASS are offered on the Micro: proven ([PASS_FILTERS_VERIFIED]) or a review build ([Proven.on]). */
    val passFiltersOn: Boolean get() = Proven.on(PASS_FILTERS_VERIFIED)

    /** A positive preamp (up to [VENDOR_PREAMP_MAX_DB]) is taken on the Micro: proven ([MICRO_POSITIVE_PREAMP_VERIFIED]) or a review build ([Proven.on]). */
    val microPositivePreampOn: Boolean get() = Proven.on(MICRO_POSITIVE_PREAMP_VERIFIED)

    /** The same on the Echo-A, which runs the Micro's code but has its own evidence: proven ([ECHO_A_POSITIVE_PREAMP_VERIFIED]) or a review build ([Proven.on]). */
    val echoAPositivePreampOn: Boolean get() = Proven.on(ECHO_A_POSITIVE_PREAMP_VERIFIED)

    /** Device preamp limits (whole dB), from the record's `preamp.db`. */
    const val PREAMP_MIN_DB: Int = GeneratedDevices.CRINEAR_PROTOCOL_MICRO_PREAMP_MIN_DB
    const val PREAMP_MAX_DB: Int = GeneratedDevices.CRINEAR_PROTOCOL_MICRO_PREAMP_MAX_DB

    /**
     * THE verification flag of LOW PASS and HIGH PASS on the Protocol Micro (WalkPlay web tool, SchemeNo11: PK, LS, LP, HP; walkplay-plan.md item 3). True since the desk
     * measurement of 25.10.2026 at a 48 kHz stream (run 2026-10-25-crinear-protocol-micro-07-mac-eqtest-48k: LP 8000 Hz and HP 100 Hz PASS, with PK, shelves and preamp -6). False would
     * offer PEAK and the shelves only. The Echo-A has them in its record; its whole write is behind [io.github.chronosauros.contour.core.native.WalkPlayProfiles.ECHO_A_HARDWARE_VERIFIED].
     */
    const val PASS_FILTERS_VERIFIED = true

    /**
     * THE verification flag of a POSITIVE preamp on the Protocol Micro. The vendor's "Volume" slider (the EQ preamp) runs -16..+6 dB in 1 dB steps
     * (walkplay-plan.md item 5, decoded vendor code). Evidence: +6 dB reached the unit from the PC (T08) and from the phone;
     * the desk measurement above 0 dB is still open (only -6 dB measured), and the plan forbids a preamp above 0 dB without a low volume.
     * False: the preamp stays -30..0 as released. The lower bound never changes. One line to enable: set this to true.
     * Measured 25.10.2026 at 48 kHz: preamp +3 is accepted by the register and reads back, but the level does not rise (+0.00 dB measured), so it stays off
     * (run 2026-10-25-crinear-protocol-micro-07-mac-eqtest-48k).
     */
    const val MICRO_POSITIVE_PREAMP_VERIFIED = false

    /**
     * THE verification flag of a POSITIVE preamp on the Moondrop Echo-A (the Micro's code, its own evidence). Split from the Micro's on 25.10.2026: the Echo-A
     * has been sent -30..0 dB only (PC run EA4, phone runs EP1 and 2026-10-25-moondrop-echo-a-phone-nothing), +1..+6 dB never; it has no vendor software to read the slider from. False: the preamp stays -30..0.
     */
    const val ECHO_A_POSITIVE_PREAMP_VERIFIED = false

    /** The vendor slider's top (walkplay-plan.md item 5), whole dB. */
    const val VENDOR_PREAMP_MAX_DB = 6

    /** The top of the Micro's device preamp the app takes now: the record's until [microPositivePreampOn], then the vendor's. */
    val PREAMP_MAX_EFFECTIVE_DB: Int get() = if (microPositivePreampOn) VENDOR_PREAMP_MAX_DB else PREAMP_MAX_DB

    /** The top of the Echo-A's device preamp the app takes now: the record's until [echoAPositivePreampOn], then the vendor's. */
    val ECHO_A_PREAMP_MAX_EFFECTIVE_DB: Int get() = if (echoAPositivePreampOn) VENDOR_PREAMP_MAX_DB else PREAMP_MAX_DB

    /** The Micro's record caps; with [passFilters] LOW PASS and HIGH PASS are offered too (the bench and the tests build both). */
    fun capabilities(passFilters: Boolean): DeviceCapabilities = GeneratedDevices.CRINEAR_PROTOCOL_MICRO.caps().let { base ->
        if (passFilters) base.copy(types = base.types + setOf(FilterType.LOW_PASS, FilterType.HIGH_PASS)) else base
    }

    private fun fmt(x: Double): String = if (x == Math.rint(x)) x.toLong().toString() else x.toString()

    /**
     * A HIGH SHELF band as the Protocol Micro gets it: a LOW SHELF with the same frequency and Q and the
     * negated gain. Exact up to a constant by the RBJ identity H_HS(A) = A^2 * H_LS(1/A) (it survives the
     * bilinear transform and devicePEQ's shelf-slope alpha, which is symmetric in A and 1/A); the constant,
     * the HS gain, goes into the device preamp.
     */
    fun deviceBands(bands: List<Band>): List<Band> = bands.map {
        if (it.type == FilterType.HIGH_SHELF) it.copy(type = FilterType.LOW_SHELF, gainDb = -it.gainDb) else it
    }

    /** Sum of the gains of the enabled HIGH SHELF bands (what the emulation moves into the preamp). */
    fun highShelfGainSum(bands: List<Band>): Double =
        bands.filter { it.enabled && it.type == FilterType.HIGH_SHELF }.sumOf { it.gainDb }

    /**
     * The one rule for an explicit (curve-domain) [preampDb]: with `d = preamp + sum of HS gains` the register is
     * `floor(d + 1e-6)` and must be in [-30, 0]. So 0 < d < 1 is sent as register 0 (never louder than asked), d >= 1
     * is refused. The sum can be negative, so the register can sit above or below the curve value.
     * [plan], the importer, the PREAMP controls and the editor all ask this, so they cannot drift apart.
     */
    fun preampFits(bands: List<Band>, preampDb: Double, maxDb: Int = PREAMP_MAX_EFFECTIVE_DB): Boolean = deviceFits(preampDb + highShelfGainSum(bands), maxDb)

    private fun deviceFits(d: Double, maxDb: Int): Boolean =
        d.isFinite() && kotlin.math.floor(d + 1e-6).let { it >= PREAMP_MIN_DB && it <= maxDb }

    /** Why [preampDb] does not fit (null when it does), naming the curve-domain number the editor shows and, with a HIGH SHELF, the device's. */
    fun preampRefusal(bands: List<Band>, preampDb: Double, maxDb: Int = PREAMP_MAX_EFFECTIVE_DB): String? {
        if (preampFits(bands, preampDb, maxDb)) return null
        val hs = highShelfGainSum(bands)
        val head = "Preamp ${dbText(preampDb) { !preampFits(bands, it, maxDb) }} dB"
        val device = { d: Double -> !deviceFits(d, maxDb) }
        return if (hs == 0.0) "$head outside $PREAMP_MIN_DB..$maxDb dB range"
        else "$head needs device preamp ${dbText(preampDb + hs, device)} dB (HIGH SHELF adjustment ${if (hs > 0) "+" else ""}${dbText(hs)} dB), outside $PREAMP_MIN_DB..$maxDb dB range"
    }

    /**
     * The device preamp for [bands] with the user's preamp [preampDb] (null = AUTO), in whole dB:
     * AUTO -> `-ceil(max(device-domain curve) - 1e-6)` when that max is > 0, else 0;
     * manual -> `floor(preamp + sum of HS gains + 1e-6)`; then clamped to [-30, 0].
     * [plan] rejects out-of-range manual values, and AUTO below the floor, so the clamp only ever shapes the display.
     */
    fun devicePreamp(bands: List<Band>, preampDb: Double?, maxDb: Int = PREAMP_MAX_EFFECTIVE_DB): Int {
        val raw = if (preampDb == null) {
            Preamp.auto(deviceBands(bands), COEFFICIENT_SHELF_FORM)
        } else {
            kotlin.math.floor(preampDb + highShelfGainSum(bands) + 1e-6).toInt()
        }
        return raw.coerceIn(PREAMP_MIN_DB, maxDb)
    }

    /**
     * MAX VOLUME (owner 25.11.2026, Reddit: the Micro has no volume rocker and plays at full scale, so 20 % of the phone's volume was already very loud): one fixed cut kept
     * for the attached Micro and added under the device preamp of every profile at send time, AUTO or MANUAL alike, so a change of profile never changes the level. Whole dB,
     * [TRIM_MIN_DB]..0; it goes out in the preamp frame of the proven write sequence, so the DAC keeps it in flash with the EQ.
     */
    const val TRIM_MIN_DB: Int = PREAMP_MIN_DB

    /**
     * The register a send writes for a profile whose own register is [register], with [trimDb] under it: never below the floor. At the floor the clamp eats the trim,
     * never the profile's own preamp, so a profile never plays louder with a trim than without one. [trimDb] 0 leaves [register] as it is.
     */
    fun trimmedRegister(register: Int, trimDb: Int): Int {
        require(trimDb in TRIM_MIN_DB..0) { "MAX VOLUME $trimDb dB outside $TRIM_MIN_DB..0 dB" }
        return if (trimDb == 0) register else maxOf(PREAMP_MIN_DB, register + trimDb)
    }

    /**
     * Maps [bands] to the 8 device slots in order: enabled bands take slots 0..n-1 (HIGH SHELF emulated as
     * LOW SHELF, see [deviceBands]), disabled and unused slots become that slot's factory flat band.
     * [preampDb] = null means AUTO (see [devicePreamp]). [trimDb] is the DAC's MAX VOLUME ([trimmedRegister]): it moves only the preamp
     * register of a plan the profile alone would get, so every refusal stays the profile's own.
     */
    fun plan(bands: List<Band>, preampDb: Double?, caps: DeviceCapabilities = CAPABILITIES, maxDb: Int = PREAMP_MAX_EFFECTIVE_DB, trimDb: Int = 0): DevicePlan {
        require(trimDb in TRIM_MIN_DB..0) { "MAX VOLUME $trimDb dB outside $TRIM_MIN_DB..0 dB" }
        val plan = planUntrimmed(bands, preampDb, caps, maxDb)
        return if (trimDb == 0 || plan !is DevicePlan.Ready) plan else DevicePlan.Ready(plan.bands, trimmedRegister(plan.preampDb, trimDb))
    }

    private fun planUntrimmed(bands: List<Band>, preampDb: Double?, caps: DeviceCapabilities, maxDb: Int): DevicePlan {
        val issues = ArrayList<String>()
        val active = bands.filter { it.enabled }
        if (active.size > caps.bands) issues += "${active.size} enabled bands; ${caps.name} has ${caps.bands}"
        bands.forEachIndexed { i, b ->
            if (!b.enabled) return@forEachIndexed
            val n = "Band ${i + 1}"
            if (b.type !in caps.types) issues += "$n: ${b.type} is not supported by ${caps.name}"
            if (!(b.freqHz >= caps.freqMinHz && b.freqHz <= caps.freqMaxHz)) {
                issues += "$n: ${fmt(b.freqHz)} Hz outside ${fmt(caps.freqMinHz)}-${fmt(caps.freqMaxHz)} Hz"
            }
            // A pass filter the device offers has no gain (0 goes out, see [WalkPlay.bandWrite]): its stored gain is not held to the range. One the device
            // does not offer is refused above, with its gain checked as before.
            val noGain = b.type.isPass && b.type in caps.types
            if (!noGain && !(b.gainDb >= caps.gainMinDb && b.gainDb <= caps.gainMaxDb)) {
                issues += "$n: ${fmt(b.gainDb)} dB outside ${fmt(caps.gainMinDb)}..${fmt(caps.gainMaxDb)} dB"
            }
            val qRange = caps.qRange(b.type)
            if (!(b.q >= qRange.start && b.q <= qRange.endInclusive)) {
                issues += "$n: Q ${fmt(b.q)} outside ${fmt(qRange.start)}-${fmt(qRange.endInclusive)}"
            }
        }
        if (preampDb != null && !preampDb.isFinite()) issues += "Preamp $preampDb dB is not a number"
        if (preampDb != null && preampDb.isFinite()) preampRefusal(active, preampDb, maxDb)?.let { issues += it }
        if (issues.isNotEmpty()) return DevicePlan.Rejected(issues)
        val dev = deviceBands(active)
        val writes = List(caps.bands) { slot ->
            if (slot < dev.size) WalkPlay.bandWrite(slot, dev[slot], caps) else WalkPlay.factoryFlat(slot)
        }
        // Slots follow the enabled bands in order; the editor numbers every band, enabled or not.
        val rows = bands.indices.filter { bands[it].enabled }
        writes.forEachIndexed { slot, write ->
            // The emulated LOW SHELF of a HIGH SHELF is what goes out, so that is what is checked (original and
            // quantised values); a band that does not fit is refused, never wrapped.
            when (val failure = runCatching { WalkPlay.bandWriteReport(write, 0) }.exceptionOrNull()) {
                null -> {}
                is WalkPlay.Q30Overflow ->
                    issues += SendBlocked.band(caps.name, (rows.getOrNull(slot) ?: slot) + 1, active.getOrNull(slot)?.type ?: FilterType.PEAK)
                else -> issues += "Band ${slot + 1}: non-finite biquad coefficients; cannot send to ${caps.name}"
            }
        }
        if (issues.isNotEmpty()) return DevicePlan.Rejected(issues)
        val deviceGain = if (preampDb == null) {
            val needed = runCatching { Preamp.auto(dev, COEFFICIENT_SHELF_FORM) }.getOrElse {
                return DevicePlan.Rejected(listOf("AUTO preamp: no finite response; cannot send to ${caps.name}"))
            }
            // AUTO that needs more than the device takes is refused with the number, not sent as -30.
            if (needed < PREAMP_MIN_DB) return DevicePlan.Rejected(listOf(SendBlocked.auto(needed, PREAMP_MIN_DB)))
            needed
        } else runCatching { devicePreamp(active, preampDb, maxDb) }.getOrElse {
            return DevicePlan.Rejected(listOf("AUTO preamp: no finite response; cannot send to ${caps.name}"))
        }
        return DevicePlan.Ready(writes, deviceGain)
    }

    fun plan(profile: Profile, caps: DeviceCapabilities = CAPABILITIES, maxDb: Int = PREAMP_MAX_EFFECTIVE_DB, trimDb: Int = 0): DevicePlan =
        plan(profile.bands, profile.preampDb, caps, maxDb, trimDb)

    /** True when the device registers [regs] (8 bands) and [preampDb] are exactly what [plan] writes. */
    fun matches(plan: DevicePlan.Ready, regs: List<WalkPlay.Registers>, preampDb: Int): Boolean =
        regs.size == plan.bands.size && plan.preampDb == preampDb &&
            plan.bands.indices.all { plan.bands[it].registers() == regs[it] }

    /** AUTO's refusal for [bands] (the device-domain preamp it needs is below the floor), or null when AUTO fits or has no response. */
    fun autoRefusal(bands: List<Band>): String? {
        val needed = runCatching { Preamp.auto(deviceBands(bands.filter { it.enabled }), COEFFICIENT_SHELF_FORM) }.getOrNull() ?: return null
        return if (needed < PREAMP_MIN_DB) SendBlocked.auto(needed, PREAMP_MIN_DB) else null
    }

    /**
     * Invert register quantisation and validate the re-encoded registers; coefficients are not compared. The native
     * codes accepted are PK, the LOW SHELF code (HIGH SHELF is only ever sent as that) and the pass codes [caps] offers.
     *
     * With a MAX VOLUME [trimDb] the preamp comes back as the profile's own, the register less the trim, so a read sent back is not cut twice. At the floor the clamp
     * may have eaten part of the trim: the loudest preamp that writes the same register is taken (a -12 dB profile under a -20 dB trim reads back as -10). A register above
     * what any send under the trim writes (the trim set, nothing sent since, or another tool's write) was not written under it: it comes back as it is.
     */
    fun importExact(bands: List<WalkPlay.DeviceBand>, preampDb: Int, caps: DeviceCapabilities = CAPABILITIES, maxDb: Int = PREAMP_MAX_EFFECTIVE_DB, trimDb: Int = 0): DacImport {
        require(trimDb in TRIM_MIN_DB..0) { "MAX VOLUME $trimDb dB outside $TRIM_MIN_DB..0 dB" }
        val own = preampDb - trimDb
        // own in range: trimmedRegister(own, trimDb) == preampDb, so the import's own re-encode check against [own] is the check against the register read
        return if (trimDb != 0 && preampDb >= PREAMP_MIN_DB && own <= maxDb) importUntrimmed(bands, own, caps, maxDb)
            else importUntrimmed(bands, preampDb, caps, maxDb)
    }

    private fun importUntrimmed(bands: List<WalkPlay.DeviceBand>, preampDb: Int, caps: DeviceCapabilities, maxDb: Int): DacImport {
        if (bands.size != WalkPlay.BANDS || bands.indices.any { bands[it].registers.index != it }) {
            return DacImport.Rejected(listOf("Expected all 8 DAC slots in order"))
        }
        if (preampDb !in PREAMP_MIN_DB..maxDb) {
            return DacImport.Rejected(listOf("DAC preamp $preampDb dB outside $PREAMP_MIN_DB..$maxDb dB"))
        }
        // Keep one factory-flat band editable when the entire DAC is flat.
        val native = setOf(WalkPlay.TYPE_PK, WalkPlay.TYPE_LSQ) + caps.types.filter { it.isPass }.map(WalkPlay::typeCode)
        val lastUsed = maxOf(0, bands.indexOfLast { it.registers != WalkPlay.factoryFlat(it.registers.index).registers() })
        val reconstructed = ArrayList<Band>()
        for (slot in 0..lastUsed) {
            val deviceBand = bands[slot]
            val r = deviceBand.registers
            if (!deviceBand.enabled || r.typeCode !in native) {
                return DacImport.Rejected(listOf("DAC band ${slot + 1}: disabled or unsupported native type ${r.typeCode}; cannot import exactly"))
            }
            // A pass filter has no gain: a non-zero gain register is not something Contour wrote.
            if (deviceBand.type.isPass && r.gain256 != 0) {
                return DacImport.Rejected(listOf("DAC band ${slot + 1}: pass filter with a gain register; cannot import exactly"))
            }
            // Whole Hz and Q in 1/256 steps: exactly what the registers hold and CrinEar's tool shows.
            val frequency = r.freq.toDouble()
            val q = WalkPlay.intendedQ(r.q256, deviceBand.type, caps)
            reconstructed += Band("dac$slot", deviceBand.type, frequency, r.gain256 / 256.0, q)
        }
        val eq = ImportedEq(reconstructed, preampDb.toDouble())
        val candidate = plan(eq.bands, eq.preampDb, caps, maxDb)
        if (candidate is DevicePlan.Rejected && candidate.issues.all { SendBlocked.isBandRefusal(it) }) {
            // The DAC already holds a band Contour would refuse to send (written by another tool): load what it holds,
            // with the refusal as a warning; the plan stays Rejected, so sending it back stays blocked.
            val writes = List(WalkPlay.BANDS) { slot -> if (slot < reconstructed.size) WalkPlay.bandWrite(slot, reconstructed[slot], caps) else WalkPlay.factoryFlat(slot) }
            if (matches(DevicePlan.Ready(writes, preampDb), bands.map { it.registers }, preampDb)) {
                return DacImport.Ready(eq, candidate.issues.joinToString(" ") + " Sending it back stays blocked until you change the band.")
            }
        }
        if (candidate !is DevicePlan.Ready || !matches(candidate, bands.map { it.registers }, preampDb)) {
            val issue = if (candidate is DevicePlan.Rejected) candidate.issues.joinToString("; ")
                else "quantised registers differ on re-encode"
            return DacImport.Rejected(listOf("DAC state cannot be imported exactly: $issue"))
        }
        return DacImport.Ready(eq)
    }

    /** "This profile is on the DAC": its plan produces exactly the registers read back. */
    fun matches(profile: Profile, regs: List<WalkPlay.Registers>, preampDb: Int, trimDb: Int = 0): Boolean =
        (plan(profile, trimDb = trimDb) as? DevicePlan.Ready)?.let { matches(it, regs, preampDb) } ?: false

    /** The factory flat state (every slot flat, preamp 0). */
    fun isFlat(regs: List<WalkPlay.Registers>, preampDb: Int): Boolean = matches(flatPlan(), regs, preampDb)

    /** Factory flat on every slot, preamp 0: what the device held on 25.09.2026. */
    fun flatPlan(): DevicePlan.Ready = DevicePlan.Ready(List(WalkPlay.BANDS) { WalkPlay.factoryFlat(it) }, 0)
}
