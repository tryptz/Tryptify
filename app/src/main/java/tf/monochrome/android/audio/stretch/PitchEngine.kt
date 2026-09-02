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
 * How much work either engine may spend per second of audio.
 *
 * It means something different to each, and both are real. For WSOLA it is the
 * grain and its search radius, and the radius decides the lowest frequency that
 * survives a splice -- below that floor a note still plays but its pitch
 * wanders, because the splices land mid-cycle: 110 Hz through FAST measures
 * about 8% flat. For the vocoder it is the analysis block, which decides how
 * finely a partial can be resolved and how large an FFT runs on every hop.
 *
 * That second meaning is new, and it is why this exists at all past WSOLA. The
 * vocoder's block was picked for accuracy alone, at nearly three times
 * upstream's own default, and the measurement that chose it never asked what it
 * cost -- which on a phone is a dropout.
 */
enum class PitchQuality(
    val nativeId: Int,
    val label: String,
    /** WSOLA: lowest frequency the splice search can hold together, at 48 kHz. */
    val bassFloorHz: Int,
    /** Vocoder: worst-case pitch error over 82 Hz..3.5 kHz, in hertz. */
    val vocoderErrorHz: String,
) {
    FAST(0, "Fast", 375, "1.40 Hz"),
    BALANCED(1, "Balanced", 188, "0.42 Hz"),
    HIGH(2, "High", 94, "0.18 Hz"),
    ;

    companion object {
        fun fromName(name: String?): PitchQuality =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}
