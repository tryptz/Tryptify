package tf.monochrome.android.audio.usb

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The volume of the exclusive USB path, which [LibusbAudioSink] applies to
 * PCM before handing it to libusb.
 *
 * It exists because that path skips AudioFlinger entirely: Android's own
 * volume, the keys and the system slider only steer STREAM_MUSIC, which never
 * reaches a DAC driven over libusb. Without this the DAC played at whatever
 * level it was left at, full scale on most, which is the "dangerous startup
 * sound levels" a review of the app warned about.
 *
 * Two inputs, multiplied on the audio thread by [getVolume]:
 *  - the DAC level, in decibels ([levelDb]): what the glass volume slider and
 *    the volume keys set. Decibels because hearing is logarithmic: a linear
 *    0..1 put most of its travel within a few dB of full and made the bottom
 *    steps the loud ones. Every session starts at [SAFE_START_DB] (see
 *    [startSession]) and is never saved, so a DAC never opens at full scale.
 *  - the player's own gain ([setVolume]): crossfade ramps and the like, as
 *    PlaybackService has always pushed it.
 *
 * At 0 dB with the player at unity the sink passes PCM through untouched, so
 * bit-perfect output stays bit-perfect.
 *
 * Writes are single stores to volatile fields and reads single loads: the
 * audio thread never locks or allocates to read the volume.
 */
@Singleton
class BypassVolumeController @Inject constructor() {
    @Volatile
    private var playerGain: Float = 1.0f

    @Volatile
    private var dacGain: Float = dbToGain(SAFE_START_DB)

    private val _levelDb = MutableStateFlow(SAFE_START_DB)
    /** The DAC level the slider shows: [MIN_DB] (muted) up to 0 dB. */
    val levelDb: StateFlow<Float> = _levelDb.asStateFlow()

    // The level a mute came from, for unmuting to. Here rather than in a
    // control so every way in agrees: the bar, the pop-up, the system panel.
    private var beforeMuteDb: Float = SAFE_START_DB

    private val _keyPresses = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** A volume key changed the level: the cue for the glass pop-up to show. */
    val keyPresses: SharedFlow<Unit> = _keyPresses.asSharedFlow()

    /** The player's own gain (crossfade, ramps), 0..1. Boost above unity is not offered: the DAC has no headroom past full scale. */
    fun setVolume(v: Float) {
        playerGain = v.coerceIn(0f, 1f)
    }

    /** The linear gain the sink applies: the DAC level times the player's gain. */
    fun getVolume(): Float = playerGain * dacGain

    /**
     * The DAC level alone, as linear gain: for the crossfade's outgoing tail,
     * which carries the player's gain in its own mix but still has to sit
     * under the DAC level, or a blend would play the old song louder.
     */
    fun getDacGain(): Float = dacGain

    /** Sets the DAC level, clamped to [MIN_DB]..0 dB; [MIN_DB] is silence. */
    fun setLevelDb(db: Float) {
        val clamped = db.coerceIn(MIN_DB, 0f)
        dacGain = dbToGain(clamped)
        _levelDb.value = clamped
    }

    /** One volume key press up (+1) or down (-1): [STEP_DB] each. */
    fun stepLevel(steps: Int) {
        setLevelDb(stepFrom(_levelDb.value, steps))
        _keyPresses.tryEmit(Unit)
    }

    /**
     * Mutes, remembering the level; unmuting goes back to it, never higher.
     * Muting again while muted keeps the level it first came from.
     */
    fun setMuted(muted: Boolean) {
        val now = _levelDb.value
        if (muted) {
            if (now > MIN_DB) beforeMuteDb = now
            setLevelDb(MIN_DB)
        } else if (now <= MIN_DB) {
            setLevelDb(beforeMuteDb)
        }
    }

    /**
     * A DAC was just claimed for exclusive output: start quiet. Whatever the
     * last session ended at — a desk amplifier's level is far too loud for
     * an earphone dongle — the next one begins at [SAFE_START_DB], and the
     * sink fades it in from silence. An unmute is no way around that either.
     */
    fun startSession() {
        beforeMuteDb = SAFE_START_DB
        setLevelDb(SAFE_START_DB)
    }

    companion object {
        /** The bottom of the scale, which is silence. */
        const val MIN_DB = -60f
        /** Where every session starts: quiet on sensitive earphones, audible on a desk amplifier. */
        const val SAFE_START_DB = -24f
        /** One volume key press. 30 steps from silence to full, like a phone's own volume. */
        const val STEP_DB = 2f

        /** dB to linear gain; [MIN_DB] and below are silence. */
        fun dbToGain(db: Float): Float = if (db <= MIN_DB) 0f else 10f.pow(db / 20f)

        /** Linear gain to dB, for showing a level; silence is [MIN_DB]. */
        fun gainToDb(gain: Float): Float =
            if (gain <= 0f) MIN_DB else (20f * log10(gain)).coerceIn(MIN_DB, 0f)

        /** A slider position (0..1) as a level: even steps in dB, the left end silent. */
        fun positionToDb(position: Float): Float = MIN_DB + position.coerceIn(0f, 1f) * -MIN_DB

        /** A level as a slider position (0..1). */
        fun dbToPosition(db: Float): Float = ((db - MIN_DB) / -MIN_DB).coerceIn(0f, 1f)

        /**
         * [steps] key presses from [db], landing on whole [STEP_DB] marks so a
         * level the slider left at -23.4 dB goes to -22 then -20, not -21.4.
         */
        fun stepFrom(db: Float, steps: Int): Float {
            if (steps == 0) return db
            val marks = db / STEP_DB
            // The mark at or below the level when going up, at or above when
            // going down; a level already on a mark is its own.
            val from = if (steps > 0) floor(marks + 1e-3f) else ceil(marks - 1e-3f)
            return ((from + steps) * STEP_DB).coerceIn(MIN_DB, 0f)
        }
    }
}
