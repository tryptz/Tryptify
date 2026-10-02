package tf.monochrome.android.visualizer

/**
 * Bundled presets known to crash projectM, as paths relative to the preset
 * root. Never loaded: left out of the playlist and shown flagged in the
 * browser.
 *
 * Found by running every bundled preset through the same projectM 4.1.6 the
 * app builds, with the fixes in `app/src/main/cpp/projectm_patches/` applied,
 * on a headless GLES context under AddressSanitizer — see
 * `tools/preset_crash_scan/`. A preset is listed when its process died: a
 * signal, a memory error, an uncaught exception, or no frame for 30 seconds.
 *
 * Regenerate it whenever projectM, its patches or the preset pack change.
 * Presets that crash only one device's GPU driver cannot be found this way;
 * [PresetCrashGuard] flags those on the device itself.
 */
internal object KnownCrashPresets {
    val paths: Set<String> = setOf(
    )
}
