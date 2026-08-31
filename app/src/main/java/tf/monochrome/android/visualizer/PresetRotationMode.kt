package tf.monochrome.android.visualizer

/**
 * When the visualizer changes preset by itself.
 *
 * This was two switches that each claimed to do it. "Preset rotation" drove
 * projectM's own timer, "Auto-shuffle Presets" changed the preset on every new
 * track and drew the playlist in random order, and its description said it
 * rotated presets during playback -- which was the other one's job. Between
 * them there was no single answer to "stop changing my preset", because either
 * could still be doing it.
 *
 * One choice replaces both, and [Off] is one of its values rather than the
 * absence of two others.
 */
enum class PresetRotationMode(val key: String) {
    /** Nothing changes the preset. Picking one, and Next, still work. */
    Off("off"),

    /** projectM's timer, at the rotation-seconds setting. */
    Timer("timer"),

    /** A new preset for each track, and nothing in between. */
    Track("track");

    /** Whether anything changes the preset on its own. */
    val isRotating: Boolean get() = this != Off

    companion object {
        /** The historical behaviour, and what a fresh install gets. */
        val Default: PresetRotationMode = Timer

        fun fromKey(key: String?): PresetRotationMode =
            entries.firstOrNull { it.key == key } ?: Default

        /**
         * The single mode that best matches the pair of switches this replaced.
         *
         * The timer wins when both were on, which is what most installs had:
         * it is the continuous behaviour of the two, and the one whose absence
         * would be noticed. Kept a pure function so the mapping is pinned by a
         * test rather than living only in a DataStore read.
         */
        /**
         * What the player's two-state rotation chip should store.
         *
         * The chip is on/off over a three-state setting, so turning it back on
         * has to restore which rotating mode was chosen -- and [remembered] is
         * that choice, which is why it has to be something that outlived the
         * process. Held only in memory it reverts to [Default] on every launch,
         * so a listener on "Each track" who switched rotation off came back to
         * find the timer running instead.
         *
         * [Default] is the fallback for a [remembered] that is [Off] or absent:
         * turning rotation on has to start something rotating.
         */
        fun toggled(enabled: Boolean, remembered: PresetRotationMode): PresetRotationMode =
            when {
                !enabled -> Off
                remembered.isRotating -> remembered
                else -> Default
            }

        fun migratedFrom(timedRotation: Boolean, changeEachTrack: Boolean): PresetRotationMode =
            when {
                timedRotation -> Timer
                changeEachTrack -> Track
                else -> Off
            }
    }
}
