// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.chart

import tf.monochrome.android.audio.stepmania.CourseType
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.asset.GlyphBeatDivision
import tf.monochrome.android.glyph.asset.GlyphLane

/**
 * Reads an SSC back into something playable.
 *
 * The mode deliberately consumes the same file the generator writes rather than
 * carrying the in-memory `StepChart` straight across. Two reasons: a chart the
 * player generated last week is a file on disk with no objects behind it, and a
 * parser that reads real SSC means a simfile from anywhere else plays too.
 *
 * Tolerant by design. Unknown tags are skipped, an unparseable chart is dropped
 * rather than taking the file down with it, and a row of the wrong width is
 * padded or trimmed — a partly-broken simfile should cost the player one
 * difficulty, not the song.
 */
object SscParser {

    private const val BEATS_PER_MEASURE = 4f

    /**
     * The simfile's charts of one [course]: dance's four-panel charts, or
     * pop'n's nine-button ones. A file can hold both, as StepMania's can,
     * and each course reads its own; null when it has none of that course.
     */
    fun parse(text: String, course: CourseType = CourseType.DANCE): GlyphSimfile? {
        // An .sm, or comments in an .ssc, are rewritten into plain .ssc first.
        val tags = readTags(SmConverter.toSsc(text))

        val bpms = parseBeatValues(tags.header["BPMS"]).map {
            GlyphTiming.BpmSegment(it.first, it.second)
        }
        val stops = parseBeatValues(tags.header["STOPS"]).map {
            GlyphTiming.Stop(it.first, it.second)
        }
        // StepMania's OFFSET is the seconds the chart leads the audio, so the
        // sign flips exactly here and nowhere else downstream.
        val offsetSeconds = -(tags.header["OFFSET"]?.trim()?.toFloatOrNull() ?: 0f)
        val timing = GlyphTiming(offsetSeconds, bpms, stops)

        val charts = tags.charts
            // This course's charts only. A pack's dance-double (eight lanes)
            // or pump (five) chart squeezed into four would play as something
            // nobody wrote; an unnamed type is taken as the default,
            // dance-single, and pop'n's pnm-nine is read as pop'n.
            .filter { chart -> course.matchesStepsType(chart["STEPSTYPE"]) }
            .mapNotNull { chart -> parseChart(chart, timing, course)?.let { it to isExactDifficulty(chart["DIFFICULTY"]) } }
            // One chart per difficulty. A pack can carry several Edits, or an
            // Edit beside a real Challenge, and they all land on Challenge:
            // the one that is named so wins, then the first.
            .groupBy { it.first.difficulty }
            .map { (_, same) -> (same.firstOrNull { it.second } ?: same.first()).first }
        if (charts.isEmpty()) return null

        return GlyphSimfile(
            title = tags.header["TITLE"].orEmpty().trim().ifEmpty { "Untitled" },
            artist = tags.header["ARTIST"].orEmpty().trim(),
            musicFileName = tags.header["MUSIC"].orEmpty().trim(),
            credit = tags.header["CREDIT"].orEmpty().trim(),
            sampleStartSeconds = tags.header["SAMPLESTART"]?.trim()?.toFloatOrNull()?.takeIf { it >= 0f },
            sampleLengthSeconds = tags.header["SAMPLELENGTH"]?.trim()?.toFloatOrNull()?.takeIf { it > 0f },
            displayBpm = GlyphDisplayBpm.parse(tags.header["DISPLAYBPM"]),
            timing = timing,
            charts = charts.sortedBy { it.difficulty.ordinal },
        )
    }

    private fun parseChart(
        raw: Map<String, String>,
        songTiming: GlyphTiming,
        course: CourseType,
    ): GlyphChart? {
        val noteData = raw["NOTES"] ?: return null

        // SSC lets a chart override the song's timing. When it does, the chart
        // gets its own map; when it does not, it shares the song's — sharing is
        // what keeps two difficulties of one song in agreement.
        val chartBpms = parseBeatValues(raw["BPMS"]).map {
            GlyphTiming.BpmSegment(it.first, it.second)
        }
        val timing = if (chartBpms.isEmpty()) songTiming else {
            GlyphTiming(
                offsetSeconds = raw["OFFSET"]?.trim()?.toFloatOrNull()?.let { -it }
                    ?: songTiming.offsetSeconds,
                segments = chartBpms,
                stops = parseBeatValues(raw["STOPS"]).map {
                    GlyphTiming.Stop(it.first, it.second)
                },
            )
        }

        val notes = parseNotes(noteData, timing, course) ?: return null
        val difficulty = difficultyOf(raw["DIFFICULTY"]) ?: return null

        return GlyphChart(
            difficulty = difficulty,
            meter = raw["METER"]?.trim()?.toIntOrNull() ?: course.meter(difficulty),
            chartName = raw["CHARTNAME"]?.trim()?.ifEmpty { null }
                ?: raw["DESCRIPTION"]?.trim()?.ifEmpty { null }
                ?: difficulty.sscName,
            stepsType = raw["STEPSTYPE"]?.trim()?.ifEmpty { null } ?: course.stepsType,
            notes = notes,
            course = course,
        )
    }

    private fun parseNotes(data: String, timing: GlyphTiming, course: CourseType): List<GlyphNote>? {
        // The columns, in SSC order: four for dance, nine for pop'n.
        val lanes = GlyphLane.forCourse(course)
        val measures = data.split(',')
        val notes = ArrayList<GlyphNote>()
        // Holds are opened on their head and closed on the next '3' in the same
        // lane, so an unterminated hold is visible as a leftover here rather
        // than as a note that never ends during play.
        val openHolds = arrayOfNulls<PendingHold>(lanes.size)

        for ((measureIndex, measure) in measures.withIndex()) {
            val rows = measure.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (rows.isEmpty()) continue

            for ((rowIndex, row) in rows.withIndex()) {
                val beat = (measureIndex + rowIndex.toFloat() / rows.size) * BEATS_PER_MEASURE
                val seconds = timing.beatToSeconds(beat)
                val division = GlyphBeatDivision.forRow(rowIndex, rows.size)

                for (lane in lanes.indices) {
                    // A row shorter than the lane count is padded with '0'
                    // rather than throwing: a truncated line should cost the
                    // notes it lost, not the chart.
                    val character = row.getOrNull(lane) ?: '0'
                    when (character) {
                        '0' -> Unit
                        '3' -> {
                            val pending = openHolds[lane] ?: continue
                            openHolds[lane] = null
                            notes += pending.close(seconds)
                        }
                        else -> {
                            val type = GlyphNoteType.entries
                                .firstOrNull { it.sscCharacter == character.uppercaseChar() }
                                ?: continue
                            if (type.hasTail) {
                                openHolds[lane] = PendingHold(
                                    lane = lanes[lane],
                                    type = type,
                                    beat = beat,
                                    startSeconds = seconds,
                                    division = division,
                                    measure = measureIndex,
                                )
                            } else {
                                notes += GlyphNote(
                                    lane = lanes[lane],
                                    type = type,
                                    beat = beat,
                                    timeSeconds = seconds,
                                    endTimeSeconds = seconds,
                                    division = division,
                                    measure = measureIndex,
                                )
                            }
                        }
                    }
                }
            }
        }

        // A hold with no tail is played as a tap. Dropping it would silently
        // remove a note the chart plainly asks for.
        for (pending in openHolds) {
            if (pending != null) notes += pending.close(pending.startSeconds)
        }

        if (notes.isEmpty()) return null
        return notes.sortedWith(compareBy({ it.timeSeconds }, { it.lane.ordinal }))
    }

    private class PendingHold(
        val lane: GlyphLane,
        val type: GlyphNoteType,
        val beat: Float,
        val startSeconds: Float,
        val division: GlyphBeatDivision,
        val measure: Int,
    ) {
        fun close(endSeconds: Float) = GlyphNote(
            lane = lane,
            type = if (endSeconds <= startSeconds) GlyphNoteType.TAP else type,
            beat = beat,
            timeSeconds = startSeconds,
            endTimeSeconds = maxOf(endSeconds, startSeconds),
            division = division,
            measure = measure,
        )
    }

    private fun isExactDifficulty(value: String?): Boolean {
        val name = value?.trim().orEmpty()
        return StepManiaDifficulty.entries.any { it.sscName.equals(name, ignoreCase = true) }
    }

    private fun difficultyOf(value: String?): StepManiaDifficulty? {
        val name = value?.trim().orEmpty()
        if (name.isEmpty()) return null
        return StepManiaDifficulty.entries.firstOrNull {
            it.sscName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true)
        // "Edit" and "Insane" are common aliases for the hardest tier; mapping
        // them beats refusing to load a chart over its label.
        } ?: when (name.lowercase()) {
            // The names older simfiles use, mapped as StepMania maps them.
            "edit", "insane", "expert", "smaniac", "oni" -> StepManiaDifficulty.CHALLENGE
            "heavy", "maniac", "another" -> StepManiaDifficulty.HARD
            "standard", "trick", "normal" -> StepManiaDifficulty.MEDIUM
            "novice", "basic", "light" -> StepManiaDifficulty.EASY
            else -> null
        }
    }

    /** `0.000=150.000,32.000=170.000` to beat/value pairs. */
    private fun parseBeatValues(raw: String?): List<Pair<Float, Float>> =
        raw?.split(',')
            ?.mapNotNull { entry ->
                val parts = entry.split('=')
                if (parts.size != 2) return@mapNotNull null
                val beat = parts[0].trim().toFloatOrNull() ?: return@mapNotNull null
                val value = parts[1].trim().toFloatOrNull() ?: return@mapNotNull null
                beat to value
            }
            .orEmpty()

    private class Tags(
        val header: Map<String, String>,
        val charts: List<Map<String, String>>,
    )

    /**
     * Splits `#TAG:value;` pairs into a song header and one map per chart.
     *
     * `#NOTEDATA:;` starts a chart, so anything after the first one belongs to
     * a chart rather than the song — which is how a per-chart `#BPMS` override
     * stays distinguishable from the song's own.
     */
    private fun readTags(text: String): Tags {
        val header = LinkedHashMap<String, String>()
        val charts = ArrayList<MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null

        var index = 0
        while (index < text.length) {
            val hash = text.indexOf('#', index)
            if (hash < 0) break
            val colon = text.indexOf(':', hash + 1)
            if (colon < 0) break
            // Values can contain ':' (note data does not, but descriptions do),
            // so the terminator is the semicolon, not the next colon.
            val semicolon = text.indexOf(';', colon + 1)
            if (semicolon < 0) break

            val key = text.substring(hash + 1, colon).trim().uppercase()
            val value = text.substring(colon + 1, semicolon)

            if (key == "NOTEDATA") {
                current = LinkedHashMap()
                charts += current
            } else if (current != null) {
                current[key] = value
            } else {
                header[key] = value
            }
            index = semicolon + 1
        }

        return Tags(header, charts)
    }
}
