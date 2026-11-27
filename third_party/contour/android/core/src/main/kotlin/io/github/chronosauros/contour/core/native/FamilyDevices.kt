package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.BuildChannel
import io.github.chronosauros.contour.core.DacImport
import io.github.chronosauros.contour.core.DevicePlan
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.ImportedEq
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.Proven
import io.github.chronosauros.contour.core.ProtocolMicro
import io.github.chronosauros.contour.core.WalkPlay
import io.github.chronosauros.contour.core.isPass
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What one read of a Contour 1.6 family DAC holds, in the terms the app works with (the 1.6 counterpart of [NativeState], which stays
 * FiiO's): the Moondrop Dawn Pro 2, the Kiwi Ears Allegro Mini, the Moondrop DUSK-SP and the Moondrop Echo-A (read only until its write is verified). Pure JVM; built by the app from the
 * sessions' reads and by the tests from replays.
 *
 * Two questions about a profile, because they have different jobs: [holds] = the EQ the profile compiles to sounds the same as the registers
 * read (bands only; the editor uses it to find a library profile for what the DAC holds, so a read never adds a twin), [matches] = ON DAC,
 * everything Contour writes reads back exactly as written (bank, bands to the coefficient, pre-gain, EQ switch).
 */
sealed interface FamilyState {
    /** The number the DAC says it is on (Dawn: the EQ index; Echo-A: the slot); 0 where there is none. */
    val slot: Int
    /** The preamp as the DAC has it; null = this DAC has none, or it was not read. */
    val preampDb: Double?
    /** The state can be opened in the editor (FROM DAC); false = it is only shown. */
    val importable: Boolean
    fun holds(profile: Profile): Boolean
    fun matches(profile: Profile): Boolean
    fun importExact(): ImportedEq
    /** The DAC Settings page rows ([FamilySettings]) with what this read says. */
    fun readings(): List<SettingReading>
    fun describe(): String

    /** Moondrop Dawn Pro 2: the active EQ index and the bands of that bank, the global gain, the pre-gain when the DAC answered. */
    data class Dawn(val model: MoondropCatalog.Model, val snapshot: MoondropCodec.Snapshot) : FamilyState {
        override val slot: Int get() = snapshot.activeIndex
        override val preampDb: Double? get() = snapshot.preGain?.db
        /** The DAC is on a bank Contour writes (EQ index 7 or 9, the record's set: the manufacturer's PEQ mode). On any other index the bands read are the unit's own preset. */
        val onWritableBank: Boolean get() = snapshot.peqMode
        override val importable: Boolean get() = onWritableBank
        /** Said before a send while the DAC is on another index; null when it is on one of Contour's banks. */
        val bankNotice: String? get() = if (onWritableBank) null
            else "The DAC is on EQ index ${snapshot.activeIndex}, one of its own presets. Switch the DAC to its PEQ mode (EQ index ${model.writableIndexes.sorted().joinToString(" or ")}): HOLD TO SEND writes the EQ the DAC is on."

        private fun planned(profile: Profile) = MoondropCodec.planFor(model, profile.bands, profile.effectivePreampDb(model.shelfForm))
        override fun holds(profile: Profile): Boolean = runCatching {
            onWritableBank && planned(profile).let { p ->
                p.issues.isEmpty() && p.bands.all { e -> snapshot.bands.singleOrNull { it.index == e.index }?.let { sameSound(e, it) } == true }
            }
        }.getOrDefault(false)
        /** Strict: a pre-gain the DAC did not answer for is not proven, so the profile is not ON DAC (the editor still finds it by [holds]). */
        override fun matches(profile: Profile): Boolean = runCatching {
            onWritableBank && planned(profile).let { p ->
                p.issues.isEmpty() && MoondropCodec.matches(p.bands, snapshot.activeIndex, snapshot.bands).isEmpty() &&
                    snapshot.preGain?.rawQ8 == preGainQ8(profile.effectivePreampDb(model.shelfForm))
            }
        }.getOrDefault(false)

        override fun importExact(): ImportedEq {
            require(onWritableBank) { "${model.productName} is on EQ index ${snapshot.activeIndex}, not one of Contour's banks (${model.writableIndexes.sorted().joinToString(", ")}): nothing to open" }
            val raw = snapshot.bands.sortedBy { it.index }
            // Every band the DAC holds comes in, 0 dB ones included: a band at 0 dB still has its own frequency and Q, and a send writes those back. Only the trailing bands a send would
            // write for a missing band anyway (a disabled one, or exactly the neutral filler) are left out.
            val last = maxOf(0, raw.indexOfLast { it.nativeType != 0 && !isFiller(it) })
            val bands = raw.take(last + 1).map { r ->
                val type = r.type
                if (r.nativeType == 0 || type == null)
                    Band("dac${r.index}", FilterType.PEAK, r.frequencyHz.toDouble().coerceIn(model.minFrequencyHz, model.maxFrequencyHz), 0.0,
                        r.q.coerceIn(model.minQ, model.maxQ), enabled = false)
                else {
                    require(!type.isPass || r.gainQ8 == 0) { "Band ${r.index + 1}: ${type.name.replace('_', ' ')} has gain ${r.gainDb} dB on the DAC; Contour keeps pass filters at 0 dB" }
                    Band("dac${r.index}", type, r.frequencyHz.toDouble(), if (type.isPass) 0.0 else r.gainDb, MoondropCodec.intendedQ(model, type, r.q))
                }
            }
            return ImportedEq(bands, snapshot.preGain?.db?.takeIf { it in model.preGainMinDb..model.preGainMaxDb })
        }

        /** The two gains in tenths of a dB (the rows' [DacSetting.scale] is 10); the pre-gain only when the DAC answered. */
        override fun readings(): List<SettingReading> = listOfNotNull(
            SettingReading(FamilySettings.EQ_INDEX, snapshot.activeIndex, null),
            SettingReading(FamilySettings.GLOBAL_GAIN, (snapshot.globalGain.db * 10).roundToInt(), null),
            snapshot.preGain?.let { SettingReading(FamilySettings.PRE_GAIN, (it.db * 10).roundToInt(), null) },
        )
        override fun describe(): String =
            "${model.productName}: EQ index ${snapshot.activeIndex}" + (if (onWritableBank) " (Contour's bank)" else " (the unit's own; Contour writes index ${model.writableIndexes.sorted().joinToString(" or ")})") +
                "\nglobal gain ${snapshot.globalGain.db} dB; PRE GAIN ${snapshot.preGain?.db?.let { "$it dB" } ?: "not read"}\n" +
                snapshot.bands.joinToString("\n") { b -> "${b.index + 1}  ${b.type?.name?.take(2) ?: "--"} ${b.frequencyHz} Hz ${"%.2f".format(java.util.Locale.ROOT, b.gainDb)} dB Q ${"%.3f".format(java.util.Locale.ROOT, b.q)}" }
    }

    /** A KT Micro DAC (Kiwi Ears Allegro Mini, JCally JM12): the five bands, the EQ switch, the digital DAC gain, and for a model with Global Gain the register (raw, read only) when it answered. */
    data class Allegro(val model: KtMicroCatalog.Model, val snapshot: KtMicroCodec.Snapshot, val globalGain: KtMicroCodec.RegisterValue? = null) : FamilyState {
        override val slot: Int get() = 0
        override val preampDb: Double? get() = null
        override val importable: Boolean get() = true
        val eqEnabled: Boolean get() = snapshot.eqEnabled
        /** The vendor tool's Global Gain steps are the record's `preamp.globalGain.userSteps`; the layout of register 0x3B is not proven, so only a first byte inside that range counts. */
        val globalGainStep: Int? get() = model.globalGainUserSteps?.let { steps -> globalGain?.value?.firstOrNull()?.takeIf { it in steps } }

        private fun planned(profile: Profile) = KtMicroCodec.compile(profile.copy(preampDb = 0.0), snapshot, snapshot.eqEnabled).expectedBands
        override fun holds(profile: Profile): Boolean = runCatching {
            planned(profile).all { e -> snapshot.bands.singleOrNull { it.index == e.index }?.let { sameSound(e, it) } == true }
        }.getOrDefault(false)
        /** ON DAC: the EQ is switched on and the bands are the profile's. */
        override fun matches(profile: Profile): Boolean = snapshot.eqEnabled && holds(profile)

        override fun importExact(): ImportedEq {
            // Every PEAK band the DAC holds comes in, 0 dB ones included (each has its own frequency and Q, which a send writes back). Left out: the trailing bands a send would write for a missing band
            // anyway (exactly the filler of [KtMicroCodec.compile]), and a trailing shelf at 0 dB, which does nothing and which Contour does not write.
            val last = maxOf(0, snapshot.bands.indexOfLast { if (it.nativeType == 0) !isFiller(it) else it.gainTenths != 0 })
            val bands = snapshot.bands.take(last + 1).map { r ->
                val type = KtMicroCodec.type(r.nativeType)
                // a type the model does not offer (a shelf another tool stored on the Allegro Mini) is not opened as something else
                require(type in model.nativeTypes) { if (type == FilterType.PEAK) "Band ${r.index + 1}: the DAC holds a type Contour does not write to the ${model.productName}" else
                    "Band ${r.index + 1}: the DAC holds a shelf; Contour writes ${if (model.nativeTypes.size == 1) "PEAK bands to the ${model.productName} only" else "only ${model.nativeTypes.joinToString { it.name }} to the ${model.productName}"}" }
                Band("dac${r.index}", type, model.intendedFrequency(r.storedFrequencyHz), r.gainTenths / 10.0, r.qMilli / 1000.0 / (if (type == FilterType.PEAK) 1.0 else model.shelfQScale))
            }
            return ImportedEq(bands, 0.0)
        }

        override fun readings(): List<SettingReading> = listOfNotNull(
            SettingReading(FamilySettings.EQ_SWITCH, if (eqEnabled) 1 else 0, null),
            globalGainStep?.let { SettingReading(FamilySettings.GLOBAL_GAIN, it, null) },
        )
        override fun describe(): String =
            "${model.productName}: EQ ${if (eqEnabled) "ON" else "OFF"}; ${snapshot.digitalGain?.describe() ?: "digital DAC gain unknown"}" +
                (if (model.globalGainUserSteps == null) "\n" else "; Global Gain register 0x3B ${globalGain?.value?.joinToString(" ") { "%02x".format(it) } ?: "not read"} (raw)\n") +
                snapshot.bands.joinToString("\n") { b -> "${b.index + 1}  ${KtMicroCodec.type(b.nativeType).name.take(2)} ${b.storedFrequencyHz} Hz ${b.gainTenths / 10.0} dB Q ${b.qMilli / 1000.0}" }
    }

    /**
     * A Moondrop gen-1 cable (DUSK-SP): the active preset and the nine meta records of the Custom bank. What PLAYS is the Custom bank when the preset is the Custom one ([onCustom]); on one of the
     * firmware presets (1-5) the bands that read back are the Custom bank, NOT what plays, and what plays is the preset's table ([presetBands], record `slots.presetBands`: the cable cannot report it,
     * so the app shows the published values as if that profile had been sent). [holds], [matches] and [importExact] all speak of what plays.
     * The Custom bank is compared on the meta records (type, integer Hz, Q x 256, whole-dB gain): the coefficients, which carry the exact gain, cannot be read back, so a band whose meta record equals what a send
     * would write counts as the same band (the gain on screen is the exact one, the record's is rounded half away from zero). A firmware preset is compared on the table itself, exactly (see [samePreset]).
     */
    data class Dusk(val model: MoondropGen1Catalog.Model, val snapshot: MoondropGen1Codec.Snapshot) : FamilyState {
        override val slot: Int get() = snapshot.activeIndex
        override val preampDb: Double? get() = null
        val onCustom: Boolean get() = snapshot.onCustom
        /** What plays on a firmware preset: its table bands; null on the Custom EQ (the bank read is what plays) and for a preset the record lists no bands for. */
        val presetBands: List<Band>? get() = if (onCustom) null else model.presetBands(snapshot.activeIndex)
        override val importable: Boolean get() = onCustom || presetBands != null
        /** The name of a profile made from what plays: the firmware preset's name ("DUSK-V"); null on the Custom EQ (the app's usual FROM DAC name). */
        val importName: String? get() = if (onCustom || presetBands == null) null else model.presetName(snapshot.activeIndex)
        /** What the foot button of the Tune screen reads while the cable is idle and nothing was picked or edited since the last read or send: the cable's active preset in capitals ("CUSTOM EQ", "DUSK-DEFAULT", ...). */
        val footLabel: String get() = model.presetName(snapshot.activeIndex).uppercase(java.util.Locale.ROOT)

        private fun planned(profile: Profile) = MoondropGen1Codec.plan(model, profile.bands)
        override fun holds(profile: Profile): Boolean = runCatching {
            if (onCustom) MoondropGen1Codec.issues(model, profile.bands).isEmpty() &&
                MoondropGen1Codec.mismatches(planned(profile), snapshot.activeIndex, snapshot.bands, model.customIndex).isEmpty()
            else presetBands?.let { samePreset(it, profile.bands) } == true
        }.getOrDefault(false)
        /** ON DAC: the cable plays the profile: its Custom bank (the nine meta records are what a send writes) or the firmware preset whose table the profile's bands equal. */
        override fun matches(profile: Profile): Boolean = holds(profile)

        override fun importExact(): ImportedEq {
            presetBands?.let { table -> return ImportedEq(table.mapIndexed { i, b -> b.copy(id = "dac$i") }, 0.0) }
            require(onCustom) { "${model.productName} plays its ${model.presetName(snapshot.activeIndex)} preset, which the record lists no bands for, so nothing is opened" }
            val raw = snapshot.bands.sortedBy { it.index }
            // Every band comes in, 0 dB ones included (each has its own frequency and Q, which a send writes back); only the trailing bands that are exactly the factory ones (what a send writes for a missing band) are left out.
            val last = maxOf(0, raw.indexOfLast { !isFiller(model, it) })
            val bands = raw.take(last + 1).map { r ->
                val type = r.type ?: throw IllegalArgumentException("Band ${r.index}: the cable holds filter type ${r.nativeType}, which Contour does not write")
                Band("dac${r.index - 1}", type, r.freqHz.toDouble(), r.gainDb.toDouble(), r.q)
            }
            return ImportedEq(bands, 0.0)
        }

        override fun readings(): List<SettingReading> = listOf(SettingReading(FamilySettings.PRESET, snapshot.activeIndex, null))
        override fun describe(): String =
            "${model.productName}: preset ${snapshot.activeIndex} (${model.presetName(snapshot.activeIndex)})" + (if (onCustom) "" else "; the bands below are the preset's table, the cable cannot report what plays") + "\n" +
                (presetBands?.mapIndexed { i, b -> "${i + 1}  ${b.type.name.take(2)} ${b.freqHz} Hz ${b.gainDb} dB Q ${"%.3f".format(java.util.Locale.ROOT, b.q)}" }
                    ?: snapshot.bands.map { b -> "${b.index}  ${b.type?.name?.take(2) ?: "--"} ${b.freqHz} Hz ${b.gainDb} dB Q ${"%.3f".format(java.util.Locale.ROOT, b.q)}" }).joinToString("\n")
    }

    /**
     * Moondrop Echo-A: read through the Protocol Micro's code (SchemeNo11). While [WalkPlayProfile.writeOn] is false it is only shown, never
     * written, never opened in the editor; once the flag is true it compares and imports like the Micro, held to the Echo-A's own limits.
     */
    data class EchoA(val profile: WalkPlayProfile, val snapshot: WalkPlaySnapshot) : FamilyState {
        override val slot: Int get() = snapshot.slot
        override val preampDb: Double get() = snapshot.preampDb.toDouble()
        override val importable: Boolean get() = profile.writeOn

        private val registers get() = snapshot.bands.map { it.registers }
        private fun planned(eq: Profile): DevicePlan.Ready? = ProtocolMicro.plan(eq, profile.caps) as? DevicePlan.Ready
        /** The bands the profile compiles to are the registers read (the preamp is not compared). */
        override fun holds(profile: Profile): Boolean = this.profile.writeOn &&
            planned(profile)?.let { p -> p.bands.indices.all { p.bands[it].registers() == registers.getOrNull(it) } } == true
        /** ON DAC: the registers and the preamp read are exactly what [ProtocolMicro.plan] writes for the profile. */
        override fun matches(profile: Profile): Boolean = this.profile.writeOn &&
            planned(profile)?.let { ProtocolMicro.matches(it, registers, snapshot.preampDb) } == true

        override fun importExact(): ImportedEq {
            check(profile.writeOn) { "${profile.name} is read only in Contour 1.6" }
            return when (val r = ProtocolMicro.importExact(snapshot.bands, snapshot.preampDb, profile.caps)) {
                is DacImport.Ready -> r.eq
                is DacImport.Rejected -> throw IllegalStateException(r.issues.joinToString("; "))
            }
        }
        override fun readings(): List<SettingReading> = emptyList()
        override fun describe(): String =
            "${profile.name}: ${if (profile.writeOn) "write enabled" else "READ ONLY"}${if (profile.hardwareVerified) "" else " - not verified on hardware"}\n" +
                "firmware ${snapshot.firmware}, slot ${snapshot.slot}, preamp ${snapshot.preampDb} dB\n" +
                snapshot.bands.joinToString("\n") { b ->
                    val r = b.registers
                    "%d  %s %7.1f Hz %6.2f dB Q %.3f   raw f=%d q=%d g=%d t=%d".format(java.util.Locale.ROOT, r.index + 1, b.type.name.take(2), b.freqHz, b.gainDb, b.q, r.freq, r.q256, r.gain256, r.typeCode)
                }
    }

}

private fun preGainQ8(db: Double) = WalkPlay.toInt32(WalkPlay.jsRound(db * 256))
/** A band that does nothing: disabled (type 0) or a peak at 0 dB. */
private fun silent(type: Int, gainQ8: Int) = type == 0 || (type == 2 && gainQ8 == 0)
/** The Dawn Pro 2 band holds exactly what a send writes for a missing band ([MoondropCodec.neutralBand]). */
private fun isFiller(r: MoondropCodec.RawBand): Boolean = MoondropCodec.neutralBand(r.index).let { n ->
    r.nativeType == n.nativeType && r.frequencyHz == n.frequencyHz && r.qQ8 == n.qQ8 && r.gainQ8 == n.gainQ8
}
/** The Allegro Mini band holds exactly what a send writes for a missing band ([KtMicroCodec.compile]: 0 dB, stored 100 Hz, Q 1.000, PEAK). */
private fun isFiller(r: KtMicroCodec.RawBand): Boolean = r.gainTenths == 0 && r.storedFrequencyHz == 100 && r.qMilli == 1000 && r.nativeType == 0
/** A Moondrop gen-1 band that is exactly the factory band of its position (what a send writes for a missing band: PEAK, 0 dB, Q 1.0 at the position's frequency). */
private fun isFiller(model: MoondropGen1Catalog.Model, r: MoondropGen1Codec.RawBand): Boolean =
    MoondropGen1Codec.meta(MoondropGen1Codec.neutralBand(model, r.index - 1)) == r.meta
/** A profile's audible bands (enabled, not at 0 dB) are exactly the firmware preset's table, in any order: same type, frequency, gain and Q (to what the table prints: 0.5 Hz, 0.01 dB, 0.01 Q). */
private fun samePreset(table: List<Band>, bands: List<Band>): Boolean {
    val order = compareBy<Band>({ it.freqHz }, { it.gainDb }, { it.q })
    val mine = bands.filter { it.enabled && it.gainDb != 0.0 }.sortedWith(order)
    return table.size == mine.size && table.sortedWith(order).zip(mine).all { (t, b) ->
        t.type == b.type && abs(t.freqHz - b.freqHz) <= 0.5 && abs(t.gainDb - b.gainDb) <= 0.01 && abs(t.q - b.q) <= 0.01
    }
}
private fun sameSound(e: MoondropCodec.BandWrite, a: MoondropCodec.RawBand): Boolean =
    (silent(e.nativeType, e.gainQ8) && silent(a.nativeType, a.gainQ8)) ||
        (e.nativeType == a.nativeType && e.frequencyHz == a.frequencyHz && e.qQ8 == a.qQ8 && e.gainQ8 == a.gainQ8)
private fun sameSound(e: KtMicroCodec.RawBand, a: KtMicroCodec.RawBand): Boolean =
    (e.gainTenths == 0 && a.gainTenths == 0) ||
        (e.nativeType == a.nativeType && e.storedFrequencyHz == a.storedFrequencyHz && e.qMilli == a.qMilli && e.gainTenths == a.gainTenths)

/** The 1.6 families' entry points the app and the tests share. */
object FamilyDevices {
    /** What HOLD TO SEND says on a read-only DAC. */
    val READ_ONLY_LABEL: String get() = if (Proven.channel == BuildChannel.PERF) "READ ONLY - NOT VERIFIED" else "READ ONLY"

    /** The Moondrop Echo-A, read through [WalkPlaySession] with the Micro SchemeNo11 profile: the version, slot, every band and the preamp. No session object
     * leaves here; the write (only when [WalkPlayProfiles.ECHO_A_HARDWARE_VERIFIED] is true) is the app's own session in `DacClient.writeProfile`. */
    fun readEchoA(port: NativePort): FamilyState.EchoA {
        val profile = WalkPlayProfiles.echoA
        return FamilyState.EchoA(profile, WalkPlaySession(profile, port).read())
    }

    /** Why HOLD TO SEND may not write [target] given what was last read ([state], null = nothing read); empty = it may. The profile's own limits are [DeviceTarget.issues]. */
    fun stateIssues(target: DeviceTarget, state: FamilyState?): List<String> = when (target.family) {
        DeviceTarget.Family.MOONDROP -> if (state is FamilyState.Dawn) emptyList() else listOf(READ_FIRST)
        DeviceTarget.Family.KT_MICRO ->
            if (target.readOnly) listOf(DeviceTarget.READ_ONLY_ISSUE.format(target.caps.name))
            else if (state is FamilyState.Allegro) listOfNotNull(state.snapshot.writeBlocker) else listOf(READ_FIRST)
        DeviceTarget.Family.MOONDROP_GEN1 ->
            if (target.readOnly) listOf(DeviceTarget.READ_ONLY_ISSUE.format(target.caps.name))
            else if (state is FamilyState.Dusk) emptyList() else listOf(READ_FIRST)
        DeviceTarget.Family.WALKPLAY_READ_ONLY ->
            if (target.readOnly) listOf(DeviceTarget.READ_ONLY_ISSUE.format(target.caps.name))
            else if (state is FamilyState.EchoA) emptyList() else listOf(READ_FIRST)
        else -> emptyList()
    }
    const val READ_FIRST = "Read the DAC first"
}
