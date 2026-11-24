package tf.monochrome.android.audio.eq.shizuku

/**
 * Where the Shizuku system-wide equalizer stands, for the settings row that
 * offers it and for everything that must know whether it is the one playing
 * other apps' audio ([ACTIVE]) or the standard system-wide effect still is.
 */
enum class ShizukuPeqState {
    /** Not asked for: the option, or system-wide itself, is off. */
    OFF,

    /** Asked for, on an Android older than 13: the capture it rests on is not there. */
    UNSUPPORTED,

    /** Asked for, and the Shizuku app is not on the phone. */
    NOT_INSTALLED,

    /** Shizuku is installed and not running (it is started again after each restart of the phone). */
    NOT_RUNNING,

    /** Shizuku is running and has not been told to let this app use it. */
    NEEDS_PERMISSION,

    /** Everything is in place and the service is being started. */
    STARTING,

    /** Other apps play through the parametric correction. */
    ACTIVE,

    /** It would not start, or stopped. The standard system-wide effect plays. */
    FAILED,
    ;

    companion object {
        /** The first Android the loopback capture is used on (Quve's own floor). */
        const val MIN_SDK = 33

        /**
         * The state for what is known, the first thing in the way first.
         * [wanted]: system-wide and the option are both on and there is a
         * curve to play. [serviceRunning] and [failed] are the service's own
         * word, and mean nothing until Shizuku is there and allowed.
         */
        fun resolve(
            wanted: Boolean,
            sdk: Int,
            installed: Boolean,
            binderAlive: Boolean,
            permitted: Boolean,
            serviceRunning: Boolean,
            failed: Boolean,
        ): ShizukuPeqState = when {
            !wanted -> OFF
            sdk < MIN_SDK -> UNSUPPORTED
            // Running without being found installed (a hidden profile, say) is running.
            !binderAlive -> if (installed) NOT_RUNNING else NOT_INSTALLED
            !permitted -> NEEDS_PERMISSION
            serviceRunning -> ACTIVE
            failed -> FAILED
            else -> STARTING
        }
    }
}
