package io.github.chronosauros.contour.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import io.github.chronosauros.contour.core.BuildChannel
import io.github.chronosauros.contour.core.DacImport
import io.github.chronosauros.contour.core.DevicePlan
import io.github.chronosauros.contour.core.Proven
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.DeviceProtocol
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.WalkPlay
import io.github.chronosauros.contour.R
import io.github.chronosauros.contour.ui.UiText
import io.github.chronosauros.contour.ui.coreTexts
import io.github.chronosauros.contour.core.native.FamilyDevices
import io.github.chronosauros.contour.core.native.FamilyState
import io.github.chronosauros.contour.core.native.FiioMelodyExchange
import io.github.chronosauros.contour.core.native.FiioReset
import io.github.chronosauros.contour.core.native.FiioResetBackup
import io.github.chronosauros.contour.core.native.FiioResetSent
import io.github.chronosauros.contour.core.native.FiioResetTicket
import io.github.chronosauros.contour.core.native.FiioRestoreResult
import io.github.chronosauros.contour.core.native.KtMicroCatalog
import io.github.chronosauros.contour.core.native.KtMicroCodec
import io.github.chronosauros.contour.core.native.KtMicroSession
import io.github.chronosauros.contour.core.native.MoondropCatalog
import io.github.chronosauros.contour.core.native.MoondropCodec
import io.github.chronosauros.contour.core.native.MoondropGen1Catalog
import io.github.chronosauros.contour.core.native.MoondropGen1Session
import io.github.chronosauros.contour.core.native.MoondropSession
import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.core.native.NativePort
import io.github.chronosauros.contour.core.native.NativeSession
import io.github.chronosauros.contour.core.native.NativeState
import io.github.chronosauros.contour.core.native.ProbePlan
import io.github.chronosauros.contour.core.native.ProbeReport
import io.github.chronosauros.contour.core.native.SettingReading
import io.github.chronosauros.contour.core.native.SettingWriteOutcome
import io.github.chronosauros.contour.core.native.WalkPlayProfile
import io.github.chronosauros.contour.core.native.WalkPlayProfiles
import io.github.chronosauros.contour.core.native.WalkPlayReset
import io.github.chronosauros.contour.core.native.WalkPlayResetBackup
import io.github.chronosauros.contour.core.native.WalkPlayResetSent
import io.github.chronosauros.contour.core.native.WalkPlayResetTicket
import io.github.chronosauros.contour.core.native.WalkPlayRestoreResult
import io.github.chronosauros.contour.core.native.WalkPlaySession
import io.github.chronosauros.contour.core.native.WalkPlaySettings
import io.github.chronosauros.contour.core.native.WalkPlaySnapshot
import io.github.chronosauros.contour.core.native.WriteTestResult
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What one lock-step read returned. WalkPlay DACs: [bands] + [preampDb]; the FiiO KA15: [nativeState]; the 1.6 families: [familyState]. */
data class DacSnapshot(
    val firmware: String,
    val slot: Int,
    val bands: List<WalkPlay.DeviceBand>,
    val preampDb: Int?,
    val readMs: Long,
    val protocol: DeviceProtocol? = DeviceProtocol.MICRO,
    val nativeState: NativeState? = null,
    val target: DeviceTarget = DeviceTarget.of(requireNotNull(protocol)),
    val familyState: FamilyState? = null,
    /** The MAX VOLUME Contour sends this DAC with ([io.github.chronosauros.contour.core.ProtocolMicro.trimmedRegister]); 0 on every DAC but the Protocol Micro. ON DAC and FROM DAC take it out of the preamp read. */
    val trimDb: Int = 0,
) {
    val shownPreamp: Double? get() = if (familyState != null) familyState.preampDb else if (nativeState != null) nativeState.preamp else preampDb?.toDouble()
    fun matches(profile: Profile): Boolean = familyState?.matches(profile) ?: nativeState?.matches(profile)
        ?: requireNotNull(protocol).matches(profile, bands.map { it.registers }, requireNotNull(preampDb), trimDb)
    /** What the DAC holds as an EQ Contour can open; what the screens say about a state it cannot open exactly is [refusalText]. */
    fun importExact(): DacImport = familyState?.let { s -> runCatching<DacImport> { DacImport.Ready(s.importExact()) }
        .getOrElse { DacImport.Rejected(listOf(it.message ?: "The DAC state cannot be imported exactly")) } }
        ?: nativeState?.let { s -> runCatching<DacImport> { DacImport.Ready(s.importExact()) }
        .getOrElse { DacImport.Rejected(listOf(it.message ?: "Native state cannot be imported exactly")) } }
        ?: requireNotNull(protocol).importExact(bands, requireNotNull(preampDb), trimDb = trimDb)
    /** A state Contour cannot open exactly names the reason only in a review build; a release says it in one plain sentence. */
    fun refusalText(r: DacImport.Rejected): UiText =
        if (Proven.channel != BuildChannel.PERF) UiText.res(R.string.dac_import_not_exact) else coreTexts(r.issues)
}

/**
 * A write plus its read-back: verified = every register (freq, Q, gain, type x every device slot + preamp) as
 * written; on the Protocol Max and the TRN the bulk slot byte as well; on the KA15 the complete native USER slot state.
 * A verified KA15 write can still carry a note in [mismatches] (the slot name did not read back).
 * The Kiwi Ears Allegro Mini ends at SAVE and drops off the USB bus: its result is [pending] (not verified, no [readBack]) until the controller
 * reads the DAC again after it is back and [PendingSave.verify] has compared that read. The Dawn Pro 2 reports in [previousIndex] the EQ index
 * it was on before the write switched it to Contour's bank (null = it was already there).
 */
data class WriteResult(
    val verified: Boolean,
    val mismatches: List<String>,
    val writeMs: Long,
    val readBackMs: Long,
    val readBack: DacSnapshot?,
    val pending: PendingSave? = null,
    val previousIndex: Int? = null,
)

/** What proves a write that ended at SAVE: [verify] gets the first read of the DAC after it is back and returns what differs from what was written (empty = verified). */
class PendingSave(
    /** Run once, with the DAC back on the bus, before the read that proves the write (the DUSK-SP: the RAM select of the Custom EQ, since the cable restarts onto preset 1); null = nothing. */
    val afterReturn: (suspend (DacHandle) -> Unit)? = null,
    val verify: (DacSnapshot) -> List<String>,
)

/**
 * Operations on the supported DACs (Protocol Micro, Protocol Max, TRN Black Pearl, FiiO KA15). Each one opens the HID interface, does its reports on the single
 * USB thread and releases the interface again. Writes happen only when the user asks (debug buttons).
 * [guardFor] (KA15) throws once the DAC session the operation started on has ended; [audioKeepAlive] keeps the
 * KA15's USB audio stream running for the whole HID session (it answers HID only while audio streams).
 */
class DacClient(private val manager: UsbManager, private val guardFor: (DacHandle) -> (() -> Unit) = { {} },
    private val audioKeepAlive: (UsbDevice) -> AutoCloseable? = { null },
    /** The MAX VOLUME a DAC is sent with now ([DeviceTarget.takesVolumeTrim]; 0 for every other DAC): what a send adds under the profile's preamp and what a read takes back out. */
    private val trimFor: (DeviceTarget) -> Int = { 0 }) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "contour-usb") }
    private val usb = executor.asCoroutineDispatcher()

    fun shutdown() = executor.shutdown()

    /** [postGuard]: false for a write that ends at a SAVE the DAC leaves the bus on (the JA11): the session guard after the block would throw "DAC SESSION CHANGED" for the detach that is expected there. */
    private suspend fun <T> withDevice(device: DacHandle, postGuard: Boolean = true, block: (HidIo) -> T): T = hidLock.withLock {
        // Review builds only (`--es simulate`): a simulated DAC has no USB; nothing below it runs for it.
        device.simulated?.let { open -> return@withLock withContext(usb) { open(guardFor(device)).use(block) } }
        val dev = requireNotNull(device.usb)
        val fiio = target(device).fiio ?: return@withLock withContext(usb) { HidTransport.open(manager, dev).use(block) }
        val guard = guardFor(device)
        withContext(usb) {
            guard()
            val stream = if (fiio.needsAudioStream) audioKeepAlive(dev) else null
            try { HidTransport.open(manager, dev, guard).use(block).also { if (postGuard) guard() } } finally { stream?.close() }
        }
    }

    private companion object {
        /** One HID session at a time in the whole app. A recreated Activity (the relaunch after an update, a theme change) has a
         * second DacClient for a moment, and a second session claiming the interface breaks the first ("Short native output report"). */
        val hidLock = Mutex()
    }

    private fun target(device: DacHandle): DeviceTarget = device.target

    private fun protocol(device: DacHandle): DeviceProtocol =
        target(device).walkplay ?: throw IOException("WalkPlay-only operation unavailable on ${target(device).caps.name}")

    private fun nativeSession(device: DacHandle, t: HidIo, target: DeviceTarget) = NativeSession(target, object : NativePort {
        /** SnowSky Melody and Tiny A: their own bounded request boundary with input cleanup (see FiioMelodyExchange). */
        private val melody = if (device.simulated == null && FiioMelodyExchange.appliesTo(target.fiio)) FiioMelodyExchange(
            t.nativePayloadSize(requireNotNull(target.fiio).reportId, NativeHidReports.Kind.INPUT),
            { t.checkSession() }, { SystemClock.elapsedRealtime() },
            { id, payload -> t.sendNative(id, NativeHidReports.Kind.OUTPUT, payload) }, { t.receive(it) },
            FiioMelodyExchange.name(requireNotNull(target.fiio))) else null
        override val deviceKey = device.key
        override fun guard() = t.checkSession()
        override fun pause(ms: Long) { t.checkSession(); Thread.sleep(ms); t.checkSession() }
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) = t.sendNative(id, kind, payload)
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean) =
            melody?.request(id, payload, matches) ?: t.requestNative(id, payload, matches)
    })

    private fun nativeSnapshot(target: DeviceTarget, state: NativeState, ms: Long) =
        DacSnapshot("native", state.slot, emptyList(), null, ms, protocol = null, nativeState = state, target = target)

    suspend fun readDevice(device: DacHandle): DacSnapshot = withDevice(device) { t ->
        val target = target(device)
        if (target.walkplay != null) readWalkPlay(device, t, target) else if (target.fiio == null) readFamily(device, t, target) else {
            val start = SystemClock.elapsedRealtime()
            val state = nativeSession(device, t, target).read(); t.checkSession()
            val ms = SystemClock.elapsedRealtime() - start
            UsbLog.line("read: ${target.caps.name}, slot ${state.slot}, preamp ${state.preamp} dB, $ms ms")
            nativeSnapshot(target, state, ms)
        }
    }

    /**
     * Reads every readable setting of the DAC's DAC Settings list, read-only. One reader per device family: the FiiO/SnowSky frame session
     * today; a WalkPlay reader (report 0x4B) and a standard USB Audio Class reader would join here, and the caller never needs to know which.
     * Every TX and RX goes to the USB log with the setting id.
     */
    suspend fun readSettings(device: DacHandle): List<SettingReading> = withDevice(device) { t ->
        val target = target(device)
        require(target.settings.isNotEmpty()) { "${target.caps.name} has no settings list" }
        val start = SystemClock.elapsedRealtime()
        // WalkPlay (report 0x4B): the rows the page offers, each asked the way the vendor's tool asks; the Dawn Pro 2 and the Allegro Mini fill their rows from the connect read ([FamilyState.readings]).
        target.walkplayProfile?.let { profile ->
            val r = WalkPlaySession(profile, WalkPlayPort(t, device)).readSettings(target.settings).map { SettingReading(it.id, it.value, null) }
            t.checkSession()
            UsbLog.line("settings: ${target.caps.name}, ${r.count { it.value != null }}/${r.size} answered, ${SystemClock.elapsedRealtime() - start} ms")
            return@withDevice r
        }
        if (target.fiio == null) throw IOException("No settings reader for ${target.caps.name} yet")
        val r = nativeSession(device, t, target).readSettings(UsbLog::line); t.checkSession()
        UsbLog.line("settings: ${target.caps.name}, ${r.count { it.value != null }}/${r.size} answered, ${SystemClock.elapsedRealtime() - start} ms")
        r
    }

    /**
     * Sets one setting of a FiiO/SnowSky DAC and reads it back ([NativeSession.writeSetting]): the SET, its echo, the model's gap, the same command
     * read. The caller (the controller) lets it through only for a setting with writeVerified. KA15: inside the audio keep-alive of [withDevice].
     */
    suspend fun writeSetting(device: DacHandle, id: String, value: Int): SettingWriteOutcome = withDevice(device) { t ->
        val target = target(device)
        if (target.fiio == null || target.settings.isEmpty()) throw IOException("No settings writer for ${target.caps.name}")
        nativeSession(device, t, target).writeSetting(id, value, UsbLog::line).also { t.checkSession() }
    }

    /**
     * Sets one setting of a WalkPlay DAC (TRN: DAC filter, work mode, mic; Micro and Echo-A: mic) and reads it back ([WalkPlaySession.writeSetting]). The caller (the controller)
     * lets it through only for a row the gate opened ([DacSetting.writeEnabled]); it is checked here again, so a row that is read only for the app (the gain mode, the balance)
     * or that no release has proven cannot reach a frame. The vendor's flash save (`01 01 00`) follows a mic write only, as in its tool, on a Max too (without it the Max loses the mic value over a replug; the guard lets `01 01 00` through there only as that save). A Space II volume write gets its own save (`01 04`) inside the session.
     */
    suspend fun writeWalkPlaySetting(device: DacHandle, id: String, value: Int): SettingWriteOutcome = withDevice(device) { t ->
        val target = target(device)
        val profile = target.walkplayProfile ?: throw IOException("No WalkPlay settings writer for ${target.caps.name}")
        val row = target.settings.firstOrNull { it.id == id }?.takeIf { it.writeEnabled } ?: throw IOException("${target.caps.name}: \"$id\" is not written by the app")
        UsbLog.line("write: ${target.caps.name} ${row.label} -> $value")
        val r = WalkPlaySession(profile, WalkPlayPort(t, device)).writeSetting(id, value, explicitHold = true, save = id == WalkPlaySettings.MIC)
        t.checkSession()
        UsbLog.line("settings write: ${if (r.verified) "VERIFIED" else r.reason}")
        when {
            r.verified -> SettingWriteOutcome.Verified
            r.readback != null -> SettingWriteOutcome.Mismatch(r.readback!!)
            else -> SettingWriteOutcome.NoReply
        }
    }

    /**
     * Allegro Mini: the EQ switch (register 0x24) and SAVE, the bands untouched ([KtMicroSession.writeSwitch]). The DAC leaves the USB bus after SAVE, so the
     * result is pending: the controller verifies it with the first read after the DAC is back. Nothing else of the Allegro is ever written from here.
     */
    suspend fun writeEqSwitch(device: DacHandle, on: Boolean): WriteResult = withDevice(device) { t ->
        val target = target(device)
        val model = target.ktMicro ?: throw IOException("No EQ switch writer for ${target.caps.name}")
        val start = SystemClock.elapsedRealtime()
        val session = KtMicroSession(model, WalkPlayPort(t, device))
        UsbLog.line("write: ${target.caps.name} EQ switch -> ${if (on) "ON" else "OFF"}")
        session.writeSwitch(on, explicitHold = true)
        savePending(session, start)
    }

    /**
     * Allegro Mini: the Global Gain (register 0x3B, step 1-15, never 0) and SAVE, the bands and the EQ switch untouched ([KtMicroSession.writeGlobalGain]). The DAC leaves
     * the USB bus after SAVE, so the result is pending: the first read after it is back (0x3B is part of that read) must show the written step. The row that calls this
     * stays off ([io.github.chronosauros.contour.core.native.DacSetting.writeVerified] false) until the PC bench has proven the frame on the unit.
     */
    suspend fun writeAllegroGlobalGain(device: DacHandle, step: Int): WriteResult = withDevice(device) { t ->
        val target = target(device)
        val model = target.ktMicro ?: throw IOException("No Global Gain writer for ${target.caps.name}")
        val start = SystemClock.elapsedRealtime()
        val session = KtMicroSession(model, WalkPlayPort(t, device))
        UsbLog.line("write: ${target.caps.name} Global Gain -> step $step (${KtMicroCodec.globalGainDb(step)} dB)")
        session.writeGlobalGain(step, explicitHold = true)
        savePending(session, start)
    }

    /**
     * Dawn Pro 2: one gain, Pre Gain (0x23) or Global Gain (0x03), in dB on the 0.1 dB grid ([MoondropSession.writeGain]): the one gain frame, never a band, never `01 04 00`,
     * read back in the same session with the same index and bands. Not saved to flash ([MoondropSession.writeGain] `persist` false, as the manufacturer's own apply flow
     * passes it); whether a gain survives a power cycle is for the bench to prove. The row that calls this stays off until the PC bench has proven the frame.
     */
    suspend fun writeDawnGain(device: DacHandle, register: MoondropCodec.GainRegister, db: Double): WriteResult = withDevice(device) { t ->
        val target = target(device)
        val model = target.moondrop ?: throw IOException("No gain writer for ${target.caps.name}")
        val start = SystemClock.elapsedRealtime()
        val session = MoondropSession(model, WalkPlayPort(t, device))
        UsbLog.line("write: ${target.caps.name} ${register.what} -> $db dB")
        val r = session.writeGain(register, db, explicitHold = true, persist = false)
        t.checkSession()
        val back = requireNotNull(r.readback)
        val ms = SystemClock.elapsedRealtime() - start
        UsbLog.line("write: ${if (r.verified) "VERIFIED" else "MISMATCH"}${r.reason?.let { ", $it" }.orEmpty()}, $ms ms")
        WriteResult(r.verified, listOfNotNull(r.reason), ms, 0, familySnapshot(target, FamilyState.Dawn(model, back), 0))
    }

    // ---- factory reset (SnowSky Melody and Tiny A, FiiO KA15): the three calls of the controller's flow ---------------------------------------------------------------

    /** Every USER slot (name and registers) and every readable setting, read only ([NativeSession.captureForReset]): the backup before the reset, and the read after it. */
    suspend fun captureForReset(device: DacHandle, takenAtMs: Long): FiioResetBackup = withDevice(device) { t ->
        val target = target(device)
        if (!FiioReset.inApp(target.fiio)) throw IOException("${target.caps.name} has no factory reset")
        UsbLog.line("reset: reading ${target.caps.name} (USER slots, names, settings)")
        nativeSession(device, t, target).captureForReset(true, takenAtMs, UsbLog::line).also { t.checkSession() }
    }

    /**
     * Sends the reset frame (`aa 0a 00 SEQ 21 01 00 CRC ee`, from [ticket] only) and nothing else. The DAC may restart: no answer, a late answer, or a session
     * that ends after the frame went out are [FiioResetSent] notes, never an error; a failure before the frame went out is thrown.
     */
    suspend fun factoryReset(device: DacHandle, ticket: FiioResetTicket): FiioResetSent {
        var sent: FiioResetSent? = null
        try {
            withDevice(device) { t ->
                val target = target(device)
                if (!FiioReset.inApp(target.fiio)) throw IOException("${target.caps.name} has no factory reset")
                UsbLog.line("reset: sending the factory reset to ${target.caps.name}")
                sent = nativeSession(device, t, target).factoryReset(ticket, UsbLog::line)
            }
        } catch (e: IOException) {
            // The session guard after the block throws when the DAC re-enumerated: the frame has gone out, which [sent] says.
            UsbLog.line("reset: ${e.message}")
            return sent ?: throw e
        }
        return requireNotNull(sent)
    }

    /** Writes [backup] back ([NativeSession.restoreAfterReset]): the slots, names, settings and the slot the DAC was on, one line each. */
    suspend fun restoreAfterReset(device: DacHandle, backup: FiioResetBackup): FiioRestoreResult = withDevice(device) { t ->
        val target = target(device)
        if (!FiioReset.inApp(target.fiio)) throw IOException("${target.caps.name} has no factory reset")
        UsbLog.line("reset: writing the saved copy back to ${target.caps.name}")
        nativeSession(device, t, target).restoreAfterReset(backup, UsbLog::line).also { t.checkSession() }
    }

    // ---- factory reset (WalkPlay: Protocol Micro, Echo-A, TRN): the same three calls ---------------------------------------------------------------------------

    private fun resettable(target: DeviceTarget): WalkPlayProfile =
        target.walkplayProfile?.takeIf { WalkPlayReset.enabled(it) } ?: throw IOException("${target.caps.name} has no factory reset")

    /** Every band, the preamp, the slot byte and every readable setting, read only ([WalkPlaySession.captureForReset]): the backup before the reset, and the read after it. */
    suspend fun captureWalkPlayForReset(device: DacHandle, takenAtMs: Long): WalkPlayResetBackup = withDevice(device) { t ->
        val target = target(device)
        val profile = resettable(target)
        UsbLog.line("reset: reading ${target.caps.name} (bands, preamp, settings)")
        WalkPlaySession(profile, WalkPlayPort(t, device)).captureForReset(true, takenAtMs).also { t.checkSession() }
    }

    /**
     * Sends the reset sequence ([WalkPlayReset.sequence], from [ticket] only). The DAC may restart: a session that ends after the first frame is a [WalkPlayResetSent] note,
     * never an error; a failure before the first frame is thrown.
     */
    suspend fun walkPlayFactoryReset(device: DacHandle, ticket: WalkPlayResetTicket): WalkPlayResetSent {
        var sent: WalkPlayResetSent? = null
        try {
            withDevice(device) { t ->
                val target = target(device)
                val profile = resettable(target)
                UsbLog.line("reset: sending the factory reset to ${target.caps.name}")
                sent = WalkPlaySession(profile, WalkPlayPort(t, device)).factoryReset(ticket, UsbLog::line)
            }
        } catch (e: IOException) {
            UsbLog.line("reset: ${e.message}")
            return sent ?: throw e
        }
        return requireNotNull(sent)
    }

    /** Writes [backup] back ([WalkPlaySession.restoreAfterReset]): bands and preamp through the ordinary write, then the settings the app writes (the gain mode and the balance are read only here). */
    suspend fun restoreWalkPlayAfterReset(device: DacHandle, backup: WalkPlayResetBackup): WalkPlayRestoreResult = withDevice(device) { t ->
        val target = target(device)
        val profile = resettable(target)
        UsbLog.line("reset: writing the saved copy back to ${target.caps.name}")
        WalkPlaySession(profile, WalkPlayPort(t, device)).restoreAfterReset(backup, target.settings, UsbLog::line).also { t.checkSession() }
    }

    /**
     * Dawn Pro 2: back to the EQ index [index] the DAC was on before Contour's send switched it to Contour's bank. An explicit user action; the index is saved
     * (`01 01 00`) so the DAC is on it after a power cycle too. Returns the index one read confirms; the caller compares it with [index].
     */
    suspend fun returnToIndex(device: DacHandle, index: Int): Int = withDevice(device) { t ->
        val target = target(device)
        val model = target.moondrop ?: throw IOException("No EQ index on ${target.caps.name}")
        UsbLog.line("switch: ${target.caps.name} back to EQ index $index")
        MoondropSession(model, WalkPlayPort(t, device)).selectIndex(index, explicitUserAction = true).also { t.checkSession(); UsbLog.line("switch: EQ index $it confirmed") }
    }

    /** The write self-test (review builds, [NativeSession.writeTest]): every TX and RX and the result line go to [sink]. */
    suspend fun writeTest(device: DacHandle, id: String, to: Int?, sink: (String) -> Unit): WriteTestResult = withDevice(device) { t ->
        val target = target(device)
        if (target.fiio == null || target.settings.isEmpty()) throw IOException("No settings writer for ${target.caps.name}")
        nativeSession(device, t, target).writeTest(id, to, sink).also { t.checkSession() }
    }

    /**
     * The DAC probe (review builds): every read Contour is allowed to make on the attached DAC, logged through [sink] by [ProbeReport]. Layer 1
     * (FiiO frames) or 2 (WalkPlay reports) through the usual HID session and the app-wide lock; layer 3 (USB Audio Class descriptors and GET
     * requests) on a connection that claims nothing. If another app holds the HID interface the probe says `busy` and skips layers 1 and 2: it
     * never forces. Writes nothing; a no-reply or a refused request is a result, not a failure.
     */
    suspend fun probe(device: DacHandle, label: String, sink: (String) -> Unit) {
        val target = target(device)
        val usbDevice = requireNotNull(device.usb) { "A simulated DAC has no USB descriptors to probe" }
        val report = ProbeReport(label, target.caps.name, sink) { SystemClock.elapsedRealtime() }
        report.note("info", "vid %04x pid %04x ${if (target.fiio != null) "FiiO" else if (target.walkplay != null) "WalkPlay" else "1.6 family"}".format(usbDevice.vendorId, usbDevice.productId))
        val fiio = target.fiio
        val gap = fiio?.let { ProbePlan.fiioGapMs(it) }
        if (fiio != null && gap == null) report.note("fiio", "skipped: FiiO Control drives ${fiio.productName} with a different protocol (24-bit command IDs)")
        else if (fiio == null && target.walkplay == null) report.note("hid", "skipped: ${target.caps.name} is read by its own session, the probe asks it nothing")
        else try {
            withDevice(device) { t ->
                if (fiio != null) nativeSession(device, t, target).probeFiio(report, requireNotNull(gap)).also { t.checkSession() }
                else probeWalkPlay(device, t, target, report)
            }
        } catch (e: IOException) {
            val busy = e.message.orEmpty().let { "claimInterface" in it || "No unambiguous" in it || "openDevice failed" in it || "no HID interface" in it }
            report.stopped(if (busy) "${ProbePlan.deviceToken(target.caps.name)} hid busy: ${e.message}" else "${ProbePlan.deviceToken(target.caps.name)} hid err ${e.message}")
        }
        hidLock.withLock { withContext(usb) { UacProbe.run(manager, usbDevice, report) } }
        report.done()
    }

    /** Layer 2: WalkPlay read requests, one at a time, 100 ms apart; a DAC that does not know the command stays silent. The frames and the asking are the session's. */
    private fun probeWalkPlay(device: DacHandle, t: HidIo, target: DeviceTarget, report: ProbeReport) {
        val layer = "walkplay"
        val session = walkPlaySession(device, t, target).first
        ProbePlan.WALKPLAY_COMMANDS.forEachIndexed { i, cmd ->
            if (i > 0) Thread.sleep(100)
            t.checkSession()
            val tx = ProbePlan.walkPlayRead(cmd)
            val name = "0x%02x".format(cmd)
            report.tx(layer, name, UsbLog.hex(tx))
            val rx = session.probeRequest(tx, cmd)
            if (rx != null) report.rx(layer, name, UsbLog.hex(rx)) else report.noReply(layer, name)
        }
    }

    /** A slot Contour already knows: the DAC switches to USER [slot] (nothing is saved) and one query confirms it; returns the slot confirmed. No band is read. */
    suspend fun selectSlot(device: DacHandle, slot: Int): Int = withDevice(device) { t ->
        val start = SystemClock.elapsedRealtime()
        val confirmed = nativeSession(device, t, target(device)).selectSlot(slot, explicitUserAction = true); t.checkSession()
        UsbLog.line("switch: slot $slot confirmed $confirmed, ${SystemClock.elapsedRealtime() - start} ms")
        confirmed
    }

    /**
     * A setting whose write makes the DAC leave the USB bus ([io.github.chronosauros.contour.core.native.DacSetting.reconnectsAfterWrite]: the JA11's in-line mic): the SET and nothing after it. The session ends with the frame (no guard after it,
     * [withDevice] without its post-guard): the controller waits for the DAC to come back and confirms the value with a settings read.
     */
    suspend fun writeSettingThenLeave(device: DacHandle, id: String, value: Int) = withDevice(device, postGuard = false) { t ->
        val target = target(device)
        if (target.fiio == null || target.settings.isEmpty()) throw IOException("No settings writer for ${target.caps.name}")
        nativeSession(device, t, target).sendSettingThenLeave(id, value, UsbLog::line)
    }

    /** A FiiO USER slot Contour has not seen, and the background scan: the DAC switches to USER [slot] (nothing is saved) and that slot is read. */
    suspend fun readSlot(device: DacHandle, slot: Int): DacSnapshot = withDevice(device) { t ->
        val target = target(device)
        val start = SystemClock.elapsedRealtime()
        UsbLog.line("native read: switch to slot $slot")
        val state = nativeSession(device, t, target).readSlot(slot, explicitUserAction = true); t.checkSession()
        UsbLog.line("read: ${target.caps.name}, slot ${state.slot}, preamp ${state.preamp} dB")
        nativeSnapshot(target, state, SystemClock.elapsedRealtime() - start)
    }

    /** KA15: renames one USER slot and returns the name read back; the EQ on the DAC is untouched. */
    suspend fun renameSlot(device: DacHandle, slot: Int, name: String): String = withDevice(device) { t ->
        UsbLog.line("native rename: slot $slot -> $name")
        nativeSession(device, t, target(device)).renameSlot(slot, name, explicitUserAction = true).also { t.checkSession() }
    }

    /** A/B: only the supplied bands + TEMP_WRITE, on the existing USB thread, through the WalkPlay session. No read-back wait. */
    suspend fun writeTemporaryBands(device: DacHandle, bands: List<WalkPlay.BandWrite>, slot: Int) = withDevice(device) { t ->
        val t0 = SystemClock.elapsedRealtime()
        UsbLog.line("A/B RAM: ${bands.size} changed bands, slot $slot, preamp kept")
        walkPlaySession(device, t, target(device)).first.writeTemporary(bands, slot, explicitHold = true)
        UsbLog.line("A/B RAM: TEMP_WRITE sent, ${SystemClock.elapsedRealtime() - t0} ms (DSP not verified by read-back)")
    }

    /** Profile -> device plan -> write -> read back and compare. Throws with the issues when it does not fit.
     * [targetSlot]: KA15 only, the USER slot to write (null = USER1). [onProgress]: 0..1, the frames completed out of those
     * planned (select, every band, preamp, save, the read-back), called on the USB thread. */
    suspend fun writeProfile(device: DacHandle, profile: Profile, targetSlot: Int? = null, onProgress: ((Float) -> Unit)? = null, trimDb: Int? = null): WriteResult {
        val target = target(device)
        // [trimDb]: the MAX VOLUME to send under (the DAC page's new value); null = the one this DAC is sent with now
        val trim = trimDb ?: trimFor(target)
        // A 1.6 family DAC is read only until its write is proven or the build is a review build (DeviceTarget.readOnly, Proven): this is the guard, and nothing below it builds a frame for a read-only DAC.
        if (target.readOnly) throw IOException(DeviceTarget.READ_ONLY_ISSUE.format(target.caps.name))
        target.echo?.let { return writeEcho(device, profile, target, it, onProgress) }
        target.moondrop?.let { return writeDawn(device, profile, target, it, onProgress) }
        target.ktMicro?.let { return writeAllegro(device, profile, target, it, onProgress) }
        target.gen1?.let { return writeGen1(device, profile, target, it, onProgress) }
        if (!target.native) return when (val plan = requireNotNull(target.walkplay).plan(profile, trim)) {
            is DevicePlan.Rejected -> throw IOException(plan.issues.joinToString("; "))
            is DevicePlan.Ready -> writePlan(device, plan, onProgress, trim)
        }
        target.issues(profile).takeIf { it.isNotEmpty() }?.let { throw IOException(it.joinToString("; ")) }
        return withDevice(device, postGuard = target.fiio?.disconnectOnSave != true) { t ->
            val start = SystemClock.elapsedRealtime()
            UsbLog.line("native write: target slot ${targetSlot ?: target.destinationSlot}")
            val r = nativeSession(device, t, target).write(profile, explicitHold = true, targetSlot = targetSlot,
                onProgress = onProgress?.let { report -> { done, total -> report(done.toFloat() / total) } })
            // A DAC that leaves the USB bus on SAVE (JA11): the write ended at the SAVE frame, the result is pending until the first read after it is back matches what was written.
            r.expected?.takeIf { r.pending }?.let { expected ->
                val ms = SystemClock.elapsedRealtime() - start
                UsbLog.line("native write: SAVE sent, $ms ms; the DAC leaves the USB bus; not verified until it is read again")
                return@withDevice WriteResult(false, emptyList(), ms, 0, null, pending = PendingSave { back ->
                    val state = back.nativeState as? NativeState.Fiio ?: return@PendingSave listOf("the DAC was not read back")
                    if (runCatching { state.codec.matches(expected, state.raw) }.getOrDefault(false)) emptyList()
                    else listOf("FiiO native register mismatch (the DAC reads slot ${state.slot}, ${state.raw.count} filters; wrote slot ${expected.activeSlot}, ${expected.count})")
                })
            }
            t.checkSession()
            r.reason?.let { UsbLog.line("native write: ${if (r.verified) "VERIFIED, note" else "NOT VERIFIED"}: $it") }
            if (r.reason == null) UsbLog.line("native write: VERIFIED")
            WriteResult(r.verified, listOfNotNull(r.reason), SystemClock.elapsedRealtime() - start, 0,
                nativeSnapshot(target, requireNotNull(r.readback), 0))
        }
    }

    // ---- Contour 1.6 families: Moondrop Dawn Pro 2, Kiwi Ears Allegro Mini, Moondrop Echo-A (report 0x4B through the descriptor-proven transport) ----------

    /** The port of a 1.6 family: report 0x4B, except the Moondrop gen-1 cable (DUSK-SP), whose report is 1 and whose requests are SET_REPORT and GET_REPORT ([HidTransport.requestNative]). */
    private fun familyPort(t: HidIo, device: DacHandle, target: DeviceTarget, tick: () -> Unit = {}) =
        WalkPlayPort(t, device, if (target.gen1 != null) MoondropGen1Catalog.REPORT_ID else WalkPlay.REPORT_ID, tick)

    private fun familySnapshot(target: DeviceTarget, state: FamilyState, ms: Long) =
        DacSnapshot((state as? FamilyState.EchoA)?.snapshot?.firmware ?: "native", state.slot, emptyList(), null, ms, protocol = null, target = target, familyState = state)

    /** One read of a 1.6 family DAC by its :core session. The Echo-A is read through [FamilyDevices.readEchoA]; its write is [writeEcho] and only reachable once the flag is set. */
    private fun readFamily(device: DacHandle, t: HidIo, target: DeviceTarget): DacSnapshot {
        val port = familyPort(t, device, target)
        val start = SystemClock.elapsedRealtime()
        val dawn = target.moondrop
        val allegro = target.ktMicro
        val gen1 = target.gen1
        val state: FamilyState = when {
            dawn != null -> FamilyState.Dawn(dawn, MoondropSession(dawn, port).read(withPreGain = true))
            gen1 != null -> FamilyState.Dusk(gen1, MoondropGen1Session(gen1, port).read())
            allegro != null -> KtMicroSession(allegro, port).let { s -> FamilyState.Allegro(allegro, s.read(), if (allegro.globalGainUserSteps != null) s.readGlobalGain() else null) }
            else -> FamilyDevices.readEchoA(port)
        }
        t.checkSession()
        val ms = SystemClock.elapsedRealtime() - start
        UsbLog.line("read: ${state.describe().lineSequence().first()}, $ms ms")
        return familySnapshot(target, state, ms)
    }

    /**
     * Dawn Pro 2: all eight bands and Pre Gain, through [MoondropSession.write], which sends what MOONDROP Hub's Write Cfg sends (the index switch to 7 without a check, the
     * bands, the gains, the save) and verifies by reading the bands back, whatever index the unit reports. The result carries the index the DAC was found on only when the write
     * moved it (a unit that ignores the switch stays where it is), so the app offers to return to it only then.
     */
    private suspend fun writeDawn(device: DacHandle, profile: Profile, target: DeviceTarget, model: MoondropCatalog.Model,
                                  onProgress: ((Float) -> Unit)?): WriteResult {
        target.issues(profile).takeIf { it.isNotEmpty() }?.let { throw IOException(it.joinToString("; ")) }
        val planned = MoondropCodec.planFor(model, profile.bands, profile.effectivePreampDb(model.shelfForm))
        if (planned.issues.isNotEmpty()) throw IOException(planned.issues.joinToString("; "))
        return withDevice(device) { t ->
            val start = SystemClock.elapsedRealtime()
            // Frames planned: the read before and the read-back (every query), the index switch, the band reports, pre-gain, save.
            val steps = Steps(2 * 2 * MoondropCodec.readQueries(model).size + planned.bands.size + 9, onProgress)
            val session = MoondropSession(model, WalkPlayPort(t, device) { steps.tick() })
            UsbLog.line("write: ${target.caps.name}: as MOONDROP Hub (EQ index switch to ${model.writableIndex} unchecked), ${planned.bands.size} bands, pre-gain ${planned.preGainDb} dB")
            val r = session.write(planned.bands, planned.preGainDb, explicitHold = true)
            t.checkSession()
            val back = requireNotNull(r.readback)
            val previous = session.lastBefore?.activeIndex?.takeIf { it != back.activeIndex }
            val ms = SystemClock.elapsedRealtime() - start
            UsbLog.line("write: ${if (r.verified) "VERIFIED" else "MISMATCH"}${r.reason?.let { ", $it" }.orEmpty()}, $ms ms" + (previous?.let { "; the DAC was on EQ index $it" } ?: "") + "; EQ index after: ${back.activeIndex}")
            WriteResult(r.verified, listOfNotNull(r.reason), ms, 0, familySnapshot(target, FamilyState.Dawn(model, back), 0), previousIndex = previous)
        }
    }

    /**
     * Echo-A (only reached when [WalkPlayProfiles.ECHO_A_HARDWARE_VERIFIED] is true or in a review build): the Protocol Micro's write sequence (bands, preamp, TEMP_WRITE, commit) planned with the
     * Echo-A's own limits, then read back and compared register for register, as a Micro write is.
     */
    private suspend fun writeEcho(device: DacHandle, profile: Profile, target: DeviceTarget, echo: WalkPlayProfile, onProgress: ((Float) -> Unit)?): WriteResult {
        target.issues(profile).takeIf { it.isNotEmpty() }?.let { throw IOException(it.joinToString("; ")) }
        return withDevice(device) { t ->
            val start = SystemClock.elapsedRealtime()
            var steps: Steps? = null
            val session = WalkPlaySession(echo, WalkPlayPort(t, device) { steps?.tick() })
            val plan = when (val planned = session.plan(profile)) {
                is DevicePlan.Rejected -> throw IOException(planned.issues.joinToString("; "))
                is DevicePlan.Ready -> planned
            }
            steps = Steps(session.plannedFrames(plan), onProgress)
            UsbLog.line("write: ${target.caps.name}: ${plan.bands.size} bands, preamp ${plan.preampDb} dB")
            val r = session.writePlan(plan, explicitHold = true)
            t.checkSession()
            val back = requireNotNull(r.readback)
            val ms = SystemClock.elapsedRealtime() - start
            UsbLog.line("write: ${if (r.verified) "VERIFIED" else "MISMATCH"}${r.reason?.let { ", $it" }.orEmpty()}, $ms ms")
            WriteResult(r.verified, listOfNotNull(r.reason), ms, 0, familySnapshot(target, FamilyState.EchoA(echo, back), 0))
        }
    }

    /**
     * DUSK-SP (Moondrop gen 1): the nine bands to the Custom EQ ([MoondropGen1Session.write]): a read, the 56 requests with the record's pacing, ending at the commit. The commit stores the bank and
     * RESTARTS the cable (it leaves the bus ~650 ms after the commit and is back 0.5-0.7 s later, on preset 1), so the result is pending: once it is back the controller selects the Custom EQ in RAM
     * ([PendingSave.afterReturn]) and the read after it must show preset 0 and the nine meta records written. It has no preamp: only the bands reach the codec.
     */
    private suspend fun writeGen1(device: DacHandle, profile: Profile, target: DeviceTarget, model: MoondropGen1Catalog.Model,
                                  onProgress: ((Float) -> Unit)?): WriteResult {
        target.issues(profile).takeIf { it.isNotEmpty() }?.let { throw IOException(it.joinToString("; ")) }
        // postGuard false: the cable leaves the bus after the commit, and the session guard after the block would throw for that expected detach
        return withDevice(device, postGuard = false) { t ->
            val start = SystemClock.elapsedRealtime()
            val steps = Steps(io.github.chronosauros.contour.core.native.MoondropGen1Codec.plannedRequests(model), onProgress)
            val session = MoondropGen1Session(model, familyPort(t, device, target) { steps.tick() })
            UsbLog.line("write: ${target.caps.name}: Custom EQ, ${model.bandCount} bands (the missing ones neutral), meta records and coefficients, then the commit")
            session.write(profile.bands, explicitHold = true)
            val planned = requireNotNull(session.lastPlanned)
            val ms = SystemClock.elapsedRealtime() - start
            UsbLog.line("write: commit sent, $ms ms; the cable restarts (leaves the bus, comes back on preset ${model.powerUpPreset}); not verified until it is back, selected onto the Custom EQ and read again")
            WriteResult(false, emptyList(), ms, 0, null, pending = PendingSave(afterReturn = { d ->
                val r = selectGen1Preset(d, model.customIndex)
                if (!r.verified) throw IOException(r.mismatches.firstOrNull() ?: "the cable did not take the Custom EQ")
            }) { back ->
                val state = back.familyState as? FamilyState.Dusk ?: return@PendingSave listOf("the DAC was not read back")
                io.github.chronosauros.contour.core.native.MoondropGen1Codec.mismatches(planned, state.snapshot.activeIndex, state.snapshot.bands, model.customIndex)
            })
        }
    }

    /**
     * DUSK-SP: selects firmware preset (or the Custom EQ) [index] ([MoondropGen1Session.selectPreset]: the select frame alone, no commit, then a read of the preset) and reads the cable again, so the result
     * carries the state a read shows. The choice is RAM only (kept until the cable restarts or is plugged in again); nothing is written to any bank. Verified when the cable reports [index].
     */
    suspend fun selectGen1Preset(device: DacHandle, index: Int): WriteResult = withDevice(device) { t ->
        val target = target(device)
        val model = target.gen1 ?: throw IOException("No preset to select on ${target.caps.name}")
        val start = SystemClock.elapsedRealtime()
        val session = MoondropGen1Session(model, familyPort(t, device, target))
        UsbLog.line("switch: ${target.caps.name} -> preset $index (${model.presetName(index)})")
        val confirmed = session.selectPreset(index, explicitUserAction = true)
        val back = session.read()
        t.checkSession()
        val ms = SystemClock.elapsedRealtime() - start
        UsbLog.line("switch: preset $index confirmed $confirmed, $ms ms")
        val reason = if (confirmed == index) null else "the cable reports preset $confirmed, not $index"
        WriteResult(confirmed == index, listOfNotNull(reason), ms, 0, familySnapshot(target, FamilyState.Dusk(model, back), 0))
    }

    /** Allegro Mini: the bands and SAVE, register 0x24 (the EQ switch) untouched: the switch on the DAC page is the only way to change it. The DAC leaves the bus, so the result is pending (see [savePending]). The Allegro has no preamp: only 0 dB reaches the codec. */
    private suspend fun writeAllegro(device: DacHandle, profile: Profile, target: DeviceTarget, model: KtMicroCatalog.Model,
                                     onProgress: ((Float) -> Unit)?): WriteResult {
        target.issues(profile).takeIf { it.isNotEmpty() }?.let { throw IOException(it.joinToString("; ")) }
        return withDevice(device) { t ->
            val start = SystemClock.elapsedRealtime()
            val steps = Steps(2 * KtMicroCodec.readQueries(model).size + 2 * model.bandCount + 6, onProgress)
            val session = KtMicroSession(model, WalkPlayPort(t, device) { steps.tick() })
            UsbLog.line("write: ${target.caps.name}: ${model.bandCount} bands, " + if (model.sendTurnsEqOn) "EQ switch turned on if it is off" else "EQ switch left as found")
            session.write(profile.copy(preampDb = 0.0), explicitHold = true, eqEnabled = model.sendEqEnabled)
            if (model.sendTurnsEqOn) UsbLog.line("write: EQ switch " + if (session.lastPlan?.frames?.any { (it.payload[0].toInt() and 255) == KtMicroCodec.EQ_ENABLE_REGISTER } == true) "was off: turned on" else "was already on")
            savePending(session, start)
        }
    }

    /** A KT write ended at SAVE: not verified, nothing read back. What it was meant to write is what the first read after the DAC is back must show. */
    private fun savePending(session: KtMicroSession, start: Long): WriteResult {
        val plan = requireNotNull(session.lastPlan)
        val ms = SystemClock.elapsedRealtime() - start
        UsbLog.line("write: SAVE sent, $ms ms; the DAC leaves the USB bus; not verified until it is read again")
        return WriteResult(false, emptyList(), ms, 0, null, pending = PendingSave { back ->
            val state = back.familyState as? FamilyState.Allegro ?: return@PendingSave listOf("the DAC was not read back")
            // a Global Gain write also expects the 0x3B value (a plan without one adds nothing)
            KtMicroCodec.mismatches(plan, state.snapshot, state.globalGain)
        })
    }

    /** Micro: factory flat on all 8 slots, preamp 0. Max: neutral flat PK on all 10 slots, preamp 0. TRN: its factory flat on all 10 slots, preamp 0. */
    suspend fun restoreFlat(device: DacHandle): WriteResult = writePlan(device, protocol(device).flatPlan(), trimDb = trimFor(target(device)))

    /**
     * The state just read with band 1's gain register 0.5 dB lower, like research/protocol/write_test.py
     * step 1 (devicepeq mode). Refuses when band 1 is already at the -10 dB limit.
     */
    suspend fun sendTestBand1(device: DacHandle): WriteResult = withDevice(device) { t ->
        require(protocol(device) == DeviceProtocol.MICRO) { "Test write is Micro-only" }
        val (session, port) = walkPlaySession(device, t, target(device))
        val now = session.read()
        val regs = now.bands.map { it.registers }
        val g = regs[0].gain256 - 128
        if (g < -10 * 256) throw IOException("band 1 already at -10 dB, refusing")
        val writes = regs.mapIndexed { i, r ->
            WalkPlay.BandWrite(r.index, r.freq.toDouble(), (if (i == 0) g else r.gain256) / 256.0, r.q256 / 256.0, r.typeCode, rateHz = DeviceProtocol.MICRO.caps.coefficientRateHz)
        }
        walkPlayWrite(session, port, DevicePlan.Ready(writes, now.preampDb), DeviceProtocol.MICRO, trimFor(target(device)))
    }

    /** [trimDb]: the MAX VOLUME [plan] was made under; the read-back snapshot carries it. */
    private suspend fun writePlan(device: DacHandle, plan: DevicePlan.Ready, onProgress: ((Float) -> Unit)? = null, trimDb: Int = 0): WriteResult = withDevice(device) { t ->
        val p = protocol(device)
        // Frames planned: the read before (Max: the whole state; Micro: version and slot), every report of the write sequence, the read-back.
        var steps: Steps? = null
        val (session, port) = walkPlaySession(device, t, target(device)) { steps?.tick() }
        steps = Steps(session.plannedFrames(plan), onProgress)
        walkPlayWrite(session, port, plan, p, trimDb)
    }

    /** Frames done out of [total] planned, as 0..1 for [report]; the total grows if the plan was underestimated, so it never reaches 1 early. */
    private class Steps(private val total: Int, private val report: ((Float) -> Unit)?) {
        private var done = 0
        fun tick() { done++; report?.invoke(done.toFloat() / maxOf(total, done + 1)) }
    }

    /** The WalkPlay session of [target] (Micro, Max, TRN) on [t]; [tick] hears every frame (the progress of a send). */
    private fun walkPlaySession(device: DacHandle, t: HidIo, target: DeviceTarget, tick: () -> Unit = {}): Pair<WalkPlaySession, WalkPlayPort> {
        val port = WalkPlayPort(t, device, tick = tick)
        return WalkPlaySession(requireNotNull(target.walkplayProfile), port) to port
    }

    private fun snapshotOf(p: DeviceProtocol, s: WalkPlaySnapshot, ms: Long, trimDb: Int) = DacSnapshot(s.firmware, s.slot, s.bands, s.preampDb, ms, p, trimDb = trimDb)

    /** MICRO: exactly the 1.2.2 predicates and parsers (DeviceProtocol delegates). MAX, TRN: strict, all 10 slots; TRN asks a missing band once more. */
    private fun readWalkPlay(device: DacHandle, t: HidIo, target: DeviceTarget): DacSnapshot {
        val p = requireNotNull(target.walkplay)
        val t0 = SystemClock.elapsedRealtime()
        val s = walkPlaySession(device, t, target).first.read()
        val ms = SystemClock.elapsedRealtime() - t0
        val trim = trimFor(target)
        UsbLog.line("read: ${if (p.experimental) "${p.caps.name}, " else ""}firmware ${s.firmware}, slot ${s.slot}, preamp ${s.preampDb} dB${if (trim != 0) " (MAX VOLUME $trim dB)" else ""}, $ms ms")
        return snapshotOf(p, s, ms, trim)
    }

    /** The session's write (every band report, preamp, commit sequence with devicePEQ's delays, the read-back) as a [WriteResult]; the registers, preamp and (Max, TRN) slot are compared. */
    private fun walkPlayWrite(session: WalkPlaySession, port: WalkPlayPort, plan: DevicePlan.Ready, p: DeviceProtocol, trimDb: Int): WriteResult {
        val r = session.writePlan(plan, explicitHold = true)
        val back = requireNotNull(r.readback)
        val now = SystemClock.elapsedRealtime()
        val writeMs = port.lastSendEndMs - port.firstSendMs
        val readMs = now - port.lastSendEndMs
        val mismatches = session.mismatches(plan, back, requireNotNull(session.lastSlot))
        val verified = mismatches.isEmpty()
        UsbLog.line("write: ${if (verified) "VERIFIED" else "MISMATCH ${mismatches.size}"}, write $writeMs ms, read-back $readMs ms")
        mismatches.forEach { UsbLog.line("  $it") }
        return WriteResult(verified, mismatches, writeMs, readMs, snapshotOf(p, back, readMs, trimDb))
    }
}
