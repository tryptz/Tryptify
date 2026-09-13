package tf.monochrome.android.audio.dsp.preset

import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.audio.dsp.model.MixPreset

/**
 * Hard-coded showcase presets for the DSP Mixer.
 *
 * These ship with the app so a fresh install demonstrates the engine's reverb,
 * delay, modulation and dynamics processors. They use negative ids so they
 * never collide with Room's positive autoincrement, and `isCustom = false` so
 * the UI treats them as read-only (load, export — but not delete).
 */
object BuiltInMixPresets {

    /**
     * A preset captured out of the mixer rather than written in the DSL below,
     * kept as the engine's own state JSON so it is exactly what was tuned.
     *
     * It has to be raw: [PresetScope] cannot describe it. The builder decides
     * `inputEnabled` from the bus index (only bus 0 takes input) and writes
     * `bypassed` as false for every processor, and this preset needs input on
     * TWO buses and two bypassed processors on the first. That is the shape of
     * it -- a dry path and a wet path running in parallel off the same input:
     *
     *   bus 0  the dry side, trimmed a hair, with a Haas and a Stereo parked on
     *          it bypassed (set up, switched off -- kept because they are part
     *          of the patch as saved)
     *   bus 1  the wet side: a long, wide Reverb, then Stereo, then Gain, run
     *          up +8.2 dB to sit against the dry
     *   bus 4  master, +4.6 dB
     *
     * Re-expressing it in the DSL would mean transcribing sixteen float
     * parameters by index and teaching the builder two new concepts, with a
     * changed sound as the cost of getting either wrong. If the builder grows
     * `inputEnabled` and `bypassed` later, this can move across -- and the
     * string here is the reference to check the result against.
     */
    private const val WIDE_STAGE =
        """{"buses":[""" +
            """{"gain":-0.0919491,"pan":0,"muted":false,"soloed":false,"inputEnabled":true,"plugins":[{"type":23,"bypassed":true,"dryWet":1,"os":1,"params":[1,10.3143]},{"type":1,"bypassed":true,"dryWet":1,"os":1,"params":[-2.1135,4.39726,0]}]},""" +
            """{"gain":8.15997,"pan":0,"muted":false,"soloed":false,"inputEnabled":true,"plugins":[{"type":17,"bypassed":false,"dryWet":1,"os":1,"params":[0,2.81718,34.0753,94.4716,34.6712,0.05,68.7378,10863,386.575,0,100,100]},{"type":1,"bypassed":false,"dryWet":1,"os":1,"params":[-13.8023,0,0]},{"type":0,"bypassed":false,"dryWet":1,"os":1,"params":[5.59187]}]},""" +
            """{"gain":0,"pan":0,"muted":false,"soloed":false,"inputEnabled":false,"plugins":[]},""" +
            """{"gain":0,"pan":0,"muted":false,"soloed":false,"inputEnabled":false,"plugins":[]},""" +
            """{"gain":4.61484,"pan":0,"muted":false,"soloed":false,"inputEnabled":false,"plugins":[]}""" +
            """]}"""

    val presets: List<MixPreset> = listOf(
        builtIn(-1L, "Concert Hall") {
            bus(0) {
                plugin(
                    SnapinType.REVERB,
                    ReverbP.PRE_DELAY to 40f,
                    ReverbP.DECAY to 6f,
                    ReverbP.SIZE to 92f,
                    ReverbP.DAMPING to 38f,
                    ReverbP.DIFFUSION to 88f,
                    ReverbP.TONE to 9000f,
                    ReverbP.EARLY_LATE to 70f,
                    ReverbP.WIDTH to 100f,
                    ReverbP.MIX to 45f,
                )
            }
        },

        builtIn(-2L, "Stadium Delay") {
            bus(0) {
                plugin(
                    SnapinType.DELAY,
                    DelayP.TIME to 380f,
                    DelayP.FEEDBACK to 46f,
                    DelayP.PING_PONG to 1f,
                    DelayP.FB_HICUT to 6000f,
                    DelayP.MIX to 35f,
                )
                plugin(
                    SnapinType.REVERB,
                    ReverbP.DECAY to 4f,
                    ReverbP.SIZE to 75f,
                    ReverbP.MIX to 28f,
                )
            }
        },

        builtIn(-3L, "Dream Chorus") {
            bus(0) {
                plugin(
                    SnapinType.CHORUS,
                    ChorusP.RATE to 0.45f,
                    ChorusP.DEPTH to 70f,
                    ChorusP.VOICES to 5f,
                    ChorusP.SPREAD to 85f,
                    ChorusP.MIX to 55f,
                )
                plugin(
                    SnapinType.REVERB,
                    ReverbP.DECAY to 3.5f,
                    ReverbP.SIZE to 65f,
                    ReverbP.TONE to 7000f,
                    ReverbP.MIX to 26f,
                )
            }
        },

        builtIn(-4L, "Lo-Fi Crunch") {
            bus(0) {
                plugin(
                    SnapinType.DISTORTION,
                    DistortionP.DRIVE to 22f,
                    DistortionP.TYPE to 5f,
                    DistortionP.TONE to 4500f,
                    DistortionP.BIAS to 0.15f,
                    DistortionP.DYNAMICS to 35f,
                    DistortionP.OUTPUT to -4f,
                    DistortionP.MIX to 65f,
                )
            }
        },

        builtIn(-5L, "Wide & Warm") {
            bus(0) {
                plugin(
                    SnapinType.STEREO,
                    StereoP.MID_DB to 1f,
                    StereoP.WIDTH_DB to 4f,
                )
                plugin(
                    SnapinType.COMPRESSOR,
                    CompressorP.ATTACK to 25f,
                    CompressorP.RELEASE to 180f,
                    CompressorP.RATIO to 2.5f,
                    CompressorP.THRESHOLD to -20f,
                    CompressorP.KNEE to 8f,
                    CompressorP.MAKEUP to 3f,
                )
            }
        },

        builtIn(-6L, "Club Master") {
            master {
                plugin(
                    SnapinType.COMPRESSOR,
                    CompressorP.ATTACK to 15f,
                    CompressorP.RELEASE to 120f,
                    CompressorP.RATIO to 3f,
                    CompressorP.THRESHOLD to -16f,
                    CompressorP.KNEE to 6f,
                    CompressorP.MAKEUP to 4f,
                )
                plugin(
                    SnapinType.LIMITER,
                    LimiterP.INPUT_GAIN to 2f,
                    LimiterP.THRESHOLD to -1.5f,
                    LimiterP.RELEASE to 80f,
                    LimiterP.LOOKAHEAD to 5f,
                )
            }
        },

        builtInRaw(-8L, "Wide Stage", WIDE_STAGE),

        builtIn(-7L, "Vocal Air") {
            bus(0) {
                plugin(
                    SnapinType.REVERB,
                    ReverbP.PRE_DELAY to 25f,
                    ReverbP.DECAY to 1.8f,
                    ReverbP.SIZE to 45f,
                    ReverbP.DAMPING to 30f,
                    ReverbP.TONE to 12000f,
                    ReverbP.EARLY_LATE to 40f,
                    ReverbP.MIX to 24f,
                )
                plugin(
                    SnapinType.STEREO,
                    StereoP.WIDTH_DB to 2.5f,
                )
            }
        },
    )

    /** As [builtIn], for a preset carried as engine state JSON rather than built. */
    private fun builtInRaw(id: Long, name: String, stateJson: String): MixPreset =
        MixPreset(id = id, name = name, stateJson = stateJson, isCustom = false)

    private fun builtIn(id: Long, name: String, block: PresetScope.() -> Unit): MixPreset =
        MixPreset(
            id = id,
            name = name,
            stateJson = MixPresetBuilder.build(block),
            isCustom = false,
        )
}
