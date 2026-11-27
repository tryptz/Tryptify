package io.github.chronosauros.contour.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bands plus preamp as read from a file. [preampDb] = null: the file had none (or asked for auto).
 * [notes] are things the reader changed on the way in (a channel chosen, empty filters skipped); the importer
 * puts them in front of its own fitting notes in the one-line report.
 */
data class ImportedEq(val bands: List<Band>, val preampDb: Double?, val notes: List<String> = emptyList())

/**
 * AutoEQ / Equalizer APO / squig.link / graph.hangout.audio parametric text:
 * ```
 * Preamp: -6.1 dB
 * Filter 1: ON PK Fc 105 Hz Gain -2.5 dB Q 0.70
 * ```
 * Types PK/PEQ, LS/LSC/LSQ, HS/HSC/HSQ, LP/LPQ, HP/HPQ, BP, AP (Equalizer APO's tokens; the pass filters take no Gain); OFF = disabled. Tolerant to case, spacing,
 * missing filter numbers, a decimal comma, a number without its leading zero (`-.5`), a typographic minus and the spaces a web page or a word processor puts in
 * (no-break, narrow, thin; see [plain]). Lines that are not a filter, a preamp or a channel are skipped. A filter or preamp line whose values cannot be read is
 * never imported as something else (a gain that is not there is not 0 dB): it is left out and named in [ImportedEq.notes].
 */
object ApoText {
    private const val NUM = """[-+]?(?:\d+(?:[.,]\d+)?|[.,]\d+)"""
    private val PREAMP = Regex("""^\s*preamp\s*:\s*($NUM)\s*(?:db)?\s*$""", RegexOption.IGNORE_CASE)
    private val PREAMP_LINE = Regex("""^\s*preamp\s*:""", RegexOption.IGNORE_CASE)
    private val FILTER_LINE = Regex("""^\s*filter\s*(\d*)\s*:""", RegexOption.IGNORE_CASE)
    private val FILTER = Regex(
        """^\s*filter\s*\d*\s*:\s*(on|off)\s+([a-z]+)\s*(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    /**
     * After a value: the number ends there - no more digits or separators leading to a digit, no exponent. Without it `Gain 2e-1 dB` read as 2 dB,
     * `Gain -2..5` as -2 and `Fc 1,200.5` as 1.2 Hz, silently; now such a filter is unreadable and named (review 18.11.2026).
     */
    private const val END = """(?![\d.,]*\d)(?![eE][-+]?\d)"""
    private val FC = Regex("""\bfc\s*($NUM)$END\s*(?:hz)?""", RegexOption.IGNORE_CASE)
    private val GAIN = Regex("""\bgain\s*($NUM)$END\s*(?:db)?""", RegexOption.IGNORE_CASE)
    private val Q = Regex("""\bq\s*($NUM)$END""", RegexOption.IGNORE_CASE)
    /** A Q or a bandwidth (`BW Oct 1.0`) the line names but [Q] cannot read: the filter is not read, rather than given [DEFAULT_Q]. */
    private val Q_WORD = Regex("""\b(?:q|bw)\b""", RegexOption.IGNORE_CASE)
    private val CHANNEL = Regex("""^\s*channel\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)

    /**
     * [line] with the typographic minus signs and dashes (U+2212, U+2010-U+2014, U+FE63, U+FF0D) as `-` and the other spaces (U+00A0, U+1680, U+2000-U+200A,
     * U+202F, U+205F, U+3000) as a plain space: the regular expressions here know only ASCII, so a copied "Gain −3 dB" read as 0 dB and a no-break space
     * hid a whole line.
     */
    private fun plain(line: String): String {
        if (line.all { it.code < 0x80 }) return line
        val out = StringBuilder(line.length)
        for (c in line) out.append(
            when (c) {
                '−', '‐', '‑', '‒', '–', '—', '﹣', '－' -> '-'
                ' ', ' ', in ' '..' ', ' ', ' ', '　' -> ' '
                else -> c
            },
        )
        return out.toString()
    }

    /** Q used when a shelf or pass filter line has none (Butterworth / APO's 0.71 default). */
    const val DEFAULT_Q: Double = 0.707

    fun typeOf(code: String): FilterType? = when (code.uppercase()) {
        "PK", "PEQ" -> FilterType.PEAK
        "LS", "LSC", "LSQ" -> FilterType.LOW_SHELF
        "HS", "HSC", "HSQ" -> FilterType.HIGH_SHELF
        "LP", "LPQ" -> FilterType.LOW_PASS
        "HP", "HPQ" -> FilterType.HIGH_PASS
        "BP" -> FilterType.BAND_PASS
        "AP" -> FilterType.ALL_PASS
        else -> null
    }

    fun codeOf(type: FilterType): String = when (type) {
        FilterType.PEAK -> "PK"
        FilterType.LOW_SHELF -> "LSC"
        FilterType.HIGH_SHELF -> "HSC"
        FilterType.LOW_PASS -> "LPQ"
        FilterType.HIGH_PASS -> "HPQ"
        FilterType.BAND_PASS -> "BP"
        FilterType.ALL_PASS -> "AP"
    }

    private fun num(s: String): Double = s.replace(',', '.').toDouble()

    private enum class Side { BOTH, LEFT, RIGHT, OTHER }

    private data class Raw(val type: FilterType, val freqHz: Double, val gainDb: Double, val q: Double, val enabled: Boolean)

    /** The lines under one `Channel:` header ([spec] is what follows it); the lines before the first header are a [Side.BOTH] block. */
    private class Block(val side: Side, val spec: String = "") {
        var preamp: Double? = null
        /** The block's last `Preamp:` line could not be read (a readable one after it takes over; one before it keeps its value). */
        var preampUnread = false
        val filters = ArrayList<Raw>()
        /** The filter lines whose values could not be read: the number the file gives them, else their place among the block's filter lines. */
        val unread = ArrayList<String>()
        var filterLines = 0
    }

    /**
     * The filter of one `Filter n:` line ([line] already [plain]); null when a value it needs cannot be read: no ON/OFF and type, a type Contour does not know,
     * no frequency, no gain on a peak or shelf, or a Q / bandwidth named without a number Contour reads. Never a guessed value in place of one that is there.
     */
    private fun readFilter(line: String): Raw? {
        val m = FILTER.matchEntire(line) ?: return null
        val type = typeOf(m.groupValues[2]) ?: return null
        val rest = m.groupValues[3]
        val fc = FC.find(rest)?.groupValues?.get(1)?.let(::num) ?: return null
        // A pass filter has no gain: a stray "Gain" on its line is ignored, so it never reaches a band.
        val gain = if (type.isPass) 0.0 else GAIN.find(rest)?.groupValues?.get(1)?.let(::num) ?: return null
        // No Q at all is APO's default (a shelf or pass filter written without one); a Q or a bandwidth that is there but not read is not.
        val q = Q.find(rest)?.groupValues?.get(1)?.let(::num) ?: if (Q_WORD.containsMatchIn(rest)) return null else DEFAULT_Q
        return Raw(type, fc, gain, q, m.groupValues[1].equals("on", ignoreCase = true))
    }

    /** REW's empty slot (`Filter 11: ON None`): nothing to read and nothing lost. */
    private fun isEmptySlot(line: String) = FILTER.matchEntire(line)?.groupValues?.get(2)?.equals("none", ignoreCase = true) == true

    private fun sideOf(spec: String): Side {
        val words = spec.uppercase().split(Regex("""[\s,]+""")).filter { it.isNotEmpty() }
        // Equalizer APO also numbers channels: 1 = left, 2 = right
        val left = words.any { it == "L" || it == "FL" || it == "LEFT" || it == "1" }
        val right = words.any { it == "R" || it == "FR" || it == "RIGHT" || it == "2" }
        return when {
            "ALL" in words || (left && right) -> Side.BOTH
            left -> Side.LEFT
            right -> Side.RIGHT
            else -> Side.OTHER
        }
    }

    /**
     * Reads the file as the user means it. A file with `Channel:` blocks (squig.link writes `Channel: L` and
     * `Channel: R`, each with its own Preamp) is read stereo-linked: a block for both channels (`Channel: all`,
     * `L R`, or no header at all) wins; otherwise the left block is taken and, when the right one differs, a note
     * says so. Filters are kept exactly as written - see [parseImport] for the importer's clean-up.
     */
    fun parse(text: String, newId: (Int) -> String = { "b${it + 1}" }): ImportedEq {
        val blocks = ArrayList<Block>()
        var block = Block(Side.BOTH).also { blocks += it }
        for (raw in text.lineSequence()) {
            val line = plain(raw)
            val ch = CHANNEL.matchEntire(line)
            if (ch != null) {
                block = Block(sideOf(ch.groupValues[1]), ch.groupValues[1]).also { blocks += it }
                continue
            }
            if (PREAMP_LINE.containsMatchIn(line)) {
                val p = PREAMP.matchEntire(line)
                if (p != null) { block.preamp = num(p.groupValues[1]); block.preampUnread = false } else block.preampUnread = true
                continue
            }
            val head = FILTER_LINE.find(line) ?: continue
            block.filterLines++
            if (isEmptySlot(line)) continue
            val filter = readFilter(line)
            if (filter != null) block.filters += filter
            else block.unread += head.groupValues[1].ifEmpty { block.filterLines.toString() }
        }

        val notes = ArrayList<String>()
        val global = blocks.first()
        val both = blocks.filter { it.side == Side.BOTH }
        val left = blocks.filter { it.side == Side.LEFT && it.filters.isNotEmpty() }
        val right = blocks.filter { it.side == Side.RIGHT && it.filters.isNotEmpty() }
        val other = blocks.filter { it.side == Side.OTHER && it.filters.isNotEmpty() }
        val chosen: List<Block>
        if (both.any { it.filters.isNotEmpty() }) {
            chosen = both
            if (left.isNotEmpty() || right.isNotEmpty()) notes += "left/right filters skipped (the file also has filters for both channels)"
        } else if (left.isNotEmpty()) {
            chosen = left
            if (right.isNotEmpty() && !sameEq(left, right, global.preamp)) notes += "left channel imported (file has separate L/R)"
        } else if (right.isNotEmpty()) {
            chosen = right
        } else if (other.isNotEmpty()) {
            // only blocks for other channels (C, SL, LFE ...): better than nothing, taken as both
            chosen = other
            notes += "channel ${other.joinToString(", ") { it.spec.trim() }} imported (the file has no L or R block)"
        } else {
            // no block has a filter that was read: the first one with unreadable lines is named in the notes
            chosen = listOf(blocks.firstOrNull { it.unread.isNotEmpty() } ?: global)
        }
        val preamp = chosen.lastOrNull { it.preamp != null }?.preamp ?: global.preamp
        val bands = chosen.flatMap { it.filters }.mapIndexed { i, r -> Band(newId(i), r.type, r.freqHz, r.gainDb, r.q, r.enabled) }
        // What could not be read is said, in the blocks that were taken (a right block left out keeps its own lines to itself).
        val unread = chosen.flatMap { it.unread }
        if (unread.size == 1) notes += "filter ${unread[0]} unreadable, skipped"
        else if (unread.size > 1) notes += "filters ${unread.joinToString(", ")} unreadable, skipped"
        // Said when the block whose Preamp lines decide (the last taken block that has one, else the lines before any Channel) ends on one that could not be
        // read - also after a readable one, whose value stays. A global line that a channel block's own preamp overrides is not named (review 18.11.2026).
        val preampBlock = chosen.lastOrNull { it.preamp != null || it.preampUnread } ?: global
        if (preampBlock.preampUnread) notes += "preamp unreadable, skipped"
        return ImportedEq(bands, preamp, notes)
    }

    private fun preampOf(blocks: List<Block>, global: Double?): Double? = blocks.lastOrNull { it.preamp != null }?.preamp ?: global

    private fun sameEq(a: List<Block>, b: List<Block>, globalPreamp: Double?): Boolean =
        a.flatMap { it.filters } == b.flatMap { it.filters } && preampOf(a, globalPreamp) == preampOf(b, globalPreamp)

    /** A filter that does nothing: a peak or shelf at 0 dB (squig.link and hangout files often carry all 10 slots). */
    fun doesNothing(b: Band) =
        !b.type.isPass && abs(b.gainDb) < 1e-9

    /** A filter that does nothing and has no frequency or Q to speak of (Fc 0, Q 0: the padding of a file that fills unused slots with zeros), as opposed to a 0 dB filter someone placed. */
    private fun isPadding(b: Band) =
        doesNothing(b) && !(b.freqHz.isFinite() && b.freqHz > 0.0 && b.q.isFinite() && b.q > 0.0)

    /**
     * The one filter kept from a file of only 0 dB filters (hangout.audio pads unused slots with zeros) must not block
     * a send for a frequency or Q the device cannot take: it does nothing at 0 dB, so it is moved into range. Only a
     * filter that does nothing is touched; one with gain is never altered.
     */
    private fun neutralInRange(b: Band): Band {
        if (!doesNothing(b)) return b
        val freq = if (b.freqHz.isFinite()) b.freqHz.coerceIn(20.0, 20000.0) else 1000.0
        val q = if (b.q.isFinite()) b.q.coerceIn(0.1, 10.0) else 1.0
        return if (freq == b.freqHz && q == b.q) b else b.copy(freqHz = freq, q = q)
    }

    /**
     * [parse] for importing a file into a profile: every filter stays, the ones at 0 dB included (each has its own frequency and Q, and a file written from a DAC read has them on
     * purpose); a 0 dB filter that cannot be sent as it stands is moved into range ([neutralInRange]). Only the padding of a file that fills its unused slots with zeros (Fc 0, Q 0) is
     * dropped and counted in the notes; a file made only of padding keeps its first filter, so an exported flat profile imports as a flat profile. A caller that has fewer bands than
     * the file has filters leaves out 0 dB ones itself, only as many as it takes (the app's Importer).
     */
    fun parseImport(text: String, newId: (Int) -> String = { "b${it + 1}" }): ImportedEq {
        val eq = parse(text, newId)
        val kept = eq.bands.filterNot(::isPadding).ifEmpty { eq.bands.take(1) }.map(::neutralInRange)
        val skipped = eq.bands.size - kept.size
        if (skipped == 0) return if (kept == eq.bands) eq else ImportedEq(kept, eq.preampDb, eq.notes)
        val note = "$skipped empty filter${if (skipped == 1) "" else "s"} skipped"
        return ImportedEq(kept.mapIndexed { i, b -> b.copy(id = newId(i)) }, eq.preampDb, eq.notes + note)
    }

    /**
     * At most [capacity] bands for a DAC that has fewer bands than the file has filters: while there are more, a filter that does nothing ([doesNothing]) is left out, the last one first, and
     * only as many as it takes. Filters with gain and pass filters are never left out. Returns the bands (in their order) and how many were left out; the count can leave the list over [capacity]
     * when there are not enough empty filters, which the caller reports as it does for any other overflow.
     */
    fun dropEmptyToFit(bands: List<Band>, capacity: Int): Pair<List<Band>, Int> {
        var extra = bands.size - capacity
        if (extra <= 0) return bands to 0
        val out = HashSet<Int>()
        for (i in bands.indices.reversed()) {
            if (extra == 0) break
            if (doesNothing(bands[i])) { out += i; extra-- }
        }
        return bands.filterIndexed { i, _ -> i !in out } to out.size
    }

    /** Shortest exact decimal with at least [minDecimals] and at most [maxDecimals] places. */
    private fun fmt(x: Double, minDecimals: Int, maxDecimals: Int): String {
        var d = BigDecimal.valueOf(x).setScale(maxDecimals, RoundingMode.HALF_UP).stripTrailingZeros()
        if (d.scale() < minDecimals) d = d.setScale(minDecimals)
        return d.toPlainString()
    }

    /**
     * A gain or preamp as written to a file: a value with at most two decimals exactly as before, any other value with
     * the shortest decimal that parses back to the same Double - never a rounded string (rounding up could make it
     * louder, and could change what the planner decides about it).
     */
    private fun fmtGain(x: Double): String {
        val d = BigDecimal.valueOf(x).stripTrailingZeros()
        return if (d.scale() <= 2) fmt(x, 1, 2) else d.toPlainString()
    }

    /**
     * APO text. Lossless for values with up to 2 decimals (Fc, gain) and 3 (Q); gains and preamp are exact for any value.
     * The shape is squig.link's export:
     * its import regex `Filter\s*\d+:\s*(\S+)\s*(\S+)\s*Fc\s*(\S+)\s*Hz\s*Gain\s*(\S+)\s*dB(\s*Q\s*(\S+))?`
     * matches every PK / LSC / HSC line. Low-, high-, band- and all-pass lines (LPQ / HPQ / BP / AP) have no Gain:
     * Equalizer APO reads them, squig.link skips them.
     */
    fun format(bands: List<Band>, preampDb: Double): String = buildString {
        append("Preamp: ").append(fmtGain(preampDb)).append(" dB\n")
        bands.forEachIndexed { i, b ->
            append("Filter ").append(i + 1).append(": ").append(if (b.enabled) "ON" else "OFF")
            append(' ').append(codeOf(b.type)).append(" Fc ").append(fmt(b.freqHz, 0, 2)).append(" Hz")
            if (!b.type.isPass) {
                append(" Gain ").append(fmtGain(b.gainDb)).append(" dB")
            }
            append(" Q ").append(fmt(b.q, 2, 3)).append('\n')
        }
    }

    /**
     * The text of a profile as the user means it (stored bands, stored or auto preamp) for SHARE and SAVE .TXT.
     * Never the device domain: the FiiO KA15 correction (shelf Q scaled, register preamp offset) is applied only
     * when sending, inside the codec, and is not part of the [Profile]. An AUTO preamp is computed with the shelves of [form], the one the attached DAC plays ([ShelfForm]), so the
     * exported "Preamp:" is the number the PREAMP row shows.
     */
    fun formatProfile(p: Profile, form: ShelfForm): String = format(p.bands, p.effectivePreampDb(form))

    /** The text of a `.txt` file: CRLF like squig.link's export (every reader here also takes plain LF). */
    fun fileText(p: Profile, form: ShelfForm): String = formatProfile(p, form).replace("\n", "\r\n")

    /**
     * Profile name from a file name: without the extension and without squig.link's trailing " Filters"
     * (`<model> Filters.txt`), upper case, at most [max] characters. Null when nothing is left.
     */
    fun nameFromFile(fileName: String?, max: Int): String? {
        var n = fileName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim() ?: return null
        if (n.endsWith(".txt", ignoreCase = true)) n = n.dropLast(4)
        n = n.trim()
        if (n.endsWith(" filters", ignoreCase = true)) n = n.dropLast(8)
        return n.trim().uppercase().take(max).trim().ifEmpty { null }
    }

    /** `<NAME> Filters.txt`, squig.link's convention; characters that file systems refuse become spaces. */
    fun fileNameFor(profileName: String): String {
        val clean = profileName.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").trim().replace(Regex("""\s+"""), " ")
        return clean.ifEmpty { "Profile" } + " Filters.txt"
    }
}

/** EQ by Ear session JSON (the .json files in eq-library, one folder per IEM): parse only. */
object EqByEarJson {
    @Serializable
    private data class FileBand(
        val type: String,
        val fc: Double,
        val gain: Double = 0.0,
        val q: Double = ApoText.DEFAULT_Q,
        val enabled: Boolean = true,
    )

    @Serializable
    private data class FileSession(
        val bands: List<FileBand> = emptyList(),
        val preampDb: Double? = null,
        val preampAuto: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Unknown filter types are skipped. `preampAuto: true` gives preampDb = null (auto). */
    fun parse(text: String, newId: (Int) -> String = { "b${it + 1}" }): ImportedEq {
        val s = json.decodeFromString(FileSession.serializer(), text)
        val bands = ArrayList<Band>()
        for (b in s.bands) {
            val type = ApoText.typeOf(b.type) ?: continue
            bands += Band(newId(bands.size), type, b.fc, if (type.isPass) 0.0 else b.gain, b.q, b.enabled)
        }
        return ImportedEq(bands, if (s.preampAuto) null else s.preampDb)
    }
}
