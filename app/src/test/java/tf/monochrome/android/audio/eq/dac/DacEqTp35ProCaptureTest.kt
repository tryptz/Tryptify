package tf.monochrome.android.audio.eq.dac

import io.github.chronosauros.contour.core.DacImport
import io.github.chronosauros.contour.core.DevicePlan
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.native.NativeHidReports
import io.github.chronosauros.contour.core.native.NativePort
import io.github.chronosauros.contour.core.native.WalkPlaySession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import java.io.IOException

/**
 * The EPZ TP35 Pro is not one of Contour's DACs: the page drives it as the
 * Tanchjim Space II ([DacEqUntested]). This holds that choice to the one
 * piece of hard evidence there is, a recording of a real TP35 Pro
 * (`src/test/resources/dac-eq/`, from devicePEQ): Contour's Space II code
 * reads the recorded unit's replies, and the frames it would send are the
 * frames the unit was sent.
 */
class DacEqTp35ProCaptureTest {

    private val entry = DacEqUntested.EPZ_TP35_PRO
    private val target: DeviceTarget = entry.driveAs
    private val profile = requireNotNull(target.walkplayProfile)

    private class Exchange(val sent: ByteArray, val replies: List<ByteArray>)

    private val recording = Json.parseToJsonElement(
        requireNotNull(javaClass.getResourceAsStream("/dac-eq/walkplay_schemeno16_tp35_pro.json")).bufferedReader().use { it.readText() },
    ).jsonObject

    private fun bytes(of: kotlinx.serialization.json.JsonElement) =
        of.jsonObject.getValue("data").jsonArray.map { it.jsonPrimitive.int.toByte() }.toByteArray()

    private val exchanges = recording.getValue("exchanges").jsonArray.map { x ->
        Exchange(bytes(x.jsonObject.getValue("send")), x.jsonObject.getValue("responses").jsonArray.map(::bytes))
    }

    /** A request as it is on the wire: the report is 63 bytes after its id, zero filled. */
    private fun wire(payload: ByteArray) = payload.copyOf(63)

    /**
     * Answers as the recorded unit did, in the order it did: two requests can
     * be the same bytes on the wire (the slot byte's and band 0's) and were
     * answered differently. A request the recording does not hold is one no
     * TP35 Pro is known to answer, and fails the test.
     */
    private inner class Recorded : NativePort {
        val asked = ArrayList<ByteArray>()
        /** The replies the reader would not take, by their place in the recording. */
        val turnedAway = ArrayList<Int>()
        private val answered = HashMap<String, Int>()
        override val deviceKey = "recorded:TP35 Pro"
        override fun guard() = Unit
        override fun pause(ms: Long) = Unit
        override fun send(id: Int, kind: NativeHidReports.Kind, payload: ByteArray) = error("a read sends nothing")
        override fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
            assertEquals(0x4B, id)
            asked += payload
            val same = exchanges.filter { it.replies.isNotEmpty() && wire(it.sent).contentEquals(wire(payload)) }
            if (same.isEmpty()) throw AssertionError("not in the recording: " + payload.take(8).joinToString(" ") { "%02x".format(it) })
            val n = answered.merge(wire(payload).contentToString(), 1, Int::plus)!! - 1
            val exchange = same[n.coerceAtMost(same.lastIndex)]
            val reply = exchange.replies.first()
            if (!matches(reply)) {
                turnedAway += exchanges.indexOf(exchange)
                throw IOException("Native request timed out or returned invalid state")
            }
            return reply
        }
    }

    private val read by lazy {
        val unit = Recorded()
        WalkPlaySession(profile, unit).read() to unit
    }

    @Test
    fun `the recording is of the DAC the entry names`() {
        val device = recording.getValue("device").jsonObject
        val vendor = device.getValue("vendorId").jsonPrimitive.int
        val product = device.getValue("productId").jsonPrimitive.int
        val name = device.getValue("productName").jsonPrimitive.content
        assertEquals(entry, DacEqUntested.find(vendor, product, name))
        assertEquals(target, DacEqUntested.target(vendor, product, name))
    }

    @Test
    fun `the Space II's read takes the recorded unit's replies`() {
        val (snapshot, unit) = read
        assertEquals("1.5", snapshot.firmware)
        assertEquals(10, snapshot.bands.size)
        assertEquals(-7, snapshot.preampDb)
        assertEquals(101, snapshot.slot)
        // Everything asked was something the recorded unit answered: the
        // version, the slot byte, ten bands and the preamp. One was asked
        // twice. The unit's first reply to the slot byte's request (the
        // second exchange of the recording) still carries bytes of its
        // version reply; Contour's reader turns away a reply it cannot take
        // whole and asks again, and the unit's next reply is clean.
        assertEquals(listOf(1), unit.turnedAway)
        assertEquals(14, unit.asked.size)
        assertEquals(listOf(0x0C) + List(12) { 0x09 } + listOf(0x03), unit.asked.map { it[1].toInt() })
    }

    @Test
    fun `what it holds comes into the editor inside the TP35 Pro's own limits`() {
        val held = WalkPlaySession(profile, Recorded()).importExact(read.first)
        assertTrue("$held", held is DacImport.Ready)
        val bands = DacEqMapping.fromDac((held as DacImport.Ready).eq, target)
        assertEquals(0, bands.passFilters)
        assertEquals(10, bands.bands.size)
        assertEquals(-7f, bands.preampDb)
        // The one band the recorded unit had set: +7 dB at 21 Hz.
        val set = bands.bands.single { it.gain != 0f }
        assertEquals(21f, set.freq)
        assertEquals(7f, set.gain)
        assertEquals(154 / 256f, set.q)
        val caps = entry.caps()
        assertTrue(bands.bands.all { DacEqFit.limit(it, caps) == it })
    }

    /**
     * The recorded unit was then sent an EQ: +7 dB at 21 Hz with a Q of 0.6,
     * nine flat bands and a preamp of -7 dB. Given that EQ, the page's frames
     * are those frames to the byte: the ten bands with their coefficients,
     * the preamp, and the commit. One byte differs, in the last frame: the
     * recording's `01 01 00` is the reference tool's of May 2026; Contour
     * sends `01 01 01`, the form that tool has sent every WalkPlay DAC since
     * and the one WalkPlay's own app sends to save an EQ and leave it on.
     */
    @Test
    fun `the frames it is sent are the frames the recorded unit was sent`() {
        val typed = listOf(EqBand(0, FilterType.PEAKING, 21f, 7f, 0.6f)) +
            (1..9).map { EqBand(it, FilterType.PEAKING, 100f, 0f, 1f) }
        val sending = DacEqMapping.profile("TP35", typed, -7.0, target, now = 0L)
        assertEquals(emptyList<String>(), target.issues(sending))

        val protocol = requireNotNull(target.walkplay)
        val plan = protocol.plan(sending) as DevicePlan.Ready
        val ours = protocol.writeSequence(plan, read.first.slot).map { it.report }
        val theirs = exchanges.filter { it.sent[0] == 0x01.toByte() }.map { byteArrayOf(0x4B) + wire(it.sent) }
        assertEquals(15, theirs.size)
        assertEquals(theirs.size, ours.size)
        for (i in 0 until theirs.lastIndex) assertArrayEquals("frame ${i + 1}", theirs[i], ours[i])

        val flash = ours.last()
        val recorded = theirs.last()
        assertEquals(listOf(3), flash.indices.filter { flash[it] != recorded[it] })
        assertEquals(1, flash[3].toInt())
        assertEquals(0, recorded[3].toInt())
    }

    /** Every frame of a send is one of the six kinds the recorded unit was sent; nothing else leaves for it. */
    @Test
    fun `whatever the EQ, only the recorded kinds of frame are sent`() {
        val kinds = exchanges.filter { it.sent[0] == 0x01.toByte() }.map { it.sent[1].toInt() to it.sent[2].toInt() }.toSet()
        val eq = listOf(
            EqBand(0, FilterType.LOWSHELF, 105f, 4f, 0.7071f),
            EqBand(1, FilterType.PEAKING, 3200f, -3f, 3f),
            EqBand(2, FilterType.HIGHSHELF, 9000f, -2f, 0.7071f),
        )
        val fitted = DacEqFit.fit(eq, target, caps = entry.caps()).bands
        val protocol = requireNotNull(target.walkplay)
        val plan = protocol.plan(DacEqMapping.profile("TP35", fitted, null, target, now = 0L)) as DevicePlan.Ready
        val sent = protocol.writeSequence(plan, 101).map { it.report }
        assertEquals(10, sent.count { it[2].toInt() == 0x09 })
        for (frame in sent) {
            assertEquals(0x4B, frame[0].toInt())
            assertEquals(0x01, frame[1].toInt())
            val kind = frame[2].toInt() to frame[3].toInt()
            // The flash frame's flag is the one byte that is not the recording's (see above).
            assertTrue("%02x %02x".format(frame[2], frame[3]), kind in kinds || kind == (0x01 to 0x01))
        }
    }
}
