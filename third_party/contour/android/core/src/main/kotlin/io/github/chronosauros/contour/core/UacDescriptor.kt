package io.github.chronosauros.contour.core

/**
 * The USB Audio Class descriptors of a DAC, parsed from the raw configuration descriptor (first configuration only: the FiiO K13 R2R
 * declares two identical ones). Pure parsing for the DAC probe; the control requests are the app's. Read-only knowledge: which
 * AudioControl interface, UAC 1.0 or 2.0, which units exist, which Feature Unit channels have a mute or volume control, and the units on
 * the playback path (USB streaming in -> ... -> a terminal that is not USB streaming).
 *
 * The advBeta `UacVolume.find` (tag advbeta-1.3) is the model for the unit walk; it kept only the one volume Feature Unit, the probe keeps all.
 */
object UacDescriptor {
    enum class Kind { INPUT, OUTPUT, FEATURE, MIXER, SELECTOR, OTHER }

    /**
     * One channel of a Feature Unit (0 = master). [mute] and [volume] are the control bits of the descriptor: UAC 1.0 0 or 1 (present);
     * UAC 2.0 0 = none, 1 = read-only, 3 = read/write (2 is invalid).
     */
    data class Channel(val index: Int, val mute: Int, val volume: Int) {
        val hasMute: Boolean get() = mute != 0
        val hasVolume: Boolean get() = volume != 0
    }

    data class Unit(
        val id: Int,
        val kind: Kind,
        /** The class-specific descriptor subtype (0x02 input terminal ... 0x0A clock source). */
        val subtype: Int,
        /** Terminals only: 0x0101 = USB streaming. */
        val terminalType: Int = 0,
        val sources: List<Int> = emptyList(),
        /** Feature Units only. */
        val channels: List<Channel> = emptyList(),
    )

    data class AudioControl(val interfaceNumber: Int, val uac2: Boolean, val bcdAdc: Int, val units: List<Unit>) {
        val version: String get() = if (uac2) "UAC 2.0" else "UAC 1.0"
        val featureUnits: List<Unit> get() = units.filter { it.kind == Kind.FEATURE }

        /** Every route from a USB-streaming input terminal to an output terminal that is not USB streaming, as unit ids in signal order. */
        fun playbackPaths(): List<List<Int>> {
            val byId = units.associateBy { it.id }
            fun back(id: Int, seen: Set<Int>): List<List<Int>> {
                if (id in seen) return emptyList()
                val u = byId[id] ?: return emptyList()
                if (u.kind == Kind.INPUT) return if (u.terminalType == USB_STREAMING) listOf(listOf(id)) else emptyList()
                return u.sources.flatMap { s -> back(s, seen + id).map { it + id } }
            }
            return units.filter { it.kind == Kind.OUTPUT && it.terminalType != USB_STREAMING }.flatMap { o -> o.sources.flatMap { s -> back(s, setOf(o.id)).map { it + o.id } } }
        }

        /** Feature Units on a playback path (the volume the phone's music goes through). */
        fun playbackFeatureUnits(): List<Unit> {
            val ids = playbackPaths().flatten().toSet()
            return featureUnits.filter { it.id in ids }
        }

        fun describe(u: Unit): String = when (u.kind) {
            Kind.INPUT -> "IT 0x%02x type 0x%04x%s".format(u.id, u.terminalType, if (u.terminalType == USB_STREAMING) " (USB streaming)" else "")
            Kind.OUTPUT -> "OT 0x%02x type 0x%04x%s src %s".format(u.id, u.terminalType, if (u.terminalType == USB_STREAMING) " (USB streaming)" else "", u.sources.joinToString { "0x%02x".format(it) })
            Kind.FEATURE -> "FU 0x%02x src %s controls %s".format(u.id, u.sources.joinToString { "0x%02x".format(it) },
                u.channels.joinToString(" ") { "ch${it.index}:mute=${it.mute},vol=${it.volume}" }.ifEmpty { "none" })
            Kind.MIXER -> "MIXER 0x%02x src %s".format(u.id, u.sources.joinToString { "0x%02x".format(it) })
            Kind.SELECTOR -> "SELECTOR 0x%02x src %s".format(u.id, u.sources.joinToString { "0x%02x".format(it) })
            Kind.OTHER -> "UNIT 0x%02x subtype 0x%02x src %s".format(u.id, u.subtype, u.sources.joinToString { "0x%02x".format(it) })
        }
    }

    const val USB_STREAMING = 0x0101

    /** The first AudioControl interface of the first configuration, or null when the device has none. */
    fun parse(raw: ByteArray): AudioControl? {
        var acInterface = -1
        var uac2 = false
        var bcd = 0
        var inAc = false
        var configs = 0
        val units = ArrayList<Unit>()
        var i = 0
        while (i + 1 < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len < 2 || i + len > raw.size) break
            val d = raw.copyOfRange(i, i + len)
            fun u8(k: Int) = d[k].toInt() and 0xFF
            fun u16(k: Int) = u8(k) or (u8(k + 1) shl 8)
            when (u8(1)) {
                0x02 -> if (++configs > 1) break
                0x04 -> if (len >= 9) { // interface
                    val ac = u8(5) == 0x01 && u8(6) == 0x01
                    if (ac && acInterface < 0) { acInterface = u8(2); uac2 = u8(7) == 0x20 }
                    inAc = ac && acInterface == u8(2)
                }
                0x24 -> if (inAc && len >= 4) { // class-specific AudioControl
                    val id = u8(3)
                    when (u8(2)) {
                        0x01 -> if (len >= 5) bcd = u16(3) // header: bcdADC
                        0x02 -> if (len >= 6) units += Unit(id, Kind.INPUT, 0x02, terminalType = u16(4))
                        0x03 -> if (len >= 8) units += Unit(id, Kind.OUTPUT, 0x03, terminalType = u16(4), sources = listOf(u8(7)))
                        0x04 -> units += mixer(d, Kind.MIXER)
                        0x05 -> units += mixer(d, Kind.SELECTOR)
                        0x06 -> if (len >= 7) units += featureUnit(d, uac2)
                        else -> {
                            val st = u8(2)
                            // UAC2 07 effect (source at 6); processing / extension (bNrInPins at 6; UAC2 08 processing, 09 extension; UAC1 07 / 08).
                            if (uac2 && st == 0x07 && len >= 7) units += Unit(id, Kind.OTHER, st, sources = listOf(u8(6)))
                            else if (st in 0x07..0x09 && len >= 7) {
                                val n = u8(6)
                                units += Unit(id, Kind.OTHER, st, sources = (0 until n).filter { 7 + it < len }.map { u8(7 + it) })
                            } else units += Unit(id, Kind.OTHER, st)
                        }
                    }
                }
            }
            i += len
        }
        if (acInterface < 0) return null
        return AudioControl(acInterface, uac2, bcd, units)
    }

    private fun mixer(d: ByteArray, kind: Kind): Unit {
        fun u8(k: Int) = d[k].toInt() and 0xFF
        val n = if (d.size > 4) u8(4) else 0
        return Unit(u8(3), kind, u8(2), sources = (0 until n).filter { 5 + it < d.size }.map { u8(5 + it) })
    }

    private fun featureUnit(d: ByteArray, uac2: Boolean): Unit {
        fun u8(k: Int) = d[k].toInt() and 0xFF
        val channels = ArrayList<Channel>()
        if (uac2) {
            // bmaControls: 4 bytes per channel from offset 5 (0 = master); mute = bits 0-1, volume = bits 2-3; last byte iFeature
            val count = (d.size - 6) / 4
            for (ch in 0 until count) {
                val b = u8(5 + ch * 4)
                channels += Channel(ch, b and 3, (b shr 2) and 3)
            }
        } else {
            // bControlSize at 5, bmaControls from 6; mute = bit 0, volume = bit 1; last byte iFeature
            val size = u8(5).coerceAtLeast(1)
            val count = (d.size - 7) / size
            for (ch in 0 until count) {
                val b = u8(6 + ch * size)
                channels += Channel(ch, b and 1, (b shr 1) and 1)
            }
        }
        return Unit(u8(3), Kind.FEATURE, 0x06, sources = listOf(u8(4)), channels = channels.filter { it.hasMute || it.hasVolume })
    }

    // ---- values --------------------------------------------------------------------------------------------------------

    /** A volume value: signed 1/256 dB (UAC 1.0 and 2.0 alike); 0x8000 is silence (minus infinity). */
    fun volumeDb(raw: Int): Double? = raw.toShort().toInt().takeIf { it != Short.MIN_VALUE.toInt() }?.let { it / 256.0 }

    fun s16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) or (b[at + 1].toInt() shl 8)).toShort().toInt()

    /** The text for a volume GET_CUR / GET_MIN / GET_MAX / GET_RES reply of [n] bytes: "-30.00 dB (raw -7680)". */
    fun volumeText(b: ByteArray, n: Int): String =
        if (n < 2) "short reply" else s16(b, 0).let { v -> "${volumeDb(v)?.let { "%.2f dB".format(java.util.Locale.ROOT, it) } ?: "silence"} (raw $v)" }

    /** The text for a UAC 2.0 volume GET_RANGE reply: wNumSubRanges, then min / max / res per sub-range. */
    fun volumeRangeText(b: ByteArray, n: Int): String {
        if (n < 2) return "short reply"
        val count = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        val parts = (0 until count).filter { 2 + it * 6 + 5 < n }.map { k ->
            val o = 2 + k * 6
            "${volumeText(b.copyOfRange(o, o + 2), 2)} .. ${volumeText(b.copyOfRange(o + 2, o + 4), 2)} step ${volumeText(b.copyOfRange(o + 4, o + 6), 2)}"
        }
        return "$count sub-range(s): " + parts.joinToString("; ")
    }
}
