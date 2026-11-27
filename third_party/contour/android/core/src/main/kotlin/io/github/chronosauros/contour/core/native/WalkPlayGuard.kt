package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.DeviceProtocol
import io.github.chronosauros.contour.core.WalkPlay

/**
 * The WalkPlay frames that are never sent, whatever a caller asks (walkplay-plan.md, "Lista zakazow"). [WalkPlaySession] checks every request and every
 * send against this single list before the port sees it, so a frame that is on it cannot leave through the session whoever built it.
 *
 * Refused in every context:
 *  - the unresolved reset and probe candidates of other WalkPlay classes: `05 01 02 00 FF`, `05 01 01 00 FF` (MT2000), `02 00 00 01 FF` (CB1100AU), `80 00 00 00 FF`;
 *  - the CB1300D frames (`01 60`, `80 DA 00`, `01 22`, `01 D3`, `01 24`, `80 22`, `80 D3`, `80 24`, `80 26`, `80 25`) and the firmware update (`01 FF`);
 *  - the noise-reduction write `01 1B` (disabled for class CB; never on the TRN).
 *
 * Refused outside a ticketed factory reset ([Context.RESET], see [WalkPlayReset]):
 *  - `01 05 01` .. `01 05 04` (meaning unknown; the vendor sends them only inside its reset). `01 05 xx` with any other `xx` is refused too;
 *  - `01 05 00` and `01 17 00` on the TRN (its write never sends them). On the Micro, the Max and the Echo-A they are steps of [WalkPlay.COMMIT_SEQUENCE]
 *    (devicePEQ's commit, hardware-proven on the Micro): they stay allowed there, as part of that commit, and nowhere else is one built;
 *  - a POSITIVE preamp (`01 03 02 00 g`, g above 0) unless the profile's [WalkPlayProfile.preampMaxDb] is above 0 (never in the app: [ProtocolMicro.MICRO_POSITIVE_PREAMP_VERIFIED], [ProtocolMicro.ECHO_A_POSITIVE_PREAMP_VERIFIED]).
 *
 * Settings writes follow the tabs WalkPlay shows for the scheme: `01 11`, `01 16` and `01 1D` on the TRN and the Protocol Max (SchemeNo16, the DAC Filter tab, see
 * [WalkPlaySettings]), `01 19` (gain mode) on the TRN and the Tanchjim Space II only (the Max has a physical Eco / Boost switch that wins after every replug; the Space II kept LOW over a replug); `01 02` (mic) on every WalkPlay DAC (the MIC tab; the Max
 * exposes no audio input, its row is vendor parity); `01 32` (DRE) and `01 85` (the DAC's own volume) on the Tanchjim Space II only; nothing else. `01 01 00` (the flash save after a mic write) goes to the Max only in [Context.MIC_SAVE]: there `01 01 x` is also the EQ enable / flash frame.
 * `01 04` (the flash save after a Space II volume write) goes to the Space II only in [Context.VOLUME_SAVE]; the Micro, the Max and the Echo-A never get it. The TRN's own write sequence ends with a `01 04 00` of its
 * own (offset and preamp flash, [WalkPlay.trnWriteSequence], proven on its unit): that one stays allowed on the TRN, which has no volume row.
 */
object WalkPlayGuard {
    /** [MIC_SAVE]: the flash save `01 01 00` right after a mic write ([WalkPlaySession.writeSetting]); the one place a Max may be sent it. */
    enum class Context { NORMAL, RESET, MIC_SAVE, VOLUME_SAVE }

    // [VOLUME_SAVE]: the flash save `01 04` right after a Space II volume write ([WalkPlaySession.writeSetting]); the one place anything is sent it.

    private val CANDIDATES: List<List<Int>> = listOf(
        listOf(0x05, 0x01, 0x02, 0x00, 0xFF), listOf(0x05, 0x01, 0x01, 0x00, 0xFF), listOf(0x02, 0x00, 0x00, 0x01, 0xFF), listOf(0x80, 0x00, 0x00, 0x00, 0xFF),
    )
    private val CB1300D_WRITES = setOf(0x60, 0x22, 0xD3, 0x24)
    private val CB1300D_READS = setOf(0xDA, 0x22, 0xD3, 0x24, 0x26, 0x25)

    private fun u(p: ByteArray, i: Int) = if (i < p.size) p[i].toInt() and 255 else 0
    private fun zerosFrom(p: ByteArray, from: Int) = (from until p.size).all { p[it].toInt() == 0 }

    /**
     * The EPZ TP65 (tester entry, issue #6) gets an allow-list instead, in every context: the reads its owner saw answered on the unit (version `80 0C 00`, the slot byte `80 09 00`,
     * band `80 09 00 00 i 00`, the preamp `80 03 01`) and the two writes validated on it, a band frame `01 09 18 00 i 00 00` (i 0..9) and the RAM apply `01 0A 04 00 00 FF FF`.
     * Anything else (the preamp write, `01 05`, `01 17`, the flash `01 01 x`, `01 04`, every setting, reset and probe frame) never leaves for it.
     */
    private fun tp65Refusal(p: ByteArray, profile: WalkPlayProfile): String? {
        val op = u(p, 0)
        val cmd = u(p, 1)
        val bands = profile.bands
        val ok = when (op) {
            WalkPlay.READ -> when (cmd) {
                WalkPlay.CMD_VERSION -> zerosFrom(p, 2)
                // band i: 80 09 00 00 i 00; the slot byte request 80 09 00 is band 0's, byte for byte (zero padded)
                WalkPlay.CMD_PEQ -> u(p, 2) == 0 && u(p, 3) == 0 && u(p, 4) in 0 until bands && zerosFrom(p, 5)
                WalkPlay.CMD_GLOBAL_GAIN -> u(p, 2) == 0x01 && zerosFrom(p, 3)
                else -> false
            }
            WalkPlay.WRITE -> when (cmd) {
                WalkPlay.CMD_PEQ -> u(p, 2) == WalkPlay.BAND_LEN && u(p, 3) == 0 && u(p, 4) in 0 until bands && u(p, 5) == 0 && u(p, 6) == 0
                0x0A -> p.contentEquals(WalkPlay.TP65_APPLY.copyOfRange(1, WalkPlay.REPORT_SIZE))
                else -> false
            }
            else -> false
        }
        return if (ok) null else "${hex(p.take(7).map { it.toInt() and 255 })} is not a frame validated on the ${profile.name} (only its band reads and writes, the version, the slot byte, the preamp read 80 03 01 and the RAM apply 01 0A leave for it): never sent"
    }

    /** Why [payload] (the bytes after the report id, `d[0]` first) must not leave in [context] for [profile], or null. */
    fun refusal(payload: ByteArray, profile: WalkPlayProfile, context: Context): String? {
        if (profile.protocol == DeviceProtocol.EPZ_TP65) return tp65Refusal(payload, profile)
        val op = u(payload, 0)
        val cmd = u(payload, 1)
        for (c in CANDIDATES) if (c.indices.all { u(payload, it) == c[it] }) return "reset/probe candidate of another WalkPlay class (${hex(c)}): never sent"
        if (op == WalkPlay.WRITE) {
            if (cmd in CB1300D_WRITES) return "0x%02x is a CB1300D frame: never sent".format(cmd)
            if (cmd == 0xFF) return "01 FF is the firmware update: never sent"
            if (cmd == 0x1B) return "01 1B (noise reduction) is disabled for class CB: never sent"
            val trn = profile.protocol == DeviceProtocol.TRN
            // the Tanchjim Space II is held to the Max's rules (the stricter of the two SchemeNo16 DACs) for 01 01 00 (only as a mic save); its gain mode is a real setting (it kept LOW over a replug, 13.11.2026)
            val maxLike = profile.protocol == DeviceProtocol.MAX || profile.protocol == DeviceProtocol.SPACE_2
            val schemeNo16 = trn || maxLike
            when (cmd) {
                0x05 -> {
                    val sub = u(payload, 2)
                    if (sub in 1..4) { if (context != Context.RESET) return "01 05 0$sub is the factory reset's: sent only inside a confirmed reset" }
                    else if (sub != 0 || (trn && context != Context.RESET)) return "01 05 %02x is not a step of ${profile.name}'s write".format(sub)
                }
                0x17 -> if (u(payload, 2) != 0 || (trn && context != Context.RESET)) return "01 17 is not a step of ${profile.name}'s write"
                WalkPlay.CMD_GLOBAL_GAIN -> {
                    val g = payload.getOrNull(4)?.toInt() ?: 0
                    if (g > 0 && profile.preampMaxDb <= 0) return "a positive preamp ($g dB) is not verified on a unit: never sent"
                }
                WalkPlaySettings.CMD_GAIN_MODE ->
                    if (!trn && profile.protocol != DeviceProtocol.SPACE_2) return "0x%02x (gain mode) is not a setting of ${profile.name}: ".format(cmd) + if (profile.protocol == DeviceProtocol.MAX) "its physical Eco / Boost switch sets it and wins after every replug" else "WalkPlay shows it for SchemeNo16 only"
                WalkPlaySettings.CMD_DRE, WalkPlaySettings.CMD_VOLUME ->
                    if (profile.protocol != DeviceProtocol.SPACE_2) return "0x%02x is a setting of the Tanchjim Space II only, not of ${profile.name}".format(cmd)
                WalkPlaySettings.CMD_VOLUME_SAVE ->
                    if (!trn && (profile.protocol != DeviceProtocol.SPACE_2 || context != Context.VOLUME_SAVE)) return "01 04 is sent to the Tanchjim Space II only as the flash save right after its volume write (the TRN's write sequence has its own); never to ${profile.name} otherwise"
                WalkPlaySettings.CMD_DAC_FILTER, WalkPlaySettings.CMD_WORK_MODE, WalkPlaySettings.CMD_BALANCE ->
                    if (!schemeNo16) return "0x%02x is not a setting of ${profile.name} (WalkPlay shows it for SchemeNo16 only)".format(cmd)
                // the vendor's flash save after a mic write: on a Max `01 01 x` is also the EQ enable / flash frame, so `01 01 00` goes out only as that save (it kept the mic over a replug and left the EQ alone, dac-lab run 2026-11-11-crinear-protocol-max-23)
                0x01 -> if (maxLike && u(payload, 2) == 0 && context != Context.MIC_SAVE) return "01 01 00 is sent to ${profile.name} only as the flash save after a mic write: its `01 01 x` is also the EQ enable / flash frame"
            }
        } else if (op == WalkPlay.READ && cmd in CB1300D_READS) return "0x%02x is a CB1300D read: never sent".format(cmd)
        return null
    }

    private fun hex(b: List<Int>) = b.joinToString(" ") { "%02X".format(it) }
}
