package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Proven
import io.github.chronosauros.contour.core.devices.GeneratedDevices

/**
 * The FiiO JadeAudio JA11's pass filters. FiiO Control lists three types for the JA11 (filterList 0-2: PEAK 0, LOW_SHELF 1, HIGH_SHELF 2). The unit also took LOW PASS (4) and HIGH PASS (5), the type bytes the KA15, Melody,
 * Tiny A and K13 R2R take, and they play as pass filters (dac-lab runs 2026-11-11-fiio-ja11-03, -04, -06 and -07 on the Mac); the record states them in `peq.types`. Like the Protocol Max's candidates ([io.github.chronosauros.contour.core.ProtocolMax.CANDIDATES_VERIFIED])
 * they are behind one flag and [Proven]: set false, a release goes back to PEAK and the two shelves. BAND PASS (3) and ALL PASS (6) are not offered at all: BP plays flat (checked twice) and AP cannot be told from an ignored type.
 */
object FiioJa11 {
    /**
     * THE verification flag of LOW PASS and HIGH PASS on the FiiO JA11. True since the Mac runs of 11.11.2026 (the unit took both at the edges and at 1 kHz, read them back as types 4 and 5, and the sweep measured a low pass at 4 kHz within 0.06 dB and
     * high passes at 60 and 150 Hz). One line to disable: set this to false. The morning's phone run flips the record's `hardwareVerified`, not this.
     */
    const val PASS_FILTERS_VERIFIED = true

    /** LOW PASS and HIGH PASS are offered on the JA11: proven ([PASS_FILTERS_VERIFIED]) or a review build ([Proven.on]). */
    val passFiltersOn: Boolean get() = Proven.on(PASS_FILTERS_VERIFIED)

    private val PASS_TYPES = setOf(FilterType.LOW_PASS, FilterType.HIGH_PASS)

    /** The JA11's record config, which states LOW PASS and HIGH PASS. */
    private val RECORD: FiioConfig = GeneratedDevices.FIIO_JA11
    private val WITHOUT_PASS: FiioConfig = RECORD.copy(typeCodes = RECORD.typeCodes - PASS_TYPES)

    /** The config the app drives the JA11 with now: the record's, without LOW PASS and HIGH PASS where the flag is off in a release. */
    val CONFIG: FiioConfig get() = if (passFiltersOn) RECORD else WITHOUT_PASS

    /** True for the JA11's record itself, the config [CONFIG] stands in for. */
    internal fun isRecord(c: FiioConfig): Boolean = c === RECORD
}
