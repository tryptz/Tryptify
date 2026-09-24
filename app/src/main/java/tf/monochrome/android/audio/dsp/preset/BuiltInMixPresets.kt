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

        // Captured out of the mixer, so its values are written out whole rather
        // than as overrides. A dry path and a wet path run in parallel off the
        // same input -- which is why bus 2 takes input too:
        //   bus 1  the dry side, trimmed a hair, with a Haas and a Stereo parked
        //          on it bypassed (set up, switched off -- part of the patch)
        //   bus 2  the wet side: a long, wide Reverb, then Stereo, then Gain,
        //          run up +8.2 dB to sit against the dry
        //   master +4.6 dB
        builtIn(-8L, "Wide Stage") {
            bus(0, gainDb = -0.0919491f) {
                pluginWithParams(SnapinType.HAAS, floatArrayOf(1f, 10.3143f), bypassed = true)
                pluginWithParams(SnapinType.STEREO, floatArrayOf(-2.1135f, 4.39726f, 0f), bypassed = true)
            }
            bus(1, gainDb = 8.15997f) {
                inputEnabled = true
                pluginWithParams(
                    SnapinType.REVERB,
                    floatArrayOf(0f, 2.81718f, 34.0753f, 94.4716f, 34.6712f, 0.05f, 68.7378f, 10863f, 386.575f, 0f, 100f, 100f),
                )
                pluginWithParams(SnapinType.STEREO, floatArrayOf(-13.8023f, 0f, 0f))
                pluginWithParams(SnapinType.GAIN, floatArrayOf(5.59187f))
            }
            master(gainDb = 4.61484f)
        },

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

    private fun builtIn(id: Long, name: String, block: PresetScope.() -> Unit): MixPreset =
        MixPreset(
            id = id,
            name = name,
            stateJson = MixPresetBuilder.build(block),
            isCustom = false,
        )
}
