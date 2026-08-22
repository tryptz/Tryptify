// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.asset

/**
 * Every glyph the mode can draw, as a closed set of names.
 *
 * The pack's README asks that gameplay resolve assets "through the manifest or
 * an equivalent typed mapping instead of constructing filenames ad hoc". This
 * is that mapping: a lane and a beat subdivision produce a [GlyphAssetId], the
 * id produces a manifest entry, and the manifest entry produces a file. Nothing
 * between the playfield and the disk concatenates a string, so a typo is a
 * compile error and a missing file is a load-time failure with a name in it.
 */
@JvmInline
value class GlyphAssetId(val name: String)

/** The four lanes, in SSC column order. */
enum class GlyphLane(val assetSuffix: String, val label: String) {
    LEFT("left", "Left"),
    DOWN("down", "Down"),
    UP("up", "Up"),
    RIGHT("right", "Right"),
    ;

    companion object {
        /** Left/right and up/down swapped — the mirror modifier. */
        fun mirrorOf(lane: GlyphLane): GlyphLane = when (lane) {
            LEFT -> RIGHT
            RIGHT -> LEFT
            UP -> DOWN
            DOWN -> UP
        }
    }
}

/**
 * The rhythmic subdivision a note falls on.
 *
 * Colour comes from this rather than from the lane, so the palette reads as
 * rhythm. [label] is the accessible name; the mode never relies on the hue
 * alone to say what a note is.
 */
enum class GlyphBeatDivision(
    val assetSuffix: String,
    val paletteKey: String,
    val label: String,
    /** Notes per beat this subdivision represents. */
    val perBeat: Int,
) {
    QUARTER("4th", "4th", "Quarter", 1),
    EIGHTH("8th", "8th", "Eighth", 2),
    TWELFTH("12th", "12th", "Twelfth", 3),
    SIXTEENTH("16th", "16th", "Sixteenth", 4),
    TWENTY_FOURTH("24th", "24th", "Twenty-fourth", 6),
    THIRTY_SECOND("32nd", "32nd", "Thirty-second", 8),
    FORTY_EIGHTH("48th", "48th", "Forty-eighth", 12),
    SIXTY_FOURTH("64th", "64th", "Sixty-fourth", 16),
    ;

    companion object {
        /**
         * The subdivision a row lands on, from its position within a measure.
         *
         * [rowsPerMeasure] is the note data's own resolution. The coarsest
         * subdivision that still lands exactly on the row wins, which is what
         * makes a quarter note red rather than yellow in a 16th-resolution
         * chart. Anything finer than a 64th is drawn as a 64th rather than
         * dropped — the pack has no smaller colour and an uncoloured note
         * would be worse than a slightly-wrong one.
         */
        fun forRow(rowInMeasure: Int, rowsPerMeasure: Int): GlyphBeatDivision {
            if (rowsPerMeasure <= 0) return QUARTER
            for (division in entries) {
                // A measure is four beats, so a subdivision of `perBeat` notes
                // per beat lands every rowsPerMeasure / (4 * perBeat) rows —
                // but only when that divides exactly. Without the exactness
                // check, integer division rounds the stride down to 1 for a
                // subdivision the measure cannot express (12ths in a 16-row
                // measure) and every row matches it.
                val rowsPerNote = 4 * division.perBeat
                if (rowsPerMeasure % rowsPerNote != 0) continue
                val stride = rowsPerMeasure / rowsPerNote
                if (stride >= 1 && rowInMeasure % stride == 0) return division
            }
            return SIXTY_FOURTH
        }
    }
}

/** Notes that are not a plain tap. */
enum class GlyphSpecialNote(val assetName: String, val label: String) {
    MINE("mine", "Mine"),
    LIFT("lift", "Lift"),
    FAKE("fake", "Fake"),
    SHOCK("shock", "Shock"),
}

/** The judgement wordmarks, best to worst. */
enum class GlyphJudgementArt(val assetName: String) {
    MARVELOUS("judgement_marvelous"),
    PERFECT("judgement_perfect"),
    GREAT("judgement_great"),
    GOOD("judgement_good"),
    BOO("judgement_boo"),
    MISS("judgement_miss"),
}

/** The grade badges. */
enum class GlyphGradeArt(val assetName: String) {
    SSS("grade_sss"),
    SS("grade_ss"),
    S("grade_s"),
    A("grade_a"),
    B("grade_b"),
    C("grade_c"),
    D("grade_d"),
    FAILED("grade_failed"),
}

/** Feedback overlays drawn above the receptors. */
enum class GlyphEffectArt(val assetName: String) {
    TAP_EXPLOSION("tap_explosion"),
    HOLD_GLOW("hold_glow"),
    MISS_CRACK("miss_crack"),
    COMBO_BURST("combo_burst"),
}

/** Tintable interface icons. Names match the pack one-for-one. */
enum class GlyphIcon(val assetName: String) {
    ARROW_BACK("arrow_back"),
    CLOSE("close"),
    PLAY("play"),
    PAUSE("pause"),
    RESTART("restart"),
    LOOP("loop"),
    LOOP_SEGMENT("loop_segment"),
    SPEED("speed"),
    METRONOME("metronome"),
    WAVEFORM("waveform"),
    GHOST("ghost"),
    TARGET("target"),
    HITBOX("hitbox"),
    TIMING_WINDOW("timing_window"),
    CHART("chart"),
    ACCURACY_GRAPH("accuracy_graph"),
    TIMER("timer"),
    SETTINGS("settings"),
    FILTER("filter"),
    BOOKMARK("bookmark"),
    CHALLENGE("challenge"),
    TROPHY("trophy"),
    CALIBRATE("calibrate"),
    AUDIO_STEM("audio_stem"),
    MUSIC_NOTE("music_note"),
    STEP_CHART("step_chart"),
    MIRROR("mirror"),
    SHUFFLE("shuffle"),
    CHEVRON_LEFT("chevron_left"),
    CHEVRON_RIGHT("chevron_right"),
    EXPAND("expand"),
    INFO("info"),
    ACCESSIBILITY("accessibility"),
}

/** Tintable decoration. Used sparingly — see the mode's visual direction. */
enum class GlyphDecor(val assetName: String) {
    GRID_TILE("grid_tile"),
    PANEL_CORNERS("panel_corners"),
    SCANLINE_DIVIDER("scanline_divider"),
    FOCUS_BRACKETS("focus_brackets"),
    TIMELINE_TICKS("timeline_ticks"),
    ACCURACY_SPARKLINE("accuracy_sparkline"),
}

/**
 * Semantic asset lookup.
 *
 * Every accessor returns a [GlyphAssetId] whose name is guaranteed to exist in
 * a well-formed pack; `GlyphAssetCatalogTest` asserts that by resolving all of
 * them against the shipped manifest and the files on disk.
 */
object GlyphAssetCatalog {

    fun receptor(lane: GlyphLane, active: Boolean): GlyphAssetId =
        GlyphAssetId("receptor_${lane.assetSuffix}" + if (active) "_active" else "")

    fun tap(lane: GlyphLane, division: GlyphBeatDivision): GlyphAssetId =
        GlyphAssetId("tap_${lane.assetSuffix}_${division.assetSuffix}")

    /** The uncoloured tap, for a chart whose resolution says nothing useful. */
    fun tapDefault(lane: GlyphLane): GlyphAssetId = GlyphAssetId("tap_${lane.assetSuffix}")

    fun holdHead(lane: GlyphLane, roll: Boolean): GlyphAssetId =
        GlyphAssetId(if (roll) "roll_head_${lane.assetSuffix}" else "hold_head_${lane.assetSuffix}")

    fun holdBody(roll: Boolean): GlyphAssetId = GlyphAssetId(if (roll) "roll_body" else "hold_body")

    fun holdTail(roll: Boolean): GlyphAssetId = GlyphAssetId(if (roll) "roll_tail" else "hold_tail")

    fun special(note: GlyphSpecialNote): GlyphAssetId = GlyphAssetId(note.assetName)

    fun judgement(art: GlyphJudgementArt): GlyphAssetId = GlyphAssetId(art.assetName)

    fun grade(art: GlyphGradeArt): GlyphAssetId = GlyphAssetId(art.assetName)

    fun effect(art: GlyphEffectArt): GlyphAssetId = GlyphAssetId(art.assetName)

    fun icon(icon: GlyphIcon): GlyphAssetId = GlyphAssetId(icon.assetName)

    fun decor(decor: GlyphDecor): GlyphAssetId = GlyphAssetId(decor.assetName)

    /**
     * Everything the playfield needs before a chart can start.
     *
     * Prewarming is per-difficulty because a Beginner chart never shows a 48th
     * note and there is no reason to rasterize one. Receptors, holds and the
     * feedback overlays are unconditional: they are on screen from the first
     * frame.
     */
    fun playfieldAssets(divisions: Set<GlyphBeatDivision>): List<GlyphAssetId> = buildList {
        for (lane in GlyphLane.entries) {
            add(receptor(lane, active = false))
            add(receptor(lane, active = true))
            add(tapDefault(lane))
            for (division in divisions) add(tap(lane, division))
            add(holdHead(lane, roll = false))
            add(holdHead(lane, roll = true))
        }
        add(holdBody(roll = false))
        add(holdTail(roll = false))
        add(holdBody(roll = true))
        add(holdTail(roll = true))
        for (note in GlyphSpecialNote.entries) add(special(note))
        for (art in GlyphJudgementArt.entries) add(judgement(art))
        for (art in GlyphEffectArt.entries) add(effect(art))
    }
}
