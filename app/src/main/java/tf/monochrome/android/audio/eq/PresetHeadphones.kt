package tf.monochrome.android.audio.eq

import tf.monochrome.android.domain.model.EqPreset
import tf.monochrome.android.domain.model.Headphone
import tf.monochrome.android.domain.model.PresetEar
import tf.monochrome.android.domain.model.PresetHeadphone

/**
 * What an AutoEQ preset keeps of the headphone picker when it is saved, and
 * what the picker shows when it is loaded. Pure, so the rules are tested
 * without the screen (`PresetHeadphonesTest`); `EqViewModel.loadPreset` and
 * `PreferencesManager.applyEqPreset` (the per-device switch, with no screen
 * open) both follow [restore].
 */
internal object PresetHeadphones {

    /**
     * The [PresetHeadphone] to save with a preset: the picked [headphone] and
     * what each ear shows. [right] is null for a mono save. Null when there is
     * nothing to keep: no headphone, no label, no curve.
     */
    fun capture(headphone: Headphone?, left: PresetEar, right: PresetEar?): PresetHeadphone? {
        val named = headphone?.takeIf { it.name.isNotBlank() }
        if (named == null && left.isEmpty && (right == null || right.isEmpty)) return null
        return PresetHeadphone(
            id = named?.id.orEmpty(),
            name = named?.name.orEmpty(),
            left = left,
            right = right,
        )
    }

    /** What loading a preset puts in the picker and on the graph. */
    data class Restore(
        /** The picked headphone; null clears the picker. */
        val headphone: Headphone?,
        /** The left ear, or the only one in mono. Empty clears it. */
        val left: PresetEar,
        /** The right ear; null leaves the stored right ear as it is. */
        val right: PresetEar?,
    )

    /**
     * What loading [preset] shows, with 2-channel mode [stereoAfterLoad] (a
     * stereo preset turns it on). The right ear follows the bands' rule in
     * loadPreset: a stereo preset brings its own; a mono preset drives both
     * ears while 2-channel mode is on; with it off, the stored right ear is a
     * calibration kept for later and is not touched.
     *
     * A preset saved before headphones were kept has none, and clears the
     * picker and the curve rather than leave another headphone's there.
     */
    fun restore(preset: EqPreset, stereoAfterLoad: Boolean): Restore {
        val saved = preset.headphone
        val headphone = saved
            ?.takeIf { it.name.isNotBlank() }
            ?.let { Headphone(id = it.id.ifBlank { it.name }, name = it.name) }
        val left = saved?.left ?: PresetEar()
        val right = when {
            preset.bandsR != null -> saved?.right ?: PresetEar()
            stereoAfterLoad -> left
            else -> null
        }
        return Restore(headphone, left, right)
    }
}
