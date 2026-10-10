package tf.monochrome.android.audio.eq

import kotlinx.serialization.Serializable
import tf.monochrome.android.audio.pipeline.OutputKind

/**
 * The kinds of output an AutoEQ preset can be assigned to, Poweramp-style.
 * HDMI, a call's hands-free link and anything unrecognised have none: a
 * headphone correction has no business on a TV, and nobody assigns one there.
 */
enum class OutputSlot {
    SPEAKER,
    WIRED,
    BLUETOOTH,
    USB;

    /**
     * Whether devices in this slot can be told apart by name. A Bluetooth
     * headphone and a USB DAC report their own; wired headphones report
     * nothing Android can tell apart, and the speaker reports the phone.
     */
    val named: Boolean get() = this == BLUETOOTH || this == USB
}

/**
 * An output as the per-device EQ knows it: a slot, plus the device's name for
 * the slots whose devices have one. Without a name it stands for the whole
 * slot — "Bluetooth" rather than one headphone.
 */
@Serializable
data class OutputId(val slot: OutputSlot, val name: String? = null) {
    /** Its key in the stored assignments. */
    val key: String get() = if (name == null) "slot:$slot" else "device:$slot:$name"

    /** The whole slot this device belongs to. */
    val generic: OutputId get() = if (name == null) this else OutputId(slot)

    companion object {
        /** The output a stored [key] stands for, or null for one this build does not know. */
        fun fromKey(key: String): OutputId? {
            val parts = key.split(":", limit = 3)
            val slot = parts.getOrNull(1)?.let { s -> OutputSlot.entries.firstOrNull { it.name == s } }
                ?: return null
            return when {
                parts[0] == "slot" && parts.size == 2 -> OutputId(slot)
                // The name is everything after the slot, colons and all.
                parts[0] == "device" && parts.size == 3 && parts[2].isNotEmpty() -> OutputId(slot, parts[2])
                else -> null
            }
        }
    }
}

/**
 * Which AutoEQ preset each output plays through. Assignments are explicit: an
 * output nobody assigned leaves the EQ as it is.
 *
 * Pure, so the rules are pinned by unit tests rather than by a phone with
 * three headphones paired to it.
 */
object OutputEq {
    /**
     * The preset id that means "no correction": an output assigned it turns
     * the EQ off. Without it, explicit assignments could only ever switch a
     * correction on, so headphones' correction stayed on the speaker after
     * they were taken off. Not a real preset id: those are UUIDs.
     */
    const val EQ_OFF = "\u0000off"

    /** Named devices remembered for the assign sheet; the oldest drop off. */
    const val MAX_KNOWN_DEVICES = 24

    /**
     * The output a routed device is, or null for one with no slot. The name
     * counts only where the slot is told apart by name, and a blank one is no
     * name at all — that device falls back to its slot.
     */
    fun idFor(kind: OutputKind, productName: String?): OutputId? {
        val slot = when (kind) {
            OutputKind.SPEAKER -> OutputSlot.SPEAKER
            OutputKind.WIRED -> OutputSlot.WIRED
            OutputKind.BLUETOOTH_CLASSIC, OutputKind.BLUETOOTH_LE -> OutputSlot.BLUETOOTH
            OutputKind.USB -> OutputSlot.USB
            OutputKind.BLUETOOTH_SCO, OutputKind.HDMI, OutputKind.OTHER -> return null
        }
        return OutputId(slot, productName?.trim()?.takeIf { slot.named && it.isNotEmpty() })
    }

    /**
     * The preset [output] plays through: its own, or else its slot's. A named
     * device's assignment overrides its slot's, so one pair of headphones can
     * have a correction of its own while every other Bluetooth device shares one.
     */
    fun resolve(output: OutputId, assignments: Map<String, String>): String? =
        assignments[output.key] ?: assignments[output.generic.key]

    /**
     * [assignments] after the assign sheet's OK for [presetId]: exactly the
     * outputs in [outputs] play it. Each one is taken from whatever it played
     * before, since an output plays one preset; outputs left unticked that
     * played it are let go; every other output keeps its own.
     */
    fun assign(
        assignments: Map<String, String>,
        presetId: String,
        outputs: Set<OutputId>,
    ): Map<String, String> {
        val kept = assignments.filterValues { it != presetId }
        return kept + outputs.associate { it.key to presetId }
    }

    /** [assignments] without [presetId], which was deleted. */
    fun forget(assignments: Map<String, String>, presetId: String): Map<String, String> =
        assignments.filterValues { it != presetId }

    /**
     * [known] with [seen] moved to the front, if it is a named device. Most
     * recent first, so the devices you actually use stay in the sheet and a
     * friend's speaker paired once drops off the end eventually.
     */
    fun remember(known: List<OutputId>, seen: OutputId, max: Int = MAX_KNOWN_DEVICES): List<OutputId> {
        if (seen.name == null) return known
        return (listOf(seen) + known.filter { it != seen }).take(max)
    }
}
