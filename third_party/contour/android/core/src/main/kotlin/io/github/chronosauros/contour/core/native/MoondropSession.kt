package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.WalkPlay

/**
 * Reads, selects and writes a native Moondrop DAC (Dawn Pro 2) through a [NativePort].
 *
 * An EQ write is MOONDROP Hub's "Write Cfg", frame for frame ([MoondropCodec.writeSteps]): the Hub sends `01 0F 01 07`, the bands tagged with the writable bank
 * ([MoondropCatalog.Model.writableIndex], 7), the commit, the gains and the save, and the bands of the bank the unit is on take the write (the owner's unit, firmware 1.5,
 * ignores the switch and stays on index 9: captured and read back 24.10.2026). Contour's banks are the record's set of EQ indexes ([MoondropCatalog.Model.writableIndexes]: 7 and 9):
 * a write starts only when the read before shows one of them (on a preset index nothing is sent), does not require the switch to take, and is verified by reading the eight bands
 * back and comparing them (and the pre-gain when it reads), whatever index the unit reports after. The index found before ([lastBefore]) and the one found after are logged and are
 * in [Snapshot.activeIndex] of the read-back.
 * [selectIndex], [readWritableBank] and [enableAllPeq] stay as bench tools.
 */
class MoondropSession(val model: MoondropCatalog.Model, private val port: NativePort) {
    private val io = NativeIo(port)

    private fun reply(frame: MoondropCodec.Frame, inputBytes: Int, bank: () -> Int?): MoondropCodec.Reply {
        val bytes = io.request(frame.reportId, frame.payload) { p -> runCatching {
            val r = MoondropCodec.Reply(port.deviceKey, frame.reportId, p)
            when (frame.kind) {
                MoondropCodec.Kind.SLOT -> MoondropCodec.parseSlot(r, port.deviceKey, inputBytes)
                MoondropCodec.Kind.BAND -> MoondropCodec.parseBand(r, port.deviceKey, model, requireNotNull(frame.index), requireNotNull(bank()), inputBytes)
                MoondropCodec.Kind.GLOBAL_GAIN -> MoondropCodec.parseGain(r, port.deviceKey, inputBytes, MoondropCodec.CMD_GLOBAL_GAIN)
                MoondropCodec.Kind.PRE_GAIN -> MoondropCodec.parseGain(r, port.deviceKey, inputBytes, MoondropCodec.CMD_PRE_GAIN)
                MoondropCodec.Kind.VERSION -> MoondropCodec.parseVersion(r, port.deviceKey, inputBytes)
                else -> error("Not an automatic read query")
            }
        }.isSuccess }
        return MoondropCodec.Reply(port.deviceKey, frame.reportId, bytes)
    }

    private fun inputBytes(): Int {
        val inputBytes = port.payloadSize(model.reportId, NativeHidReports.Kind.INPUT)
        val frames = MoondropCodec.readQueries(model)
        require(inputBytes >= 36 && port.payloadSize(model.reportId, NativeHidReports.Kind.OUTPUT) >= frames.maxOf { it.minimumDescriptorOutputPayloadBytes }) {
            "The HID report descriptor lacks the complete Moondrop report 0x4B"
        }
        return inputBytes
    }

    /** EQ index, all eight bands of the active bank, global gain, EQ index again; with [withPreGain] the pre-gain (0x23) as a last,
     * best-effort read (its reply layout is unproven: no valid answer leaves [MoondropCodec.Snapshot.preGain] null). */
    fun read(withPreGain: Boolean = false): MoondropCodec.Snapshot = io.guarded {
        val inBytes = inputBytes()
        var slot: Int? = null
        val replies = MoondropCodec.readQueries(model).map { frame ->
            reply(frame, inBytes) { slot }.also {
                if (slot == null) slot = MoondropCodec.parseSlot(it, port.deviceKey, inBytes)
            }
        }
        val pre = if (!withPreGain) null else try {
            reply(MoondropCodec.preGainQuery(), inBytes) { slot }
        } catch (e: java.io.IOException) {
            port.guard()
            if (!io.isReplyTimeout(e)) throw e
            port.log("Pre-gain (0x23) read: no valid reply; pre-gain unknown")
            null
        }
        MoondropCodec.snapshot(model, port.deviceKey, replies, inBytes, pre)
    }

    /** Switches the active EQ to [index] (`01 0F 01 i`, 150 ms), with [persist] saves it (`01 01 00`, 200 ms), and returns the index one
     * read confirms. Nothing else is written; the caller compares the answer with [index]. */
    fun selectIndex(index: Int, explicitUserAction: Boolean, persist: Boolean = true): Int = io.guarded {
        require(explicitUserAction) { "Switching the EQ index requires an explicit user action" }
        require(index in 0..255) { "EQ index $index out of range" }
        MoondropCodec.selectSteps(index, persist).forEach { io.send(model.reportId, it.payload); io.pause(it.delayAfterMs) }
        confirmedIndex()
    }

    private fun confirmedIndex(): Int {
        val inBytes = inputBytes()
        return MoondropCodec.parseSlot(io.retryTimeouts { reply(MoondropCodec.slotQuery(), inBytes) { null } }, port.deviceKey, inBytes)
    }

    /** The firmware version (`80 0C`, the read MDHUB's `readFirmwareVersion` makes), decoded as MDHUB does ([MoondropCodec.parseVersion]); read only. */
    fun readFirmware(): MoondropCodec.Firmware = io.guarded {
        val inBytes = inputBytes()
        val frame = MoondropCodec.versionQuery()
        MoondropCodec.requireSendable(frame.payload)
        val r = io.retryTimeouts { reply(frame, inBytes) { null } }
        MoondropCodec.parseVersion(r, port.deviceKey, inBytes)
    }

    /** What [enableAllPeq] saw: the whole read before and the whole read after the one frame. */
    class EnableAllPeqResult(val before: MoondropCodec.Snapshot, val after: MoondropCodec.Snapshot)

    /**
     * MDHUB's `enableAllPEQ` alone (`01 0A 04 <index> 00 FF FF`, [MoondropCodec.enableAllPeqPayload]) and, with [persist], `01 01 00`: no band, no EQ index switch, no gain. Preflight
     * before any I/O; the read before ([lastBefore]), the frame (through [MoondropCodec.requireSendable] with this index), the read after. The caller compares the two: whether the
     * unit followed to another EQ index, or any band changed, is the finding. [persist] saves the active bank whatever it is (as `select --persist`): whether that is safe on a
     * bank that is not Contour's is unknown, so it is off unless asked for.
     */
    fun enableAllPeq(index: Int, persist: Boolean, explicitUserAction: Boolean): EnableAllPeqResult = io.guarded {
        require(explicitUserAction) { "An explicit user action is required" }
        val steps = MoondropCodec.enableAllPeqSteps(index, persist) // preflight everything before the first frame
        steps.forEach { MoondropCodec.requireSendable(it.payload, enableIndex = index) }
        val before = read().also { lastBefore = it }
        steps.forEach { io.send(model.reportId, it.payload); io.pause(it.delayAfterMs) }
        EnableAllPeqResult(before, read())
    }

    /** Switches to the writable bank (no save) and reads it: a tap on the slot before a backup. */
    fun readWritableBank(explicitUserAction: Boolean, withPreGain: Boolean = false): MoondropCodec.Snapshot = io.guarded {
        require(explicitUserAction) { "Reading another EQ index requires an explicit user action" }
        val now = read().also { lastBefore = it }
        if (now.activeIndex != model.writableIndex) {
            val confirmed = selectIndex(model.writableIndex, explicitUserAction = true, persist = false)
            require(confirmed == model.writableIndex) { "EQ index switch failed: asked ${model.writableIndex}, the DAC reports $confirmed" }
        }
        read(withPreGain)
    }

    /** The bands as [write] will send them: all eight in order, the ones not given are neutral (PK, 0 dB, Q 0.707, the unit's default frequencies). */
    fun padBands(bands: List<MoondropCodec.BandWrite>): List<MoondropCodec.BandWrite> {
        require(bands.size <= model.bandCount) { "${bands.size} bands; ${model.productName} has ${model.bandCount}" }
        return List(model.bandCount) { i ->
            val b = bands.getOrNull(i)
            if (b == null) MoondropCodec.neutralBand(i) else b.also { require(it.index == i) { "Band ${it.index} stands at position $i" } }
        }
    }

    /** The bands the last [write] intended, set before its first mutating frame. */
    var lastExpected: List<MoondropCodec.BandWrite>? = null
        private set

    /** What the DAC showed on the read that opens the last [write] or [readWritableBank] (set before its first mutating frame):
     * the EQ index it was found on, which a test returns to with [selectIndex]. */
    var lastBefore: MoondropCodec.Snapshot? = null
        private set

    /**
     * Writes one gain, PRE-GAIN (0x23) or GLOBAL GAIN (0x03), and reads everything back: `01 23 02 lo hi` / `01 03 02 lo hi` (0.1 dB grid, signed Q8.8) and
     * nothing else; no band, no commit, never `01 04 00`. Preflight before any I/O (range); then the read before ([lastBefore], with the pre-gain: a gain whose
     * read does not answer is not written over); then the frame, every frame through [MoondropCodec.requireSendable]; then the read after, which must show
     * the new raw value, the same active index and the same bands. With [persist] `01 01 00` follows, and only on the writable index (the save is the active
     * bank's). The persistence of a gain over a power cycle is not proven: it takes a read after replugging.
     */
    fun writeGain(register: MoondropCodec.GainRegister, db: Double, explicitHold: Boolean, persist: Boolean = false): WriteOutcome<MoondropCodec.Snapshot> = io.guarded {
        require(explicitHold) { "An explicit user action is required" }
        val steps = MoondropCodec.gainSteps(model, register, db, persist) // preflight everything before the first frame
        val want = MoondropCodec.gainRawQ8(model, register, db)
        steps.forEach { MoondropCodec.requireSendable(it.payload) }
        val before = read(withPreGain = true).also { lastBefore = it }
        require(register != MoondropCodec.GainRegister.PRE || before.preGain != null) { "Pre-gain (0x23) gave no valid read; it is not written over, nothing sent" }
        if (persist) require(model.isWritable(before.activeIndex)) {
            "The DAC is on EQ index ${before.activeIndex}, not one of Contour's banks (${model.writableIndexes.sorted().joinToString(", ")}): the flash save is the active bank's, nothing sent"
        }
        steps.forEach { io.send(model.reportId, it.payload); io.pause(it.delayAfterMs) }
        val back = read(withPreGain = true)
        val problems = ArrayList<String>()
        if (back.activeIndex != before.activeIndex) problems += "EQ index ${before.activeIndex} -> ${back.activeIndex}"
        if (back.bands != before.bands) problems += "the bands changed"
        val got = when (register) { MoondropCodec.GainRegister.PRE -> back.preGain?.rawQ8; MoondropCodec.GainRegister.GLOBAL -> back.globalGain.rawQ8 }
        if (got == null) problems += "${register.what} not read back"
        else if (got != want) problems += "${register.what} $want -> $got (Q8.8)"
        WriteOutcome(problems.isEmpty(), false, problems.joinToString("; ").ifEmpty { null }, back)
    }

    private fun notPeqMode(index: Int) =
        "The DAC is on EQ index $index, one of the unit's own presets (Contour writes EQ index ${model.writableIndexes.sorted().joinToString(" or ")}): switch the DAC to its PEQ mode first; nothing written"

    /**
     * Writes [bands] (all eight; see [padBands]) and, when [preGainDb] is given, the pre-gain, the way MOONDROP Hub's Write Cfg does ([MoondropCodec.writeSteps]):
     * a read first (its global gain is written back unchanged) that must show an EQ index of the model's writable set ([MoondropCatalog.Model.writableIndexes]; on a preset index
     * it throws and sends nothing), then the Hub's frames, then a read of the active bank. The outcome is decided by comparing the eight bands (and the pre-gain when it reads)
     * with what was sent, whatever EQ index the unit reports after (both are logged; [lastBefore] holds the first). Nothing is sent when the preflight fails, and a write that reads
     * back different bands sends nothing more. The offset save `01 04 00` is never sent.
     */
    fun write(bands: List<MoondropCodec.BandWrite>, preGainDb: Double?, explicitHold: Boolean): WriteOutcome<MoondropCodec.Snapshot> = io.guarded {
        require(explicitHold) { "HOLD TO SEND required" }
        val full = padBands(bands)
        MoondropCodec.writeSteps(model, full, preGainDb) // preflight everything before the first frame
        lastExpected = full
        val before = read().also { lastBefore = it }
        // The one gate left: the unit must be on one of Contour's banks (the record's slots.writableIndexes). On any other index the bands read are one of its own presets and a band frame
        // would overwrite it, so nothing is sent. On the writable ones the Hub's sequence goes out without a check that the switch takes (the owner's unit stays on 9).
        require(model.isWritable(before.activeIndex)) { notPeqMode(before.activeIndex) }
        val steps = MoondropCodec.writeSteps(model, full, preGainDb, before.globalGain.rawQ8)
        steps.forEach { MoondropCodec.requireSendable(it.payload) }
        steps.forEach { io.send(model.reportId, it.payload); io.pause(it.delayAfterMs) }
        val back = read(withPreGain = preGainDb != null)
        port.log("Moondrop write: EQ index found ${before.activeIndex}, after the write ${back.activeIndex}")
        val problems = ArrayList(MoondropCodec.matches(full, back.activeIndex, back.bands))
        val note = if (preGainDb == null) null else {
            val want = WalkPlay.toInt32(WalkPlay.jsRound(preGainDb * 256))
            when {
                back.preGain == null -> "pre-gain not confirmed: no valid read of 0x23"
                back.preGain.rawQ8 != want -> { problems += "pre-gain ${want} -> ${back.preGain.rawQ8} (Q8)"; null }
                else -> null
            }
        }
        if (problems.isNotEmpty()) problems += "EQ index ${before.activeIndex} -> ${back.activeIndex}"
        val reason = (problems + listOfNotNull(note)).joinToString("; ").ifEmpty { null }
        WriteOutcome(problems.isEmpty(), false, reason, back)
    }
}
