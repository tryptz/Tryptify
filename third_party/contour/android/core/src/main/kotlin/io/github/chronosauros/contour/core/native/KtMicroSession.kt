package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.BuildChannel
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.Proven

/**
 * How long a KT DAC that was saved is waited for once it is back on USB. After SAVE it leaves the bus and returns in 0.8-3 s (JCally JM12, runs 2026-11-10-jcally-jm12-*); twice it came back and
 * answered no read until it was unplugged and plugged in again. The app reads again every second; after [SILENT_READS] reads that failed with the DAC on the bus the send ends, never as saved, and
 * [silentVerdict] is what it tells the person.
 */
object KtMicroSaveWait {
    const val SILENT_READS = 5
    fun silentVerdict(failedReads: Int): String? =
        if (failedReads >= SILENT_READS) "the DAC is back on USB but does not answer; unplug it, plug it back in, then tap to read it" else null
}

/**
 * Reads and writes a KT Micro DAC (Kiwi Ears Allegro Mini, JCally JM12) through a [NativePort]: the session the app would run, with the
 * transport left to the port. A write ends at SAVE: the DAC drops off the USB bus then ([WriteOutcome.pending]), and the proof is
 * a read by a new session once it is back ([KtMicroCodec.Plan.expectedRegisters] is what that read must return).
 */
class KtMicroSession(val model: KtMicroCatalog.Model, private val port: NativePort) {
    private val io = NativeIo(port)
    /** The plan of the last [write], set as soon as its read is done (before the first mutating frame). */
    var lastPlan: KtMicroCodec.Plan? = null
        private set

    private fun reply(f: KtMicroCodec.Frame, command: Int = KtMicroCodec.READ): KtMicroCodec.Reply {
        val reg = f.payload[0].toInt() and 255
        val bytes = io.request(f.reportId, f.payload) { p -> runCatching {
            KtMicroCodec.parseRegister(KtMicroCodec.Reply(port.deviceKey, f.reportId, p), port.deviceKey, reg, command)
        }.isSuccess }
        return KtMicroCodec.Reply(port.deviceKey, f.reportId, bytes)
    }

    /** The EQ registers, the enable register once more to prove it did not change, then the channel flags (best effort). */
    fun read(): KtMicroCodec.Snapshot = io.guarded {
        val replies = KtMicroCodec.readQueries(model).map { reply(it) }
        val required = KtMicroCodec.snapshot(model, port.deviceKey, replies)
        val enable = model.registers.enableRegister
        val closing = KtMicroCodec.parseRegister(reply(KtMicroCodec.readRegister(enable)), port.deviceKey, enable)
        require(closing == required.registers.single { it.register == enable }) { "KT EQ enable changed during read" }
        // Last and best-effort: unsupported flags must not break a previously working EQ read.
        val flags = optionalFlags()
        val s = KtMicroCodec.snapshot(model, port.deviceKey, replies + listOfNotNull(flags))
        if (s.digitalGain == null) port.log("KT channel layout unknown: no valid 0x01 reply; digital DAC gain unknown.")
        s
    }

    private fun optionalFlags(): KtMicroCodec.Reply? = try {
        reply(KtMicroCodec.readRegister(KtMicroCodec.CHANNEL_FLAGS_REGISTER))
    } catch (e: java.io.IOException) {
        port.guard(); null
    } catch (e: IllegalArgumentException) { port.guard(); null }

    /** What [write] would send for [profile] given the state [before] (nothing is sent). */
    fun plan(profile: Profile, before: KtMicroCodec.Snapshot, eqEnabled: Boolean? = true): KtMicroCodec.Plan =
        KtMicroCodec.compile(profile, before, eqEnabled)

    /** Reads the DAC, then writes [profile] (bands, and the EQ switch when it differs from [eqEnabled]; null = the switch is left as the read found it) register by register, every write
     * acknowledged, then SAVE. Returns a pending outcome: the read after the DAC is back decides. */
    fun write(profile: Profile, explicitHold: Boolean, eqEnabled: Boolean? = true): WriteOutcome<KtMicroCodec.Snapshot> {
        port.guard() // not again after SAVE: the DAC is off the bus by then
        require(explicitHold) { "HOLD TO SEND required" }
        return run(plan(profile, read(), eqEnabled).also { lastPlan = it })
    }

    /** Reads the DAC, then writes only the EQ switch (register 0x24) and SAVE; the bands stay. Pending like [write]. Refused when the switch is already there. */
    fun writeSwitch(eqEnabled: Boolean, explicitHold: Boolean): WriteOutcome<KtMicroCodec.Snapshot> {
        port.guard()
        require(explicitHold) { "An explicit user action is required" }
        return run(KtMicroCodec.compileSwitch(read(), eqEnabled).also { lastPlan = it })
    }

    /** The 0x3B value the last [writeGlobalGain] found on the DAC (what a restore writes back), set before its first mutating frame. */
    var lastGainBefore: KtMicroCodec.RegisterValue? = null
        private set

    /**
     * Reads the DAC and its Global Gain (0x3B), then writes only the Global Gain to [step] (1-15; step 0 is refused before anything is read), acknowledged,
     * and with [save] SAVE: pending like [write] (the DAC leaves the bus; the proof is [readGlobalGain] after it is back, against
     * [KtMicroCodec.Plan.expectedGlobalGain], with [KtMicroCodec.mismatches]). Without [save] the write stays in RAM and the gain is read back in the same
     * session: verified or not at once. The bands and the EQ switch are not touched. Refused (nothing written) when 0x3B does not answer or does not read
     * as a plain step.
     */
    fun writeGlobalGain(step: Int, explicitHold: Boolean, save: Boolean = true): WriteOutcome<KtMicroCodec.Snapshot> {
        port.guard()
        require(explicitHold) { "An explicit user action is required" }
        require(model.globalGainUserSteps != null) { "${model.productName} offers no Global Gain: register 0x3B is never written for it" }
        KtMicroCodec.requireGlobalGainStep(step)
        val before = read()
        val current = readGlobalGain() ?: throw java.io.IOException("Global Gain (0x3B) gave no valid reply; nothing written")
        lastGainBefore = current
        val plan = KtMicroCodec.compileGlobalGain(before, current, step, save).also { lastPlan = it }
        if (save) return run(plan)
        plan.frames.forEach { f ->
            val reg = f.payload[0].toInt() and 255
            val r = try { reply(f, KtMicroCodec.WRITE) } catch (e: java.io.IOException) {
                if (!io.isReplyTimeout(e)) throw e
                throw java.io.IOException("KT write acknowledgement missing at 0x${reg.toString(16)}; send stopped", e)
            }
            KtMicroCodec.checkWriteAck(r, port.deviceKey, reg, plan.model.registers)
            io.pause(f.delayAfterMs.toLong())
        }
        val back = read()
        val gain = readGlobalGain()
        val problems = KtMicroCodec.mismatches(plan, back, gain)
        return WriteOutcome(problems.isEmpty(), false, problems.joinToString("; ").ifEmpty { null }, back)
    }

    /** The Global Gain register (0x3B), read only and best effort: the raw four bytes, or null when the DAC gives no valid reply. Read only; the write is [writeGlobalGain]. */
    fun readGlobalGain(): KtMicroCodec.RegisterValue? = io.guarded {
        val reg = KtMicroCodec.GLOBAL_GAIN_REGISTER
        try {
            KtMicroCodec.parseRegister(reply(KtMicroCodec.readRegister(reg)), port.deviceKey, reg)
        } catch (e: java.io.IOException) {
            port.guard(); if (!io.isReplyTimeout(e)) throw e; null
        } catch (e: IllegalArgumentException) { port.guard(); null }
    }

    // ---- factory reset (CLEAR, 0x43): backup, the frame, the read after it, the restore. Bench only (KtMicroReset) ------------------------------------------------------------------

    /**
     * What the DAC holds right now, as a [KtMicroResetBackup]: the EQ registers and the Global Gain (0x3B). Used before CLEAR (the backup) and after it (what CLEAR left). Nothing is written.
     */
    fun captureForReset(explicitUserAction: Boolean, takenAtMs: Long): KtMicroResetBackup = io.guarded {
        require(explicitUserAction) { "A backup before a reset requires an explicit user action" }
        val state = read()
        KtMicroResetBackup(takenAtMs, state, readGlobalGain())
    }

    /**
     * Sends CLEAR (the vendor's flash clear), built from [ticket] only, and waits for the DAC to echo it (byte 6 = 3 is the ack the vendor tool waits for). A missing answer is a
     * note, never an exception: the DAC may restart or leave the bus, and nothing counts as reset until a read after it says so. No SAVE follows (the vendor sequence has none).
     * Then the session waits [KtMicroReset.SETTLE_MS]; the read after it is the caller's.
     */
    fun factoryClear(ticket: KtMicroResetTicket, log: (String) -> Unit = {}): KtMicroResetSent {
        require(model.resetOffered) { "${model.productName} has no reset in Contour: CLEAR (0x43) is never sent to it" }
        port.guard()
        val frame = KtMicroCodec.compileClear(ticket)
        var acknowledged = false
        var note: String? = null
        try {
            val reply = io.request(frame.reportId, frame.payload) { KtMicroCodec.isClearReply(it) }
            acknowledged = KtMicroCodec.clearAck(reply, model.registers)
            if (!acknowledged) note = "CLEAR was echoed with byte 6 = ${reply[6].toInt() and 255}, not ${model.registers.writeAck}: whether it ran is for the read after to say"
        } catch (e: java.io.IOException) {
            if (!io.isReplyTimeout(e)) throw e
            note = "no acknowledgement of CLEAR (the DAC may have restarted or left the bus): whether it ran is for the read after to say"
        }
        log("reset: CLEAR sent" + (note?.let { ", $it" } ?: ", acknowledged"))
        io.pause(KtMicroReset.SETTLE_MS)
        return KtMicroResetSent(acknowledged, note)
    }

    /**
     * Writes [backup] back after a CLEAR, given what the read after it found ([after]): the EQ switch and the bands the CLEAR changed, byte for byte ([KtMicroCodec.compileRestore]) and SAVE,
     * then, after the DAC is back (a read proves it), the Global Gain when it differs ([writeGlobalGain]), SAVE again, and a last read. Every SAVE takes the DAC off the USB bus: this
     * session runs on a transport that survives that (the bench replays the rig's log and the rig reconnects between calls); the app's transport does not, so it will need the same steps
     * as separate sessions. A step that fails is a line, never a silent skip; [KtMicroRestoreResult.ok] is true only when everything that had to be written read back as saved.
     */
    fun restoreAfterReset(backup: KtMicroResetBackup, after: KtMicroResetBackup, log: (String) -> Unit = {}): KtMicroRestoreResult {
        port.guard()
        backup.problems(model).let { require(it.isEmpty()) { "Backup incomplete: " + it.joinToString("; ") } }
        val lines = ArrayList<String>()
        var ok = true
        var lost = false
        val plan = KtMicroCodec.compileRestore(backup.snapshot, after.snapshot)
        if (plan == null) lines += "EQ switch and bands: unchanged by the reset, nothing written"
        else try {
            lastPlan = plan
            run(plan)
            io.pause(KtMicroReset.SETTLE_MS) // the DAC left the bus on SAVE: the read below is the first thing asked of it
            val problems = KtMicroCodec.mismatches(plan, read())
            log("reset restore: EQ switch and bands ${if (problems.isEmpty()) "VERIFIED" else "NOT VERIFIED"}")
            lines += if (problems.isEmpty()) "EQ switch and bands: restored" else "EQ switch and bands: NOT restored: ${problems.joinToString("; ")}"
            if (problems.isNotEmpty()) ok = false
        } catch (e: java.io.IOException) {
            lines += "EQ switch and bands: NOT restored: ${e.message}"; ok = false; lost = true
        } catch (e: IllegalArgumentException) {
            lines += "EQ switch and bands: NOT restored: ${e.message}"; ok = false
        }
        val wanted = requireNotNull(backup.globalGain)
        if (lost) { lines += "Global Gain: not restored (the DAC session ended)"; ok = false }
        else if (after.globalGain?.value == wanted.value) lines += "Global Gain: unchanged by the reset, nothing written"
        else try {
            val step = requireNotNull(KtMicroCodec.globalGainStep(wanted))
            writeGlobalGain(step, explicitHold = true, save = true)
            io.pause(KtMicroReset.SETTLE_MS)
            val problems = KtMicroCodec.mismatches(requireNotNull(lastPlan), read(), readGlobalGain())
            log("reset restore: Global Gain ${if (problems.isEmpty()) "VERIFIED" else "NOT VERIFIED"}")
            lines += if (problems.isEmpty()) "Global Gain: restored" else "Global Gain: NOT restored: ${problems.joinToString("; ")}"
            if (problems.isNotEmpty()) ok = false
        } catch (e: java.io.IOException) {
            lines += "Global Gain: NOT restored: ${e.message}"; ok = false
        } catch (e: IllegalArgumentException) {
            lines += "Global Gain: NOT restored: ${e.message}"; ok = false
        }
        return KtMicroRestoreResult(lines, ok)
    }

    private fun run(p: KtMicroCodec.Plan): WriteOutcome<KtMicroCodec.Snapshot> {
        p.frames.forEach { f ->
            if (f.expectsReply) {
                val reg = f.payload[0].toInt() and 255
                val r = try { reply(f, KtMicroCodec.WRITE) } catch (e: java.io.IOException) {
                    if (!io.isReplyTimeout(e)) throw e
                    throw java.io.IOException("KT write acknowledgement missing at 0x${reg.toString(16)}; send stopped", e)
                }
                KtMicroCodec.checkWriteAck(r, port.deviceKey, reg, p.model.registers)
            } else if (p.reconnectAfterSave && (f.payload[4].toInt() and 255) == KtMicroCodec.SAVE) {
                // The DAC may disappear on SAVE: no guard after it, no wait, no retry, no false verification.
                port.send(f.reportId, NativeHidReports.Kind.OUTPUT, f.payload)
                return@forEach
            } else io.send(f.reportId, f.payload)
            io.pause(f.delayAfterMs.toLong())
        }
        return WriteOutcome(false, true, if (Proven.channel == BuildChannel.PERF) "Save sent; the DAC reconnects: read it again after it is back and compare (not verified)" else "Saved. The DAC restarts - Contour reads it again when it is back.", null)
    }
}
