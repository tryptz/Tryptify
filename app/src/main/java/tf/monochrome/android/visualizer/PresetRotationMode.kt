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

        /**
         * The mode an install upgrading from the old build lands on.
         *
         * There was only ever one switch to read. "Auto-shuffle Presets"
         * gated the per-track roll; the timer ran for everybody and had no off
         * switch at all -- `projectm_set_preset_locked` was never called. An
         * earlier version of this took a `timedRotation` argument as well, read
         * from a DataStore key that had never existed, so it returned [Timer]
         * for everyone and quietly overrode the one setting there was.
         *
         * Switching auto-shuffle off is read here as "stop changing my preset",
         * so it lands on [Off]. That does take away a timer the listener never
         * had a way to decline, and it is deliberate: the off switch is the
         * only thing they ever said, and this takes them at their word.
         * [Timer] is therefore not reachable from migration, only from an
         * explicit choice in Settings.
         *
         * A pure function so the mapping is pinned by a test rather than living
         * only inside a DataStore read -- though the mapping was never the part
         * that broke. See `PreferencesManager.rotationModeFrom`, which is what
         * the test has to drive to catch a key that does not exist.
         */
        fun migratedFromAutoShuffle(changeEachTrack: Boolean): PresetRotationMode =
            if (changeEachTrack) Track else Off
    }
}
