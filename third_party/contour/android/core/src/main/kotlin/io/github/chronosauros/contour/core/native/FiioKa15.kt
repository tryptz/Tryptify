package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Proven
import io.github.chronosauros.contour.core.devices.GeneratedDevices

/**
 * The FiiO KA15's pass filters. FiiO's own app offers the KA15 all seven types (Contour 1.6 scope: filterList 0-6, PK 0, LS 1, HS 2, BP 3, LP 4, HP 5, AP 6, the same type bytes as the
 * SnowSky Melody, Tiny A and K13 R2R); a unit has now taken types 3-6 (see [PASS_FILTERS_VERIFIED]). (FreeDSP-Studio's dsp_fiio.rs, MIT, offers the KA15 only PK, LSQ, HSQ, LP and HP: the two sources differ
 * on BP and AP, and the bench settled it.) While the flag was false a release stayed on PEAK and the two shelves, in the app and in a read of the unit, and a review build offered the four through [Proven]
 * (like the Melody's did before its phone run); the PC bench offers and decodes them behind `--pass-filters` ([DeviceTarget.ka15]).
 */
object FiioKa15 {
    /**
     * THE verification flag of LOW PASS, HIGH PASS, BAND PASS and ALL PASS on the FiiO KA15. True since 25.10.2026: the phone sent all four and read them back as their own types (Nothing Phone, perf APK of
     * 0a99e04, run 2026-10-25-contour16-r7-phone-nothing: LP 4, HP 5, BP 3, AP 6, VERIFIED), after the PC bench had written them (run 2026-10-24-fiio-ka15-02-mac, S2 T07: every type written and read back) and
     * measured them (run 2026-10-24-fiio-ka15-06-mac-eqtest: LP 8000, HP 100, BP 1000, AP 1000, acoustic PASS). While false, a release hid them and a review build ([Proven.on]) offered them.
     * One line to disable: set this to false.
     */
    const val PASS_FILTERS_VERIFIED = true

    /** LOW PASS, HIGH PASS, BAND PASS and ALL PASS are offered on the KA15: proven ([PASS_FILTERS_VERIFIED]) or a review build ([Proven.on]). */
    val passFiltersOn: Boolean get() = Proven.on(PASS_FILTERS_VERIFIED)

    private val PLAIN: FiioConfig = GeneratedDevices.FIIO_KA15
    private val WITH_PASS: FiioConfig = PLAIN.copy(typeCodes = PLAIN.typeCodes + SnowSkyMelody.PASS_TYPE_CODES)

    /** The config the app drives the KA15 with now: the record's, plus the four pass types where [passFiltersOn]. */
    val CONFIG: FiioConfig get() = if (passFiltersOn) WITH_PASS else PLAIN

    /** The KA15's record config; with [passFilters] the four pass types are offered and decoded too (the bench and the tests build both). */
    fun config(passFilters: Boolean): FiioConfig = if (passFilters) WITH_PASS else PLAIN

    /** True for the KA15's record itself, the config [CONFIG] stands in for. */
    internal fun isRecord(c: FiioConfig): Boolean = c === PLAIN
}
