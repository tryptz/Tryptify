package tf.monochrome.android.audio.usb

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide software volume that [LibusbAudioSink] applies to PCM
 * before handing it to libusb. Exists because the bypass path skips
 * AudioFlinger entirely — the `Player.volume` that the UI slider
 * drives never reaches the DAC, and the phone's hardware volume keys
 * (which only steer STREAM_MUSIC) have nothing to grab onto. This
 * gives the slider, the ReplayGain pass, and the hardware-key
 * dispatcher a single atomic to write into; the audio thread reads
 * it lock-free per handleBuffer tick.
 *
 * Range is clamped 0..1 — boost above unity is intentionally not
 * supported because the DAC has no headroom to give back if we
 * overflow PCM range, and gain >1 in software would just clip at the
 * sink. The slider already maxes at 1.0, so this matches.
 */
@Singleton
class BypassVolumeController @Inject constructor() {
    // @Volatile gives us atomic 32-bit reads/writes without the cost
    // of an AtomicReference<Float>. The audio thread's read is a
    // single load; the UI / RG / key threads' writes are single
    // stores. There's no read-modify-write here so we don't need
    // AtomicInteger / compareAndSet.
    @Volatile
    private var volume: Float = 1.0f

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
    }

    fun getVolume(): Float = volume
}
