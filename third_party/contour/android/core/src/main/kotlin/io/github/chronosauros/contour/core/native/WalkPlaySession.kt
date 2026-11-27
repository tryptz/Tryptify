package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.DacImport
import io.github.chronosauros.contour.core.DeviceCapabilities
import io.github.chronosauros.contour.core.DevicePlan
import io.github.chronosauros.contour.core.DeviceProtocol
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.Proven
import io.github.chronosauros.contour.core.ProtocolMicro
import io.github.chronosauros.contour.core.WalkPlay
import io.github.chronosauros.contour.core.devices.GeneratedDevices
import io.github.chronosauros.contour.core.devices.PendingRecords
import java.io.IOException

/**
 * A WalkPlay DAC a [WalkPlaySession] can drive. [protocol] is the code the app runs for it (planner, write sequence, parsers),
 * so the frames are the app's. [types] are the filter types the profile offers; [hardwareVerified] is false for a DAC no
 * write of Contour ever reached. The Moondrop Echo-A has no protocol of its own: it is a SchemeNo11 row of devicePEQ's catalog
 * (8 bands), driven by the Protocol Micro's code. [caps] are the limits the planner holds a band to: the protocol's own, or the
 * record's for the Echo-A (PK, LS, emulated HS, LP, HP; gain +-10 dB, Q 0.1-10). [preampMaxDb] is the top of the device preamp the planner takes:
 * 0 until [ProtocolMicro.microPositivePreampOn] (Micro) or [ProtocolMicro.echoAPositivePreampOn] (Echo-A): the vendor's +6 dB is unproven on each, [Proven]. Only the Micro's code reads it.
 *
 * [hardwareVerified] is the profile's own flag (what is proven); whether the app WRITES it is [writeOn] ([Proven.on]). [capsOverride] and [preampMaxOverride] replace the
 * protocol's current caps and preamp top (the bench and the tests widen a profile with them); null follows the protocol, which follows the gate at the time of the call.
 */
data class WalkPlayProfile(
    val id: String,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val protocol: DeviceProtocol,
    val types: Set<FilterType>,
    val schemeNo: Int,
    val hardwareVerified: Boolean,
    /** The literal USB product string, where one was read; null = not recorded. */
    val usbName: String? = null,
    val capsOverride: DeviceCapabilities? = null,
    val preampMaxOverride: Int? = null,
) {
    val bands: Int get() = protocol.caps.bands
    val caps: DeviceCapabilities get() = capsOverride ?: protocol.caps
    val preampMaxDb: Int get() = preampMaxOverride ?: if (protocol == DeviceProtocol.MICRO && productId == WalkPlayProfiles.ECHO_A_PRODUCT_ID) ProtocolMicro.ECHO_A_PREAMP_MAX_EFFECTIVE_DB else protocol.preampMax
    /** The app writes this DAC: its flag is proven, or this is a review build. */
    val writeOn: Boolean get() = Proven.on(hardwareVerified)
}

object WalkPlayProfiles {
    // Identity, name, filter types and SchemeNo of every profile are its record's (devices/*.json); what stays here is which protocol code drives it and
    // that no write of Contour ever reached the Echo-A.
    const val ECHO_A_PRODUCT_ID = GeneratedDevices.MOONDROP_ECHO_A_PRODUCT_ID

    /**
     * THE verification flag of the Echo-A EQ write (PEAK, LOW SHELF and the emulated HIGH SHELF, LOW PASS, HIGH PASS, preamp -30..0). True since the desk measurement of 25.10.2026 at a
     * 48 kHz stream (run 2026-10-25-moondrop-echo-a-05-mac-eqtest-48k: PK, shelf mix with the emulated HS, preamp -6, LP and HP all PASS; the DSP is fixed and does not follow the stream).
     * False would keep the Echo-A read only, with the "not verified on hardware" label. Its reset and mic stay as they were (reset behind [WalkPlayReset.VERIFIED], mic read only), and a
     * positive preamp stays off ([ProtocolMicro.ECHO_A_POSITIVE_PREAMP_VERIFIED]).
     */
    const val ECHO_A_HARDWARE_VERIFIED = true

    private fun stable(id: String, protocol: DeviceProtocol, hardwareVerified: Boolean = true) = protocol.data.let { d ->
        WalkPlayProfile(id, d.name, d.vendorId, d.productId, protocol, d.types, d.schemeNo, hardwareVerified, usbName = d.usbName)
    }
    val micro = stable("micro", DeviceProtocol.MICRO)
    val max = stable("max", DeviceProtocol.MAX)
    val trn = stable("trn", DeviceProtocol.TRN)
    /** The Tanchjim Space II: written and read back on a unit from the Mac (runs 2026-11-13-tanchjim-space-ii-01-mac to -04-mac-max) and from a phone (edge profile VERIFIED, a replug kept it, 13.11.2026), so it writes in every build. */
    val space2 = stable("space-2", DeviceProtocol.SPACE_2)
    /**
     * THE write flag of the EPZ TP65 (supported since 1.8.2; tested by a TP65 owner, https://github.com/Chronosauros/contour/issues/6). True since 1.8.1-tp65test3: the read-only 1.8.0-tp65test2 passed on
     * the unit (issue comment of 26.11.2026: firmware 0.4, all ten bands and the preamp as on Windows, byte for byte, over a replug) and its owner asked for the TP65 write. The write is
     * the TP65's own ([WalkPlay.tp65WriteSequence]): only the band frames and the RAM apply `01 0A` its owner validated (Windows, 25.11.2026), the changed bands only, no preamp, `01 05`,
     * `01 17` or flash `01 01 01`, and [WalkPlayGuard] refuses every other frame for it. (1.8.0-tp65test1 had it true on the Space II's sequence, 1.8.0-tp65test2 false.) The tp65test3
     * PEAK sends passed on the unit (issue comment of 26.11.2026, five sends VERIFIED, byte for byte after a replug and on Windows); 1.8.1-tp65test4 added the two shelves on the same frames,
     * and they passed too (issue comment of 27.11.2026: LOW SHELF 100 Hz and HIGH SHELF 4000 Hz at -6 dB, then flat, each VERIFIED, the frames byte for byte as planned). 1.8.2 ships it unchanged.
     */
    const val TP65_TESTER_WRITE = true
    val tp65 = stable("epz-tp65", DeviceProtocol.EPZ_TP65, TP65_TESTER_WRITE)
    val echoA = GeneratedDevices.MOONDROP_ECHO_A.let { d ->
        // The Echo-A runs the Protocol Micro's code, so its record has to agree with that protocol on what the code takes from it.
        require(d.bands == DeviceProtocol.MICRO.caps.bands && d.schemeNo == DeviceProtocol.MICRO.data.schemeNo && d.reportId == WalkPlay.REPORT_ID) {
            "devices/moondrop-echo-a.json (bands ${d.bands}, SchemeNo ${d.schemeNo}, report ${d.reportId}) does not match the Protocol Micro code that drives it"
        }
        // The record gives bands, gain, Q and types; its frequency range is still "unknown" there (PendingRecords), and the preamp policy is the Micro's.
        val caps = DeviceCapabilities(d.name, d.vendorId, d.productId, d.bands, d.gainMinDb, d.gainMaxDb, d.qMin, d.qMax,
            PendingRecords.ECHO_A_FREQ_HZ.start, PendingRecords.ECHO_A_FREQ_HZ.endInclusive, d.types, DeviceProtocol.MICRO.caps.preampWholeDb,
            shelfForm = d.shelfForm, coefficientRateHz = d.coefficientRateHz)
        WalkPlayProfile("echo-a", d.name, d.vendorId, d.productId, DeviceProtocol.MICRO, d.types, d.schemeNo, ECHO_A_HARDWARE_VERIFIED, usbName = d.usbName, capsOverride = caps)
    }
    val all = listOf(micro, max, trn, echoA, space2, tp65)

    fun find(vendorId: Int, productId: Int): WalkPlayProfile? = all.singleOrNull { it.vendorId == vendorId && it.productId == productId }

    /** The profile of a WalkPlay protocol the stable app drives (Micro, Max, TRN). */
    fun of(protocol: DeviceProtocol): WalkPlayProfile = when (protocol) {
        DeviceProtocol.MICRO -> micro
        DeviceProtocol.MAX -> max
        DeviceProtocol.TRN -> trn
        DeviceProtocol.SPACE_2 -> space2
        DeviceProtocol.EPZ_TP65 -> tp65
    }
}

/** What one read of a WalkPlay DAC returned (the app's DacSnapshot without the timing). */
data class WalkPlaySnapshot(val firmware: String, val slot: Int, val bands: List<WalkPlay.DeviceBand>, val preampDb: Int)

/** One settings row as the DAC reported it ([WalkPlaySession.readSettings]); [value] null = the DAC did not answer (or the bytes made no sense). */
data class WalkPlayReading(val id: String, val value: Int?)

/**
 * Reads and writes a WalkPlay DAC through a [NativePort]: the sequence of `DacClient` in :app (read, band writes, preamp,
 * flash commit, read-back), frame for frame, with the transport left to the port. The port
 * carries the payload after the report ID (63 bytes, report 0x4B); every buffer here is the full 64-byte report the protocol
 * code works on. What the app's HidTransport does and the port cannot: the 200 ms reply window and a drain of stale input before
 * a request (the port does it), and the 3 attempts of a request (done here, as `HidTransport.request`).
 *
 * Every report that leaves, request or send, is first held to [WalkPlayGuard] (the frames that are never sent); the factory reset's frames pass it only inside
 * [factoryReset], which needs the ticket of a [WalkPlayResetFlow].
 */
class WalkPlaySession(val profile: WalkPlayProfile, private val port: NativePort) {
    private val io = NativeIo(port)
    private val p: DeviceProtocol get() = profile.protocol

    /** The plan of the last [write], set before its first mutating frame. */
    var lastPlan: DevicePlan.Ready? = null
        private set
    /** The slot byte the last [write] read first (echoed in every band frame). */
    var lastSlot: Int? = null
        private set

    private fun full(payload: ByteArray) = byteArrayOf(WalkPlay.REPORT_ID.toByte()) + payload
    private fun payload(report: ByteArray): ByteArray {
        require(report.size == WalkPlay.REPORT_SIZE && (report[0].toInt() and 255) == WalkPlay.REPORT_ID) { "Not a 64-byte WalkPlay report" }
        return report.copyOfRange(1, report.size)
    }

    /** The guard's context: [WalkPlayGuard.Context.RESET] only while [factoryReset] sends its sequence. */
    private var context = WalkPlayGuard.Context.NORMAL

    /** The value of the setting the last [writeSetting] read before it wrote (null = the DAC did not answer, or no write yet). */
    var lastSettingBefore: Int? = null
        private set

    /** [report] itself, or an IllegalArgumentException (never an IOException: nothing retries it) naming why the frame is on the never-send list. */
    private fun checked(report: ByteArray): ByteArray {
        val p = payload(report)
        WalkPlayGuard.refusal(p, profile, context)?.let { throw IllegalArgumentException("Refused, never sent: $it") }
        return report
    }

    private fun send(report: ByteArray) = io.send(WalkPlay.REPORT_ID, payload(checked(report)))

    /** `HidTransport.request`: send, take the first reply [matches] accepts, at most 3 attempts (the same report again). */
    private fun request(report: ByteArray, what: String, matches: (ByteArray) -> Boolean): ByteArray {
        var last: IOException? = null
        checked(report)
        repeat(ATTEMPTS) {
            try {
                return full(io.request(WalkPlay.REPORT_ID, payload(report)) { matches(full(it)) })
            } catch (e: IOException) {
                if (!io.isReplyTimeout(e)) throw e
                last = e
            }
        }
        throw IOException("the DAC did not answer $what", last)
    }

    /** TRN: a band that did not come back is asked for once more (the app drains the input queue first; the port does that). */
    private fun bandReply(i: Int): ByteArray = try {
        request(WalkPlay.bandRequest(i), "band $i") { p.isBandReply(it, i) }
    } catch (e: IOException) {
        if (p != DeviceProtocol.TRN || e.message?.startsWith("the DAC did not answer") != true) throw e
        port.log("band $i missing: asked once more")
        request(WalkPlay.bandRequest(i), "band $i") { p.isBandReply(it, i) }
    }

    /** DacClient.read: version, bulk slot, every band, preamp; each reply only if the protocol's own predicate accepts it. */
    fun read(): WalkPlaySnapshot = io.guarded {
        val version = p.parseVersion(request(WalkPlay.versionRequest(), "version") { p.isReply(it, WalkPlay.CMD_VERSION) })
        val slot = p.parseSlot(request(WalkPlay.slotRequest(), "slot") { p.isSlotReply(it) }, profile.caps)
        val bands = (0 until profile.bands).map { i -> p.parseBand(bandReply(i), i, profile.caps) }
        val preamp = p.parsePreamp(request(p.preampRequest(), "preamp") { p.isReply(it, WalkPlay.CMD_GLOBAL_GAIN) })
        WalkPlaySnapshot(version, slot, bands, preamp)
    }

    /** What the app would write for [eq]: the protocol's plan (the Micro's planner holds a band to this profile's own [WalkPlayProfile.caps]), narrowed to the types this profile offers. */
    fun plan(eq: Profile): DevicePlan {
        if (p == DeviceProtocol.MICRO) return ProtocolMicro.plan(eq, profile.caps, profile.preampMaxDb)
        val plan = p.plan(eq.bands, eq.preampDb, profile.caps)
        if (plan !is DevicePlan.Ready) return rename(plan as DevicePlan.Rejected)
        // A stock profile offers what its protocol offers now (the TRN's LOW PASS and HIGH PASS follow [io.github.chronosauros.contour.core.ProtocolTrn] and the gate); a widened one only its own types.
        val offered = if (profile.capsOverride == null) profile.types + p.caps.types else profile.types
        val refused = eq.bands.withIndex().filter { (_, b) -> b.enabled && b.type !in offered }
            .map { (i, b) -> "Band ${i + 1}: ${b.type} is not offered for ${profile.name} (${offered.sorted().joinToString()})" }
        return if (refused.isEmpty()) plan else DevicePlan.Rejected(refused)
    }

    private fun rename(r: DevicePlan.Rejected): DevicePlan.Rejected =
        if (profile.name == p.caps.name) r else DevicePlan.Rejected(r.issues.map { it.replace(p.caps.name, profile.name).replace(p.caps.name.removePrefix("CrinEar Protocol "), profile.name) })

    /** The EQ a read holds, as the app imports it; rejected when it cannot be re-encoded exactly. */
    fun importExact(s: WalkPlaySnapshot): DacImport =
        if (p == DeviceProtocol.MICRO) ProtocolMicro.importExact(s.bands, s.preampDb, profile.caps, profile.preampMaxDb) else p.importExact(s.bands, s.preampDb, profile.caps)

    /** Frames [writePlan] puts on the wire (requests and reports), for a progress bar: the read before (the whole state on Max and TRN,
     * version and slot on Micro), every report of the write sequence, the read-back. */
    fun plannedFrames(plan: DevicePlan.Ready): Int =
        (if (p.experimental) profile.bands + 3 else 2) + runCatching { p.writeSequence(plan, 0).size }.getOrDefault(plan.bands.size + 8) + profile.bands + 3

    /**
     * A/B (Protocol Micro only): the supplied bands and TEMP_WRITE, RAM only, no read-back (the DSP is not verified by one). The sequence is
     * encoded first, so a band that does not fit leaves before the first report.
     */
    fun writeTemporary(bands: List<WalkPlay.BandWrite>, slot: Int, explicitHold: Boolean) = io.guarded {
        require(explicitHold) { "HOLD TO SEND required" }
        require(p.supportsAb) { "A/B unavailable on the ${profile.name.removePrefix("CrinEar ")}: RAM-only writes are not verified" }
        val steps = WalkPlay.temporaryBandSequence(bands, slot)
        for (step in steps) {
            send(step.report)
            io.pause(step.delayAfterMs)
        }
    }

    /** The probe's read (review builds): one WalkPlay report [report] (64 bytes with the report ID) and the first reply of command [command], null when the DAC stays silent. */
    fun probeRequest(report: ByteArray, command: Int): ByteArray? = try {
        request(report, "probe 0x%02x".format(command)) { WalkPlay.isReply(it, command) }
    } catch (e: IOException) {
        if (e.message?.startsWith("the DAC did not answer") == true) null else throw e
    }

    /** DacClient.writeProfile: [plan] the profile, then [writePlan]. Throws with the issues when it does not fit. */
    fun write(eq: Profile, explicitHold: Boolean): WriteOutcome<WalkPlaySnapshot> {
        val plan = when (val r = plan(eq)) {
            is DevicePlan.Rejected -> throw IOException(r.issues.joinToString("; "))
            is DevicePlan.Ready -> r
        }
        return writePlan(plan, explicitHold)
    }

    /**
     * DacClient.writePlan + write: the slot first (Micro: version and slot; Max, TRN: the whole state), the sequence, the read-back, the comparison. [slotByte] null = the slot byte
     * the DAC reads now goes into every band frame (an ordinary write); given = that byte does (the restore after a reset writes the one of its backup). The reads before the write
     * are the same either way.
     */
    fun writePlan(plan: DevicePlan.Ready, explicitHold: Boolean, slotByte: Int? = null): WriteOutcome<WalkPlaySnapshot> = io.guarded {
        require(explicitHold) { "HOLD TO SEND required" }
        val steps0 = runCatching { p.writeSequence(plan, 0) }.getOrNull() // preflight: nothing leaves if a band does not encode
        requireNotNull(steps0) { "The plan does not encode" }
        var current: List<WalkPlay.Registers>? = null
        val found = if (p.experimental) {
            require(plan.bands.size == p.caps.bands && plan.bands.indices.all { plan.bands[it].index == it }) { "Plan does not cover every ${profile.name} slot" }
            val before = read()
            if (p == DeviceProtocol.EPZ_TP65) {
                // No preamp frame leaves for a TP65, so the DAC keeps the preamp it holds: a plan that asks for another one is refused here, before the first write frame.
                if (plan.preampDb != before.preampDb) throw IOException("the ${profile.name} holds a preamp of ${before.preampDb} dB and Contour does not write it yet; this EQ asks for ${plan.preampDb} dB: nothing was sent")
                current = before.bands.map { it.registers }
            }
            before.slot
        } else {
            request(WalkPlay.versionRequest(), "version") { WalkPlay.isReply(it, WalkPlay.CMD_VERSION) }
            WalkPlay.parseSlot(request(WalkPlay.slotRequest(), "slot") { WalkPlay.isReply(it, WalkPlay.CMD_PEQ) })
        }
        val slot = slotByte ?: found
        lastPlan = plan
        lastSlot = slot
        for (step in p.writeSequence(plan, slot, current)) {
            send(step.report)
            io.pause(step.delayAfterMs)
        }
        val back = read()
        val problems = mismatches(plan, back, slot)
        WriteOutcome(problems.isEmpty(), false, problems.joinToString("; ").ifEmpty { null }, back)
    }

    /** DacClient.write's verdict: every register of every band, the preamp and (Max, TRN) the bulk slot. */
    fun mismatches(plan: DevicePlan.Ready, back: WalkPlaySnapshot, slot: Int): List<String> {
        val out = ArrayList<String>()
        plan.bands.forEachIndexed { i, w ->
            val want = w.registers()
            val have = back.bands[i].registers
            if (want != have) out += "band ${i + 1}: wrote $want, read $have"
        }
        if (back.preampDb != plan.preampDb) out += "preamp: wrote ${plan.preampDb}, read ${back.preampDb}"
        if (p.experimental && back.slot != slot) out += "slot: wrote $slot, read ${back.slot}"
        return out
    }

    // ---- settings (walkplay-plan.md item 7 and 8; the app writes the rows [WalkPlaySettings.offered] gives it: the TRN's four proven ones, the rest in review builds only) ---------------------------------------------------

    /** One row's value, or null when the DAC stays silent (the Micro does not answer 0x11/0x16/0x19/0x1B/0x1D). The reads are the plan's, one report (the balance: two). */
    private fun readValue(s: DacSetting): Int? = try {
        if (s.id == WalkPlaySettings.BALANCE) {
            val asks = WalkPlaySettings.readRequests(s)
            val c0 = request(asks[0], "${s.label} channel 0") { WalkPlaySettings.isReply(s, it, 0) }
            val c1 = request(asks[1], "${s.label} channel 1") { WalkPlaySettings.isReply(s, it, 1) }
            WalkPlaySettings.balanceValue(c0, c1)
        } else {
            WalkPlaySettings.decode(s, request(WalkPlaySettings.readRequests(s).single(), s.label) { WalkPlaySettings.isReply(s, it) })
        }
    } catch (e: IOException) {
        if (e.message?.startsWith("the DAC did not answer") == true) null else throw e
    }

    /** The values of [rows] (default: this DAC's rows, [WalkPlaySettings.of]), each asked the way the vendor's tool asks; a silent DAC is a null value, not an error. */
    fun readSettings(rows: List<DacSetting> = WalkPlaySettings.of(profile)): List<WalkPlayReading> = io.guarded {
        rows.filter { it.read != null }.map { WalkPlayReading(it.id, readValue(it)) }
    }

    /**
     * Writes one setting alone, as the bench's `setting-plan` asks: the value is checked before any frame, the row is read, its write goes out, [save] adds the vendor's
     * `01 01 00` (the vendor adds it after a mic write; whether the other rows need it is unknown), the row is read again and must show [value]. The value the DAC held is
     * [lastSettingBefore] (the restore). A read-only row (the balance) and a row this DAC does not have are refused before the first frame.
     */
    fun writeSetting(id: String, value: Int, explicitHold: Boolean, save: Boolean = false): WriteOutcome<Int> = io.guarded {
        require(explicitHold) { "HOLD TO SEND required" }
        val s = WalkPlaySettings.row(profile, id)
        val frames = WalkPlaySettings.writeReports(s, value)
        lastSettingBefore = readValue(s)
        fun write() {
            for (f in frames) { send(f); io.pause(SETTING_GAP_MS) }
            if (id == WalkPlaySettings.VOLUME) {
                // the Space II's volume is always followed by the vendor's flash save `01 04` (not the mic's `01 01 00`); the guard lets it through in this context only
                val before = context
                context = WalkPlayGuard.Context.VOLUME_SAVE
                try { send(WalkPlaySettings.saveVolumeReport()) } finally { context = before }
                io.pause(SAVE_GAP_MS)
            } else if (save) {
                // on a Max the save is allowed through the guard as the flash save after a mic write and nowhere else
                val before = context
                if (id == WalkPlaySettings.MIC) context = WalkPlayGuard.Context.MIC_SAVE
                try { send(WalkPlaySettings.saveReport()) } finally { context = before }
                io.pause(SAVE_GAP_MS)
            }
        }
        write()
        var back = readValue(s)
        if (back != value && p == DeviceProtocol.SPACE_2) {
            // The Space II drops an output frame now and then (a band frame in 3 of 16 A/B switches on a phone, 16.11.2026), so a SET that did not land is sent once
            // more, with its save, and the row read again; what it reads then is the verdict. Only the Space II: the other DACs' proven frames stay as they are.
            port.log("setting ${s.id}: the DAC reads ${back ?: "nothing"}, wrote $value: sent once more")
            write()
            back = readValue(s)
        }
        WriteOutcome(back == value, false, if (back == value) null else "the DAC reads ${back ?: "nothing"}, wrote $value", back)
    }

    // ---- factory reset (Protocol Micro, Echo-A, TRN): backup, the sequence, the read after it, the restore ------------------------------------------------------

    /**
     * What the DAC holds right now, as a [WalkPlayResetBackup]: every band, the preamp, the slot byte and every readable setting (a silent one is listed in
     * [WalkPlayResetBackup.unreadSettings]). Used before the reset (the backup) and after it (what the reset left). Nothing is written.
     */
    fun captureForReset(explicitUserAction: Boolean, takenAtMs: Long): WalkPlayResetBackup = io.guarded {
        require(explicitUserAction) { "A backup before a reset requires an explicit user action" }
        require(WalkPlayReset.offered(profile)) { "${profile.name} has no factory reset in Contour" }
        val state = read()
        val readings = readSettings()
        WalkPlayResetBackup(profile.id, takenAtMs, state.firmware, state.slot, state.preampDb, state.bands.map { it.registers },
            readings.mapNotNull { r -> r.value?.let { r.id to it } }.toMap(), readings.filter { it.value == null }.map { it.id })
    }

    /**
     * Sends the whole reset sequence of [WalkPlayReset], built from [ticket] only, with the guard in [WalkPlayGuard.Context.RESET]. A session that ends after the first frame
     * went out is normal (the DAC may restart): it comes back in [WalkPlayResetSent], never as an exception, and nothing counts as reset until a read after it says so.
     */
    fun factoryReset(ticket: WalkPlayResetTicket, log: (String) -> Unit = {}): WalkPlayResetSent {
        port.guard()
        val steps = WalkPlayReset.sequence(profile, ticket) // refuses another DAC and a ticket that was spent
        context = WalkPlayGuard.Context.RESET
        var sent = 0
        try {
            for (step in steps) {
                log("reset TX ${hexOf(payload(step.report))}")
                send(step.report)
                sent++
                io.pause(step.delayAfterMs)
            }
            io.pause(WalkPlayReset.SETTLE_MS) // the DAC may restart: nothing is asked of it before this
            return WalkPlayResetSent(sent, null)
        } catch (e: IOException) {
            if (sent == 0) throw e
            val note = "the session ended after step $sent of ${steps.size}: ${e.message}"
            log("reset: $note")
            return WalkPlayResetSent(sent, note)
        } finally {
            context = WalkPlayGuard.Context.NORMAL
        }
    }

    private fun hexOf(b: ByteArray) = b.copyOf(b.indexOfLast { it != 0.toByte() }.coerceAtLeast(2) + 1).joinToString(" ") { "%02x".format(it.toInt() and 255) }

    /**
     * Writes [backup] back after a reset: all bands and the preamp through the ordinary write of this DAC ([writePlan]: its own sequence, the read-back and the comparison),
     * then every saved setting of [settingRows] (default: all rows; the app passes [WalkPlaySettings.offered], where the mic is read only) that has a write and differs from what the DAC reads now (the read-only ones are a line, never written). A step that
     * fails is a line, never a silent skip; a lost session stops the restore. [WalkPlayRestoreResult.ok] is true only when everything that had to be written read back as saved.
     * On the TRN the backup holds bands 8 and 9 too, so they are written back whatever the reset did to them.
     */
    fun restoreAfterReset(backup: WalkPlayResetBackup, settingRows: List<DacSetting> = WalkPlaySettings.of(profile), log: (String) -> Unit = {}): WalkPlayRestoreResult = io.guarded {
        require(WalkPlayReset.offered(profile)) { "${profile.name} has no factory reset in Contour" }
        backup.problems(profile).let { require(it.isEmpty()) { "Backup incomplete: " + it.joinToString("; ") } }
        val lines = ArrayList<String>()
        var ok = true
        var lost = false
        try {
            // the slot (tag) byte of every band row is the backup's, not the one the DAC reads after the reset (the reset may have changed it)
            val r = writePlan(DevicePlan.Ready(backup.writes(profile.caps.coefficientRateHz), backup.preampDb), explicitHold = true, slotByte = backup.slot)
            log("reset restore: bands and preamp ${if (r.verified) "VERIFIED" else "NOT VERIFIED"}${r.reason?.let { ": $it" }.orEmpty()}")
            lines += if (r.verified) "Bands and preamp: restored" else "Bands and preamp: NOT restored: ${r.reason}"
            if (!r.verified) ok = false
        } catch (e: IOException) {
            lines += "Bands and preamp: NOT restored: ${e.message}"; ok = false; lost = true
        } catch (e: IllegalArgumentException) {
            lines += "Bands and preamp: NOT restored: ${e.message}"; ok = false
        }
        val rows = settingRows.associateBy { it.id }
        for ((id, value) in backup.settings.entries.sortedBy { it.key }) {
            val row = rows[id] ?: continue
            if (lost) { lines += "${row.label}: not restored (the DAC session ended)"; ok = false; continue }
            if (row.writeCommand == null) { lines += "${row.label}: read only, not written back"; continue }
            try {
                val now = readValue(row)
                if (now == value) { lines += "${row.label}: unchanged"; continue }
                val back = writeSetting(id, value, explicitHold = true)
                lines += if (back.verified) "${row.label}: restored" else "${row.label}: NOT restored: ${back.reason}"
                if (!back.verified) ok = false
            } catch (e: IOException) {
                lines += "${row.label}: NOT restored: ${e.message}"; ok = false; lost = true
            } catch (e: IllegalArgumentException) {
                lines += "${row.label}: NOT restored: ${e.message}"; ok = false
            }
        }
        WalkPlayRestoreResult(lines, ok)
    }

    private companion object {
        /** The pause after a settings write before the next frame or the read, and after the flash save: Contour's, the vendor's code has none. */
        const val SETTING_GAP_MS = 100L
        const val SAVE_GAP_MS = 200L
        /** HidTransport.RETRIES + 1. */
        const val ATTEMPTS = 3
    }
}
