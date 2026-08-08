package tf.monochrome.android.audio.input

/**
 * Lifecycle of the stereo line-in capture stream.
 *
 * Mirrors the shape of `UsbExclusiveController.Status`: the UI maps each state
 * to a specific sentence, so a failure reads as "the device reported no stereo
 * capture configuration" rather than one boilerplate error string.
 */
enum class StereoInputStatus {
    /** Feature off, or no device selected. */
    Idle,

    /** Enabled, but nothing usable is plugged in. */
    NoDevice,

    /** Enabled, but RECORD_AUDIO has not been granted yet. */
    PermissionRequired,

    /** AudioRecord is being built / started. */
    Starting,

    /** Capturing. */
    Running,

    /** Start or capture failed; see [StereoInputEngine.lastError]. */
    Error,
}

/** Why a capture attempt failed, in terms the Settings screen can act on. */
enum class StereoInputFailure {
    /** RECORD_AUDIO missing at the moment we tried to open the device. */
    PermissionDenied,

    /** The selected device disappeared between selection and start. */
    DeviceLost,

    /** AudioRecord.getMinBufferSize rejected the rate/channel/encoding triple. */
    UnsupportedFormat,

    /** AudioRecord constructed but did not reach STATE_INITIALIZED. */
    InitFailed,

    /** startRecording() threw, usually because another app holds the input exclusively. */
    StartFailed,

    /** The read loop got a hard error mid-stream. */
    ReadFailed,
}

/** What the capture stream actually negotiated, for the diagnostics card. */
data class StereoInputFormat(
    val sampleRate: Int,
    val channelCount: Int,
    /** `MediaRecorder.AudioSource` constant actually used. */
    val audioSource: Int,
) {
    val isStereo: Boolean get() = channelCount >= 2
}

/** How the input signal is mapped to the engine's stereo aux pair. */
enum class StereoInputChannelMode(val label: String, val blurb: String) {
    Stereo("Stereo", "Capture both channels — the normal choice for a line or interface input"),
    Mono("Mono", "Capture one channel and send it to both sides"),
}

/** Which pair of buses the mode switch pre-routes when the user starts line-in. */
enum class StereoInputMode(val key: String, val label: String, val blurb: String) {
    /** Line-in only. Playback is replaced by a silent carrier stream. */
    Live("live", "Live", "Hear only the input. Playback is replaced by the line-in stream."),

    /** Line-in on top of whatever is playing. */
    Mix("mix", "Mix with playback", "Hear the input alongside the current track."),
    ;

    companion object {
        fun fromKey(key: String?): StereoInputMode =
            entries.firstOrNull { it.key == key } ?: Live
    }
}
