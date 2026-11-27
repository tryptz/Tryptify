package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Proven
import io.github.chronosauros.contour.core.devices.GeneratedDevices

/**
 * The SnowSky Melody's pass filters. FiiO Control lists all seven types for the Melody (filterList 0-6: PK 0, LS 1, HS 2, BP 3, LP 4, HP 5, AP 6), the same type bytes the Tiny A and the
 * K13 R2R take; the record holds BP, LP, HP and AP in `peq.heldTypes` until a unit had taken them, which the Melody did on the phone on 25.10.2026. Like the Protocol Micro's ([io.github.chronosauros.contour.core.ProtocolMicro]),
 * they are behind one flag and [Proven.on].
 */
object SnowSkyMelody {
    /**
     * THE verification flag of LOW PASS, HIGH PASS, BAND PASS and ALL PASS on the SnowSky Melody. True since 25.10.2026: the PC bench wrote them (T07) and the phone wrote and read back all four (Nothing Phone, run 2026-10-25-snowsky-melody-phone-nothing: types 4, 5, 3, 6, VERIFIED). While false, the Melody
     * offered PEAK and the two shelves only in the app and in a read of the unit, in a release build. One line to disable: set this to false.
     */
    const val PASS_FILTERS_VERIFIED = true

    /** BAND PASS, LOW PASS, HIGH PASS and ALL PASS are offered on the Melody: proven ([PASS_FILTERS_VERIFIED]) or a review build ([Proven.on]). */
    val passFiltersOn: Boolean get() = Proven.on(PASS_FILTERS_VERIFIED)

    /** The type bytes of the held types: FiiO Control's filterList for the Melody, and the Tiny A's and K13 R2R's own bytes (FiiO web app capture, 14.10.2026). */
    val PASS_TYPE_CODES: Map<FilterType, Int> = mapOf(FilterType.BAND_PASS to 3, FilterType.LOW_PASS to 4, FilterType.HIGH_PASS to 5, FilterType.ALL_PASS to 6)

    private val PLAIN: FiioConfig = GeneratedDevices.SNOWSKY_MELODY
    private val WITH_PASS: FiioConfig = PLAIN.copy(typeCodes = PLAIN.typeCodes + PASS_TYPE_CODES)

    /** The config the app drives the Melody with now: the record's, plus the four pass types where [passFiltersOn]. */
    val CONFIG: FiioConfig get() = if (passFiltersOn) WITH_PASS else PLAIN

    /** The Melody's record config; with [passFilters] the four pass types are offered and decoded too (the bench and the tests build both). */
    fun config(passFilters: Boolean): FiioConfig = if (passFilters) WITH_PASS else PLAIN

    /** True for the Melody's record itself, the config [CONFIG] stands in for. */
    internal fun isRecord(c: FiioConfig): Boolean = c === PLAIN
}
