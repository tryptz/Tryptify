package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.*

/** Pure session contract used by the Android transport AND fake-transport tests. */
interface NativePort {
    val deviceKey: String
    fun guard()
    fun pause(ms: Long)
    fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray)
    fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray
    /** Payload bytes (after the report ID) of the descriptor's [kind] report [id]. Only the families that check exact
     * input lengths ask (native Moondrop); the app's own port keeps this default, the bench tool answers it. */
    fun payloadSize(id: Int, kind: NativeHidReports.Kind): Int = error("This port does not know the HID report descriptor")
    /** A note from a session for the operator (the bench tool prints it); the app ignores it. */
    fun log(message: String) {}
}
/** Contour stable: the native (non-WalkPlay) DACs are FiiO: KA15, K13 R2R, SnowSky Melody and Tiny A. */
sealed interface NativeState {
    val slot: Int
    val preamp: Double?
    fun importExact(): ImportedEq
    fun matches(profile: Profile): Boolean
    fun describe(): String = when (this) {
        is Fiio -> (slotDescription?.let { "$it\n" } ?: "") + "${raw.count} native filters; pregain ${raw.preampTenths}/10 dB\n" + raw.bands.joinToString("\n")
    }
    /** [names]: USER slot -> name read from the DAC (KA15), empty where the device has no names. */
    data class Fiio(val codec: FiioCodec, val raw: FiioSnapshot, val names: Map<Int, String> = emptyMap()) : NativeState {
        override val slot get() = raw.activeSlot
        /** Honest slot text for the SnowSky DACs, whose slot register can be stale on a USER slot; null elsewhere. */
        val slotDescription: String? get() = if (!FiioMelodyExchange.appliesTo(codec.config)) null else when (slot) {
            in codec.config.userSlots -> "${codec.config.slotLabels[slot]} (slot $slot)"
            in 7..9 -> "USER slot (number unknown; DAC reports $slot)"
            in 0..6 -> "Slot unknown (stock preset or USER; DAC reports $slot)"
            240 -> "Close EQ (slot 240)"
            else -> "Slot unknown (DAC reports $slot)"
        }
        override val preamp get() = codec.intendedPreampDb(raw.preampTenths)
        override fun importExact(): ImportedEq = codec.importExact(raw).let { ImportedEq(it.bands, it.preampDb) }
        override fun matches(profile: Profile): Boolean = runCatching {
            codec.matches(codec.planOnExplicitSend(profile.copy(bands = codec.padToDeviceCount(profile.bands), preampDb = profile.effectivePreampDb(codec.config.shelfForm)), slot, true).expected, raw)
        }.getOrDefault(false)
    }
}
/** One DAC setting read: [value] as [DacSetting.decode] gives it, null = NO REPLY ([note] says whether the DAC was silent or the bytes made no sense). */
data class SettingReading(val id: String, val value: Int?, val note: String?)
/** A settings write and its read-back: [Verified] = the DAC says the value it was given, [Mismatch] = it says [read], [NoReply] = no usable reply in two reads. */
sealed interface SettingWriteOutcome {
    data object Verified : SettingWriteOutcome
    data class Mismatch(val read: Int) : SettingWriteOutcome
    data object NoReply : SettingWriteOutcome
}
/** The write self-test of one setting: [result] is OK, MISMATCH, NO_REPLY or RESTORE_FAILED; a value is null where the DAC did not answer. */
data class WriteTestResult(val id: String, val from: Int?, val to: Int?, val back: Int?, val restored: Int?, val result: String, val extra: String? = null) {
    fun line() = "WRITETEST $id from=${from ?: "none"} to=${to ?: "none"} back=${back ?: "none"} restored=${restored ?: "none"} result=$result"
}
/**
 * The outcome of a FiiO write. [pending]: the DAC leaves the USB bus on SAVE ([FiioConfig.disconnectOnSave], the JA11), so the session ends at the SAVE frame: nothing is verified and nothing is read back here, [expected] is what the
 * first read after the DAC is back must show ([FiioCodec.matches]); [verified] is false and [readback] null until then.
 */
data class NativeWriteResult(val verified: Boolean, val reason: String?, val readback: NativeState?, val expected: FiioSnapshot? = null, val pending: Boolean = false)

/** No mutation on read, qualification or local preflight. Every callback is guarded both sides. */
class NativeSession(val target: DeviceTarget, private val port: NativePort) {
    private val melody = FiioMelodyExchange.appliesTo(target.fiio)
    internal companion object {
        /** After a READ slot switch, as FiioWritePlan.delayAfterSelectMs before a send. */
        const val SELECT_SETTLE_MS = 300L
        /**
         * A settings read-back that is silent or still shows the old value is asked again after this long, up to [LATE_READBACKS] times, before a write is called
         * unanswered or mismatched: the KA15 answers a filter change late (hardware 21.10.2026, runs/2026-10-21-fiio-ka15-02-phone: the restore wrote the filter, got no
         * read-back, and a read ten seconds later showed it written). The same 2.5 s the WalkPlay engine waits before it asks again; a read only, the write is never repeated.
         */
        const val LATE_READBACK_MS = 2500L
        /** The KA15 FILTER write is read back no sooner than this (DacSettings.kt: it stopped answering after a FILTER write on 17.10.2026). */
        const val FILTER_SETTLE_MS = 1000L
        const val LATE_READBACKS = 2
    }
    private fun <T> guarded(op: () -> T): T { port.guard(); return op().also { port.guard() } }
    private fun pause(ms: Long) { if (ms > 0) guarded { port.pause(ms) } }
    private fun send(id: Int, bytes: ByteArray, kind: NativeHidReports.Kind = NativeHidReports.Kind.OUTPUT) { guarded { port.send(id, kind, bytes) }; frameDone() }
    private fun request(id: Int, bytes: ByteArray, check: (ByteArray) -> Boolean): ByteArray = guarded {
        port.request(id, bytes, check).also { require(check(it)) { "Uncorrelated native reply" } }
    }.also { frameDone() }
    // Progress of a write: every HID frame sent or answered is a step done, out of the steps [write] planned (estimated up front,
    // narrowed once the first read says whether the slot has to be switched; never fewer than one step ahead of those done).
    private var progress: ((done: Int, total: Int) -> Unit)? = null
    private var stepsDone = 0
    private var stepsTotal = 0
    private fun frameDone() { progress?.let { stepsDone++; it(stepsDone, maxOf(stepsTotal, stepsDone + 1)) } }
    private fun fiioReply(c: FiioCodec, f: FiioFrame): ByteArray {
        fun logical(p: ByteArray) = c.logicalReply(f.reportId, p, p.size)
        val raw = request(f.reportId, f.payload()) { p -> runCatching {
            val l = logical(p)
            require(l[4] == f.payload()[4])
            if ((l[4].toInt() and 255) in setOf(FiioCodec.BAND, FiioCodec.NAME)) require(l[6] == f.payload()[6])
        }.isSuccess }
        return logical(raw)
    }
    /** Read-only queries are idempotent, so a silent DAC (busy after a bank switch or a flash save) may be asked again.
     * Never wrap a mutation in this. Only a reply timeout is retried; a lost session or a bad reply still aborts. */
    private fun isReplyTimeout(e: java.io.IOException) = e.message?.startsWith("Native request timed out") == true
    private fun <T> retryTimeouts(attempts: Int = 8, gapMs: Long = 400, op: () -> T): T {
        var last: java.io.IOException? = null
        repeat(attempts) { i ->
            try { return op() } catch (e: java.io.IOException) {
                if (!isReplyTimeout(e)) throw e
                last = e
                if (i < attempts - 1) pause(gapMs)
            }
        }
        throw requireNotNull(last)
    }
    /** One write, one echo (the SnowSky SAVE uses a different reply). KA15 stops answering
     * until it is unplugged when 12 writes go out back to back and no echo is read (hardware, 03.10.2026). A missing
     * parameter echo is not fatal; the full post-save read decides success. A SnowSky SELECT requires its echo. */
    private fun writeAndConsumeEcho(f: FiioFrame, requireEcho: Boolean = false) {
        val frame = f.payload()
        val cmd = frame[4]
        try {
            request(f.reportId, frame) { p ->
                p.size >= 6 && p[0] == 0xAA.toByte() && p[1] == 0x0A.toByte() && p[4] == cmd &&
                    (!melody || melodyEcho(frame, p))
            }
        } catch (e: java.io.IOException) { if (requireEcho || !isReplyTimeout(e)) throw e }
    }
    private fun melodyEcho(frame: ByteArray, reply: ByteArray): Boolean {
        // A name write is echoed as the stored field (LEN 9, slot + 9 bytes, upper-case, rest cleared), not byte for byte.
        if ((frame[4].toInt() and 255) == FiioCodec.NAME) return reply.size >= 18 && (reply[5].toInt() and 255) == 9 &&
            reply[6] == frame[6] && (7 until frame.size - 2).all { reply[it] == frame[it] } && reply[17] == 0xEE.toByte()
        if (reply.size < frame.size || reply[5] != frame[5] || reply[frame.lastIndex] != 0xEE.toByte()) return false
        val band = (frame[4].toInt() and 255) == FiioCodec.BAND
        if (!(6 until frame.size - 2).all { (band && it in 11..12) || reply[it] == frame[it] }) return false
        if (!band) return true
        fun q(bytes: ByteArray) = ((bytes[11].toInt() and 255) shl 8) or (bytes[12].toInt() and 255)
        return kotlin.math.abs(q(frame) - q(reply)) <= 1 + q(frame) / 250
    }
    /** Melody 1.20 sends 00 EE 00 <counter> 00 plus stale tail, never an AA SAVE echo.
     * Neither this completion report nor silence verifies a save; the following slot/curve read does. */
    private fun saveMelody(f: FiioFrame) {
        try {
            request(f.reportId, f.payload()) { p ->
                p.size >= 5 && p[0] == 0.toByte() && p[1] == 0xEE.toByte() && p[2] == 0.toByte() && p[4] == 0.toByte()
            }
        } catch (e: java.io.IOException) { if (!isReplyTimeout(e)) throw e }
    }
    /**
     * The KA15 SAVE (0x19): the DAC reports `00 ee` when the flash write is done, about 820-830 ms after the frame (hardware, hang analysis 22.10.2026). The report is read here
     * so it never sits unread in front of the polls that follow (the first poll after a save used to go out ~845 ms later, met that report, and the KA15 never answered again).
     * Silence is accepted: the read after [FiioWritePlan.delayAfterSaveMs] decides the save, not this report.
     */
    private fun saveKa15(f: FiioFrame) {
        try {
            request(f.reportId, f.payload()) { p -> p.size >= 2 && p[0] == 0.toByte() && p[1] == 0xEE.toByte() }
        } catch (e: java.io.IOException) { if (!isReplyTimeout(e)) throw e }
    }
    private fun melodySlotMismatch(c: FiioCodec, expected: Int, actual: Int) =
        "${FiioMelodyExchange.name(c.config)} requested ${c.config.slotLabels[expected] ?: "USER"} (slot $expected), but DAC reports slot $actual; not verified"
    private fun readFiio(c: FiioCodec): NativeState.Fiio {
        // KA15: names first, as the official app does on connect. SnowSky: names last, so no name traffic sits
        // before the slot query whose reply goes stale ([FiioConfig.nameBySlotId]).
        val early = c.config.userSlotNames && !c.config.nameBySlotId
        val slot = (if (early) readNames(c) else emptyMap()).let { names -> readFiioBody(c, fiioReply(c, c.querySlot()), names) }
        return if (c.config.userSlotNames && !early) withNames(c, slot) else slot
    }
    private fun readNames(c: FiioCodec) = c.config.userSlots.sorted().associateWith { readName(c, it) }
    /** SnowSky: the names read after the curve (they never decide the slot). */
    private fun withNames(c: FiioCodec, state: NativeState.Fiio) = if (c.config.userSlotNames) state.copy(names = readNames(c)) else state
    /** The preamp query of a DAC that may answer it with its bypass "no EQ active" frame ([FiioConfig.bypassPreampAnswersAsCount]): the 0x17 reply, or on the bypass slot that one 0x18 frame. */
    private fun bypassPreampReply(c: FiioCodec, activeSlot: Int): ByteArray {
        val f = c.queryPreamp()
        val raw = request(f.reportId, f.payload()) { p -> runCatching {
            val l = c.logicalReply(f.reportId, p, p.size)
            require(l[4] == f.payload()[4] || c.isBypassNoEqReply(l, activeSlot))
        }.isSuccess }
        return c.logicalReply(f.reportId, raw, raw.size)
    }
    private fun readFiioBody(c: FiioCodec, slot: ByteArray, names: Map<Int, String> = emptyMap()): NativeState.Fiio {
        val count = fiioReply(c, c.queryCount())
        val bypassCheck = c.config.bypassPreampAnswersAsCount && c.parseSlot(slot) == c.config.bypassSlot
        val preamp = if (bypassCheck) bypassPreampReply(c, c.parseSlot(slot)) else fiioReply(c, c.queryPreamp())
        if (bypassCheck && c.isBypassNoEqReply(preamp, c.parseSlot(slot))) // measured on the RETRO NANO: Close EQ answers the preamp query with a 0x18 frame, data 0; no bands to read
            return NativeState.Fiio(c, c.bypassSnapshot(slot, count, fiioReply(c, c.querySlot())), names = names)
        val bands = c.activeBandQueries(c.parseCount(count)).map { fiioReply(c, it) }
        // USER band/preamp traffic corrupts the SnowSky slot reply. Never query/predict it here.
        // 0..6 may still be a stock preset, and Close EQ is stable: retain their closing guard.
        val opening = c.parseSlot(slot)
        val raw = if (melody && (opening in c.config.userSlots || opening in 7..9))
            c.melodyUserSnapshot(slot, count, preamp, bands)
        else {
            val closing = fiioReply(c, c.querySlot())
            // SnowSky cold plug on a USER slot: the first reply is a boot value (0 on the Tiny A, Pixel 14.10.2026) and
            // after the band reads the same register says the last band index. A stock preset answers the same both times.
            if (melody && c.parseSlot(closing) != opening && c.parseSlot(closing) in 7..9) c.melodyUserSnapshot(closing, count, preamp, bands)
            else c.snapshot(slot, count, preamp, bands, closing)
        }
        return NativeState.Fiio(c, raw, names = names)
    }
    private fun readName(c: FiioCodec, slot: Int) = c.parseName(fiioReply(c, c.queryName(slot)), slot)
    /** After a verified save the USER slot takes the profile's name, unless the name is in another script than Latin
     * ([FiioCodec.nameFitsSlot]): then the slot keeps its own. A name that does not read back is a note, never a failed save:
     * the EQ is already on the DAC. */
    private fun nameAfterSave(c: FiioCodec, back: NativeState.Fiio, slot: Int, profileName: String): Pair<NativeState.Fiio, String?> {
        if (!FiioCodec.nameFitsSlot(profileName)) return back to null
        val name = FiioCodec.slotName(profileName, c.config.nameLength)
        if (name.isEmpty() || back.names[slot] == name) return back to null
        return try {
            writeAndConsumeEcho(c.writeName(slot, name))
            val read = retryTimeouts { readName(c, slot) }
            back.copy(names = back.names + (slot to read)) to (if (read == name) null else "Slot name reads back as $read, not $name")
        } catch (e: java.io.IOException) {
            if (!isReplyTimeout(e)) throw e
            back to "Slot name not confirmed: no reply"
        } catch (e: IllegalArgumentException) { back to "Slot name not confirmed: ${e.message}" }
    }
    private fun codec(): FiioCodec = FiioCodec(requireNotNull(target.fiio) { "Not a FiiO DAC" })
    /** Renames one USER slot (KA15) and reads the name back; the DAC's EQ is untouched. */
    fun renameSlot(slot: Int, name: String, explicitUserAction: Boolean): String = guarded {
        require(explicitUserAction) { "Rename requires an explicit user action" }
        val c = codec()
        val frame = c.writeName(slot, name)
        retryTimeouts { readName(c, slot) }
        writeAndConsumeEcho(frame)
        retryTimeouts { readName(c, slot) }
    }
    /**
     * Reads every readable setting of the device's list ([DeviceTarget.settings]), one `get` after another through the same request
     * path as a normal read; nothing is written. [log] gets every TX and RX as hex with the setting id.
     *
     * Slot register: on the SnowSky DACs a USER slot's 0x16 reply goes stale after any other query and is right only straight after a
     * select or SAVE (PROTOCOL.md, commit 1a6f96b). Nothing here reads, assumes or changes the slot, and the app keeps the slot it
     * knows. The next normal read tolerates whatever the register holds (it already treats an opening reply that differs from the
     * closing one as "USER slot, number unknown"), except a value that looks like a USER slot id (160..162): so the toggles and the
     * choices, whose replies are small numbers (0..6), are read last and the levels first.
     */
    fun readSettings(log: (String) -> Unit = {}): List<SettingReading> = guarded {
        val c = codec()
        val readable = target.settings.filter { it.read is SettingRead.FiioGet }
        fun readRank(s: DacSetting) = if (s.kind == SettingKind.TOGGLE || s.kind == SettingKind.CHOICE) 1 else 0
        // The header info (sample rate, firmware) goes last. FiiO's own pause between commands for the model.
        val gap = (ProbePlan.fiioGapMs(c.config) ?: 0).toLong()
        val jobs: List<() -> SettingReading> =
            readable.sortedBy(::readRank).map { s -> val r = s.read as SettingRead.FiioGet; { readOne(c, s.id, r.command, r.replyData, r.decode, false, log) } } +
                target.info.map { i -> { readOne(c, i.id, i.command, i.replyData, i.decode, true, log) } }
        jobs.mapIndexed { n, job -> if (n > 0) pause(gap); job() }
    }
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 255) }
    private fun readOne(c: FiioCodec, id: String, cmd: Int, replyData: Int, decode: (ByteArray) -> Int?, crcOptional: Boolean, log: (String) -> Unit): SettingReading {
        val f = c.querySetting(cmd)
        val tx = f.payload()
        log("settings $id TX ${hex(tx)}")
        try {
            val rx = request(f.reportId, tx) { p -> p.size >= 7 && (p[0].toInt() and 255) == 0xBB && (p[1].toInt() and 255) == 0x0B && (p[4].toInt() and 255) == cmd }
            val logical = runCatching { c.settingReply(rx, cmd, replyData, crcOptional) }
            log("settings $id RX ${hex(logical.getOrDefault(rx))}")
            val value = logical.getOrNull()?.let { runCatching { decode(it) }.getOrNull() }
            log("settings $id = ${value ?: "NO REPLY (unparsable)"}")
            return SettingReading(id, value, if (value == null) "unparsable reply" else null)
        } catch (e: java.io.IOException) {
            if (!isReplyTimeout(e)) throw e
            log("settings $id NO REPLY")
            return SettingReading(id, null, "no reply")
        }
    }
    /** One settings read with up to two tries (a silent DAC is asked once more; an unparsable reply too). Reads only. */
    private fun readValue(c: FiioCodec, s: DacSetting, log: (String) -> Unit): Int? {
        val r = s.read as? SettingRead.FiioGet ?: return null
        val gap = (ProbePlan.fiioGapMs(c.config) ?: 0).toLong()
        repeat(2) { n ->
            if (n > 0) pause(gap)
            readOne(c, s.id, r.command, r.replyData, r.decode, false, log).value?.let { return it }
        }
        return null
    }
    /**
     * One settings write: the SET, its echo (every write consumes its echo; the KA15 stops answering when writes pile up unread), the model's gap, then
     * the read-back: a GET of the same command, at most two tries. [request] takes only a reply whose command byte is the setting's, so a stale
     * report of another command is dropped, never taken for the value. A missing echo is logged and does not decide: the read-back does.
     */
    private fun writeOne(c: FiioCodec, s: DacSetting, value: Int, log: (String) -> Unit): SettingWriteOutcome {
        val frame = c.setSetting(s, value)
        val gap = (ProbePlan.fiioGapMs(c.config) ?: 0).toLong()
        val tx = frame.payload()
        val cmd = tx[4]
        pause(gap)
        log("settings ${s.id} SET TX ${hex(tx)}")
        try {
            val echo = request(frame.reportId, tx) { p -> p.size >= 6 && p[0] == 0xAA.toByte() && p[1] == 0x0A.toByte() && p[4] == cmd }
            log("settings ${s.id} SET RX ${hex(echo)}")
        } catch (e: java.io.IOException) {
            // a row that makes the DAC leave the bus ([DacSetting.reconnectsAfterWrite]) may lose its echo to the detach: not an error, the read after it is back decides
            if (!isReplyTimeout(e) && !s.reconnectsAfterWrite) throw e
            log("settings ${s.id} SET no echo")
        }
        pause(gap)
        // The KA15 stopped answering after a FILTER write read back at once (17.10.2026): it settles first, the late re-reads below stay as the second chance.
        if (settleBeforeReadMs(c, s) > 0) pause(settleBeforeReadMs(c, s))
        var got = readValue(c, s, log)
        var late = 0
        while (got != value && late < LATE_READBACKS) {
            late++
            log("settings ${s.id} read-back ${got ?: "NO REPLY"} (wrote $value), asking again in $LATE_READBACK_MS ms ($late of $LATE_READBACKS)")
            pause(LATE_READBACK_MS)
            got = readValue(c, s, log)
        }
        log("settings ${s.id} read-back ${got ?: "NO REPLY"} (wrote $value)")
        return when {
            got == null -> SettingWriteOutcome.NoReply
            got == value -> SettingWriteOutcome.Verified
            else -> SettingWriteOutcome.Mismatch(got)
        }
    }
    /** How long a settings write waits before its first read-back: the KA15 FILTER needs [FILTER_SETTLE_MS], every other row only the model's gap. */
    private fun settleBeforeReadMs(c: FiioCodec, s: DacSetting): Long =
        if (s.id == "filter" && !melody && c.config.consumeWriteEchoes) FILTER_SETTLE_MS else 0L
    /** One setting as the DAC reads now (up to two tries), or null when it does not answer. Reads only: the bench's `setting-plan` takes the value to restore from it. */
    fun readSetting(id: String, log: (String) -> Unit = {}): Int? = guarded {
        val c = codec()
        val s = target.settings.firstOrNull { it.id == id } ?: throw IllegalArgumentException("$id is not in the ${target.caps.name} list")
        readValue(c, s, log)
    }
    /** What the read-back said: the value when it answered, else null. */
    private fun back(o: SettingWriteOutcome, wrote: Int): Int? = when (o) {
        SettingWriteOutcome.Verified -> wrote
        is SettingWriteOutcome.Mismatch -> o.read
        SettingWriteOutcome.NoReply -> null
    }
    /**
     * Sets one setting of the connected model and reads it back ([writeOne]). Refuses (IllegalArgumentException) what [DacWrite] refuses: a setting
     * that is not in this model's list, USB AUDIO, the never-send commands, a value out of range. The app calls it only for a setting whose
     * [DacSetting.writeEnabled] is true (proven, or a review build; the controller checks), and the perf-only write self-test uses [writeTest].
     */
    fun writeSetting(id: String, value: Int, log: (String) -> Unit = {}): SettingWriteOutcome = guarded {
        val c = codec()
        val s = target.settings.firstOrNull { it.id == id } ?: throw IllegalArgumentException("$id is not in the ${target.caps.name} list")
        writeOne(c, s, value, log)
    }
    /**
     * The SET of a setting whose write makes the DAC leave the USB bus ([DacSetting.reconnectsAfterWrite]: the JA11's in-line mic switch) and nothing after it: the frame, its echo if one still arrives, no read-back, no guard after it
     * (the session is gone or going). Returns the value the caller must find with a settings read once the DAC is back ([writeSetting] reads back in the same session and is for rows that stay on the bus). Same refusals as [writeSetting].
     */
    fun sendSettingThenLeave(id: String, value: Int, log: (String) -> Unit = {}) {
        port.guard()
        val c = codec()
        val s = target.settings.firstOrNull { it.id == id } ?: throw IllegalArgumentException("$id is not in the ${target.caps.name} list")
        require(s.reconnectsAfterWrite) { "${s.label} does not make the DAC leave the bus: use writeSetting" }
        val frame = c.setSetting(s, value)
        val tx = frame.payload()
        val cmd = tx[4]
        pause((ProbePlan.fiioGapMs(c.config) ?: 0).toLong())
        log("settings ${s.id} SET TX ${hex(tx)} (the DAC leaves the bus after it)")
        try {
            request(frame.reportId, tx) { p -> p.size >= 6 && p[0] == 0xAA.toByte() && p[1] == 0x0A.toByte() && p[4] == cmd }
            log("settings ${s.id} SET echoed")
        } catch (e: java.io.IOException) {
            log("settings ${s.id} SET no echo (${e.message}): the DAC may have left the bus with the frame")
        }
    }
    /**
     * The write self-test (perf and debug builds, `--es writetest <id> [--ei to <v>]`): read the original, write [to] (default: the next option,
     * original -1 / +1 for a level, one step towards L when the balance is centred, else one step back to the centre), echo and read back, write the
     * original, echo and read back. VOLUME STEPS also logs the VOLUME before, after the write and after the restore (120 may rescale it). When the
     * restore does not read back the original the result is RESTORE_FAILED and nothing more is sent. Refuses a setting that is not in the model's
     * list and 0x20 before any frame goes out.
     */
    fun writeTest(id: String, to: Int?, log: (String) -> Unit = {}): WriteTestResult = guarded {
        val c = codec()
        val s = target.settings.firstOrNull { it.id == id } ?: throw IllegalArgumentException("$id is not in the ${target.caps.name} list")
        DacWrite.checkSetting(c.config, s)
        require(s.read is SettingRead.FiioGet) { "${s.label} has no read-back" }
        fun finish(r: WriteTestResult): WriteTestResult { log(r.line()); r.extra?.let(log); return r }
        val original = readValue(c, s, log) ?: return@guarded finish(WriteTestResult(id, null, to, null, null, "NO_REPLY"))
        // the top of a level that follows another setting (the volume follows VOLUME STEPS)
        val top = s.maxRule?.let { rule ->
            val src = target.settings.firstOrNull { it.id == rule.settingId }?.let { readValue(c, it, log) }
            s.maxFor { if (it == rule.settingId) src else null }
        } ?: s.max
        val volume = if (id == "volume-steps") target.settings.firstOrNull { it.id == "volume" } else null
        val volumeBefore = volume?.let { readValue(c, it, log) }
        val goal = to ?: when (s.kind) {
            SettingKind.TOGGLE, SettingKind.CHOICE -> (original + 1) % s.options.size
            SettingKind.LEVEL -> if (original > s.min) original - 1 else original + 1
            SettingKind.BALANCE -> if (original == 0) -1 else original - Integer.signum(original)
        }
        DacWrite.checkValue(s, goal)
        if (s.kind == SettingKind.LEVEL) require(goal <= top) { "${s.label}: $goal is above the current top $top" }
        val first = writeOne(c, s, goal, log)
        val volumeAfterWrite = volume?.let { readValue(c, it, log) }
        val second = writeOne(c, s, original, log)
        val volumeAfterRestore = volume?.let { readValue(c, it, log) }
        val extra = volume?.let { "WRITETEST $id volume before=${volumeBefore ?: "none"} afterWrite=${volumeAfterWrite ?: "none"} afterRestore=${volumeAfterRestore ?: "none"}" }
        val backValue = back(first, goal)
        val restored = back(second, original)
        if (second != SettingWriteOutcome.Verified) {
            log("WRITETEST $id RESTORE_FAILED: wrote $original back, the DAC reads ${restored ?: "nothing"}. NOTHING MORE IS SENT - check the DAC by hand")
            return@guarded finish(WriteTestResult(id, original, goal, backValue, restored, "RESTORE_FAILED", extra))
        }
        val result = when (first) {
            SettingWriteOutcome.Verified -> "OK"
            is SettingWriteOutcome.Mismatch -> "MISMATCH"
            SettingWriteOutcome.NoReply -> "NO_REPLY"
        }
        finish(WriteTestResult(id, original, goal, backValue, restored, result, extra))
    }
    /**
     * The DAC probe's FiiO frame layer (review builds): the active slot (0x16), every GET of [ProbePlan.FIIO_COMMANDS], the slot again, each with
     * its TX and RX (or no reply) in [report]. [gapMs] is FiiO's own pause between commands for the model. Reads only: a frame that is not on the
     * allow-list cannot be built. Never retries (a silent command is a result), never selects, switches or asks for a slot's content.
     */
    fun probeFiio(report: ProbeReport, gapMs: Int) {
        val c = codec()
        val layer = "fiio"
        var slotBefore: Int? = null
        ProbePlan.FIIO_SEQUENCE.forEachIndexed { i, cmd ->
            if (i > 0) pause(gapMs.toLong())
            val f = ProbePlan.fiioFrame(c, cmd)
            val tx = f.payload()
            val name = "0x%02x".format(cmd)
            report.tx(layer, name, hex(tx))
            try {
                val rx = request(f.reportId, tx) { p -> p.size >= 7 && (p[0].toInt() and 255) == 0xBB && (p[1].toInt() and 255) == 0x0B && (p[4].toInt() and 255) == cmd }
                val logical = runCatching { c.settingReply(rx, cmd, 0) }.getOrNull()
                report.rx(layer, name, logical?.let(::hex) ?: "raw ${hex(rx)}")
                if (cmd == ProbePlan.FIIO_SLOT && logical != null && logical.size >= 7 && (logical[5].toInt() and 255) >= 1) {
                    val slot = logical[6].toInt() and 255
                    if (i == 0) { slotBefore = slot; report.note(layer, "slot before $slot") }
                    else report.note(layer, "slot after $slot" + if (slot == slotBefore) " (same)" else " (differs from before $slotBefore; SnowSky slot registers go stale after queries)")
                }
            } catch (e: java.io.IOException) {
                if (!isReplyTimeout(e)) throw e
                report.noReply(layer, name)
            }
        }
    }
    fun read(): NativeState = guarded { codec().let { c -> retryTimeouts(5) { readFiio(c) } } }
    /** Switches the DAC to USER [slot], as its own preset button would (nothing is written or saved), and returns the slot one
     * query confirms; no band is read. A tap on a slot Contour already knows, and the end of the background scan on connect.
     * Same echo and settle handling as [readSlot]; the SnowSky DACs always select. The caller compares the answer with [slot]. */
    fun selectSlot(slot: Int, explicitUserAction: Boolean): Int = guarded {
        require(explicitUserAction) { "Switching slots requires an explicit user action" }
        val c = codec()
        selectAndConfirm(c, c.selectUserSlot(slot))
    }
    /** [selectSlot] for the model's bypass / Close EQ ([FiioCodec.selectBypassSlot]): only to put the DAC back on the slot it was found on (the bench's `select`). The app never calls it. */
    fun selectBypassSlot(explicitUserAction: Boolean): Int = guarded {
        require(explicitUserAction) { "Switching slots requires an explicit user action" }
        val c = codec()
        selectAndConfirm(c, c.selectBypassSlot())
    }
    private fun selectAndConfirm(c: FiioCodec, select: FiioFrame): Int {
        if (c.config.consumeWriteEchoes) writeAndConsumeEcho(select, requireEcho = melody) else send(select.reportId, select.payload())
        pause(SELECT_SETTLE_MS)
        return c.parseSlot(retryTimeouts { fiioReply(c, c.querySlot()) })
    }
    /** A tap on a FiiO USER slot Contour has not seen, and the background scan on connect: switches the DAC to USER [slot], as its own
     * preset button would (nothing is written or saved), and reads that slot. The SnowSky DACs always select, like a send: their USER slot reply may be stale. */
    fun readSlot(slot: Int, explicitUserAction: Boolean): NativeState.Fiio = guarded {
        require(explicitUserAction) { "Reading another slot requires an explicit user action" }
        val c = codec()
        val before = if (melody) null else read() as NativeState.Fiio
        if (before?.raw?.activeSlot != slot) {
            val select = c.selectUserSlot(slot)
            if (c.config.consumeWriteEchoes) writeAndConsumeEcho(select, requireEcho = melody) else send(select.reportId, select.payload())
            pause(SELECT_SETTLE_MS)
        }
        val selected = retryTimeouts { fiioReply(c, c.querySlot()) }
        val selectedSlot = c.parseSlot(selected)
        require(selectedSlot == slot) { if (melody) melodySlotMismatch(c, slot, selectedSlot) else "FiiO slot switch failed" }
        if (melody) withNames(c, retryTimeouts { readFiioBody(c, selected) }) else retryTimeouts(5) { readFiio(c) }
    }
    /** [targetSlot]: the USER slot chosen in the app (KA15 slot picker); null = the target's fixed destination.
     * [onProgress] (done, total): the HID frames completed so far out of those planned; called on the calling thread. */
    fun write(profile: Profile, explicitHold: Boolean, targetSlot: Int? = null, onProgress: ((done: Int, total: Int) -> Unit)? = null): NativeWriteResult {
        port.guard()
        // A DAC that leaves the USB bus on SAVE ([FiioConfig.disconnectOnSave]) ends the session at the SAVE frame: no guard after it (the session is gone or going), the result is pending.
        return writeBody(profile, explicitHold, targetSlot, onProgress).also { if (!it.pending) port.guard() }
    }
    private fun writeBody(profile: Profile, explicitHold: Boolean, targetSlot: Int?, onProgress: ((done: Int, total: Int) -> Unit)?): NativeWriteResult {
        require(explicitHold) { "HOLD TO SEND required" }
        require(target.issues(profile).isEmpty()) { target.issues(profile).joinToString("; ") }
        // Frames planned: the read before, select + check + backup read, preamp + count + one per band, save, the read-back, the name.
        val fiio = requireNotNull(target.fiio)
        val leavesOnSave = fiio.disconnectOnSave
        val readFrames = 3 + codec().padToDeviceCount(profile.bands).size + (if (melody) 0 else 1) + (if (fiio.userSlotNames) fiio.userSlots.size else 0)
        progress = onProgress
        stepsDone = 0
        stepsTotal = readFrames + (2 + readFrames) + (2 + codec().padToDeviceCount(profile.bands).size) + 1 + (if (leavesOnSave) 0 else readFrames + (if (fiio.userSlotNames) 2 else 0))
        onProgress?.invoke(0, stepsTotal)
        return try {
        when (val before = read()) {
            is NativeState.Fiio -> {
                val c = before.codec
                val p = c.planOnExplicitSend(profile.copy(bands = c.padToDeviceCount(profile.bands), preampDb = profile.effectivePreampDb(c.config.shelfForm)), targetSlot ?: target.destinationSlot!!, true)
                // The official app never switches presets to save the active one. On the Pixel a KA15 preset
                // switch silenced every later HID reply (hardware, 03.10.2026), so only switch when needed.
                // The SnowSky DACs must always select: their initial USER slot reply may already be stale.
                val alreadyActive = !melody && before.raw.activeSlot == p.targetSlot
                if (alreadyActive) stepsTotal -= 2 + readFrames // no switch, and the read before is the backup
                if (!alreadyActive) {
                    // 0x19 saves the active preset whatever its slot byte says, so another USER slot needs the switch.
                    if (c.config.consumeWriteEchoes) writeAndConsumeEcho(p.selection, requireEcho = melody) else send(p.selection.reportId, p.selection.payload())
                    pause(p.delayAfterSelectMs.toLong())
                }
                val selected = retryTimeouts { fiioReply(c, p.selectionVerification) }
                val selectedSlot = c.parseSlot(selected)
                require(selectedSlot == p.targetSlot) {
                    if (melody) melodySlotMismatch(c, p.targetSlot, selectedSlot) else "FiiO destination selection failed"
                }
                val backup = if (melody) retryTimeouts { readFiioBody(c, selected) }
                    else if (alreadyActive) before else retryTimeouts { readFiio(c) }
                val writes = c.writesAfterSelection(p, selected, backup.raw)
                writes.forEach {
                    if (c.config.consumeWriteEchoes) writeAndConsumeEcho(it) else send(it.reportId, it.payload())
                    if ((it.payload()[4].toInt() and 255) == FiioCodec.COUNT) pause(p.delayAfterCountMs.toLong())
                    pause(c.config.writeGapMs.toLong())
                }
                pause(p.delayBeforeSaveMs.toLong())
                if (leavesOnSave) {
                    // The DAC leaves the USB bus after SAVE (devicePEQ: the JA11): the frame is the last thing asked of it - no echo, no wait, no read, no guard (the session is gone or going). Nothing is verified here:
                    // the first read after the DAC is back must show [FiioWritePlan.expected] (the app: DeviceController's pending save; the bench: read-plan or parse --expect).
                    port.guard()
                    // An IOException here is the transport's session check after the transfer (the DAC detached with the frame): the frame went out, and the read after the DAC is back decides, so it is pending, not a failure.
                    // A frame that did not go out is no IOException (a short report is an IllegalArgumentException) and the read after it would not match.
                    try { port.send(p.save.reportId, NativeHidReports.Kind.OUTPUT, p.save.payload()) } catch (e: java.io.IOException) { port.log("SAVE: the session ended with the frame (the DAC left the bus): ${e.message}") }
                    frameDone()
                    return NativeWriteResult(false, null, null, expected = p.expected, pending = true)
                }
                if (melody) saveMelody(p.save) else if (c.config.consumeWriteEchoes) saveKa15(p.save) else send(p.save.reportId, p.save.payload())
                pause(p.delayAfterSaveMs.toLong())
                val back = if (melody) {
                    // SAVE restores the slot register. Anchor the snapshot before any band/preamp traffic.
                    val saved = retryTimeouts { fiioReply(c, c.querySlot()) }
                    val savedSlot = c.parseSlot(saved)
                    require(savedSlot == p.targetSlot) { melodySlotMismatch(c, p.targetSlot, savedSlot) }
                    // The names read before the send stand; nameAfterSave updates the slot written.
                    retryTimeouts { readFiioBody(c, saved) }.copy(names = before.names)
                } else retryTimeouts { readFiio(c) }
                val ok = c.matches(p.expected, back.raw)
                if (!ok) NativeWriteResult(false, "FiiO native register mismatch", back)
                else if (!c.config.userSlotNames) NativeWriteResult(true, null, back)
                else nameAfterSave(c, back, p.targetSlot, profile.name).let { (named, note) -> NativeWriteResult(true, note, named) }
            }
        }
        } finally { progress = null }
    }

    // ---- factory reset (SnowSky Melody, Tiny A, FiiO KA15): backup, the reset frame, the read after it, the restore ---------------------------------------------------
    // The frame itself exists only through FiioResetFlow's ticket ([FiioCodec.resetFrame]); these three calls are the session's half of that flow.

    /**
     * What the DAC holds right now, as a [FiioResetBackup]: every USER slot (the SnowSky DACs always switch to it: [readSlot]) with its name and native
     * registers, then every readable setting ([readSettings]; a silent one is listed in [FiioResetBackup.unreadSettings]), then the DAC goes back to the
     * USER slot it was on (a stock preset cannot be selected back from here). Used before the reset (the backup) and after it (what the reset left).
     * Nothing is written or saved; switching the slot is what a tap on a slot does.
     */
    fun captureForReset(explicitUserAction: Boolean, takenAtMs: Long, log: (String) -> Unit = {}): FiioResetBackup = guarded {
        require(explicitUserAction) { "A backup before a reset requires an explicit user action" }
        val c = codec()
        require(FiioReset.offered(c.config)) { "${c.config.productName} has no factory reset in Contour" }
        val first = retryTimeouts(5) { readFiio(c) }
        val slots = c.config.userSlots.sorted().map { slot ->
            log("reset backup: reading slot $slot")
            val state = readSlot(slot, explicitUserAction = true)
            FiioSlotBackup(slot, state.names[slot], state.raw.copy(activeSlot = slot))
        }
        val known = target.settings.map { it.id }.toSet()
        val readings = readSettings(log).filter { it.id in known }
        val settings = readings.mapNotNull { r -> r.value?.let { r.id to it } }.toMap()
        val back = first.slot.takeIf { it in c.config.userSlots }
        if (back != null) try { selectSlot(back, explicitUserAction = true) } catch (e: java.io.IOException) {
            if (!isReplyTimeout(e)) throw e
            log("reset backup: the DAC did not confirm the return to slot $back")
        }
        FiioResetBackup(c.config.productName, takenAtMs, first.slot, slots, settings, readings.filter { it.value == null }.map { it.id })
    }

    /**
     * Sends the factory reset: `aa 0a 00 SEQ 21 01 00 CRC ee`, built by [FiioCodec.resetFrame] from [ticket] only. No answer, a late answer or the
     * session ending after the frame went out are all normal (the DAC may restart): they come back in [FiioResetSent], never as an exception, and
     * nothing counts as reset until a read after it says so. The session is not guarded after the send (the DAC may already be gone).
     * The DAC answers the frame at once, but not with the echo `aa 0a ... 21` of the other commands: it sends a bare `00 ee` and its reply counter (Melody, Tiny A and KA15 phone
     * runs, 21-25.10.2026). That answer is not an echo, so [FiioResetSent.echoed] stays false, but the note and the log name it instead of saying "no answer".
     */
    fun factoryReset(ticket: FiioResetTicket, log: (String) -> Unit = {}): FiioResetSent {
        port.guard()
        val c = codec()
        val frame = c.resetFrame(ticket) // refuses another model and a ticket that was spent
        val tx = frame.payload()
        log("reset TX ${hex(tx)}")
        var other: ByteArray? = null
        return try {
            val echo = port.request(frame.reportId, tx) { p ->
                (p.size >= 6 && p[0] == 0xAA.toByte() && p[1] == 0x0A.toByte() && p[4] == tx[4]).also { if (!it && other == null) other = p.copyOf(minOf(p.size, 4)) }
            }
            log("reset RX ${hex(echo)}")
            FiioResetSent(true, null)
        } catch (e: java.io.IOException) {
            val answer = other
            if (answer != null && isReplyTimeout(e)) log("reset RX (not an echo) ${hex(answer)}")
            val note = if (!isReplyTimeout(e)) "the session ended after the reset frame: ${e.message}"
                else if (answer != null) "the DAC answered the reset frame, but not with an echo of it (it may be restarting)"
                else "no answer to the reset frame (the DAC may be restarting)"
            log("reset: $note")
            FiioResetSent(false, note)
        }
    }

    /**
     * Writes [backup] back after a reset: each USER slot (the exact registers, through [write]: switch, backup read, writes, SAVE, read-back, then the
     * name), then every saved setting that differs from what the DAC reads now and may be written ([DacWrite]; VOLUME STEPS first, the volume last), then
     * the USER slot the DAC was on. A slot or setting that fails is a line, never a silent skip; a lost session stops the restore. [FiioRestoreResult.ok]
     * is true only when everything that had to be written read back as saved.
     */
    fun restoreAfterReset(backup: FiioResetBackup, log: (String) -> Unit = {}): FiioRestoreResult = guarded {
        val c = codec()
        require(FiioReset.offered(c.config)) { "${c.config.productName} has no factory reset in Contour" }
        backup.problems(c.config).let { require(it.isEmpty()) { "Backup incomplete: " + it.joinToString("; ") } }
        val lines = ArrayList<String>()
        var ok = true
        var lost = false
        for (sb in backup.slots.sortedBy { it.slot }) {
            val label = c.config.slotLabels[sb.slot] ?: "slot ${sb.slot}"
            if (lost) { lines += "$label: not restored (the DAC session ended)"; ok = false; continue }
            try {
                val eq = c.importExact(sb.snapshot)
                val profile = Profile("restore-${sb.slot}", sb.name.orEmpty(), bands = eq.bands, preampDb = eq.preampDb, createdAt = 0, updatedAt = 0)
                val r = write(profile, explicitHold = true, targetSlot = sb.slot)
                log("reset restore: $label ${if (r.verified) "VERIFIED" else "NOT VERIFIED"}${r.reason?.let { ": $it" }.orEmpty()}")
                lines += if (r.verified) "$label: restored" + (r.reason?.let { " ($it)" } ?: "") else "$label: NOT restored: ${r.reason}"
                if (!r.verified) ok = false
            } catch (e: java.io.IOException) {
                lines += "$label: NOT restored: ${e.message}"; ok = false; lost = true
            } catch (e: IllegalArgumentException) {
                lines += "$label: NOT restored: ${e.message}"; ok = false
            }
        }
        val byId = target.settings.associateBy { it.id }
        for ((id, value) in backup.settings.entries.sortedBy { FiioReset.restoreRank(it.key) }) {
            val s = byId[id] ?: continue
            val label = s.label
            if (lost) { lines += "$label: not restored (the DAC session ended)"; ok = false; continue }
            if (!s.writeEnabled) continue // read-only on this DAC: nothing to write back
            try {
                val now = readValue(c, s, log)
                if (now == value) { lines += "$label: unchanged"; continue }
                when (val o = writeOne(c, s, value, log)) {
                    SettingWriteOutcome.Verified -> lines += "$label: restored"
                    is SettingWriteOutcome.Mismatch -> { lines += "$label: NOT restored: the DAC reads ${o.read}, saved $value"; ok = false }
                    SettingWriteOutcome.NoReply -> { lines += "$label: NOT restored: no reply"; ok = false }
                }
            } catch (e: java.io.IOException) {
                lines += "$label: NOT restored: ${e.message}"; ok = false; lost = true
            } catch (e: IllegalArgumentException) {
                lines += "$label: NOT restored: ${e.message}"; ok = false
            }
        }
        val slot = backup.activeSlot
        if (!lost && slot != null && slot in c.config.userSlots) {
            try {
                val now = selectSlot(slot, explicitUserAction = true)
                lines += if (now == slot) "The DAC is back on ${c.config.slotLabels[slot] ?: "slot $slot"}" else "The DAC reports slot $now, not ${c.config.slotLabels[slot] ?: "slot $slot"}"
            } catch (e: java.io.IOException) {
                lines += "The DAC did not confirm the return to slot $slot: ${e.message}"
            }
        } else if (slot != null && slot !in c.config.userSlots) lines += "The DAC was on a stock preset or Close EQ before the reset: choose it on the DAC"
        FiioRestoreResult(lines, ok)
    }
}
