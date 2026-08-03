package tf.monochrome.android.audio.dsp.model

import kotlinx.serialization.Serializable
import tf.monochrome.android.audio.dsp.SnapinType

@Serializable
data class PluginInstance(
    val slotIndex: Int,
    val typeOrdinal: Int,
    val bypassed: Boolean = false,
    val dryWet: Float = 1f,
    val parameters: Map<Int, Float> = emptyMap(),
    /** Per-plugin oversampling factor: 1 (off), 2, or 4. */
    val oversampling: Int = 1
) {
    val type: SnapinType?
        get() = SnapinType.fromOrdinal(typeOrdinal)

    val displayName: String
        get() = type?.displayName ?: "Unknown"
}
