package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.ProtocolMicro
import io.github.chronosauros.contour.core.WalkPlay

/**
 * The MAX VOLUME row of the DAC page (owner 25.11.2026, sketch A: a DAC page for the Micro with one slider, -30..0 dB in whole dB). Not a register of its own: the value is
 * the cut Contour adds under every profile's preamp ([ProtocolMicro.trimmedRegister]), kept per DAC by the app. A move of the slider sends again what is ON DAC under the
 * new value through the proven write sequence (bands, the preamp frame `01 03 02 00 g`, commit) and reads it back; the DAC keeps it in flash with the EQ.
 * It is never part of [DeviceTarget.settings], so no settings read asks a DAC for it.
 */
object VolumeTrim {
    const val ID = "max-volume"

    /** [SettingRead] marks the row as one with a value (the page shows it); nothing is ever read through it. [DacSetting.writeCommand] is the preamp frame the value goes out in. */
    val ROW = DacSetting(
        ID, "MAX VOLUME", SettingKind.LEVEL, EvidenceRank.HW, min = ProtocolMicro.TRIM_MIN_DB, max = 0, unit = "dB", group = 0,
        read = SettingRead.WalkPlayGet(WalkPlay.CMD_GLOBAL_GAIN) { null }, writeCommand = WalkPlay.CMD_GLOBAL_GAIN, writeVerified = true,
    )

    /** The page rows MAX VOLUME adds for [target]: the row for the Protocol Micro ([DeviceTarget.takesVolumeTrim]), none for any other DAC. */
    fun rows(target: DeviceTarget): List<DacSetting> = if (target.takesVolumeTrim) listOf(ROW) else emptyList()
}
