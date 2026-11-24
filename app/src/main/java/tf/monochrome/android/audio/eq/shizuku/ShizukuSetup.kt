package tf.monochrome.android.audio.eq.shizuku

/**
 * What the settings screen needs to lead a listener to Shizuku: the app is
 * someone else's, started outside this one, and nothing here can install or
 * start it. So the first time the option is turned on, the steps are shown
 * before the switch moves.
 */
object ShizukuSetup {
    /** The Shizuku app's package. */
    const val PACKAGE = "moe.shizuku.privileged.api"

    /** Where the Shizuku app is had when it is not on the phone: its own releases. */
    const val DOWNLOAD_URL = "https://github.com/RikkaApps/Shizuku/releases"

    /**
     * Whether a move of the switch shows the steps first instead of moving
     * it: only turning it on, and only until the steps were once read to
     * the end ([seen]; null while that is not known yet, which shows
     * nothing: twice is worse than not at all, and the line under the
     * switch opens them again). Turning it off is never held up.
     */
    fun showsStepsFirst(turningOn: Boolean, seen: Boolean?): Boolean = turningOn && seen == false
}
