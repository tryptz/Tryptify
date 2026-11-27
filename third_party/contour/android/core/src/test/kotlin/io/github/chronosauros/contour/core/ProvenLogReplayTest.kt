package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Replay of the hardware proofs: every log under test resources `bench/proven/<dac>/<function>.log` is a TX/RX log of a Mac bench run that passed on the unit (the header says
 * which run, firmware, date and verdict), copied from the lab's `runs/<id>/hid/`. It is played through the app's own session for the DAC's family with the phone's transport rules
 * (like [NativeBenchLogTest]): every report the session sends must be the log's TX byte for byte (zero padding after the payload aside), and each request gets the first logged RX that the
 * session's own matcher accepts, else the timeout the phone would see. The input of a write (the EQ the bench was given) sits next to the log as `<function>.json`; a setting or reset has
 * its parameters in the log header (`# setting`, `# value`, `# save`, `# flags`).
 *
 * A cell that passes is proven without the unit as long as the app keeps sending these bytes. A cell that does not pass is the list of what goes back on hardware, and a new PASS log
 * replaces the old one. The test reports every cell in one go (DAC, function, first differing frame, expected against sent) and fails when one differs.
 */
class ProvenLogReplayTest {
    private class Frame(val tx: ByteArray, val rx: List<ByteArray>)

    /** One proven cell: [dac] is the folder, [name] the function (`eq-pk`, `pass-lp`, `setting-volume`, `reset`). */
    private class Proof(val dac: String, val name: String, val header: Map<String, String>, val frames: List<Frame>, val input: JsonObject?) {
        val cell get() = "$dac/$name"
        val flags: Set<String> get() = header["flags"]?.split(' ')?.toSet() ?: emptySet()
    }

    private sealed interface Outcome {
        class Match(val frames: Int) : Outcome
        /** [index] is the 0-based frame; [expected] and [sent] are hex (or a note when there is no frame on one side). */
        class Differs(val index: Int, val expected: String, val sent: String) : Outcome
        class Failed(val message: String) : Outcome
    }

    private class Divergence(val index: Int, val expected: String, val sent: String) : RuntimeException(null, null, false, false)

    private fun text(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 255) }
    private fun hex(s: String) = s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private fun load(file: File): Proof {
        val lines = file.readLines()
        val header = LinkedHashMap<String, String>()
        lines.filter { it.startsWith("#") }.forEach { l -> l.removePrefix("#").trim().let { header[it.substringBefore(' ').trimEnd(':')] = it.substringAfter(' ', "").trim() } }
        val frames = ArrayList<Frame>()
        lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach { line ->
            when (line.substringBefore(' ')) {
                "TX" -> frames += Frame(hex(line.substringAfter(' ')), ArrayList())
                "RX" -> (frames.last().rx as ArrayList) += hex(line.substringAfter(' '))
                else -> error("Not a bench log line in ${file.name}: $line")
            }
        }
        val json = File(file.parentFile, file.nameWithoutExtension + ".json").takeIf { it.exists() }?.let { Json.parseToJsonElement(it.readText()).jsonObject }
        return Proof(file.parentFile.name, file.nameWithoutExtension, header, frames, json)
    }

    private fun proofs(): List<Proof> {
        val dir = File(requireNotNull(javaClass.getResource("/bench/proven")) { "test resources bench/proven" }.toURI())
        return dir.walkTopDown().filter { it.isFile && it.extension == "log" }.sortedBy { it.path }.map(::load).toList()
    }

    /** The phone's transport rules (HidTransport.requestNative) over a logged exchange; the first report that differs ends the replay. */
    private class ReplayPort(val reportId: Int, val reportBytes: Int, val frames: List<Frame>) : NativePort {
        override val deviceKey = "proven-log"
        var next = 0
        override fun guard() {}
        override fun pause(ms: Long) {}
        override fun payloadSize(id: Int, kind: NativeHidReports.Kind) = reportBytes - 1
        private fun u(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 255) }
        private fun take(id: Int, payload: ByteArray): Frame {
            val n = next++
            val sent = byteArrayOf(id.toByte()) + payload
            val shown = sent.copyOf(maxOf(1, sent.indexOfLast { it != 0.toByte() } + 1))
            if (id != reportId) throw Divergence(n, "report ID $reportId", u(shown))
            val f = frames.getOrNull(n) ?: throw Divergence(n, "(the log ends after ${frames.size} frames)", u(shown))
            val tx = f.tx
            val same = tx.size >= sent.size && sent.indices.all { tx[it] == sent[it] } && (sent.size until tx.size).all { tx[it] == 0.toByte() }
            if (!same) throw Divergence(n, u(tx.copyOf(maxOf(1, tx.indexOfLast { it != 0.toByte() } + 1))), u(shown))
            return f
        }
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) { take(id, payload) }
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray =
            take(id, payload).rx.firstOrNull { r -> r.isNotEmpty() && (r[0].toInt() and 255) == id && runCatching { matches(r.copyOfRange(1, r.size)) }.getOrDefault(false) }
                ?.let { it.copyOfRange(1, it.size) }
                ?: throw IOException("Native request timed out or returned invalid state")
    }

    // ---- the bench's input format ({"bands":[{"type","freq","gain","q"}],"preamp"?,"name"?,"eqEnabled"?,"slot"?}), as the lab's tool reads it ----

    private fun filterType(textual: String): FilterType {
        val key = textual.trim().uppercase().replace('-', '_').replace(' ', '_')
        ApoText.typeOf(key)?.let { return it }
        return when (key) {
            "PEAK", "PEAKING" -> FilterType.PEAK
            "LOWSHELF", "LOW_SHELF" -> FilterType.LOW_SHELF
            "HIGHSHELF", "HIGH_SHELF" -> FilterType.HIGH_SHELF
            "LOWPASS", "LOW_PASS" -> FilterType.LOW_PASS
            "HIGHPASS", "HIGH_PASS" -> FilterType.HIGH_PASS
            "BANDPASS", "BAND_PASS" -> FilterType.BAND_PASS
            "ALLPASS", "ALL_PASS" -> FilterType.ALL_PASS
            else -> throw IllegalArgumentException("Unknown filter type $textual")
        }
    }

    private fun profileOf(json: JsonObject): Profile {
        fun num(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull ?: throw IllegalArgumentException("number \"$key\" missing")
        val bands = json.getValue("bands").jsonArray.mapIndexed { i, e ->
            val o = e.jsonObject
            val type = filterType(o.getValue("type").jsonPrimitive.content)
            val gain = if (o["gain"] == null && type.isPass) 0.0 else num(o, "gain")
            Band("proven-$i", type, num(o, "freq"), gain, num(o, "q"), enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true)
        }
        val preamp = json["preamp"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.doubleOrNull
        return Profile("proven", (json["name"] as? JsonPrimitive)?.contentOrNull ?: "", bands = bands, preampDb = preamp, createdAt = 0, updatedAt = 0)
    }

    // ---- the families ----

    private val allegro = requireNotNull(KtMicroCatalog.find(0x31B2, 0x0111, "Kiwi Ears-Allegro Mini"))
    /** The JCally JM12: the same VID:PID, found by its USB name. Its proofs are the Mac runs of 10.11.2026 (the jm12 folder). */
    private val jm12 = requireNotNull(KtMicroCatalog.find(0x31B2, 0x0111, "KT02H20 HIFI Audio"))
    /** The Kiwi Ears AD1 (3302:20E3, found by its USB name): the Mac runs of 13.11.2026 (the ad1 folder). */
    private val ad1 = requireNotNull(KtMicroCatalog.find(0x3302, 0x20E3, "AD1"))

    private fun fiioTarget(p: Proof): DeviceTarget {
        val pass = "pass-filters" in p.flags
        return when (p.dac) {
            "ka15" -> DeviceTarget.ka15(pass)
            "k13-r2r" -> DeviceTarget.K13
            "melody" -> DeviceTarget.melody(pass)
            "tiny-a" -> DeviceTarget.TINY_A
            "ja11" -> DeviceTarget.FIIO_JA11
            "retro-nano" -> DeviceTarget.RETRO_NANO
            "jiezi" -> DeviceTarget.FIIO_JIEZI
            else -> error("Not a FiiO cell: ${p.cell}")
        }
    }

    /** `--pass-filters` and `--positive-preamp` widen the WalkPlay profile as the bench tool does (the flags the run gave every call). */
    private fun walkPlayProfile(p: Proof): WalkPlayProfile {
        var wp = when (p.dac) { "micro" -> WalkPlayProfiles.micro; "max" -> WalkPlayProfiles.max; "trn" -> WalkPlayProfiles.trn; "space-2" -> WalkPlayProfiles.space2; "echo-a" -> WalkPlayProfiles.echoA; else -> error("Not a WalkPlay cell: ${p.cell}") }
        if ("pass-filters" in p.flags) { val caps = ProtocolMicro.capabilities(true); wp = wp.copy(capsOverride = caps, types = caps.types) }
        if ("positive-preamp" in p.flags) wp = wp.copy(preampMaxOverride = ProtocolMicro.VENDOR_PREAMP_MAX_DB)
        return wp
    }

    private val walkPlayRows = mapOf("dac-filter" to WalkPlaySettings.DAC_FILTER, "work-mode" to WalkPlaySettings.WORK_MODE, "gain-mode" to WalkPlaySettings.GAIN_MODE, "mic" to WalkPlaySettings.MIC)

    private fun fail(reason: String): Nothing = throw IllegalStateException(reason)

    /** Plays the proof through its family's session. Returns normally when the session ended (the caller checks that it used the whole log). */
    private fun drive(p: Proof, port: (Int, Int) -> ReplayPort) {
        val command = p.header["command"] ?: fail("no # command in the header")
        val input = p.input
        when (p.dac) {
            "ka15", "k13-r2r", "melody", "tiny-a", "ja11", "retro-nano", "jiezi" -> {
                val target = fiioTarget(p)
                val f = requireNotNull(target.fiio)
                // the JA11: report ID 2 and its 20 data bytes; the RETRO NANO: report 7 of 66 bytes (65 data bytes, a 64-byte IN endpoint); the JIEZI: 63 data bytes
                val bytes = if (p.dac == "k13-r2r") 33 else if (p.dac == "ja11") 21 else if (p.dac == "retro-nano") 66 else 64
                val rp = port(f.reportId, bytes)
                when (command) {
                    "plan" -> {
                        val json = input ?: fail("no .json input")
                        val profile = profileOf(json)
                        val slot = json.getValue("slot").jsonPrimitive.int
                        target.issues(profile).takeIf { it.isNotEmpty() }?.let { fail("the app refuses this EQ: ${it.joinToString("; ")}") }
                        NativeSession(target, rp).write(profile, explicitHold = true, targetSlot = slot)
                    }
                    "setting-plan" -> {
                        val id = p.header.getValue("setting"); val value = p.header.getValue("value").toInt()
                        val row = target.settings.firstOrNull { it.id == id } ?: fail("the app has no setting row $id")
                        DacWrite.checkSetting(f, row); DacWrite.checkValue(row, value)
                        val session = NativeSession(target, rp)
                        session.readSetting(id) {}
                        session.writeSetting(id, value) {}
                    }
                    else -> fail("command $command is not replayed for FiiO")
                }
            }
            "allegro-mini", "jm12", "ad1" -> {
                val kt = if (p.dac == "jm12") jm12 else if (p.dac == "ad1") ad1 else allegro
                val rp = port(KtMicroCodec.REPORT_ID, 11)
                val session = KtMicroSession(kt, rp)
                when (command) {
                    "plan" -> {
                        val json = input ?: fail("no .json input")
                        val profile = profileOf(json).let { it.copy(preampDb = it.preampDb ?: 0.0) }
                        val eqEnabled = (json["eqEnabled"] as? JsonPrimitive)?.booleanOrNull ?: true
                        KtMicroCodec.issues(kt, profile).takeIf { it.isNotEmpty() }?.let { fail("the app refuses this EQ: ${it.joinToString("; ")}") }
                        session.write(profile, explicitHold = true, eqEnabled = eqEnabled)
                    }
                    "setting-plan" -> {
                        check(p.header["setting"] == "global-gain")
                        val step = p.header.getValue("value").toInt()
                        KtMicroCodec.requireGlobalGainStep(step)
                        session.writeGlobalGain(step, explicitHold = true, save = p.header["save"]?.toBooleanStrictOrNull() ?: true)
                    }
                    "reset-plan" -> {
                        val flow = KtMicroResetFlow(kt)
                        val taken = session.captureForReset(true, 0L)
                        flow.begin(taken)
                        val ticket = flow.confirm(ownerConsent = true)
                        session.factoryClear(ticket) {}
                        flow.sent()
                        val after = session.captureForReset(true, 0L)
                        if (flow.compared(after).isNotEmpty()) session.restoreAfterReset(taken, after) {}
                        flow.restored()
                    }
                    else -> fail("command $command is not replayed for the Allegro Mini")
                }
            }
            "micro", "echo-a", "trn", "max", "space-2" -> {
                val wp = walkPlayProfile(p)
                val rp = port(0x4B, 64)
                val session = WalkPlaySession(wp, rp)
                when (command) {
                    "plan" -> {
                        val profile = profileOf(input ?: fail("no .json input"))
                        when (val plan = session.plan(profile)) {
                            is DevicePlan.Rejected -> fail("the app refuses this EQ: ${plan.issues.joinToString("; ")}")
                            is DevicePlan.Ready -> session.writePlan(plan, explicitHold = true)
                        }
                    }
                    "setting-plan" -> {
                        val row = WalkPlaySettings.of(wp).firstOrNull { it.id == walkPlayRows[p.header["setting"]] } ?: fail("the app has no setting ${p.header["setting"]}")
                        val vendor = p.header.getValue("value").toInt()
                        val index = if (row.id == WalkPlaySettings.DAC_FILTER || row.id == WalkPlaySettings.GAIN_MODE) vendor - 1 else vendor
                        session.writeSetting(row.id, index, explicitHold = true, save = p.header["save"]?.toBooleanStrictOrNull() ?: false)
                    }
                    else -> fail("command $command is not replayed for WalkPlay")
                }
            }
            "dawn-pro-2" -> {
                val model = MoondropCatalog.dawnPro2
                val rp = port(model.reportId, model.reportBytes)
                when (command) {
                    "plan" -> {
                        val profile = profileOf(input ?: fail("no .json input"))
                        DeviceTarget.DAWN_PRO_2.issues(profile).takeIf { it.isNotEmpty() }?.let { fail("the app refuses this EQ: ${it.joinToString("; ")}") }
                        // As the bench plans it: the input's preamp is the PRE-GAIN, and a missing one leaves the pre-gain as it is (the app itself always writes it, from the profile's effective preamp).
                        val planned = MoondropCodec.planFor(model, profile.bands, profile.preampDb)
                        MoondropSession(model, rp).write(planned.bands, planned.preGainDb, explicitHold = true)
                    }
                    else -> fail("command $command is not replayed for the Dawn Pro 2")
                }
            }
            "moondrop-dusk" -> {
                // the Moondrop gen-1 cable: every report is a request (SET_REPORT then GET_REPORT), the log has an RX for each TX
                val model = MoondropGen1Catalog.duskSp
                val rp = port(MoondropGen1Codec.REPORT_ID, model.reportBytes)
                val session = MoondropGen1Session(model, rp)
                when (command) {
                    "plan" -> {
                        val profile = profileOf(input ?: fail("no .json input"))
                        MoondropGen1Codec.issues(model, profile.bands).takeIf { it.isNotEmpty() }?.let { fail("the app refuses this EQ: ${it.joinToString("; ")}") }
                        session.writeAndVerify(profile.bands, explicitHold = true) // the proof is the RAM select and the read after the cable restarted (the rig logs it in the same file)
                    }
                    "select" -> session.selectPreset(p.header.getValue("slot").toInt(), explicitUserAction = true)
                    "read-plan" -> session.read()
                    else -> fail("command $command is not replayed for the DUSK-SP")
                }
            }
            else -> fail("no replay for ${p.dac}")
        }
    }

    private fun replay(p: Proof): Outcome {
        var port: ReplayPort? = null
        return try {
            drive(p) { id, bytes -> ReplayPort(id, bytes, p.frames).also { port = it } }
            val used = port?.next ?: 0
            if (used < p.frames.size) Outcome.Differs(used, text(p.frames[used].tx.let { it.copyOf(maxOf(1, it.indexOfLast { b -> b != 0.toByte() } + 1)) }), "(the session ended after $used frames)")
            else Outcome.Match(used)
        } catch (d: Divergence) {
            Outcome.Differs(d.index, d.expected, d.sent)
        } catch (e: IllegalStateException) {
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            val used = port?.next ?: 0
            Outcome.Failed("${e.javaClass.simpleName}: ${e.message} (after $used of ${p.frames.size} frames)")
        }
    }

    @Test fun `every proven log carries its run, firmware, date and verdict, and no personal name`() {
        val all = proofs()
        assertTrue(all.size >= 10, "bench/proven holds ${all.size} logs")
        val file = File(javaClass.getResource("/bench/proven")!!.toURI())
        for (p in all) {
            for (key in listOf("proof", "firmware", "date", "verdict", "device", "command")) assertTrue(!p.header[key].isNullOrBlank(), "${p.cell}: header \"$key\"")
            assertTrue(p.header.getValue("proof").startsWith("run 2"), "${p.cell}: proof names a run")
            assertTrue(p.header.getValue("verdict").let { it.startsWith("PASS") || it.startsWith("OK") }, "${p.cell}: verdict ${p.header["verdict"]}")
            assertEquals(p.dac, p.header["device"], "${p.cell}: the header's device is its folder")
            assertTrue(p.frames.isNotEmpty(), p.cell)
        }
        file.walkTopDown().filter { it.isFile }.forEach { f ->
            val body = f.readText().lowercase()
            // home paths only: names and phone serials are refused for every exported file by android/tools/export-public.sh
            for (name in listOf("/users/", "/home/")) assertFalse(name in body, "${f.name} holds \"$name\"")
        }
    }

    /** The cells listed in `proven/STALE.md` (`- <dac>/<function>: <commit> - <why>`): their frames changed after the hardware run and they await a new proof. */
    private fun stale(): Map<String, String> {
        val file = File(File(requireNotNull(javaClass.getResource("/bench/proven")) { "test resources bench/proven" }.toURI()), "STALE.md")
        return file.readLines().mapNotNull { Regex("^- (\\S+/\\S+): (.*)$").find(it) }.associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test fun `every proven log replays byte for byte through the app's own session, apart from the listed stale cells`() {
        val all = proofs()
        val stale = stale()
        assertTrue(stale.keys.all { k -> all.any { it.cell == k } }, "STALE.md names a cell that has no log: ${stale.keys - all.map { it.cell }.toSet()}")
        val matched = ArrayList<String>()
        val rows = ArrayList<String>()
        val listed = ArrayList<String>()
        val healed = ArrayList<String>()
        for (p in all) {
            val line = when (val r = replay(p)) {
                is Outcome.Match -> { matched += "${p.cell} (${r.frames} frames)"; if (p.cell in stale) healed += "${p.cell} now matches: remove it from STALE.md"; continue }
                is Outcome.Differs -> "${p.cell}: frame ${r.index} (0-based) - expected ${r.expected} - sent ${r.sent}"
                is Outcome.Failed -> "${p.cell}: ${r.message}"
            }
            if (p.cell in stale) listed += "STALE ${line} [${stale.getValue(p.cell)}]" else rows += "DIFFERS $line"
        }
        println("PROVEN REPLAY: ${matched.size} of ${all.size} cells match, ${listed.size} listed stale (awaiting re-proof on hardware)")
        matched.forEach { println("  MATCH $it") }
        listed.forEach { println("  $it") }
        rows.forEach { println("  $it") }
        assertTrue(rows.isEmpty(), "${rows.size} proven cell(s) differ and are not on the stale list; they go back on hardware (or onto proven/STALE.md):\n" + rows.joinToString("\n"))
        assertTrue(healed.isEmpty(), "the stale list is not honest:\n" + healed.joinToString("\n"))
    }
}
