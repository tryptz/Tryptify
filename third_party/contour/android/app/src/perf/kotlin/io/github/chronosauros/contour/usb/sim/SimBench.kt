package io.github.chronosauros.contour.usb.sim

/**
 * What the owner's units answered to a read (the bench logs of the PC measuring rig, copied from :core's test resources `bench/`): the simulated Dawn Pro 2 and Allegro Mini start from
 * these registers. A log is `TX <report>` followed by its `RX <report>` lines (report ID first, hex); `#` starts a comment.
 */
internal object SimBench {
    /** The (TX, RX...) frames of a log, as reports with their ID byte. */
    fun frames(text: String): List<Pair<ByteArray, List<ByteArray>>> {
        val out = ArrayList<Pair<ByteArray, ArrayList<ByteArray>>>()
        text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val body = bytes(line.substringAfter(' '))
            when (line.substringBefore(' ')) { "TX" -> out += body to ArrayList(); "RX" -> out.last().second += body; else -> error("Not a bench log line: $line") }
        }
        return out
    }

    fun bytes(hex: String): ByteArray = hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    /** Moondrop Dawn Pro 2, HID report descriptor of the unit (the sessions ask it for the report sizes). */
    val DAWN_DESCRIPTOR = "05 0c 09 01 a1 01 85 03 09 e9 09 ea 09 cd 09 cf 15 00 25 01 75 01 95 04 81 02 75 04 95 01 81 03 85 4b 06 01 ff 09 01 15 00 25 03 75 08 95 3f 81 03 85 54 09 03 15 00 25 03 75 08 95 3f 81 03 85 4b 09 02 15 00 25 03 75 08 95 3f 91 02 85 54 09 04 15 00 25 03 75 08 95 3f 91 02 c0"

    /** Moondrop Dawn Pro 2, read plan of 21.10.2026 (EQ index, the eight bands of the bank it is on, gains). */
    val DAWN_READ = """
TX 4b 80 0f 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00  # read active bank (slot)
RX 4b 80 0f 00 09 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
TX 4b 80 09 18 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00  # read band 0
RX 4b 80 09 18 00 00 00 00 00 00 00 40 ab 55 1e 80 83 b1 e1 3f 55 aa e1 7f 7d 4e 1e c0 14 00 b5 00 00 00 02 00 09 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
TX 4b 80 09 18 00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00  # read band 1
RX 4b 80 09 18 00 01 00 00 00 00 00 40 f1 00 5b 80 90 3f a5 3f 0f ff a4 7f 70 c0 5a c0 3c 00 b5 00 00 00 02 00 09 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
TX 4b 80 09 18 00 02 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00  # read band 2
RX 4b 80 09 18 00 02 00 00 00 00 00 40 55 56 2f 81 c0 71 d3 3e ab a9 d0 7e 40 8e 2c c1 c8 00 b5 00 00 00 02 00 09 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
TX 4b 80 09 18 00 03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00  # read band 3
RX 4b 80 09 18 00 03 00 00 00 00 00 40 db ce 8d 83 bf c5 8a 3c 25 31 72 7c 41 3a 75 c3 58 02 b5 00 00 00 02 00 09 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
TX 4b 80 09 18 00 04 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00  # read band 4
RX 4b 80 09 18 00 04 00 00 ac 77 cf 3e e7 2a a6 88 99 14 0b 39 19 d5 59 77 bc 73 25 c8 78 05 cd 00 00 fd 02 00 09 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
TX 4b 80 09 18 00 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00  # read band 5
RX 4b 80 09 18 00 05 00 00 ae cb 41 3e f1 1b 1c 93 cb 93 ce 35 0f e4 e3 6c 88 a0 ef cb 18 15 00 02 00 fd 02 00 09 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
TX 4b 80 09 18 00 06 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00  # read band 6
RX 4b 80 09 18 00 06 00 00 e1 26 24 3a 9f 88 60 c2 a7 e2 15 2b 61 77 9f 3d 79 f6 c5 da b0 36 00 02 00 fb 02 00 09 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
TX 4b 80 09 18 00 07 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00  # read band 7
RX 4b 80 09 18 00 07 00 00 00 00 00 40 41 24 51 ec 60 a7 0c 0c bf db ae 13 a0 58 f3 f3 20 4e b5 00 00 00 02 00 09 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
TX 4b 80 03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00  # read observed pregain offset
RX 4b 80 03 02 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
TX 4b 80 0f 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00  # read active bank (slot)
RX 4b 80 0f 00 09 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
""".trimIndent()

    /** Kiwi Ears Allegro Mini, read plan of 20.10.2026 (EQ switch, the five bands, digital gain, channel flags). */
    val ALLEGRO_READ = """
TX 4b 24 00 00 00 52 00 03 00 00 00  # read register 0x24 (EQ enable)
RX 4b 24 00 00 00 52 00 02 00 00 00
TX 4b 26 00 00 00 52 00 00 00 00 00  # read register 0x26 (band 0 gain and frequency)
RX 4b 26 00 00 00 52 00 00 00 e8 03
TX 4b 27 00 00 00 52 00 00 00 00 00  # read register 0x27 (band 0 Q and type)
RX 4b 27 00 00 00 52 00 c3 02 00 00
TX 4b 28 00 00 00 52 00 00 00 00 00  # read register 0x28 (band 1 gain and frequency)
RX 4b 28 00 00 00 52 00 00 00 d0 07
TX 4b 29 00 00 00 52 00 00 00 00 00  # read register 0x29 (band 1 Q and type)
RX 4b 29 00 00 00 52 00 c3 02 00 00
TX 4b 2a 00 00 00 52 00 00 00 00 00  # read register 0x2a (band 2 gain and frequency)
RX 4b 2a 00 00 00 52 00 00 00 88 13
TX 4b 2b 00 00 00 52 00 00 00 00 00  # read register 0x2b (band 2 Q and type)
RX 4b 2b 00 00 00 52 00 c3 02 00 00
TX 4b 2c 00 00 00 52 00 00 00 00 00  # read register 0x2c (band 3 gain and frequency)
RX 4b 2c 00 00 00 52 00 00 00 40 1f
TX 4b 2d 00 00 00 52 00 00 00 00 00  # read register 0x2d (band 3 Q and type)
RX 4b 2d 00 00 00 52 00 c3 02 00 00
TX 4b 2e 00 00 00 52 00 00 00 00 00  # read register 0x2e (band 4 gain and frequency)
RX 4b 2e 00 00 00 52 00 00 00 10 27
TX 4b 2f 00 00 00 52 00 00 00 00 00  # read register 0x2f (band 4 Q and type)
RX 4b 2f 00 00 00 52 00 c3 02 00 00
TX 4b 66 00 00 00 52 00 00 00 00 00  # read register 0x66 (digital DAC gain)
RX 4b 66 00 00 00 52 00 00 02 00 00
TX 4b 24 00 00 00 52 00 03 00 00 00  # read register 0x24 (EQ enable)
RX 4b 24 00 00 00 52 00 02 00 00 00
TX 4b 01 00 00 00 52 00 00 00 00 00  # read register 0x01 (channel flags)
RX 4b 01 00 00 00 52 00 00 02 00 00
""".trimIndent()
}
