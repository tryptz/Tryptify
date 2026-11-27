package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Band

/**
 * Reads, selects and writes a Moondrop gen-1 cable (DUSK-SP) through a [NativePort]. The port carries one request as SET_REPORT(Output, id 1), a wait of the model's `replyWaitMs`, and
 * GET_REPORT(Input, id 1): the transport of this family, not an interrupt endpoint. Every frame of the session is a request, writes included: a write's reply is only checked to be a
 * whole report (the cable echoes the request with the flag's bit 7 set, measured), and the proof of a write is the read-back of the nine meta records and the active preset ([verifyAfterReturn]).
 *
 * Contour writes the Custom EQ (index 0) only; the firmware presets (1-5) are selected with [selectPreset] and never written. The commit that ends a write restarts the cable onto preset 1
 * ([write], [verifyAfterReturn]); a select is RAM only. On a preset the bands a read returns are the Custom bank, not what plays ([MoondropGen1Codec.Snapshot.onCustom]).
 */
class MoondropGen1Session(val model: MoondropGen1Catalog.Model, private val port: NativePort) {
    private val io = NativeIo(port)

    private fun ask(frame: MoondropGen1Codec.Frame, accepts: (ByteArray) -> Boolean): ByteArray {
        MoondropGen1Codec.requireSendable(frame.payload)
        return io.request(MoondropGen1Codec.REPORT_ID, frame.payload, accepts)
    }

    private fun check() {
        val size = port.payloadSize(MoondropGen1Codec.REPORT_ID, NativeHidReports.Kind.INPUT)
        require(size == MoondropGen1Codec.PAYLOAD_BYTES && port.payloadSize(MoondropGen1Codec.REPORT_ID, NativeHidReports.Kind.OUTPUT) == MoondropGen1Codec.PAYLOAD_BYTES) {
            "The HID report descriptor does not declare the 62-byte report 1 of a Moondrop gen-1 cable"
        }
    }

    /** The active preset, then the nine meta records of the Custom bank. Reads are asked again when the cable is silent (they change nothing). */
    fun read(): MoondropGen1Codec.Snapshot = io.guarded {
        check()
        val index = MoondropGen1Codec.parsePreset(io.retryTimeouts { ask(MoondropGen1Codec.presetQuery(), MoondropGen1Codec::isPresetReply) })
        val bands = List(model.bandCount) { i ->
            val n = i + 1
            MoondropGen1Codec.parseBand(io.retryTimeouts { ask(MoondropGen1Codec.bandQuery(n)) { MoondropGen1Codec.isBandReply(it, n) } }, n)
        }
        MoondropGen1Codec.Snapshot(model, index, bands)
    }

    /** The bands the last [write] intended, set before its first mutating frame. */
    var lastPlanned: List<MoondropGen1Codec.BandWrite>? = null
        private set

    /** What the cable showed on the read that opens the last [write] (set before its first mutating frame): the preset it was found on, which a test returns to with [selectPreset]. */
    var lastBefore: MoondropGen1Codec.Snapshot? = null
        private set

    private fun runSteps(steps: List<MoondropGen1Codec.Frame>) {
        steps.forEach { f ->
            ask(f, MoondropGen1Codec::isWholeReply)
            io.pause(f.delayAfterMs)
        }
    }

    /**
     * Selects firmware preset (or the Custom EQ) [index]: `5a 00 [5a, idx]` alone (no commit: the commit would restart the cable onto preset 1), then reads the preset and returns the index the
     * cable reports; the caller compares it with [index]. The choice is RAM only: the cable keeps it until its next restart or plug-in. Nothing else is written.
     */
    fun selectPreset(index: Int, explicitUserAction: Boolean): Int = io.guarded {
        require(explicitUserAction) { "Switching the preset requires an explicit user action" }
        require(index in model.presetLabels.keys) { "Preset $index is not one of ${model.presetLabels.keys.sorted().joinToString(", ")}" }
        check()
        runSteps(MoondropGen1Codec.selectSteps(index, model.afterSelectMs))
        MoondropGen1Codec.parsePreset(io.retryTimeouts { ask(MoondropGen1Codec.presetQuery(), MoondropGen1Codec::isPresetReply) })
    }

    /**
     * Writes [bands] to the Custom EQ ([MoondropGen1Codec.plan]: nine bands, the missing ones neutral) up to and including the commit, then ENDS: the commit stores the bank to flash and restarts the
     * cable, which leaves the bus about 650 ms after the commit's reply and is back 0.5-0.7 s later on preset 1. The outcome is pending ([WriteOutcome.pending], [lastPlanned] is what it must read back
     * as); the proof is [verifyAfterReturn] on a new session once the cable is back. The preflight ([MoondropGen1Codec.issues]) and the read before come first (nothing is sent to a cable that does not
     * answer); no guard runs after the commit.
     */
    fun write(bands: List<Band>, explicitHold: Boolean): WriteOutcome<MoondropGen1Codec.Snapshot> {
        port.guard() // not again after the commit: the cable leaves the bus then
        require(explicitHold) { "HOLD TO SEND required" }
        MoondropGen1Codec.issues(model, bands).takeIf { it.isNotEmpty() }?.let { throw IllegalArgumentException(it.joinToString("; ")) }
        val planned = MoondropGen1Codec.plan(model, bands)
        val steps = MoondropGen1Codec.writeSteps(model, planned) // every frame built (and checked) before the first one goes out
        steps.forEach { MoondropGen1Codec.requireSendable(it.payload) }
        lastPlanned = planned
        lastBefore = read()
        runSteps(steps)
        return WriteOutcome(false, true, "Saved. The cable restarts - Contour reads it again when it is back.", null)
    }

    /**
     * After the restart: the RAM select of the Custom EQ (`5a 00 [5a, 0]`, no commit), then a read of the active preset and the nine bands, which must be preset 0 and the meta records of [planned] (the
     * coefficients cannot be read back). A mismatch is reported, nothing more is sent.
     */
    fun verifyAfterReturn(planned: List<MoondropGen1Codec.BandWrite>): WriteOutcome<MoondropGen1Codec.Snapshot> = io.guarded {
        check()
        runSteps(MoondropGen1Codec.selectSteps(model.customIndex, model.afterSelectMs))
        val back = read()
        port.log("Moondrop gen-1 write: cable found on preset ${lastBefore?.activeIndex ?: "?"}, after the restart and the select ${back.activeIndex}")
        val problems = MoondropGen1Codec.mismatches(planned, back.activeIndex, back.bands, model.customIndex)
        WriteOutcome(problems.isEmpty(), false, problems.joinToString("; ").ifEmpty { null }, back)
    }

    /**
     * [write] and [verifyAfterReturn] in one session, for a transport that survives the restart (the bench: the rig reopens the cable between the commit and the next frame; the tests' fake cable).
     * The app's transport does not, so it ends [write] at the commit and runs [verifyAfterReturn] as a separate session.
     */
    fun writeAndVerify(bands: List<Band>, explicitHold: Boolean): WriteOutcome<MoondropGen1Codec.Snapshot> {
        write(bands, explicitHold)
        return verifyAfterReturn(requireNotNull(lastPlanned))
    }
}
