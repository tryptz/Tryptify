package tf.monochrome.android.audio.eq

import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.domain.model.FrequencyPoint
import java.util.Locale

/**
 * Parser for frequency response data and parametric EQ formats.
 *
 * Handles:
 * - Raw measurement data (CSV/TXT format with frequency and gain columns)
 * - Parametric EQ filter notation (Preamp, Filter lines)
 * - Multiple delimiters (comma, semicolon, tab, whitespace)
 * - European number format (comma as decimal separator)
 */
object EqDataParser {

    /**
     * Parse raw frequency response measurement data
     *
     * Supports:
     * - Header detection (looks for 'freq'/'frequency' and 'raw'/'spl'/'gain'/'db' columns)
     * - Multiple delimiters (semicolon, comma, tab, whitespace)
     * - European format (comma decimals)
     *
     * @param rawData Raw text data with frequency/gain pairs
     * @return List of FrequencyPoint objects sorted by frequency
     */
    fun parseRawData(rawData: String): List<FrequencyPoint> {
        if (rawData.isEmpty()) return emptyList()

        val lines = rawData.trim().split('\n')
        if (lines.isEmpty()) return emptyList()

        val firstLine = lines[0].trim()

        // Determine delimiter
        val delimiter = when {
            firstLine.contains(';') -> ";"
            firstLine.contains(',') -> ","
            firstLine.contains('\t') -> "\t"
            else -> "\\s+"
        }

        // Detect columns
        var freqIdx = 0
        var gainIdx = 1

        val hasHeader = firstLine.contains(Regex("[a-zA-Z]"))
        if (hasHeader) {
            val headerPattern = if (delimiter == "\\s+") Regex("\\s+") else Regex(Regex.escape(delimiter))
            val headers = firstLine.split(headerPattern).map { h ->
                h.trim().lowercase().replace(Regex("['\"]"), "")
            }

            val fIdx = headers.indexOfFirst { it.contains("freq") || it == "f" }
            if (fIdx >= 0) freqIdx = fIdx

            val rIdx = headers.indexOfFirst { it == "raw" }
            if (rIdx >= 0) {
                gainIdx = rIdx
            } else {
                val splIdx = headers.indexOfFirst {
                    it.contains("spl") || it.contains("gain") || it.contains("db") || it.contains("mag")
                }
                if (splIdx >= 0 && splIdx != freqIdx) gainIdx = splIdx
            }
        }

        val points = mutableListOf<FrequencyPoint>()
        val dataLines = if (hasHeader) lines.drop(1) else lines

        for (line in dataLines) {
            val cleanLine = line.trim()
            if (cleanLine.isEmpty() || !cleanLine[0].isDigit() && cleanLine[0] != '-' && cleanLine[0] != '.') {
                continue
            }

            val parts = if (delimiter == "\\s+") {
                cleanLine.split(Regex("\\s+"))
            } else {
                cleanLine.split(delimiter)
            }

            if (parts.size <= maxOf(freqIdx, gainIdx)) continue

            var freqStr = parts[freqIdx].trim()
            var gainStr = parts[gainIdx].trim()

            // Handle European format
            if (delimiter != ",") {
                if (freqStr.contains(',')) freqStr = freqStr.replace(',', '.')
                if (gainStr.contains(',')) gainStr = gainStr.replace(',', '.')
            }

            val freq = freqStr.toFloatOrNull()
            val gain = gainStr.toFloatOrNull()

            if (freq != null && gain != null) {
                points.add(FrequencyPoint(freq, gain))
            }
        }

        return points.sortedBy { it.freq }
    }

}
