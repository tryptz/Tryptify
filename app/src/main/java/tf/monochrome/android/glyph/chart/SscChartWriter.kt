// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.chart

import java.util.Locale
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty

/**
 * Writes the map editor's work into a simfile.
 *
 * An edit touches one chart, so the file is rewritten around it rather than
 * regenerated: every other difficulty, every header tag and every tag this app
 * does not know is kept exactly as written. Only the edited chart's `#NOTES`
 * (and the tempo, when the editor changed it) is replaced; a difficulty the
 * file does not have yet is added at the end.
 */
object SscChartWriter {

    /** [text] with [difficulty]'s note data replaced by [noteData], or added. */
    fun withChart(
        text: String,
        difficulty: StepManiaDifficulty,
        meter: Int,
        noteData: String,
    ): String {
        val tags = tagsOf(text)
        // The chart blocks: each starts at a #NOTEDATA and runs to the next.
        val starts = tags.indices.filter { tags[it].key == "NOTEDATA" }
        for ((n, start) in starts.withIndex()) {
            val end = starts.getOrNull(n + 1) ?: tags.size
            val block = tags.subList(start, end)
            val label = block.firstOrNull { it.key == "DIFFICULTY" }?.value ?: continue
            if (difficultyOf(label) != difficulty) continue
            val notes = block.firstOrNull { it.key == "NOTES" } ?: continue
            return text.replaceRange(notes.valueStart, notes.valueEnd, "\n" + noteData)
        }
        return buildString {
            append(text.trimEnd())
            append("\n\n")
            append(chartBlock(difficulty, meter, noteData))
        }
    }

    /**
     * [text] at one tempo, [bpm], with beat zero at [beatZeroSeconds] in the
     * audio. For a song with a single tempo only: one with tempo changes keeps
     * its own map, which the editor does not touch.
     */
    fun withTempo(text: String, bpm: Float, beatZeroSeconds: Float): String {
        var out = text
        // StepMania's #OFFSET is the seconds the chart leads the audio: the
        // opposite sign of where beat zero is (see GlyphTiming).
        out = replaceHeaderTag(out, "OFFSET", decimal(-beatZeroSeconds))
        out = replaceHeaderTag(out, "BPMS", "0.000=${decimal(bpm)}")
        out = replaceHeaderTag(out, "DISPLAYBPM", decimal(bpm))
        return out
    }

    /** A new simfile for a song that has none, holding the one chart. */
    fun newSimfile(
        title: String,
        artist: String,
        musicFileName: String,
        bpm: Float,
        beatZeroSeconds: Float,
        difficulty: StepManiaDifficulty,
        meter: Int,
        noteData: String,
    ): String = buildString {
        append("#VERSION:0.83;\n")
        append("#TITLE:${tag(title)};\n")
        append("#ARTIST:${tag(artist)};\n")
        append("#CREDIT:Trypt Mania map editor;\n")
        append("#MUSIC:${tag(musicFileName)};\n")
        append("#OFFSET:${decimal(-beatZeroSeconds)};\n")
        append("#BPMS:0.000=${decimal(bpm)};\n")
        append("#DISPLAYBPM:${decimal(bpm)};\n\n")
        append(chartBlock(difficulty, meter, noteData))
    }

    private fun chartBlock(difficulty: StepManiaDifficulty, meter: Int, noteData: String): String = buildString {
        append("#NOTEDATA:;\n")
        append("#CHARTNAME:${difficulty.sscName};\n")
        append("#STEPSTYPE:dance-single;\n")
        append("#DESCRIPTION:Made in the map editor;\n")
        append("#CHARTSTYLE:;\n")
        append("#DIFFICULTY:${difficulty.sscName};\n")
        append("#METER:$meter;\n")
        append("#RADARVALUES:;\n")
        append("#NOTES:\n")
        append(noteData)
        append(";\n")
    }

    /** The header's [key] set to [value], added before the first chart if it is missing. */
    private fun replaceHeaderTag(text: String, key: String, value: String): String {
        val tags = tagsOf(text)
        val firstChart = tags.firstOrNull { it.key == "NOTEDATA" }?.tagStart ?: text.length
        val existing = tags.firstOrNull { it.key == key && it.tagStart < firstChart }
        if (existing != null) return text.replaceRange(existing.valueStart, existing.valueEnd, value)
        return text.substring(0, firstChart) + "#$key:$value;\n" + text.substring(firstChart)
    }

    private class Tag(val key: String, val value: String, val tagStart: Int, val valueStart: Int, val valueEnd: Int)

    /** `#KEY:value;` spans, read the way [SscParser] reads them. */
    private fun tagsOf(text: String): List<Tag> {
        val out = ArrayList<Tag>()
        var index = 0
        while (index < text.length) {
            val hash = text.indexOf('#', index)
            if (hash < 0) break
            val colon = text.indexOf(':', hash + 1)
            if (colon < 0) break
            val semicolon = text.indexOf(';', colon + 1)
            if (semicolon < 0) break
            out += Tag(
                key = text.substring(hash + 1, colon).trim().uppercase(Locale.ROOT),
                value = text.substring(colon + 1, semicolon),
                tagStart = hash,
                valueStart = colon + 1,
                valueEnd = semicolon,
            )
            index = semicolon + 1
        }
        return out
    }

    /** The difficulty a #DIFFICULTY label names, with the aliases [SscParser] accepts. */
    private fun difficultyOf(label: String): StepManiaDifficulty? {
        val name = label.trim()
        return StepManiaDifficulty.entries.firstOrNull {
            it.sscName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true)
        } ?: when (name.lowercase(Locale.ROOT)) {
            "edit", "insane", "expert" -> StepManiaDifficulty.CHALLENGE
            "novice", "basic" -> StepManiaDifficulty.EASY
            else -> null
        }
    }

    private fun tag(value: String): String = value
        .replace(';', ',')
        .replace('#', ' ')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()

    private fun decimal(value: Float): String = String.format(Locale.US, "%.3f", value)
}
