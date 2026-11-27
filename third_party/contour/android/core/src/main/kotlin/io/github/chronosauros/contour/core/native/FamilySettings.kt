package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.devices.PendingRecords
import kotlin.math.roundToInt

/**
 * The DAC Settings page rows of the Contour 1.6 families, in the page's own [DacSetting] language. Kept apart from [DacSettings] (the FiiO and
 * WalkPlay lists); [DeviceTarget.settings] asks here for a target that has neither.
 *
 * The Dawn Pro 2 rows are reads. Its Pre Gain has a write path ([MoondropSession.writeGain], 0.1 dB, values in tenths of a dB here) and keeps [DacSetting.writeVerified] false until a unit
 * has proven the frame: enabling it later is that one flag. Its Global Gain is read only in every build (the unit ignores the write). The Allegro Mini's EQ switch (register 0x24) and its Global Gain
 * (register 0x3B, steps 1-15) have write paths ([KtMicroSession.writeSwitch] and [KtMicroSession.writeGlobalGain]); each counts as done only after the DAC is back on the bus and a read agrees.
 * Both passed the PC bench and the phone (EQ switch OFF/ON and Global Gain 1/15/13, each VERIFIED after the re-enumeration, and persistence over a replug: Pixel run 2026-10-24-kiwi-ears-allegro-mini-04-phone), so [DacSetting.writeVerified] is true. The readers are not
 * [SettingRead.FiiO]-style frames: the controller fills the rows from the read state ([FamilyState.readings]); the [SettingRead] is only the
 * page's "this row has a reader" mark.
 */
object FamilySettings {
    const val EQ_INDEX = "eq-index"
    const val GLOBAL_GAIN = "global-gain"
    const val PRE_GAIN = "pre-gain"
    const val EQ_SWITCH = "eq-switch"
    /** The active preset of a Moondrop gen-1 cable (DUSK-SP): option 0 is the Custom EQ Contour writes, 1-5 are the firmware presets. */
    const val PRESET = "preset"

    /** The page's mark for a row whose value comes from the family read; [command] is the register the value lives in. */
    private fun mark(command: Int) = SettingRead.WalkPlayGet(command) { null }

    private val dawn = listOf(
        // The active EQ index (register 0x0F). Read on the owner's unit 21.10.2026: 9. The range is provisional: only the index the DAC reports is shown.
        DacSetting(EQ_INDEX, "EQ INDEX", SettingKind.LEVEL, EvidenceRank.HW, min = PendingRecords.DAWN_EQ_INDEX_RANGE.first, max = PendingRecords.DAWN_EQ_INDEX_RANGE.last, group = 0, read = mark(MoondropCodec.CMD_EQ_ACTIVE)),
        // Register 0x03, the manufacturer's GLOBAL GAIN (the DAC offset); the range is MoondropCatalog's, the value is in tenths of a dB (0.1 dB step).
        // Read only in every build, like the TRN microphone ([WalkPlaySettings]): the unit ignores `01 03 02 lo hi` (PC T14, runs/2026-10-21-moondrop-dawn-pro-2-02, RUN-DP3; the phone only read it,
        // RUN-DPP1), so no row writes it. No [DacSetting.writeCommand]. The EQ write still sends the register with the value read just before, as MOONDROP Hub's Write Cfg does
        // ([MoondropCodec.writeSteps]), which changes nothing on the unit; the bench's `setting-plan global-gain` stays a bench tool ([MoondropSession.writeGain]).
        DacSetting(GLOBAL_GAIN, "GLOBAL GAIN", SettingKind.LEVEL, EvidenceRank.HW, min = (MoondropCatalog.dawnPro2.globalGainMinDb * 10).roundToInt(),
            max = (MoondropCatalog.dawnPro2.globalGainMaxDb * 10).roundToInt(), unit = "dB", group = 0,
            read = mark(MoondropCodec.CMD_GLOBAL_GAIN), writeCommand = null, writeVerified = false, scale = 10),
        // Register 0x23, the manufacturer's PRE-GAIN (the preamp), MoondropCatalog's range in 0.1 dB, from Contour's floor (Preamp.FLOOR_DB, -30 dB) instead of the register's -128.
        // The read layout and the write are from the vendor tool, not yet proven on the unit.
        DacSetting(PRE_GAIN, "PRE GAIN", SettingKind.LEVEL, EvidenceRank.VB, min = (maxOf(MoondropCatalog.dawnPro2.preGainMinDb, io.github.chronosauros.contour.core.Preamp.FLOOR_DB) * 10).roundToInt(),
            max = (MoondropCatalog.dawnPro2.preGainMaxDb * 10).roundToInt(), unit = "dB", group = 0,
            read = mark(MoondropCodec.CMD_PRE_GAIN), writeCommand = MoondropCodec.CMD_PRE_GAIN, writeVerified = false, scale = 10),
    )

    /**
     * The EQ switch row of a KT model, register 0x24. writeVerified here means: a write is accepted by the page and then proven by a read after the DAC re-enumerates
     * (KtMicroSession.writeSwitch). Allegro Mini: PC bench passed; the first phone run (runs/2026-10-22-kiwi-ears-allegro-mini-01-phone) could not open the 10-byte HID report, fixed in 289fbfb, and the phone then
     * switched EQ OFF and ON with a VERIFIED read-back each time (Pixel run 2026-10-24-kiwi-ears-allegro-mini-04-phone): on in every build. The JM12's register was only READ on the unit (10.11.2026): its
     * write is for a review build until the bench and the phone have proven it.
     */
    private fun ktSwitch(writeVerified: Boolean) = DacSetting(EQ_SWITCH, "EQ", SettingKind.TOGGLE, EvidenceRank.HW, options = listOf("OFF", "ON"), group = 0,
        read = mark(KtMicroCodec.EQ_ENABLE_REGISTER), writeCommand = KtMicroCodec.EQ_ENABLE_REGISTER, writeVerified = writeVerified)

    /** The Global Gain row of a KT model: the model's steps; [writeVerified] as for the EQ switch (the Allegro Mini's passed on the unit; the JM12's was only measured in RAM). */
    private fun ktGlobalGain(model: KtMicroCatalog.Model, writeVerified: Boolean) = ktGlobalGainRow(requireNotNull(model.globalGainUserSteps), writeVerified)

    /** A KT model without Global Gain: the EQ switch alone (no model today; the generator allows it). */
    private val ktSwitchOnly = listOf(ktSwitch(writeVerified = false))

    private val allegro = listOf(
        ktSwitch(writeVerified = true),
        // Register 0x3B, the vendor tool's GLOBAL GAIN (analog DAC gain): the record's steps (13 = 0 dB per the vendor tool); the page shows the step as its dB (1-15 = -18.0..+3.0 dB in 1.5 dB steps), the value and the write stay the step. The write (KtMicroSession.writeGlobalGain,
        // step 0 never) passed the PC bench: runs/2026-10-21-kiwi-ears-allegro-mini-02 T14, 13 -> 12 -> 13, each written, saved and read back after the DAC re-enumerated. Like the EQ switch,
        // the phone then wrote steps 1, 15 and 13 with a VERIFIED read-back each (Pixel run 2026-10-24-kiwi-ears-allegro-mini-04-phone), so writeVerified is true.
        ktGlobalGain(KtMicroCatalog.models.first(), writeVerified = true),
    )

    /**
     * The JCally JM12: the same two rows, writes on (the phone run 2026-11-10-jcally-jm12-11-phone verified the EQ switch OFF and ON). The EQ switch was written and verified in every bench run (runs 2026-11-10-jcally-jm12-02 and -03, the EQ played in the acoustic runs); register 0x3B took
     * [step, 0, 0, 0] for steps 1-15 and the level followed it by about 1.5 dB per step (run -06), and the Global Gain with SAVE passed 9 -> 8 -> 9 on the Mac (run -10, T14). The unit ships at step 9.
     */
    private val jm12 = listOf(ktSwitch(writeVerified = true), ktGlobalGain(KtMicroCatalog.jm12Models.first(), writeVerified = true))

    /** The Kiwi Ears AD1: the JM12's two rows, both writes on. The Global Gain was written 13 -> 12 -> 13, saved, read back after the reconnect and restored on the unit (run 2026-11-13-kiwi-ears-ad1-01-mac T14); the EQ switch OFF was written from the DAC page on a phone and read back VERIFIED (phone check 13.11.2026). */
    private val ad1 = listOf(ktSwitch(writeVerified = true), ktGlobalGain(KtMicroCatalog.ad1Models.first(), writeVerified = true))

    /**
     * The preset row of a Moondrop gen-1 cable: the Custom EQ and the firmware presets of the record (`slots.labels`), when the record offers the stock picker (`slots.stockPicker`). Choosing one selects it
     * (`5a 00 [5a, idx]`, the commit `dc 00 [ff]`) and reads the index back ([MoondropGen1Session.selectPreset]); a send always goes to the Custom EQ and leaves the cable on it. The row's write is
     * for a review build until a unit has taken it (`driver.hardwareVerified` of the record opens it everywhere).
     */
    private fun gen1(model: MoondropGen1Catalog.Model): List<DacSetting> =
        if (!model.stockPicker) emptyList() else listOf(DacSetting(PRESET, "PRESET", SettingKind.CHOICE, EvidenceRank.OS,
            options = model.presetLabels.toSortedMap().values.map { it.uppercase(java.util.Locale.ROOT) }, group = 0, inlineChoice = model.stockPickerInline,
            read = mark(MoondropGen1Codec.CMD_PRESET), writeCommand = MoondropGen1Codec.CMD_PRESET, writeVerified = model.hardwareVerified))

    private val dusk = gen1(MoondropGen1Catalog.duskSp)

    fun of(target: DeviceTarget): List<DacSetting> = when {
        target.moondrop != null -> dawn
        target.gen1 != null -> dusk
        target.ktMicro != null -> when {
            target.ktMicro.globalGainUserSteps == null -> ktSwitchOnly
            target.ktMicro in KtMicroCatalog.jm12Models -> jm12
            target.ktMicro in KtMicroCatalog.ad1Models -> ad1
            else -> allegro
        }
        else -> emptyList()
    }
}

private fun ktGlobalGainRow(steps: IntRange, writeVerified: Boolean) =
    DacSetting(FamilySettings.GLOBAL_GAIN, "GLOBAL GAIN", SettingKind.LEVEL, EvidenceRank.VB, min = steps.first, max = steps.last, unit = "dB", group = 1,
        read = SettingRead.WalkPlayGet(KtMicroCodec.GLOBAL_GAIN_REGISTER) { null }, writeCommand = KtMicroCodec.GLOBAL_GAIN_REGISTER, writeVerified = writeVerified, valueText = ::globalGainText)

/** The KT Micro Global Gain step as its dB, the way the other dB rows read: `-18.0`, `0.0`, `+3.0` (the unit is the row's); a value outside the steps stays the number. */
internal fun globalGainText(step: Int): String {
    if (step !in KtMicroCodec.GLOBAL_GAIN_STEP_MIN..KtMicroCodec.GLOBAL_GAIN_STEP_MAX) return step.toString()
    val db = KtMicroCodec.globalGainDb(step)
    return if (db > 0) String.format(java.util.Locale.ROOT, "+%.1f", db) else String.format(java.util.Locale.ROOT, "%.1f", db + 0.0)
}
