package tf.monochrome.android.audio.dsp.preset

import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.MixPreset

/**
 * Hard-coded showcase presets for the DSP Mixer.
 *
 * These ship with the app so a fresh install demonstrates the engine's reverb,
 * delay, modulation and dynamics processors. They use negative ids so they
 * never collide with Room's positive autoincrement, and `isCustom = false` so
 * the UI treats them as read-only (load, export — but not delete).
 *
 * ## Laid out the way a mastering engineer patches a desk
 *
 * Every stereo preset is built on the same console, wired with the mixer's
 * routes instead of one long chain on one bus:
 *
 *  - **Bus 1 — Source.** Takes the track. Corrective work only (a subsonic
 *    high-pass), and it does not go to the master itself: it feeds Tone and
 *    the effect sends, so everything downstream hears the cleaned signal.
 *  - **Bus 2 — Tone.** The dry path: broad tonal EQ, then stereo width —
 *    corrective before dynamics, tonal after, width late, in the usual order.
 *  - **Buses 3 and 4 — Returns.** Effects on aux returns, 100 % wet, fed by
 *    sends: reverbs high-passed at 200–300 Hz so no low end builds up in the
 *    tail, with pre-delay to keep the source articulate. A return can feed
 *    another (a delay into a reverb).
 *  - **Master — fader at 0 dB.** Glue compression only where the preset is
 *    about dynamics, and always last a limiter with its ceiling at −1 dB, the
 *    usual true-peak margin, to catch what EQ boosts and wet returns add.
 *    Level is made inside the chain, never by riding the master fader.
 *
 * The engine aligns every route for delay, so the dry and wet paths meet in
 * time however they are wired.
 *
 * Wide sources: with "spread channels" on, a 5.1 or Atmos song lands its
 * channel groups on buses 1–9 (centre on 2, LFE on 3, …), the same strips
 * these presets use as Tone and Returns. They are made for stereo; for a
 * wide song, spread off runs it all through bus 1 as designed.
 *
 * ## Atmos Upmix
 *
 * The one preset that is not stereo: it turns on the upmix, which spreads a
 * stereo song to 9.1.6 ahead of the mixer (StereoUpmixer), so its nine
 * channel groups arrive on buses 1–9 and each strip shapes one of them.
 */
object BuiltInMixPresets {

    // The console every stereo preset is patched on (engine indices).
    private const val SOURCE = 0
    private const val TONE = 1
    private const val RETURN_A = 2
    private const val RETURN_B = 3
    private const val MASTER = BusConfig.MASTER_INDEX

    val presets: List<MixPreset> = listOf(
        // A big hall behind the mix: the source untouched in front, a long
        // dark hall on a return. Send −3 dB, the hall tuned for size, not mud.
        builtIn(-1L, "Concert Hall") {
            source(hall = 0.71f)
            tone {
                plugin(
                    SnapinType.EQ_3BAND,
                    Eq3P.LOW_FREQ to 120f, Eq3P.LOW_GAIN to 0.5f,
                    Eq3P.HIGH_FREQ to 10000f, Eq3P.HIGH_GAIN to 1f,
                )
            }
            bus(RETURN_A) {
                inputEnabled = false
                plugin(
                    SnapinType.REVERB,
                    ReverbP.PRE_DELAY to 40f,
                    ReverbP.DECAY to 6f,
                    ReverbP.SIZE to 92f,
                    ReverbP.DAMPING to 38f,
                    ReverbP.DIFFUSION to 88f,
                    ReverbP.TONE to 9000f,
                    ReverbP.LOW_CUT to 250f,
                    ReverbP.EARLY_LATE to 70f,
                    ReverbP.WIDTH to 100f,
                    ReverbP.MIX to 100f,
                )
            }
            safetyMaster()
        },

        // The delay is a return of its own, and it feeds the reverb return
        // as well, so the repeats sit in the same room as the source.
        builtIn(-2L, "Stadium Delay") {
            bus(SOURCE) {
                cleanup()
                sendTo(MASTER, 0f)
                sendTo(TONE, 1f)
                sendTo(RETURN_A, 0.5f)
                sendTo(RETURN_B, 0.35f)
            }
            tone {}
            bus(RETURN_A) {
                inputEnabled = false
                plugin(
                    SnapinType.DELAY,
                    DelayP.TIME to 380f,
                    DelayP.FEEDBACK to 46f,
                    DelayP.PING_PONG to 1f,
                    DelayP.FB_LOWCUT to 200f,
                    DelayP.FB_HICUT to 6000f,
                    DelayP.MIX to 100f,
                )
                sendTo(RETURN_B, 0.5f)
            }
            bus(RETURN_B) {
                inputEnabled = false
                plugin(
                    SnapinType.REVERB,
                    ReverbP.PRE_DELAY to 30f,
                    ReverbP.DECAY to 4f,
                    ReverbP.SIZE to 75f,
                    ReverbP.LOW_CUT to 250f,
                    ReverbP.MIX to 100f,
                )
            }
            safetyMaster()
        },

        // Chorus as a send, not an insert, so the source keeps its focus and
        // the shimmer sits beside it; the chorus return feeds a soft room.
        builtIn(-3L, "Dream Chorus") {
            bus(SOURCE) {
                cleanup()
                sendTo(MASTER, 0f)
                sendTo(TONE, 1f)
                sendTo(RETURN_A, 0.8f)
                sendTo(RETURN_B, 0.3f)
            }
            tone {}
            bus(RETURN_A) {
                inputEnabled = false
                plugin(
                    SnapinType.CHORUS,
                    ChorusP.RATE to 0.45f,
                    ChorusP.DEPTH to 70f,
                    ChorusP.VOICES to 5f,
                    ChorusP.SPREAD to 85f,
                    ChorusP.MIX to 100f,
                )
                sendTo(RETURN_B, 0.4f)
            }
            bus(RETURN_B) {
                inputEnabled = false
                plugin(
                    SnapinType.REVERB,
                    ReverbP.DECAY to 3.5f,
                    ReverbP.SIZE to 65f,
                    ReverbP.TONE to 7000f,
                    ReverbP.LOW_CUT to 300f,
                    ReverbP.MIX to 100f,
                )
            }
            safetyMaster()
        },

        // Parallel distortion: the clean path dulled a little, the crushed
        // path band-limited on its return and blended under it, so the low
        // end and the transients stay clean while the grit fills the mids.
        builtIn(-4L, "Lo-Fi Crunch") {
            bus(SOURCE) {
                cleanup()
                sendTo(MASTER, 0f)
                sendTo(TONE, 1f)
                sendTo(RETURN_A, 1f)
            }
            tone {
                plugin(
                    SnapinType.EQ_3BAND,
                    Eq3P.HIGH_FREQ to 6000f, Eq3P.HIGH_GAIN to -4f,
                )
            }
            bus(RETURN_A, gainDb = -3f) {
                inputEnabled = false
                plugin(
                    SnapinType.DISTORTION,
                    DistortionP.DRIVE to 22f,
                    DistortionP.TYPE to 5f,
                    DistortionP.TONE to 4500f,
                    DistortionP.BIAS to 0.15f,
                    DistortionP.DYNAMICS to 35f,
                    DistortionP.OUTPUT to -4f,
                    DistortionP.MIX to 100f,
                )
                plugin(
                    SnapinType.FILTER,
                    FilterP.TYPE to FilterP.HIGH_PASS,
                    FilterP.CUTOFF to 200f,
                    FilterP.SLOPE to FilterP.SLOPE_24,
                )
            }
            safetyMaster()
        },

        // Warmth on the tone bus, width after it, and the glue where glue
        // belongs: on the master, gentle, before the limiter.
        builtIn(-5L, "Wide & Warm") {
            source()
            tone {
                plugin(
                    SnapinType.EQ_3BAND,
                    Eq3P.LOW_FREQ to 120f, Eq3P.LOW_GAIN to 1.5f,
                    Eq3P.MID_FREQ to 3500f, Eq3P.MID_GAIN to -1f, Eq3P.MID_Q to 0.8f,
                    Eq3P.HIGH_FREQ to 12000f, Eq3P.HIGH_GAIN to -0.5f,
                )
                plugin(
                    SnapinType.STEREO,
                    StereoP.MID_DB to 1f,
                    StereoP.WIDTH_DB to 4f,
                )
            }
            master {
                glue(ratio = 2f, thresholdDb = -20f, attackMs = 25f, releaseMs = 180f, makeupDb = 2f)
                ceiling()
            }
        },

        // A club master. Mud out and weight in on the tone bus; a New York
        // parallel compressor on a return, slammed and tucked in underneath;
        // then glue and the limiter, driven a little, on the master.
        builtIn(-6L, "Club Master") {
            bus(SOURCE) {
                cleanup()
                sendTo(MASTER, 0f)
                sendTo(TONE, 1f)
                sendTo(RETURN_A, 1f)
            }
            tone {
                plugin(
                    SnapinType.EQ_3BAND,
                    Eq3P.LOW_FREQ to 80f, Eq3P.LOW_GAIN to 2f,
                    Eq3P.MID_FREQ to 350f, Eq3P.MID_GAIN to -1.5f, Eq3P.MID_Q to 1f,
                    Eq3P.HIGH_FREQ to 10000f, Eq3P.HIGH_GAIN to 1.5f,
                )
            }
            // Blended back at about −8 dB, the usual home for a parallel bus.
            bus(RETURN_A, gainDb = -8f) {
                inputEnabled = false
                plugin(
                    SnapinType.COMPRESSOR,
                    CompressorP.ATTACK to 1f,
                    CompressorP.RELEASE to 60f,
                    CompressorP.RATIO to 10f,
                    CompressorP.THRESHOLD to -30f,
                    CompressorP.KNEE to 2f,
                    CompressorP.MAKEUP to 10f,
                )
            }
            master {
                glue(ratio = 3f, thresholdDb = -16f, attackMs = 15f, releaseMs = 120f, makeupDb = 2f)
                ceiling(inputGainDb = 2f, releaseMs = 80f)
            }
        },

        // Captured out of the mixer, its values written out whole. Rewired
        // onto the console: it was a dry bus and a second bus taking the
        // track in parallel, with the master run up +4.6 dB. Now the source
        // sends to the wet bus, the master sits at 0 dB like every other
        // preset, and the limiter guards the ceiling.
        builtIn(-8L, "Wide Stage") {
            bus(SOURCE, gainDb = -0.0919491f) {
                pluginWithParams(SnapinType.HAAS, floatArrayOf(1f, 10.3143f), bypassed = true)
                pluginWithParams(SnapinType.STEREO, floatArrayOf(-2.1135f, 4.39726f, 0f), bypassed = true)
                sendTo(RETURN_A, 1f)
            }
            bus(RETURN_A, gainDb = 8.15997f) {
                inputEnabled = false
                pluginWithParams(
                    SnapinType.REVERB,
                    floatArrayOf(0f, 2.81718f, 34.0753f, 94.4716f, 34.6712f, 0.05f, 68.7378f, 10863f, 386.575f, 0f, 100f, 100f),
                )
                pluginWithParams(SnapinType.STEREO, floatArrayOf(-13.8023f, 0f, 0f))
                pluginWithParams(SnapinType.GAIN, floatArrayOf(5.59187f))
            }
            safetyMaster()
        },

        // Air and presence on the tone bus, mud out; a short bright plate on
        // a return with enough pre-delay that the vocal stays in front of it.
        builtIn(-7L, "Vocal Air") {
            source(hall = 0.5f)
            tone {
                plugin(
                    SnapinType.EQ_3BAND,
                    Eq3P.LOW_FREQ to 150f, Eq3P.LOW_GAIN to 0f,
                    Eq3P.MID_FREQ to 300f, Eq3P.MID_GAIN to -1.5f, Eq3P.MID_Q to 1.2f,
                    Eq3P.HIGH_FREQ to 12000f, Eq3P.HIGH_GAIN to 2.5f,
                )
                plugin(
                    SnapinType.STEREO,
                    StereoP.WIDTH_DB to 2.5f,
                )
            }
            bus(RETURN_A) {
                inputEnabled = false
                plugin(
                    SnapinType.REVERB,
                    ReverbP.PRE_DELAY to 25f,
                    ReverbP.DECAY to 1.8f,
                    ReverbP.SIZE to 45f,
                    ReverbP.DAMPING to 30f,
                    ReverbP.TONE to 12000f,
                    ReverbP.LOW_CUT to 300f,
                    ReverbP.EARLY_LATE to 40f,
                    ReverbP.MIX to 100f,
                )
            }
            safetyMaster()
        },

        // Stereo spread to a 9.1.6 bed: the upmix makes the channels, the
        // strips finish them. Each bus holds one channel group:
        //   1 Front  2 Centre  3 LFE  4 Rear  5 Front Wide  6 Side
        //   7 Top Front  8 Top Rear  9 Top Side
        // Restrained on purpose — the upmix is built to fold back to the
        // song, and every dB here moves the fold with it.
        builtIn(-9L, "Atmos Upmix 9.1.6") {
            upmix = true
            // Front: the anchor. Only the subsonic cleanup.
            bus(UP_FRONT) { cleanup() }
            // Centre: a touch of presence so the lead sits forward.
            bus(UP_CENTRE) {
                plugin(
                    SnapinType.EQ_3BAND,
                    Eq3P.MID_FREQ to 2500f, Eq3P.MID_GAIN to 1f, Eq3P.MID_Q to 0.8f,
                )
            }
            // LFE: bass management, steeper than the upmix's own split, so
            // nothing above the crossover reaches the sub.
            bus(UP_LFE) {
                plugin(
                    SnapinType.FILTER,
                    FilterP.TYPE to FilterP.LOW_PASS,
                    FilterP.CUTOFF to 120f,
                    FilterP.SLOPE to FilterP.SLOPE_24,
                )
            }
            // Surround ring: the further back, the duller — air absorption
            // is how a room says "behind you".
            bus(UP_FRONT_WIDE) { distance(highShelfDb = -0.5f) }
            bus(UP_SIDE) { distance(highShelfDb = -1f) }
            bus(UP_REAR) { distance(highShelfDb = -2f) }
            // Heights: no low end overhead, and a lift around 8 kHz, the band
            // the ear reads as "above".
            for (top in listOf(UP_TOP_FRONT, UP_TOP_SIDE, UP_TOP_REAR)) {
                bus(top) {
                    plugin(
                        SnapinType.FILTER,
                        FilterP.TYPE to FilterP.HIGH_PASS,
                        FilterP.CUTOFF to 200f,
                    )
                    plugin(
                        SnapinType.EQ_3BAND,
                        Eq3P.HIGH_FREQ to 8000f, Eq3P.HIGH_GAIN to 1.5f,
                    )
                }
            }
            // Linked across the whole bed: every channel is turned down
            // together, so the image never shifts under the limiter.
            safetyMaster()
        },
    )

    // The 9.1.6 groups' buses: group k on bus k + 1 (engine index skips 4).
    private const val UP_FRONT = 0
    private const val UP_CENTRE = 1
    private const val UP_LFE = 2
    private const val UP_REAR = 3
    private const val UP_FRONT_WIDE = 5
    private const val UP_SIDE = 6
    private const val UP_TOP_FRONT = 7
    private const val UP_TOP_REAR = 8
    private const val UP_TOP_SIDE = 9

    /** The Atmos upmix preset's id, for the tests. */
    internal const val ATMOS_UPMIX_ID = -9L

    // ── The console's standard pieces ─────────────────────────────────────

    /** Subsonic cleanup: 25 Hz, 24 dB/oct. Rumble nobody hears, headroom everybody pays for. */
    private fun BusScope.cleanup() = plugin(
        SnapinType.FILTER,
        FilterP.TYPE to FilterP.HIGH_PASS,
        FilterP.CUTOFF to 25f,
        FilterP.SLOPE to FilterP.SLOPE_24,
    )

    /**
     * Bus 1 as most presets want it: cleanup, then on to Tone at unity and,
     * if [hall] is above zero, to the first return at that send level.
     */
    private fun PresetScope.source(hall: Float = 0f) {
        bus(SOURCE) {
            cleanup()
            sendTo(MASTER, 0f)
            sendTo(TONE, 1f)
            if (hall > 0f) sendTo(RETURN_A, hall)
        }
    }

    /** Bus 2, fed by the source rather than the track, out to the master. */
    private fun PresetScope.tone(block: BusScope.() -> Unit) = bus(TONE) {
        inputEnabled = false
        block()
    }

    private fun BusScope.glue(ratio: Float, thresholdDb: Float, attackMs: Float, releaseMs: Float, makeupDb: Float) =
        plugin(
            SnapinType.COMPRESSOR,
            CompressorP.ATTACK to attackMs,
            CompressorP.RELEASE to releaseMs,
            CompressorP.RATIO to ratio,
            CompressorP.THRESHOLD to thresholdDb,
            CompressorP.KNEE to 6f,
            CompressorP.MAKEUP to makeupDb,
        )

    /** The last thing on every master: a limiter with its ceiling at −1 dB. */
    private fun BusScope.ceiling(inputGainDb: Float = 0f, releaseMs: Float = 100f) = plugin(
        SnapinType.LIMITER,
        LimiterP.INPUT_GAIN to inputGainDb,
        LimiterP.THRESHOLD to CEILING_DB,
        LimiterP.RELEASE to releaseMs,
        LimiterP.LOOKAHEAD to 5f,
    )

    /** Master at 0 dB with only the ceiling on it. */
    private fun PresetScope.safetyMaster() = master { ceiling() }

    /** A surround pair's distance cue: a gentle high-shelf cut. */
    private fun BusScope.distance(highShelfDb: Float) = plugin(
        SnapinType.EQ_3BAND,
        Eq3P.HIGH_FREQ to 6000f, Eq3P.HIGH_GAIN to highShelfDb,
    )

    /** The usual true-peak margin, so codecs downstream have room. */
    const val CEILING_DB = -1f

    private fun builtIn(id: Long, name: String, block: PresetScope.() -> Unit): MixPreset =
        MixPreset(
            id = id,
            name = name,
            stateJson = MixPresetBuilder.build(block),
            isCustom = false,
        )
}
