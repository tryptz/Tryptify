package tf.monochrome.android.audio.stretch

/**
 * Which algorithm performs an independent transposition.
 *
 * Two engines rather than one, because they fail in opposite directions and no
 * single setting covers both. The vocoder resolves a partial to a fraction of a
 * hertz and smears attacks across its analysis block; WSOLA splices in the time
 * domain at waveform-similar points, so an attack survives, and sustained
 * polyphony picks up the phasiness the vocoder avoids. Which one is "better"
 * depends entirely on the record, so it is the listener's choice.
 */
enum class PitchEngine(val nativeId: Int, val label: String, val summary: String) {
    /** signalsmith-stretch, 0.35 s block. Accurate on sustained tones. */
    VOCODER(0, "Smooth", "Best on sustained, melodic material. Softens attacks."),

    /** WSOLA. Keeps transients intact, which is what percussion needs. */
    WSOLA(1, "Punchy", "Keeps drum attacks intact. Can sound phasey on pads."),
    ;

    companion object {
        fun fromName(name: String?): PitchEngine =
            entries.firstOrNull { it.name == name } ?: VOCODER
    }
}

/**
 * How much CPU the WSOLA stretcher may spend, and what it can hold together.
 *
 * The search radius decides the lowest frequency that survives a splice --
 * roughly `sampleRate / radius`. Below that floor a note still plays but its
 * pitch wanders, because the splices land mid-cycle and eat or add partial
 * periods: a 110 Hz bass through FAST measures about 8% flat. Ignored by
 * [PitchEngine.VOCODER], which has no grain.
 */
enum class PitchQuality(
    val nativeId: Int,
    val label: String,
    /** Lowest frequency the splice search can hold together, at 48 kHz. */
    val bassFloorHz: Int,
) {
    FAST(0, "Fast", 375),
    BALANCED(1, "Balanced", 188),
    HIGH(2, "High", 94),
    ;

    companion object {
        fun fromName(name: String?): PitchQuality =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}
