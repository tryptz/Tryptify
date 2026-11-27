package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Proven

/** How the DAC Settings page draws a setting: OFF | ON well, 2-3 option well (more: a button that opens a list), a slider, a centred slider. */
enum class SettingKind { TOGGLE, CHOICE, LEVEL, BALANCE }

/** Where the bytes of a setting come from, best first (research/dac-settings-2026-10-17/INVENTORY.md). Any rank below HW is not proof the DAC does it. */
enum class EvidenceRank { HW, VB, VB_MINUS, OS, MP, CM }

/**
 * How a setting is read, per source. The page and the controller know only [DacSetting]; the reader that runs a [SettingRead] is
 * chosen by the device family (the FiiO/SnowSky frame session today). A third source is meant to fit here later - standard USB Audio
 * Class controls (hardware volume, per-channel volume) that any USB DAC may have - as one more case; it is not built yet.
 */
sealed interface SettingRead {
    /** FiiO frame `bb 0b SEQ cmd 00 CRC ee` on report 7 (FiiO, SnowSky). [decode] gets the logical reply (see [FiioCodec.settingReply]). */
    data class FiioGet(val command: Int, val decode: (ByteArray) -> Int?, val replyData: Int = 1) : SettingRead
    /** WalkPlay report 0x4B, `d[0]` = 0x80 read (Protocol Micro, Max). No WalkPlay setting has a reader yet: the lists are empty. */
    data class WalkPlayGet(val command: Int, val decode: (ByteArray) -> Int?) : SettingRead
}

/**
 * One setting a DAC offers, as the DAC Settings page shows it. The page is generated from a device's list of these; a device
 * with an empty list gets no page. The app sends [writeCommand] only for a setting whose write passed the self-test on that
 * model's real DAC ([writeVerified]); every other setting stays read-only.
 *
 * A [SettingRead]'s decoder returns the value, or null when the bytes make no sense. Values: TOGGLE 0/1, CHOICE the
 * option index, LEVEL the raw number, BALANCE negative = the sound moves to the left (the right channel is quieter by that many steps),
 * positive = to the right, 0 = centre - the side FiiO Control names.
 */
data class DacSetting(
    val id: String,
    val label: String,
    val kind: SettingKind,
    val evidence: EvidenceRank,
    /** TOGGLE: OFF, ON. CHOICE: 2-8 labels (more than 3 are chosen from a list). */
    val options: List<String> = emptyList(),
    /** CHOICE: the short form of each option for tight places (the row's value text); empty = the options themselves. */
    val shortOptions: List<String> = emptyList(),
    /** LEVEL: the slider range (BALANCE: -[max]..[max]). Provisional where the DAC's own range is unknown. */
    val min: Int = 0,
    val max: Int = 0,
    val step: Int = 1,
    val unit: String = "",
    /** Rows with the same group sit together; a gap separates the groups. */
    val group: Int = 0,
    /** A small caps header above the first row of the section; null = none. */
    val section: String? = null,
    val readCommand: Int? = null,
    /** null = no known read: the page shows the control dimmed with no value. */
    val read: SettingRead? = null,
    val writeCommand: Int? = null,
    val writeVerified: Boolean = false,
    /** LEVEL: the slider's top follows another setting's value (the volume top follows VOLUME STEPS); [max] is the fallback. */
    val maxRule: MaxRule? = null,
    /** LEVEL: the value is in 1/[scale] of the [unit] (tenths of a dB = 10); the page shows value / scale. 1 = the value itself. */
    val scale: Int = 1,
    /** LEVEL: how the page words a value whose raw number is not what a person reads (the Allegro Mini's Global Gain step 13 is 0.0 dB); null = the value, divided by [scale]. Display only: the register value and the write are the raw number. */
    val valueText: ((Int) -> String)? = null,
    /** The DAC leaves the USB bus after this row's write and comes back by itself (the JA11's in-line mic switch re-enumerates the audio interfaces): the write has no echo or read-back in the same session, the value counts only after a read once the DAC is back. From the record's settings row (`reconnectsAfterWrite`). */
    val reconnectsAfterWrite: Boolean = false,
    /** CHOICE: the DAC page shows every option as a full-width button on the page itself (no dropdown or sheet): a few options the person picks between all the time (the DUSK-SP presets, record `slots.stockPickerInline`). */
    val inlineChoice: Boolean = false,
) {
    init {
        require(id.isNotBlank() && label.isNotBlank())
        (read as? SettingRead.FiioGet)?.let { require(it.command in 0..255 && it.command != RESET_TO_FACTORY) { "$id: bad read command" } }
        require(writeCommand == null || (writeCommand in 0..255 && writeCommand != RESET_TO_FACTORY)) { "$id: 0x21 is RESET_TO_FACTORY" }
        when (kind) {
            SettingKind.TOGGLE -> require(options.size == 2)
            SettingKind.CHOICE -> require(options.size in 2..8 && (shortOptions.isEmpty() || shortOptions.size == options.size))
            SettingKind.LEVEL -> require(step > 0 && min < max && scale >= 1)
            SettingKind.BALANCE -> require(step > 0 && max > 0)
        }
    }
    /**
     * The app writes this row: it has a write frame the DAC may take (USB AUDIO 0x20 never), and the write is proven ([writeVerified]) or this is a review build ([Proven.on]).
     * The one test every write path and every control asks; the flag stays the row's own.
     */
    val writeEnabled: Boolean get() = writeCommand != null && writeCommand !in DacWrite.READ_ONLY && Proven.on(writeVerified)
    /**
     * The row can never be written, in any build: it has no write command, or its command is one no build sends (USB AUDIO 0x20, [DacWrite.READ_ONLY]: switching it re-enumerates the DAC).
     * Unlike an unproven row ([writeVerified] false, which a review build opens through [Proven.on]) the page draws it as a value with no control.
     */
    val readOnly: Boolean get() = writeCommand == null || writeCommand in DacWrite.READ_ONLY
    /** A CHOICE with more options than fit side by side: the row shows the current one and opens a list. */
    val longChoice: Boolean get() = kind == SettingKind.CHOICE && options.size > 3
    /** The text of option [i] where space is short. */
    fun shortOption(i: Int): String = shortOptions.getOrNull(i) ?: options.getOrElse(i) { "--" }
    /** The slider top for the values read: the rule's maximum for the value of its setting, else [max]. */
    fun maxFor(valueOf: (String) -> Int?): Int = maxRule?.let { r -> valueOf(r.settingId)?.let { r.maxima.getOrNull(it) } } ?: max
    /** [maxima] by the value (option index) of the setting [settingId]. */
    data class MaxRule(val settingId: String, val maxima: List<Int>)
    private companion object { const val RESET_TO_FACTORY = 0x21 }
}

/** A read that is not a setting but says something about the DAC: shown in the page header (the sample rate, the firmware). */
data class DacInfo(
    val id: String,
    val command: Int,
    /** Data bytes the reply must have at least. */
    val replyData: Int,
    val decode: (ByteArray) -> Int?,
    val format: (Int) -> String,
)

/** One row of a device record's `settings` list (in devices/): the template [id] and where it sits on that model's page. Emitted by the generator. */
internal data class SettingPlacement(val id: String, val section: String?, val group: Int, val writeVerified: Boolean, val reconnectsAfterWrite: Boolean = false)

/** The setting templates (commands, ranges, options, decoders). Which settings a device has, where they sit and which are write-verified is in its record (in devices/): [forModel]. Only what FiiO Control's own config lists for the model; everything is read-only so far. */
object DacSettings {
    private fun u(b: Byte) = b.toInt() and 255

    /** One data byte 0 or 1 (a[6]); anything else is not a toggle or a two-way choice. */
    private val toggle: (ByteArray) -> Int? = { a -> u(a[6]).takeIf { it in 0..1 } }

    /** A choice of [count] options: the data byte a[6] when it is one of them. */
    private fun choice(count: Int): (ByteArray) -> Int? = { a -> u(a[6]).takeIf { it in 0 until count } }

    /** Volume and brightness: the raw byte a[6]. */
    private val rawByte: (ByteArray) -> Int? = { a -> u(a[6]) }

    /**
     * Balance, as FiiO Control shows it (hardware, Melody 17.10.2026: its "L3" reads `00 03`): a[6] = left, a[7] = right attenuation steps;
     * only one side is ever attenuated. The value names the side the sound moves to: (0, n) = -n = "L n", (n, 0) = +n = "R n".
     */
    private val balance: (ByteArray) -> Int? = { a ->
        val left = u(a[6]); val right = u(a[7])
        when {
            left > 0 && right > 0 -> null
            right > 0 -> -right
            else -> left
        }
    }

    private fun get(cmd: Int, decode: (ByteArray) -> Int?, data: Int = 1) = SettingRead.FiioGet(cmd, decode, data)

    // The commands, ranges and option labels are FiiO Control's own config for the model (FIIO-CONTROL-APP.md); the reads were checked against what
    // FiiO Control showed on the Tiny A, Melody and KA15 (CAPTURES.md). The balance range is its -12..12. The volume top follows VOLUME STEPS
    // (60 when 0, 120 when 1, 60 when it gives no reply). The Melody and the Tiny A double the volume value when switched to 120 (38 -> 76, same level) and
    // halves it back (hardware, 17.10.2026).
    private val volume = DacSetting("volume", "VOLUME", SettingKind.LEVEL, EvidenceRank.VB_MINUS, min = 0, max = 60, group = 0, section = null,
        read = get(0x02, rawByte), writeCommand = 0x02, maxRule = DacSetting.MaxRule("volume-steps", listOf(60, 120)))
    private val volumeSteps = DacSetting("volume-steps", "VOLUME STEPS", SettingKind.CHOICE, EvidenceRank.VB_MINUS, options = listOf("60", "120"), group = 0,
        read = get(0x0C, choice(2)), writeCommand = 0x0C)
    private val balanceSetting = DacSetting("balance", "BALANCE", SettingKind.BALANCE, EvidenceRank.VB_MINUS, max = 12, group = 0,
        read = get(0x07, balance, data = 2), writeCommand = 0x07)
    private fun mic(group: Int) = DacSetting("mic", "IN-LINE MIC", SettingKind.TOGGLE, EvidenceRank.VB_MINUS, options = listOf("OFF", "ON"), group = group,
        read = get(0x12, toggle), writeCommand = 0x12)
    private fun light(group: Int) = DacSetting("light", "LIGHT", SettingKind.TOGGLE, EvidenceRank.VB_MINUS, options = listOf("OFF", "ON"), group = group,
        read = get(0x06, toggle), writeCommand = 0x06)
    private fun uac(group: Int) = DacSetting("uac", "USB AUDIO", SettingKind.CHOICE, EvidenceRank.VB_MINUS, options = listOf("UAC 1.0", "UAC 2.0"), group = group,
        read = get(0x20, choice(2)), writeCommand = 0x20)
    // FILTER: value 4 = non over-sampling is confirmed on hardware (Melody and KA15 both read 04 while FiiO Control showed NOS). Values 0..3 follow the
    // order of FiiO Control's list (UI top to bottom) - inferred, not seen on hardware, hence the rank stays below HW.
    private val filter = DacSetting("filter", "FILTER", SettingKind.CHOICE, EvidenceRank.VB_MINUS,
        options = listOf("MINIMUM PHASE FAST", "LINEAR PHASE FAST", "MINIMUM PHASE SLOW", "LINEAR PHASE SLOW", "NON OVER-SAMPLING"),
        shortOptions = listOf("MIN F", "LIN F", "MIN S", "LIN S", "NOS"), group = 0,
        read = get(0x03, choice(5)), writeCommand = 0x03)
    private val gain = DacSetting("gain", "GAIN", SettingKind.CHOICE, EvidenceRank.VB_MINUS, options = listOf("HIGH", "LOW"), group = 0,
        read = get(0x04, choice(2)), writeCommand = 0x04)
    // 0x05 is titled factory_mode in FiiO Control's config but its options are Class AB / Class H.
    private val output = DacSetting("output", "OUTPUT", SettingKind.CHOICE, EvidenceRank.VB_MINUS, options = listOf("CLASS AB", "CLASS H"), group = 0,
        read = get(0x05, choice(2)), writeCommand = 0x05)
    private val dre = DacSetting("dre", "DRE", SettingKind.TOGGLE, EvidenceRank.VB_MINUS, options = listOf("OFF", "ON"), group = 0,
        read = get(0x32, toggle), writeCommand = 0x32)
    private val buttons = DacSetting("buttons", "BUTTONS", SettingKind.CHOICE, EvidenceRank.VB_MINUS, options = listOf("MODE A", "MODE B"), group = 1,
        read = get(0x0D, choice(2)), writeCommand = 0x0D)
    private val brightness = DacSetting("brightness", "BRIGHTNESS", SettingKind.LEVEL, EvidenceRank.VB_MINUS, min = 0, max = 10, group = 2,
        read = get(0x0E, rawByte), writeCommand = 0x0E)
    private val screenOff = DacSetting("screen-off", "SCREEN OFF", SettingKind.CHOICE, EvidenceRank.VB_MINUS,
        options = listOf("15 S", "30 S", "1 MIN", "2 MIN", "5 MIN", "10 MIN", "30 MIN"), group = 2,
        read = get(0x0F, choice(7)), writeCommand = 0x0F)
    private val rotation = DacSetting("rotation", "ROTATION", SettingKind.TOGGLE, EvidenceRank.VB_MINUS, options = listOf("OFF", "ON"), group = 2,
        read = get(0x10, toggle), writeCommand = 0x10)
    private val language = DacSetting("language", "LANGUAGE", SettingKind.CHOICE, EvidenceRank.VB_MINUS, options = listOf("CHINESE", "ENGLISH"), group = 2,
        read = get(0x11, choice(2)), writeCommand = 0x11)

    /** Every template by id. A device record (in devices/) lists ids and where each sits; the decoders and commands stay here. */
    private val templates: Map<String, DacSetting> = listOf(
        volume, volumeSteps, balanceSetting, mic(0), light(0), uac(0), filter, gain, output, dre, buttons, brightness, screenOff, rotation, language,
    ).associateBy { it.id }

    /**
     * A device's settings list from its record: each id's template with the record's section, group and write flag. Placement means
     * [DacSetting.section], [DacSetting.group] and [DacSetting.writeVerified] only (passed the write self-test on that model's real DAC:
     * SET, echo, read-back, restore, read-back - CAPTURES.md, 17.10.2026; per model, not per command - the KA15 stopped answering after a
     * FILTER write that the Melody takes). Called by the generated catalog (core/devices/GeneratedDevices).
     */
    internal fun forModel(placements: List<SettingPlacement>): List<DacSetting> = placements.map { p ->
        val template = templates[p.id] ?: error("no DAC setting template for the id \"${p.id}\" (devices/)")
        // A slider whose top follows another row (the volume follows VOLUME STEPS) keeps that rule only when the model has the row: without it the top is the template's own max
        // (the JA11 has no VOLUME STEPS row and its volume is 0-60, so DacWrite.hardMax must not allow 120).
        val rule = template.maxRule?.takeIf { r -> placements.any { it.id == r.settingId } }
        template.copy(section = p.section, group = p.group, writeVerified = p.writeVerified, maxRule = rule, reconnectsAfterWrite = p.reconnectsAfterWrite)
    }

    // ---- header info: read with the settings, shown after the DAC's name -------------------------------------------------------

    private val RATES = listOf("32 kHz", "44.1 kHz", "48 kHz", "88.2 kHz", "96 kHz", "176.4 kHz", "192 kHz", "352.8 kHz", "384 kHz", "705.6 kHz", "768 kHz",
        "DSD64", "DSD128", "DSD256", "DSD512")

    /** 0x09: the sample rate now playing (FIIO-CONTROL-APP.md: 0 = 32k ... 10 = 768k, 11..14 = DSD64..DSD512). */
    val SAMPLE_RATE = DacInfo("rate", 0x09, 1, rawByte) { RATES.getOrNull(it) ?: "$it" }

    /** 0x0B: three data bytes major, minor, patch (the reply has no CRC byte); encoded as major * 10000 + minor * 100 + patch. */
    val FIRMWARE = DacInfo("fw", 0x0B, 3, { a -> u(a[6]) * 10_000 + u(a[7]) * 100 + u(a[8]) }) { "FW ${it / 10_000}.${it / 100 % 100}.${it % 100}" }

    /** 0x0B on a model whose reply carries two data bytes, major and minor, then a CRC byte (FiiO JA11: `bb 0b 00 00 0b 02 02 14 b6 ee` = 2.20); encoded as major * 100 + minor. */
    val FIRMWARE_2 = DacInfo("fw2", 0x0B, 2, { a -> u(a[6]) * 100 + u(a[7]) }) { "FW ${it / 100}.${"%02d".format(it % 100)}" }

    private val infos: Map<String, DacInfo> = listOf(SAMPLE_RATE, FIRMWARE, FIRMWARE_2).associateBy { it.id }

    /** The header reads a device record lists by id (devices/), in the record's order. */
    internal fun infoFor(ids: List<String>): List<DacInfo> = ids.map { infos[it] ?: error("no DAC info read for the id \"$it\" (devices/)") }
}
