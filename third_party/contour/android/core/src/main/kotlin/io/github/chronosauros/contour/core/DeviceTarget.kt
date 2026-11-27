package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.devices.GeneratedDevices
import io.github.chronosauros.contour.core.devices.Recognition
import io.github.chronosauros.contour.core.native.*

/**
 * The DAC the app talks to: a WalkPlay DAC ([walkplay]: Protocol Micro, Protocol Max, TRN Black Pearl) or a
 * FiiO ([fiio]: native FiiO HID protocol; the KA15 was hardware-tested on a Pixel 04.10.2026, the K13 R2R, the
 * SnowSky Melody and Tiny A are routed the same way). Family identity is separate from WalkPlay's wire representation; no native register coercion.
 *
 * Contour 1.6 adds three targets (and the JCally JM12, a second KT Micro) that only [findDevice] knows (they need the USB product name, and the 1.5 recognition list of [find] stays
 * as it was): the Moondrop Dawn Pro 2 ([moondrop], one bank), the Kiwi Ears Allegro Mini ([ktMicro], the DAC leaves the bus after SAVE) and the Moondrop
 * Echo-A ([echo]; the Protocol Micro's planner and write sequence drive it, held to the Echo-A's own limits). None of the three is proven on hardware for a write,
 * so each is [readOnly] in a release and writable in a review build ([Proven]).
 */
data class DeviceTarget private constructor(
    val walkplay: DeviceProtocol? = null,
    private val fiioRecord: FiioConfig? = null,
    val moondrop: MoondropCatalog.Model? = null,
    val ktMicro: KtMicroCatalog.Model? = null,
    val echo: WalkPlayProfile? = null,
    /** The Moondrop DUSK-SP (gen-1 protocol, report 1 over the control pipe): its own session, no preamp, nine bands. */
    val gen1: MoondropGen1Catalog.Model? = null,
) {
    /**
     * The FiiO config the app drives this DAC with now: the record's, except that the SnowSky Melody's follows its pass-filter gate
     * ([SnowSkyMelody.PASS_FILTERS_VERIFIED] or a review build, see [Proven]): LP, HP, BP and AP in the offered types and in what a read decodes.
     * The FiiO KA15's follows [FiioKa15.PASS_FILTERS_VERIFIED] or a review build ([FiioKa15.passFiltersOn], see [Proven]), like the Melody's: a release offered PEAK and the two shelves until a unit had taken the four (it has: the flag is true since 25.10.2026).
     * The FiiO JA11's follows [FiioJa11.PASS_FILTERS_VERIFIED] the same way (true: LOW PASS and HIGH PASS in every build; false, a release offers PEAK and the two shelves).
     */
    val fiio: FiioConfig? get() = fiioRecord?.let { if (SnowSkyMelody.isRecord(it)) SnowSkyMelody.CONFIG else if (FiioKa15.isRecord(it)) FiioKa15.CONFIG else if (FiioJa11.isRecord(it)) FiioJa11.CONFIG else it }

    /** Which family of code drives this DAC. */
    enum class Family { WALKPLAY, FIIO, MOONDROP, KT_MICRO, WALKPLAY_READ_ONLY, MOONDROP_GEN1 }
    val family: Family get() = when {
        walkplay != null -> Family.WALKPLAY
        fiio != null -> Family.FIIO
        moondrop != null -> Family.MOONDROP
        ktMicro != null -> Family.KT_MICRO
        gen1 != null -> Family.MOONDROP_GEN1
        else -> Family.WALKPLAY_READ_ONLY
    }
    /** One of the three Contour 1.6 targets (Dawn Pro 2, Allegro Mini, Echo-A): driven by its own :core session, not by FiiO's or WalkPlay's. */
    val isFamily: Boolean get() = walkplay == null && fiio == null
    /**
     * Read only: the app reads this DAC's EQ and never writes it. A 1.6 family DAC is read only until its write is proven on hardware (its own flag) or the build is a
     * review build ([Proven.on]): the Echo-A ([WalkPlayProfile.hardwareVerified]), the Dawn Pro 2 and the Allegro Mini ([MoondropCatalog.Model.hardwareVerified]).
     * A FiiO-family DAC is read only the same way while its record says `hardwareVerified` false (the JA11, until a unit has taken a write); the four proven FiiO records (KA15, K13 R2R, Melody, Tiny A) say true, so they are never read only.
     */
    val readOnly: Boolean get() = when {
        echo != null -> !echo.writeOn
        moondrop != null -> !Proven.on(moondrop.hardwareVerified)
        ktMicro != null -> !Proven.on(ktMicro.hardwareVerified)
        gen1 != null -> !Proven.on(gen1.hardwareVerified)
        walkplay != null -> !WalkPlayProfiles.of(walkplay).writeOn
        fiioRecord != null -> !Proven.on(fiioRecord.hardwareVerified)
        else -> false
    }
    /** The DAC has a preamp the app writes; the Allegro Mini has none (its Global Gain is the analog DAC gain, never written). */
    val hasPreamp: Boolean get() = ktMicro == null && gen1 == null
    /** What the preamp is called on this DAC: the Moondrop manufacturer's name for register 0x23. */
    val preampLabel: String get() = if (moondrop != null) "PRE GAIN" else "PREAMP"

    val supportsAb: Boolean get() = walkplay == DeviceProtocol.MICRO
    /** MAX VOLUME (a fixed cut under every profile, [ProtocolMicro.trimmedRegister]): the Protocol Micro only; the Echo-A runs the Micro's planner but is not given one. */
    val takesVolumeTrim: Boolean get() = walkplay?.takesVolumeTrim == true
    val experimental: Boolean get() = !supportsAb
    /** Micro, Protocol Max and TRN Black Pearl: the WalkPlay targets, with their editor/import/preamp policy. */
    val stable: Boolean get() = walkplay != null
    val native: Boolean get() = walkplay == null
    /** The WalkPlay profile of this target (Micro, Max, TRN, Echo-A), or null for a FiiO, a Dawn Pro 2 and an Allegro Mini. */
    val walkplayProfile: WalkPlayProfile? get() = echo ?: walkplay?.let { WalkPlayProfiles.of(it) }
    /**
     * What the DAC Settings page shows for this device; empty = no such page. A WalkPlay target shows [WalkPlaySettings.offered] (in a release the TRN's four
     * proven rows), a FiiO its list, a 1.6 family its [FamilySettings] rows.
     */
    val settings: List<DacSetting> get() = walkplayProfile?.let { WalkPlaySettings.offered(it) } ?: fiio?.settings ?: FamilySettings.of(this)
    /** Header reads of the DAC Settings page (sample rate, firmware), read with the settings; and the values the review preview shows (the hardware's). */
    val info: List<DacInfo> get() = fiio?.info.orEmpty()
    val settingsPreview: Map<String, Int> get() = fiio?.settingsPreview.orEmpty()
    /** KA15: the intended (acoustic) range, register range minus [FiioConfig.preampOffsetDb] (-24..0 dB). */
    /** Never below [Preamp.FLOOR_DB]: only the Dawn Pro 2's register goes lower (-128 dB), and Contour offers it down to -30 like the WalkPlay DACs. */
    val preampMin: Double get() = maxOf(Preamp.FLOOR_DB, fiio?.preampMinDb ?: walkplay?.preampMin?.toDouble() ?: moondrop?.preGainMinDb ?: echo?.protocol?.preampMin?.toDouble() ?: 0.0)
    val preampMax: Double get() = fiio?.preampMaxDb ?: walkplay?.preampMax?.toDouble() ?: moondrop?.preGainMaxDb ?: echo?.preampMaxDb?.toDouble() ?: 0.0
    /**
     * The grid the editor keeps a manual preamp on: 0.1 dB, or a whole dB for a DAC that keeps whole dB only ([DeviceCapabilities.preampWholeDb]: the KA15, SnowSky Melody
     * and Tiny A, and since 1.6.2 the WalkPlay targets - Protocol Micro, Protocol Max, TRN Black Pearl, Echo-A), so the number on screen is the number the DAC holds after a send.
     * On the WalkPlay targets the planner sends `floor(preamp + 1e-6)` (plus an emulated high shelf's gain, kept on the same grid by [highShelfStepDb]): a manual -2.5 dB went out
     * as -3 while the editor showed -2.5 (user report 27.10.2026).
     */
    val preampStep: Double get() = if (caps.preampWholeDb) 1.0 else 0.1
    /** A stored manual preamp as the DAC holds it: [preampDb] rounded down to [preampStep] (a value saved before the editor kept the grid, or one of a file). */
    fun heldPreamp(preampDb: Double): Double = if (preampStep == 0.1) preampDb else Preamp.floorTo(preampDb, preampStep)
    val caps: DeviceCapabilities get() = walkplay?.caps ?: fiio?.let { f ->
        DeviceCapabilities(f.productName, f.capture?.vendorId ?: 0, f.capture?.productId ?: 0, f.maxFilters,
            f.minGainDb, f.maxGainDb, f.minQ, f.maxQ, f.minFrequencyHz.toDouble(), f.maxFrequencyHz.toDouble(),
            f.typeCodes.keys, f.preampWholeDb, shelfForm = f.shelfForm, shelfQScale = f.shelfQScale, peakQScale = f.peakQScale)
    } ?: familyCaps()
    /** The limits of the 1.6 families, from their :core catalogs; only what Contour offers is in [DeviceCapabilities.types] (the Dawn Pro 2: PEAK, plus its shelves and pass filters behind their flags). */
    private fun familyCaps(): DeviceCapabilities = when {
        moondrop != null -> DeviceCapabilities(MOONDROP_NAME, MoondropCatalog.VENDOR_ID, moondrop.productIds.min(), moondrop.bandCount,
            moondrop.minGainDb, moondrop.maxGainDb, moondrop.minQ, moondrop.maxQ, moondrop.minFrequencyHz, moondrop.maxFrequencyHz, MOONDROP_TYPES, false, shelfForm = moondrop.shelfForm, shelfQScale = moondrop.shelfQScale)
        ktMicro != null -> DeviceCapabilities(ktMicro.name, ktMicro.data.vendorId, ktMicro.productIds.min(), ktMicro.bandCount,
            ktMicro.minGainDb, ktMicro.maxGainDb, ktMicro.minQ, ktMicro.maxQ, ktMicro.minFrequencyHz, ktMicro.maxFrequencyHz, ktMicro.nativeTypes, false, shelfForm = ktMicro.shelfForm, shelfQScale = ktMicro.shelfQScale, shelfLimits = ktMicro.shelfLimits)
        gen1 != null -> gen1.caps()
        // The Echo-A's record gives the band count, gain, Q and types; its frequency range is still "unknown" there (PendingRecords), and its preamp
        // policy is the Protocol Micro's, whose code drives it (see [WalkPlayProfiles.echoA]).
        else -> requireNotNull(echo).caps
    }
    /** The USER slot a send goes to when the app has not chosen one (KA15: USER1). */
    val destinationSlot: Int? get() = fiio?.userSlots?.minOrNull()
    /** The WalkPlay code whose preamp rules apply: the stable protocol, or the Protocol Micro's for a writable Echo-A (a read-only one keeps the plain range). */
    private val preampRules: DeviceProtocol? get() = walkplay ?: writableEcho?.protocol
    /** The Echo-A while its write is on ([WalkPlayProfiles.ECHO_A_HARDWARE_VERIFIED]); its preamp rules are the Protocol Micro's, held to the profile's own top. */
    private val writableEcho: WalkPlayProfile? get() = echo?.takeIf { it.writeOn }
    /** The preamp control, the clipboard import and the send checks follow the WalkPlay planner's rules for this target (Micro, Max, TRN, and an Echo-A whose write is on). */
    val usesWalkPlayRules: Boolean get() = preampRules != null
    fun shelfOffset(bands: List<Band>): Double = preampRules?.shelfOffset(bands) ?: 0.0
    /** The HIGH SHELF is emulated here: a LOW SHELF of the opposite gain plus the shelf gain folded into the preamp ([ProtocolMicro.deviceBands]): the Protocol Micro, and the Echo-A while its write is on. */
    val emulatesHighShelf: Boolean get() = walkplay == DeviceProtocol.MICRO || writableEcho != null
    /**
     * The grid the gain of an emulated HIGH SHELF is kept on, or null where the shelf is native: the preamp resolution of the DAC (a whole dB on the Micro and the Echo-A), because the
     * gain goes into the preamp register. Off the grid the register rounds down and the level ends below what the editor shows (Echo-A phone run 25.10.2026: -3 dB preamp and a -1.5 dB
     * shelf wrote -5, 0.5 dB low).
     */
    val highShelfStepDb: Double? get() = if (emulatesHighShelf) preampStep else null
    /** Does this stored explicit preamp fit the device with these bands? The planner's own rule (WalkPlay) or the KA15 range. */
    fun preampFits(bands: List<Band>, preampDb: Double): Boolean =
        writableEcho?.let { preampDb.isFinite() && ProtocolMicro.preampFits(bands, preampDb, it.preampMaxDb) }
            ?: preampRules?.preampFits(bands, preampDb) ?: (!hasPreamp || (preampDb.isFinite() && preampDb in preampMin..preampMax))
    /** Why [preampDb] does not fit with these [bands] (the planner's own refusal text), or null; null too for a target that does not use the WalkPlay rules. */
    fun preampRefusal(bands: List<Band>, preampDb: Double): String? =
        writableEcho?.let { e -> if (preampFits(bands, preampDb)) null else if (preampDb.isFinite()) ProtocolMicro.preampRefusal(bands, preampDb, e.preampMaxDb) else "Preamp $preampDb dB is not a number" }
            ?: preampRules?.preampRefusal(bands, preampDb)
    /** AUTO's refusal for [bands] (the preamp it needs is below the floor), or null; the WalkPlay rules only. */
    fun autoRefusal(bands: List<Band>): String? = preampRules?.autoRefusal(bands)
    /** The lowest 0.1 dB manual preamp whose register equals AUTO's (AUTO to MANUAL never changes the sound), or null; the WalkPlay rules only. */
    fun manualForAuto(bands: List<Band>): Double? = preampRules?.manualForAuto(bands)
    /**
     * Import of a file's explicit preamp, as [DeviceProtocol.fitImportedPreamp] does it, with this target's own [preampFits]: rounded DOWN to 0.1 dB, the exact
     * file value kept when rounding would flip the verdict. Nothing is capped.
     */
    fun fitImportedPreamp(bands: List<Band>, preampDb: Double): Double {
        if (!preampDb.isFinite()) return if (preampDb.isNaN()) 0.0 else if (preampDb > 0) preampMax else preampMin
        val down = Preamp.floorTo(preampDb, preampStep)
        return if (preampFits(bands, down) == preampFits(bands, preampDb)) down else preampDb
    }
    fun shownPreamp(profile: Profile): Double = if (!hasPreamp) 0.0 else writableEcho?.let { e ->
        ProtocolMicro.devicePreamp(profile.bands, profile.preampDb, e.preampMaxDb) - ProtocolMicro.highShelfGainSum(profile.bands)
    } ?: preampRules?.shownPreamp(profile) ?: profile.effectivePreampDb(shelfForm)
    /**
     * How this DAC builds a shelf (record `peq.shelfForm`, see [ShelfForm]): what the graph draws and what AUTO preamp computes for it. With no DAC attached the app's target is the Protocol Micro (slope), so the
     * editor looks and computes as it did before the field existed. Band registers and coefficient bytes never follow it.
     */
    val shelfForm: ShelfForm get() = caps.shelfForm
    /** The Q this DAC takes for a [type] band: a type has its own range where the DAC stores a scaled Q (KA15 and TRN shelves, K13 peaks: the register range divided by sqrt(2)). */
    fun qRange(type: FilterType): ClosedFloatingPointRange<Double> = caps.qRange(type)

    /** The graph's short headline for the first refusal; the detailed planner reasons stay untouched. */
    fun invalidEqHeadline(profile: Profile, reasons: List<String> = issues(profile)): String {
        // The graph shows this headline when it cannot draw the curve: that failure names its cause before any send blocker.
        val curveFailure = runCatching { Dsp.responseDb(profile.bands, shelfForm) }.exceptionOrNull()?.message
        val issue = (curveFailure ?: reasons.firstOrNull()).orEmpty().trim().lowercase(java.util.Locale.ROOT)
        val band = Regex("""^band (\d+):""").find(issue)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { profile.bands.getOrNull(it - 1) }
            ?: if ("no finite response" in issue) profile.bands.firstOrNull {
                it.enabled && (!it.freqHz.isFinite() || it.freqHz !in caps.freqMinHz..caps.freqMaxHz ||
                    !it.gainDb.isFinite() || (!it.type.isPass && it.gainDb !in caps.gainRange(it.type)) ||
                    !it.q.isFinite() || it.q !in qRange(it.type))
            } else null
        val cause = when {
            Regex("""^\d+ (enabled )?bands;""").containsMatchIn(issue) -> "band count"
            // A shelf without a real response also fails the preamp calculation: name the shelf, not the preamp.
            band == null && "no finite response" in issue &&
                profile.bands.any { it.enabled && (it.type == FilterType.LOW_SHELF || it.type == FilterType.HIGH_SHELF) } -> "shelf gain or Q"
            issue.startsWith("pregain") || issue.startsWith("preamp") || issue.startsWith("auto preamp") ||
                issue.startsWith("manual/auto attenuation") || issue.startsWith("this eq needs") -> "preamp"
            // Max/catalog refusals group the fields into one range reason. Resolve that reason from its band.
            band != null && (!band.freqHz.isFinite() || band.freqHz !in caps.freqMinHz..caps.freqMaxHz) -> "frequency"
            band != null && (!band.gainDb.isFinite() || (!band.type.isPass && band.gainDb !in caps.gainRange(band.type))) -> "gain"
            band != null && (!band.q.isFinite() || band.q !in qRange(band.type)) -> "Q"
            "shelf" in issue || (band?.type in setOf(FilterType.LOW_SHELF, FilterType.HIGH_SHELF) &&
                "non-finite biquad" in issue) -> "shelf gain or Q"
            " hz outside " in issue || issue.startsWith("frequency") -> "frequency"
            " db outside " in issue -> "gain"
            Regex("""\bq\b""").containsMatchIn(issue) && "outside" in issue -> "Q"
            else -> return "INVALID EQ - EDIT BAND"
        }
        return "INVALID EQ - adjust $cause"
    }

    fun issues(profile: Profile): List<String> {
        if (walkplay != null) return (walkplay.plan(profile) as? DevicePlan.Rejected)?.issues.orEmpty()
        val f = fiio ?: return familyIssues(profile)
        return runCatching {
            require(f.codecBlockers.isEmpty()) { f.codecBlockers.joinToString("; ") }
            require(f.userSlots.isNotEmpty()) { if (Proven.channel == BuildChannel.PERF) "No proven writable user slot" else "This DAC has no slot Contour can write to." }
            require(profile.bands.size <= caps.bands) { "${profile.bands.size} bands; ${caps.name} accepts ${caps.bands}; no truncation" }
            // Every concrete problem is listed (each field of each band, then the preamp): the first one is what HOLD TO SEND shows.
            val found = mutableListOf<String>()
            profile.bands.forEachIndexed { i, b ->
                val n = i + 1
                // Shelves and peaks have their own intended Q range when the DAC stores a scaled Q (KA15 shelves, K13 peaks: up to 7.07).
                val scaled = caps.qScale(b.type) != 1.0
                val (qLo, qHi) = caps.qRange(b.type).let { it.start to it.endInclusive }
                if (b.type !in caps.types) found += "Band $n: type unsupported"
                if (!b.freqHz.isFinite() || b.freqHz !in caps.freqMinHz..caps.freqMaxHz)
                    found += "Band $n: freq ${num(b.freqHz)} Hz outside ${num(caps.freqMinHz)}-${num(caps.freqMaxHz)} Hz"
                // A pass filter has no gain: it is written as 0 and its stored value is not checked against the range.
                if (!b.gainDb.isFinite() || (!b.type.isPass && b.gainDb !in caps.gainRange(b.type)))
                    found += "Band $n: gain ${num(b.gainDb)} dB outside ${num(caps.gainRange(b.type).start)}..${num(caps.gainRange(b.type).endInclusive)} dB"
                if (!b.q.isFinite() || b.q !in qLo..qHi)
                    found += "Band $n: ${if (!scaled) "" else if (b.type.isShelf) "shelf " else "peak "}Q %.2f outside %.2f-%.2f".format(java.util.Locale.ROOT, b.q, qLo, qHi)
            }
            val gain = profile.effectivePreampDb(shelfForm)
            if (!(gain.isFinite() && gain in preampMin..preampMax))
                found += "Pregain %.1f outside %.1f..%.1f dB".format(java.util.Locale.ROOT, gain, preampMin, preampMax)
            if (found.isNotEmpty()) return found
            FiioCodec(f).planOnExplicitSend(profile.copy(preampDb = gain), destinationSlot!!, true)
        }.exceptionOrNull()?.let { listOf(it.message ?: "Unrepresentable EQ") }.orEmpty()
    }
    /** Why [profile] cannot be sent to a 1.6 family DAC (empty = it can): the ranges of the DAC's catalog in the wording of the FiiO block, then the codec's own checks. */
    private fun familyIssues(profile: Profile): List<String> {
        if (readOnly) return listOf(READ_ONLY_ISSUE.format(caps.name))
        // A writable Echo-A is planned by the Protocol Micro's planner with its own limits, exactly as the session writes it.
        echo?.let { return (ProtocolMicro.plan(profile, it.caps, it.preampMaxDb) as? DevicePlan.Rejected)?.issues.orEmpty() }
        val found = mutableListOf<String>()
        if (profile.bands.size > caps.bands) return listOf("${profile.bands.size} bands; ${caps.name} accepts ${caps.bands}; no truncation")
        profile.bands.forEachIndexed { i, b ->
            if (!b.enabled) return@forEachIndexed
            val n = i + 1
            if (b.type !in caps.types) found += "Band $n: type unsupported"
            if (!b.freqHz.isFinite() || b.freqHz !in caps.freqMinHz..caps.freqMaxHz)
                found += "Band $n: freq ${num(b.freqHz)} Hz outside ${num(caps.freqMinHz)}-${num(caps.freqMaxHz)} Hz"
            if (!b.gainDb.isFinite() || (!b.type.isPass && b.gainDb !in caps.gainRange(b.type)))
                found += "Band $n: gain ${num(b.gainDb)} dB outside ${num(caps.gainRange(b.type).start)}..${num(caps.gainRange(b.type).endInclusive)} dB"
            // a shelf's intended Q range is the register range divided by the DAC's shelf scale (the Dawn Pro 2: up to 7.07)
            val qRange = caps.qRange(b.type)
            if (!b.q.isFinite() || b.q !in qRange)
                found += "Band $n: ${if (caps.qScale(b.type) != 1.0) "shelf " else ""}Q %.2f outside %.2f-%.2f".format(java.util.Locale.ROOT, b.q, qRange.start, qRange.endInclusive)
        }
        // AUTO is computed from the curve, and a band with no real response (a strong, steep shelf inside the DAC's own ranges) has no curve: that is a reason
        // to name, like the same band with a manual preamp, never an exception for the page that asks while it draws (1.6.5 threw it from here).
        val gain = if (!hasPreamp && moondrop == null) null else runCatching { profile.effectivePreampDb(shelfForm) }.getOrElse { e ->
            if (found.isNotEmpty()) return found
            return moondrop?.let { MoondropCodec.planFor(it, profile.bands, null).issues }.orEmpty().ifEmpty { listOf(e.message ?: "Unrepresentable EQ") }
        }
        if (hasPreamp && gain != null && !(gain.isFinite() && gain in preampMin..preampMax))
            found += "Pregain %.1f outside %.1f..%.1f dB".format(java.util.Locale.ROOT, gain, preampMin, preampMax)
        if (found.isNotEmpty()) return found
        return when {
            moondrop != null -> MoondropCodec.planFor(moondrop, profile.bands, gain).issues
            gen1 != null -> MoondropGen1Codec.issues(gen1, profile.bands)
            else -> KtMicroCodec.issues(requireNotNull(ktMicro), profile.copy(preampDb = 0.0))
        }
    }

    companion object {
        /** The display names of the 1.6 families, from their records. */
        val MOONDROP_NAME: String = GeneratedDevices.MOONDROP_DAWN_PRO_2.name
        /** The Allegro Mini's display name; a KT target's own is its caps name ([KtMicroCatalog.Model.name]). */
        val KT_NAME: String = GeneratedDevices.KIWI_EARS_ALLEGRO_MINI.name
        /** 1.6: the Dawn Pro 2 offers PEAK; its pass filters (LP, HP) where [MoondropCatalog.passFiltersOn] and its shelves (LS, HS) where [MoondropCatalog.shelvesOn] (proven or a review build). */
        val MOONDROP_TYPES: Set<FilterType> get() = MoondropCatalog.OFFERED_TYPES
        /** Why a read-only DAC is not written: a review build names the gate, a release only says it is read only. */
        val READ_ONLY_ISSUE: String get() = if (Proven.channel == BuildChannel.PERF) "%s is read only: not verified on hardware, Contour sends nothing to it" else "%s is read only in Contour"
        /** A number in an issue text: whole values without decimals ("0", "25000"), others to one place. */
        private fun num(x: Double): String =
            if (x.isFinite() && x == Math.rint(x) && Math.abs(x) < 1e9) x.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, x)
        private const val BLOCK_LABEL_MAX = 30 // as wide as the longest label HOLD TO SEND already shows
        private const val BLOCK_LABEL_GENERIC = "INVALID EQ - EDIT BAND"
        private val bandIssue = Regex("""^Band (\d+): (.*?)(?: outside .*)?$""")
        private val bandCountIssue = Regex("""^(\d+) bands; .* accepts (\d+);""")
        /**
         * The text HOLD TO SEND shows for the first of [issues] (short, uppercase), e.g. "BAND 10: FREQ 0 HZ - EDIT BAND"
         * or "PREAMP OUT OF RANGE". Anything it does not recognise, or that would not fit, stays the generic label.
         */
        fun blockLabel(issues: List<String>): String {
            val issue = issues.firstOrNull()?.trim() ?: return BLOCK_LABEL_GENERIC
            if (issue == "Read the DAC first") return "READ THE DAC FIRST"
            if (issue.startsWith("Pregain") || issue.contains("preamp", ignoreCase = true)) return "PREAMP OUT OF RANGE"
            bandCountIssue.find(issue)?.let { return "${it.groupValues[1]} BANDS - MAX ${it.groupValues[2]}" }
            val m = bandIssue.find(issue) ?: return BLOCK_LABEL_GENERIC
            val base = "BAND ${m.groupValues[1]}: ${m.groupValues[2].uppercase(java.util.Locale.ROOT)}"
            return when {
                "$base - EDIT BAND".length <= BLOCK_LABEL_MAX -> "$base - EDIT BAND"
                base.length <= BLOCK_LABEL_MAX -> base
                else -> BLOCK_LABEL_GENERIC
            }
        }
        val MICRO = DeviceTarget(walkplay = DeviceProtocol.MICRO)
        val MAX = DeviceTarget(walkplay = DeviceProtocol.MAX)
        val TRN = DeviceTarget(walkplay = DeviceProtocol.TRN)
        val KA15 = DeviceTarget(fiioRecord = FiioCatalog.KA15)
        val K13 = DeviceTarget(fiioRecord = FiioCatalog.K13)
        val MELODY = DeviceTarget(fiioRecord = FiioCatalog.MELODY)
        val TINY_A = DeviceTarget(fiioRecord = FiioCatalog.TINY_A)
        /** The FiiO JadeAudio JA11 (found by its USB name; [readOnly] in a release until its record says `hardwareVerified`). */
        val FIIO_JA11 = DeviceTarget(fiioRecord = FiioCatalog.JA11)
        /** The SnowSky RETRO NANO (0A12:4007, found by its USB name) and the FiiO JadeAudio JIEZI (2972:0151, found by its USB name): both written and read back from a phone on 13.11.2026, so their records say `hardwareVerified` true. */
        val RETRO_NANO = DeviceTarget(fiioRecord = FiioCatalog.RETRO_NANO)
        val FIIO_JIEZI = DeviceTarget(fiioRecord = FiioCatalog.JIEZI)
        private val MELODY_WITH_PASS = DeviceTarget(fiioRecord = SnowSkyMelody.config(true))
        private val KA15_WITH_PASS = DeviceTarget(fiioRecord = FiioKa15.config(true))
        /** The KA15 as the app plans with it now is [KA15]; this is it with LP, HP, BP and AP offered whatever the flag says (the bench's `--pass-filters` and the tests). */
        fun ka15(passFilters: Boolean): DeviceTarget = if (passFilters) KA15_WITH_PASS else KA15
        /** The Melody as the app plans with it now is [MELODY]; this is it with LP, HP, BP and AP offered whatever the flag says (the bench's `--pass-filters` and the tests). */
        fun melody(passFilters: Boolean): DeviceTarget = if (passFilters) MELODY_WITH_PASS else MELODY
        val DAWN_PRO_2 = DeviceTarget(moondrop = MoondropCatalog.dawnPro2)
        val ALLEGRO_MINI = DeviceTarget(ktMicro = KtMicroCatalog.models.first())
        /** The JCally JM12 (KT02H20, same VID:PID as the Allegro Mini, found by its USB name): written and read back from a phone on 10.11.2026 (run 2026-11-10-jcally-jm12-11-phone), so it writes in every build. */
        val JCALLY_JM12 = DeviceTarget(ktMicro = KtMicroCatalog.jm12Models.first())
        /** The Kiwi Ears AD1 (3302:20E3, a KT Micro register DAC on the WalkPlay vendor id, found by its USB name): written and read back from a phone on 13.11.2026, so it writes in every build. */
        val KIWI_EARS_AD1 = DeviceTarget(ktMicro = KtMicroCatalog.ad1Models.first())
        /** The Tanchjim Space II (3302:4381, WalkPlay SchemeNo16 on the Protocol Max's code, found by its USB name): written and read back from a phone on 13.11.2026, so it writes in every build. */
        val SPACE_2 = DeviceTarget(walkplay = DeviceProtocol.SPACE_2)
        /** The EPZ TP65 (3302:4325, found by its USB name; supported since 1.8.2): read by Contour on a TP65 owner's unit (1.8.0-tp65test2) and written there with its own sequence ([WalkPlayProfiles.TP65_TESTER_WRITE]; PEAK with 1.8.1-tp65test3, the shelves with tp65test4, each VERIFIED); no DAC settings, the preamp read only. */
        val EPZ_TP65 = DeviceTarget(walkplay = DeviceProtocol.EPZ_TP65)
        val ECHO_A = DeviceTarget(echo = WalkPlayProfiles.echoA)
        /** The Moondrop DUSK-SP (35D8:1499, found by its USB name; [readOnly] in a release until its record says `hardwareVerified`). */
        val MOONDROP_DUSK = DeviceTarget(gen1 = MoondropGen1Catalog.duskSp)
        /** An Echo-A target for [profile] (tests: the writable variant, `echoA.copy(hardwareVerified = true)`). */
        internal fun forEcho(profile: WalkPlayProfile) = DeviceTarget(echo = profile)
        /** The 1.6 families whose write is proven in every build, which [find] does not know (the DUSK-SP since its phone run of 11.11.2026, run 2026-11-11-moondrop-dusk-09-phone). */
        val FAMILIES = listOf(DAWN_PRO_2, ALLEGRO_MINI, JCALLY_JM12, ECHO_A, MOONDROP_DUSK)
        fun of(protocol: DeviceProtocol) = when (protocol) {
            DeviceProtocol.MICRO -> MICRO
            DeviceProtocol.MAX -> MAX
            DeviceProtocol.TRN -> TRN
            DeviceProtocol.SPACE_2 -> SPACE_2
            DeviceProtocol.EPZ_TP65 -> EPZ_TP65
        }

        /** The target of each device record, by record id; the identities themselves are [GeneratedDevices.USB_IDS], the records' `usb` sections. */
        private val byRecord: Map<String, DeviceTarget> = mapOf(
            GeneratedDevices.CRINEAR_PROTOCOL_MICRO_USB.recordId to MICRO,
            GeneratedDevices.CRINEAR_PROTOCOL_MAX_USB.recordId to MAX,
            GeneratedDevices.TRN_BLACK_PEARL_USB.recordId to TRN,
            GeneratedDevices.FIIO_KA15_USB.recordId to KA15,
            GeneratedDevices.FIIO_K13_R2R_USB.recordId to K13,
            GeneratedDevices.SNOWSKY_MELODY_USB.recordId to MELODY,
            GeneratedDevices.SNOWSKY_TINY_A_USB.recordId to TINY_A,
            GeneratedDevices.FIIO_JA11_USB.recordId to FIIO_JA11,
            GeneratedDevices.SNOWSKY_RETRO_NANO_USB.recordId to RETRO_NANO,
            GeneratedDevices.FIIO_JIEZI_USB.recordId to FIIO_JIEZI,
            GeneratedDevices.MOONDROP_DAWN_PRO_2_USB.recordId to DAWN_PRO_2,
            GeneratedDevices.KIWI_EARS_ALLEGRO_MINI_USB.recordId to ALLEGRO_MINI,
            GeneratedDevices.JCALLY_JM12_USB.recordId to JCALLY_JM12,
            GeneratedDevices.KIWI_EARS_AD1_USB.recordId to KIWI_EARS_AD1,
            GeneratedDevices.TANCHJIM_SPACE_II_USB.recordId to SPACE_2,
            GeneratedDevices.EPZ_TP65_USB.recordId to EPZ_TP65,
            GeneratedDevices.MOONDROP_ECHO_A_USB.recordId to ECHO_A,
            GeneratedDevices.MOONDROP_DUSK_USB.recordId to MOONDROP_DUSK,
        ).also { m -> require(m.keys == GeneratedDevices.USB_IDS.map { it.recordId }.toSet()) { "a device record has no DeviceTarget (or the other way round)" } }

        /**
         * Strict VID:PID allow-list, the only discovery API of the 1.5 devices: the device records whose `usb.recognisedBy` is "find" (Protocol Micro,
         * Protocol Max, TRN Black Pearl, FiiO KA15, FiiO K13 R2R, SnowSky Melody and Tiny A). Every other identity is unrecognised (NO DAC).
         */
        fun find(vendorId: Int, productId: Int): DeviceTarget? =
            GeneratedDevices.USB_IDS.firstOrNull { it.recognition == Recognition.FIND && it.vendorId == vendorId && it.productId == productId }
                ?.let { byRecord.getValue(it.recordId) }

        /**
         * [find] and then the three 1.6 families (`usb.recognisedBy` "findDevice"): the Dawn Pro 2 and the Allegro Mini by VID, PID and the exact USB product name
         * (a record's `usb.productName` and `usb.nameAliases`), the Echo-A by VID:PID. A product name that is missing or differs recognises the first two
         * never. The app uses this; [find] keeps the 1.5 list.
         */
        fun findDevice(vendorId: Int, productId: Int, productName: String?): DeviceTarget? =
            find(vendorId, productId)
                ?: GeneratedDevices.USB_IDS.firstOrNull { it.recognition == Recognition.FIND_DEVICE && it.matches(vendorId, productId, productName) }
                    ?.let { byRecord.getValue(it.recordId) }

        /**
         * The id of the device record [target] stands for: a key the app can keep across versions (the last DAC found, for an import with none attached). Null for a
         * target no record names (the pass-filter variants of the tests and the bench).
         */
        fun recordIdOf(target: DeviceTarget): String? = byRecord.entries.firstOrNull { it.value == target }?.key

        /** The target of a device record id ([recordIdOf]); null for an id no record has (any longer). */
        fun ofRecord(recordId: String): DeviceTarget? = byRecord[recordId]
    }
}
