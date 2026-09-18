package tf.monochrome.ios

/**
 * Marks which platform the shared code is running on. Replaced per-layer as
 * real expect/actual platform plumbing (audio, library scanner) migrates in.
 */
expect fun platformName(): String
