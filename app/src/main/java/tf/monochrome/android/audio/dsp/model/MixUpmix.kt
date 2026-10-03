package tf.monochrome.android.audio.dsp.model

/**
 * The mix's Atmos upmix switch, carried in its state JSON as a top-level
 * `"upmix":"9.1.6"` after the bus list.
 *
 * In the state JSON rather than a preference of its own, so it travels with
 * everything that moves a mix — the saved mix, presets, export and import,
 * cloud sync — without any of them knowing about it. It is attached and
 * stripped on the Kotlin side ([tf.monochrome.android.audio.dsp.DspEngineManager]),
 * so the native engine never sees it, and a build from before it simply
 * ignores the key: the engine's parser walks from one bus's `{"gain":` to the
 * next and never reaches a top-level key after them.
 */
object MixUpmix {
    const val LAYOUT = "9.1.6"

    private val KEY = Regex("""\s*,?\s*"upmix"\s*:\s*"[^"]*"\s*""")
    private val ON = Regex(""""upmix"\s*:\s*"${Regex.escape(LAYOUT)}"""")

    /** Whether [stateJson] asks for the upmix. */
    fun isOn(stateJson: String): Boolean = ON.containsMatchIn(stateJson)

    /** [stateJson] without the key, for the engine. */
    fun strip(stateJson: String): String = KEY.replace(stateJson, "")

    /** [stateJson] with the key set to [on] (absent when off). */
    fun attach(stateJson: String, on: Boolean): String {
        val bare = strip(stateJson).trimEnd()
        if (!on || !bare.endsWith("}")) return bare
        val body = bare.dropLast(1).trimEnd()
        val sep = if (body.endsWith("{")) "" else ","
        return "$body$sep\"upmix\":\"$LAYOUT\"}"
    }
}
