package tf.monochrome.android.data.local.tags

import java.io.File
import java.io.RandomAccessFile

/**
 * What an MP4's audio track says about itself, read from its sample entry: the
 * codec, channels, rate, depth and bitrate, for Track info.
 *
 * jaudiotagger guesses at these for MP4 and gets multichannel and Apple files
 * wrong: a 5.1 AAC file read as "-14" channels and 16-bit, and an ALAC file as
 * AAC. The sample entry (`stsd` under the audio `trak`) is where the file
 * declares them, along with the codec's own configuration: `esds` for AAC,
 * `alac`, `dec3`/`dac3` for Dolby, `dfLa` for FLAC, `dOps` for Opus. Only the
 * `moov` box is read, never the audio, and a fragmented file (Apple's) keeps
 * its `moov` up front like any other.
 */
internal data class Mp4Audio(
    /** "AAC-LC", "HE-AAC", "ALAC", "E-AC-3", "FLAC"… */
    val codec: String,
    val lossless: Boolean,
    val channels: Int?,
    val sampleRateHz: Int?,
    /** Lossless codecs only: lossy audio has no bit depth to speak of. */
    val bitsPerSample: Int?,
    val bitrateBps: Long?,
    val durationSeconds: Double?,
    /** E-AC-3 carrying Dolby Atmos (JOC). */
    val atmos: Boolean,
    /** Movie fragments (`mvex`): Apple's files and streams. */
    val fragmented: Boolean,
)

internal object Mp4AudioInfo {

    /** [file]'s audio track, or null when it is not an MP4 with one. */
    fun read(file: File): Mp4Audio? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val moov = findTopLevel(raf, "moov") ?: return@use null
            if (moov.second > MAX_MOOV_BYTES) return@use null
            val bytes = ByteArray(moov.second.toInt())
            raf.seek(moov.first)
            raf.readFully(bytes)
            parseMoov(bytes, fileBytes = raf.length())
        }
    }.getOrNull()

    /** Offset and size of the payload of the first top-level [type] box. */
    private fun findTopLevel(raf: RandomAccessFile, type: String): Pair<Long, Long>? {
        var at = 0L
        val end = raf.length()
        val header = ByteArray(16)
        while (at + 8 <= end) {
            raf.seek(at)
            raf.readFully(header, 0, 8)
            var size = u32(header, 0)
            val name = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8L
            if (size == 1L) {
                raf.readFully(header, 8, 8)
                size = u64(header, 8)
                headerSize = 16
            } else if (size == 0L) {
                size = end - at
            }
            if (size < headerSize) return null
            if (name == type) return (at + headerSize) to (size - headerSize)
            at += size
        }
        return null
    }

    /**
     * The audio track in a `moov` payload. [fileBytes] estimates the bitrate
     * when the codec configuration does not carry one.
     */
    internal fun parseMoov(moov: ByteArray, fileBytes: Long = 0): Mp4Audio? {
        val top = Boxes(moov, 0, moov.size)
        val fragmented = top.find("mvex") != null
        val movieTimescale = top.find("mvhd")?.let { mvhdTimescale(moov, it) }
        val fragmentDuration = top.find("mvex")
            ?.let { Boxes(moov, it.payload, it.end).find("mehd") }
            ?.let { mehdDuration(moov, it) }
        for (trak in top.all("trak")) {
            val mdia = Boxes(moov, trak.payload, trak.end).find("mdia") ?: continue
            val mdiaBoxes = Boxes(moov, mdia.payload, mdia.end)
            val hdlr = mdiaBoxes.find("hdlr") ?: continue
            // version/flags(4) pre_defined(4) handler_type(4)
            if (string4(moov, hdlr.payload + 8) != "soun") continue
            val (timescale, duration) = mdiaBoxes.find("mdhd")?.let { mdhd(moov, it) } ?: (0L to 0L)
            val stsd = mdiaBoxes.find("minf")
                ?.let { Boxes(moov, it.payload, it.end).find("stbl") }
                ?.let { Boxes(moov, it.payload, it.end).find("stsd") } ?: continue
            // version/flags(4) entry_count(4), then the first entry.
            val entry = Boxes(moov, stsd.payload + 8, stsd.end).first() ?: continue
            val seconds = when {
                timescale > 0 && duration > 0 && duration != 0xFFFFFFFFL -> duration.toDouble() / timescale
                fragmentDuration != null && movieTimescale != null && movieTimescale > 0 ->
                    fragmentDuration.toDouble() / movieTimescale
                else -> null
            }
            return parseEntry(moov, entry, seconds, fileBytes, fragmented)
        }
        return null
    }

    private fun parseEntry(b: ByteArray, entry: Box, seconds: Double?, fileBytes: Long, fragmented: Boolean): Mp4Audio? {
        var type = entry.type
        val p = entry.payload
        // reserved(6) data_reference_index(2) version(2) revision(2) vendor(4)
        // channelcount(2) samplesize(2) compression_id(2) packet_size(2) samplerate(4, 16.16)
        if (p + 28 > entry.end) return null
        val version = u16(b, p + 8)
        var channels = u16(b, p + 16).takeIf { it > 0 }
        var bits = u16(b, p + 18).takeIf { it > 0 }
        var rate = (u32(b, p + 24) ushr 16).toInt().takeIf { it > 0 }
        var children = p + 28
        when (version) {
            // QuickTime v1: four more 32-bit fields.
            1 -> children += 16
            // QuickTime v2: rate and channels move into the extension.
            2 -> {
                if (p + 64 <= entry.end) {
                    rate = java.lang.Double.longBitsToDouble(u64(b, p + 32)).toInt().takeIf { it > 0 } ?: rate
                    channels = u32(b, p + 40).toInt().takeIf { it > 0 } ?: channels
                    bits = u32(b, p + 48).toInt().takeIf { it > 0 } ?: bits
                }
                children += 36
            }
        }
        val boxes = Boxes(b, children, entry.end)
        // An encrypted entry names its real format in sinf/frma.
        if (type == "enca") {
            boxes.find("sinf")?.let { Boxes(b, it.payload, it.end).find("frma") }
                ?.let { type = string4(b, it.payload) }
        }
        val fileBitrate = if (seconds != null && seconds > 0 && fileBytes > 0) (fileBytes * 8 / seconds).toLong() else null
        return when (type) {
            "mp4a" -> {
                val esds = boxes.find("esds")?.let { esds(b, it) }
                Mp4Audio(
                    codec = esds?.codec ?: "AAC",
                    lossless = false,
                    channels = esds?.channels ?: channels,
                    sampleRateHz = esds?.sampleRate ?: rate,
                    bitsPerSample = null,
                    bitrateBps = esds?.avgBitrate?.takeIf { it > 0 } ?: fileBitrate,
                    durationSeconds = seconds,
                    atmos = false,
                    fragmented = fragmented,
                )
            }
            "alac" -> {
                // ALACSpecificConfig, after the inner box's version/flags:
                // frameLength(4) compatibleVersion(1) bitDepth(1) pb(1) mb(1)
                // kb(1) numChannels(1) maxRun(2) maxFrameBytes(4) avgBitRate(4) sampleRate(4)
                val cfg = boxes.find("alac")?.payload?.plus(4)
                val ok = cfg != null && cfg + 24 <= entry.end
                Mp4Audio(
                    codec = "ALAC",
                    lossless = true,
                    channels = if (ok) (b[cfg!! + 9].toInt() and 0xFF).takeIf { it > 0 } ?: channels else channels,
                    sampleRateHz = if (ok) u32(b, cfg!! + 20).toInt().takeIf { it > 0 } ?: rate else rate,
                    bitsPerSample = if (ok) (b[cfg!! + 5].toInt() and 0xFF).takeIf { it > 0 } ?: bits else bits,
                    bitrateBps = (if (ok) u32(b, cfg!! + 16).takeIf { it > 0 } else null) ?: fileBitrate,
                    durationSeconds = seconds,
                    atmos = false,
                    fragmented = fragmented,
                )
            }
            "ec-3" -> {
                val dec3 = boxes.find("dec3")?.let { dec3(b, it) }
                Mp4Audio(
                    codec = "E-AC-3",
                    lossless = false,
                    channels = dec3?.channels ?: channels,
                    sampleRateHz = rate,
                    bitsPerSample = null,
                    bitrateBps = dec3?.bitrate ?: fileBitrate,
                    durationSeconds = seconds,
                    atmos = dec3?.atmos == true,
                    fragmented = fragmented,
                )
            }
            "ac-3" -> {
                val dac3 = boxes.find("dac3")?.let { dac3(b, it) }
                Mp4Audio("AC-3", false, dac3?.first ?: channels, rate, null, dac3?.second ?: fileBitrate, seconds, false, fragmented)
            }
            "ac-4" -> Mp4Audio("AC-4", false, channels, rate, null, fileBitrate, seconds, false, fragmented)
            "fLaC" -> {
                // dfLa: version/flags(4), then a metadata block header(4) and STREAMINFO.
                val info = boxes.find("dfLa")?.payload?.plus(8)
                val ok = info != null && info + 18 <= entry.end
                val sr = if (ok) ((u32(b, info!! + 10) ushr 12) and 0xFFFFF).toInt() else null
                val ch = if (ok) ((b[info!! + 12].toInt() shr 1) and 0x7) + 1 else null
                val bps = if (ok) (((b[info!! + 12].toInt() and 1) shl 4) or ((b[info + 13].toInt() and 0xF0) shr 4)) + 1 else null
                Mp4Audio("FLAC", true, ch ?: channels, sr ?: rate, bps ?: bits, fileBitrate, seconds, false, fragmented)
            }
            "Opus" -> {
                // dOps: version(1) OutputChannelCount(1) PreSkip(2) InputSampleRate(4)
                val dops = boxes.find("dOps")?.payload
                val ch = dops?.takeIf { it + 8 <= entry.end }?.let { (b[it + 1].toInt() and 0xFF).takeIf { n -> n > 0 } }
                Mp4Audio("Opus", false, ch ?: channels, 48_000, null, fileBitrate, seconds, false, fragmented)
            }
            ".mp3" -> Mp4Audio("MP3", false, channels, rate, null, fileBitrate, seconds, false, fragmented)
            else -> null
        }
    }

    private class Esds(val codec: String, val channels: Int?, val sampleRate: Int?, val avgBitrate: Long?)

    /** The ES descriptor: the object type, its bitrate, and AAC's AudioSpecificConfig. */
    private fun esds(b: ByteArray, box: Box): Esds? {
        var at = box.payload + 4 // version/flags
        val end = box.end
        // ES_Descriptor (tag 3)
        if (at >= end || b[at].toInt() != 3) return null
        at = descriptorBody(b, at + 1, end) ?: return null
        val flags = b[at + 2].toInt() and 0xFF
        at += 3 // ES_ID(2) flags(1)
        if (flags and 0x80 != 0) at += 2 // dependsOn_ES_ID
        if (flags and 0x40 != 0) at += 1 + (b[at].toInt() and 0xFF) // URL
        if (flags and 0x20 != 0) at += 2 // OCR_ES_Id
        // DecoderConfigDescriptor (tag 4)
        if (at >= end || b[at].toInt() != 4) return null
        val dcd = descriptorBody(b, at + 1, end) ?: return null
        if (dcd + 13 > end) return null
        val objectType = b[dcd].toInt() and 0xFF
        val avgBitrate = u32(b, dcd + 9)
        var codec = when (objectType) {
            0x40 -> "AAC"
            0x66, 0x67, 0x68 -> "AAC"
            0x69, 0x6B -> "MP3"
            0xA5 -> "AC-3"
            0xA6 -> "E-AC-3"
            else -> "AAC"
        }
        var channels: Int? = null
        var sampleRate: Int? = null
        // DecoderSpecificInfo (tag 5): AudioSpecificConfig, for MPEG-4 audio.
        val dsiTag = dcd + 13
        if (objectType == 0x40 && dsiTag < end && b[dsiTag].toInt() == 5) {
            val dsi = descriptorBody(b, dsiTag + 1, end)
            if (dsi != null) {
                val asc = AudioSpecificConfig.parse(b, dsi, end)
                if (asc != null) {
                    codec = asc.name
                    channels = asc.channels
                    sampleRate = asc.sampleRate
                }
            }
        }
        return Esds(codec, channels, sampleRate, avgBitrate)
    }

    /** Skips a descriptor's expandable length; returns where its body starts. */
    private fun descriptorBody(b: ByteArray, lengthAt: Int, end: Int): Int? {
        var at = lengthAt
        repeat(4) {
            if (at >= end) return null
            val byte = b[at++].toInt() and 0xFF
            if (byte and 0x80 == 0) return at
        }
        return at
    }

    private class Dec3(val channels: Int?, val bitrate: Long?, val atmos: Boolean)

    /** dec3: data_rate(13) num_ind_sub(3), then each independent substream. */
    private fun dec3(b: ByteArray, box: Box): Dec3? {
        val bits = BitReader(b, box.payload, box.end)
        val dataRate = bits.read(13) ?: return null
        val numIndSub = (bits.read(3) ?: return null) + 1
        var channels = 0
        for (i in 0 until numIndSub) {
            bits.read(2) // fscod
            bits.read(5) // bsid
            bits.read(1) // reserved
            bits.read(1) // asvc
            bits.read(3) // bsmod
            val acmod = bits.read(3) ?: return null
            val lfeon = bits.read(1) ?: return null
            bits.read(3) // reserved
            val numDepSub = bits.read(4) ?: return null
            if (numDepSub > 0) bits.read(9) else bits.read(1)
            if (i == 0) channels = ACMOD_CHANNELS[acmod] + lfeon
        }
        // reserved(7) flag_ec3_extension_type_a(1): Joint Object Coding, Atmos.
        bits.read(7)
        val atmos = bits.read(1) == 1
        return Dec3(channels.takeIf { it > 0 }, dataRate.toLong() * 1000, atmos)
    }

    /** dac3: fscod(2) bsid(5) bsmod(3) acmod(3) lfeon(1) bit_rate_code(5). */
    private fun dac3(b: ByteArray, box: Box): Pair<Int, Long?>? {
        val bits = BitReader(b, box.payload, box.end)
        bits.read(2); bits.read(5); bits.read(3)
        val acmod = bits.read(3) ?: return null
        val lfeon = bits.read(1) ?: return null
        val rateCode = bits.read(5) ?: return null
        val kbps = AC3_BITRATES.getOrNull(rateCode)
        return (ACMOD_CHANNELS[acmod] + lfeon) to kbps?.let { it * 1000L }
    }

    private fun mdhd(b: ByteArray, box: Box): Pair<Long, Long> {
        val p = box.payload
        return if (b[p].toInt() == 1) u32(b, p + 20) to u64(b, p + 24)
        else u32(b, p + 12) to u32(b, p + 16)
    }

    private fun mvhdTimescale(b: ByteArray, box: Box): Long {
        val p = box.payload
        return if (b[p].toInt() == 1) u32(b, p + 20) else u32(b, p + 12)
    }

    private fun mehdDuration(b: ByteArray, box: Box): Long? {
        val p = box.payload
        val d = if (b[p].toInt() == 1) u64(b, p + 4) else u32(b, p + 4)
        return d.takeIf { it > 0 }
    }

    /** Channels by AC-3 audio coding mode, LFE not included. */
    private val ACMOD_CHANNELS = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)
    private val AC3_BITRATES = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640)

    /** A moov past this is not one a phone's music file has. */
    private const val MAX_MOOV_BYTES = 32L * 1024 * 1024
}

/** MPEG-4 audio's AudioSpecificConfig: object type, rate and channel layout. */
internal object AudioSpecificConfig {
    class Parsed(val name: String, val channels: Int?, val sampleRate: Int?)

    fun parse(b: ByteArray, at: Int, end: Int): Parsed? {
        val bits = BitReader(b, at, end)
        val objectType = readObjectType(bits) ?: return null
        var rate = readRate(bits) ?: return null
        val config = bits.read(4) ?: return null
        // Explicit SBR / PS: the output rate is the extension's.
        if (objectType == 5 || objectType == 29) {
            rate = readRate(bits) ?: rate
        }
        val name = when (objectType) {
            1 -> "AAC Main"
            2 -> "AAC-LC"
            3 -> "AAC SSR"
            4 -> "AAC LTP"
            5 -> "HE-AAC"
            23 -> "AAC-LD"
            29 -> "HE-AAC v2"
            39 -> "AAC-ELD"
            42 -> "xHE-AAC"
            else -> "AAC"
        }
        return Parsed(name, CHANNEL_CONFIGS.getOrNull(config)?.takeIf { it > 0 }, rate.takeIf { it > 0 })
    }

    private fun readObjectType(bits: BitReader): Int? {
        val t = bits.read(5) ?: return null
        return if (t == 31) (bits.read(6) ?: return null) + 32 else t
    }

    private fun readRate(bits: BitReader): Int? {
        val index = bits.read(4) ?: return null
        return if (index == 0xF) bits.read(24) else RATES.getOrNull(index)
    }

    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** Channels by channelConfiguration; 0 is "in the program config element", unknown here. */
    private val CHANNEL_CONFIGS = intArrayOf(0, 1, 2, 3, 4, 5, 6, 8, 0, 0, 0, 7, 8, 24, 8)
}

/** Big-endian bits from [start] up to [end]; null past the end. */
internal class BitReader(private val b: ByteArray, start: Int, private val end: Int) {
    private var bit = start.toLong() * 8

    fun read(count: Int): Int? {
        var value = 0
        repeat(count) {
            val byte = (bit / 8).toInt()
            if (byte >= end || byte >= b.size) return null
            val shift = 7 - (bit % 8).toInt()
            value = (value shl 1) or ((b[byte].toInt() shr shift) and 1)
            bit++
        }
        return value
    }
}

internal class Box(val type: String, val payload: Int, val end: Int)

/** The boxes laid end to end in [b] from [start] to [end]. */
internal class Boxes(private val b: ByteArray, private val start: Int, private val end: Int) {
    fun all(): List<Box> {
        val out = ArrayList<Box>()
        var at = start
        while (at + 8 <= end) {
            var size = u32(b, at)
            var header = 8
            if (size == 1L) {
                if (at + 16 > end) break
                size = u64(b, at + 8)
                header = 16
            } else if (size == 0L) {
                size = (end - at).toLong()
            }
            if (size < header || at + size > end) break
            out += Box(string4(b, at + 4), at + header, (at + size).toInt())
            at += size.toInt()
        }
        return out
    }

    fun all(type: String): List<Box> = all().filter { it.type == type }
    fun find(type: String): Box? = all().firstOrNull { it.type == type }
    fun first(): Box? = all().firstOrNull()
}

internal fun u16(b: ByteArray, at: Int): Int =
    ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

internal fun u32(b: ByteArray, at: Int): Long =
    ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
        ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

internal fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

internal fun string4(b: ByteArray, at: Int): String = String(b, at, 4, Charsets.ISO_8859_1)
