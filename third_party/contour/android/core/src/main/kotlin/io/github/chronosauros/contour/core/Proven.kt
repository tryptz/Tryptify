package io.github.chronosauros.contour.core

/** Which build the app is: the release (and beta) code only does what is proven on hardware, the review ("perf") and debug builds also do what is not proven yet. */
enum class BuildChannel { RELEASE, PERF }

/**
 * THE gate of every write, reset and widened limit that no unit has proven yet. One policy, one function:
 *
 *   `Proven.on(flag)` = the feature's own flag is true (proven on hardware), or the build is a review build.
 *
 * The flags stay where the features are and stay the single source of what is proven: [ProtocolMicro.PASS_FILTERS_VERIFIED], [ProtocolMicro.MICRO_POSITIVE_PREAMP_VERIFIED], [ProtocolMicro.ECHO_A_POSITIVE_PREAMP_VERIFIED],
 * [ProtocolTrn.PASS_FILTERS_VERIFIED] (LP and HP on the TRN Black Pearl), [io.github.chronosauros.contour.core.native.MoondropCatalog.PASS_FILTERS_VERIFIED] (LP and HP on the Dawn Pro 2), [io.github.chronosauros.contour.core.native.MoondropCatalog.SHELVES_VERIFIED] (LS and HS on the Dawn Pro 2),
 * [io.github.chronosauros.contour.core.native.SnowSkyMelody.PASS_FILTERS_VERIFIED] (LP, HP, BP and AP on the SnowSky Melody),
 * [io.github.chronosauros.contour.core.native.FiioKa15.PASS_FILTERS_VERIFIED] (LP, HP, BP and AP on the FiiO KA15),
 * [io.github.chronosauros.contour.core.native.WalkPlayProfiles.ECHO_A_HARDWARE_VERIFIED] (the profile's `hardwareVerified`), a model's `hardwareVerified` (Dawn Pro 2, Allegro Mini EQ
 * writes), [io.github.chronosauros.contour.core.native.DacSetting.writeVerified] (every settings row), [io.github.chronosauros.contour.core.native.FiioReset.VERIFIED] and
 * [io.github.chronosauros.contour.core.native.WalkPlayReset.VERIFIED] (the factory resets). A code path asks `Proven.on(flag)` and never looks at the build type itself, so a
 * flag turning true on its own is the only way a release build starts to send something new.
 *
 * The app sets [channel] once, first thing in `Application.onCreate`, from its build type ([channelFor]); everything else (the bench, the tests) is a release.
 * A feature that is on only because the build is a review build keeps its "not verified on hardware" wording: the gate decides whether it runs, not what it is called.
 * A release never shows that wording (owner 27.10.2026): it offers what is proven and stays silent about the rest.
 */
object Proven {
    @Volatile
    var channel: BuildChannel = BuildChannel.RELEASE

    /** The channel of an app build type: "perf" (Contour Private) and "debug" are review builds; "release", "beta" and anything unknown are releases. */
    fun channelFor(buildType: String): BuildChannel = if (buildType == "perf" || buildType == "debug") BuildChannel.PERF else BuildChannel.RELEASE

    /** The decision for [channel]: proven features always, unproven ones in a review build only. */
    fun allows(proven: Boolean, channel: BuildChannel): Boolean = proven || channel == BuildChannel.PERF

    /** The decision for this process: true when [proven] is true or this is a review build. */
    fun on(proven: Boolean): Boolean = allows(proven, channel)

    /** Runs [block] on [ch] and puts the channel back (tests). */
    internal inline fun <T> asChannel(ch: BuildChannel, block: () -> T): T {
        val before = channel
        channel = ch
        try { return block() } finally { channel = before }
    }
}
